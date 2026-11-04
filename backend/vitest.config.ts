import { randomBytes } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { defineConfig } from 'vitest/config';

// Owner/superuser URL: only used by global-setup to create the DB, migrate and create the runtime role.
const ownerUrl = process.env.TEST_DATABASE_URL ?? 'postgres://postgres@127.0.0.1:54329/resoshare_test';
const appUser = 'resoshare_test_app';
const appPassword = randomBytes(24).toString('base64url');
const runtimeUrl = new URL(ownerUrl);
runtimeUrl.username = appUser;
runtimeUrl.password = appPassword;

const env = {
  NODE_ENV: 'test',
  // The API under test connects as the restricted runtime role, exactly like production.
  DATABASE_URL: runtimeUrl.toString(),
  TEST_OWNER_DATABASE_URL: ownerUrl,
  APP_DB_USER: appUser,
  APP_DB_PASSWORD: appPassword,
  JWT_SECRET: randomBytes(48).toString('base64url'),
  HMAC_KEY: randomBytes(48).toString('base64url'),
  DATA_ENC_KEY: randomBytes(32).toString('base64'),
  PUBLIC_BASE_URL: 'https://share.test',
  UPLOAD_DIR: join(tmpdir(), `resoshare-test-uploads-${process.pid}`),
  TRUST_PROXY: 'false',
  LOG_LEVEL: 'silent',
  DISABLE_RATE_LIMIT: 'true',
};
Object.assign(process.env, env);

export default defineConfig({
  test: {
    include: ['tests/**/*.test.ts'],
    globalSetup: ['tests/global-setup.ts'],
    env,
    fileParallelism: false,
    testTimeout: 60_000,
    hookTimeout: 120_000,
  },
});
