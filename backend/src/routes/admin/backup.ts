import { constants } from 'node:fs';
import { access, open, stat } from 'node:fs/promises';
import { join } from 'node:path';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import type { Ctx } from '../../context.js';
import { q1, tx } from '../../db.js';
import { ApiError } from '../../errors.js';
import { audit } from '../../services/audit.js';
import { mustStaff, staffGuard } from '../../services/auth.js';

/*
 * Backups are taken by the separate `backup` container (scripts/backup.sh, pg_dump -Fc). The API never
 * touches the dumps: it only drops a `.request` file to ask for an immediate run and reads `status.json`.
 */

export const BACKUP_REQUEST_COOLDOWN_MS = 10 * 60_000;
const DEFAULT_KEEP_DAYS = 14;

export type BackupStatus = {
  lastAt: string | null;
  status: 'ok' | 'failed' | 'running' | 'never';
  sizeBytes: number | null;
  keepDays: number;
  requestedAt: string | null;
};

const StatusFile = z.object({
  lastAt: z.string().max(40).nullable(),
  status: z.enum(['ok', 'failed', 'running']),
  sizeBytes: z.number().int().min(0).nullable(),
  keepDays: z.number().int().min(1).max(3650),
});

async function dirExists(dir: string): Promise<boolean> {
  try {
    return (await stat(dir)).isDirectory();
  } catch {
    return false;
  }
}

/** Reads a small file without following it if it is huge (the backup container is trusted, but stay bounded). */
async function readSmall(path: string, max = 4096): Promise<string | null> {
  try {
    const fh = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
    try {
      const buf = Buffer.alloc(max);
      const { bytesRead } = await fh.read(buf, 0, max, 0);
      return buf.subarray(0, bytesRead).toString('utf8');
    } finally {
      await fh.close();
    }
  } catch {
    return null;
  }
}

const isIso = (s: string) => /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,3})?Z$/.test(s) && !Number.isNaN(Date.parse(s));

export async function readBackupStatus(dir: string): Promise<BackupStatus> {
  const never: BackupStatus = { lastAt: null, status: 'never', sizeBytes: null, keepDays: DEFAULT_KEEP_DAYS, requestedAt: null };
  if (!(await dirExists(dir))) return never;
  const reqText = (await readSmall(join(dir, '.request')))?.trim() ?? null;
  const requestedAt = reqText && isIso(reqText) ? reqText : null;
  const raw = await readSmall(join(dir, 'status.json'));
  if (!raw) return { ...never, requestedAt };
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return { ...never, requestedAt };
  }
  const r = StatusFile.safeParse(parsed);
  if (!r.success) return { ...never, requestedAt };
  return {
    lastAt: r.data.lastAt && isIso(r.data.lastAt) ? r.data.lastAt : null,
    status: r.data.status,
    sizeBytes: r.data.sizeBytes,
    keepDays: r.data.keepDays,
    requestedAt,
  };
}

export async function adminBackupRoutes(app: FastifyInstance, ctx: Ctx) {
  const settingsStaff = staffGuard(ctx, 'settings_staff');
  const dir = ctx.config.backupDir;

  app.get('/admin/backup', { preHandler: settingsStaff }, async () => readBackupStatus(dir));

  app.post('/admin/backup', { preHandler: settingsStaff, config: { rateLimit: { max: 10, timeWindow: '1 minute' } } }, async (req, reply) => {
    const st = mustStaff(req);
    if (!(await dirExists(dir))) {
      throw new ApiError('SERVICE_UNAVAILABLE', 'ব্যাকআপ সেবা এই সার্ভারে চালু নেই। সার্ভারের দায়িত্বে থাকা ব্যক্তির সাথে যোগাযোগ করুন।');
    }
    try {
      await access(dir, constants.W_OK);
    } catch {
      throw new ApiError('SERVICE_UNAVAILABLE', 'ব্যাকআপ ফোল্ডারে লেখা যাচ্ছে না। সার্ভার কনফিগারেশন দেখুন।');
    }
    const requestedAt = new Date().toISOString();
    await tx(ctx.db, async (c) => {
      // One request per 10 minutes, globally (all staff, all API instances).
      await c.query('SELECT pg_advisory_xact_lock(727277)');
      const last = await q1<{ at: Date | null }>(c, `SELECT max(at) AS at FROM audit_log WHERE action = 'backup.request'`);
      if (last?.at && Date.now() - last.at.getTime() < BACKUP_REQUEST_COOLDOWN_MS) {
        const wait = Math.ceil((BACKUP_REQUEST_COOLDOWN_MS - (Date.now() - last.at.getTime())) / 1000);
        throw new ApiError('TOO_MANY_REQUESTS', '১০ মিনিটের মধ্যে একবারই ব্যাকআপের অনুরোধ করা যায়। একটু পরে আবার চেষ্টা করুন।', {
          retryAfterSec: String(wait),
        });
      }
      // O_NOFOLLOW: never write through a symlink planted in the shared volume.
      const fh = await open(join(dir, '.request'), constants.O_WRONLY | constants.O_CREAT | constants.O_TRUNC | constants.O_NOFOLLOW, 0o600);
      try {
        await fh.writeFile(`${requestedAt}\n`);
      } finally {
        await fh.close();
      }
      await audit(ctx, c, {
        actorType: 'staff',
        actorId: st.id,
        actorName: st.name,
        category: 'settings',
        action: 'backup.request',
        message: 'ডাটাবেস ব্যাকআপের অনুরোধ',
        tone: 'info',
        ip: req.ip,
      });
    });
    const status = await readBackupStatus(dir);
    return reply.status(202).send({ ...status, requestedAt: status.requestedAt ?? requestedAt });
  });

}
