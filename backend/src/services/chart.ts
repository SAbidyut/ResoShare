import type { Client } from '../db.js';
import { addDays, addMonths, firstOfMonth, lastOfMonth } from '../util/time.js';
import { rateFromSeries, rateSeries, type RatePoint } from './holdings.js';

export type Period = 'daily' | 'weekly' | 'monthly' | 'yearly';

export function chartDates(period: Period, today: string): string[] {
  const out: string[] = [];
  switch (period) {
    case 'daily':
      for (let i = 6; i >= 0; i--) out.push(addDays(today, -i));
      break;
    case 'weekly':
      for (let i = 7; i >= 0; i--) out.push(addDays(today, -7 * i));
      break;
    case 'monthly':
      for (let i = 11; i >= 1; i--) out.push(lastOfMonth(addMonths(firstOfMonth(today), -i)));
      out.push(today);
      break;
    case 'yearly': {
      const y = Number(today.slice(0, 4));
      for (let i = 4; i >= 1; i--) out.push(`${y - i}-12-31`);
      out.push(today);
      break;
    }
  }
  return out;
}

export async function buildChart(client: Client, period: Period, today: string, myShares: number) {
  const series = await rateSeries(client, today);
  const points: RatePoint[] = [];
  for (const d of chartDates(period, today)) {
    const r = rateFromSeries(series, d);
    if (r !== null) points.push({ date: d, ratePaisa: r });
  }
  const todayRate = rateFromSeries(series, today) ?? 0;
  const yesterday = rateFromSeries(series, addDays(today, -1));
  const prevMonthEnd = rateFromSeries(series, addDays(firstOfMonth(today), -1));
  const first = points[0]?.ratePaisa ?? todayRate;
  const last = points[points.length - 1]?.ratePaisa ?? todayRate;
  const rates = points.map((p) => p.ratePaisa);
  const periodChange = last - first;
  return {
    period,
    points,
    todayRatePaisa: todayRate,
    dayChangePaisa: yesterday === null ? 0 : todayRate - yesterday,
    periodChangePaisa: periodChange,
    periodChangeBp: first > 0 ? Math.round((periodChange * 10_000) / first) : 0,
    highPaisa: rates.length ? Math.max(...rates) : todayRate,
    lowPaisa: rates.length ? Math.min(...rates) : todayRate,
    monthChangePaisa: prevMonthEnd === null ? 0 : todayRate - prevMonthEnd,
    myShares,
  };
}
