import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { q1 } from '../src/db.js';
import { verifyAuditChain } from '../src/services/audit.js';
import { hashPassword } from '../src/util/passwords.js';
import { currentStep, totpAt } from '../src/util/totp.js';
import { addDays, dhakaDate } from '../src/util/time.js';
import { bearer, err, newStaff, newUser, ownerPool, randomMobile, setupApp, teardownApp, todayRatePaisa, type TestEnv } from './helpers.js';

let env: TestEnv;
let admin: Awaited<ReturnType<typeof newStaff>>;
const A = (path: string) => `/api/v1${path}`;

beforeAll(async () => {
  env = await setupApp();
  admin = await newStaff(env, 'super_admin');
});
afterAll(async () => {
  await teardownApp();
});

describe('admin authentication', () => {
  it('enrolls TOTP on first login, rejects replayed and wrong codes', async () => {
    const email = `fresh${Date.now()}@example.com`;
    const role = await q1<{ id: string }>(env.db, `SELECT id FROM roles WHERE key = 'support'`);
    await env.db.query(
      `INSERT INTO staff (name, email, mobile, password_hash, role_id, must_change_password) VALUES ('মিম আক্তার', $1, $2, $3, $4, true)`,
      [email, randomMobile(), await hashPassword('Temp-pass-11'), role?.id],
    );
    const l1 = await env.app.inject({ method: 'POST', url: A('/admin/auth/login'), payload: { email, password: 'Temp-pass-11', deviceId: 'adm-dev-1' } });
    expect(l1.statusCode).toBe(200);
    const { mfaToken, enrollment } = l1.json();
    expect(enrollment.secret).toMatch(/^[A-Z2-7]{32}$/);
    expect(enrollment.otpauthUri).toContain('otpauth://totp/');

    const wrong = await env.app.inject({ method: 'POST', url: A('/admin/auth/mfa'), payload: { mfaToken, code: '000000' } });
    expect(err(wrong).code).toBe('MFA_INVALID');

    const code = totpAt(enrollment.secret, currentStep());
    const ok = await env.app.inject({ method: 'POST', url: A('/admin/auth/mfa'), payload: { mfaToken, code } });
    expect(ok.statusCode).toBe(200);
    expect(ok.json().staff.mustChangePassword).toBe(true);
    expect(ok.json().staff.roleKey).toBe('support');

    // challenge is single-use
    const reuseToken = await env.app.inject({ method: 'POST', url: A('/admin/auth/mfa'), payload: { mfaToken, code } });
    expect(err(reuseToken).code).toBe('MFA_INVALID');

    // a new login with the same (already used) code is a replay
    const l2 = await env.app.inject({ method: 'POST', url: A('/admin/auth/login'), payload: { email, password: 'Temp-pass-11', deviceId: 'adm-dev-1' } });
    expect(l2.json().enrollment).toBeNull();
    const replay = await env.app.inject({ method: 'POST', url: A('/admin/auth/mfa'), payload: { mfaToken: l2.json().mfaToken, code } });
    expect(err(replay).code).toBe('MFA_INVALID');

    // temp password gate
    const tok = ok.json().tokens.accessToken;
    const dash = await env.app.inject({ method: 'GET', url: A('/admin/dashboard'), headers: bearer(tok) });
    expect(err(dash).code).toBe('PASSWORD_CHANGE_REQUIRED');
    const me = await env.app.inject({ method: 'GET', url: A('/admin/me'), headers: bearer(tok) });
    expect(me.statusCode).toBe(200);
    const wrongPw = await env.app.inject({
      method: 'POST',
      url: A('/admin/me/password'),
      headers: bearer(tok),
      payload: { currentPassword: 'bad-pass-1', newPassword: 'Brand-new-88' },
    });
    expect(wrongPw.statusCode).toBe(400);
    const ch = await env.app.inject({
      method: 'POST',
      url: A('/admin/me/password'),
      headers: bearer(tok),
      payload: { currentPassword: 'Temp-pass-11', newPassword: 'Brand-new-88' },
    });
    expect(ch.statusCode).toBe(204);
    const dash2 = await env.app.inject({ method: 'GET', url: A('/admin/dashboard'), headers: bearer(tok) });
    expect(dash2.statusCode).toBe(200);
  });

  it('an mfaToken cannot be used as an access token and vice versa', async () => {
    const l1 = await env.app.inject({ method: 'POST', url: A('/admin/auth/login'), payload: { email: admin.email, password: admin.password, deviceId: 'adm-dev-1' } });
    const r = await env.app.inject({ method: 'GET', url: A('/admin/me'), headers: bearer(l1.json().mfaToken) });
    expect(r.statusCode).toBe(401);
    const m = await env.app.inject({ method: 'POST', url: A('/admin/auth/mfa'), payload: { mfaToken: admin.token, code: '123456' } });
    expect(err(m).code).toBe('MFA_INVALID');
  });

  it('blocks a challenge after 5 wrong OTPs', async () => {
    const s = await newStaff(env, 'support');
    const l = await env.app.inject({ method: 'POST', url: A('/admin/auth/login'), payload: { email: s.email, password: s.password, deviceId: 'adm-dev-1' } });
    const mfaToken = l.json().mfaToken;
    for (let i = 0; i < 5; i++) await env.app.inject({ method: 'POST', url: A('/admin/auth/mfa'), payload: { mfaToken, code: '111111' } });
    const good = await env.app.inject({ method: 'POST', url: A('/admin/auth/mfa'), payload: { mfaToken, code: totpAt(s.secret, currentStep() + 1) } });
    expect(good.statusCode).not.toBe(200);
  });

  it('logs staff out after the idle timeout', async () => {
    const s = await newStaff(env, 'support');
    const ok = await env.app.inject({ method: 'GET', url: A('/admin/dashboard'), headers: bearer(s.token) });
    expect(ok.statusCode).toBe(200);
    await env.db.query(`UPDATE sessions SET last_active_at = now() - interval '11 minutes' WHERE subject_id = $1`, [s.id]);
    const idle = await env.app.inject({ method: 'GET', url: A('/admin/dashboard'), headers: bearer(s.token) });
    expect(idle.statusCode).toBe(401);
    expect(err(idle).code).toBe('SESSION_EXPIRED');
    const refresh = await env.app.inject({ method: 'POST', url: A('/admin/auth/refresh'), payload: { refreshToken: s.refresh } });
    expect(err(refresh).code).toBe('SESSION_EXPIRED');
  });

  it('staff refresh tokens do not work on the user refresh endpoint', async () => {
    const s = await newStaff(env, 'support');
    const r = await env.app.inject({ method: 'POST', url: A('/auth/refresh'), payload: { refreshToken: s.refresh } });
    expect(err(r).code).toBe('SESSION_EXPIRED');
    const ok = await env.app.inject({ method: 'POST', url: A('/admin/auth/refresh'), payload: { refreshToken: s.refresh } });
    expect(ok.statusCode).toBe(200);
  });
});

