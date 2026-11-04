import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import type { Ctx, Permission } from '../../context.js';
import { q1, tx } from '../../db.js';
import { ApiError } from '../../errors.js';
import { audit } from '../../services/audit.js';
import {
  lockedError,
  logoutByRefresh,
  MFA_TTL_SEC,
  mustStaff,
  registerFailure,
  revokeAllSessions,
  rotateRefresh,
  signMfa,
  staffGuard,
  startSession,
  verifyMfa,
} from '../../services/auth.js';
import { fakeVerify, hashPassword, verifyPassword } from '../../util/passwords.js';
import { newTotpSecret, otpauthUri, verifyTotp } from '../../util/totp.js';
import { maskEmail } from '../../util/mask.js';
import { parse, zDeviceId, zEmail, zLoginPassword, zPassword } from '../../util/validate.js';

const LoginSchema = z.object({ email: z.string().min(3).max(254), password: zLoginPassword, deviceId: zDeviceId }).strict();
const MfaSchema = z.object({ mfaToken: z.string().min(20).max(2000), code: z.string().max(10) }).strict();
const RefreshSchema = z.object({ refreshToken: z.string().min(1).max(200) }).strict();
const PasswordSchema = z.object({ currentPassword: zLoginPassword, newPassword: zPassword }).strict();
const MAX_MFA_ATTEMPTS = 5;

export async function staffMe(ctx: Ctx, staffId: string) {
  const r = await q1<{
    id: string;
    name: string;
    email: string;
    role_key: string;
    role_name: string;
    permissions: Permission[];
    must_change_password: boolean;
  }>(
    ctx.db,
    `SELECT st.id, st.name, st.email, r.key AS role_key, r.name AS role_name, r.permissions, st.must_change_password
       FROM staff st JOIN roles r ON r.id = st.role_id WHERE st.id = $1`,
    [staffId],
  );
  if (!r) throw new ApiError('UNAUTHORIZED');
  return {
    id: r.id,
    name: r.name,
    email: r.email,
    roleKey: r.role_key,
    roleName: r.role_name,
    permissions: r.permissions,
    mustChangePassword: r.must_change_password,
  };
}

