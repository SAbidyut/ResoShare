import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import type { Ctx } from '../../context.js';
import { q, q1, tx } from '../../db.js';
import { ApiError } from '../../errors.js';
import { audit } from '../../services/audit.js';
import { mustStaff, staffGuard } from '../../services/auth.js';
import { poolState, rateOn } from '../../services/holdings.js';
import { createNotice } from '../../services/notices.js';
import { bnCount, bnDate, bnDigits, bnMoney } from '../../util/bn.js';
import { addDays, diffDays, dhakaDate } from '../../util/time.js';
import { parse, zDate } from '../../util/validate.js';

const ScheduleSchema = z
  .object({
    items: z
      .array(
        z
          .object({
            date: zDate,
            ratePaisa: z.number().int('পয়সাসহ সঠিক রেট দিন').min(1, 'রেট শূন্যের বেশি হতে হবে').max(10_000_000_000, 'রেট অনেক বড়'),
          })
          .strict(),
      )
      .min(1, 'কমপক্ষে একটি দিনের রেট দিন')
      .max(15, 'সর্বোচ্চ ১৫ দিনের রেট দেওয়া যাবে'),
    notify: z.boolean(),
  })
  .strict();

const PoolSchema = z.object({ saleEnabled: z.boolean() }).strict();
const IssueSchema = z.object({ additionalShares: z.number().int().min(1, 'কমপক্ষে ১টি শেয়ার').max(1_000_000, 'একবারে সর্বোচ্চ ১০,০০,০০০টি') }).strict();
const DividendSchema = z
  .object({
    fiscalYear: z.string().regex(/^\d{4}$/, 'অর্থবছর ৪ অঙ্কে লিখুন (যেমন ২০২৬)'),
    perSharePaisa: z.number().int().min(1, 'ডিভিডেন্ড শূন্যের বেশি হতে হবে').max(1_000_000_000, 'পরিমাণ অনেক বড়'),
    distributionDate: zDate,
  })
  .strict();

export async function ratesAdmin(ctx: Ctx) {
  const today = dhakaDate();
  const tomorrow = addDays(today, 1);
  const todayRate = (await rateOn(ctx.db, today)) ?? 0;
  const tomorrowRate = (await rateOn(ctx.db, tomorrow)) ?? todayRate;
  const future = await q<{ rate_date: string; rate_paisa: number }>(
    ctx.db,
    'SELECT rate_date, rate_paisa FROM rates WHERE rate_date > $1 AND rate_date <= $2',
    [today, addDays(today, 15)],
  );
  const byDate = new Map(future.map((r) => [r.rate_date, r.rate_paisa]));
  const schedule = Array.from({ length: 15 }, (_, i) => {
    const d = addDays(today, i + 1);
    return { date: d, ratePaisa: byDate.get(d) ?? null };
  });
  const hist = await q<{ rate_date: string; rate_paisa: number; set_by_name: string | null }>(
    ctx.db,
    `SELECT r.rate_date, r.rate_paisa, st.name AS set_by_name FROM rates r LEFT JOIN staff st ON st.id = r.set_by
      WHERE r.rate_date <= $1 ORDER BY r.rate_date DESC LIMIT 31`,
    [today],
  );
  const history = hist.slice(0, 30).map((r, i) => {
    const older = hist[i + 1];
    return { date: r.rate_date, ratePaisa: r.rate_paisa, changePaisa: older ? r.rate_paisa - older.rate_paisa : 0, setByName: r.set_by_name };
  });
  return {
    today: { date: today, ratePaisa: todayRate },
    tomorrow: { date: tomorrow, ratePaisa: tomorrowRate },
    schedule,
    history,
  };
}

function dividendJson(r: {
  id: string;
  fiscal_year: string;
  per_share_paisa: number;
  distribution_date: string;
  total_shares: number;
  total_paisa: number;
  created_at: Date;
}) {
  return {
    id: r.id,
    fiscalYear: r.fiscal_year,
    perSharePaisa: r.per_share_paisa,
    distributionDate: r.distribution_date,
    totalShares: r.total_shares,
    totalPaisa: r.total_paisa,
    status: r.distribution_date <= dhakaDate() ? 'distributed' : 'declared',
    createdAt: r.created_at.toISOString(),
  };
}

