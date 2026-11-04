import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { z } from 'zod';
import type { Ctx } from '../../context.js';
import { q, q1, tx } from '../../db.js';
import { ApiError } from '../../errors.js';
import { audit } from '../../services/audit.js';
import { mustStaff, staffGuard } from '../../services/auth.js';
import { ADMIN_ORDER_COLS, adminOrderJson, type AdminOrderRow } from '../../services/format.js';
import { poolState } from '../../services/holdings.js';
import { createNotice } from '../../services/notices.js';
import { bnCount, bnMoney, possessive, toAsciiDigits } from '../../util/bn.js';
import { dhakaDate, firstOfMonth } from '../../util/time.js';
import { decodeCursor, encodeCursor, idParam, likePattern, parse, zPageQuery, zReason } from '../../util/validate.js';

const ListQuery = zPageQuery.extend({
  q: z.string().max(100).optional(),
  status: z.enum(['all', 'confirmed', 'pending', 'cancelled']).default('all'),
});
const ReasonSchema = z.object({ reason: zReason }).strict();

export async function adminOrderRoutes(app: FastifyInstance, ctx: Ctx) {
  const view = staffGuard(ctx, 'view');
  const approve = staffGuard(ctx, 'payment_approve');

  app.get('/admin/orders', { preHandler: view }, async (req) => {
    const query = parse(ListQuery, req.query);
    const cur = decodeCursor(query.cursor);
    const params: unknown[] = [];
    const where: string[] = [];
    if (query.status !== 'all') {
      params.push(query.status);
      where.push(`o.status = $${params.length}`);
    }
    const text = query.q?.trim();
    if (text) {
      const ascii = toAsciiDigits(text);
      params.push(likePattern(text));
      const conds = [`s.full_name ILIKE $${params.length} ESCAPE '\\'`];
      params.push(likePattern(ascii));
      conds.push(`o.reference ILIKE $${params.length} ESCAPE '\\'`);
      const om = /^(?:ORD-)?0*(\d{1,12})$/i.exec(ascii);
      if (om) {
        params.push(Number(om[1]));
        conds.push(`o.order_seq = $${params.length}`);
      }
      const sm = /^RA-SH-0*(\d{1,9})$/i.exec(ascii);
      if (sm) {
        params.push(Number(sm[1]));
        conds.push(`s.shareholder_seq = $${params.length}`);
      }
      where.push(`(${conds.join(' OR ')})`);
    }
    params.push(cur?.t ?? null, cur?.id ?? '00000000-0000-0000-0000-000000000000');
    const n = params.length;
    where.push(`($${n - 1}::timestamptz IS NULL OR (o.created_at, o.id) < ($${n - 1}::timestamptz, $${n}::uuid))`);
    params.push(query.limit + 1);
    const rows = await q<AdminOrderRow>(
      ctx.db,
      `SELECT ${ADMIN_ORDER_COLS} FROM orders o JOIN shareholders s ON s.id = o.shareholder_id
        WHERE ${where.join(' AND ')}
        ORDER BY o.created_at DESC, o.id DESC LIMIT $${params.length}`,
      params,
    );
    const today = dhakaDate();
    const totals = await q1<{ today: number; month: number }>(
      ctx.db,
      `SELECT
         COALESCE(SUM(amount_paisa) FILTER (WHERE created_at >= ($1::date::timestamp AT TIME ZONE 'Asia/Dhaka')), 0)::bigint AS today,
         COALESCE(SUM(amount_paisa) FILTER (WHERE created_at >= ($2::date::timestamp AT TIME ZONE 'Asia/Dhaka')), 0)::bigint AS month
         FROM orders WHERE status IN ('pending','confirmed') AND created_at >= ($2::date::timestamp AT TIME ZONE 'Asia/Dhaka')`,
      [today, firstOfMonth(today)],
    );
    const more = rows.length > query.limit;
    const items = rows.slice(0, query.limit);
    const last = items[items.length - 1];
    return {
      items: items.map(adminOrderJson),
      nextCursor: more && last ? encodeCursor(last.created_at, last.id) : null,
      todayTotalPaisa: totals?.today ?? 0,
      monthTotalPaisa: totals?.month ?? 0,
    };
  });

  const decide = (ok: boolean) => async (req: FastifyRequest, _reply: FastifyReply) => {
    const st = mustStaff(req);
    const id = idParam(req.params);
    const reason = ok ? null : parse(ReasonSchema, req.body).reason;
    const row = await tx(ctx.db, async (c) => {
      const o = await q1<{ status: string; shareholder_id: string; quantity: number; amount_paisa: number; method: string }>(
        c,
        'SELECT status, shareholder_id, quantity, amount_paisa, method FROM orders WHERE id = $1 FOR UPDATE',
        [id],
      );
      if (!o) throw new ApiError('NOT_FOUND');
      if (o.status !== 'pending') throw new ApiError('CONFLICT', 'এই অর্ডার আগেই নিষ্পত্তি হয়েছে।');
      const holder = await q1<{ full_name: string; account_status: string }>(c, 'SELECT full_name, account_status FROM shareholders WHERE id = $1', [
        o.shareholder_id,
      ]);
      if (!holder) throw new ApiError('NOT_FOUND');
      if (ok) {
        if (holder.account_status !== 'active') throw new ApiError('CONFLICT', 'শেয়ারহোল্ডারের অ্যাকাউন্ট স্থগিত আছে।');
        const pool = await poolState(c, true); // locks the pool row: no overselling under concurrency
        if (pool.remainingShares < o.quantity) {
          throw new ApiError('INSUFFICIENT_SHARES', `মাত্র ${bnCount(pool.remainingShares)}টি শেয়ার অবশিষ্ট আছে।`);
        }
      }
      await c.query('UPDATE orders SET status = $2, note = $3, decided_at = now(), decided_by = $4 WHERE id = $1', [
        id,
        ok ? 'confirmed' : 'cancelled',
        reason,
        st.id,
      ]);
      const updated = await q1<AdminOrderRow>(
        c,
        `SELECT ${ADMIN_ORDER_COLS} FROM orders o JOIN shareholders s ON s.id = o.shareholder_id WHERE o.id = $1`,
        [id],
      );
      const method = o.method === 'cash' ? 'ক্যাশ জমা' : 'ব্যাংক ট্রান্সফার';
      await createNotice(c, {
        title: ok ? `আপনার ${method} যাচাই হয়েছে` : `আপনার ${method} বাতিল হয়েছে`,
        body: ok
          ? `${bnCount(o.quantity)}টি শেয়ার আপনার প্রোফাইলে যুক্ত হয়েছে। মোট ${bnMoney(o.amount_paisa)}।`
          : `${bnCount(o.quantity)}টি শেয়ারের অর্ডার (${bnMoney(o.amount_paisa)}) বাতিল করা হয়েছে। কারণ: ${reason}`,
        category: 'payment',
        audience: 'user',
        userIds: [o.shareholder_id],
        createdBy: st.id,
      });
      await audit(c, {
        actorType: 'staff',
        actorId: st.id,
        actorName: st.name,
        category: 'approval',
        action: ok ? 'payment.approve' : 'payment.reject',
        message: `${possessive(holder.full_name)} ${bnMoney(o.amount_paisa)} পেমেন্ট ${ok ? 'অনুমোদিত' : 'বাতিল'}`,
        tone: ok ? 'ok' : 'bad',
        ip: req.ip,
        meta: { orderId: id, ...(reason ? { reason } : {}) },
      });
      return updated as AdminOrderRow;
    });
    return adminOrderJson(row);
  };

  app.post('/admin/orders/:id/approve', { preHandler: approve }, decide(true));
  app.post('/admin/orders/:id/reject', { preHandler: approve }, decide(false));
}
