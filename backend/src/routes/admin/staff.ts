import { randomBytes } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { ALL_PERMISSIONS, type Ctx, type Permission, type StaffAuth } from '../../context.js';
import { q, q1, tx } from '../../db.js';
import { ApiError } from '../../errors.js';
import { audit } from '../../services/audit.js';
import { mustStaff, revokeAllSessions, staffGuard } from '../../services/auth.js';
import { createNotice } from '../../services/notices.js';
import { getSettings, settingsJson } from '../../services/settings.js';
import { unlockAll } from '../../services/throttle.js';
import { toAsciiDigits } from '../../util/bn.js';
import { tempPassword } from '../../util/crypto.js';
import { hashPassword } from '../../util/passwords.js';
import { encodeCursor, idParam, parse, zEmail, zMobile, zName, zPageQuery, zUuid } from '../../util/validate.js';

const zPerm = z.enum(ALL_PERMISSIONS as [Permission, ...Permission[]]);
const zRoleName = z
  .string()
  .transform((s) => s.trim())
  .pipe(z.string().min(2, 'রোলের নাম লিখুন').max(60, 'রোলের নাম অনেক বড়'));

const CreateStaffSchema = z.object({ name: zName, email: zEmail, mobile: zMobile, roleId: zUuid }).strict();
const PatchStaffSchema = z
  .object({ roleId: zUuid.optional(), status: z.enum(['active', 'disabled']).optional() })
  .strict()
  .refine((v) => v.roleId !== undefined || v.status !== undefined, 'কিছু পরিবর্তন করুন');
const CreateRoleSchema = z.object({ name: zRoleName, permissions: z.array(zPerm).min(1, 'কমপক্ষে একটি অনুমতি দিন').max(8) }).strict();
const PatchRoleSchema = z
  .object({ name: zRoleName.optional(), permissions: z.array(zPerm).min(1, 'কমপক্ষে একটি অনুমতি দিন').max(8).optional() })
  .strict();
const AuditQuery = zPageQuery.extend({
  category: z.enum(['all', 'rate', 'approval', 'login', 'settings', 'staff', 'shareholder', 'content']).default('all'),
});

const txt = (max: number) =>
  z
    .string()
    .transform((s) => s.trim())
    .pipe(z.string().max(max, 'লেখা অনেক বড়'));

const SettingsSchema = z
  .object({
    company: z
      .object({
        name: z
          .string()
          .transform((s) => s.trim())
          .pipe(z.string().min(2, 'কোম্পানির নাম লিখুন').max(100, 'নাম অনেক বড়')),
        email: z
          .string()
          .transform((s) => s.trim().toLowerCase())
          .pipe(z.union([z.literal(''), zEmail])),
        hotline: z
          .string()
          .transform((s) => toAsciiDigits(s).trim())
          .pipe(z.string().max(20, 'নম্বর অনেক বড়').regex(/^[0-9+\-\s]*$/, 'সঠিক হটলাইন নম্বর দিন')),
      })
      .strict(),
    payment: z
      .object({
        cashEnabled: z.boolean(),
        bankEnabled: z.boolean(),
        officeAddress: txt(300),
        bankName: txt(100),
        accountName: txt(100),
        accountNumber: txt(40),
      })
      .strict()
      .refine((p) => p.cashEnabled || p.bankEnabled, 'কমপক্ষে একটি পেমেন্ট মাধ্যম চালু রাখুন'),
    security: z
      .object({
        autoLogoutMinutes: z.number().int().min(3, 'কমপক্ষে ৩ মিনিট').max(60, 'সর্বোচ্চ ৬০ মিনিট'),
        mfaRequired: z.literal(true).optional(),
      })
      .strict(),
  })
  .strict();

type StaffRow = {
  id: string;
  name: string;
  email: string;
  mobile: string;
  role_id: string;
  role_key: string;
  role_name: string;
  status: string;
  last_active_at: Date | null;
  totp_enabled: boolean;
};
const STAFF_SQL = `SELECT st.id, st.name, st.email, st.mobile, st.role_id, r.key AS role_key, r.name AS role_name, st.status, st.last_active_at, st.totp_enabled
                     FROM staff st JOIN roles r ON r.id = st.role_id`;
const staffJson = (r: StaffRow) => ({
  id: r.id,
  name: r.name,
  email: r.email,
  mobile: r.mobile,
  roleId: r.role_id,
  roleKey: r.role_key,
  roleName: r.role_name,
  status: r.status,
  lastActiveAt: r.last_active_at ? r.last_active_at.toISOString() : null,
  mfaEnrolled: r.totp_enabled,
});

