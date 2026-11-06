import { z } from 'zod';

// Long, specific markers so a random secret never trips them by chance.
const WEAK_MARKERS = ['changeme', 'change-me', 'change_me', 'example', 'placeholder', 'replace-', 'your-', 'your_', 'xxxxxxxx', '00000000', 'secret-key', 'password'];

function strongSecret(name: string, minLen: number) {
  return z
    .string({ error: `${name} is required` })
    .min(minLen, `${name} must be at least ${minLen} characters`)
    .refine((v) => !WEAK_MARKERS.some((m) => v.toLowerCase().includes(m)), `${name} looks like a placeholder; generate one with "npm run gen-secrets"`)
    .refine((v) => new Set(v).size >= 16, `${name} has too little entropy`);
}

const EnvSchema = z.object({
  NODE_ENV: z.enum(['development', 'production', 'test']).default('production'),
  HOST: z.string().default('0.0.0.0'),
  PORT: z.coerce.number().int().min(1).max(65535).default(3000),
  DATABASE_URL: z.string().min(1, 'DATABASE_URL is required'),
  MIGRATION_DATABASE_URL: z.string().min(1).optional(),
  DB_SSL: z.enum(['true', 'false']).default('false'),
  DB_POOL_MAX: z.coerce.number().int().min(1).max(100).default(10),
  JWT_SECRET: strongSecret('JWT_SECRET', 32),
  DATA_ENC_KEY: z
    .string({ error: 'DATA_ENC_KEY is required' })
    .refine((v) => {
      try {
        return Buffer.from(v, 'base64').length === 32 && /^[A-Za-z0-9+/=]+$/.test(v);
      } catch {
        return false;
      }
    }, 'DATA_ENC_KEY must be 32 random bytes encoded as base64 (npm run gen-secrets)'),
  HMAC_KEY: strongSecret('HMAC_KEY', 32),
  PUBLIC_BASE_URL: z.string().url('PUBLIC_BASE_URL must be a URL like https://share.rapti.net'),
  UPLOAD_DIR: z.string().min(1).refine((v) => v.startsWith('/'), 'UPLOAD_DIR must be an absolute path'),
  TRUST_PROXY: z.string().default('false'),
  LOG_LEVEL: z.enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent']).default('info'),
  DISABLE_RATE_LIMIT: z.enum(['true', 'false']).default('false'),
  BACKUP_DIR: z.string().default('/backups').refine((v) => v.startsWith('/'), 'BACKUP_DIR must be an absolute path'),
});

export type Config = {
  env: 'development' | 'production' | 'test';
  host: string;
  port: number;
  databaseUrl: string;
  migrationDatabaseUrl: string | null;
  dbSsl: boolean;
  dbPoolMax: number;
  jwtSecret: Uint8Array;
  dataEncKey: Buffer;
  hmacKey: Buffer;
  publicBaseUrl: string;
  uploadDir: string;
  trustProxy: boolean | number | string;
  logLevel: string;
  rateLimitEnabled: boolean;
  backupDir: string;
};

function parseTrustProxy(v: string): boolean | number | string {
  const t = v.trim().toLowerCase();
  if (t === 'true') return true;
  if (t === 'false' || t === '') return false;
  if (/^\d+$/.test(t)) return Number(t);
  return v.trim();
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const parsed = EnvSchema.safeParse(env);
  if (!parsed.success) {
    const lines = parsed.error.issues.map((i) => ` - ${i.path.join('.')}: ${i.message}`);
    throw new Error(`Invalid configuration:\n${lines.join('\n')}`);
  }
  const e = parsed.data;
  if (e.NODE_ENV === 'production') {
    if (!e.PUBLIC_BASE_URL.startsWith('https://')) {
      throw new Error('Invalid configuration:\n - PUBLIC_BASE_URL must use https:// in production');
    }
    if (e.DISABLE_RATE_LIMIT === 'true') {
      throw new Error('Invalid configuration:\n - DISABLE_RATE_LIMIT is not allowed in production');
    }
  }
  if (e.JWT_SECRET === e.HMAC_KEY) {
    throw new Error('Invalid configuration:\n - JWT_SECRET and HMAC_KEY must be different');
  }
  return {
    env: e.NODE_ENV,
    host: e.HOST,
    port: e.PORT,
    databaseUrl: e.DATABASE_URL,
    migrationDatabaseUrl: e.MIGRATION_DATABASE_URL ?? null,
    dbSsl: e.DB_SSL === 'true',
    dbPoolMax: e.DB_POOL_MAX,
    jwtSecret: new TextEncoder().encode(e.JWT_SECRET),
    dataEncKey: Buffer.from(e.DATA_ENC_KEY, 'base64'),
    hmacKey: Buffer.from(e.HMAC_KEY, 'utf8'),
    publicBaseUrl: e.PUBLIC_BASE_URL.replace(/\/+$/, ''),
    uploadDir: e.UPLOAD_DIR,
    trustProxy: parseTrustProxy(e.TRUST_PROXY),
    logLevel: e.LOG_LEVEL,
    rateLimitEnabled: e.DISABLE_RATE_LIMIT !== 'true',
    backupDir: e.BACKUP_DIR,
  };
}
