import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import type { Ctx } from '../../context.js';
import { q, q1, tx } from '../../db.js';
import { ApiError } from '../../errors.js';
import { audit } from '../../services/audit.js';
import { mustStaff, revokeAllSessions, staffGuard } from '../../services/auth.js';
import { readDecrypted } from '../../services/files.js';
import {
  ADMIN_ORDER_COLS,
  adminOrderJson,
  maskedFromLast3,
  NOMINEE_COLS,
  nomineeJson,
  ORDER_COLS,
  orderJson,
  shareholderNo,
  type AdminOrderRow,
  type NomineeRow,
  type OrderRow,
} from '../../services/format.js';
import { holdingOf, rateOn } from '../../services/holdings.js';
import { createNotice } from '../../services/notices.js';
import { possessive, toAsciiDigits } from '../../util/bn.js';
import { tempPassword } from '../../util/crypto.js';
import { maskEmail, maskMobile } from '../../util/mask.js';
import { hashPassword } from '../../util/passwords.js';
import { dhakaDate } from '../../util/time.js';
import { decodeCursor, encodeCursor, idParam, likePattern, parse, uuidParam, zPageQuery, zReason } from '../../util/validate.js';

const ListQuery = zPageQuery.extend({
  q: z.string().max(100).optional(),
  status: z.enum(['all', 'verified', 'pending', 'suspended']).default('all'),
});
const ReasonSchema = z.object({ reason: zReason }).strict();
const MessageSchema = z
  .object({
    title: z.string().transform((s) => s.trim()).pipe(z.string().min(3, 'শিরোনাম লিখুন').max(120, 'শিরোনাম অনেক বড়')),
    body: z.string().transform((s) => s.trim()).pipe(z.string().min(3, 'বার্তা লিখুন').max(2000, 'বার্তা অনেক বড়')),
  })
  .strict();

const KIND_BN: Record<string, string> = { nid_front: 'NID সামনে', nid_back: 'NID পেছনে', photo: 'ছবি' };

type HolderRow = { id: string; shareholder_seq: number; full_name: string; kyc_status: string; account_status: string };

async function getHolder(ctx: Ctx, id: string): Promise<HolderRow> {
  const h = await q1<HolderRow>(ctx.db, 'SELECT id, shareholder_seq, full_name, kyc_status, account_status FROM shareholders WHERE id = $1', [id]);
  if (!h) throw new ApiError('NOT_FOUND');
  return h;
}

/** Builds WHERE clauses for the shareholder search with only placeholders (no user text in SQL). */
function searchFilter(qtext: string | undefined, status: string, params: unknown[]): string {
  const where: string[] = [];
  if (status === 'verified') where.push(`s.kyc_status = 'verified' AND s.account_status = 'active'`);
  if (status === 'pending') where.push(`s.kyc_status IN ('pending','rejected') AND s.account_status = 'active'`);
  if (status === 'suspended') where.push(`s.account_status = 'suspended'`);
  const text = qtext?.trim();
  if (text) {
    const ascii = toAsciiDigits(text);
    params.push(likePattern(text));
    const pName = `$${params.length}`;
    const conds = [`s.full_name ILIKE ${pName} ESCAPE '\\'`];
    const idm = /^(?:RA-SH-)?0*(\d{1,9})$/i.exec(ascii);
    if (idm) {
      params.push(Number(idm[1]));
      conds.push(`s.shareholder_seq = $${params.length}`);
    }
    const digits = ascii.replace(/\D/g, '');
    if (digits.length >= 4 && digits.length === ascii.replace(/[\s+-]/g, '').length) {
      params.push(likePattern(digits));
      conds.push(`s.mobile LIKE $${params.length} ESCAPE '\\'`);
    }
    if (text.includes('@')) {
      params.push(likePattern(text.toLowerCase()));
      conds.push(`s.email LIKE $${params.length} ESCAPE '\\'`);
    }
    where.push(`(${conds.join(' OR ')})`);
  }
  return where.length ? `WHERE ${where.join(' AND ')}` : '';
}