type RoleRow = { id: string; key: string; name: string; permissions: Permission[]; is_system: boolean };
const roleJson = (r: RoleRow) => ({ id: r.id, key: r.key, name: r.name, permissions: r.permissions, isSystem: r.is_system });

/** Prevents privilege escalation: a staff member can only grant permissions they hold. */
function assertGrantable(actor: StaffAuth, perms: Permission[]) {
  if (actor.roleKey === 'super_admin') return;
  const missing = perms.filter((p) => !actor.permissions.includes(p));
  if (missing.length) throw new ApiError('FORBIDDEN', 'নিজের চেয়ে বেশি অনুমতি অন্য কাউকে দেওয়া যাবে না।');
}

/**
 * H1: managing another staff member (role change, disable, reset, unlock) is only allowed for a super admin,
 * or for someone who already holds EVERY permission of the target's current role. Otherwise a
 * {view, settings_staff} staff could reset an `accounts` member, enroll their own authenticator and act as them.
 */
function assertCanManage(actor: StaffAuth, target: { role_key: string; permissions: Permission[] }) {
  if (actor.roleKey === 'super_admin') return;
  if (target.role_key === 'super_admin') throw new ApiError('FORBIDDEN', 'সুপার অ্যাডমিনকে শুধু সুপার অ্যাডমিন পরিবর্তন করতে পারেন।');
  const missing = target.permissions.filter((p) => !actor.permissions.includes(p));
  if (missing.length) throw new ApiError('FORBIDDEN', 'আপনার চেয়ে বেশি অনুমতির স্টাফকে পরিবর্তন করা যাবে না।');
}

type TargetRow = { name: string; role_key: string; status: string; permissions: Permission[] };
const TARGET_SQL = `SELECT st.name, r.key AS role_key, st.status, r.permissions FROM staff st JOIN roles r ON r.id = st.role_id WHERE st.id = $1 FOR UPDATE OF st`;

async function activeSuperAdmins(c: import('pg').PoolClient, excludeId: string): Promise<number> {
  const r = await q1<{ n: number }>(
    c,
    `SELECT count(*)::int AS n FROM staff st JOIN roles r ON r.id = st.role_id
      WHERE r.key = 'super_admin' AND st.status = 'active' AND st.id <> $1`,
    [excludeId],
  );
  return r?.n ?? 0;
}

