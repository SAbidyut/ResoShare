import type { Client } from '../db.js';
import { q } from '../db.js';

export type CompanySettings = { name: string; email: string; hotline: string };
export type PaymentSettings = {
  cashEnabled: boolean;
  bankEnabled: boolean;
  officeAddress: string;
  bankName: string;
  accountName: string;
  accountNumber: string;
};
export type SecuritySettings = { autoLogoutMinutes: number };
export type AllSettings = { company: CompanySettings; payment: PaymentSettings; security: SecuritySettings };

const DEFAULTS: AllSettings = {
  company: { name: 'Resonance Automation', email: '', hotline: '' },
  payment: { cashEnabled: true, bankEnabled: true, officeAddress: '', bankName: '', accountName: '', accountNumber: '' },
  security: { autoLogoutMinutes: 10 },
};

export async function getSettings(client: Client): Promise<AllSettings> {
  const rows = await q<{ key: string; value: Record<string, unknown> }>(client, 'SELECT key, value FROM settings');
  const map = new Map(rows.map((r) => [r.key, r.value]));
  return {
    company: { ...DEFAULTS.company, ...(map.get('company') as Partial<CompanySettings> | undefined) },
    payment: { ...DEFAULTS.payment, ...(map.get('payment') as Partial<PaymentSettings> | undefined) },
    security: { ...DEFAULTS.security, ...(map.get('security') as Partial<SecuritySettings> | undefined) },
  };
}

export function settingsJson(s: AllSettings) {
  return {
    company: s.company,
    payment: s.payment,
    security: { autoLogoutMinutes: s.security.autoLogoutMinutes, mfaRequired: true as const },
  };
}
