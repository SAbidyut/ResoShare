import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { q1 } from '../src/db.js';
import { bearer, err, loginUser, multipart, newUser, randomMobile, randomNid, registerUser, sampleJpeg, setupApp, teardownApp, type TestEnv } from './helpers.js';

let env: TestEnv;
beforeAll(async () => {
  env = await setupApp();
});
afterAll(async () => {
  await teardownApp();
});

describe('registration', () => {
  it('registers, logs in and returns a dashboard', async () => {
    const { res, data } = await registerUser(env.app);
    expect(res.statusCode).toBe(201);
    expect(res.json().shareholderNo).toMatch(/^RA-SH-\d{5}$/);

    const login = await loginUser(env.app, data.mobile);
    expect(login.statusCode).toBe(200);
    const body = login.json();
    expect(body.tokens.expiresIn).toBe(900);
    expect(body.me.kycStatus).toBe('pending');
    expect(body.me.nominees).toHaveLength(2);
    expect(body.me.nominees[0].idNumberMasked).toMatch(/^•+\d{3}$/);
    expect(JSON.stringify(body)).not.toContain(data.nidNumber);

    const dash = await env.app.inject({ method: 'GET', url: '/api/v1/me/dashboard', headers: bearer(body.tokens.accessToken) });
    expect(dash.statusCode).toBe(200);
    const d = dash.json();
    expect(d.today.ratePaisa).toBeGreaterThan(0);
    expect(d.holding).toEqual({ shares: 0, paidPaisa: 0, valuePaisa: 0, profitPaisa: 0, profitBp: 0 });
    expect(d.pool.totalShares).toBeGreaterThanOrEqual(10000);
    expect(d.pool.remainingShares).toBe(d.pool.totalShares - d.pool.soldShares);
    expect(Array.isArray(d.recentOrders)).toBe(true);
    expect(dash.headers['cache-control']).toBe('no-store');
  });

  it('accepts Bangla digits and +88 prefix for mobile, login by email works', async () => {
    const ascii = randomMobile();
    const bn = `+88${ascii.replace(/\d/g, (x) => '০১২৩৪৫৬৭৮৯'[Number(x)] as string)}`;
    const { res, data } = await registerUser(env.app, { mobile: bn });
    expect(res.statusCode).toBe(201);
    const l = await loginUser(env.app, ascii);
    expect(l.statusCode).toBe(200);
    expect(l.json().me.mobile).toBe(ascii);
    const byEmail = await loginUser(env.app, data.email.toUpperCase());
    expect(byEmail.statusCode).toBe(200);
  });

  it('rejects duplicate mobile and NID with one generic CONFLICT (no field named)', async () => {
    const nid = randomNid();
    const first = await registerUser(env.app, { nidNumber: nid });
    expect(first.res.statusCode).toBe(201);
    const dupMobile = await registerUser(env.app, { mobile: first.data.mobile });
    const dupNid = await registerUser(env.app, { nidNumber: nid });
    const dupEmail = await registerUser(env.app, { email: first.data.email });
    for (const r of [dupMobile.res, dupNid.res, dupEmail.res]) {
      expect(r.statusCode).toBe(409);
      expect(r.json()).toEqual({ error: { code: 'CONFLICT', message: 'এই মোবাইল, ইমেইল বা NID দিয়ে আগেই রেজিস্ট্রেশন হয়েছে' } });
    }
  });

  it('validates input: weak password, under-age, nominee % sum, missing rules acceptance', async () => {
    const weak = await registerUser(env.app, { password: 'password1' });
    expect(weak.res.statusCode).toBe(400);
    expect(err(weak.res).fields?.password).toBeTruthy();
    const young = await registerUser(env.app, { dob: '2015-01-01' });
    expect(err(young.res).fields?.dob).toBeTruthy();
    const pct = await registerUser(env.app, {
      nominees: [
        { name: 'ফাতেমা আক্তার', relation: 'স্ত্রী', idType: 'nid', idNumber: randomNid(), sharePercent: 50 },
        { name: 'আরিফ উদ্দিন', relation: 'পুত্র', idType: 'nid', idNumber: randomNid(), sharePercent: 40 },
      ],
    });
    expect(pct.res.statusCode).toBe(400);
    const birth = await registerUser(env.app, {
      nominees: [{ name: 'আরিফ উদ্দিন', relation: 'পুত্র', idType: 'birth_reg', idNumber: '12345', sharePercent: 100 }],
    });
    expect(err(birth.res).fields?.['nominees.0.idNumber']).toBeTruthy();
    const rules = await registerUser(env.app, { acceptRules: false });
    expect(err(rules.res).fields?.acceptRules).toBeTruthy();
  });

  it('rejects own NID as nominee, unknown keys, non-image uploads and missing files', async () => {
    const nid = randomNid();
    const own = await registerUser(env.app, {
      nidNumber: nid,
      nominees: [{ name: 'ফাতেমা আক্তার', relation: 'স্ত্রী', idType: 'nid', idNumber: nid, sharePercent: 100 }],
    });
    expect(own.res.statusCode).toBe(400);

    const jpg = await sampleJpeg();
    const base = {
      fullName: 'করিম উদ্দিন',
      mobile: randomMobile(),
      email: 'k@example.com',
      password: 'Strong-pass-42',
      nidNumber: randomNid(),
      dob: '1990-01-01',
      nominees: [{ name: 'ফাতেমা আক্তার', relation: 'স্ত্রী', idType: 'nid', idNumber: randomNid(), sharePercent: 100 }],
      acceptRules: true,
    };
    const extra = multipart({ data: JSON.stringify({ ...base, isAdmin: true }) }, { nidFront: { buf: jpg }, nidBack: { buf: jpg }, photo: { buf: jpg } });
    const r1 = await env.app.inject({ method: 'POST', url: '/api/v1/auth/register', ...extra });
    expect(r1.statusCode).toBe(400);

    const html = Buffer.from('<html><script>alert(1)</script></html>');
    const bad = multipart({ data: JSON.stringify(base) }, { nidFront: { buf: html, type: 'image/jpeg' }, nidBack: { buf: jpg }, photo: { buf: jpg } });
    const r2 = await env.app.inject({ method: 'POST', url: '/api/v1/auth/register', ...bad });
    expect(r2.statusCode).toBe(400);
    expect(err(r2).fields?.nidFront).toBeTruthy();

    const missing = multipart({ data: JSON.stringify(base) }, { nidFront: { buf: jpg } });
    const r3 = await env.app.inject({ method: 'POST', url: '/api/v1/auth/register', ...missing });
    expect(r3.statusCode).toBe(400);
    expect(err(r3).fields?.photo).toBeTruthy();

    const huge = multipart({ data: JSON.stringify(base) }, { nidFront: { buf: Buffer.concat([jpg, Buffer.alloc(6 * 1024 * 1024)]) }, nidBack: { buf: jpg }, photo: { buf: jpg } });
    const r4 = await env.app.inject({ method: 'POST', url: '/api/v1/auth/register', ...huge });
    expect(r4.statusCode).toBe(413);
    expect(err(r4).code).toBe('PAYLOAD_TOO_LARGE');
  });

  it('stores NID encrypted and KYC images encrypted without metadata', async () => {
    const nid = randomNid();
    const { res } = await registerUser(env.app, { nidNumber: nid });
    expect(res.statusCode).toBe(201);
    const row = await q1<{ nid_enc: string; id: string }>(env.db, 'SELECT id, nid_enc FROM shareholders WHERE nid_last3 = $1 ORDER BY created_at DESC LIMIT 1', [nid.slice(-3)]);
    expect(row?.nid_enc).toMatch(/^v1\./);
    expect(row?.nid_enc).not.toContain(nid);
    expect(env.app.ctx.crypto.decryptText(row?.nid_enc as string)).toBe(nid);
    const f = await q1<{ storage_name: string }>(env.db, 'SELECT storage_name FROM kyc_files WHERE shareholder_id = $1 LIMIT 1', [row?.id]);
    const { readFile } = await import('node:fs/promises');
    const blob = await readFile(`${process.env.UPLOAD_DIR}/${f?.storage_name}`);
    expect(blob.subarray(0, 4).toString()).toBe('RSF1');
    expect(blob.includes(Buffer.from('secret-gps-like-metadata'))).toBe(false);
    const plain = env.app.ctx.crypto.decryptFile(blob);
    expect(plain.subarray(0, 3)).toEqual(Buffer.from([0xff, 0xd8, 0xff]));
    expect(plain.includes(Buffer.from('secret-gps-like-metadata'))).toBe(false);
  });
});