export async function adminStaffRoutes(app: FastifyInstance, ctx: Ctx) {
  const settingsStaff = staffGuard(ctx, 'settings_staff');
  const view = staffGuard(ctx, 'view');

  app.get('/admin/staff', { preHandler: settingsStaff }, async () => {
    const staff = await q<StaffRow>(ctx.db, `${STAFF_SQL} ORDER BY (r.key = 'super_admin') DESC, st.created_at ASC`);
    const roles = await q<RoleRow>(ctx.db, 'SELECT id, key, name, permissions, is_system FROM roles ORDER BY is_system DESC, created_at ASC');
    return { items: staff.map(staffJson), roles: roles.map(roleJson) };
  });

  app.post('/admin/staff', { preHandler: settingsStaff }, async (req, reply) => {
    const actor = mustStaff(req);
    const body = parse(CreateStaffSchema, req.body);
    const role = await q1<RoleRow>(ctx.db, 'SELECT id, key, name, permissions, is_system FROM roles WHERE id = $1', [body.roleId]);
    if (!role) throw new ApiError('INVALID_INPUT', undefined, { roleId: 'রোল পাওয়া যায়নি' });
    if (role.key === 'super_admin' && actor.roleKey !== 'super_admin') throw new ApiError('FORBIDDEN');
    assertGrantable(actor, role.permissions);
    const exists = await q1(ctx.db, 'SELECT 1 FROM staff WHERE email = $1', [body.email]);
    if (exists) throw new ApiError('CONFLICT', 'এই ইমেইল দিয়ে আগেই স্টাফ আছে।', { email: 'এই ইমেইল দিয়ে আগেই স্টাফ আছে' });
    const temp = tempPassword();
    const hash = await hashPassword(temp);
    const id = await tx(ctx.db, async (c) => {
      const r = await q1<{ id: string }>(
        c,
        `INSERT INTO staff (name, email, mobile, password_hash, role_id, must_change_password) VALUES ($1,$2,$3,$4,$5,true) RETURNING id`,
        [body.name, body.email, body.mobile, hash, role.id],
      );
      if (!r) throw new Error('staff insert failed');
      await audit(ctx, c, {
        actorType: 'staff',
        actorId: actor.id,
        actorName: actor.name,
        category: 'staff',
        action: 'staff.create',
        message: `নতুন স্টাফ “${body.name}” যোগ (${role.name} রোল)`,
        tone: 'info',
        ip: req.ip,
        meta: { staffId: r.id },
      });
      return r.id;
    });
    const row = await q1<StaffRow>(ctx.db, `${STAFF_SQL} WHERE st.id = $1`, [id]);
    return reply.status(201).send({ staff: staffJson(row as StaffRow), tempPassword: temp });
  });

  app.patch('/admin/staff/:id', { preHandler: settingsStaff }, async (req) => {
    const actor = mustStaff(req);
    const id = idParam(req.params);
    const body = parse(PatchStaffSchema, req.body);
    if (id === actor.id) throw new ApiError('FORBIDDEN', 'নিজের রোল বা অবস্থা নিজে পরিবর্তন করা যাবে না।');
    await tx(ctx.db, async (c) => {
      // L3: serialize every change that could remove a super admin (no write-skew between two admins).
      await c.query('SELECT pg_advisory_xact_lock(727276)');
      const target = await q1<TargetRow>(c, TARGET_SQL, [id]);
      if (!target) throw new ApiError('NOT_FOUND');
      assertCanManage(actor, target);
      let newRole: RoleRow | undefined;
      if (body.roleId) {
        newRole = await q1<RoleRow>(c, 'SELECT id, key, name, permissions, is_system FROM roles WHERE id = $1', [body.roleId]);
        if (!newRole) throw new ApiError('INVALID_INPUT', undefined, { roleId: 'রোল পাওয়া যায়নি' });
        if (newRole.key === 'super_admin' && actor.roleKey !== 'super_admin') throw new ApiError('FORBIDDEN');
        assertGrantable(actor, newRole.permissions);
      }
      const losesSuper =
        target.role_key === 'super_admin' &&
        target.status === 'active' &&
        ((newRole && newRole.key !== 'super_admin') || body.status === 'disabled');
      if (losesSuper && (await activeSuperAdmins(c, id)) === 0) {
        throw new ApiError('CONFLICT', 'কমপক্ষে একজন সক্রিয় সুপার অ্যাডমিন থাকতে হবে।');
      }
      if (newRole) await c.query('UPDATE staff SET role_id = $2, token_version = token_version + 1, updated_at = now() WHERE id = $1', [id, newRole.id]);
      if (body.status) await c.query('UPDATE staff SET status = $2, updated_at = now() WHERE id = $1', [id, body.status]);
      if (newRole || body.status === 'disabled') await revokeAllSessions(c, 'staff', id, 'staff_changed');
      const parts: string[] = [];
      if (newRole) parts.push(`রোল: ${newRole.name}`);
      if (body.status) parts.push(body.status === 'active' ? 'সক্রিয় করা হয়েছে' : 'নিষ্ক্রিয় করা হয়েছে');
      await audit(ctx, c, {
        actorType: 'staff',
        actorId: actor.id,
        actorName: actor.name,
        category: 'staff',
        action: 'staff.update',
        message: `স্টাফ “${target.name}” পরিবর্তন (${parts.join(', ')})`,
        tone: body.status === 'disabled' ? 'bad' : 'warn',
        ip: req.ip,
        meta: { staffId: id, ...body },
      });
    });
    const row = await q1<StaffRow>(ctx.db, `${STAFF_SQL} WHERE st.id = $1`, [id]);
    return staffJson(row as StaffRow);
  });

  app.post('/admin/staff/:id/reset', { preHandler: settingsStaff }, async (req) => {
    const actor = mustStaff(req);
    const id = idParam(req.params);
    if (id === actor.id) throw new ApiError('FORBIDDEN', 'নিজের অ্যাকাউন্ট এখান থেকে রিসেট করা যাবে না।');
    const temp = tempPassword();
    const hash = await hashPassword(temp);
    await tx(ctx.db, async (c) => {
      const target = await q1<TargetRow>(c, TARGET_SQL, [id]);
      if (!target) throw new ApiError('NOT_FOUND');
      assertCanManage(actor, target);
      await unlockAll(c, 'staff', id);
      await c.query(
        `UPDATE staff SET password_hash = $2, must_change_password = true, totp_enabled = false, totp_secret_enc = NULL, totp_last_step = NULL,
                token_version = token_version + 1, failed_logins = 0, lock_count = 0, locked_until = NULL, updated_at = now()
          WHERE id = $1`,
        [id, hash],
      );
      await revokeAllSessions(c, 'staff', id, 'reset');
      await audit(ctx, c, {
        actorType: 'staff',
        actorId: actor.id,
        actorName: actor.name,
        category: 'staff',
        action: 'staff.reset',
        message: `স্টাফ “${target.name}” এর পাসওয়ার্ড ও ২-ধাপ যাচাই রিসেট`,
        tone: 'warn',
        ip: req.ip,
        meta: { staffId: id },
      });
    });
    return { tempPassword: temp };
  });

  // M2: clears login throttles (all IPs) and the account-wide cap for a staff member.
  app.post('/admin/staff/:id/unlock', { preHandler: settingsStaff }, async (req, reply) => {
    const actor = mustStaff(req);
    const id = idParam(req.params);
    await tx(ctx.db, async (c) => {
      const target = await q1<TargetRow>(c, TARGET_SQL, [id]);
      if (!target) throw new ApiError('NOT_FOUND');
      if (id !== actor.id) assertCanManage(actor, target);
      await unlockAll(c, 'staff', id);
      await audit(ctx, c, {
        actorType: 'staff',
        actorId: actor.id,
        actorName: actor.name,
        category: 'staff',
        action: 'staff.unlock',
        message: `স্টাফ “${target.name}” এর লগইন লক খোলা হয়েছে`,
        tone: 'warn',
        ip: req.ip,
        meta: { staffId: id },
      });
    });
    return reply.status(204).send();
  });

  app.post('/admin/roles', { preHandler: settingsStaff }, async (req, reply) => {
    const actor = mustStaff(req);
    const body = parse(CreateRoleSchema, req.body);
    const perms = [...new Set(body.permissions)];
    if (!perms.includes('view')) perms.unshift('view');
    assertGrantable(actor, perms);
    const row = await tx(ctx.db, async (c) => {
      const dup = await q1(c, 'SELECT 1 FROM roles WHERE lower(name) = lower($1)', [body.name]);
      if (dup) throw new ApiError('CONFLICT', 'এই নামে রোল আগেই আছে।', { name: 'এই নামে রোল আগেই আছে' });
      const r = await q1<RoleRow>(
        c,
        'INSERT INTO roles (key, name, permissions, is_system) VALUES ($1,$2,$3,false) RETURNING id, key, name, permissions, is_system',
        [`custom_${randomBytes(5).toString('hex')}`, body.name, perms],
      );
      await audit(ctx, c, {
        actorType: 'staff',
        actorId: actor.id,
        actorName: actor.name,
        category: 'staff',
        action: 'role.create',
        message: `নতুন রোল তৈরি: ${body.name}`,
        tone: 'info',
        ip: req.ip,
        meta: { permissions: perms },
      });
      return r as RoleRow;
    });
    return reply.status(201).send(roleJson(row));
  });

  app.patch('/admin/roles/:id', { preHandler: settingsStaff }, async (req) => {
    const actor = mustStaff(req);
    const id = idParam(req.params);
    const body = parse(PatchRoleSchema, req.body);
    const row = await tx(ctx.db, async (c) => {
      const role = await q1<RoleRow>(c, 'SELECT id, key, name, permissions, is_system FROM roles WHERE id = $1 FOR UPDATE', [id]);
      if (!role) throw new ApiError('NOT_FOUND');
      if (role.is_system) throw new ApiError('FORBIDDEN', 'সিস্টেম রোল পরিবর্তন করা যাবে না।');
      assertGrantable(actor, role.permissions);
      let perms = role.permissions;
      if (body.permissions) {
        perms = [...new Set(body.permissions)];
        if (!perms.includes('view')) perms.unshift('view');
        assertGrantable(actor, perms);
      }
      if (body.name) {
        const dup = await q1(c, 'SELECT 1 FROM roles WHERE lower(name) = lower($1) AND id <> $2', [body.name, id]);
        if (dup) throw new ApiError('CONFLICT', 'এই নামে রোল আগেই আছে।', { name: 'এই নামে রোল আগেই আছে' });
      }
      const updated = await q1<RoleRow>(
        c,
        'UPDATE roles SET name = $2, permissions = $3 WHERE id = $1 RETURNING id, key, name, permissions, is_system',
        [id, body.name ?? role.name, perms],
      );
      // Staff holding this role must pick up the new permissions on their next request: sessions stay, perms are read live.
      await audit(ctx, c, {
        actorType: 'staff',
        actorId: actor.id,
        actorName: actor.name,
        category: 'staff',
        action: 'role.update',
        message: `রোল পরিবর্তন: ${updated?.name ?? role.name}`,
        tone: 'warn',
        ip: req.ip,
        meta: { roleId: id, permissions: perms },
      });
      return updated as RoleRow;
    });
    return roleJson(row);
  });

  app.get('/admin/audit', { preHandler: settingsStaff }, async (req) => {
    const query = parse(AuditQuery, req.query);
    let beforeId: number | null = null;
    if (query.cursor) {
      try {
        const obj = JSON.parse(Buffer.from(query.cursor, 'base64url').toString('utf8')) as { id?: unknown };
        if (typeof obj.id !== 'string' || !/^\d{1,18}$/.test(obj.id)) throw new Error('bad');
        beforeId = Number(obj.id);
      } catch {
        throw new ApiError('INVALID_INPUT', undefined, { cursor: 'সঠিক তথ্য দিন' });
      }
    }
    const rows = await q<{ id: number; at: Date; actor_name: string; category: string; message: string; tone: string }>(
      ctx.db,
      `SELECT id, at, actor_name, category, message, tone FROM audit_log
        WHERE ($1::text = 'all' OR category = $1) AND ($2::bigint IS NULL OR id < $2::bigint)
        ORDER BY id DESC LIMIT $3`,
      [query.category, beforeId, query.limit + 1],
    );
    const more = rows.length > query.limit;
    const items = rows.slice(0, query.limit);
    const last = items[items.length - 1];
    return {
      items: items.map((r) => ({ id: String(r.id), at: r.at.toISOString(), actorName: r.actor_name, category: r.category, message: r.message, tone: r.tone })),
      nextCursor: more && last ? encodeCursor(last.at, String(last.id)) : null,
    };
  });

  app.get('/admin/settings', { preHandler: view }, async () => settingsJson(await getSettings(ctx.db)));

  app.put('/admin/settings', { preHandler: settingsStaff }, async (req) => {
    const actor = mustStaff(req);
    const body = parse(SettingsSchema, req.body);
    const before = await getSettings(ctx.db);
    const next = {
      company: body.company,
      payment: body.payment,
      security: { autoLogoutMinutes: body.security.autoLogoutMinutes },
    };
    const changed: string[] = [];
    if (JSON.stringify(before.company) !== JSON.stringify(next.company)) changed.push('কোম্পানির তথ্য');
    if (JSON.stringify(before.payment) !== JSON.stringify(next.payment)) changed.push('পেমেন্ট মাধ্যম');
    if (before.security.autoLogoutMinutes !== next.security.autoLogoutMinutes) changed.push('নিরাপত্তা');
    // L9: where shareholders send money is the most fraud-sensitive setting.
    const paymentDetailsChanged = (['officeAddress', 'bankName', 'accountName', 'accountNumber'] as const).some(
      (k) => before.payment[k] !== next.payment[k],
    );
    if (paymentDetailsChanged && actor.roleKey !== 'super_admin') {
      throw new ApiError('FORBIDDEN', 'পেমেন্টের তথ্য (ব্যাংক অ্যাকাউন্ট ও অফিসের ঠিকানা) শুধু সুপার অ্যাডমিন পরিবর্তন করতে পারেন।');
    }
    await tx(ctx.db, async (c) => {
      for (const [key, value] of Object.entries(next)) {
        await c.query(
          `INSERT INTO settings (key, value, updated_by, updated_at) VALUES ($1,$2,$3,now())
           ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_by = EXCLUDED.updated_by, updated_at = now()`,
          [key, JSON.stringify(value), actor.id],
        );
      }
      if (paymentDetailsChanged) {
        await createNotice(c, {
          title: 'পেমেন্টের তথ্য পরিবর্তিত হয়েছে',
          body: 'কোম্পানির পেমেন্টের তথ্য (ব্যাংক অ্যাকাউন্ট বা অফিসের ঠিকানা) হালনাগাদ হয়েছে। টাকা পাঠানোর আগে অ্যাপের “শেয়ার কিনুন” স্ক্রিনে নতুন তথ্য দেখে নিন। কোম্পানি কখনো ফোন বা মেসেজে অ্যাকাউন্ট নম্বর বদলানোর কথা বলে না।',
          category: 'company',
          audience: 'all',
          createdBy: actor.id,
        });
      }
      if (changed.length) {
        await audit(ctx, c, {
          actorType: 'staff',
          actorId: actor.id,
          actorName: actor.name,
          category: 'settings',
          action: 'settings.update',
          message: `সেটিংস হালনাগাদ (${changed.join(', ')})`,
          tone: 'warn',
          ip: req.ip,
          meta: { sections: changed, paymentDetailsChanged },
        });
      }
    });
    return settingsJson(await getSettings(ctx.db));
  });
}
