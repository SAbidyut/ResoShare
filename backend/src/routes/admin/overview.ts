import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import type { Ctx } from '../../context.js';
import { q, q1 } from '../../db.js';
import { staffGuard } from '../../services/auth.js';
import { ADMIN_ORDER_COLS, adminOrderJson, type AdminOrderRow } from '../../services/format.js';
import { poolState, todayRate } from '../../services/holdings.js';
import { addDays, addMonths, diffDays, dhakaDate, firstOfMonth } from '../../util/time.js';
import { parse } from '../../util/validate.js';

const PeriodQuery = z.object({ period: z.enum(['daily', 'weekly', 'monthly', 'yearly']).default('monthly') }).loose();

type Bucket = { date: string; shares: number; amountPaisa: number };

export function salesBuckets(period: 'daily' | 'weekly' | 'monthly' | 'yearly', today: string): Bucket[] {
  const out: Bucket[] = [];
  if (period === 'daily') for (let i = 6; i >= 0; i--) out.push({ date: addDays(today, -i), shares: 0, amountPaisa: 0 });
  if (period === 'weekly') for (let i = 7; i >= 0; i--) out.push({ date: addDays(today, -7 * i - 6), shares: 0, amountPaisa: 0 });
  if (period === 'monthly') for (let i = 11; i >= 0; i--) out.push({ date: addMonths(firstOfMonth(today), -i), shares: 0, amountPaisa: 0 });
  if (period === 'yearly') {
    const y = Number(today.slice(0, 4));
    for (let i = 4; i >= 0; i--) out.push({ date: `${y - i}-01-01`, shares: 0, amountPaisa: 0 });
  }
  return out;
}

export function bucketIndex(buckets: Bucket[], day: string): number {
  // buckets are ascending by start date; find the last bucket whose start ≤ day
  for (let i = buckets.length - 1; i >= 0; i--) if ((buckets[i] as Bucket).date <= day) return i;
  return -1;
}

export async function adminOverviewRoutes(app: FastifyInstance, ctx: Ctx) {
  const view = staffGuard(ctx, 'view');

  app.get('/admin/dashboard', { preHandler: view }, async () => {
    const today = await todayRate(ctx.db);
    const pool = await poolState(ctx.db);
    const holders = await q1<{ n: number }>(ctx.db, 'SELECT count(*)::int AS n FROM shareholders');
    const pend = await q1<{ payments: number; kyc: number }>(
      ctx.db,
      `SELECT (SELECT count(*)::int FROM orders WHERE status = 'pending') AS payments,
              (SELECT count(*)::int FROM shareholders WHERE kyc_status = 'pending') AS kyc`,
    );
    const recent = await q<AdminOrderRow>(
      ctx.db,
      `SELECT ${ADMIN_ORDER_COLS} FROM orders o JOIN shareholders s ON s.id = o.shareholder_id
        ORDER BY o.created_at DESC, o.id DESC LIMIT 5`,
    );
    return {
      today,
      pool,
      shareholderCount: holders?.n ?? 0,
      pending: { payments: pend?.payments ?? 0, kyc: pend?.kyc ?? 0 },
      recentOrders: recent.map(adminOrderJson),
    };
  });

  app.get('/admin/stats/sales', { preHandler: view }, async (req) => {
    const { period } = parse(PeriodQuery, req.query);
    const today = dhakaDate();
    const buckets = salesBuckets(period, today);
    const start = (buckets[0] as Bucket).date;
    const rows = await q<{ d: string; shares: number; amount: number }>(
      ctx.db,
      `SELECT (decided_at AT TIME ZONE 'Asia/Dhaka')::date AS d, SUM(quantity)::bigint AS shares, SUM(amount_paisa)::bigint AS amount
         FROM orders
        WHERE status = 'confirmed' AND decided_at >= ($1::date::timestamp AT TIME ZONE 'Asia/Dhaka')
        GROUP BY 1`,
      [start],
    );
    for (const r of rows) {
      if (diffDays(r.d, today) > 0) continue;
      const i = bucketIndex(buckets, r.d);
      if (i < 0) continue;
      const b = buckets[i] as Bucket;
      b.shares += r.shares;
      b.amountPaisa += r.amount;
    }
    const last = buckets[buckets.length - 1] as Bucket;
    const prev = buckets[buckets.length - 2];
    return {
      period,
      points: buckets,
      totalShares: buckets.reduce((s, b) => s + b.shares, 0),
      totalAmountPaisa: buckets.reduce((s, b) => s + b.amountPaisa, 0),
      lastChangeShares: last.shares - (prev?.shares ?? 0),
    };
  });
}
