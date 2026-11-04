import type { Client } from '../db.js';
import { q, q1 } from '../db.js';
import { dhakaDate } from '../util/time.js';

export type RatePoint = { date: string; ratePaisa: number };

/** Effective rate on a date = the row for that date, else the latest earlier row. */
export async function rateOn(client: Client, date: string): Promise<number | null> {
  const r = await q1<{ rate_paisa: number }>(
    client,
    'SELECT rate_paisa FROM rates WHERE rate_date <= $1 ORDER BY rate_date DESC LIMIT 1',
    [date],
  );
  return r?.rate_paisa ?? null;
}

export async function todayRate(client: Client): Promise<{ date: string; ratePaisa: number }> {
  const date = dhakaDate();
  return { date, ratePaisa: (await rateOn(client, date)) ?? 0 };
}

/** All rate rows up to `upTo`, ascending (small table: one row per day). */
export async function rateSeries(client: Client, upTo: string): Promise<RatePoint[]> {
  const rows = await q<{ rate_date: string; rate_paisa: number }>(
    client,
    'SELECT rate_date, rate_paisa FROM rates WHERE rate_date <= $1 ORDER BY rate_date ASC',
    [upTo],
  );
  return rows.map((r) => ({ date: r.rate_date, ratePaisa: r.rate_paisa }));
}

export function rateFromSeries(series: RatePoint[], date: string): number | null {
  let lo = 0;
  let hi = series.length - 1;
  let ans: number | null = null;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    const p = series[mid] as RatePoint;
    if (p.date <= date) {
      ans = p.ratePaisa;
      lo = mid + 1;
    } else hi = mid - 1;
  }
  return ans;
}

export type Holding = { shares: number; paidPaisa: number; issuedOn: string | null };

export async function holdingOf(client: Client, shareholderId: string): Promise<Holding> {
  const r = await q1<{ shares: number; paid: number; issued_on: string | null }>(
    client,
    `SELECT COALESCE(SUM(quantity), 0)::bigint AS shares,
            COALESCE(SUM(amount_paisa), 0)::bigint AS paid,
            (MAX(decided_at) AT TIME ZONE 'Asia/Dhaka')::date AS issued_on
       FROM orders WHERE shareholder_id = $1 AND status = 'confirmed'`,
    [shareholderId],
  );
  return { shares: r?.shares ?? 0, paidPaisa: r?.paid ?? 0, issuedOn: r?.issued_on ?? null };
}

export type Pool = { totalShares: number; soldShares: number; remainingShares: number; saleEnabled: boolean };

export async function poolState(client: Client, forUpdate = false): Promise<Pool> {
  const p = await q1<{ total_shares: number; sale_enabled: boolean }>(
    client,
    `SELECT total_shares, sale_enabled FROM share_pool WHERE id = 1${forUpdate ? ' FOR UPDATE' : ''}`,
  );
  const s = await q1<{ sold: number }>(client, `SELECT COALESCE(SUM(quantity), 0)::bigint AS sold FROM orders WHERE status = 'confirmed'`);
  const total = p?.total_shares ?? 0;
  const sold = s?.sold ?? 0;
  return { totalShares: total, soldShares: sold, remainingShares: Math.max(0, total - sold), saleEnabled: p?.sale_enabled ?? false };
}
