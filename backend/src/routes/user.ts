import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import type { Ctx } from '../context.js';
import { q, q1, tx } from '../db.js';
import { ApiError } from '../errors.js';
import { audit } from '../services/audit.js';
import {
  logoutByRefresh,
  mustUser,
  revokeAllSessions,
  rotateRefresh,
  startSession,
  userGuard,
} from '../services/auth.js';
import { buildChart, type Period } from '../services/chart.js';
import { KYC_KINDS, removeStored, sanitizeImage, storeEncrypted, type KycKind } from '../services/files.js';
import {
  cashRef,
  certificateNo,
  ME_COLS,
  meJson,
  NOMINEE_COLS,
  nomineeJson,
  ORDER_COLS,
  orderJson,
  shareholderNo,
  type NomineeRow,
  type OrderRow,
  type ShareholderRow,
} from '../services/format.js';
import { holdingOf, poolState, rateOn } from '../services/holdings.js';
import { visibleToSql } from '../services/notices.js';
import { getSettings } from '../services/settings.js';
import { clearOnSuccess, recordFailure, reserveAttempt } from '../services/throttle.js';
import { bnCount, bnMoney, methodName, possessive } from '../util/bn.js';
import { fakeVerify, hashPassword, verifyPassword } from '../util/passwords.js';
import { maskEmail, maskMobile } from '../util/mask.js';
import { addDays, dhakaDate } from '../util/time.js';
import {
  decodeCursor,
  encodeCursor,
  idParam,
  normalizeMobile,
  parse,
  zDeviceId,
  zDob,
  zEmail,
  zLoginPassword,
  zMobile,
  zName,
  zNid,
  zNominees,
  zPageQuery,
  zPassword,
} from '../util/validate.js';
import { readMultipart } from './multipart.js';

const RegisterSchema = z
  .object({
    fullName: zName,
    mobile: zMobile,
    email: zEmail,
    password: zPassword,
    nidNumber: zNid,
    dob: zDob,
    nominees: zNominees,
    acceptRules: z.literal(true, { error: 'নিয়ম ও রেগুলেশন মেনে নিতে হবে' }),
  })
  .strict();

const LoginSchema = z
  .object({
    identifier: z.string().min(3).max(254),
    password: zLoginPassword,
    deviceId: zDeviceId,
  })
  .strict();

const RefreshSchema = z.object({ refreshToken: z.string().min(1).max(200) }).strict();

const PasswordSchema = z.object({ currentPassword: zLoginPassword, newPassword: zPassword }).strict();

const OrderSchema = z
  .object({
    quantity: z.number().int('পূর্ণ সংখ্যা দিন').min(1, 'কমপক্ষে ১টি শেয়ার').max(10000, 'একবারে সর্বোচ্চ ১০,০০০টি শেয়ার'),
    method: z.enum(['cash', 'bank'], { error: 'পেমেন্ট মাধ্যম বাছাই করুন' }),
    reference: z.string().max(60).nullable().optional(),
    expectedRatePaisa: z.number().int().min(1).max(10_000_000_000),
    acceptRules: z.literal(true, { error: 'নিয়ম ও রেগুলেশন মেনে নিতে হবে' }),
  })
  .strict();

const KycSchema = z.object({ nidNumber: zNid, dob: zDob }).strict();
const NomineesSchema = z.object({ nominees: zNominees }).strict();
const ChartQuery = z.object({ period: z.enum(['daily', 'weekly', 'monthly', 'yearly']).default('daily') }).loose();

const BANK_REF_RE = /^[A-Za-z0-9\-/]{3,40}$/;
const DUPLICATE_MSG = 'এই মোবাইল, ইমেইল বা NID দিয়ে আগেই রেজিস্ট্রেশন হয়েছে';
const BANK_REF_USED = 'এই রেফারেন্স নম্বর আগে ব্যবহার করা হয়েছে';

function isUniqueViolation(err: unknown): err is { code: string; constraint?: string } {
  return typeof err === 'object' && err !== null && (err as { code?: string }).code === '23505';
}

