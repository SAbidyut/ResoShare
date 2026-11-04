import { createHash } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import type { Ctx } from '../context.js';
import { q1 } from '../db.js';
import { ApiError } from '../errors.js';
import { getSettings } from '../services/settings.js';
import { holdingOf, rateOn } from '../services/holdings.js';
import { addDays, dhakaDate } from '../util/time.js';
import { certificateNo, shareholderNo } from '../services/format.js';
import { bnCount, bnDate } from '../util/bn.js';
import { safeEqual } from '../util/crypto.js';

export async function publicRoutes(app: FastifyInstance, ctx: Ctx) {
  app.get('/health', { config: { rateLimit: { max: 60, timeWindow: '1 minute' } } }, async () => {
    await ctx.db.query('SELECT 1');
    return { ok: true };
  });

  app.get('/public/info', { config: { rateLimit: { max: 60, timeWindow: '1 minute' } } }, async () => {
    const s = await getSettings(ctx.db);
    const today = dhakaDate();
    const rate = (await rateOn(ctx.db, today)) ?? 0;
    const yesterday = await rateOn(ctx.db, addDays(today, -1));
    return {
      companyName: s.company.name,
      hotline: s.company.hotline,
      email: s.company.email,
      officeAddress: s.payment.officeAddress,
      todayRatePaisa: rate,
      dayChangePaisa: yesterday === null ? 0 : rate - yesterday,
    };
  });

  // Public on purpose: the registration screen links to the rules before an account exists.
  app.get('/rules', { config: { rateLimit: { max: 60, timeWindow: '1 minute' } } }, async () => {
    const r = await q1<{ version: string; effective_date: string; sections: unknown }>(
      ctx.db,
      `SELECT version, effective_date, sections FROM rules_versions WHERE status = 'published'`,
    );
    if (!r) throw new ApiError('NOT_FOUND', 'নিয়ম এখনো প্রকাশ করা হয়নি।');
    return { version: r.version, effectiveDate: r.effective_date, sections: r.sections };
  });
}

const escapeHtml = (s: string) =>
  s.replace(/[&<>"'`=/]/g, (c) => `&#${c.charCodeAt(0)};`);

const STYLE = `body{margin:0;font-family:'Kalpurush','Hind Siliguri','Noto Sans Bengali',system-ui,sans-serif;background:#F3F5F9;color:#0F172A}
.top{background:#0B1D3A;color:#fff;padding:28px 20px 56px}.brand{display:flex;align-items:center;gap:12px;font-weight:700;font-size:18px}
.card{max-width:440px;margin:-36px auto 24px;background:#fff;border:1px solid #E1E6EF;border-radius:18px;padding:22px}
.ok{color:#0F7B4F}.bad{color:#B42318}h1{margin:0 0 6px;font-size:22px}.row{display:flex;justify-content:space-between;gap:12px;padding:10px 0;border-top:1px solid #E1E6EF;font-size:15px}
.muted{color:#5B6578;font-size:14px}`;
const STYLE_HASH = createHash('sha256').update(STYLE).digest('base64');
const CSP = `default-src 'none'; style-src 'sha256-${STYLE_HASH}'; img-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'`;

const LOGO = `<svg width="40" height="40" viewBox="0 0 40 40" aria-hidden="true"><rect width="40" height="40" rx="11" fill="#F5A524"/><path d="M6 21 C9 8 14 8 17 21 S24 34 27 21 S31 12 34 19" fill="none" stroke="#0B1D3A" stroke-width="3" stroke-linecap="round"/></svg>`;

function page(company: string, inner: string): string {
  return `<!doctype html><html lang="bn"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex,nofollow"><title>সার্টিফিকেট যাচাই</title><style>${STYLE}</style></head><body><div class="top"><div class="brand">${LOGO}<span>${escapeHtml(company)}</span></div></div><div class="card">${inner}</div></body></html>`;
}

export async function verifyPageRoute(app: FastifyInstance, ctx: Ctx) {
  app.get(
    '/verify/:certNo',
    { config: { rateLimit: { max: 30, timeWindow: '1 minute' } } },
    async (req, reply) => {
      const { certNo } = req.params as { certNo?: string };
      const sig = (req.query as { s?: unknown }).s;
      const settings = await getSettings(ctx.db);
      reply.header('content-security-policy', CSP).type('text/html; charset=utf-8');

      const invalid = () =>
        page(
          settings.company.name,
          `<h1 class="bad">✗ যাচাই করা যায়নি</h1><p class="muted">এই সার্টিফিকেটটি বৈধ নয় অথবা এর পরে শেয়ারের পরিমাণ বদলেছে। সর্বশেষ সার্টিফিকেটের QR স্ক্যান করুন অথবা অফিসে যোগাযোগ করুন${
            settings.company.hotline ? ` (হটলাইন: ${escapeHtml(settings.company.hotline)})` : ''
          }।</p>`,
        );

      const m = typeof certNo === 'string' ? /^RA-CERT-(\d{5,9})$/.exec(certNo) : null;
      if (!m || typeof sig !== 'string' || !/^[a-f0-9]{20}$/.test(sig)) return reply.send(invalid());
      const seq = Number(m[1]);
      const holder = await q1<{ id: string; full_name: string; shareholder_seq: number }>(
        ctx.db,
        'SELECT id, full_name, shareholder_seq FROM shareholders WHERE shareholder_seq = $1',
        [seq],
      );
      if (!holder) return reply.send(invalid());
      const h = await holdingOf(ctx.db, holder.id);
      if (h.shares <= 0 || !h.issuedOn) return reply.send(invalid());
      const expected = ctx.crypto.certSignature(certificateNo(seq), h.shares, h.issuedOn);
      if (!safeEqual(expected, sig)) return reply.send(invalid());
      return reply.send(
        page(
          settings.company.name,
          `<h1 class="ok">✓ বৈধ শেয়ার সার্টিফিকেট</h1><p class="muted">এই সার্টিফিকেটটি কোম্পানির রেকর্ডের সাথে মিলেছে।</p>
<div class="row"><span class="muted">নাম</span><strong>${escapeHtml(holder.full_name)}</strong></div>
<div class="row"><span class="muted">শেয়ারহোল্ডার ID</span><strong>${escapeHtml(shareholderNo(seq))}</strong></div>
<div class="row"><span class="muted">সার্টিফিকেট নম্বর</span><strong>${escapeHtml(certificateNo(seq))}</strong></div>
<div class="row"><span class="muted">শেয়ার সংখ্যা</span><strong>${escapeHtml(bnCount(h.shares))}টি</strong></div>
<div class="row"><span class="muted">ইস্যুর তারিখ</span><strong>${escapeHtml(bnDate(h.issuedOn))}</strong></div>`,
        ),
      );
    },
  );
}