describe('RBAC', () => {
  it('kyc_officer cannot approve payments or change rates; support cannot approve KYC', async () => {
    const officer = await newStaff(env, 'kyc_officer');
    const support = await newStaff(env, 'support');
    const u = await newUser(env.app);
    await env.app.inject({ method: 'POST', url: A(`/admin/kyc/${u.id}/approve`), headers: bearer(admin.token) });
    const rate = await todayRatePaisa(env.db);
    const o = await env.app.inject({
      method: 'POST',
      url: A('/me/orders'),
      headers: bearer(u.token),
      payload: { quantity: 1, method: 'cash', reference: null, expectedRatePaisa: rate, acceptRules: true },
    });
    const pay = await env.app.inject({ method: 'POST', url: A(`/admin/orders/${o.json().id}/approve`), headers: bearer(officer.token) });
    expect(pay.statusCode).toBe(403);
    expect(err(pay).code).toBe('FORBIDDEN');
    const sched = await env.app.inject({
      method: 'PUT',
      url: A('/admin/rates/schedule'),
      headers: bearer(officer.token),
      payload: { items: [{ date: addDays(dhakaDate(), 1), ratePaisa: 100000 }], notify: false },
    });
    expect(sched.statusCode).toBe(403);
    const u2 = await newUser(env.app);
    const kyc = await env.app.inject({ method: 'POST', url: A(`/admin/kyc/${u2.id}/approve`), headers: bearer(support.token) });
    expect(kyc.statusCode).toBe(403);
    const staffList = await env.app.inject({ method: 'GET', url: A('/admin/staff'), headers: bearer(support.token) });
    expect(staffList.statusCode).toBe(403);
    const audit = await env.app.inject({ method: 'GET', url: A('/admin/audit'), headers: bearer(support.token) });
    expect(audit.statusCode).toBe(403);
    const files = await env.app.inject({ method: 'GET', url: A('/admin/kyc-files/00000000-0000-0000-0000-000000000000'), headers: bearer(support.token) });
    expect(files.statusCode).toBe(403);
    const view = await env.app.inject({ method: 'GET', url: A('/admin/shareholders'), headers: bearer(support.token) });
    expect(view.statusCode).toBe(200);
  });

  it('prevents privilege escalation and super-admin tampering', async () => {
    const list = (await env.app.inject({ method: 'GET', url: A('/admin/staff'), headers: bearer(admin.token) })).json();
    const roles = list.roles as { id: string; key: string; isSystem: boolean }[];
    const superRole = roles.find((r) => r.key === 'super_admin') as { id: string };
    const supportRole = roles.find((r) => r.key === 'support') as { id: string };

    const custom = await env.app.inject({
      method: 'POST',
      url: A('/admin/roles'),
      headers: bearer(admin.token),
      payload: { name: 'স্টাফ ম্যানেজার', permissions: ['settings_staff'] },
    });
    expect(custom.statusCode).toBe(201);
    expect(custom.json().permissions).toEqual(['view', 'settings_staff']);
    expect(custom.json().isSystem).toBe(false);
    const manager = await newStaff(env, custom.json().key);

    const toSuper = await env.app.inject({
      method: 'POST',
      url: A('/admin/staff'),
      headers: bearer(manager.token),
      payload: { name: 'হ্যাকার ভাই', email: `h${Date.now()}@example.com`, mobile: randomMobile(), roleId: superRole.id },
    });
    expect(toSuper.statusCode).toBe(403);
    const grantMore = await env.app.inject({
      method: 'POST',
      url: A('/admin/roles'),
      headers: bearer(manager.token),
      payload: { name: 'সব ক্ষমতা', permissions: ['payment_approve'] },
    });
    expect(grantMore.statusCode).toBe(403);
    const widenOwnRole = await env.app.inject({
      method: 'PATCH',
      url: A(`/admin/roles/${custom.json().id}`),
      headers: bearer(manager.token),
      payload: { permissions: ['settings_staff', 'rate_change'] },
    });
    expect(widenOwnRole.statusCode).toBe(403);
    const selfEdit = await env.app.inject({ method: 'PATCH', url: A(`/admin/staff/${manager.id}`), headers: bearer(manager.token), payload: { roleId: supportRole.id } });
    expect(selfEdit.statusCode).toBe(403);
    const touchSuper = await env.app.inject({ method: 'POST', url: A(`/admin/staff/${admin.id}/reset`), headers: bearer(manager.token) });
    expect(touchSuper.statusCode).toBe(403);
    const disableSuper = await env.app.inject({ method: 'PATCH', url: A(`/admin/staff/${admin.id}`), headers: bearer(manager.token), payload: { status: 'disabled' } });
    expect(disableSuper.statusCode).toBe(403);
    const sysRole = await env.app.inject({ method: 'PATCH', url: A(`/admin/roles/${supportRole.id}`), headers: bearer(admin.token), payload: { name: 'নতুন নাম' } });
    expect(sysRole.statusCode).toBe(403);
    const selfDisable = await env.app.inject({ method: 'PATCH', url: A(`/admin/staff/${admin.id}`), headers: bearer(admin.token), payload: { status: 'disabled' } });
    expect(selfDisable.statusCode).toBe(403);

    const created = await env.app.inject({
      method: 'POST',
      url: A('/admin/staff'),
      headers: bearer(manager.token),
      payload: { name: 'শারমিন আক্তার', email: `sharmin${Date.now()}@example.com`, mobile: randomMobile(), roleId: supportRole.id },
    });
    expect(created.statusCode).toBe(201);
    expect(created.json().tempPassword).toHaveLength(12);
    expect(created.json().staff.mfaEnrolled).toBe(false);
    const dupEmail = await env.app.inject({
      method: 'POST',
      url: A('/admin/staff'),
      headers: bearer(admin.token),
      payload: { name: 'শারমিন আক্তার', email: created.json().staff.email, mobile: randomMobile(), roleId: supportRole.id },
    });
    expect(dupEmail.statusCode).toBe(409);
  });

  it('disabling a staff member kills their session; role changes apply on the next request', async () => {
    const s = await newStaff(env, 'support');
    const accountsRole = await q1<{ id: string }>(env.db, `SELECT id FROM roles WHERE key = 'accounts'`);
    const ch = await env.app.inject({ method: 'PATCH', url: A(`/admin/staff/${s.id}`), headers: bearer(admin.token), payload: { roleId: accountsRole?.id } });
    expect(ch.statusCode).toBe(200);
    expect(ch.json().roleKey).toBe('accounts');
    const old = await env.app.inject({ method: 'GET', url: A('/admin/dashboard'), headers: bearer(s.token) });
    expect(old.statusCode).toBe(401);
    const s2 = await newStaff(env, 'support');
    const dis = await env.app.inject({ method: 'PATCH', url: A(`/admin/staff/${s2.id}`), headers: bearer(admin.token), payload: { status: 'disabled' } });
    expect(dis.json().status).toBe('disabled');
    const dead = await env.app.inject({ method: 'GET', url: A('/admin/dashboard'), headers: bearer(s2.token) });
    expect(dead.statusCode).toBe(401);
    const login = await env.app.inject({ method: 'POST', url: A('/admin/auth/login'), payload: { email: s2.email, password: s2.password, deviceId: 'adm-dev-1' } });
    expect(login.statusCode).toBe(401);
  });
});

