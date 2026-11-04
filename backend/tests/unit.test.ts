import { describe, expect, it } from 'vitest';
import { loadConfig } from '../src/config.js';
import { bnDate, bnMoney, groupIndian, possessive } from '../src/util/bn.js';
import { Crypto, tempPassword } from '../src/util/crypto.js';
import { maskEmail, maskId, maskMobile } from '../src/util/mask.js';
import { base32Encode, hotp, verifyTotp, totpAt } from '../src/util/totp.js';
import { addMonths, ageOn, isValidDate, lastOfMonth } from '../src/util/time.js';
import { normalizeMobile } from '../src/util/validate.js';
import { chartDates } from '../src/services/chart.js';
import { salesBuckets } from '../src/routes/admin/overview.js';
import { compareVersions } from '../src/routes/admin/content.js';

describe('totp (RFC 4226/6238 vectors)', () => {
  const secret = Buffer.from('12345678901234567890');
  it('matches RFC 4226 HOTP values', () => {
    expect(hotp(secret, 0)).toBe('755224');
    expect(hotp(secret, 1)).toBe('287082');
    expect(hotp(secret, 9)).toBe('520489');
  });
  it('matches RFC 6238 TOTP at T=59s (6-digit truncation of 94287082)', () => {
    const b32 = base32Encode(secret);
    expect(totpAt(b32, Math.floor(59 / 30))).toBe('287082');
    expect(verifyTotp(b32, '287082', null, 59_000)).toBe(1);
    expect(verifyTotp(b32, '287082', 1, 59_000)).toBeNull(); // replay
    expect(verifyTotp(b32, 'abcdef', null, 59_000)).toBeNull();
  });
});

describe('bangla formatting', () => {
  it('formats money with Indian grouping and paisa', () => {
    expect(bnMoney(100000)).toBe('৳১,০০০.০০');
    expect(bnMoney(260000000)).toBe('৳২৬,০০,০০০.০০');
    expect(bnMoney(101250)).toBe('৳১,০১২.৫০');
    expect(bnMoney(-50)).toBe('-৳০.৫০');
    expect(groupIndian(1234567)).toBe('12,34,567');
  });
  it('formats dates and possessives', () => {
    expect(bnDate('2026-11-04')).toBe('০৪ নভেম্বর ২০২৬');
    expect(bnDate('2026-11-04', false)).toBe('০৪ নভেম্বর');
    expect(possessive('ফাতেমা আক্তার')).toBe('ফাতেমা আক্তারের');
    expect(possessive('সুমাইয়া')).toBe('সুমাইয়ার');
  });
});

describe('validation helpers', () => {
  it('normalises mobiles', () => {
    expect(normalizeMobile('+8801711223344')).toBe('01711223344');
    expect(normalizeMobile('০১৭১১-২২৩৩৪৪')).toBe('01711223344');
    expect(normalizeMobile('01211223344')).toBeNull();
    expect(normalizeMobile('0171122334')).toBeNull();
  });
  it('masks identifiers', () => {
    expect(maskMobile('01911234567')).toBe('01911-••••••');
    expect(maskEmail('rahim@example.com')).toBe('r•••@example.com');
    expect(maskId('1234567890')).toBe('•••••••890');
  });
  it('dates', () => {
    expect(isValidDate('2026-02-29')).toBe(false);
    expect(isValidDate('2028-02-29')).toBe(true);
    expect(lastOfMonth('2026-02-10')).toBe('2026-02-28');
    expect(addMonths('2026-01-01', -2)).toBe('2025-11-01');
    expect(ageOn('2008-11-05', '2026-11-04')).toBe(17);
    expect(ageOn('2008-11-04', '2026-11-04')).toBe(18);
    expect(compareVersions('1.10', '1.9')).toBeGreaterThan(0);
    expect(compareVersions('2', '1.9.9')).toBeGreaterThan(0);
  });
  it('chart & sales buckets have the documented sizes', () => {
    expect(chartDates('daily', '2026-11-04')).toHaveLength(7);
    expect(chartDates('weekly', '2026-11-04')).toHaveLength(8);
    expect(chartDates('monthly', '2026-11-04').at(-2)).toBe('2026-10-31');
    expect(chartDates('yearly', '2026-11-04')).toEqual(['2022-12-31', '2023-12-31', '2024-12-31', '2025-12-31', '2026-11-04']);
    expect(salesBuckets('monthly', '2026-11-04')[0]?.date).toBe('2025-12-01');
    expect(salesBuckets('weekly', '2026-11-04').at(-1)?.date).toBe('2026-10-29');
  });
});

describe('crypto', () => {
  const c = new Crypto(Buffer.alloc(32, 7), Buffer.from('k'.repeat(40)));
  it('round-trips and authenticates ciphertext', () => {
    const blob = c.encryptText('1234567890');
    expect(c.decryptText(blob)).toBe('1234567890');
    expect(c.encryptText('1234567890')).not.toBe(blob); // random IV
    const parts = blob.split('.');
    parts[3] = Buffer.from('tampered').toString('base64url');
    expect(() => c.decryptText(parts.join('.'))).toThrow();
    const f = c.encryptFile(Buffer.from('jpegdata'));
    f[f.length - 1] ^= 1;
    expect(() => c.decryptFile(f)).toThrow();
  });
  it('temp passwords are 12 chars with letters and digits', () => {
    for (let i = 0; i < 50; i++) {
      const p = tempPassword();
      expect(p).toMatch(/^[A-Za-z2-9]{12}$/);
      expect(p).toMatch(/[A-Za-z]/);
      expect(p).toMatch(/\d/);
    }
  });
});

describe('config', () => {
  const good = {
    DATABASE_URL: 'postgres://x@y/z',
    JWT_SECRET: 'Gq3v9LkP2xR8mT1wZ5nB7cY4hJ6dF0sA-_aQ',
    HMAC_KEY: 'Zr8nM2kQ7vL1pX5tW9cB3gH6jD0fS4yA-_eR',
    DATA_ENC_KEY: Buffer.alloc(32, 9).toString('base64'),
    PUBLIC_BASE_URL: 'https://share.rapti.net',
    UPLOAD_DIR: '/data/uploads',
    NODE_ENV: 'production',
  };
  it('accepts strong config', () => {
    expect(loadConfig(good).publicBaseUrl).toBe('https://share.rapti.net');
  });
  it('refuses weak or missing secrets and http in production', () => {
    expect(() => loadConfig({ ...good, JWT_SECRET: 'change-me-change-me-change-me-change-me' })).toThrow(/JWT_SECRET/);
    expect(() => loadConfig({ ...good, JWT_SECRET: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' })).toThrow(/entropy/);
    expect(() => loadConfig({ ...good, DATA_ENC_KEY: 'short' })).toThrow(/DATA_ENC_KEY/);
    expect(() => loadConfig({ ...good, HMAC_KEY: undefined })).toThrow(/HMAC_KEY/);
    expect(() => loadConfig({ ...good, PUBLIC_BASE_URL: 'http://share.rapti.net' })).toThrow(/https/);
    expect(() => loadConfig({ ...good, DISABLE_RATE_LIMIT: 'true' })).toThrow(/DISABLE_RATE_LIMIT/);
    expect(() => loadConfig({ ...good, HMAC_KEY: good.JWT_SECRET })).toThrow(/different/);
  });
});
