import type { Ctx } from '../context.js';
import { q1 } from '../db.js';
import { certificateNo, shareholderNo } from './format.js';
import { holdingOf } from './holdings.js';
import { getSettings } from './settings.js';

export type Certificate = {
  certificateNo: string;
  shareholderNo: string;
  fullName: string;
  shares: number;
  issuedOn: string;
  verifyUrl: string;
  companyName: string;
};

/** The current share certificate of a holder (signed QR link), or null when they hold no confirmed shares. */
export async function buildCertificate(ctx: Ctx, shareholderId: string): Promise<Certificate | null> {
  const holder = await q1<{ shareholder_seq: number; full_name: string }>(
    ctx.db,
    'SELECT shareholder_seq, full_name FROM shareholders WHERE id = $1',
    [shareholderId],
  );
  if (!holder) return null;
  const h = await holdingOf(ctx.db, shareholderId);
  if (h.shares <= 0 || !h.issuedOn) return null;
  const cert = certificateNo(holder.shareholder_seq);
  const sig = ctx.crypto.certSignature(cert, h.shares, h.issuedOn);
  const settings = await getSettings(ctx.db);
  return {
    certificateNo: cert,
    shareholderNo: shareholderNo(holder.shareholder_seq),
    fullName: holder.full_name,
    shares: h.shares,
    issuedOn: h.issuedOn,
    verifyUrl: `${ctx.config.publicBaseUrl}/verify/${cert}?s=${sig}`,
    companyName: settings.company.name,
  };
}
