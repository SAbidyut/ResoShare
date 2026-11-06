import type { Client } from '../db.js';
import { q1 } from '../db.js';

export type NoticeCategory = 'rate' | 'payment' | 'company' | 'dividend' | 'rules' | 'kyc' | 'message';

export type NewNotice = {
  title: string;
  body: string;
  category: NoticeCategory;
  audience: 'all' | 'selected' | 'user';
  userIds?: string[];
  createdBy?: string | null;
};

/** Creates an in-app notice (notices are app-only; no SMS/email). Returns id and recipient count. */
export async function createNotice(client: Client, n: NewNotice): Promise<{ id: string; recipientCount: number; createdAt: Date }> {
  const row = await q1<{ id: string; created_at: Date }>(
    client,
    `INSERT INTO notices (title, body, category, audience, created_by) VALUES ($1,$2,$3,$4,$5) RETURNING id, created_at`,
    [n.title, n.body, n.category, n.audience, n.createdBy ?? null],
  );
  if (!row) throw new Error('notice insert failed');
  let count = 0;
  if (n.audience === 'all') {
    const c = await q1<{ n: number }>(client, `SELECT count(*)::int AS n FROM shareholders WHERE account_status = 'active'`);
    count = c?.n ?? 0;
  } else {
    const ids = n.userIds ?? [];
    const res = await client.query(
      `INSERT INTO notice_recipients (notice_id, shareholder_id)
       SELECT $1, s.id FROM shareholders s WHERE s.id = ANY($2::uuid[])
       ON CONFLICT DO NOTHING`,
      [row.id, ids],
    );
    count = res.rowCount ?? 0;
  }
  await client.query('UPDATE notices SET recipient_count = $2 WHERE id = $1', [row.id, count]);
  return { id: row.id, recipientCount: count, createdAt: row.created_at };
}

/**
 * SQL predicate: notice `n` is visible to shareholder `param` (a placeholder like `$1`, never user text).
 * Broadcasts are shown from 30 days before the holder joined; targeted notices always.
 */
export function visibleToSql(param: string): string {
  if (!/^\$\d{1,2}$/.test(param)) throw new Error('visibleToSql expects a placeholder');
  return `((n.audience = 'all' AND n.created_at >= (SELECT sh.created_at - interval '30 days' FROM shareholders sh WHERE sh.id = ${param}))
           OR EXISTS (SELECT 1 FROM notice_recipients r WHERE r.notice_id = n.id AND r.shareholder_id = ${param}))`;
}