describe('login security', () => {
  it('uses the same generic error for unknown user and wrong password', async () => {
    const u = await newUser(env.app);
    const wrong = await loginUser(env.app, u.data.mobile, 'Wrong-pass-1');
    const unknown = await loginUser(env.app, randomMobile(), 'Wrong-pass-1');
    expect(wrong.statusCode).toBe(401);
    expect(unknown.statusCode).toBe(401);
    expect(err(wrong)).toEqual(err(unknown));
  });

  it('throttles 5 failures per (account, IP): that IP gets the generic 401 even with the right password, other IPs still work', async () => {
    const u = await newUser(env.app);
    const ipA = '203.0.113.10';
    const login = (password: string, remoteAddress: string) =>
      env.app.inject({ method: 'POST', url: '/api/v1/auth/login', remoteAddress, payload: { identifier: u.data.mobile, password, deviceId: 'test-device-1' } });
    let wrong;
    for (let i = 0; i < 5; i++) wrong = await login('Wrong-pass-1', ipA);
    expect(wrong?.statusCode).toBe(401);
    const blocked = await login(u.data.password, ipA);
    expect(blocked.statusCode).toBe(401);
    expect(blocked.json()).toEqual(wrong?.json()); // indistinguishable from a wrong password
    const otherIp = await login(u.data.password, '198.51.100.7');
    expect(otherIp.statusCode).toBe(200);
  });

  it('rotates refresh tokens and revokes the family on reuse', async () => {
    const u = await newUser(env.app);
    const r1 = await env.app.inject({ method: 'POST', url: '/api/v1/auth/refresh', payload: { refreshToken: u.refresh } });
    expect(r1.statusCode).toBe(200);
    const t1 = r1.json();
    expect(t1.refreshToken).not.toBe(u.refresh);
    // replaying the old token = theft signal
    const reuse = await env.app.inject({ method: 'POST', url: '/api/v1/auth/refresh', payload: { refreshToken: u.refresh } });
    expect(reuse.statusCode).toBe(401);
    expect(err(reuse).code).toBe('SESSION_EXPIRED');
    // the new token of the same family is dead too
    const r2 = await env.app.inject({ method: 'POST', url: '/api/v1/auth/refresh', payload: { refreshToken: t1.refreshToken } });
    expect(err(r2).code).toBe('SESSION_EXPIRED');
    // and so is the access token bound to that session
    const me = await env.app.inject({ method: 'GET', url: '/api/v1/me', headers: bearer(t1.accessToken) });
    expect(me.statusCode).toBe(401);
    expect(err(me).code).toBe('SESSION_EXPIRED');
  });

  it('logout revokes the session', async () => {
    const u = await newUser(env.app);
    const out = await env.app.inject({ method: 'POST', url: '/api/v1/auth/logout', payload: { refreshToken: u.refresh } });
    expect(out.statusCode).toBe(204);
    const me = await env.app.inject({ method: 'GET', url: '/api/v1/me', headers: bearer(u.token) });
    expect(err(me).code).toBe('SESSION_EXPIRED');
  });

  it('rejects missing, forged and expired tokens with the right codes', async () => {
    const none = await env.app.inject({ method: 'GET', url: '/api/v1/me' });
    expect(none.statusCode).toBe(401);
    expect(err(none).code).toBe('UNAUTHORIZED');
    const forged = await env.app.inject({ method: 'GET', url: '/api/v1/me', headers: bearer('eyJhbGciOiJub25lIn0.eyJzdWIiOiJ4In0.abcdefghijklmnop') });
    expect(err(forged).code).toBe('UNAUTHORIZED');
    const { SignJWT } = await import('jose');
    const expired = await new SignJWT({ typ: 'user', sid: '00000000-0000-0000-0000-000000000000', ver: 0 })
      .setProtectedHeader({ alg: 'HS256' })
      .setSubject('00000000-0000-0000-0000-000000000000')
      .setIssuer('resoshare')
      .setAudience('resoshare-api')
      .setIssuedAt(Math.floor(Date.now() / 1000) - 3600)
      .setExpirationTime(Math.floor(Date.now() / 1000) - 60)
      .sign(env.app.ctx.config.jwtSecret);
    const exp = await env.app.inject({ method: 'GET', url: '/api/v1/me', headers: bearer(expired) });
    expect(err(exp).code).toBe('TOKEN_EXPIRED');
    const wrongKey = await new SignJWT({ typ: 'user', sid: 'x', ver: 0 })
      .setProtectedHeader({ alg: 'HS256' })
      .setSubject('x')
      .setIssuer('resoshare')
      .setAudience('resoshare-api')
      .setExpirationTime(Math.floor(Date.now() / 1000) - 60)
      .sign(new TextEncoder().encode('another-secret-another-secret-another-secret'));
    const wk = await env.app.inject({ method: 'GET', url: '/api/v1/me', headers: bearer(wrongKey) });
    expect(err(wk).code).toBe('UNAUTHORIZED');
  });

  it('a user token cannot call admin routes', async () => {
    const u = await newUser(env.app);
    const r = await env.app.inject({ method: 'GET', url: '/api/v1/admin/dashboard', headers: bearer(u.token) });
    expect(r.statusCode).toBe(401);
  });

  it('changing password needs the right current password (400, not 401) and revokes other sessions', async () => {
    const u = await newUser(env.app);
    const other = await loginUser(env.app, u.data.mobile);
    const otherToken = other.json().tokens.accessToken;
    const wrong = await env.app.inject({
      method: 'POST',
      url: '/api/v1/me/password',
      headers: bearer(u.token),
      payload: { currentPassword: 'nope-nope-1', newPassword: 'Another-pass-9' },
    });
    expect(wrong.statusCode).toBe(400);
    expect(err(wrong).fields?.currentPassword).toBe('বর্তমান পাসওয়ার্ড সঠিক নয়');
    const ok = await env.app.inject({
      method: 'POST',
      url: '/api/v1/me/password',
      headers: bearer(u.token),
      payload: { currentPassword: 'Strong-pass-42', newPassword: 'Another-pass-9' },
    });
    expect(ok.statusCode).toBe(204);
    const still = await env.app.inject({ method: 'GET', url: '/api/v1/me', headers: bearer(u.token) });
    expect(still.statusCode).toBe(200);
    const revoked = await env.app.inject({ method: 'GET', url: '/api/v1/me', headers: bearer(otherToken) });
    expect(err(revoked).code).toBe('SESSION_EXPIRED');
  });
});

