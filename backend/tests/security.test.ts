// Regression tests for the security audit findings (H1, M1–M3, L1–L10).
import { readFileSync } from 'node:fs';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { q1 } from '../src/db.js';
import { runAuditVerifyJob } from '../src/jobs/scheduler.js';
import { assertRestrictedRole, assertSchemaCurrent } from '../src/migrate.js';
import { auditHash, GENESIS_HASH, verifyAuditChain } from '../src/services/audit.js';
import { perIpLockMs } from '../src/services/throttle.js';
import { Crypto, sha256Hex } from '../src/util/crypto.js';
import { currentStep, totpAt } from '../src/util/totp.js';
import { bearer, err, newStaff, newUser, ownerPool, randomNid, registerUser, setupApp, teardownApp, todayRatePaisa, type TestEnv } from './helpers.js';

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

const userLogin = (identifier: string, password: string, remoteAddress: string) =>
  env.app.inject({ method: 'POST', url: A('/auth/login'), remoteAddress, payload: { identifier, password, deviceId: 'sec-device-1' } });
const staffLogin = (email: string, password: string, remoteAddress: string) =>
  env.app.inject({ method: 'POST', url: A('/admin/auth/login'), remoteAddress, payload: { email, password, deviceId: 'sec-device-1' } });

async function customRole(name: string, permissions: string[]) {
  const r = await env.app.inject({ method: 'POST', url: A('/admin/roles'), headers: bearer(admin.token), payload: { name, permissions } });
  expect(r.statusCode).toBe(201);
  return r.json() as { id: string; key: string };
}

describe('H1: staff management needs every permission of the target', () => {
  it('a {view, settings_staff} staff cannot reset, disable, re-role or unlock an accounts member, but can manage a support member', async () => {
    const mgrRole = await customRole('স্টাফ ব্যবস্থাপক এক', ['settings_staff']);
    const manager = await newStaff(env, mgrRole.key);
    const victim = await newStaff(env, 'accounts');
    const support = await newStaff(env, 'support');
    const supportRole = await q1<{ id: string }>(env.db, `SELECT id FROM roles WHERE key = 'support'`);

    const reset = await env.app.inject({ method: 'POST', url: A(`/admin/staff/${victim.id}/reset`), headers: bearer(manager.token) });
    expect(reset.statusCode).toBe(403);
    const disable = await env.app.inject({ method: 'PATCH', url: A(`/admin/staff/${victim.id}`), headers: bearer(manager.token), payload: { status: 'disabled' } });
    expect(disable.statusCode).toBe(403);
    const demote = await env.app.inject({ method: 'PATCH', url: A(`/admin/staff/${victim.id}`), headers: bearer(manager.token), payload: { roleId: supportRole?.id } });
    expect(demote.statusCode).toBe(403);
    const unlock = await env.app.inject({ method: 'POST', url: A(`/admin/staff/${victim.id}/unlock`), headers: bearer(manager.token) });
    expect(unlock.statusCode).toBe(403);
    // the victim's TOTP and password are untouched
    const v = await q1<{ totp_enabled: boolean; must_change_password: boolean }>(env.db, 'SELECT totp_enabled, must_change_password FROM staff WHERE id = $1', [victim.id]);
    expect(v).toEqual({ totp_enabled: true, must_change_password: false });

    const okReset = await env.app.inject({ method: 'POST', url: A(`/admin/staff/${support.id}/reset`), headers: bearer(manager.token) });
    expect(okReset.statusCode).toBe(200);
    const superReset = await env.app.inject({ method: 'POST', url: A(`/admin/staff/${victim.id}/reset`), headers: bearer(admin.token) });
    expect(superReset.statusCode).toBe(200);
  });
});

