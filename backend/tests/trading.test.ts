import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { q1 } from '../src/db.js';
import { runRateNoticeJob } from '../src/jobs/scheduler.js';
import { addDays, dhakaDate } from '../src/util/time.js';
import { bearer, err, multipart, newStaff, newUser, randomNid, sampleJpeg, setupApp, teardownApp, todayRatePaisa, type TestEnv } from './helpers.js';

let env: TestEnv;
let admin: Awaited<ReturnType<typeof newStaff>>;

beforeAll(async () => {
  env = await setupApp();
  admin = await newStaff(env, 'super_admin');
});
afterAll(async () => {
  await teardownApp();
});

const A = (path: string) => `/api/v1${path}`;

async function verifiedUser() {
  const u = await newUser(env.app);
  const r = await env.app.inject({ method: 'POST', url: A(`/admin/kyc/${u.id}/approve`), headers: bearer(admin.token) });
  expect(r.statusCode).toBe(204);
  return u;
}

async function placeOrder(token: string, quantity: number, method: 'cash' | 'bank' = 'cash', reference: string | null = null) {
  const rate = await todayRatePaisa(env.db);
  return env.app.inject({
    method: 'POST',
    url: A('/me/orders'),
    headers: bearer(token),
    payload: { quantity, method, reference, expectedRatePaisa: rate, acceptRules: true },
  });
}