export async function adminShareholderRoutes(app: FastifyInstance, ctx: Ctx) {
  const view = staffGuard(ctx, 'view');
  const settingsStaff = staffGuard(ctx, 'settings_staff');
  const kycApprove = staffGuard(ctx, 'kyc_approve');
  const noticesRules = staffGuard(ctx, 'notices_rules');

  app.get('/admin/shareholders', { preHandler: view }, async (req) => {
    const query = parse(ListQuery, req.query);
    const cur = decodeCursor(query.cursor);
    const params: unknown[] = [];
    const where = searchFilter(query.q, query.status, params);
    const total = await q1<{ n: number }>(ctx.db, `SELECT count(*)::int AS n FROM shareholders s ${where}`, params);
    const pageParams = [...params, cur?.t ?? null, cur?.id ?? '00000000-0000-0000-0000-000000000000', query.limit + 1];
    const n = params.length;
    const cursorCond = `($${n + 1}::timestamptz IS NULL OR (s.created_at, s.id) < ($${n + 1}::timestamptz, $${n + 2}::uuid))`;
    const rows = await q<HolderRow & { created_at: Date; shares: number }>(
      ctx.db,
      `SELECT s.id, s.shareholder_seq, s.full_name, s.kyc_status, s.account_status, s.created_at,
              COALESCE((SELECT SUM(o.quantity) FROM orders o WHERE o.shareholder_id = s.id AND o.status = 'confirmed'), 0)::bigint AS shares
         FROM shareholders s
         ${where ? `${where} AND ${cursorCond}` : `WHERE ${cursorCond}`}
        ORDER BY s.created_at DESC, s.id DESC LIMIT $${n + 3}`,
      pageParams,
    );
    const more = rows.length > query.limit;
    const items = rows.slice(0, query.limit);
    const last = items[items.length - 1];
    return {
      items: items.map((r) => ({
        id: r.id,
        shareholderNo: shareholderNo(r.shareholder_seq),
        fullName: r.full_name,
        shares: r.shares,
        kycStatus: r.kyc_status,
        accountStatus: r.account_status,
      })),
      total: total?.n ?? 0,
      nextCursor: more && last ? encodeCursor(last.created_at, last.id) : null,
    };
  });

  app.get('/admin/shareholders/:id', { preHandler: view }, async (req) => {
    const id = idParam(req.params);
    const s = await q1<{
      id: string;
      shareholder_seq: number;
      full_name: string;
      mobile: string;
      email: string;
      kyc_status: string;
      kyc_note: string | null;
      account_status: string;
      created_at: Date;
      dob: string;
      nid_last3: string;
      nid_len: number;
    }>(
      ctx.db,
      `SELECT id, shareholder_seq, full_name, mobile, email, kyc_status, kyc_note, account_status, created_at, dob, nid_last3, nid_len
         FROM shareholders WHERE id = $1`,
      [id],
    );
    if (!s) throw new ApiError('NOT_FOUND');
    const h = await holdingOf(ctx.db, id);
    const rate = (await rateOn(ctx.db, dhakaDate())) ?? 0;
    const noms = await q<NomineeRow>(ctx.db, `SELECT ${NOMINEE_COLS} FROM nominees WHERE shareholder_id = $1 ORDER BY slot`, [id]);
    const orders = await q<OrderRow>(
      ctx.db,
      `SELECT ${ORDER_COLS} FROM orders o WHERE o.shareholder_id = $1 ORDER BY o.created_at DESC, o.id DESC LIMIT 50`,
      [id],
    );
    const files = await q<{ id: string; kind: string }>(
      ctx.db,
      `SELECT id, kind FROM kyc_files WHERE shareholder_id = $1 AND active ORDER BY array_position(ARRAY['nid_front','nid_back','photo'], kind)`,
      [id],
    );
    return {
      id: s.id,
      shareholderNo: shareholderNo(s.shareholder_seq),
      fullName: s.full_name,
      mobileMasked: maskMobile(s.mobile),
      emailMasked: maskEmail(s.email),
      kycStatus: s.kyc_status,
      kycNote: s.kyc_note,
      accountStatus: s.account_status,
      joinedAt: s.created_at.toISOString(),
      dob: s.dob,
      nidMasked: maskedFromLast3(s.nid_last3, s.nid_len),
      shares: h.shares,
      paidPaisa: h.paidPaisa,
      valuePaisa: h.shares * rate,
      nominees: noms.map(nomineeJson),
      orders: orders.map(orderJson),
      kycFiles: files,
    };
  });

  app.get('/admin/shareholders/:id/contact', { preHandler: view, config: { rateLimit: { max: 30, timeWindow: '1 minute' } } }, async (req) => {
    const st = mustStaff(req);
    const id = idParam(req.params);
    const s = await q1<{ full_name: string; mobile: string; email: string }>(ctx.db, 'SELECT full_name, mobile, email FROM shareholders WHERE id = $1', [id]);
    if (!s) throw new ApiError('NOT_FOUND');
    await audit(ctx, ctx.db, {
      actorType: 'staff',
      actorId: st.id,
      actorName: st.name,
      category: 'shareholder',
      action: 'shareholder.contact.view',
      message: `${possessive(s.full_name)} যোগাযোগ তথ্য দেখা হয়েছে`,
      tone: 'info',
      ip: req.ip,
      meta: { shareholderId: id },
    });
    return { mobile: s.mobile, email: s.email };
  });

  app.post('/admin/shareholders/:id/suspend', { preHandler: settingsStaff }, async (req, reply) => {
    const st = mustStaff(req);
    const id = idParam(req.params);
    const body = parse(ReasonSchema, req.body);
    const h = await getHolder(ctx, id);
    if (h.account_status === 'suspended') throw new ApiError('CONFLICT', 'অ্যাকাউন্টটি আগেই স্থগিত আছে।');
    await tx(ctx.db, async (c) => {
      await c.query(`UPDATE shareholders SET account_status = 'suspended', suspend_reason = $2, updated_at = now() WHERE id = $1`, [id, body.reason]);
      await revokeAllSessions(c, 'user', id, 'suspended');
      await audit(ctx, c, {
        actorType: 'staff',
        actorId: st.id,
        actorName: st.name,
        category: 'shareholder',
        action: 'shareholder.suspend',
        message: `${possessive(h.full_name)} অ্যাকাউন্ট স্থগিত`,
        tone: 'bad',
        ip: req.ip,
        meta: { shareholderId: id, reason: body.reason },
      });
    });
    return reply.status(204).send();
  });

  app.post('/admin/shareholders/:id/activate', { preHandler: settingsStaff }, async (req, reply) => {
    const st = mustStaff(req);
    const id = idParam(req.params);
    const h = await getHolder(ctx, id);
    if (h.account_status === 'active') throw new ApiError('CONFLICT', 'অ্যাকাউন্টটি আগেই সক্রিয় আছে।');
    await tx(ctx.db, async (c) => {
      await c.query(`UPDATE shareholders SET account_status = 'active', suspend_reason = NULL, updated_at = now() WHERE id = $1`, [id]);
      await audit(ctx, c, {
        actorType: 'staff',
        actorId: st.id,
        actorName: st.name,
        category: 'shareholder',
        action: 'shareholder.activate',
        message: `${possessive(h.full_name)} অ্যাকাউন্ট আবার সক্রিয়`,
        tone: 'ok',
        ip: req.ip,
        meta: { shareholderId: id },
      });
    });
    return reply.status(204).send();
  });

  app.post('/admin/shareholders/:id/reset-password', { preHandler: settingsStaff }, async (req) => {
    const st = mustStaff(req);
    const id = idParam(req.params);
    const h = await getHolder(ctx, id);
    const temp = tempPassword();
    const hash = await hashPassword(temp);
    await tx(ctx.db, async (c) => {
      await c.query(
        `UPDATE shareholders SET password_hash = $2, must_change_password = true, token_version = token_version + 1,
                failed_logins = 0, lock_count = 0, locked_until = NULL, updated_at = now() WHERE id = $1`,
        [id, hash],
      );
      await revokeAllSessions(c, 'user', id, 'password_reset');
      await audit(ctx, c, {
        actorType: 'staff',
        actorId: st.id,
        actorName: st.name,
        category: 'shareholder',
        action: 'shareholder.password.reset',
        message: `${possessive(h.full_name)} পাসওয়ার্ড রিসেট (অস্থায়ী পাসওয়ার্ড দেওয়া হয়েছে)`,
        tone: 'warn',
        ip: req.ip,
        meta: { shareholderId: id },
      });
    });
    return { tempPassword: temp };
  });

  app.post('/admin/shareholders/:id/message', { preHandler: noticesRules }, async (req, reply) => {
    const st = mustStaff(req);
    const id = idParam(req.params);
    const body = parse(MessageSchema, req.body);
    const h = await getHolder(ctx, id);
    await tx(ctx.db, async (c) => {
      await createNotice(c, { title: body.title, body: body.body, category: 'message', audience: 'user', userIds: [id], createdBy: st.id });
      await audit(ctx, c, {
        actorType: 'staff',
        actorId: st.id,
        actorName: st.name,
        category: 'content',
        action: 'notice.direct',
        message: `${h.full_name}কে বার্তা পাঠানো হয়েছে: “${body.title}”`,
        tone: 'info',
        ip: req.ip,
        meta: { shareholderId: id },
      });
    });
    return reply.status(204).send();
  });

  app.get('/admin/kyc-files/:fileId', { preHandler: kycApprove, config: { rateLimit: { max: 60, timeWindow: '1 minute' } } }, async (req, reply) => {
    const st = mustStaff(req);
    const fileId = uuidParam(req.params, 'fileId');
    const f = await q1<{ storage_name: string; kind: string; full_name: string; shareholder_id: string }>(
      ctx.db,
      `SELECT k.storage_name, k.kind, s.full_name, k.shareholder_id FROM kyc_files k JOIN shareholders s ON s.id = k.shareholder_id WHERE k.id = $1`,
      [fileId],
    );
    if (!f) throw new ApiError('NOT_FOUND');
    let data: Buffer;
    try {
      data = await readDecrypted(ctx, f.storage_name);
    } catch (err) {
      req.log.error({ err, fileId }, 'kyc file unreadable');
      throw new ApiError('NOT_FOUND');
    }
    await audit(ctx, ctx.db, {
      actorType: 'staff',
      actorId: st.id,
      actorName: st.name,
      category: 'shareholder',
      action: 'kyc.file.view',
      message: `${possessive(f.full_name)} KYC নথি দেখা হয়েছে (${KIND_BN[f.kind] ?? f.kind})`,
      tone: 'info',
      ip: req.ip,
      meta: { fileId, shareholderId: f.shareholder_id },
    });
    return reply
      .header('content-type', 'image/jpeg')
      .header('content-disposition', 'inline; filename="kyc.jpg"')
      .header('x-content-type-options', 'nosniff')
      .send(data);
  });

  app.get('/admin/approvals', { preHandler: view }, async () => {
    const payments = await q<AdminOrderRow>(
      ctx.db,
      `SELECT ${ADMIN_ORDER_COLS} FROM orders o JOIN shareholders s ON s.id = o.shareholder_id
        WHERE o.status = 'pending' ORDER BY o.created_at ASC, o.id ASC LIMIT 200`,
    );
    const kyc = await q<{ id: string; shareholder_seq: number; full_name: string; kyc_submitted_at: Date; nid_last3: string; nid_len: number; dob: string }>(
      ctx.db,
      `SELECT id, shareholder_seq, full_name, kyc_submitted_at, nid_last3, nid_len, dob
         FROM shareholders WHERE kyc_status = 'pending' ORDER BY kyc_submitted_at ASC LIMIT 200`,
    );
    const files = kyc.length
      ? await q<{ id: string; kind: string; shareholder_id: string }>(
          ctx.db,
          `SELECT id, kind, shareholder_id FROM kyc_files WHERE active AND shareholder_id = ANY($1::uuid[])
            ORDER BY array_position(ARRAY['nid_front','nid_back','photo'], kind)`,
          [kyc.map((k) => k.id)],
        )
      : [];
    return {
      payments: payments.map(adminOrderJson),
      kyc: kyc.map((k) => ({
        shareholderId: k.id,
        shareholderNo: shareholderNo(k.shareholder_seq),
        fullName: k.full_name,
        submittedAt: k.kyc_submitted_at.toISOString(),
        nidMasked: maskedFromLast3(k.nid_last3, k.nid_len),
        dob: k.dob,
        files: files.filter((f) => f.shareholder_id === k.id).map((f) => ({ id: f.id, kind: f.kind })),
      })),
    };
  });

  const decideKyc = (approve: boolean) => async (req: import('fastify').FastifyRequest, reply: import('fastify').FastifyReply) => {
    const st = mustStaff(req);
    const id = uuidParam(req.params, 'shareholderId');
    const reason = approve ? null : parse(ReasonSchema, req.body).reason;
    await tx(ctx.db, async (c) => {
      const h = await q1<{ full_name: string; kyc_status: string }>(c, 'SELECT full_name, kyc_status FROM shareholders WHERE id = $1 FOR UPDATE', [id]);
      if (!h) throw new ApiError('NOT_FOUND');
      if (h.kyc_status !== 'pending') throw new ApiError('CONFLICT', 'এই KYC আগেই নিষ্পত্তি হয়েছে।');
      await c.query(
        `UPDATE shareholders SET kyc_status = $2, kyc_note = $3, kyc_decided_at = now(), kyc_decided_by = $4, updated_at = now() WHERE id = $1`,
        [id, approve ? 'verified' : 'rejected', reason, st.id],
      );
      await createNotice(c, {
        title: approve ? 'আপনার KYC যাচাই সম্পন্ন হয়েছে' : 'আপনার KYC যাচাই বাতিল হয়েছে',
        body: approve
          ? 'আপনার পরিচয় যাচাই সম্পন্ন। এখন অ্যাপ থেকে শেয়ার কিনতে পারবেন।'
          : `কারণ: ${reason}। সঠিক নথি দিয়ে প্রোফাইল থেকে আবার জমা দিন।`,
        category: 'kyc',
        audience: 'user',
        userIds: [id],
        createdBy: st.id,
      });
      await audit(ctx, c, {
        actorType: 'staff',
        actorId: st.id,
        actorName: st.name,
        category: 'approval',
        action: approve ? 'kyc.approve' : 'kyc.reject',
        message: approve ? `${possessive(h.full_name)} KYC যাচাই সম্পন্ন` : `${possessive(h.full_name)} KYC বাতিল`,
        tone: approve ? 'ok' : 'bad',
        ip: req.ip,
        meta: { shareholderId: id, ...(reason ? { reason } : {}) },
      });
    });
    return reply.status(204).send();
  };

  app.post('/admin/kyc/:shareholderId/approve', { preHandler: kycApprove }, decideKyc(true));
  app.post('/admin/kyc/:shareholderId/reject', { preHandler: kycApprove }, decideKyc(false));

}