describe('rates', () => {
  it('validates the 15-day schedule and never edits today', async () => {
    const today = dhakaDate();
    const past = await env.app.inject({
      method: 'PUT',
      url: A('/admin/rates/schedule'),
      headers: bearer(admin.token),
      payload: { items: [{ date: addDays(today, -1), ratePaisa: 100000 }], notify: false },
    });
    expect(past.statusCode).toBe(400);
    const todayEdit = await env.app.inject({
      method: 'PUT',
      url: A('/admin/rates/schedule'),
      headers: bearer(admin.token),
      payload: { items: [{ date: today, ratePaisa: 100000 }], notify: false },
    });
    expect(todayEdit.statusCode).toBe(400);
    const far = await env.app.inject({
      method: 'PUT',
      url: A('/admin/rates/schedule'),
      headers: bearer(admin.token),
      payload: { items: [{ date: addDays(today, 16), ratePaisa: 100000 }], notify: false },
    });
    expect(far.statusCode).toBe(400);
    const zero = await env.app.inject({
      method: 'PUT',
      url: A('/admin/rates/schedule'),
      headers: bearer(admin.token),
      payload: { items: [{ date: addDays(today, 2), ratePaisa: 0 }], notify: false },
    });
    expect(zero.statusCode).toBe(400);
    const float = await env.app.inject({
      method: 'PUT',
      url: A('/admin/rates/schedule'),
      headers: bearer(admin.token),
      payload: { items: [{ date: addDays(today, 2), ratePaisa: 1012.5 }], notify: false },
    });
    expect(float.statusCode).toBe(400);

    const items = Array.from({ length: 15 }, (_, i) => ({ date: addDays(today, i + 1), ratePaisa: 101000 + i * 500 }));
    const ok = await env.app.inject({ method: 'PUT', url: A('/admin/rates/schedule'), headers: bearer(admin.token), payload: { items, notify: true } });
    expect(ok.statusCode).toBe(200);
    const r = ok.json();
    expect(r.schedule).toHaveLength(15);
    expect(r.schedule.every((s: { ratePaisa: number | null }) => s.ratePaisa !== null)).toBe(true);
    expect(r.tomorrow.ratePaisa).toBe(101000);
    expect(r.today.ratePaisa).toBe(await todayRatePaisa(env.db));
    expect(r.history[0].date).toBe(today);

    const audit = await q1<{ message: string }>(env.db, `SELECT message FROM audit_log WHERE action = 'rate.schedule' ORDER BY id DESC LIMIT 1`);
    expect(audit?.message).toContain('শেয়ারের রেট সূচি হালনাগাদ (১৫ দিন');
  });
});

