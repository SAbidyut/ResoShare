import { randomBytes } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { defineConfig } from 'vitest/config';

const env = {
  NODE_ENV: 'test',
  DATABASE_URL: process.env.TEST_DATABASE_URL ?? 'postgres://postgres@127.0.0.1:54329/resoshare_test',
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