describe('M1: bank reference uniqueness is race-free', () => {
  it('two accounts submitting the same reference at once: exactly one wins', async () => {
    const a = await newUser(env.app);
    const b = await newUser(env.app);
    for (const u of [a, b]) await env.app.inject({ method: 'POST', url: A(`/admin/kyc/${u.id}/approve`), headers: bearer(admin.token) });
    const rate = await todayRatePaisa(env.db);
    const ref = `RACE-${Date.now()}`;
    const place = (token: string, reference: string) =>
      env.app.inject({ method: 'POST', url: A('/me/orders'), headers: bearer(token), payload: { quantity: 1, method: 'bank', reference, expectedRatePaisa: rate, acceptRules: true } });
    // Hold both buyers' rows so both requests pass the friendly pre-check before either inserts:
    // only the database unique index can stop the second one.
    const blocker = await env.db.connect();
    let results;
    try {
      await blocker.query('BEGIN');
      await blocker.query('SELECT id FROM shareholders WHERE id IN ($1, $2) FOR UPDATE', [a.id, b.id]);
      const pending = [place(a.token, ref), place(b.token, ref.toLowerCase())];
      await new Promise((r) => setTimeout(r, 400));
      await blocker.query('COMMIT');
      results = await Promise.all(pending);
    } finally {
      await blocker.query('ROLLBACK').catch(() => {});
      blocker.release();
    }
    const codes = results.map((r) => r.statusCode).sort();
    expect(codes.filter((c) => c === 201)).toHaveLength(1);
    for (const r of results.filter((x) => x.statusCode !== 201)) {
      expect(r.statusCode).toBe(409);
      expect(err(r).fields?.reference).toBe('এই রেফারেন্স নম্বর আগে ব্যবহার করা হয়েছে');
    }
    const n = await q1<{ n: number }>(env.db, `SELECT count(*)::int AS n FROM orders WHERE lower(reference) = lower($1) AND status <> 'cancelled'`, [ref]);
    expect(n?.n).toBe(1);
  });
});