describe('audit log', () => {
  it('is append-only and its hash chain verifies', async () => {
    // the API's runtime role has no UPDATE/DELETE/TRUNCATE on audit_log at all …
    await expect(env.db.query(`UPDATE audit_log SET message = 'tampered' WHERE id = (SELECT min(id) FROM audit_log)`)).rejects.toThrow(/permission denied/);
    await expect(env.db.query('DELETE FROM audit_log')).rejects.toThrow(/permission denied/);
    await expect(env.db.query('TRUNCATE audit_log')).rejects.toThrow(/permission denied/);
    // … and even the owner is stopped by the trigger
    const owner = ownerPool();
    try {
      await expect(owner.query(`UPDATE audit_log SET message = 'tampered' WHERE id = (SELECT min(id) FROM audit_log)`)).rejects.toThrow(/append-only/);
      await expect(owner.query('DELETE FROM audit_log')).rejects.toThrow(/append-only/);
      await expect(owner.query('TRUNCATE audit_log')).rejects.toThrow(/append-only/);
    } finally {
      await owner.end();
    }
    expect(await verifyAuditChain(env.app.ctx, env.db)).toBeNull();

    const page = await env.app.inject({ method: 'GET', url: A('/admin/audit?category=login&limit=5'), headers: bearer(admin.token) });
    expect(page.statusCode).toBe(200);
    const body = page.json();
    expect(body.items.length).toBeGreaterThan(0);
    expect(body.items.every((i: { category: string }) => i.category === 'login')).toBe(true);
    expect(['info', 'ok', 'bad', 'warn']).toContain(body.items[0].tone);
    if (body.nextCursor) {
      const p2 = await env.app.inject({ method: 'GET', url: A(`/admin/audit?category=login&limit=5&cursor=${body.nextCursor}`), headers: bearer(admin.token) });
      expect(p2.statusCode).toBe(200);
      expect(Number(p2.json().items[0].id)).toBeLessThan(Number(body.items[4].id));
    }
  });
});

