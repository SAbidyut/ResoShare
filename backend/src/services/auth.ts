import { SignJWT, jwtVerify, errors as joseErrors } from 'jose';
import type pg from 'pg';
import type { FastifyReply, FastifyRequest } from 'fastify';
import type { Ctx, Permission, StaffAuth } from '../context.js';
import { q1, tx } from '../db.js';
import { ApiError } from '../errors.js';
import { randomToken, sha256Hex } from '../util/crypto.js';
import { getSettings } from './settings.js';

export const ACCESS_TTL_SEC = 15 * 60;
export const USER_SESSION_TTL_MS = 30 * 24 * 3600 * 1000;
export const STAFF_SESSION_TTL_MS = 12 * 3600 * 1000;
export const MFA_TTL_SEC = 5 * 60;
const ISSUER = 'resoshare';
const AUD_ACCESS = 'resoshare-api';
const AUD_MFA = 'resoshare-mfa';

export type SubjectType = 'user' | 'staff';
export type Tokens = { accessToken: string; refreshToken: string; expiresIn: number };

type AccessClaims = { sub: string; typ: SubjectType; sid: string; ver: number };

export async function signAccess(ctx: Ctx, c: AccessClaims): Promise<string> {
  return new SignJWT({ typ: c.typ, sid: c.sid, ver: c.ver })
    .setProtectedHeader({ alg: 'HS256', typ: 'JWT' })
    .setSubject(c.sub)
    .setIssuer(ISSUER)
    .setAudience(AUD_ACCESS)
    .setIssuedAt()
    .setExpirationTime(`${ACCESS_TTL_SEC}s`)
    .sign(ctx.config.jwtSecret);
}

export async function verifyAccess(ctx: Ctx, token: string): Promise<AccessClaims> {
  try {
    const { payload } = await jwtVerify(token, ctx.config.jwtSecret, {
      issuer: ISSUER,
      audience: AUD_ACCESS,
      algorithms: ['HS256'],
    });
    const { sub, typ, sid, ver } = payload as Record<string, unknown>;
    if (typeof sub !== 'string' || (typ !== 'user' && typ !== 'staff') || typeof sid !== 'string' || typeof ver !== 'number') {
      throw new ApiError('UNAUTHORIZED');
    }
    return { sub, typ, sid, ver };
  } catch (err) {
    if (err instanceof ApiError) throw err;
    if (err instanceof joseErrors.JWTExpired) throw new ApiError('TOKEN_EXPIRED');
    throw new ApiError('UNAUTHORIZED');
  }
}

export async function signMfa(ctx: Ctx, staffId: string, challengeId: string): Promise<string> {
  return new SignJWT({ purpose: 'mfa' })
    .setProtectedHeader({ alg: 'HS256', typ: 'JWT' })
    .setSubject(staffId)
    .setJti(challengeId)
    .setIssuer(ISSUER)
    .setAudience(AUD_MFA)
    .setIssuedAt()
    .setExpirationTime(`${MFA_TTL_SEC}s`)
    .sign(ctx.config.jwtSecret);
}

export async function verifyMfa(ctx: Ctx, token: string): Promise<{ staffId: string; challengeId: string }> {
  try {
    const { payload } = await jwtVerify(token, ctx.config.jwtSecret, { issuer: ISSUER, audience: AUD_MFA, algorithms: ['HS256'] });
    if (payload.purpose !== 'mfa' || typeof payload.sub !== 'string' || typeof payload.jti !== 'string') throw new Error('bad');
    return { staffId: payload.sub, challengeId: payload.jti };
  } catch {
    throw new ApiError('MFA_INVALID');
  }
}

export function clientIp(req: FastifyRequest): string {
  return req.ip;
}

function userAgent(req: FastifyRequest): string | null {
  const ua = req.headers['user-agent'];
  return typeof ua === 'string' ? ua.slice(0, 200) : null;
}