describe('M2/L1/L2: per-(account, IP) throttling', () => {
  it('locks only the attacking IP, doubles up to 1 h, and resets on success', async () => {
    expect([0, 1, 2, 3].map(perIpLockMs)).toEqual([15, 30, 60, 60].map((m) => m * 60_000));
    const u = await newUser(env.app);
    const ip = '203.0.113.20';
    // 4 failures then a success resets the counter
    for (let i = 0; i < 4; i++) await userLogin(u.data.mobile, 'Wrong-pass-1', ip);
    expect((await userLogin(u.data.mobile, u.data.password, ip)).statusCode).toBe(200);
    for (let i = 0; i < 4; i++) await userLogin(u.data.mobile, 'Wrong-pass-1', ip);
    expect((await userLogin(u.data.mobile, u.data.password, ip)).statusCode).toBe(200);
    // 5 failures lock this IP …
    for (let i = 0; i < 5; i++) await userLogin(u.data.mobile, 'Wrong-pass-1', ip);
    expect((await userLogin(u.data.mobile, u.data.password, ip)).statusCode).toBe(401);
    // … for ~15 min, not 24 h
    const row = await q1<{ locked_until: Date; lock_count: number }>(
      env.db,
      `SELECT locked_until, lock_count FROM login_throttle WHERE subject_type = 'user' AND subject_id = $1 AND ip = $2`,
      [u.id, ip],
    );
    const mins = (row!.locked_until.getTime() - Date.now()) / 60_000;
    expect(mins).toBeGreaterThan(14);
    expect(mins).toBeLessThanOrEqual(15.1);
    // the real owner on another IP is unaffected
    expect((await userLogin(u.data.mobile, u.data.password, '198.51.100.20')).statusCode).toBe(200);
    // after the lock expires the IP may try again
    await env.db.query(`UPDATE login_throttle SET locked_until = now() - interval '1 second' WHERE subject_id = $1 AND ip = $2`, [u.id, ip]);
    expect((await userLogin(u.data.mobile, u.data.password, ip)).statusCode).toBe(200);
  });

  it('the account-wide cap (30 failures/h across IPs) locks for 15 min without escalating', async () => {
    const u = await newUser(env.app);
    for (let i = 0; i < 30; i++) await userLogin(u.data.mobile, 'Wrong-pass-1', `10.9.${Math.floor(i / 4)}.${i}`);
    const fresh = await userLogin(u.data.mobile, u.data.password, '198.51.100.99');
    expect(fresh.statusCode).toBe(401);
    const acct = await q1<{ locked_until: Date }>(env.db, 'SELECT locked_until FROM shareholders WHERE id = $1', [u.id]);
    const mins = (acct!.locked_until.getTime() - Date.now()) / 60_000;
    expect(mins).toBeGreaterThan(14);
    expect(mins).toBeLessThanOrEqual(15.1);
  });

  it('a parallel burst cannot get more than 5 password checks per (account, IP)', async () => {
    const u = await newUser(env.app);
    const ip = '203.0.113.30';
    const burst = await Promise.all(Array.from({ length: 15 }, () => userLogin(u.data.mobile, 'Wrong-pass-1', ip)));
    expect(burst.every((r) => r.statusCode === 401)).toBe(true);
    const checked = await q1<{ n: number }>(env.db, `SELECT count(*)::int AS n FROM login_failures WHERE subject_id = $1 AND ip = $2`, [u.id, ip]);
    expect(checked?.n).toBeLessThanOrEqual(5);
    expect((await userLogin(u.data.mobile, u.data.password, ip)).statusCode).toBe(401);
  });

  it('staff: wrong passwords lock only that IP; the unlock endpoint clears it; responses never say LOCKED', async () => {
    const s = await newStaff(env, 'support');
    const ip = '203.0.113.40';
    const results = [];
    for (let i = 0; i < 6; i++) results.push(await staffLogin(s.email, 'Wrong-pass-9', ip));
    expect(results.every((r) => r.statusCode === 401 && err(r).code === 'UNAUTHORIZED')).toBe(true);
    const blocked = await staffLogin(s.email, s.password, ip);
    expect(blocked.statusCode).toBe(401);
    expect(blocked.json()).toEqual(results[0]?.json());
    expect((await staffLogin(s.email, s.password, '198.51.100.40')).statusCode).toBe(200);

    const unlock = await env.app.inject({ method: 'POST', url: A(`/admin/staff/${s.id}/unlock`), headers: bearer(admin.token) });
    expect(unlock.statusCode).toBe(204);
    expect((await staffLogin(s.email, s.password, ip)).statusCode).toBe(200);
    const logged = await q1(env.db, `SELECT 1 FROM audit_log WHERE action = 'staff.unlock' AND meta->>'staffId' = $1`, [s.id]);
    expect(logged).toBeTruthy();
  });

  it('staff: a known password is not enough — wrong OTPs count against the IP and only a correct OTP resets', async () => {
    const s = await newStaff(env, 'support');
    const ip = '203.0.113.50';
    for (let i = 0; i < 5; i++) {
      await env.db.query('UPDATE staff SET totp_last_step = NULL WHERE id = $1', [s.id]);
      const l = await staffLogin(s.email, s.password, ip);
      if (l.statusCode !== 200) break;
      await env.app.inject({ method: 'POST', url: A('/admin/auth/mfa'), remoteAddress: ip, payload: { mfaToken: l.json().mfaToken, code: '000000' } });
    }
    const l = await staffLogin(s.email, s.password, ip);
    if (l.statusCode === 200) {
      const m = await env.app.inject({
        method: 'POST',
        url: A('/admin/auth/mfa'),
        remoteAddress: ip,
        payload: { mfaToken: l.json().mfaToken, code: totpAt(s.secret, currentStep()) },
      });
      expect(m.statusCode).toBe(401);
      expect(err(m).code).toBe('MFA_INVALID');
    } else {
      expect(l.statusCode).toBe(401);
    }
    // another IP with password + correct OTP works and resets the counters
    await env.db.query('UPDATE staff SET totp_last_step = NULL WHERE id = $1', [s.id]);
    const ok = await staffLogin(s.email, s.password, '198.51.100.50');
    const mfa = await env.app.inject({
      method: 'POST',
      url: A('/admin/auth/mfa'),
      remoteAddress: '198.51.100.50',
      payload: { mfaToken: ok.json().mfaToken, code: totpAt(s.secret, currentStep()) },
    });
    expect(mfa.statusCode).toBe(200);
  });

  it('a mistyped password 4 times does not lock an admin who then gets password + OTP right', async () => {
    const s = await newStaff(env, 'support');
    const ip = '203.0.113.60';
    for (let i = 0; i < 4; i++) await staffLogin(s.email, 'Wrong-pass-9', ip);
    await env.db.query('UPDATE staff SET totp_last_step = NULL WHERE id = $1', [s.id]);
    const l = await staffLogin(s.email, s.password, ip);
    expect(l.statusCode).toBe(200);
    const m = await env.app.inject({ method: 'POST', url: A('/admin/auth/mfa'), remoteAddress: ip, payload: { mfaToken: l.json().mfaToken, code: totpAt(s.secret, currentStep()) } });
    expect(m.statusCode).toBe(200);
  });
});

