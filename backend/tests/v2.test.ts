// v2 features: public rate, shareholder tab counts, admin certificate view, symbol password policy, backups.
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, utimesSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { buildApp } from '../src/app.js';
import { loadConfig } from '../src/config.js';
import { q1 } from '../src/db.js';
import { hashPassword } from '../src/util/passwords.js';
import { addDays, dhakaDate } from '../src/util/time.js';
import { bearer, err, newStaff, newUser, registerUser, setupApp, teardownApp, todayRatePaisa, type TestEnv } from './helpers.js';

let env: TestEnv;
let admin: Awaited<ReturnType<typeof newStaff>>;
const A = (path: string) => `/api/v1${path}`;
const SHAPE_MSG = 'পাসওয়ার্ডে অক্ষর, সংখ্যা ও একটি চিহ্ন (যেমন @ # !) রাখুন';

beforeAll(async () => {
  env = await setupApp();
  admin = await newStaff(env, 'super_admin');
});
afterAll(async () => {
  await teardownApp();
});

describe('GET /public/info (v2)', () => {
  it("includes today's rate and the change against yesterday's effective rate, without auth", async () => {
    const today = dhakaDate();
    const rate = await todayRatePaisa(env.db);
    await env.db.query(
      `INSERT INTO rates (rate_date, rate_paisa, notify, notice_posted) VALUES ($1, $2, false, true) ON CONFLICT (rate_date) DO NOTHING`,
      [addDays(today, -1), rate - 1250],
    );
    const y = await q1<{ rate_paisa: number }>(env.db, 'SELECT rate_paisa FROM rates WHERE rate_date <= $1 ORDER BY rate_date DESC LIMIT 1', [addDays(today, -1)]);
    const r = await env.app.inject({ method: 'GET', url: A('/public/info') });
    expect(r.statusCode).toBe(200);
    const b = r.json();
    expect(b.todayRatePaisa).toBe(rate);
    expect(b.dayChangePaisa).toBe(rate - (y?.rate_paisa ?? rate));
    expect(Object.keys(b).sort()).toEqual(['companyName', 'dayChangePaisa', 'email', 'hotline', 'officeAddress', 'todayRatePaisa']);
  });
});

describe('GET /admin/shareholders counts (v2)', () => {
  it('returns totals over all shareholders, independent of q and status', async () => {
    const v = await newUser(env.app);
    await env.app.inject({ method: 'POST', url: A(`/admin/kyc/${v.id}/approve`), headers: bearer(admin.token) });
    const s = await newUser(env.app);
    await env.app.inject({ method: 'POST', url: A(`/admin/shareholders/${s.id}/suspend`), headers: bearer(admin.token), payload: { reason: 'পরীক্ষা' } });
    await newUser(env.app);

    const expected = await q1<{ all: number; verified: number; pending: number; suspended: number }>(
      env.db,
      `SELECT count(*)::int AS all,
              count(*) FILTER (WHERE kyc_status = 'verified' AND account_status = 'active')::int AS verified,
              count(*) FILTER (WHERE kyc_status IN ('pending','rejected') AND account_status = 'active')::int AS pending,
              count(*) FILTER (WHERE account_status = 'suspended')::int AS suspended
         FROM shareholders`,
    );
    const plain = (await env.app.inject({ method: 'GET', url: A('/admin/shareholders'), headers: bearer(admin.token) })).json();
    const filtered = (
      await env.app.inject({ method: 'GET', url: A(`/admin/shareholders?status=suspended&q=${encodeURIComponent('কোনো নাম নেই')}`), headers: bearer(admin.token) })
    ).json();
    expect(plain.counts).toEqual(expected);
    expect(filtered.counts).toEqual(expected);
    expect(filtered.total).toBe(0);
    expect(expected!.verified + expected!.pending + expected!.suspended).toBe(expected!.all);
    // the per-status lists agree with the badges
    for (const st of ['verified', 'pending', 'suspended'] as const) {
      const page = (await env.app.inject({ method: 'GET', url: A(`/admin/shareholders?status=${st}&limit=1`), headers: bearer(admin.token) })).json();
      expect(page.total).toBe(expected![st]);
    }
  });
});

