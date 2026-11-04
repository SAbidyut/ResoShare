import type { Ctx } from '../context.js';
import { q, q1, tx } from '../db.js';
import { audit } from '../services/audit.js';
import { createNotice } from '../services/notices.js';
import { bnDate, bnMoney } from '../util/bn.js';
import { dhakaDate } from '../util/time.js';

/**
 * Posts the in-app "rate changed" notice for today's scheduled rate (once per date, idempotent).
 * Rows from the last 2 days that were never processed are closed without a stale notice.
 */
export async function runRateNoticeJob(ctx: Ctx, today: string = dhakaDate()): Promise<number> {
  return tx(ctx.db, async (c) => {
    const rows = await q<{ rate_date: string; rate_paisa: number; notify: boolean }>(
      c,
      `SELECT rate_date, rate_paisa, notify FROM rates
        WHERE NOT notice_posted AND rate_date <= $1 AND rate_date >= ($1::date - 2)
        ORDER BY rate_date FOR UPDATE SKIP LOCKED`,
      [today],
    );
    let posted = 0;
    for (const r of rows) {
      if (r.notify && r.rate_date === today) {
        const prev = await q1<{ rate_paisa: number }>(c, 'SELECT rate_paisa FROM rates WHERE rate_date < $1 ORDER BY rate_date DESC LIMIT 1', [r.rate_date]);
        if (prev && prev.rate_paisa !== r.rate_paisa) {
          const diff = r.rate_paisa - prev.rate_paisa;
          await createNotice(c, {
            title: 'শেয়ারের রেট পরিবর্তন',
            body: `${bnDate(today, false)} থেকে প্রতি শেয়ারের রেট ${bnMoney(r.rate_paisa)} (কেনা ও বিক্রয় একই)। আগের দিনের তুলনায় ${diff > 0 ? '▲' : '▼'} ${bnMoney(Math.abs(diff))}।`,
            category: 'rate',
            audience: 'all',
          });
          await audit(ctx, c, {
            actorType: 'system',
            actorId: null,
            actorName: 'সিস্টেম',
            category: 'rate',
            action: 'rate.activate',
            message: `শেয়ারের রেট ${bnMoney(prev.rate_paisa)} থেকে ${bnMoney(r.rate_paisa)} করা হয়েছে (সূচি অনুযায়ী)`,
            tone: 'warn',
          });
          posted += 1;
        }
      }
      await c.query('UPDATE rates SET notice_posted = true WHERE rate_date = $1', [r.rate_date]);
    }
    return posted;
  });
}

export async function runCleanupJob(ctx: Ctx): Promise<void> {
  await ctx.db.query(`DELETE FROM mfa_challenges WHERE expires_at < now() - interval '1 day'`);
  await ctx.db.query(`DELETE FROM sessions WHERE (expires_at < now() - interval '7 days') OR (revoked_at < now() - interval '30 days')`);
  await ctx.db.query(`DELETE FROM refresh_tokens WHERE expires_at < now() - interval '1 day'`);
}

export function startScheduler(ctx: Ctx): () => void {
  let running = false;
  const tick = async () => {
    if (running) return;
    running = true;
    try {
      await runRateNoticeJob(ctx);
    } catch (err) {
      ctx.log.error({ err }, 'rate notice job failed');
    } finally {
      running = false;
    }
  };
  void tick();
  const minute = setInterval(() => void tick(), 60_000);
  const hourly = setInterval(() => {
    runCleanupJob(ctx).catch((err) => ctx.log.error({ err }, 'cleanup job failed'));
  }, 3_600_000);
  minute.unref();
  hourly.unref();
  return () => {
    clearInterval(minute);
    clearInterval(hourly);
  };
}