describe('L3: the last super admin cannot be removed by two admins at once', () => {
  it('mutual disable of the only two super admins leaves one active', async () => {
    const a = await newStaff(env, 'super_admin');
    const b = await newStaff(env, 'super_admin');
    await env.db.query(
      `UPDATE staff SET status = 'disabled'
        WHERE role_id = (SELECT id FROM roles WHERE key = 'super_admin') AND id NOT IN ($1, $2)`,
      [a.id, b.id],
    );
    // Hold both rows so the two requests are forced to overlap inside their transactions.
    const blocker = await env.db.connect();
    try {
      await blocker.query('BEGIN');
      await blocker.query('SELECT id FROM staff WHERE id IN ($1, $2) FOR UPDATE', [a.id, b.id]);
      const p1 = env.app.inject({ method: 'PATCH', url: A(`/admin/staff/${b.id}`), headers: bearer(a.token), payload: { status: 'disabled' } });
      const p2 = env.app.inject({ method: 'PATCH', url: A(`/admin/staff/${a.id}`), headers: bearer(b.token), payload: { status: 'disabled' } });
      await new Promise((r) => setTimeout(r, 400));
      await blocker.query('COMMIT');
      const [r1, r2] = await Promise.all([p1, p2]);
      expect([r1.statusCode, r2.statusCode].filter((c) => c === 200)).toHaveLength(1);
      const active = await q1<{ n: number }>(
        env.db,
        `SELECT count(*)::int AS n FROM staff WHERE status = 'active' AND role_id = (SELECT id FROM roles WHERE key = 'super_admin')`,
      );
      expect(active?.n).toBe(1);
    } finally {
      await blocker.query('ROLLBACK').catch(() => {});
      blocker.release();
      await env.db.query(`UPDATE staff SET status = 'active' WHERE role_id = (SELECT id FROM roles WHERE key = 'super_admin')`);
      admin = await newStaff(env, 'super_admin');
    }
  });
});

describe('L4: no contact oracle in shareholder search', () => {
  it('partial mobile/email finds nothing; exact values match and are audited', async () => {
    const u = await newUser(env.app);
    const partial = (await env.app.inject({ method: 'GET', url: A(`/admin/shareholders?q=${u.data.mobile.slice(0, 7)}`), headers: bearer(admin.token) })).json();
    expect(partial.items.some((i: { id: string }) => i.id === u.id)).toBe(false);
    const partialEmail = (await env.app.inject({ method: 'GET', url: A(`/admin/shareholders?q=${encodeURIComponent(u.data.email.slice(0, 4) + '@')}`), headers: bearer(admin.token) })).json();
    expect(partialEmail.items).toHaveLength(0);
    const before = await q1<{ n: number }>(env.db, `SELECT count(*)::int AS n FROM audit_log WHERE action = 'contact.search'`);
    const exact = (await env.app.inject({ method: 'GET', url: A(`/admin/shareholders?q=${encodeURIComponent('+88' + u.data.mobile)}`), headers: bearer(admin.token) })).json();
    expect(exact.items.map((i: { id: string }) => i.id)).toEqual([u.id]);
    const byEmail = (await env.app.inject({ method: 'GET', url: A(`/admin/shareholders?q=${encodeURIComponent(u.data.email.toUpperCase())}`), headers: bearer(admin.token) })).json();
    expect(byEmail.items.map((i: { id: string }) => i.id)).toEqual([u.id]);
    const after = await q1<{ n: number }>(env.db, `SELECT count(*)::int AS n FROM audit_log WHERE action = 'contact.search'`);
    expect((after?.n ?? 0) - (before?.n ?? 0)).toBe(2);
    const byName = (await env.app.inject({ method: 'GET', url: A(`/admin/shareholders?q=${encodeURIComponent('রহিম')}`), headers: bearer(admin.token) })).json();
    expect(byName.items.length).toBeGreaterThan(0);
  });
});

describe('L5: rejected KYC does not squat an NID', () => {
  it('a rejected account frees its NID; the squatter cannot resubmit it once someone else holds it', async () => {
    const nid = randomNid();
    const squatter = await newUser(env.app, { nidNumber: nid });
    await env.app.inject({ method: 'POST', url: A(`/admin/kyc/${squatter.id}/reject`), headers: bearer(admin.token), payload: { reason: 'নথি মেলেনি' } });
    const owner = await registerUser(env.app, { nidNumber: nid });
    expect(owner.res.statusCode).toBe(201);
    const { multipart, sampleJpeg } = await import('./helpers.js');
    const jpg = await sampleJpeg();
    const m = multipart({ data: JSON.stringify({ nidNumber: nid, dob: '1990-01-01' }) }, { nidFront: { buf: jpg }, nidBack: { buf: jpg }, photo: { buf: jpg } });
    const re = await env.app.inject({ method: 'POST', url: A('/me/kyc'), payload: m.payload, headers: { ...m.headers, ...bearer(squatter.token) } });
    expect(re.statusCode).toBe(409);
    // a third live registration with the same NID is still blocked
    const third = await registerUser(env.app, { nidNumber: nid });
    expect(third.res.statusCode).toBe(409);
  });
});