describe('transport hardening', () => {
  it('returns the error envelope, security headers and rejects prototype pollution', async () => {
    const nf = await env.app.inject({ method: 'GET', url: '/api/v1/does-not-exist' });
    expect(nf.statusCode).toBe(404);
    expect(err(nf).code).toBe('NOT_FOUND');
    expect(nf.headers['x-content-type-options']).toBe('nosniff');
    expect(nf.headers['strict-transport-security']).toContain('max-age');
    expect(nf.headers['content-security-policy']).toContain("default-src 'none'");
    expect(nf.headers['x-powered-by']).toBeUndefined();
    expect(nf.headers['access-control-allow-origin']).toBeUndefined();

    const proto = await env.app.inject({
      method: 'POST',
      url: '/api/v1/auth/login',
      headers: { 'content-type': 'application/json' },
      payload: '{"identifier":"01711111111","password":"x","deviceId":"device-123","__proto__":{"admin":true}}',
    });
    expect(proto.statusCode).toBe(400);
    expect(err(proto).code).toBe('INVALID_INPUT');

    const badJson = await env.app.inject({ method: 'POST', url: '/api/v1/auth/login', headers: { 'content-type': 'application/json' }, payload: '{bad' });
    expect(badJson.statusCode).toBe(400);

    const strict = await env.app.inject({
      method: 'POST',
      url: '/api/v1/auth/login',
      payload: { identifier: '01711111111', password: 'x', deviceId: 'device-123', role: 'admin' },
    });
    expect(strict.statusCode).toBe(400);
  });

  it('public endpoints work without auth (info, rules, health)', async () => {
    const info = await env.app.inject({ method: 'GET', url: '/api/v1/public/info' });
    expect(info.statusCode).toBe(200);
    expect(info.json().hotline).toBe('01617-345534');
    const rules = await env.app.inject({ method: 'GET', url: '/api/v1/rules' });
    expect(rules.statusCode).toBe(200);
    expect(rules.json().version).toMatch(/^\d+(\.\d+)*$/);
    expect(rules.json().sections.length).toBeGreaterThan(0);
    const h = await env.app.inject({ method: 'GET', url: '/api/v1/health' });
    expect(h.json()).toEqual({ ok: true });
  });
});
