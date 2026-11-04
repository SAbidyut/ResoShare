import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import type { Ctx } from '../../context.js';
import { q, q1, tx } from '../../db.js';
import { ApiError } from '../../errors.js';
import { audit } from '../../services/audit.js';
import { mustStaff, staffGuard } from '../../services/auth.js';
import { createNotice } from '../../services/notices.js';
import { bnCount, bnDate, bnDigits } from '../../util/bn.js';
import { decodeCursor, encodeCursor, parse, zDate, zPageQuery, zUuid } from '../../util/validate.js';

const trimmed = (min: number, max: number, minMsg: string, maxMsg: string) =>
  z
    .string()
    .transform((s) => s.trim())
    .pipe(z.string().min(min, minMsg).max(max, maxMsg));

const NoticeSchema = z
  .object({
    title: trimmed(3, 120, 'শিরোনাম লিখুন', 'শিরোনাম অনেক বড়'),
    body: trimmed(3, 2000, 'বার্তা লিখুন', 'বার্তা অনেক বড়'),
    audience: z.enum(['all', 'selected']),
    userIds: z.array(zUuid).max(500).default([]),
  })
  .strict()
  .superRefine((v, c) => {
    if (v.audience === 'selected' && v.userIds.length === 0) c.addIssue({ code: 'custom', path: ['userIds'], message: 'কমপক্ষে একজন প্রাপক বাছাই করুন' });
  });

const zSections = z
  .array(
    z
      .object({
        title: trimmed(1, 100, 'ধারার নাম লিখুন', 'ধারার নাম অনেক বড়'),
        items: z.array(trimmed(1, 500, 'নিয়ম লিখুন', 'নিয়মটি অনেক বড়')).min(1, 'কমপক্ষে একটি নিয়ম লিখুন').max(50, 'সর্বোচ্চ ৫০টি নিয়ম'),
      })
      .strict(),
  )
  .min(1, 'কমপক্ষে একটি ধারা দিন')
  .max(30, 'সর্বোচ্চ ৩০টি ধারা');

const DraftSchema = z.object({ sections: zSections }).strict();
const PublishSchema = z
  .object({
    version: z.string().regex(/^\d{1,3}(\.\d{1,3}){0,2}$/, 'সংস্করণ নম্বর লিখুন (যেমন ১.১)'),
    effectiveDate: zDate,
    summary: z.string().transform((s) => s.trim()).pipe(z.string().max(300, 'সংক্ষেপ অনেক বড়')),
    sections: zSections,
    notify: z.boolean(),
  })
  .strict();

/** Numeric comparison of dotted versions: compareVersions('1.10','1.9') > 0. */
export function compareVersions(a: string, b: string): number {
  const pa = a.split('.').map(Number);
  const pb = b.split('.').map(Number);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (d !== 0) return d;
  }
  return 0;
}

type RulesRow = {
  id: string;
  version: string | null;
  effective_date: string | null;
  summary: string;
  status: string;
  sections: unknown;
  updated_at: Date;
  by_name: string | null;
};

const rulesJson = (r: RulesRow, withSections = true) => ({
  id: r.id,
  version: r.version,
  effectiveDate: r.effective_date,
  summary: r.summary,
  status: r.status,
  sections: withSections ? r.sections : [],
  updatedAt: r.updated_at.toISOString(),
  byName: r.by_name,
});

async function rulesAdmin(ctx: Ctx) {
  const rows = await q<RulesRow>(
    ctx.db,
    `SELECT r.id, r.version, r.effective_date, r.summary, r.status, r.sections, r.updated_at, st.name AS by_name
       FROM rules_versions r LEFT JOIN staff st ON st.id = r.created_by
      ORDER BY COALESCE(r.published_at, r.updated_at) DESC LIMIT 50`,
  );
  const current = rows.find((r) => r.status === 'published');
  const draft = rows.find((r) => r.status === 'draft');
  return {
    current: current ? rulesJson(current) : null,
    draft: draft ? rulesJson(draft) : null,
    history: rows.map((r) => rulesJson(r, false)),
  };
}

