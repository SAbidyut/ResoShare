import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import type { Ctx } from '../../context.js';
import { q } from '../../db.js';
import { ApiError } from '../../errors.js';
import { audit } from '../../services/audit.js';
import { mustStaff, staffGuard } from '../../services/auth.js';
import { orderNo, shareholderNo } from '../../services/format.js';
import { plainMoney } from '../../util/bn.js';
import { maskMobile } from '../../util/mask.js';
import { dhakaDate } from '../../util/time.js';
import { parse, zDate } from '../../util/validate.js';

const TYPES = ['sales', 'shareholders', 'transactions', 'dividends', 'kyc'] as const;
type ReportType = (typeof TYPES)[number];

const TITLES: Record<ReportType, string> = {
  sales: 'বিক্রয় রিপোর্ট',
  shareholders: 'শেয়ারহোল্ডার তালিকা',
  transactions: 'লেনদেন বিবরণী',
  dividends: 'ডিভিডেন্ড রিপোর্ট',
  kyc: 'KYC অবস্থা',
};

const Query = z.object({ from: zDate.optional(), to: zDate.optional() }).loose();

const STATUS_BN: Record<string, string> = { pending: 'যাচাই চলছে', confirmed: 'নিশ্চিত', cancelled: 'বাতিল' };
const KYC_BN: Record<string, string> = { pending: 'অপেক্ষমাণ', verified: 'যাচাইকৃত', rejected: 'বাতিল' };
const ACCOUNT_BN: Record<string, string> = { active: 'সক্রিয়', suspended: 'স্থগিত' };
const METHOD_BN: Record<string, string> = { cash: 'ক্যাশ', bank: 'ব্যাংক ট্রান্সফার' };

const dhakaDay = (d: Date | null) => (d ? dhakaDate(d) : '');

