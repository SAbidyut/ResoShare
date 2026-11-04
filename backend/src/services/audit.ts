import pg from 'pg';
import type { Client, Db } from '../db.js';
import { q1, tx } from '../db.js';
import type { Crypto } from '../util/crypto.js';

export type AuditCategory = 'rate' | 'approval' | 'login' | 'settings' | 'staff' | 'shareholder' | 'content';
export type AuditTone = 'info' | 'ok' | 'bad' | 'warn';

export type AuditInput = {
  actorType: 'staff' | 'user' | 'system' | 'anonymous';
  actorId: string | null;
  actorName: string;
  category: AuditCategory;
  action: string;
  message: string;
  tone: AuditTone;
  ip?: string | null;
  meta?: Record<string, unknown>;
};

/** Anything that carries the key material for the chain MAC (the app Ctx, or a CLI stand-in). */
export type AuditKeyHolder = { crypto: Pick<Crypto, 'auditMac'> };

export const GENESIS_HASH = '0'.repeat(64);

/** Key-sorted JSON so the hash survives jsonb's key reordering. */
export function canonicalJson(v: unknown): string {
  if (v === null || typeof v !== 'object') return JSON.stringify(v ?? null);
  if (Array.isArray(v)) return `[${v.map(canonicalJson).join(',')}]`;
  const obj = v as Record<string, unknown>;
  return `{${Object.keys(obj)
    .filter((k) => obj[k] !== undefined)
    .sort()
    .map((k) => `${JSON.stringify(k)}:${canonicalJson(obj[k])}`)
    .join(',')}}`;
}

/**
 * Chain link = HMAC-SHA256(auditKey, prev ‖ fields). The key is an HKDF subkey of HMAC_KEY that never
 * touches the database, so someone with DB write access alone cannot recompute a valid chain.
 */
export function auditHash(k: AuditKeyHolder, prev: string, at: string, e: AuditInput, meta: Record<string, unknown>): string {
  return k.crypto.auditMac(
    [prev, at, e.actorType, e.actorId ?? '', e.actorName, e.category, e.action, e.message, e.tone, e.ip ?? '', canonicalJson(meta)].join('\u001f'),
  );
}

async function insert(k: AuditKeyHolder, client: pg.PoolClient, e: AuditInput): Promise<void> {
  // Serialize writers so the hash chain stays linear. Call audit() LAST inside a transaction.
  await client.query('SELECT pg_advisory_xact_lock(727275)');
  const last = await q1<{ hash: string }>(client, 'SELECT hash FROM audit_log ORDER BY id DESC LIMIT 1');
  const prev = last?.hash ?? GENESIS_HASH;
  const at = new Date().toISOString();
  const meta = e.meta ?? {};
  const hash = auditHash(k, prev, at, e, meta);
  await client.query(
    `INSERT INTO audit_log (at, actor_type, actor_id, actor_name, category, action, message, tone, ip, meta, prev_hash, hash)
     VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)`,
    [at, e.actorType, e.actorId, e.actorName, e.category, e.action, e.message, e.tone, e.ip ?? null, JSON.stringify(meta), prev, hash],
  );
}

/** Appends an entry. With a pool it opens its own transaction; with a client it joins the caller's transaction. */
export async function audit(k: AuditKeyHolder, client: Client | Db, e: AuditInput): Promise<void> {
  if (client instanceof pg.Pool) {
    await tx(client, (c) => insert(k, c, e));
  } else {
    await insert(k, client as pg.PoolClient, e);
  }
}

type AuditRow = {
  id: number;
  at: Date;
  actor_type: AuditInput['actorType'];
  actor_id: string | null;
  actor_name: string;
  category: AuditCategory;
  action: string;
  message: string;
  tone: AuditTone;
  ip: string | null;
  meta: Record<string, unknown>;
  prev_hash: string;
  hash: string;
};

/** Recomputes the whole chain in batches; returns the id of the first broken row or null when intact. */
export async function verifyAuditChain(k: AuditKeyHolder, client: Client | Db, batchSize = 2000): Promise<number | null> {
  let prev = GENESIS_HASH;
  let afterId = 0;
  for (;;) {
    const res = await client.query<AuditRow>('SELECT * FROM audit_log WHERE id > $1 ORDER BY id ASC LIMIT $2', [afterId, batchSize]);
    for (const r of res.rows) {
      const h = auditHash(
        k,
        prev,
        r.at.toISOString(),
        {
          actorType: r.actor_type,
          actorId: r.actor_id,
          actorName: r.actor_name,
          category: r.category,
          action: r.action,
          message: r.message,
          tone: r.tone,
          ip: r.ip,
        },
        r.meta,
      );
      if (r.prev_hash !== prev || r.hash !== h) return r.id;
      prev = r.hash;
      afterId = r.id;
    }
    if (res.rows.length < batchSize) return null;
  }
}