export async function adminMarketRoutes(app: FastifyInstance, ctx: Ctx) {
  const view = staffGuard(ctx, 'view');
  const rateChange = staffGuard(ctx, 'rate_change');
  const settingsStaff = staffGuard(ctx, 'settings_staff');
  const dividend = staffGuard(ctx, 'dividend');

  app.get('/admin/rates', { preHandler: view }, async () => ratesAdmin(ctx));

  app.put('/admin/rates/schedule', { preHandler: rateChange }, async (req) => {
    const st = mustStaff(req);
    const body = parse(ScheduleSchema, req.body);
    const today = dhakaDate();
    const fields: Record<string, string> = {};
    const seen = new Set<string>();
    body.items.forEach((it, i) => {
      const d = diffDays(it.date, today);
      if (d < 1 || d > 15) fields[`items.${i}.date`] = 'শুধু আগামী ১৫ দিনের রেট বসানো যাবে';
      if (seen.has(it.date)) fields[`items.${i}.date`] = 'একই তারিখ একাধিকবার দেওয়া হয়েছে';
      seen.add(it.date);
    });
    if (Object.keys(fields).length) throw new ApiError('INVALID_INPUT', 'শুধু আগামী ১৫ দিনের রেট বসানো যাবে।', fields);
    const sorted = [...body.items].sort((a, b) => a.date.localeCompare(b.date));
    await tx(ctx.db, async (c) => {
      for (const it of sorted) {
        await c.query(
          `INSERT INTO rates (rate_date, rate_paisa, notify, notice_posted, set_by, set_at) VALUES ($1,$2,$3,false,$4,now())
           ON CONFLICT (rate_date) DO UPDATE SET rate_paisa = EXCLUDED.rate_paisa, notify = EXCLUDED.notify,
             notice_posted = false, set_by = EXCLUDED.set_by, set_at = now()`,
          [it.date, it.ratePaisa, body.notify, st.id],
        );
      }
      const first = sorted[0];
      const lastItem = sorted[sorted.length - 1];
      await audit(ctx, c, {
        actorType: 'staff',
        actorId: st.id,
        actorName: st.name,
        category: 'rate',
        action: 'rate.schedule',
        message: `শেয়ারের রেট সূচি হালনাগাদ (${bnCount(sorted.length)} দিন${
          first && lastItem ? `: ${bnDate(first.date, false)} – ${bnDate(lastItem.date, false)}` : ''
        })`,
        tone: 'warn',
        ip: req.ip,
        meta: { items: sorted, notify: body.notify },
      });
    });
    return ratesAdmin(ctx);
  });

  app.get('/admin/pool', { preHandler: view }, async () => poolState(ctx.db));

  app.put('/admin/pool', { preHandler: rateChange }, async (req) => {
    const st = mustStaff(req);
    const body = parse(PoolSchema, req.body);
    await tx(ctx.db, async (c) => {
      await c.query('UPDATE share_pool SET sale_enabled = $1, updated_at = now() WHERE id = 1', [body.saleEnabled]);
      await audit(ctx, c, {
        actorType: 'staff',
        actorId: st.id,
        actorName: st.name,
        category: 'settings',
        action: body.saleEnabled ? 'pool.sale.on' : 'pool.sale.off',
        message: body.saleEnabled ? 'শেয়ার বিক্রি চালু করা হয়েছে' : 'শেয়ার বিক্রি বন্ধ করা হয়েছে',
        tone: 'warn',
        ip: req.ip,
      });
    });
    return poolState(ctx.db);
  });

  app.post('/admin/pool/issue', { preHandler: settingsStaff }, async (req) => {
    const st = mustStaff(req);
    const body = parse(IssueSchema, req.body);
    await tx(ctx.db, async (c) => {
      const p = await poolState(c, true);
      await c.query('UPDATE share_pool SET total_shares = total_shares + $1, updated_at = now() WHERE id = 1', [body.additionalShares]);
      await audit(ctx, c, {
        actorType: 'staff',
        actorId: st.id,
        actorName: st.name,
        category: 'settings',
        action: 'pool.issue',
        message: `নতুন ${bnCount(body.additionalShares)}টি শেয়ার ইস্যু (মোট ${bnCount(p.totalShares)} → ${bnCount(p.totalShares + body.additionalShares)})`,
        tone: 'warn',
        ip: req.ip,
      });
    });
    return poolState(ctx.db);
  });

  app.get('/admin/dividends', { preHandler: view }, async () => {
    const rows = await q<Parameters<typeof dividendJson>[0]>(
      ctx.db,
      'SELECT id, fiscal_year, per_share_paisa, distribution_date, total_shares, total_paisa, created_at FROM dividends ORDER BY fiscal_year DESC',
    );
    const pool = await poolState(ctx.db);
    return { items: rows.map(dividendJson), soldShares: pool.soldShares };
  });

  app.post('/admin/dividends', { preHandler: dividend }, async (req, reply) => {
    const st = mustStaff(req);
    const body = parse(DividendSchema, req.body);
    if (body.distributionDate < dhakaDate()) {
      throw new ApiError('INVALID_INPUT', undefined, { distributionDate: 'বিতরণের তারিখ আজ বা পরের কোনো দিন হতে হবে' });
    }
    const row = await tx(ctx.db, async (c) => {
      await poolState(c, true);
      const exists = await q1(c, 'SELECT 1 FROM dividends WHERE fiscal_year = $1', [body.fiscalYear]);
      if (exists) throw new ApiError('CONFLICT', 'এই অর্থবছরের ডিভিডেন্ড আগেই ঘোষণা করা হয়েছে।', { fiscalYear: 'এই অর্থবছরের ডিভিডেন্ড আগেই ঘোষণা করা হয়েছে' });
      const holders = await q<{ shareholder_id: string; shares: number }>(
        c,
        `SELECT shareholder_id, SUM(quantity)::bigint AS shares FROM orders WHERE status = 'confirmed' GROUP BY shareholder_id HAVING SUM(quantity) > 0`,
      );
      const totalShares = holders.reduce((s, h) => s + h.shares, 0);
      if (totalShares === 0) throw new ApiError('INVALID_INPUT', 'এখনো কোনো বিক্রিত শেয়ার নেই।');
      const d = await q1<Parameters<typeof dividendJson>[0]>(
        c,
        `INSERT INTO dividends (fiscal_year, per_share_paisa, distribution_date, total_shares, total_paisa, created_by)
         VALUES ($1,$2,$3,$4,$5,$6)
         RETURNING id, fiscal_year, per_share_paisa, distribution_date, total_shares, total_paisa, created_at`,
        [body.fiscalYear, body.perSharePaisa, body.distributionDate, totalShares, totalShares * body.perSharePaisa, st.id],
      );
      if (!d) throw new Error('dividend insert failed');
      for (const h of holders) {
        await c.query('INSERT INTO dividend_payouts (dividend_id, shareholder_id, shares, amount_paisa) VALUES ($1,$2,$3,$4)', [
          d.id,
          h.shareholder_id,
          h.shares,
          h.shares * body.perSharePaisa,
        ]);
      }
      await createNotice(c, {
        title: `${bnDigits(body.fiscalYear)} অর্থবছরের ডিভিডেন্ড ঘোষণা`,
        body: `প্রতি শেয়ারে ${bnMoney(body.perSharePaisa)} ডিভিডেন্ড ঘোষণা করা হয়েছে। বিতরণের তারিখ: ${bnDate(body.distributionDate)}।`,
        category: 'dividend',
        audience: 'all',
        createdBy: st.id,
      });
      await audit(ctx, c, {
        actorType: 'staff',
        actorId: st.id,
        actorName: st.name,
        category: 'content',
        action: 'dividend.declare',
        message: `${bnDigits(body.fiscalYear)} অর্থবছরের ডিভিডেন্ড ঘোষণা: প্রতি শেয়ারে ${bnMoney(body.perSharePaisa)} (মোট ${bnMoney(totalShares * body.perSharePaisa)})`,
        tone: 'ok',
        ip: req.ip,
        meta: { dividendId: d.id },
      });
      return d;
    });
    return reply.status(201).send(dividendJson(row));
  });
}