async function loadMe(ctx: Ctx, id: string) {
  const row = await q1<ShareholderRow>(ctx.db, `SELECT ${ME_COLS} FROM shareholders WHERE id = $1`, [id]);
  if (!row) throw new ApiError('UNAUTHORIZED');
  const noms = await q<NomineeRow>(ctx.db, `SELECT ${NOMINEE_COLS} FROM nominees WHERE shareholder_id = $1 ORDER BY slot`, [id]);
  return meJson(row, noms);
}

async function insertNominees(
  ctx: Ctx,
  client: import('pg').PoolClient,
  shareholderId: string,
  nominees: z.output<typeof zNominees>,
) {
  let slot = 1;
  for (const n of nominees) {
    await client.query(
      `INSERT INTO nominees (shareholder_id, slot, name, relation, id_type, id_number_enc, id_last3, id_len, share_percent)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)`,
      [shareholderId, slot, n.name, n.relation, n.idType, ctx.crypto.encryptText(n.idNumber), n.idNumber.slice(-3), n.idNumber.length, n.sharePercent],
    );
    slot += 1;
  }
}

function nomineeNidClash(ctx: Ctx, holderNidHash: string, nominees: z.output<typeof zNominees>): Record<string, string> | null {
  const fields: Record<string, string> = {};
  nominees.forEach((n, i) => {
    if (n.idType === 'nid' && ctx.crypto.nidIndex(n.idNumber) === holderNidHash) {
      fields[`nominees.${i}.idNumber`] = 'নিজের NID নমিনি হিসেবে দেওয়া যাবে না';
    }
  });
  return Object.keys(fields).length ? fields : null;
}

async function readKycImages(files: Record<string, Buffer>): Promise<Record<KycKind, Buffer>> {
  const labels: Record<string, string> = { nidFront: 'NID এর সামনের ছবি দিন', nidBack: 'NID এর পেছনের ছবি দিন', photo: 'আপনার সাম্প্রতিক ছবি দিন' };
  const missing: Record<string, string> = {};
  for (const k of Object.keys(KYC_KINDS)) if (!files[k]) missing[k] = labels[k] ?? 'ছবি দিন';
  if (Object.keys(missing).length) throw new ApiError('INVALID_INPUT', 'সব নথির ছবি দিন।', missing);
  const out = {} as Record<KycKind, Buffer>;
  for (const [field, kind] of Object.entries(KYC_KINDS)) {
    try {
      out[kind] = await sanitizeImage(files[field] as Buffer);
    } catch (err) {
      if (err instanceof ApiError && err.code === 'INVALID_INPUT') throw new ApiError('INVALID_INPUT', err.message, { [field]: err.message });
      throw err;
    }
  }
  return out;
}