describe('GET /admin/shareholders/:id/certificate (v2)', () => {
  it('404 without shares; afterwards identical to /me/certificate, audited, view permission', async () => {
    const u = await newUser(env.app);
    await env.app.inject({ method: 'POST', url: A(`/admin/kyc/${u.id}/approve`), headers: bearer(admin.token) });
    const none = await env.app.inject({ method: 'GET', url: A(`/admin/shareholders/${u.id}/certificate`), headers: bearer(admin.token) });
    expect(none.statusCode).toBe(404);
    const missing = await env.app.inject({ method: 'GET', url: A('/admin/shareholders/00000000-0000-0000-0000-000000000000/certificate'), headers: bearer(admin.token) });
    expect(missing.statusCode).toBe(404);

    const rate = await todayRatePaisa(env.db);
    const o = await env.app.inject({
      method: 'POST',
      url: A('/me/orders'),
      headers: bearer(u.token),
      payload: { quantity: 7, method: 'cash', reference: null, expectedRatePaisa: rate, acceptRules: true },
    });
    await env.app.inject({ method: 'POST', url: A(`/admin/orders/${o.json().id}/approve`), headers: bearer(admin.token) });

    const support = await newStaff(env, 'support');
    const adminView = await env.app.inject({ method: 'GET', url: A(`/admin/shareholders/${u.id}/certificate`), headers: bearer(support.token) });
    expect(adminView.statusCode).toBe(200);
    const mine = await env.app.inject({ method: 'GET', url: A('/me/certificate'), headers: bearer(u.token) });
    expect(adminView.json()).toEqual(mine.json());
    expect(adminView.json().shares).toBe(7);
    const verify = new URL(adminView.json().verifyUrl);
    const page = await env.app.inject({ method: 'GET', url: verify.pathname + verify.search });
    expect(page.body).toContain('বৈধ শেয়ার সার্টিফিকেট');

    const logged = await q1<{ message: string; category: string; tone: string }>(
      env.db,
      `SELECT message, category, tone FROM audit_log WHERE action = 'shareholder.certificate.view' AND meta->>'shareholderId' = $1`,
      [u.id],
    );
    expect(logged?.message).toContain('সার্টিফিকেট দেখা হয়েছে');
    expect(logged?.category).toBe('shareholder');
    expect(logged?.tone).toBe('info');

    const asUser = await env.app.inject({ method: 'GET', url: A(`/admin/shareholders/${u.id}/certificate`), headers: bearer(u.token) });
    expect(asUser.statusCode).toBe(401);
  });
});