/** Creates a session + first refresh token and returns a token pair. Runs on the caller's transaction. */
export async function startSession(
  ctx: Ctx,
  client: pg.PoolClient,
  req: FastifyRequest,
  subjectType: SubjectType,
  subjectId: string,
  tokenVersion: number,
  deviceId: string | null,
): Promise<Tokens & { sessionId: string }> {
  const ttl = subjectType === 'user' ? USER_SESSION_TTL_MS : STAFF_SESSION_TTL_MS;
  const expires = new Date(Date.now() + ttl);
  const s = await q1<{ id: string }>(
    client,
    `INSERT INTO sessions (subject_type, subject_id, device_id, ip, user_agent, expires_at)
     VALUES ($1,$2,$3,$4,$5,$6) RETURNING id`,
    [subjectType, subjectId, deviceId, clientIp(req), userAgent(req), expires],
  );
  if (!s) throw new Error('session insert failed');
  const refresh = randomToken(32);
  await client.query('INSERT INTO refresh_tokens (session_id, token_hash, expires_at) VALUES ($1,$2,$3)', [s.id, sha256Hex(refresh), expires]);
  const accessToken = await signAccess(ctx, { sub: subjectId, typ: subjectType, sid: s.id, ver: tokenVersion });
  return { sessionId: s.id, accessToken, refreshToken: refresh, expiresIn: ACCESS_TTL_SEC };
}

export async function revokeSession(client: pg.PoolClient | pg.Pool, sessionId: string, reason: string): Promise<void> {
  await client.query('UPDATE sessions SET revoked_at = now(), revoke_reason = $2 WHERE id = $1 AND revoked_at IS NULL', [sessionId, reason]);
}

export async function revokeAllSessions(
  client: pg.PoolClient | pg.Pool,
  subjectType: SubjectType,
  subjectId: string,
  reason: string,
  exceptSessionId: string | null = null,
): Promise<void> {
  await client.query(
    `UPDATE sessions SET revoked_at = now(), revoke_reason = $3
      WHERE subject_type = $1 AND subject_id = $2 AND revoked_at IS NULL AND ($4::uuid IS NULL OR id <> $4::uuid)`,
    [subjectType, subjectId, reason, exceptSessionId],
  );
}

/** Refresh-token rotation with reuse detection (a reused token revokes the whole session/family). */
export async function rotateRefresh(ctx: Ctx, refreshToken: string, expected: SubjectType): Promise<Tokens> {
  if (typeof refreshToken !== 'string' || refreshToken.length < 20 || refreshToken.length > 200) throw new ApiError('SESSION_EXPIRED');
  const hash = sha256Hex(refreshToken);
  const outcome = await tx(ctx.db, async (c) => {
    const row = await q1<{
      rt_id: string;
      used_at: Date | null;
      rt_expires: Date;
      session_id: string;
      subject_type: SubjectType;
      subject_id: string;
      revoked_at: Date | null;
      s_expires: Date;
      last_active_at: Date;
    }>(
      c,
      `SELECT rt.id AS rt_id, rt.used_at, rt.expires_at AS rt_expires, s.id AS session_id, s.subject_type, s.subject_id,
              s.revoked_at, s.expires_at AS s_expires, s.last_active_at
         FROM refresh_tokens rt JOIN sessions s ON s.id = rt.session_id
        WHERE rt.token_hash = $1
        FOR UPDATE OF rt, s`,
      [hash],
    );
    if (!row) return { error: new ApiError('SESSION_EXPIRED') };
    if (row.used_at) {
      await revokeSession(c, row.session_id, 'refresh_reuse');
      return { error: new ApiError('SESSION_EXPIRED'), reuse: row };
    }
    const now = Date.now();
    if (row.revoked_at || row.s_expires.getTime() <= now || row.rt_expires.getTime() <= now || row.subject_type !== expected) {
      return { error: new ApiError('SESSION_EXPIRED') };
    }
    let tokenVersion: number;
    if (row.subject_type === 'user') {
      const u = await q1<{ token_version: number; account_status: string }>(
        c,
        'SELECT token_version, account_status FROM shareholders WHERE id = $1',
        [row.subject_id],
      );
      if (!u) return { error: new ApiError('SESSION_EXPIRED') };
      if (u.account_status !== 'active') return { error: new ApiError('ACCOUNT_SUSPENDED') };
      tokenVersion = u.token_version;
    } else {
      const s = await q1<{ token_version: number; status: string }>(c, 'SELECT token_version, status FROM staff WHERE id = $1', [row.subject_id]);
      if (!s || s.status !== 'active') {
        await revokeSession(c, row.session_id, 'staff_disabled');
        return { error: new ApiError('SESSION_EXPIRED') };
      }
      const settings = await getSettings(c);
      if (now - row.last_active_at.getTime() > settings.security.autoLogoutMinutes * 60_000) {
        await revokeSession(c, row.session_id, 'idle_timeout');
        return { error: new ApiError('SESSION_EXPIRED') };
      }
      tokenVersion = s.token_version;
    }
    await c.query('UPDATE refresh_tokens SET used_at = now() WHERE id = $1', [row.rt_id]);
    const next = randomToken(32);
    await c.query('INSERT INTO refresh_tokens (session_id, token_hash, expires_at) VALUES ($1,$2,$3)', [
      row.session_id,
      sha256Hex(next),
      row.s_expires,
    ]);
    const accessToken = await signAccess(ctx, { sub: row.subject_id, typ: row.subject_type, sid: row.session_id, ver: tokenVersion });
    return { tokens: { accessToken, refreshToken: next, expiresIn: ACCESS_TTL_SEC } satisfies Tokens };
  });
  if ('error' in outcome && outcome.error) {
    if ('reuse' in outcome && outcome.reuse) {
      ctx.log.warn({ sessionId: outcome.reuse.session_id }, 'refresh token reuse detected; session revoked');
    }
    throw outcome.error;
  }
  return (outcome as { tokens: Tokens }).tokens;
}

