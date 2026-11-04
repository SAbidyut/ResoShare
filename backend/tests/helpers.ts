import { randomBytes, randomInt } from 'node:crypto';
import type { FastifyInstance, LightMyRequestResponse } from 'fastify';
import sharp from 'sharp';
import { buildApp } from '../src/app.js';
import { loadConfig } from '../src/config.js';
import { createPool, q1, type Db } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { hashPassword } from '../src/util/passwords.js';
import { currentStep, newTotpSecret, totpAt } from '../src/util/totp.js';
import { dhakaDate } from '../src/util/time.js';

export type TestEnv = { app: FastifyInstance; db: Db };

let shared: TestEnv | null = null;

export async function setupApp(): Promise<TestEnv> {
  if (shared) return shared;
  const config = loadConfig();
  const db = createPool(config);
  await migrate(db);
  const app = await buildApp({ config, db, logger: false });
  await app.ready();
  shared = { app, db };
  return shared;
}

export async function teardownApp(): Promise<void> {
  if (!shared) return;
  await shared.app.close();
  await shared.db.end();
  shared = null;
}

let jpegCache: Buffer | null = null;
export async function sampleJpeg(): Promise<Buffer> {
  if (!jpegCache) {
    jpegCache = await sharp({ create: { width: 400, height: 300, channels: 3, background: { r: 30, g: 60, b: 120 } } })
      .jpeg()
      .withMetadata({ exif: { IFD0: { Copyright: 'secret-gps-like-metadata' } } })
      .toBuffer();
  }
  return jpegCache;
}