describe('admin data endpoints', () => {
  it('dashboard, sales stats, shareholders search/detail, contact reveal is audited', async () => {
    const u = await newUser(env.app, { fullName: 'নাজমুল হাসান' });
    const dash = await env.app.inject({ method: 'GET', url: A('/admin/dashboard'), headers: bearer(admin.token) });
    expect(dash.statusCode).toBe(200);
    expect(dash.json().shareholderCount).toBeGreaterThan(0);
    for (const p of ['daily', 'weekly', 'monthly', 'yearly']) {
      const s = (await env.app.inject({ method: 'GET', url: A(`/admin/stats/sales?period=${p}`), headers: bearer(admin.token) })).json();
      expect(s.points.length).toBe({ daily: 7, weekly: 8, monthly: 12, yearly: 5 }[p]);
    }
    const search = (await env.app.inject({ method: 'GET', url: A(`/admin/shareholders?q=${encodeURIComponent('নাজমুল')}`), headers: bearer(admin.token) })).json();
    expect(search.items.some((i: { id: string }) => i.id === u.id)).toBe(true);
    const byId = (await env.app.inject({ method: 'GET', url: A(`/admin/shareholders?q=${u.me.shareholderNo}`), headers: bearer(admin.token) })).json();
    expect(byId.items[0].id).toBe(u.id);
    const wild = (await env.app.inject({ method: 'GET', url: A(`/admin/shareholders?q=${encodeURIComponent('%')}`), headers: bearer(admin.token) })).json();
    expect(wild.items).toHaveLength(0);
    const pending = (await env.app.inject({ method: 'GET', url: A('/admin/shareholders?status=pending'), headers: bearer(admin.token) })).json();
    expect(pending.items.every((i: { kycStatus: string }) => i.kycStatus !== 'verified')).toBe(true);

    const d = (await env.app.inject({ method: 'GET', url: A(`/admin/shareholders/${u.id}`), headers: bearer(admin.token) })).json();
    expect(d.mobileMasked).toMatch(/^01\d{3}-•{6}$/);
    expect(d.emailMasked).toContain('•••@');
    expect(d.kycFiles).toHaveLength(3);
    expect(JSON.stringify(d)).not.toContain(u.data.mobile);
    const contact = (await env.app.inject({ method: 'GET', url: A(`/admin/shareholders/${u.id}/contact`), headers: bearer(admin.token) })).json();
    expect(contact.mobile).toBe(u.data.mobile);
    const logged = await q1(env.db, `SELECT 1 FROM audit_log WHERE action = 'shareholder.contact.view' AND meta->>'shareholderId' = $1`, [u.id]);
    expect(logged).toBeTruthy();

    const msg = await env.app.inject({
      method: 'POST',
      url: A(`/admin/shareholders/${u.id}/message`),
      headers: bearer(admin.token),
      payload: { title: 'আপনার জন্য বার্তা', body: 'অফিসে যোগাযোগ করুন।' },
    });
    expect(msg.statusCode).toBe(204);
    const n = (await env.app.inject({ method: 'GET', url: A('/notices'), headers: bearer(u.token) })).json();
    expect(n.items[0].category).toBe('message');
    const nf = await env.app.inject({ method: 'GET', url: A('/admin/shareholders/00000000-0000-0000-0000-000000000000'), headers: bearer(admin.token) });
    expect(nf.statusCode).toBe(404);
  });

  it('orders list has totals and filters', async () => {
    const r = await env.app.inject({ method: 'GET', url: A('/admin/orders?status=pending'), headers: bearer(admin.token) });
    expect(r.statusCode).toBe(200);
    const b = r.json();
    expect(typeof b.todayTotalPaisa).toBe('number');
    expect(typeof b.monthTotalPaisa).toBe('number');
    expect(b.items.every((i: { status: string }) => i.status === 'pending')).toBe(true);
  });

  it('reports return rows for every type and reject bad ranges', async () => {
    for (const t of ['sales', 'shareholders', 'transactions', 'dividends', 'kyc']) {
      const r = await env.app.inject({ method: 'GET', url: A(`/admin/reports/${t}?from=2020-01-01&to=${dhakaDate()}`), headers: bearer(admin.token) });
      expect(r.statusCode).toBe(200);
      const rep = r.json();
      expect(rep.type).toBe(t);
      expect(rep.columns.length).toBeGreaterThan(3);
      for (const row of rep.rows) expect(row).toHaveLength(rep.columns.length);
    }
    const sh = (await env.app.inject({ method: 'GET', url: A('/admin/reports/shareholders'), headers: bearer(admin.token) })).json();
    expect(sh.rows.length).toBeGreaterThan(0);
    expect(sh.rows[0][2]).toMatch(/•/); // mobile masked in exports
    const bad = await env.app.inject({ method: 'GET', url: A('/admin/reports/sales?from=2026-12-01&to=2026-01-01'), headers: bearer(admin.token) });
    expect(bad.statusCode).toBe(400);
    const unknown = await env.app.inject({ method: 'GET', url: A('/admin/reports/passwords'), headers: bearer(admin.token) });
    expect(unknown.statusCode).toBe(404);
    const accounts = await newStaff(env, 'accounts');
    const allowed = await env.app.inject({ method: 'GET', url: A('/admin/reports/sales'), headers: bearer(accounts.token) });
    expect(allowed.statusCode).toBe(200);
    const kycOfficer = await newStaff(env, 'kyc_officer');
    const denied = await env.app.inject({ method: 'GET', url: A('/admin/reports/sales'), headers: bearer(kycOfficer.token) });
    expect(denied.statusCode).toBe(403);
  });

  it('dividends: declare once per fiscal year with payouts and a notice', async () => {
    const pool = (await env.app.inject({ method: 'GET', url: A('/admin/pool'), headers: bearer(admin.token) })).json();
    if (pool.soldShares === 0) {
      const u = await newUser(env.app);
      await env.app.inject({ method: 'POST', url: A(`/admin/kyc/${u.id}/approve`), headers: bearer(admin.token) });
      const rate = await todayRatePaisa(env.db);
      const o = await env.app.inject({ method: 'POST', url: A('/me/orders'), headers: bearer(u.token), payload: { quantity: 4, method: 'cash', reference: null, expectedRatePaisa: rate, acceptRules: true } });
      await env.app.inject({ method: 'POST', url: A(`/admin/orders/${o.json().id}/approve`), headers: bearer(admin.token) });
    }
    const sold = (await env.app.inject({ method: 'GET', url: A('/admin/dividends'), headers: bearer(admin.token) })).json().soldShares;
    const d = await env.app.inject({
      method: 'POST',
      url: A('/admin/dividends'),
      headers: bearer(admin.token),
      payload: { fiscalYear: '2026', perSharePaisa: 8000, distributionDate: addDays(dhakaDate(), 30) },
    });
    expect(d.statusCode).toBe(201);
    expect(d.json().totalShares).toBe(sold);
    expect(d.json().totalPaisa).toBe(sold * 8000);
    expect(d.json().status).toBe('declared');
    const dup = await env.app.inject({
      method: 'POST',
      url: A('/admin/dividends'),
      headers: bearer(admin.token),
      payload: { fiscalYear: '2026', perSharePaisa: 8000, distributionDate: addDays(dhakaDate(), 30) },
    });
    expect(dup.statusCode).toBe(409);
    const pastDate = await env.app.inject({
      method: 'POST',
      url: A('/admin/dividends'),
      headers: bearer(admin.token),
      payload: { fiscalYear: '2027', perSharePaisa: 8000, distributionDate: '2020-01-01' },
    });
    expect(pastDate.statusCode).toBe(400);
  });

  it('rules: draft, version must increase, publish notifies', async () => {
    const sections = [{ title: 'শেয়ার কেনার নিয়ম', items: ['নিয়ম ১', 'নিয়ম ২'] }];
    const draft = await env.app.inject({ method: 'PUT', url: A('/admin/rules/draft'), headers: bearer(admin.token), payload: { sections } });
    expect(draft.statusCode).toBe(200);
    expect(draft.json().draft.sections).toEqual(sections);
    const lower = await env.app.inject({
      method: 'POST',
      url: A('/admin/rules/publish'),
      headers: bearer(admin.token),
      payload: { version: '1.0', effectiveDate: dhakaDate(), summary: '', sections, notify: false },
    });
    expect(lower.statusCode).toBe(409);
    const pub = await env.app.inject({
      method: 'POST',
      url: A('/admin/rules/publish'),
      headers: bearer(admin.token),
      payload: { version: '1.10', effectiveDate: dhakaDate(), summary: 'বিক্রির নিয়ম যোগ হয়েছে', sections, notify: true },
    });
    expect(pub.statusCode).toBe(200);
    expect(pub.json().current.version).toBe('1.10');
    expect(pub.json().draft).toBeNull();
    const pubRules = (await env.app.inject({ method: 'GET', url: A('/rules') })).json();
    expect(pubRules.version).toBe('1.10');
    const n = await q1<{ title: string }>(env.db, `SELECT title FROM notices WHERE category = 'rules' ORDER BY created_at DESC LIMIT 1`);
    expect(n?.title).toContain('১.১০');
    const injected = await env.app.inject({
      method: 'PUT',
      url: A('/admin/rules/draft'),
      headers: bearer(admin.token),
      payload: { sections: [{ title: 'x', items: ['y'], extra: true }] },
    });
    expect(injected.statusCode).toBe(400);
  });

  it('notices: broadcast + selected recipients', async () => {
    const u = await newUser(env.app);
    const sel = await env.app.inject({
      method: 'POST',
      url: A('/admin/notices'),
      headers: bearer(admin.token),
      payload: { title: 'বার্ষিক সাধারণ সভা', body: 'সভা ২০ ডিসেম্বর।', audience: 'selected', userIds: [u.id] },
    });
    expect(sel.statusCode).toBe(201);
    expect(sel.json().recipientCount).toBe(1);
    const empty = await env.app.inject({
      method: 'POST',
      url: A('/admin/notices'),
      headers: bearer(admin.token),
      payload: { title: 'বার্ষিক সাধারণ সভা', body: 'সভা', audience: 'selected', userIds: [] },
    });
    expect(empty.statusCode).toBe(400);
    const all = await env.app.inject({
      method: 'POST',
      url: A('/admin/notices'),
      headers: bearer(admin.token),
      payload: { title: 'নতুন শেয়ার বিক্রি শুরু', body: 'নতুন ইস্যু উন্মুক্ত।', audience: 'all', userIds: [] },
    });
    expect(all.json().audience).toBe('all');
    const list = (await env.app.inject({ method: 'GET', url: A('/admin/notices'), headers: bearer(admin.token) })).json();
    expect(list.items[0].title).toBe('নতুন শেয়ার বিক্রি শুরু');
    const mine = (await env.app.inject({ method: 'GET', url: A('/notices'), headers: bearer(u.token) })).json();
    expect(mine.items.map((i: { title: string }) => i.title)).toEqual(expect.arrayContaining(['বার্ষিক সাধারণ সভা', 'নতুন শেয়ার বিক্রি শুরু']));
  });

  it('settings: validated, needs one payment method, mfaRequired is read-only true', async () => {
    const cur = (await env.app.inject({ method: 'GET', url: A('/admin/settings'), headers: bearer(admin.token) })).json();
    expect(cur.security.mfaRequired).toBe(true);
    const none = await env.app.inject({
      method: 'PUT',
      url: A('/admin/settings'),
      headers: bearer(admin.token),
      payload: { ...cur, payment: { ...cur.payment, cashEnabled: false, bankEnabled: false } },
    });
    expect(none.statusCode).toBe(400);
    const turnOffMfa = await env.app.inject({
      method: 'PUT',
      url: A('/admin/settings'),
      headers: bearer(admin.token),
      payload: { ...cur, security: { autoLogoutMinutes: 10, mfaRequired: false } },
    });
    expect(turnOffMfa.statusCode).toBe(400);
    const ok = await env.app.inject({
      method: 'PUT',
      url: A('/admin/settings'),
      headers: bearer(admin.token),
      payload: { ...cur, payment: { ...cur.payment, officeAddress: 'ভূঁইগড়, নারায়ণগঞ্জ' }, security: { autoLogoutMinutes: 10 } },
    });
    expect(ok.statusCode).toBe(200);
    expect(ok.json().payment.officeAddress).toBe('ভূঁইগড়, নারায়ণগঞ্জ');
    const u = await newUser(env.app);
    const pi = (await env.app.inject({ method: 'GET', url: A('/me/payment-info'), headers: bearer(u.token) })).json();
    expect(pi.officeAddress).toBe('ভূঁইগড়, নারায়ণগঞ্জ');
  });

  it('pool issue increases total shares and is permission-checked', async () => {
    const before = (await env.app.inject({ method: 'GET', url: A('/admin/pool'), headers: bearer(admin.token) })).json();
    const r = await env.app.inject({ method: 'POST', url: A('/admin/pool/issue'), headers: bearer(admin.token), payload: { additionalShares: 500 } });
    expect(r.json().totalShares).toBe(before.totalShares + 500);
    const acc = await newStaff(env, 'accounts');
    const denied = await env.app.inject({ method: 'POST', url: A('/admin/pool/issue'), headers: bearer(acc.token), payload: { additionalShares: 500 } });
    expect(denied.statusCode).toBe(403);
    const neg = await env.app.inject({ method: 'POST', url: A('/admin/pool/issue'), headers: bearer(admin.token), payload: { additionalShares: -5 } });
    expect(neg.statusCode).toBe(400);
  });
});
