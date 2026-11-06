import type pg from 'pg';
import type { Client, Db } from '../db.js';
import { q1, tx } from '../db.js';

/*
 * Login throttling (replaces the old global account lock, which let anyone lock out a known account).
 *
 *  - Per (account, client IP): 5 failed attempts → that IP is locked for 15 min, doubling per lock, max 1 h.
 *    Other IPs (the real owner) are unaffected.
 *  - Global per-account safety cap that does NOT escalate: ≥30 failures across all IPs within 1 h
 *    → the account is locked for 15 min.
 *  - Every attempt is *reserved* atomically (row lock) BEFORE the password/OTP is checked, so a parallel
 *    burst cannot get more than 5 verifications per (account, IP).
 *  - Counters reset on success (users: correct password; staff: correct OTP after the password).
 */

export type Subject = 'user' | 'staff';

export const PER_IP_MAX_FAILURES = 5;
export const PER_IP_BASE_LOCK_MS = 15 * 60_000;
export const PER_IP_MAX_LOCK_MS = 60 * 60_000;
export const FAILURE_WINDOW_MS = 60 * 60_000;
export const GLOBAL_MAX_FAILURES = 30;
export const GLOBAL_LOCK_MS = 15 * 60_000;

const TABLE: Record<Subject, 'shareholders' | 'staff'> = { user: 'shareholders', staff: 'staff' };

export function perIpLockMs(lockCount: number): number {
  return Math.min(PER_IP_BASE_LOCK_MS * 2 ** lockCount, PER_IP_MAX_LOCK_MS);
}

function normIp(ip: string): string {
  return (ip || 'unknown').slice(0, 64);
}

type ThrottleRow = { failures: number; lock_count: number; locked_until: Date | null; window_start: Date };

/**
 * Atomically reserves one attempt for (subject, ip). Returns false when the caller must NOT verify the
 * credential (locked for this IP, account-wide cap active, or the burst already used all attempts).
 */
export async function reserveAttempt(db: Db, type: Subject, id: string, ip: string): Promise<boolean> {
  const addr = normIp(ip);
  return tx(db, async (c) => {
    await c.query(
      `INSERT INTO login_throttle (subject_type, subject_id, ip) VALUES ($1,$2,$3) ON CONFLICT DO NOTHING`,
      [type, id, addr],
    );
    const row = await q1<ThrottleRow>(
      c,
      `SELECT failures, lock_count, locked_until, window_start FROM login_throttle
        WHERE subject_type = $1 AND subject_id = $2 AND ip = $3 FOR UPDATE`,
      [type, id, addr],
    );
    if (!row) return false;
    const acct = await q1<{ locked_until: Date | null }>(c, `SELECT locked_until FROM ${TABLE[type]} WHERE id = $1`, [id]);
    const now = Date.now();
    if (acct?.locked_until && acct.locked_until.getTime() > now) return false;
    if (row.locked_until && row.locked_until.getTime() > now) return false;

    let failures = row.failures;
    let windowStart = row.window_start;
    const lockExpired = row.locked_until !== null && row.locked_until.getTime() <= now;
    if (lockExpired || now - row.window_start.getTime() > FAILURE_WINDOW_MS) {
      failures = 0;
      windowStart = new Date(now);
    }
    if (failures >= PER_IP_MAX_FAILURES) {
      await lockRow(c, type, id, addr, row.lock_count);
      return false;
    }
    await c.query(
      `UPDATE login_throttle SET failures = $4, window_start = $5, locked_until = NULL, updated_at = now()
        WHERE subject_type = $1 AND subject_id = $2 AND ip = $3`,
      [type, id, addr, failures + 1, windowStart],
    );
    return true;
  });
}

async function lockRow(c: pg.PoolClient, type: Subject, id: string, ip: string, lockCount: number): Promise<void> {
  await c.query(
    `UPDATE login_throttle SET locked_until = now() + ($4::bigint * interval '1 millisecond'), lock_count = lock_count + 1, updated_at = now()
      WHERE subject_type = $1 AND subject_id = $2 AND ip = $3`,
    [type, id, ip, perIpLockMs(lockCount)],
  );
}

/** Records a verified failure: may lock this IP, and feeds the account-wide safety cap. */
export async function recordFailure(db: Db, type: Subject, id: string, ip: string): Promise<void> {
  const addr = normIp(ip);
  await tx(db, async (c) => {
    const row = await q1<ThrottleRow>(
      c,
      `SELECT failures, lock_count, locked_until, window_start FROM login_throttle
        WHERE subject_type = $1 AND subject_id = $2 AND ip = $3 FOR UPDATE`,
      [type, id, addr],
    );
    if (row && row.failures >= PER_IP_MAX_FAILURES && !(row.locked_until && row.locked_until.getTime() > Date.now())) {
      await lockRow(c, type, id, addr, row.lock_count);
    }
    await c.query('INSERT INTO login_failures (subject_type, subject_id, ip) VALUES ($1,$2,$3)', [type, id, addr]);
    const cnt = await q1<{ n: number }>(
      c,
      `SELECT count(*)::int AS n FROM login_failures WHERE subject_type = $1 AND subject_id = $2 AND at > now() - ($3::bigint * interval '1 millisecond')`,
      [type, id, FAILURE_WINDOW_MS],
    );
    if ((cnt?.n ?? 0) >= GLOBAL_MAX_FAILURES) {
      // Non-escalating: always exactly 15 minutes from the latest failure.
      await c.query(`UPDATE ${TABLE[type]} SET locked_until = now() + ($2::bigint * interval '1 millisecond') WHERE id = $1`, [id, GLOBAL_LOCK_MS]);
    }
  });
}

/** Gives back a reserved attempt that turned out not to be a failure (staff: password ok, OTP still pending). */
export async function refundAttempt(db: Client, type: Subject, id: string, ip: string): Promise<void> {
  await db.query(
    `UPDATE login_throttle SET failures = GREATEST(failures - 1, 0), updated_at = now()
      WHERE subject_type = $1 AND subject_id = $2 AND ip = $3`,
    [type, id, normIp(ip)],
  );
}

/** Success: clears this IP's counters, the account failure log and any account-wide cap. */
export async function clearOnSuccess(db: Client, type: Subject, id: string, ip: string): Promise<void> {
  await db.query('DELETE FROM login_throttle WHERE subject_type = $1 AND subject_id = $2 AND ip = $3', [type, id, normIp(ip)]);
  await db.query('DELETE FROM login_failures WHERE subject_type = $1 AND subject_id = $2', [type, id]);
  await db.query(`UPDATE ${TABLE[type]} SET locked_until = NULL, failed_logins = 0, lock_count = 0 WHERE id = $1`, [id]);
}

/** Admin unlock / password reset: clears every lock for the account on every IP. */
export async function unlockAll(db: Client, type: Subject, id: string): Promise<void> {
  await db.query('DELETE FROM login_throttle WHERE subject_type = $1 AND subject_id = $2', [type, id]);
  await db.query('DELETE FROM login_failures WHERE subject_type = $1 AND subject_id = $2', [type, id]);
  await db.query(`UPDATE ${TABLE[type]} SET locked_until = NULL, failed_logins = 0, lock_count = 0 WHERE id = $1`, [id]);
}