export function multipart(fields: Record<string, string>, files: Record<string, { buf: Buffer; filename?: string; type?: string }>) {
  const boundary = `----resotest${randomBytes(8).toString('hex')}`;
  const chunks: Buffer[] = [];
  for (const [k, v] of Object.entries(fields)) {
    chunks.push(Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="${k}"\r\n\r\n${v}\r\n`));
  }
  for (const [k, f] of Object.entries(files)) {
    chunks.push(
      Buffer.from(
        `--${boundary}\r\nContent-Disposition: form-data; name="${k}"; filename="${f.filename ?? 'img.jpg'}"\r\nContent-Type: ${f.type ?? 'image/jpeg'}\r\n\r\n`,
      ),
    );
    chunks.push(f.buf, Buffer.from('\r\n'));
  }
  chunks.push(Buffer.from(`--${boundary}--\r\n`));
  return { payload: Buffer.concat(chunks), headers: { 'content-type': `multipart/form-data; boundary=${boundary}` } };
}

export function randomMobile(): string {
  return `017${String(randomInt(0, 100_000_000)).padStart(8, '0')}`;
}

export function randomNid(): string {
  return String(randomInt(1_000_000_000, 9_999_999_999));
}

export type RegisterInput = {
  fullName?: string;
  mobile?: string;
  email?: string;
  password?: string;
  nidNumber?: string;
  dob?: string;
  nominees?: unknown[];
  acceptRules?: boolean;
};

export async function registerUser(app: FastifyInstance, input: RegisterInput = {}) {
  const mobile = input.mobile ?? randomMobile();
  const data = {
    fullName: input.fullName ?? 'মোঃ রহিম উদ্দিন',
    mobile,
    email: input.email ?? `u${randomBytes(5).toString('hex')}@example.com`,
    password: input.password ?? 'Strong-pass-42',
    nidNumber: input.nidNumber ?? randomNid(),
    dob: input.dob ?? '1990-05-01',
    nominees: input.nominees ?? [
      { name: 'ফাতেমা আক্তার', relation: 'স্ত্রী', idType: 'nid', idNumber: randomNid(), sharePercent: 60 },
      { name: 'মোঃ আরিফ উদ্দিন', relation: 'পুত্র', idType: 'birth_reg', idNumber: '20101234567890123', sharePercent: 40 },
    ],
    acceptRules: input.acceptRules ?? true,
  };
  const jpg = await sampleJpeg();
  const mp = multipart({ data: JSON.stringify(data) }, { nidFront: { buf: jpg }, nidBack: { buf: jpg }, photo: { buf: jpg } });
  const res = await app.inject({ method: 'POST', url: '/api/v1/auth/register', ...mp });
  return { res, data };
}

export async function loginUser(app: FastifyInstance, identifier: string, password = 'Strong-pass-42') {
  return app.inject({ method: 'POST', url: '/api/v1/auth/login', payload: { identifier, password, deviceId: 'test-device-1' } });
}

/** Registers + logs in; returns tokens and ids. */
export async function newUser(app: FastifyInstance, input: RegisterInput = {}) {
  const { res, data } = await registerUser(app, input);
  if (res.statusCode !== 201) throw new Error(`register failed ${res.statusCode} ${res.body}`);
  const login = await loginUser(app, data.mobile, data.password);
  if (login.statusCode !== 200) throw new Error(`login failed ${login.statusCode} ${login.body}`);
  const body = login.json();
  return { data, token: body.tokens.accessToken as string, refresh: body.tokens.refreshToken as string, id: body.me.id as string, me: body.me };
}

export const bearer = (t: string) => ({ authorization: `Bearer ${t}` });

/** Creates a staff member directly (TOTP pre-enrolled) and logs in through the real 2-step flow. */
export async function newStaff(env: TestEnv, roleKey: string, opts: { mustChange?: boolean } = {}) {
  const { app, db } = env;
  const email = `staff${randomBytes(4).toString('hex')}@example.com`;
  const password = 'Admin-pass-77';
  const secret = newTotpSecret();
  const role = await q1<{ id: string }>(db, 'SELECT id FROM roles WHERE key = $1', [roleKey]);
  const ctxCrypto = app.ctx.crypto;
  const row = await q1<{ id: string }>(
    db,
    `INSERT INTO staff (name, email, mobile, password_hash, role_id, must_change_password, totp_secret_enc, totp_enabled)
     VALUES ($1,$2,$3,$4,$5,$6,$7,true) RETURNING id`,
    ['ডি এম সাইফুল ইসলাম', email, randomMobile(), await hashPassword(password), role?.id, opts.mustChange ?? false, ctxCrypto.encryptText(secret)],
  );
  const token = await staffLogin(env, email, password, secret);
  return { id: row?.id as string, email, password, secret, token: token.accessToken, refresh: token.refreshToken };
}

export async function staffLogin(env: TestEnv, email: string, password: string, secret: string) {
  const { app, db } = env;
  // test helper: allow re-login inside the same 30 s window
  await db.query('UPDATE staff SET totp_last_step = NULL WHERE email = $1', [email]);
  const r1 = await app.inject({ method: 'POST', url: '/api/v1/admin/auth/login', payload: { email, password, deviceId: 'admin-device-1' } });
  if (r1.statusCode !== 200) throw new Error(`admin login failed ${r1.statusCode} ${r1.body}`);
  const r2 = await app.inject({
    method: 'POST',
    url: '/api/v1/admin/auth/mfa',
    payload: { mfaToken: r1.json().mfaToken, code: totpAt(secret, currentStep()) },
  });
  if (r2.statusCode !== 200) throw new Error(`mfa failed ${r2.statusCode} ${r2.body}`);
  return r2.json().tokens as { accessToken: string; refreshToken: string };
}

export async function todayRatePaisa(db: Db): Promise<number> {
  const r = await q1<{ rate_paisa: number }>(db, 'SELECT rate_paisa FROM rates WHERE rate_date <= $1 ORDER BY rate_date DESC LIMIT 1', [dhakaDate()]);
  return r?.rate_paisa ?? 0;
}

export function err(res: LightMyRequestResponse): { code: string; message: string; fields?: Record<string, string> } {
  return res.json().error;
}