export async function logoutByRefresh(ctx: Ctx, refreshToken: string, expected: SubjectType): Promise<void> {
  if (typeof refreshToken !== 'string' || refreshToken.length > 200) return;
  const row = await q1<{ session_id: string; subject_type: string }>(
    ctx.db,
    `SELECT rt.session_id, s.subject_type FROM refresh_tokens rt JOIN sessions s ON s.id = rt.session_id WHERE rt.token_hash = $1`,
    [sha256Hex(refreshToken)],
  );
  if (row && row.subject_type === expected) await revokeSession(ctx.db, row.session_id, 'logout');
}

// ---------------------------------------------------------------------------
// Request guards

type GuardOptions = { allowPasswordChange?: boolean };

function bearer(req: FastifyRequest): string {
  const h = req.headers.authorization;
  if (typeof h !== 'string') throw new ApiError('UNAUTHORIZED');
  const m = /^Bearer\s+([A-Za-z0-9\-_.]{20,2000})$/i.exec(h.trim());
  if (!m || !m[1]) throw new ApiError('UNAUTHORIZED');
  return m[1];
}

const ACTIVITY_WRITE_MS = 15_000;

export function userGuard(ctx: Ctx, opts: GuardOptions = {}) {
  return async (req: FastifyRequest, _reply: FastifyReply) => {
    const claims = await verifyAccess(ctx, bearer(req));
    if (claims.typ !== 'user') throw new ApiError('UNAUTHORIZED');
    const row = await q1<{
      revoked_at: Date | null;
      expires_at: Date;
      last_active_at: Date;
      token_version: number;
      account_status: string;
      must_change_password: boolean;
      full_name: string;
    }>(
      ctx.db,
      `SELECT s.revoked_at, s.expires_at, s.last_active_at, u.token_version, u.account_status, u.must_change_password, u.full_name
         FROM sessions s JOIN shareholders u ON u.id = s.subject_id
        WHERE s.id = $1 AND s.subject_type = 'user' AND s.subject_id = $2`,
      [claims.sid, claims.sub],
    );
    if (!row || row.revoked_at || row.expires_at.getTime() <= Date.now() || row.token_version !== claims.ver) {
      throw new ApiError('SESSION_EXPIRED');
    }
    if (row.account_status !== 'active') throw new ApiError('ACCOUNT_SUSPENDED');
    if (row.must_change_password && !opts.allowPasswordChange) throw new ApiError('PASSWORD_CHANGE_REQUIRED');
    if (Date.now() - row.last_active_at.getTime() > ACTIVITY_WRITE_MS) {
      await ctx.db.query('UPDATE sessions SET last_active_at = now() WHERE id = $1', [claims.sid]);
    }
    req.userAuth = { id: claims.sub, sid: claims.sid, name: row.full_name };
  };
}