export async function adminReportRoutes(app: FastifyInstance, ctx: Ctx) {
  const reports = staffGuard(ctx, 'reports');

  app.get('/admin/reports/:type', { preHandler: reports, config: { rateLimit: { max: 20, timeWindow: '1 minute' } } }, async (req) => {
    const st = mustStaff(req);
    const type = (req.params as { type?: string }).type as ReportType;
    if (!TYPES.includes(type)) throw new ApiError('NOT_FOUND');
    const query = parse(Query, req.query);
    const from = query.from ?? '2000-01-01';
    const to = query.to ?? dhakaDate();
    if (from > to) throw new ApiError('INVALID_INPUT', undefined, { from: 'শুরুর তারিখ শেষ তারিখের আগে হতে হবে' });
    // [from 00:00, to+1 00:00) in Asia/Dhaka
    const range = [from, to];
    const lower = `($1::date::timestamp AT TIME ZONE 'Asia/Dhaka')`;
    const upper = `(($2::date + 1)::timestamp AT TIME ZONE 'Asia/Dhaka')`;

    let columns: string[] = [];
    let rows: string[][] = [];

    if (type === 'sales') {
      columns = ['তারিখ', 'অর্ডার নম্বর', 'শেয়ারহোল্ডার ID', 'নাম', 'শেয়ার', 'রেট (৳)', 'মোট (৳)', 'মাধ্যম'];
      const r = await q<{ decided_at: Date; order_seq: number; shareholder_seq: number; full_name: string; quantity: number; rate_paisa: number; amount_paisa: number; method: string }>(
        ctx.db,
        `SELECT o.decided_at, o.order_seq, s.shareholder_seq, s.full_name, o.quantity, o.rate_paisa, o.amount_paisa, o.method
           FROM orders o JOIN shareholders s ON s.id = o.shareholder_id
          WHERE o.status = 'confirmed' AND o.decided_at >= ${lower} AND o.decided_at < ${upper}
          ORDER BY o.decided_at ASC LIMIT 20000`,
        range,
      );
      rows = r.map((x) => [
        dhakaDay(x.decided_at),
        orderNo(x.order_seq),
        shareholderNo(x.shareholder_seq),
        x.full_name,
        String(x.quantity),
        plainMoney(x.rate_paisa),
        plainMoney(x.amount_paisa),
        METHOD_BN[x.method] ?? x.method,
      ]);
    } else if (type === 'transactions') {
      columns = ['তারিখ', 'অর্ডার নম্বর', 'শেয়ারহোল্ডার ID', 'নাম', 'শেয়ার', 'মোট (৳)', 'মাধ্যম', 'রেফারেন্স', 'অবস্থা'];
      const r = await q<{ created_at: Date; order_seq: number; shareholder_seq: number; full_name: string; quantity: number; amount_paisa: number; method: string; reference: string | null; status: string }>(
        ctx.db,
        `SELECT o.created_at, o.order_seq, s.shareholder_seq, s.full_name, o.quantity, o.amount_paisa, o.method, o.reference, o.status
           FROM orders o JOIN shareholders s ON s.id = o.shareholder_id
          WHERE o.created_at >= ${lower} AND o.created_at < ${upper}
          ORDER BY o.created_at ASC LIMIT 20000`,
        range,
      );
      rows = r.map((x) => [
        dhakaDay(x.created_at),
        orderNo(x.order_seq),
        shareholderNo(x.shareholder_seq),
        x.full_name,
        String(x.quantity),
        plainMoney(x.amount_paisa),
        METHOD_BN[x.method] ?? x.method,
        x.reference ?? '',
        STATUS_BN[x.status] ?? x.status,
      ]);
    } else if (type === 'shareholders') {
      columns = ['শেয়ারহোল্ডার ID', 'নাম', 'মোবাইল', 'KYC', 'অবস্থা', 'শেয়ার', 'মোট পরিশোধ (৳)', 'যোগদান'];
      const r = await q<{ shareholder_seq: number; full_name: string; mobile: string; kyc_status: string; account_status: string; shares: number; paid: number; created_at: Date }>(
        ctx.db,
        `SELECT s.shareholder_seq, s.full_name, s.mobile, s.kyc_status, s.account_status, s.created_at,
                COALESCE(SUM(o.quantity) FILTER (WHERE o.status = 'confirmed'), 0)::bigint AS shares,
                COALESCE(SUM(o.amount_paisa) FILTER (WHERE o.status = 'confirmed'), 0)::bigint AS paid
           FROM shareholders s LEFT JOIN orders o ON o.shareholder_id = s.id
          WHERE s.created_at < (($1::date + 1)::timestamp AT TIME ZONE 'Asia/Dhaka')
          GROUP BY s.id ORDER BY s.shareholder_seq ASC LIMIT 20000`,
        [to],
      );
      rows = r.map((x) => [
        shareholderNo(x.shareholder_seq),
        x.full_name,
        maskMobile(x.mobile),
        KYC_BN[x.kyc_status] ?? x.kyc_status,
        ACCOUNT_BN[x.account_status] ?? x.account_status,
        String(x.shares),
        plainMoney(x.paid),
        dhakaDay(x.created_at),
      ]);
    } else if (type === 'dividends') {
      columns = ['অর্থবছর', 'বিতরণের তারিখ', 'শেয়ারহোল্ডার ID', 'নাম', 'শেয়ার', 'প্রতি শেয়ার (৳)', 'ডিভিডেন্ড (৳)'];
      const r = await q<{ fiscal_year: string; distribution_date: string; shareholder_seq: number; full_name: string; shares: number; per_share_paisa: number; amount_paisa: number }>(
        ctx.db,
        `SELECT d.fiscal_year, d.distribution_date, s.shareholder_seq, s.full_name, p.shares, d.per_share_paisa, p.amount_paisa
           FROM dividend_payouts p JOIN dividends d ON d.id = p.dividend_id JOIN shareholders s ON s.id = p.shareholder_id
          WHERE d.created_at >= ${lower} AND d.created_at < ${upper}
          ORDER BY d.fiscal_year DESC, s.shareholder_seq ASC LIMIT 20000`,
        range,
      );
      rows = r.map((x) => [
        x.fiscal_year,
        x.distribution_date,
        shareholderNo(x.shareholder_seq),
        x.full_name,
        String(x.shares),
        plainMoney(x.per_share_paisa),
        plainMoney(x.amount_paisa),
      ]);
    } else {
      columns = ['শেয়ারহোল্ডার ID', 'নাম', 'KYC অবস্থা', 'জমার তারিখ', 'সিদ্ধান্তের তারিখ', 'মন্তব্য'];
      const r = await q<{ shareholder_seq: number; full_name: string; kyc_status: string; kyc_submitted_at: Date; kyc_decided_at: Date | null; kyc_note: string | null }>(
        ctx.db,
        `SELECT shareholder_seq, full_name, kyc_status, kyc_submitted_at, kyc_decided_at, kyc_note FROM shareholders
          WHERE kyc_submitted_at >= ${lower} AND kyc_submitted_at < ${upper}
          ORDER BY kyc_submitted_at ASC LIMIT 20000`,
        range,
      );
      rows = r.map((x) => [
        shareholderNo(x.shareholder_seq),
        x.full_name,
        KYC_BN[x.kyc_status] ?? x.kyc_status,
        dhakaDay(x.kyc_submitted_at),
        dhakaDay(x.kyc_decided_at),
        x.kyc_note ?? '',
      ]);
    }

    await audit(ctx.db, {
      actorType: 'staff',
      actorId: st.id,
      actorName: st.name,
      category: 'settings',
      action: 'report.generate',
      message: `রিপোর্ট তৈরি: ${TITLES[type]}`,
      tone: 'info',
      ip: req.ip,
      meta: { type, from, to, rows: rows.length },
    });
    return { type, title: TITLES[type], columns, rows, generatedAt: new Date().toISOString() };
  });
}