export async function adminAuthRoutes(app: FastifyInstance, ctx: Ctx) {
  app.post('/admin/auth/login', { config: { rateLimit: { max: 10, timeWindow: '1 minute' } } }, async (req) => {
    const body = parse(LoginSchema, req.body);
    const generic = new ApiError('UNAUTHORIZED', 'ইমেইল বা পাসওয়ার্ড সঠিক নয়।');
    const emailParsed = zEmail.safeParse(body.email);
    const email = emailParsed.success ? emailParsed.data : null;
    const staff = email
      ? await q1<{
          id: string;
          name: string;
          email: string;
          password_hash: string;
          status: string;
          totp_enabled: boolean;
          locked_until: Date | null;
        }>(ctx.db, 'SELECT id, name, email, password_hash, status, totp_enabled, locked_until FROM staff WHERE email = $1', [email])
      : undefined;

    if (!staff) {
      await fakeVerify(body.password);
      await audit(ctx, ctx.db, {
        actorType: 'anonymous',
        actorId: null,
        actorName: email ? maskEmail(email) : 'অজানা',
        category: 'login',
        action: 'staff.login.fail',
        message: `অ্যাডমিন লগইন ব্যর্থ (${email ? maskEmail(email) : 'অজানা'})`,
        tone: 'bad',
        ip: req.ip,
      });
      throw generic;
    }
    if (staff.locked_until && staff.locked_until.getTime() > Date.now()) throw lockedError(staff.locked_until);
    const ok = await verifyPassword(staff.password_hash, body.password);
    if (!ok || staff.status !== 'active') {
      const lockedFor = ok ? null : await registerFailure(ctx.db, 'staff', staff.id);
      await audit(ctx, ctx.db, {
        actorType: 'staff',
        actorId: staff.id,
        actorName: staff.name,
        category: 'login',
        action: lockedFor ? 'staff.login.locked' : 'staff.login.fail',
        message: lockedFor
          ? `অ্যাডমিন অ্যাকাউন্ট সাময়িক লক: ${staff.name}`
          : ok
            ? `নিষ্ক্রিয় স্টাফের লগইন চেষ্টা: ${staff.name}`
            : `অ্যাডমিন লগইন ব্যর্থ (ভুল পাসওয়ার্ড): ${staff.name}`,
        tone: 'bad',
        ip: req.ip,
      });
      if (lockedFor) throw new ApiError('LOCKED', undefined, { retryAfterSec: String(lockedFor) });
      throw generic;
    }

    const enrollSecret = staff.totp_enabled ? null : newTotpSecret();
    const challenge = await q1<{ id: string }>(
      ctx.db,
      `INSERT INTO mfa_challenges (staff_id, pending_secret_enc, device_id, expires_at)
       VALUES ($1,$2,$3, now() + ($4::int * interval '1 second')) RETURNING id`,
      [staff.id, enrollSecret ? ctx.crypto.encryptText(enrollSecret) : null, body.deviceId, MFA_TTL_SEC],
    );
    if (!challenge) throw new Error('challenge insert failed');
    const mfaToken = await signMfa(ctx, staff.id, challenge.id);
    return {
      mfaToken,
      enrollment: enrollSecret ? { secret: enrollSecret, otpauthUri: otpauthUri(enrollSecret, staff.email) } : null,
    };
  });

  app.post('/admin/auth/mfa', { config: { rateLimit: { max: 10, timeWindow: '1 minute' } } }, async (req) => {
    const body = parse(MfaSchema, req.body);
    const { staffId, challengeId } = await verifyMfa(ctx, body.mfaToken);
    const code = body.code.trim();

    const result = await tx(ctx.db, async (c) => {
      const ch = await q1<{ staff_id: string; pending_secret_enc: string | null; device_id: string | null; attempts: number; expires_at: Date; used_at: Date | null }>(
        c,
        'SELECT staff_id, pending_secret_enc, device_id, attempts, expires_at, used_at FROM mfa_challenges WHERE id = $1 FOR UPDATE',
        [challengeId],
      );
      if (!ch || ch.staff_id !== staffId || ch.used_at || ch.expires_at.getTime() <= Date.now() || ch.attempts >= MAX_MFA_ATTEMPTS) {
        return { error: new ApiError('MFA_INVALID') };
      }
      const st = await q1<{
        name: string;
        status: string;
        token_version: number;
        totp_secret_enc: string | null;
        totp_enabled: boolean;
        totp_last_step: number | null;
        locked_until: Date | null;
      }>(
        c,
        'SELECT name, status, token_version, totp_secret_enc, totp_enabled, totp_last_step, locked_until FROM staff WHERE id = $1 FOR UPDATE',
        [staffId],
      );
      if (!st || st.status !== 'active') return { error: new ApiError('MFA_INVALID') };
      if (st.locked_until && st.locked_until.getTime() > Date.now()) return { error: lockedError(st.locked_until) };

      const enrolling = !st.totp_enabled;
      const secretEnc = enrolling ? ch.pending_secret_enc : st.totp_secret_enc;
      if (!secretEnc) return { error: new ApiError('MFA_INVALID') };
      const secret = ctx.crypto.decryptText(secretEnc);
      const step = verifyTotp(secret, code, enrolling ? null : st.totp_last_step);
      if (step === null) {
        await c.query('UPDATE mfa_challenges SET attempts = attempts + 1 WHERE id = $1', [challengeId]);
        const lockedFor = await registerFailure(c, 'staff', staffId);
        await audit(ctx, c, {
          actorType: 'staff',
          actorId: staffId,
          actorName: st.name,
          category: 'login',
          action: lockedFor ? 'staff.mfa.locked' : 'staff.mfa.fail',
          message: lockedFor ? `অ্যাডমিন অ্যাকাউন্ট সাময়িক লক (ভুল OTP): ${st.name}` : `ভুল OTP দিয়ে অ্যাডমিন লগইন চেষ্টা: ${st.name}`,
          tone: 'bad',
          ip: req.ip,
        });
        return { error: lockedFor ? new ApiError('LOCKED', undefined, { retryAfterSec: String(lockedFor) }) : new ApiError('MFA_INVALID') };
      }
      await c.query('UPDATE mfa_challenges SET used_at = now() WHERE id = $1', [challengeId]);
      await c.query(
        `UPDATE staff SET totp_last_step = $2, totp_secret_enc = $3, totp_enabled = true,
                failed_logins = 0, lock_count = 0, locked_until = NULL, last_active_at = now(), updated_at = now()
          WHERE id = $1`,
        [staffId, step, secretEnc],
      );
      const tokens = await startSession(ctx, c, req, 'staff', staffId, st.token_version, ch.device_id);
      await audit(ctx, c, {
        actorType: 'staff',
        actorId: staffId,
        actorName: st.name,
        category: 'login',
        action: enrolling ? 'staff.mfa.enroll' : 'staff.login',
        message: enrolling ? `অ্যাডমিন লগইন ও ২-ধাপ যাচাই চালু: ${st.name}` : `অ্যাডমিন লগইন (OTP যাচাইকৃত): ${st.name}`,
        tone: 'info',
        ip: req.ip,
      });
      return { tokens };
    });
    if ('error' in result && result.error) throw result.error;
    const t = (result as { tokens: { accessToken: string; refreshToken: string; expiresIn: number } }).tokens;
    return {
      tokens: { accessToken: t.accessToken, refreshToken: t.refreshToken, expiresIn: t.expiresIn },
      staff: await staffMe(ctx, staffId),
    };
  });

  app.post('/admin/auth/refresh', { config: { rateLimit: { max: 30, timeWindow: '1 minute' } } }, async (req) => {
    const body = parse(RefreshSchema, req.body);
    return rotateRefresh(ctx, body.refreshToken, 'staff');
  });

  app.post('/admin/auth/logout', { config: { rateLimit: { max: 30, timeWindow: '1 minute' } } }, async (req, reply) => {
    const body = parse(RefreshSchema, req.body);
    await logoutByRefresh(ctx, body.refreshToken, 'staff');
    return reply.status(204).send();
  });

  const anyPw = staffGuard(ctx, 'any', { allowPasswordChange: true });

  app.get('/admin/me', { preHandler: anyPw }, async (req) => staffMe(ctx, mustStaff(req).id));

  app.post('/admin/me/password', { preHandler: anyPw, config: { rateLimit: { max: 10, timeWindow: '15 minutes' } } }, async (req, reply) => {
    const s = mustStaff(req);
    const body = parse(PasswordSchema, req.body);
    const row = await q1<{ password_hash: string }>(ctx.db, 'SELECT password_hash FROM staff WHERE id = $1', [s.id]);
    if (!row || !(await verifyPassword(row.password_hash, body.currentPassword))) {
      throw new ApiError('INVALID_INPUT', 'বর্তমান পাসওয়ার্ড সঠিক নয়।', { currentPassword: 'বর্তমান পাসওয়ার্ড সঠিক নয়' });
    }
    if (body.currentPassword === body.newPassword) {
      throw new ApiError('INVALID_INPUT', undefined, { newPassword: 'নতুন পাসওয়ার্ড আগেরটার থেকে আলাদা হতে হবে' });
    }
    const hash = await hashPassword(body.newPassword);
    await tx(ctx.db, async (c) => {
      await c.query('UPDATE staff SET password_hash = $2, must_change_password = false, updated_at = now() WHERE id = $1', [s.id, hash]);
      await revokeAllSessions(c, 'staff', s.id, 'password_change', s.sid);
      await audit(ctx, c, {
        actorType: 'staff',
        actorId: s.id,
        actorName: s.name,
        category: 'login',
        action: 'staff.password.change',
        message: `পাসওয়ার্ড পরিবর্তন: ${s.name}`,
        tone: 'info',
        ip: req.ip,
      });
    });
    return reply.status(204).send();
  });
}