describe('L6: AES-GCM tag length is enforced', () => {
  it('rejects truncated tags for text and files', () => {
    const c = new Crypto(Buffer.alloc(32, 3), Buffer.from('h'.repeat(40)));
    const blob = c.encryptText('1234567890');
    const [v, iv, tag, ct] = blob.split('.');
    const short = Buffer.from(tag as string, 'base64url').subarray(0, 4).toString('base64url');
    expect(() => c.decryptText([v, iv, short, ct].join('.'))).toThrow();
    const f = c.encryptFile(Buffer.from('jpeg-bytes'));
    expect(() => c.decryptFile(f.subarray(0, 4 + 12 + 8))).toThrow();
  });
});

describe('L9: payment details and password resets are announced', () => {
  it('only a super admin may change bank/office details, and every shareholder gets a notice', async () => {
    const mgrRole = await customRole('স্টাফ ব্যবস্থাপক দুই', ['settings_staff']);
    const manager = await newStaff(env, mgrRole.key);
    const cur = (await env.app.inject({ method: 'GET', url: A('/admin/settings'), headers: bearer(admin.token) })).json();
    const changed = { ...cur, payment: { ...cur.payment, accountNumber: '9999-HACKER' }, security: { autoLogoutMinutes: cur.security.autoLogoutMinutes } };
    const denied = await env.app.inject({ method: 'PUT', url: A('/admin/settings'), headers: bearer(manager.token), payload: changed });
    expect(denied.statusCode).toBe(403);
    const toggles = await env.app.inject({
      method: 'PUT',
      url: A('/admin/settings'),
      headers: bearer(manager.token),
      payload: { ...cur, payment: { ...cur.payment, cashEnabled: true, bankEnabled: true }, security: { autoLogoutMinutes: 12 } },
    });
    expect(toggles.statusCode).toBe(200);

    const holder = await newUser(env.app);
    const ok = await env.app.inject({
      method: 'PUT',
      url: A('/admin/settings'),
      headers: bearer(admin.token),
      payload: { ...changed, payment: { ...changed.payment, accountNumber: '0123456789' } },
    });
    expect(ok.statusCode).toBe(200);
    const n = (await env.app.inject({ method: 'GET', url: A('/notices'), headers: bearer(holder.token) })).json();
    expect(n.items[0].title).toBe('পেমেন্টের তথ্য পরিবর্তিত হয়েছে');
    await env.db.query(`UPDATE settings SET value = jsonb_set(value, '{autoLogoutMinutes}', '10') WHERE key = 'security'`);
  });

  it('the shareholder is told when the office resets their password', async () => {
    const u = await newUser(env.app);
    const r = await env.app.inject({ method: 'POST', url: A(`/admin/shareholders/${u.id}/reset-password`), headers: bearer(admin.token) });
    const temp = r.json().tempPassword as string;
    const l = await userLogin(u.data.mobile, temp, '127.0.0.1');
    const tok = l.json().tokens.accessToken;
    await env.app.inject({ method: 'POST', url: A('/me/password'), headers: bearer(tok), payload: { currentPassword: temp, newPassword: 'After-reset-77' } });
    const n = (await env.app.inject({ method: 'GET', url: A('/notices'), headers: bearer(tok) })).json();
    expect(n.items.some((i: { title: string }) => i.title === 'আপনার পাসওয়ার্ড রিসেট করা হয়েছে')).toBe(true);
  });
});

