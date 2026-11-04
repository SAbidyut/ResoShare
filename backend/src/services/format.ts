// Row → API JSON serializers and human number formats.

export const shareholderNo = (seq: number) => `RA-SH-${String(seq).padStart(5, '0')}`;
export const certificateNo = (seq: number) => `RA-CERT-${String(seq).padStart(5, '0')}`;
export const orderNo = (seq: number) => `ORD-${String(seq).padStart(6, '0')}`;
export const cashRef = (seq: number) => `CASH-${String(seq).padStart(4, '0')}`;

export const iso = (d: Date | null | undefined): string | null => (d ? d.toISOString() : null);

export type OrderRow = {
  id: string;
  order_seq: number;
  quantity: number;
  rate_paisa: number;
  amount_paisa: number;
  method: 'cash' | 'bank';
  reference: string | null;
  status: 'pending' | 'confirmed' | 'cancelled';
  note: string | null;
  created_at: Date;
  decided_at: Date | null;
};

export type AdminOrderRow = OrderRow & { shareholder_id: string; shareholder_seq: number; full_name: string };

export function orderJson(r: OrderRow) {
  return {
    id: r.id,
    orderNo: orderNo(r.order_seq),
    quantity: r.quantity,
    ratePaisa: r.rate_paisa,
    amountPaisa: r.amount_paisa,
    method: r.method,
    reference: r.reference,
    status: r.status,
    note: r.note,
    createdAt: r.created_at.toISOString(),
    decidedAt: iso(r.decided_at),
  };
}

export function adminOrderJson(r: AdminOrderRow) {
  return {
    ...orderJson(r),
    shareholderId: r.shareholder_id,
    shareholderNo: shareholderNo(r.shareholder_seq),
    shareholderName: r.full_name,
  };
}

export const ORDER_COLS = `o.id, o.order_seq, o.quantity, o.rate_paisa, o.amount_paisa, o.method, o.reference, o.status, o.note, o.created_at, o.decided_at`;
export const ADMIN_ORDER_COLS = `${ORDER_COLS}, o.shareholder_id, s.shareholder_seq, s.full_name`;

export type NomineeRow = {
  slot: number;
  name: string;
  relation: string;
  id_type: 'nid' | 'birth_reg';
  id_last3: string;
  id_len: number;
  share_percent: number;
};

export function maskedFromLast3(last3: string, len: number): string {
  return `${'•'.repeat(Math.max(0, len - 3))}${last3}`;
}

export function nomineeJson(r: NomineeRow) {
  return {
    slot: r.slot,
    name: r.name,
    relation: r.relation,
    idType: r.id_type,
    idNumberMasked: maskedFromLast3(r.id_last3, r.id_len),
    sharePercent: r.share_percent,
  };
}

export const NOMINEE_COLS = `slot, name, relation, id_type, id_last3, id_len, share_percent`;

export type ShareholderRow = {
  id: string;
  shareholder_seq: number;
  full_name: string;
  mobile: string;
  email: string;
  kyc_status: 'pending' | 'verified' | 'rejected';
  kyc_note: string | null;
  account_status: 'active' | 'suspended';
  must_change_password: boolean;
  created_at: Date;
};

export const ME_COLS = `id, shareholder_seq, full_name, mobile, email, kyc_status, kyc_note, account_status, must_change_password, created_at`;

export function meJson(r: ShareholderRow, nominees: NomineeRow[]) {
  return {
    id: r.id,
    shareholderNo: shareholderNo(r.shareholder_seq),
    fullName: r.full_name,
    mobile: r.mobile,
    email: r.email,
    kycStatus: r.kyc_status,
    kycNote: r.kyc_note,
    accountStatus: r.account_status,
    mustChangePassword: r.must_change_password,
    joinedAt: r.created_at.toISOString(),
    nominees: nominees.map(nomineeJson),
  };
}