describe('password policy needs a symbol (v2)', () => {
  it('register and change-password reject passwords without a symbol; old passwords still log in', async () => {
    const reg = await registerUser(env.app, { password: 'Strongpass42' });
    expect(reg.res.statusCode).toBe(400);
    expect(err(reg.res).fields?.password).toBe(SHAPE_MSG);
    const ok = await registerUser(env.app, { password: 'Strong#pass42' });
    expect(ok.res.statusCode).toBe(201);

    const u = await newUser(env.app);
    const change = await env.app.inject({
      method: 'POST',
      url: A('/me/password'),
      headers: bearer(u.token),
      payload: { currentPassword: u.data.password, newPassword: 'Newpassword123' },
    });
    expect(change.statusCode).toBe(400);
    expect(err(change).fields?.newPassword).toBe(SHAPE_MSG);
    const staffChange = await env.app.inject({
      method: 'POST',
      url: A('/admin/me/password'),
      headers: bearer(admin.token),
      payload: { currentPassword: admin.password, newPassword: 'Newpassword123' },
    });
    expect(err(staffChange).fields?.newPassword).toBe(SHAPE_MSG);

    // an account whose password was set under the old policy keeps working
    await env.db.query('UPDATE shareholders SET password_hash = $2 WHERE id = $1', [u.id, await hashPassword('Legacy12345')]);
    const legacy = await env.app.inject({ method: 'POST', url: A('/auth/login'), payload: { identifier: u.data.mobile, password: 'Legacy12345', deviceId: 'v2-device-1' } });
    expect(legacy.statusCode).toBe(200);

    // office-issued temporary passwords satisfy the new policy and can be replaced by a compliant one
    const r = await env.app.inject({ method: 'POST', url: A(`/admin/shareholders/${u.id}/reset-password`), headers: bearer(admin.token) });
    const temp = r.json().tempPassword as string;
    expect(temp).toMatch(/[@#!%+=]/);
    const l = await env.app.inject({ method: 'POST', url: A('/auth/login'), payload: { identifier: u.data.mobile, password: temp, deviceId: 'v2-device-1' } });
    const ch = await env.app.inject({
      method: 'POST',
      url: A('/me/password'),
      headers: bearer(l.json().tokens.accessToken),
      payload: { currentPassword: temp, newPassword: 'New!pass123' },
    });
    expect(ch.statusCode).toBe(204);
  });
});

describe('backups (v2)', () => {
  const dir = process.env.BACKUP_DIR as string;

  it('GET reports "never" before any backup; POST writes the request, returns 202, and is limited to once per 10 min', async () => {
    mkdirSync(dir, { recursive: true, mode: 0o1733 });
    const g0 = await env.app.inject({ method: 'GET', url: A('/admin/backup'), headers: bearer(admin.token) });
    expect(g0.json()).toEqual({ lastAt: null, status: 'never', sizeBytes: null, keepDays: 14, requestedAt: null });

    const p1 = await env.app.inject({ method: 'POST', url: A('/admin/backup'), headers: bearer(admin.token) });
    expect(p1.statusCode).toBe(202);
    const requestedAt = p1.json().requestedAt as string;
    expect(Date.parse(requestedAt)).toBeGreaterThan(Date.now() - 60_000);
    expect(readFileSync(join(dir, '.request'), 'utf8').trim()).toBe(requestedAt);
    const logged = await q1<{ message: string; category: string }>(env.db, `SELECT message, category FROM audit_log WHERE action = 'backup.request' ORDER BY id DESC LIMIT 1`);
    expect(logged).toEqual({ message: 'ডাটাবেস ব্যাকআপের অনুরোধ', category: 'settings' });

    const p2 = await env.app.inject({ method: 'POST', url: A('/admin/backup'), headers: bearer(admin.token) });
    expect(p2.statusCode).toBe(429);
    expect(err(p2).code).toBe('TOO_MANY_REQUESTS');
    expect(Number(err(p2).fields?.retryAfterSec)).toBeGreaterThan(500);

    // the backup container picks the request up and reports
    writeFileSync(join(dir, 'status.json'), JSON.stringify({ lastAt: '2026-11-04T21:00:05Z', status: 'running', sizeBytes: 125446, keepDays: 14 }));
    const g1 = (await env.app.inject({ method: 'GET', url: A('/admin/backup'), headers: bearer(admin.token) })).json();
    expect(g1).toEqual({ lastAt: '2026-11-04T21:00:05Z', status: 'running', sizeBytes: 125446, keepDays: 14, requestedAt });

    writeFileSync(join(dir, 'status.json'), '{"lastAt":"<script>","status":"pwned","sizeBytes":-1,"keepDays":14}');
    const g2 = (await env.app.inject({ method: 'GET', url: A('/admin/backup'), headers: bearer(admin.token) })).json();
    expect(g2.status).toBe('never');
  });

  it('needs settings_staff', async () => {
    const support = await newStaff(env, 'support');
    expect((await env.app.inject({ method: 'GET', url: A('/admin/backup'), headers: bearer(support.token) })).statusCode).toBe(403);
    expect((await env.app.inject({ method: 'POST', url: A('/admin/backup'), headers: bearer(support.token) })).statusCode).toBe(403);
  });

  it('without a backup directory GET says "never" and POST is 503 with a Bangla message', async () => {
    const config = { ...loadConfig(), backupDir: join(tmpdir(), `resoshare-no-backups-${process.pid}`) };
    const app2 = await buildApp({ config, db: env.db, logger: false });
    try {
      const g = await app2.inject({ method: 'GET', url: A('/admin/backup'), headers: bearer(admin.token) });
      expect(g.json().status).toBe('never');
      const p = await app2.inject({ method: 'POST', url: A('/admin/backup'), headers: bearer(admin.token) });
      expect(p.statusCode).toBe(503);
      expect(err(p).code).toBe('SERVICE_UNAVAILABLE');
      expect(err(p).message).toMatch(/[ঀ-৿]/);
    } finally {
      await app2.close();
    }
  });

  const pgBin = ['/usr/lib/postgresql/16/bin', ...(process.env.PATH ?? '').split(':')].find((d) => existsSync(join(d, 'pg_dump')));
  it.skipIf(!pgBin)('backup.sh: on request makes a restorable dump, writes status.json, prunes old dumps; failures are reported', () => {
    const work = mkdtempSync(join(tmpdir(), 'resoshare-bk-'));
    const owner = new URL(process.env.TEST_OWNER_DATABASE_URL as string);
    const script = new URL('../scripts/backup.sh', import.meta.url).pathname;
    const baseEnv = {
      PATH: `${pgBin}:${process.env.PATH ?? ''}`,
      PGHOST: owner.hostname,
      PGPORT: owner.port || '5432',
      PGUSER: decodeURIComponent(owner.username),
      PGPASSWORD: decodeURIComponent(owner.password),
      PGDATABASE: owner.pathname.slice(1),
      BACKUP_DIR: work,
      KEEP_DAYS: '14',
      BACKUP_AT: '23:59', // keep the daily trigger out of the way; this test drives it with .request
      RUN_ONCE: '1',
    };
    const old = join(work, 'resoshare-20200101-0300.dump');
    writeFileSync(old, 'old');
    const past = new Date(Date.now() - 20 * 86_400_000);
    utimesSync(old, past, past);
    writeFileSync(join(work, '.request'), new Date().toISOString());

    execFileSync('sh', [script], { env: baseEnv, stdio: 'pipe' });
    const files = readdirSync(work);
    expect(files).not.toContain('.request');
    expect(files).not.toContain('resoshare-20200101-0300.dump');
    const dumps = files.filter((f) => /^resoshare-\d{8}-\d{4}\.dump$/.test(f));
    expect(dumps).toHaveLength(1);
    const status = JSON.parse(readFileSync(join(work, 'status.json'), 'utf8'));
    expect(status.status).toBe('ok');
    expect(status.keepDays).toBe(14);
    expect(status.sizeBytes).toBeGreaterThan(1000);
    expect(Date.parse(status.lastAt)).toBeGreaterThan(Date.now() - 120_000);
    const toc = execFileSync(join(pgBin as string, 'pg_restore'), ['-l', join(work, dumps[0] as string)], { encoding: 'utf8' });
    expect(toc).toContain('TABLE DATA public audit_log');
    expect(toc).toContain('TABLE DATA public shareholders');

    // nothing requested and not yet 23:59 → nothing happens
    execFileSync('sh', [script], { env: baseEnv, stdio: 'pipe' });
    expect(readdirSync(work).filter((f) => f.endsWith('.dump'))).toHaveLength(1);

    // a failing dump is reported as failed and leaves no partial file
    writeFileSync(join(work, '.request'), new Date().toISOString());
    execFileSync('sh', [script], { env: { ...baseEnv, PGPORT: '1' }, stdio: 'pipe' });
    const failed = JSON.parse(readFileSync(join(work, 'status.json'), 'utf8'));
    expect(failed.status).toBe('failed');
    expect(failed.sizeBytes).toBeNull();
    expect(readdirSync(work).some((f) => f.endsWith('.partial'))).toBe(false);
  });
});