describe('KYC and buying', () => {
  it('blocks buying until KYC is verified, then sends a KYC notice', async () => {
    const u = await newUser(env.app);
    const blocked = await placeOrder(u.token, 5);
    expect(blocked.statusCode).toBe(403);
    expect(err(blocked).code).toBe('KYC_REQUIRED');

    const ap = await env.app.inject({ method: 'GET', url: A('/admin/approvals'), headers: bearer(admin.token) });
    const kyc = ap.json().kyc.find((k: { shareholderId: string }) => k.shareholderId === u.id);
    expect(kyc.files).toHaveLength(3);
    expect(kyc.nidMasked).toMatch(/^•+\d{3}$/);

    const img = await env.app.inject({ method: 'GET', url: A(`/admin/kyc-files/${kyc.files[0].id}`), headers: bearer(admin.token) });
    expect(img.statusCode).toBe(200);
    expect(img.headers['content-type']).toBe('image/jpeg');
    expect(img.headers['cache-control']).toBe('no-store');
    expect(img.rawPayload.subarray(0, 3)).toEqual(Buffer.from([0xff, 0xd8, 0xff]));

    const ok = await env.app.inject({ method: 'POST', url: A(`/admin/kyc/${u.id}/approve`), headers: bearer(admin.token) });
    expect(ok.statusCode).toBe(204);
    const again = await env.app.inject({ method: 'POST', url: A(`/admin/kyc/${u.id}/approve`), headers: bearer(admin.token) });
    expect(again.statusCode).toBe(409);

    const notices = await env.app.inject({ method: 'GET', url: A('/notices'), headers: bearer(u.token) });
    expect(notices.json().items.some((n: { category: string }) => n.category === 'kyc')).toBe(true);
    expect(notices.json().unread).toBeGreaterThan(0);
  });

  it('full purchase flow: RATE_CHANGED guard → approve → certificate → verify page valid, then invalid after another purchase', async () => {
    const u = await verifiedUser();
    const rate = await todayRatePaisa(env.db);

    const stale = await env.app.inject({
      method: 'POST',
      url: A('/me/orders'),
      headers: bearer(u.token),
      payload: { quantity: 10, method: 'cash', reference: null, expectedRatePaisa: rate + 100, acceptRules: true },
    });
    expect(stale.statusCode).toBe(409);
    expect(err(stale).code).toBe('RATE_CHANGED');
    expect(err(stale).fields?.currentRatePaisa).toBe(String(rate));

    const noCert = await env.app.inject({ method: 'GET', url: A('/me/certificate'), headers: bearer(u.token) });
    expect(noCert.statusCode).toBe(404);

    const o = await placeOrder(u.token, 10);
    expect(o.statusCode).toBe(201);
    const order = o.json();
    expect(order.status).toBe('pending');
    expect(order.reference).toMatch(/^CASH-\d{4}$/);
    expect(order.amountPaisa).toBe(rate * 10);
    expect(order.orderNo).toMatch(/^ORD-\d{6}$/);

    const appr = await env.app.inject({ method: 'POST', url: A(`/admin/orders/${order.id}/approve`), headers: bearer(admin.token) });
    expect(appr.statusCode).toBe(200);
    expect(appr.json().status).toBe('confirmed');
    expect(appr.json().shareholderNo).toBe(u.me.shareholderNo);
    const twice = await env.app.inject({ method: 'POST', url: A(`/admin/orders/${order.id}/approve`), headers: bearer(admin.token) });
    expect(twice.statusCode).toBe(409);

    const dash = (await env.app.inject({ method: 'GET', url: A('/me/dashboard'), headers: bearer(u.token) })).json();
    expect(dash.holding.shares).toBe(10);
    expect(dash.holding.paidPaisa).toBe(rate * 10);
    expect(dash.holding.valuePaisa).toBe(rate * 10);

    const cert = await env.app.inject({ method: 'GET', url: A('/me/certificate'), headers: bearer(u.token) });
    expect(cert.statusCode).toBe(200);
    const c = cert.json();
    expect(c.shares).toBe(10);
    expect(c.certificateNo).toBe(u.me.shareholderNo.replace('RA-SH-', 'RA-CERT-'));
    expect(c.issuedOn).toBe(dhakaDate());
    const path = new URL(c.verifyUrl).pathname + new URL(c.verifyUrl).search;
    const page = await env.app.inject({ method: 'GET', url: path });
    expect(page.statusCode).toBe(200);
    expect(page.headers['content-type']).toContain('text/html');
    expect(page.headers['content-security-policy']).toContain("default-src 'none'");
    expect(page.headers['content-security-policy']).not.toContain('unsafe-inline');
    expect(page.body).toContain('বৈধ শেয়ার সার্টিফিকেট');
    expect(page.body).toContain('১০টি');

    // tampered signature
    const forged = await env.app.inject({ method: 'GET', url: path.replace(/s=([a-f0-9])/, (_m, ch) => `s=${ch === 'a' ? 'b' : 'a'}`) });
    expect(forged.body).toContain('যাচাই করা যায়নি');

    const o2 = await placeOrder(u.token, 5, 'bank', `TRF-${Date.now()}`);
    expect(o2.statusCode).toBe(201);
    await env.app.inject({ method: 'POST', url: A(`/admin/orders/${o2.json().id}/approve`), headers: bearer(admin.token) });
    const old = await env.app.inject({ method: 'GET', url: path });
    expect(old.body).toContain('যাচাই করা যায়নি');
    const cert2 = (await env.app.inject({ method: 'GET', url: A('/me/certificate'), headers: bearer(u.token) })).json();
    expect(cert2.shares).toBe(15);
    const fresh = await env.app.inject({ method: 'GET', url: new URL(cert2.verifyUrl).pathname + new URL(cert2.verifyUrl).search });
    expect(fresh.body).toContain('১৫টি');
  });

  it('rejects markup in names and the verify page escapes stored values', async () => {
    const bad = await newUser(env.app, { fullName: 'Rahim <script>' }).catch((e: Error) => e);
    expect(bad).toBeInstanceOf(Error);

    const u = await verifiedUser();
    const o = (await placeOrder(u.token, 1)).json();
    await env.app.inject({ method: 'POST', url: A(`/admin/orders/${o.id}/approve`), headers: bearer(admin.token) });
    // even if a hostile value reached the DB some other way, the page must not render it as HTML
    await env.db.query('UPDATE shareholders SET full_name = $2 WHERE id = $1', [u.id, '<script>alert(1)</script>']);
    const cert = (await env.app.inject({ method: 'GET', url: A('/me/certificate'), headers: bearer(u.token) })).json();
    const page = await env.app.inject({ method: 'GET', url: new URL(cert.verifyUrl).pathname + new URL(cert.verifyUrl).search });
    expect(page.body).not.toContain('<script>alert(1)</script>');
    expect(page.body).toContain('&#60;script&#62;');
  });

  it('bank orders need a unique transfer reference', async () => {
    const u = await verifiedUser();
    const none = await placeOrder(u.token, 1, 'bank', null);
    expect(none.statusCode).toBe(400);
    expect(err(none).fields?.reference).toBeTruthy();
    const ref = `REF-${Date.now()}`;
    const first = await placeOrder(u.token, 1, 'bank', ref);
    expect(first.statusCode).toBe(201);
    expect(first.json().reference).toBe(ref);
    const dup = await placeOrder(u.token, 1, 'bank', ref.toLowerCase());
    expect(dup.statusCode).toBe(409);
  });

  it('limits pending orders to 3 and lets the owner cancel (IDOR-safe)', async () => {
    const a = await verifiedUser();
    const b = await verifiedUser();
    const ids: string[] = [];
    for (let i = 0; i < 3; i++) ids.push((await placeOrder(a.token, 1)).json().id);
    const fourth = await placeOrder(a.token, 1);
    expect(err(fourth).code).toBe('TOO_MANY_PENDING');

    const steal = await env.app.inject({ method: 'POST', url: A(`/me/orders/${ids[0]}/cancel`), headers: bearer(b.token) });
    expect(steal.statusCode).toBe(404);
    const own = await env.app.inject({ method: 'POST', url: A(`/me/orders/${ids[0]}/cancel`), headers: bearer(a.token) });
    expect(own.statusCode).toBe(200);
    expect(own.json().status).toBe('cancelled');
    const bad = await env.app.inject({ method: 'POST', url: A('/me/orders/not-a-uuid/cancel'), headers: bearer(a.token) });
    expect(bad.statusCode).toBe(404);

    const listB = (await env.app.inject({ method: 'GET', url: A('/me/orders'), headers: bearer(b.token) })).json();
    expect(listB.items).toHaveLength(0);
    const listA = (await env.app.inject({ method: 'GET', url: A('/me/orders?limit=2'), headers: bearer(a.token) })).json();
    expect(listA.items).toHaveLength(2);
    expect(listA.nextCursor).toBeTruthy();
    const page2 = (await env.app.inject({ method: 'GET', url: A(`/me/orders?limit=2&cursor=${listA.nextCursor}`), headers: bearer(a.token) })).json();
    expect(page2.items).toHaveLength(1);
    const badCursor = await env.app.inject({ method: 'GET', url: A('/me/orders?cursor=%27%3BDROP'), headers: bearer(a.token) });
    expect(badCursor.statusCode).toBe(400);
  });

  it('INSUFFICIENT_SHARES at order time and at approval time; SALE_CLOSED when sale is off', async () => {
    const u = await verifiedUser();
    const pool = (await env.app.inject({ method: 'GET', url: A('/admin/pool'), headers: bearer(admin.token) })).json();
    const tooMany = await placeOrder(u.token, Math.min(10000, pool.remainingShares + 1));
    if (pool.remainingShares < 10000) expect(err(tooMany).code).toBe('INSUFFICIENT_SHARES');

    const o = await placeOrder(u.token, 3);
    expect(o.statusCode).toBe(201);
    const original = await q1<{ total_shares: number }>(env.db, 'SELECT total_shares FROM share_pool WHERE id = 1');
    await env.db.query('UPDATE share_pool SET total_shares = $1 WHERE id = 1', [pool.soldShares + 1]);
    try {
      const appr = await env.app.inject({ method: 'POST', url: A(`/admin/orders/${o.json().id}/approve`), headers: bearer(admin.token) });
      expect(appr.statusCode).toBe(409);
      expect(err(appr).code).toBe('INSUFFICIENT_SHARES');
    } finally {
      await env.db.query('UPDATE share_pool SET total_shares = $1 WHERE id = 1', [original?.total_shares]);
    }

    const off = await env.app.inject({ method: 'PUT', url: A('/admin/pool'), headers: bearer(admin.token), payload: { saleEnabled: false } });
    expect(off.json().saleEnabled).toBe(false);
    const closed = await placeOrder(u.token, 1);
    expect(err(closed).code).toBe('SALE_CLOSED');
    await env.app.inject({ method: 'PUT', url: A('/admin/pool'), headers: bearer(admin.token), payload: { saleEnabled: true } });
  });

  it('reject sends the shareholder a payment notice with the reason', async () => {
    const u = await verifiedUser();
    const o = (await placeOrder(u.token, 2)).json();
    const noReason = await env.app.inject({ method: 'POST', url: A(`/admin/orders/${o.id}/reject`), headers: bearer(admin.token), payload: {} });
    expect(noReason.statusCode).toBe(400);
    const r = await env.app.inject({
      method: 'POST',
      url: A(`/admin/orders/${o.id}/reject`),
      headers: bearer(admin.token),
      payload: { reason: 'টাকা জমা পাওয়া যায়নি' },
    });
    expect(r.json().status).toBe('cancelled');
    expect(r.json().note).toBe('টাকা জমা পাওয়া যায়নি');
    const n = (await env.app.inject({ method: 'GET', url: A('/notices'), headers: bearer(u.token) })).json();
    const pay = n.items.find((x: { category: string }) => x.category === 'payment');
    expect(pay.body).toContain('টাকা জমা পাওয়া যায়নি');
    const read = await env.app.inject({ method: 'POST', url: A(`/notices/${pay.id}/read`), headers: bearer(u.token) });
    expect(read.statusCode).toBe(204);
    const other = await newUser(env.app);
    const foreign = await env.app.inject({ method: 'POST', url: A(`/notices/${pay.id}/read`), headers: bearer(other.token) });
    expect(foreign.statusCode).toBe(404);
    const all = await env.app.inject({ method: 'POST', url: A('/notices/read-all'), headers: bearer(u.token) });
    expect(all.statusCode).toBe(204);
    const after = (await env.app.inject({ method: 'GET', url: A('/notices'), headers: bearer(u.token) })).json();
    expect(after.unread).toBe(0);
  });

  it('suspension blocks the user immediately', async () => {
    const u = await verifiedUser();
    const s = await env.app.inject({ method: 'POST', url: A(`/admin/shareholders/${u.id}/suspend`), headers: bearer(admin.token), payload: { reason: 'জাল নথি' } });
    expect(s.statusCode).toBe(204);
    const me = await env.app.inject({ method: 'GET', url: A('/me'), headers: bearer(u.token) });
    expect(me.statusCode).toBe(401); // sessions were revoked
    const login = await env.app.inject({ method: 'POST', url: A('/auth/login'), payload: { identifier: u.data.mobile, password: u.data.password, deviceId: 'dev-12345' } });
    expect(err(login).code).toBe('ACCOUNT_SUSPENDED');
    await env.app.inject({ method: 'POST', url: A(`/admin/shareholders/${u.id}/activate`), headers: bearer(admin.token) });
    const back = await env.app.inject({ method: 'POST', url: A('/auth/login'), payload: { identifier: u.data.mobile, password: u.data.password, deviceId: 'dev-12345' } });
    expect(back.statusCode).toBe(200);
  });

  it('admin password reset forces a password change before anything else', async () => {
    const u = await verifiedUser();
    const r = await env.app.inject({ method: 'POST', url: A(`/admin/shareholders/${u.id}/reset-password`), headers: bearer(admin.token) });
    const temp = r.json().tempPassword as string;
    expect(temp).toHaveLength(12);
    const login = await env.app.inject({ method: 'POST', url: A('/auth/login'), payload: { identifier: u.data.mobile, password: temp, deviceId: 'dev-12345' } });
    expect(login.json().me.mustChangePassword).toBe(true);
    const tok = login.json().tokens.accessToken;
    const dash = await env.app.inject({ method: 'GET', url: A('/me/dashboard'), headers: bearer(tok) });
    expect(err(dash).code).toBe('PASSWORD_CHANGE_REQUIRED');
    const me = await env.app.inject({ method: 'GET', url: A('/me'), headers: bearer(tok) });
    expect(me.statusCode).toBe(200);
    const ch = await env.app.inject({ method: 'POST', url: A('/me/password'), headers: bearer(tok), payload: { currentPassword: temp, newPassword: 'Fresh-pass-55' } });
    expect(ch.statusCode).toBe(204);
    const dash2 = await env.app.inject({ method: 'GET', url: A('/me/dashboard'), headers: bearer(tok) });
    expect(dash2.statusCode).toBe(200);
  });

  it('nominees can be edited and KYC can be resubmitted only after rejection', async () => {
    const u = await newUser(env.app);
    const put = await env.app.inject({
      method: 'PUT',
      url: A('/me/nominees'),
      headers: bearer(u.token),
      payload: { nominees: [{ name: 'সুমাইয়া ইসলাম', relation: 'কন্যা', idType: 'birth_reg', idNumber: '20151234567890123', sharePercent: 100 }] },
    });
    expect(put.statusCode).toBe(200);
    expect(put.json()).toHaveLength(1);
    expect(put.json()[0].idNumberMasked).toBe('••••••••••••••123');

    const jpg = await sampleJpeg();
    const mp = () => {
      const m = multipart({ data: JSON.stringify({ nidNumber: randomNid(), dob: '1991-02-03' }) }, { nidFront: { buf: jpg }, nidBack: { buf: jpg }, photo: { buf: jpg } });
      return { payload: m.payload, headers: { ...m.headers, ...bearer(u.token) } };
    };
    const early = await env.app.inject({ method: 'POST', url: A('/me/kyc'), ...mp() });
    expect(early.statusCode).toBe(409);
    await env.app.inject({ method: 'POST', url: A(`/admin/kyc/${u.id}/reject`), headers: bearer(admin.token), payload: { reason: 'ছবি অস্পষ্ট' } });
    const me = (await env.app.inject({ method: 'GET', url: A('/me'), headers: bearer(u.token) })).json();
    expect(me.kycStatus).toBe('rejected');
    expect(me.kycNote).toBe('ছবি অস্পষ্ট');
    const re = await env.app.inject({ method: 'POST', url: A('/me/kyc'), ...mp() });
    expect(re.statusCode).toBe(204);
    const me2 = (await env.app.inject({ method: 'GET', url: A('/me'), headers: bearer(u.token) })).json();
    expect(me2.kycStatus).toBe('pending');
  });

  it('chart endpoint returns ascending points and stats', async () => {
    const u = await newUser(env.app);
    for (const period of ['daily', 'weekly', 'monthly', 'yearly']) {
      const r = await env.app.inject({ method: 'GET', url: A(`/market/chart?period=${period}`), headers: bearer(u.token) });
      expect(r.statusCode).toBe(200);
      const c = r.json();
      expect(c.period).toBe(period);
      expect(c.points.length).toBeGreaterThanOrEqual(1);
      const dates = c.points.map((p: { date: string }) => p.date);
      expect([...dates].sort()).toEqual(dates);
      expect(c.todayRatePaisa).toBeGreaterThan(0);
    }
    const bad = await env.app.inject({ method: 'GET', url: A('/market/chart?period=hourly'), headers: bearer(u.token) });
    expect(bad.statusCode).toBe(400);
  });

  it('rate notice job posts once at midnight for a changed scheduled rate', async () => {
    const today = dhakaDate();
    const before = await todayRatePaisa(env.db);
    // simulate: yesterday had a different rate, and today's row was scheduled with notify
    await env.db.query(
      `INSERT INTO rates (rate_date, rate_paisa, notify, notice_posted) VALUES ($1, $2, false, true) ON CONFLICT (rate_date) DO NOTHING`,
      [addDays(today, -1), before - 500],
    );
    await env.db.query('UPDATE rates SET notify = true, notice_posted = false WHERE rate_date = $1', [today]);
    const posted = await runRateNoticeJob(env.app.ctx, today);
    expect(posted).toBe(1);
    const again = await runRateNoticeJob(env.app.ctx, today);
    expect(again).toBe(0);
    const n = await q1<{ title: string; body: string }>(env.db, `SELECT title, body FROM notices WHERE category = 'rate' ORDER BY created_at DESC LIMIT 1`);
    expect(n?.title).toBe('শেয়ারের রেট পরিবর্তন');
    expect(n?.body).toContain('▲');
  });
});