export async function userRoutes(app: FastifyInstance, ctx: Ctx) {
  const auth = userGuard(ctx);
  const authPw = userGuard(ctx, { allowPasswordChange: true });

  // ---------------- registration & auth ----------------

  app.post('/auth/register', { config: { rateLimit: { max: 5, timeWindow: '1 hour' } } }, async (req, reply) => {
    const { data, files } = await readMultipart(req, Object.keys(KYC_KINDS));
    const body = parse(RegisterSchema, data);
    const images = await readKycImages(files);
    const nidHash = ctx.crypto.nidIndex(body.nidNumber);
    const clash = nomineeNidClash(ctx, nidHash, body.nominees);
    if (clash) throw new ApiError('INVALID_INPUT', undefined, clash);

    // L2: one generic answer, never which field matched (no account enumeration).
    // L5: an NID only blocks while its KYC is pending/verified.
    const dup = await q1(
      ctx.db,
      `SELECT 1 FROM shareholders WHERE mobile = $1 OR email = $2 OR (nid_hash = $3 AND kyc_status <> 'rejected') LIMIT 1`,
      [body.mobile, body.email, nidHash],
    );
    if (dup) throw new ApiError('CONFLICT', DUPLICATE_MSG);

    const passwordHash = await hashPassword(body.password);
    const stored: { kind: KycKind; storageName: string; size: number }[] = [];
    try {
      for (const [kind, buf] of Object.entries(images) as [KycKind, Buffer][]) {
        stored.push({ kind, ...(await storeEncrypted(ctx, buf)) });
      }
      const seq = await tx(ctx.db, async (c) => {
        const row = await q1<{ id: string; shareholder_seq: number }>(
          c,
          `INSERT INTO shareholders (full_name, mobile, email, password_hash, nid_enc, nid_hash, nid_last3, nid_len, dob)
           VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9) RETURNING id, shareholder_seq`,
          [
            body.fullName,
            body.mobile,
            body.email,
            passwordHash,
            ctx.crypto.encryptText(body.nidNumber),
            nidHash,
            body.nidNumber.slice(-3),
            body.nidNumber.length,
            body.dob,
          ],
        );
        if (!row) throw new Error('insert failed');
        await insertNominees(ctx, c, row.id, body.nominees);
        for (const f of stored) {
          await c.query('INSERT INTO kyc_files (shareholder_id, kind, storage_name, size_bytes) VALUES ($1,$2,$3,$4)', [
            row.id,
            f.kind,
            f.storageName,
            f.size,
          ]);
        }
        await audit(ctx, c, {
          actorType: 'user',
          actorId: row.id,
          actorName: body.fullName,
          category: 'shareholder',
          action: 'shareholder.register',
          message: `নতুন রেজিস্ট্রেশন: ${body.fullName} (${shareholderNo(row.shareholder_seq)})`,
          tone: 'info',
          ip: req.ip,
        });
        return row.shareholder_seq;
      });
      return reply.status(201).send({ shareholderNo: shareholderNo(seq) });
    } catch (err) {
      await removeStored(
        ctx,
        stored.map((s) => s.storageName),
      );
      if (isUniqueViolation(err)) throw new ApiError('CONFLICT', DUPLICATE_MSG);
      throw err;
    }
  });

  app.post('/auth/login', { config: { rateLimit: { max: 10, timeWindow: '1 minute' } } }, async (req) => {
    const body = parse(LoginSchema, req.body);
    const generic = new ApiError('UNAUTHORIZED', 'মোবাইল/ইমেইল বা পাসওয়ার্ড সঠিক নয়।');
    const id = body.identifier.trim();
    const isEmail = id.includes('@');
    const key = isEmail ? id.toLowerCase() : normalizeMobile(id);
    const user = key
      ? await q1<{
          id: string;
          full_name: string;
          password_hash: string;
          token_version: number;
          account_status: string;
        }>(
          ctx.db,
          `SELECT id, full_name, password_hash, token_version, account_status FROM shareholders WHERE ${isEmail ? 'email' : 'mobile'} = $1`,
          [key],
        )
      : undefined;
    const masked = key ? (isEmail ? maskEmail(key) : maskMobile(key)) : 'অজানা';

    if (!user) {
      await fakeVerify(body.password);
      await audit(ctx, ctx.db, {
        actorType: 'anonymous',
        actorId: null,
        actorName: masked,
        category: 'login',
        action: 'user.login.fail',
        message: `শেয়ারহোল্ডার লগইন ব্যর্থ (${masked})`,
        tone: 'bad',
        ip: req.ip,
      });
      throw generic;
    }
    // L1: reserve the attempt atomically before checking the password (bursts cannot exceed the limit).
    // L2: a throttled attempt looks exactly like a wrong password (same body, same hashing time).
    const allowed = await reserveAttempt(ctx.db, 'user', user.id, req.ip);
    if (!allowed) {
      await fakeVerify(body.password);
      await audit(ctx, ctx.db, {
        actorType: 'user',
        actorId: user.id,
        actorName: user.full_name,
        category: 'login',
        action: 'user.login.throttled',
        message: `${user.full_name}: লগইন চেষ্টা আটকানো হয়েছে (বারবার ভুল পাসওয়ার্ড)`,
        tone: 'bad',
        ip: req.ip,
      });
      throw generic;
    }

    const ok = await verifyPassword(user.password_hash, body.password);
    if (!ok) {
      await recordFailure(ctx.db, 'user', user.id, req.ip);
      await audit(ctx, ctx.db, {
        actorType: 'user',
        actorId: user.id,
        actorName: user.full_name,
        category: 'login',
        action: 'user.login.fail',
        message: `${user.full_name}: লগইন ব্যর্থ (ভুল পাসওয়ার্ড)`,
        tone: 'bad',
        ip: req.ip,
      });
      throw generic;
    }
    await clearOnSuccess(ctx.db, 'user', user.id, req.ip);
    if (user.account_status !== 'active') throw new ApiError('ACCOUNT_SUSPENDED');

    const tokens = await tx(ctx.db, async (c) => {
      const t = await startSession(ctx, c, req, 'user', user.id, user.token_version, body.deviceId);
      await audit(ctx, c, {
        actorType: 'user',
        actorId: user.id,
        actorName: user.full_name,
        category: 'login',
        action: 'user.login',
        message: `${user.full_name}: লগইন সফল`,
        tone: 'info',
        ip: req.ip,
      });
      return t;
    });
    const me = await loadMe(ctx, user.id);
    return { tokens: { accessToken: tokens.accessToken, refreshToken: tokens.refreshToken, expiresIn: tokens.expiresIn }, me };
  });

  app.post('/auth/refresh', { config: { rateLimit: { max: 30, timeWindow: '1 minute' } } }, async (req) => {
    const body = parse(RefreshSchema, req.body);
    return rotateRefresh(ctx, body.refreshToken, 'user');
  });

  app.post('/auth/logout', { config: { rateLimit: { max: 30, timeWindow: '1 minute' } } }, async (req, reply) => {
    const body = parse(RefreshSchema, req.body);
    await logoutByRefresh(ctx, body.refreshToken, 'user');
    return reply.status(204).send();
  });

  // ---------------- profile ----------------

  app.get('/me', { preHandler: authPw }, async (req) => loadMe(ctx, mustUser(req).id));

  app.post('/me/password', { preHandler: authPw, config: { rateLimit: { max: 10, timeWindow: '15 minutes' } } }, async (req, reply) => {
    const u = mustUser(req);
    const body = parse(PasswordSchema, req.body);
    const row = await q1<{ password_hash: string }>(ctx.db, 'SELECT password_hash FROM shareholders WHERE id = $1', [u.id]);
    if (!row || !(await verifyPassword(row.password_hash, body.currentPassword))) {
      throw new ApiError('INVALID_INPUT', 'বর্তমান পাসওয়ার্ড সঠিক নয়।', { currentPassword: 'বর্তমান পাসওয়ার্ড সঠিক নয়' });
    }
    if (body.currentPassword === body.newPassword) {
      throw new ApiError('INVALID_INPUT', undefined, { newPassword: 'নতুন পাসওয়ার্ড আগেরটার থেকে আলাদা হতে হবে' });
    }
    const hash = await hashPassword(body.newPassword);
    await tx(ctx.db, async (c) => {
      await c.query('UPDATE shareholders SET password_hash = $2, must_change_password = false, updated_at = now() WHERE id = $1', [u.id, hash]);
      await revokeAllSessions(c, 'user', u.id, 'password_change', u.sid);
      await audit(ctx, c, {
        actorType: 'user',
        actorId: u.id,
        actorName: u.name,
        category: 'login',
        action: 'user.password.change',
        message: `${u.name}: পাসওয়ার্ড পরিবর্তন`,
        tone: 'info',
        ip: req.ip,
      });
    });
    return reply.status(204).send();
  });

  app.get('/me/dashboard', { preHandler: auth }, async (req) => {
    const u = mustUser(req);
    const me = await loadMe(ctx, u.id);
    const today = dhakaDate();
    const rate = (await rateOn(ctx.db, today)) ?? 0;
    const prev = await rateOn(ctx.db, addDays(today, -1));
    const h = await holdingOf(ctx.db, u.id);
    const value = h.shares * rate;
    const profit = value - h.paidPaisa;
    const pool = await poolState(ctx.db);
    const unread = await q1<{ n: number }>(
      ctx.db,
      `SELECT count(*)::int AS n FROM notices n
        WHERE ${visibleToSql('$1')}
          AND NOT EXISTS (SELECT 1 FROM notice_reads nr WHERE nr.notice_id = n.id AND nr.shareholder_id = $1)`,
      [u.id],
    );
    const recent = await q<OrderRow>(
      ctx.db,
      `SELECT ${ORDER_COLS} FROM orders o WHERE o.shareholder_id = $1 ORDER BY o.created_at DESC, o.id DESC LIMIT 3`,
      [u.id],
    );
    return {
      me,
      today: { date: today, ratePaisa: rate, prevRatePaisa: prev },
      holding: {
        shares: h.shares,
        paidPaisa: h.paidPaisa,
        valuePaisa: value,
        profitPaisa: profit,
        profitBp: h.paidPaisa > 0 ? Math.round((profit * 10_000) / h.paidPaisa) : 0,
      },
      pool,
      unreadNotices: unread?.n ?? 0,
      recentOrders: recent.map(orderJson),
    };
  });

  // ---------------- orders ----------------

  app.get('/me/orders', { preHandler: auth }, async (req) => {
    const u = mustUser(req);
    const page = parse(zPageQuery, req.query);
    const cur = decodeCursor(page.cursor);
    const rows = await q<OrderRow>(
      ctx.db,
      `SELECT ${ORDER_COLS} FROM orders o
        WHERE o.shareholder_id = $1 AND ($2::timestamptz IS NULL OR (o.created_at, o.id) < ($2::timestamptz, $3::uuid))
        ORDER BY o.created_at DESC, o.id DESC LIMIT $4`,
      [u.id, cur?.t ?? null, cur?.id ?? '00000000-0000-0000-0000-000000000000', page.limit + 1],
    );
    const more = rows.length > page.limit;
    const items = rows.slice(0, page.limit);
    const last = items[items.length - 1];
    return { items: items.map(orderJson), nextCursor: more && last ? encodeCursor(last.created_at, last.id) : null };
  });

  app.post('/me/orders', { preHandler: auth, config: { rateLimit: { max: 20, timeWindow: '1 minute' } } }, async (req, reply) => {
    const u = mustUser(req);
    const body = parse(OrderSchema, req.body);
    const settings = await getSettings(ctx.db);
    if ((body.method === 'cash' && !settings.payment.cashEnabled) || (body.method === 'bank' && !settings.payment.bankEnabled)) {
      throw new ApiError('INVALID_INPUT', 'এই পেমেন্ট মাধ্যম এখন বন্ধ আছে।', { method: 'এই পেমেন্ট মাধ্যম এখন বন্ধ আছে' });
    }
    let bankRef: string | null = null;
    if (body.method === 'bank') {
      const ref = (body.reference ?? '').trim();
      if (!BANK_REF_RE.test(ref)) {
        throw new ApiError('INVALID_INPUT', undefined, { reference: 'সঠিক ট্রান্সফার রেফারেন্স নম্বর দিন (৩–৪০ অক্ষর)' });
      }
      bankRef = ref;
    }
    const row = await tx(ctx.db, async (c) => {
      const holder = await q1<{ kyc_status: string; account_status: string; full_name: string }>(
        c,
        'SELECT kyc_status, account_status, full_name FROM shareholders WHERE id = $1 FOR UPDATE',
        [u.id],
      );
      if (!holder) throw new ApiError('UNAUTHORIZED');
      if (holder.account_status !== 'active') throw new ApiError('ACCOUNT_SUSPENDED');
      if (holder.kyc_status !== 'verified') throw new ApiError('KYC_REQUIRED');
      const pending = await q1<{ n: number }>(c, `SELECT count(*)::int AS n FROM orders WHERE shareholder_id = $1 AND status = 'pending'`, [u.id]);
      if ((pending?.n ?? 0) >= 3) throw new ApiError('TOO_MANY_PENDING');
      const pool = await poolState(c);
      if (!pool.saleEnabled) throw new ApiError('SALE_CLOSED');
      if (body.quantity > pool.remainingShares) {
        throw new ApiError('INSUFFICIENT_SHARES', `এখন সর্বোচ্চ ${bnCount(pool.remainingShares)}টি শেয়ার কেনা যাবে।`);
      }
      const rate = await rateOn(c, dhakaDate());
      if (!rate) throw new ApiError('SALE_CLOSED');
      if (rate !== body.expectedRatePaisa) {
        throw new ApiError('RATE_CHANGED', undefined, { currentRatePaisa: String(rate) });
      }
      if (bankRef) {
        const used = await q1(c, `SELECT 1 FROM orders WHERE method = 'bank' AND lower(reference) = lower($1) AND status <> 'cancelled'`, [bankRef]);
        if (used) throw new ApiError('CONFLICT', `${BANK_REF_USED}।`, { reference: BANK_REF_USED });
      }
      let reference = bankRef;
      if (body.method === 'cash') {
        const s = await q1<{ n: number }>(c, `SELECT nextval('cash_ref_seq')::bigint AS n`);
        reference = cashRef(s?.n ?? 0);
      }
      const amount = rate * body.quantity;
      const o = await q1<OrderRow>(
        c,
        `INSERT INTO orders (shareholder_id, quantity, rate_paisa, amount_paisa, method, reference)
         VALUES ($1,$2,$3,$4,$5,$6)
         RETURNING id, order_seq, quantity, rate_paisa, amount_paisa, method, reference, status, note, created_at, decided_at`,
        [u.id, body.quantity, rate, amount, body.method, reference],
      );
      if (!o) throw new Error('order insert failed');
      await audit(ctx, c, {
        actorType: 'user',
        actorId: u.id,
        actorName: holder.full_name,
        category: 'shareholder',
        action: 'order.create',
        message: `${possessive(holder.full_name)} ${bnCount(body.quantity)}টি শেয়ারের অর্ডার (${bnMoney(amount)}, ${methodName(body.method)})`,
        tone: 'info',
        ip: req.ip,
        meta: { orderId: o.id },
      });
      return o;
    }).catch((err: unknown) => {
      // M1: the unique index is the real guard against two accounts racing with the same reference.
      if (isUniqueViolation(err) && err.constraint === 'orders_bank_ref_unique') {
        throw new ApiError('CONFLICT', `${BANK_REF_USED}।`, { reference: BANK_REF_USED });
      }
      throw err;
    });
    return reply.status(201).send(orderJson(row));
  });

  app.post('/me/orders/:id/cancel', { preHandler: auth }, async (req) => {
    const u = mustUser(req);
    const id = idParam(req.params);
    const row = await tx(ctx.db, async (c) => {
      const o = await q1<{ status: string }>(c, 'SELECT status FROM orders WHERE id = $1 AND shareholder_id = $2 FOR UPDATE', [id, u.id]);
      if (!o) throw new ApiError('NOT_FOUND');
      if (o.status !== 'pending') throw new ApiError('CONFLICT', 'শুধু যাচাইয়ের অপেক্ষায় থাকা অর্ডার বাতিল করা যায়।');
      const updated = await q1<OrderRow>(
        c,
        `UPDATE orders SET status = 'cancelled', note = 'শেয়ারহোল্ডার নিজে বাতিল করেছেন', decided_at = now()
          WHERE id = $1 RETURNING id, order_seq, quantity, rate_paisa, amount_paisa, method, reference, status, note, created_at, decided_at`,
        [id],
      );
      await audit(ctx, c, {
        actorType: 'user',
        actorId: u.id,
        actorName: u.name,
        category: 'shareholder',
        action: 'order.cancel',
        message: `${u.name}: নিজের অর্ডার বাতিল`,
        tone: 'warn',
        ip: req.ip,
        meta: { orderId: id },
      });
      return updated as OrderRow;
    });
    return orderJson(row);
  });

  // ---------------- certificate ----------------

  app.get('/me/certificate', { preHandler: auth }, async (req) => {
    const u = mustUser(req);
    const holder = await q1<{ shareholder_seq: number; full_name: string }>(
      ctx.db,
      'SELECT shareholder_seq, full_name FROM shareholders WHERE id = $1',
      [u.id],
    );
    const h = await holdingOf(ctx.db, u.id);
    if (!holder || h.shares <= 0 || !h.issuedOn) throw new ApiError('NOT_FOUND', 'এখনো কোনো যাচাইকৃত শেয়ার নেই।');
    const cert = certificateNo(holder.shareholder_seq);
    const sig = ctx.crypto.certSignature(cert, h.shares, h.issuedOn);
    const settings = await getSettings(ctx.db);
    return {
      certificateNo: cert,
      shareholderNo: shareholderNo(holder.shareholder_seq),
      fullName: holder.full_name,
      shares: h.shares,
      issuedOn: h.issuedOn,
      verifyUrl: `${ctx.config.publicBaseUrl}/verify/${cert}?s=${sig}`,
      companyName: settings.company.name,
    };
  });

  // ---------------- nominees & KYC ----------------

  app.put('/me/nominees', { preHandler: auth }, async (req) => {
    const u = mustUser(req);
    const body = parse(NomineesSchema, req.body);
    const holder = await q1<{ nid_hash: string }>(ctx.db, 'SELECT nid_hash FROM shareholders WHERE id = $1', [u.id]);
    if (!holder) throw new ApiError('UNAUTHORIZED');
    const clash = nomineeNidClash(ctx, holder.nid_hash, body.nominees);
    if (clash) throw new ApiError('INVALID_INPUT', undefined, clash);
    await tx(ctx.db, async (c) => {
      await c.query('DELETE FROM nominees WHERE shareholder_id = $1', [u.id]);
      await insertNominees(ctx, c, u.id, body.nominees);
      await audit(ctx, c, {
        actorType: 'user',
        actorId: u.id,
        actorName: u.name,
        category: 'shareholder',
        action: 'nominee.update',
        message: `${u.name}: নমিনির তথ্য হালনাগাদ (${bnCount(body.nominees.length)} জন)`,
        tone: 'info',
        ip: req.ip,
      });
    });
    const rows = await q<NomineeRow>(ctx.db, `SELECT ${NOMINEE_COLS} FROM nominees WHERE shareholder_id = $1 ORDER BY slot`, [u.id]);
    return rows.map(nomineeJson);
  });

  app.post('/me/kyc', { preHandler: auth, config: { rateLimit: { max: 5, timeWindow: '1 hour' } } }, async (req, reply) => {
    const u = mustUser(req);
    const { data, files } = await readMultipart(req, Object.keys(KYC_KINDS));
    const body = parse(KycSchema, data);
    const current = await q1<{ kyc_status: string }>(ctx.db, 'SELECT kyc_status FROM shareholders WHERE id = $1', [u.id]);
    if (!current || current.kyc_status !== 'rejected') {
      throw new ApiError('CONFLICT', 'শুধু KYC বাতিল হলে নতুন করে নথি জমা দেওয়া যায়।');
    }
    const images = await readKycImages(files);
    const nidHash = ctx.crypto.nidIndex(body.nidNumber);
    const other = await q1(ctx.db, `SELECT 1 FROM shareholders WHERE nid_hash = $1 AND id <> $2 AND kyc_status <> 'rejected'`, [nidHash, u.id]);
    if (other) throw new ApiError('CONFLICT', 'এই NID দিয়ে আগেই রেজিস্ট্রেশন করা আছে।', { nidNumber: 'এই NID দিয়ে আগেই রেজিস্ট্রেশন করা আছে' });
    const stored: { kind: KycKind; storageName: string; size: number }[] = [];
    try {
      for (const [kind, buf] of Object.entries(images) as [KycKind, Buffer][]) stored.push({ kind, ...(await storeEncrypted(ctx, buf)) });
      await tx(ctx.db, async (c) => {
        const locked = await q1<{ kyc_status: string }>(c, 'SELECT kyc_status FROM shareholders WHERE id = $1 FOR UPDATE', [u.id]);
        if (!locked || locked.kyc_status !== 'rejected') throw new ApiError('CONFLICT');
        await c.query('UPDATE kyc_files SET active = false WHERE shareholder_id = $1', [u.id]);
        for (const f of stored) {
          await c.query('INSERT INTO kyc_files (shareholder_id, kind, storage_name, size_bytes) VALUES ($1,$2,$3,$4)', [u.id, f.kind, f.storageName, f.size]);
        }
        await c.query(
          `UPDATE shareholders SET nid_enc = $2, nid_hash = $3, nid_last3 = $4, nid_len = $5, dob = $6,
                  kyc_status = 'pending', kyc_note = NULL, kyc_submitted_at = now(), kyc_decided_at = NULL, kyc_decided_by = NULL, updated_at = now()
            WHERE id = $1`,
          [u.id, ctx.crypto.encryptText(body.nidNumber), nidHash, body.nidNumber.slice(-3), body.nidNumber.length, body.dob],
        );
        await audit(ctx, c, {
          actorType: 'user',
          actorId: u.id,
          actorName: u.name,
          category: 'shareholder',
          action: 'kyc.resubmit',
          message: `${u.name}: KYC নথি নতুন করে জমা`,
          tone: 'info',
          ip: req.ip,
        });
      });
    } catch (err) {
      await removeStored(
        ctx,
        stored.map((s) => s.storageName),
      );
      if (isUniqueViolation(err)) throw new ApiError('CONFLICT', 'এই NID দিয়ে আগেই রেজিস্ট্রেশন করা আছে।');
      throw err;
    }
    return reply.status(204).send();
  });

  app.get('/me/payment-info', { preHandler: auth }, async () => {
    const s = await getSettings(ctx.db);
    return s.payment;
  });

  // ---------------- notices ----------------

  app.get('/notices', { preHandler: auth }, async (req) => {
    const u = mustUser(req);
    const page = parse(zPageQuery, req.query);
    const cur = decodeCursor(page.cursor);
    const rows = await q<{ id: string; title: string; body: string; category: string; created_at: Date; read: boolean }>(
      ctx.db,
      `SELECT n.id, n.title, n.body, n.category, n.created_at,
              EXISTS (SELECT 1 FROM notice_reads nr WHERE nr.notice_id = n.id AND nr.shareholder_id = $1) AS read
         FROM notices n
        WHERE ${visibleToSql('$1')}
          AND ($2::timestamptz IS NULL OR (n.created_at, n.id) < ($2::timestamptz, $3::uuid))
        ORDER BY n.created_at DESC, n.id DESC LIMIT $4`,
      [u.id, cur?.t ?? null, cur?.id ?? '00000000-0000-0000-0000-000000000000', page.limit + 1],
    );
    const unread = await q1<{ n: number }>(
      ctx.db,
      `SELECT count(*)::int AS n FROM notices n WHERE ${visibleToSql('$1')}
          AND NOT EXISTS (SELECT 1 FROM notice_reads nr WHERE nr.notice_id = n.id AND nr.shareholder_id = $1)`,
      [u.id],
    );
    const more = rows.length > page.limit;
    const items = rows.slice(0, page.limit);
    const last = items[items.length - 1];
    return {
      items: items.map((r) => ({ id: r.id, title: r.title, body: r.body, category: r.category, createdAt: r.created_at.toISOString(), read: r.read })),
      nextCursor: more && last ? encodeCursor(last.created_at, last.id) : null,
      unread: unread?.n ?? 0,
    };
  });

  app.post('/notices/read-all', { preHandler: auth }, async (req, reply) => {
    const u = mustUser(req);
    await ctx.db.query(
      `INSERT INTO notice_reads (notice_id, shareholder_id)
       SELECT n.id, $1 FROM notices n WHERE ${visibleToSql('$1')}
       ON CONFLICT DO NOTHING`,
      [u.id],
    );
    return reply.status(204).send();
  });

  app.post('/notices/:id/read', { preHandler: auth }, async (req, reply) => {
    const u = mustUser(req);
    const id = idParam(req.params);
    const res = await ctx.db.query(
      `INSERT INTO notice_reads (notice_id, shareholder_id)
       SELECT n.id, $1 FROM notices n WHERE n.id = $2 AND ${visibleToSql('$1')}
       ON CONFLICT DO NOTHING RETURNING notice_id`,
      [u.id, id],
    );
    if ((res.rowCount ?? 0) === 0) {
      const visible = await q1(ctx.db, `SELECT 1 FROM notices n WHERE n.id = $2 AND ${visibleToSql('$1')}`, [u.id, id]);
      if (!visible) throw new ApiError('NOT_FOUND');
    }
    return reply.status(204).send();
  });

  // ---------------- market ----------------

  app.get('/market/chart', { preHandler: auth }, async (req) => {
    const u = mustUser(req);
    const { period } = parse(ChartQuery, req.query);
    const h = await holdingOf(ctx.db, u.id);
    return buildChart(ctx.db, period as Period, dhakaDate(), h.shares);
  });
}