export function staffGuard(ctx: Ctx, permission: Permission | 'any', opts: GuardOptions = {}) {
  return async (req: FastifyRequest, _reply: FastifyReply) => {
    const claims = await verifyAccess(ctx, bearer(req));
    if (claims.typ !== 'staff') throw new ApiError('UNAUTHORIZED');
    const row = await q1<{
      revoked_at: Date | null;
      expires_at: Date;
      last_active_at: Date;
      token_version: number;
      status: string;
      must_change_password: boolean;
      name: string;
      role_key: string;
      permissions: string[];
    }>(
      ctx.db,
      `SELECT s.revoked_at, s.expires_at, s.last_active_at, st.token_version, st.status, st.must_change_password, st.name,
              r.key AS role_key, r.permissions
         FROM sessions s JOIN staff st ON st.id = s.subject_id JOIN roles r ON r.id = st.role_id
        WHERE s.id = $1 AND s.subject_type = 'staff' AND s.subject_id = $2`,
      [claims.sid, claims.sub],
    );
    if (!row || row.revoked_at || row.expires_at.getTime() <= Date.now() || row.token_version !== claims.ver) {
      throw new ApiError('SESSION_EXPIRED');
    }
    if (row.status !== 'active') {
      await revokeSession(ctx.db, claims.sid, 'staff_disabled');
      throw new ApiError('UNAUTHORIZED');
    }
    const settings = await getSettings(ctx.db);
    if (Date.now() - row.last_active_at.getTime() > settings.security.autoLogoutMinutes * 60_000) {
      await revokeSession(ctx.db, claims.sid, 'idle_timeout');
      throw new ApiError('SESSION_EXPIRED');
    }
    if (row.must_change_password && !opts.allowPasswordChange) throw new ApiError('PASSWORD_CHANGE_REQUIRED');
    const perms = row.permissions as Permission[];
    if (permission !== 'any' && !perms.includes(permission)) throw new ApiError('FORBIDDEN');
    if (Date.now() - row.last_active_at.getTime() > ACTIVITY_WRITE_MS) {
      await ctx.db.query('UPDATE sessions SET last_active_at = now() WHERE id = $1', [claims.sid]);
      await ctx.db.query('UPDATE staff SET last_active_at = now() WHERE id = $1', [claims.sub]);
    }
    const auth: StaffAuth = { id: claims.sub, sid: claims.sid, name: row.name, roleKey: row.role_key, permissions: perms };
    req.staffAuth = auth;
  };
}

export function mustUser(req: FastifyRequest) {
  if (!req.userAuth) throw new ApiError('UNAUTHORIZED');
  return req.userAuth;
}

export function mustStaff(req: FastifyRequest) {
  if (!req.staffAuth) throw new ApiError('UNAUTHORIZED');
  return req.staffAuth;
}

// ---------------------------------------------------------------------------
// Lockout

export const MAX_FAILED = 5;
export const BASE_LOCK_MS = 15 * 60 * 1000;
export const MAX_LOCK_MS = 24 * 3600 * 1000;

export function lockDuration(lockCount: number): number {
  return Math.min(BASE_LOCK_MS * 2 ** lockCount, MAX_LOCK_MS);
}

/** Records a failed attempt; returns retryAfterSec when the account just became locked. */
export async function registerFailure(
  client: pg.PoolClient | pg.Pool,
  table: 'shareholders' | 'staff',
  id: string,
): Promise<number | null> {
  const row = await q1<{ failed_logins: number; lock_count: number }>(
    client,
    `UPDATE ${table} SET failed_logins = failed_logins + 1, updated_at = now() WHERE id = $1 RETURNING failed_logins, lock_count`,
    [id],
  );
  if (!row || row.failed_logins < MAX_FAILED) return null;
  const ms = lockDuration(row.lock_count);
  await client.query(
    `UPDATE ${table} SET failed_logins = 0, lock_count = lock_count + 1, locked_until = now() + ($2::bigint * interval '1 millisecond') WHERE id = $1`,
    [id, ms],
  );
  return Math.ceil(ms / 1000);
}

export function lockedError(lockedUntil: Date): ApiError {
  const sec = Math.max(1, Math.ceil((lockedUntil.getTime() - Date.now()) / 1000));
  return new ApiError('LOCKED', undefined, { retryAfterSec: String(sec) });
}
