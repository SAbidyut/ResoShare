import type { FastifyBaseLogger } from 'fastify';
import type { Config } from './config.js';
import type { Db } from './db.js';
import type { Crypto } from './util/crypto.js';

export type Ctx = {
  config: Config;
  db: Db;
  crypto: Crypto;
  log: FastifyBaseLogger;
};

export type Permission =
  | 'view'
  | 'kyc_approve'
  | 'payment_approve'
  | 'rate_change'
  | 'dividend'
  | 'notices_rules'
  | 'reports'
  | 'settings_staff';

export const ALL_PERMISSIONS: Permission[] = [
  'view',
  'kyc_approve',
  'payment_approve',
  'rate_change',
  'dividend',
  'notices_rules',
  'reports',
  'settings_staff',
];

export type UserAuth = { id: string; sid: string; name: string };
export type StaffAuth = { id: string; sid: string; name: string; roleKey: string; permissions: Permission[] };

declare module 'fastify' {
  interface FastifyRequest {
    userAuth?: UserAuth;
    staffAuth?: StaffAuth;
  }
}