export async function adminContentRoutes(app: FastifyInstance, ctx: Ctx) {
  const view = staffGuard(ctx, 'view');
  const editor = staffGuard(ctx, 'notices_rules');

  app.get('/admin/notices', { preHandler: view }, async (req) => {
    const page = parse(zPageQuery, req.query);
    const cur = decodeCursor(page.cursor);
    const rows = await q<{
      id: string;
      title: string;
      body: string;
      category: string;
      audience: string;
      recipient_count: number;
      created_at: Date;
      by_name: string | null;
    }>(
      ctx.db,
      `SELECT n.id, n.title, n.body, n.category, n.audience, n.recipient_count, n.created_at, st.name AS by_name
         FROM notices n LEFT JOIN staff st ON st.id = n.created_by
        WHERE (n.audience IN ('all','selected') OR n.category = 'message')
          AND ($1::timestamptz IS NULL OR (n.created_at, n.id) < ($1::timestamptz, $2::uuid))
        ORDER BY n.created_at DESC, n.id DESC LIMIT $3`,
      [cur?.t ?? null, cur?.id ?? '00000000-0000-0000-0000-000000000000', page.limit + 1],
    );
    const more = rows.length > page.limit;
    const items = rows.slice(0, page.limit);
    const last = items[items.length - 1];
    return {
      items: items.map((r) => ({
        id: r.id,
        title: r.title,
        body: r.body,
        category: r.category,
        audience: r.audience,
        recipientCount: r.recipient_count,
        createdAt: r.created_at.toISOString(),
        byName: r.by_name,
      })),
      nextCursor: more && last ? encodeCursor(last.created_at, last.id) : null,
    };
  });

  app.post('/admin/notices', { preHandler: editor, config: { rateLimit: { max: 20, timeWindow: '1 minute' } } }, async (req, reply) => {
    const st = mustStaff(req);
    const body = parse(NoticeSchema, req.body);
    const out = await tx(ctx.db, async (c) => {
      const n = await createNotice(c, {
        title: body.title,
        body: body.body,
        category: 'company',
        audience: body.audience,
        userIds: body.audience === 'selected' ? body.userIds : [],
        createdBy: st.id,
      });
      if (body.audience === 'selected' && n.recipientCount === 0) throw new ApiError('INVALID_INPUT', undefined, { userIds: 'প্রাপক পাওয়া যায়নি' });
      await audit(c, {
        actorType: 'staff',
        actorId: st.id,
        actorName: st.name,
        category: 'content',
        action: 'notice.send',
        message: `নোটিশ পাঠানো হয়েছে: “${body.title}” (${bnCount(n.recipientCount)} জন)`,
        tone: 'info',
        ip: req.ip,
        meta: { noticeId: n.id },
      });
      return n;
    });
    return reply.status(201).send({
      id: out.id,
      title: body.title,
      body: body.body,
      category: 'company',
      audience: body.audience,
      recipientCount: out.recipientCount,
      createdAt: out.createdAt.toISOString(),
      byName: st.name,
    });
  });

  app.get('/admin/rules', { preHandler: view }, async () => rulesAdmin(ctx));

  app.put('/admin/rules/draft', { preHandler: editor }, async (req) => {
    const st = mustStaff(req);
    const body = parse(DraftSchema, req.body);
    await tx(ctx.db, async (c) => {
      const d = await q1<{ id: string }>(c, `SELECT id FROM rules_versions WHERE status = 'draft' FOR UPDATE`);
      if (d) {
        await c.query('UPDATE rules_versions SET sections = $2, created_by = $3, updated_at = now() WHERE id = $1', [d.id, JSON.stringify(body.sections), st.id]);
      } else {
        await c.query(`INSERT INTO rules_versions (status, sections, created_by) VALUES ('draft', $1, $2)`, [JSON.stringify(body.sections), st.id]);
      }
      await audit(c, {
        actorType: 'staff',
        actorId: st.id,
        actorName: st.name,
        category: 'content',
        action: 'rules.draft',
        message: `নিয়ম ও রেগুলেশনের খসড়া সংরক্ষণ (${bnCount(body.sections.length)}টি ধারা)`,
        tone: 'info',
        ip: req.ip,
      });
    });
    return rulesAdmin(ctx);
  });

  app.post('/admin/rules/publish', { preHandler: editor }, async (req) => {
    const st = mustStaff(req);
    const body = parse(PublishSchema, req.body);
    await tx(ctx.db, async (c) => {
      const cur = await q1<{ id: string; version: string }>(c, `SELECT id, version FROM rules_versions WHERE status = 'published' FOR UPDATE`);
      if (cur && compareVersions(body.version, cur.version) <= 0) {
        throw new ApiError('CONFLICT', `নতুন সংস্করণ নম্বর বর্তমান সংস্করণ (${bnDigits(cur.version)}) থেকে বড় হতে হবে।`, {
          version: 'সংস্করণ নম্বর বর্তমানটির চেয়ে বড় হতে হবে',
        });
      }
      const dup = await q1(c, `SELECT 1 FROM rules_versions WHERE version = $1 AND status <> 'draft'`, [body.version]);
      if (dup) throw new ApiError('CONFLICT', 'এই সংস্করণ নম্বর আগে ব্যবহার হয়েছে।', { version: 'এই সংস্করণ নম্বর আগে ব্যবহার হয়েছে' });
      if (cur) await c.query(`UPDATE rules_versions SET status = 'archived' WHERE id = $1`, [cur.id]);
      await c.query(`DELETE FROM rules_versions WHERE status = 'draft'`);
      await c.query(
        `INSERT INTO rules_versions (version, effective_date, summary, status, sections, created_by, published_at)
         VALUES ($1,$2,$3,'published',$4,$5,now())`,
        [body.version, body.effectiveDate, body.summary, JSON.stringify(body.sections), st.id],
      );
      if (body.notify) {
        await createNotice(c, {
          title: `নিয়ম ও রেগুলেশন হালনাগাদ (সংস্করণ ${bnDigits(body.version)})`,
          body: `${body.summary ? `${body.summary}। ` : ''}কার্যকর: ${bnDate(body.effectiveDate)}। প্রোফাইল বা হোম থেকে “নিয়ম ও রেগুলেশন” দেখুন।`,
          category: 'rules',
          audience: 'all',
          createdBy: st.id,
        });
      }
      await audit(c, {
        actorType: 'staff',
        actorId: st.id,
        actorName: st.name,
        category: 'content',
        action: 'rules.publish',
        message: `নিয়ম ও রেগুলেশন প্রকাশ: সংস্করণ ${bnDigits(body.version)}`,
        tone: 'warn',
        ip: req.ip,
      });
    });
    return rulesAdmin(ctx);
  });
}