describe('M3: least-privilege DB role and keyed audit chain', () => {
  it('the runtime role cannot tamper with the audit log, change schema or migrations', async () => {
    await expect(env.db.query('UPDATE audit_log SET tone = tone')).rejects.toThrow(/permission denied/);
    await expect(env.db.query('ALTER TABLE audit_log DISABLE TRIGGER audit_log_no_update')).rejects.toThrow(/must be owner|permission denied/);
    await expect(env.db.query('CREATE TABLE evil (id int)')).rejects.toThrow(/permission denied/);
    await expect(env.db.query(`INSERT INTO schema_migrations (version) VALUES ('999_evil.sql')`)).rejects.toThrow(/permission denied/);
    await expect(env.db.query('CREATE ROLE evil')).rejects.toThrow(/permission denied/);
    const r = await q1<{ rolsuper: boolean; rolcreaterole: boolean; rolcreatedb: boolean }>(
      env.db,
      'SELECT rolsuper, rolcreaterole, rolcreatedb FROM pg_roles WHERE rolname = current_user',
    );
    expect(r).toEqual({ rolsuper: false, rolcreaterole: false, rolcreatedb: false });
    await expect(assertRestrictedRole(env.db)).resolves.toBeUndefined();
    await expect(assertSchemaCurrent(env.db)).resolves.toBeUndefined();
    const owner = ownerPool();
    try {
      await expect(assertRestrictedRole(owner)).rejects.toThrow(/restricted runtime role/);
    } finally {
      await owner.end();
    }
  });

  it('chain links are HMACs under a key that is not in the DB, and the verify job reports tampering', async () => {
    const last = await q1<{ prev_hash: string; hash: string; at: Date; actor_type: string; actor_id: string | null; actor_name: string; category: string; action: string; message: string; tone: string; ip: string | null; meta: Record<string, unknown> }>(
      env.db,
      'SELECT * FROM audit_log ORDER BY id DESC LIMIT 1',
    );
    expect(last).toBeTruthy();
    const input = {
      actorType: last!.actor_type as 'staff',
      actorId: last!.actor_id,
      actorName: last!.actor_name,
      category: last!.category as 'login',
      action: last!.action,
      message: last!.message,
      tone: last!.tone as 'info',
      ip: last!.ip,
    };
    expect(auditHash(env.app.ctx, last!.prev_hash, last!.at.toISOString(), input, last!.meta)).toBe(last!.hash);
    const otherKey = { crypto: new Crypto(Buffer.alloc(32, 1), Buffer.from('another-key-another-key-another-key-xyz')) };
    expect(auditHash(otherKey, last!.prev_hash, last!.at.toISOString(), input, last!.meta)).not.toBe(last!.hash);
    expect(GENESIS_HASH).toHaveLength(64);

    // An owner-level attacker disables the trigger and rewrites a row with a plain SHA-256 "fix" — detected.
    const owner = ownerPool();
    const client = await owner.connect();
    const errors: unknown[] = [];
    const ctx = { ...env.app.ctx, db: env.db, log: { ...env.app.ctx.log, error: (o: unknown) => errors.push(o), info: () => {} } } as typeof env.app.ctx;
    try {
      await client.query('BEGIN');
      await client.query('ALTER TABLE audit_log DISABLE TRIGGER audit_log_no_update');
      const target = await q1<{ id: number }>(client, 'SELECT min(id) AS id FROM audit_log');
      await client.query(`UPDATE audit_log SET message = 'মুছে ফেলা হয়েছে', hash = $2 WHERE id = $1`, [target!.id, sha256Hex('forged')]);
      expect(await verifyAuditChain(env.app.ctx, client)).toBe(target!.id);
      expect(await runAuditVerifyJob(ctx, client)).toBe(target!.id);
      expect(errors).toHaveLength(1);
      expect(JSON.stringify(errors[0])).toContain('audit_chain_mismatch');
    } finally {
      await client.query('ROLLBACK');
      client.release();
      await owner.end();
    }
    expect(await verifyAuditChain(env.app.ctx, env.db)).toBeNull();
  });
});

describe('L10: Caddy container is hardened', () => {
  it('drops all capabilities except NET_BIND_SERVICE and runs read-only', () => {
    const compose = readFileSync(new URL('../docker-compose.yml', import.meta.url), 'utf8');
    const caddy = compose.slice(compose.indexOf('  caddy:'), compose.indexOf('\nnetworks:'));
    expect(caddy).toMatch(/read_only: true/);
    expect(caddy).toMatch(/cap_drop: \["ALL"\]/);
    expect(caddy).toMatch(/cap_add: \["NET_BIND_SERVICE"\]/);
    expect(caddy).toMatch(/tmpfs: \["\/tmp"\]/);
    // M3: the api service only ever sees the runtime role, never the owner password
    const api = compose.slice(compose.indexOf('  api:'), compose.indexOf('  caddy:'));
    expect(api).toContain('resoshare_app:${POSTGRES_APP_PASSWORD}');
    expect(api).not.toContain('POSTGRES_PASSWORD}');
    expect(api).not.toContain('env_file');
  });
});

