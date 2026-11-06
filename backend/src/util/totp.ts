import { createHmac, randomBytes } from 'node:crypto';

const B32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';

export function base32Encode(buf: Buffer): string {
  let bits = 0;
  let value = 0;
  let out = '';
  for (const byte of buf) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      out += B32[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) out += B32[(value << (5 - bits)) & 31];
  return out;
}

export function base32Decode(s: string): Buffer {
  const clean = s.replace(/=+$/, '').toUpperCase().replace(/\s+/g, '');
  let bits = 0;
  let value = 0;
  const out: number[] = [];
  for (const ch of clean) {
    const idx = B32.indexOf(ch);
    if (idx < 0) throw new Error('invalid base32');
    value = (value << 5) | idx;
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 255);
      bits -= 8;
    }
  }
  return Buffer.from(out);
}

export function newTotpSecret(): string {
  return base32Encode(randomBytes(20)); // 160-bit, RFC 4226 recommendation
}

export const TOTP_STEP_SEC = 30;

export function currentStep(nowMs: number = Date.now()): number {
  return Math.floor(nowMs / 1000 / TOTP_STEP_SEC);
}

export function hotp(secret: Buffer, counter: number): string {
  const buf = Buffer.alloc(8);
  buf.writeBigUInt64BE(BigInt(counter));
  const h = createHmac('sha1', secret).update(buf).digest();
  const offset = (h[h.length - 1] ?? 0) & 0xf;
  const bin =
    (((h[offset] ?? 0) & 0x7f) << 24) |
    (((h[offset + 1] ?? 0) & 0xff) << 16) |
    (((h[offset + 2] ?? 0) & 0xff) << 8) |
    ((h[offset + 3] ?? 0) & 0xff);
  return String(bin % 1_000_000).padStart(6, '0');
}

export function totpAt(secretB32: string, step: number): string {
  return hotp(base32Decode(secretB32), step);
}

/**
 * Returns the matched time step (to be stored for replay protection) or null.
 * Accepts ±1 step of clock drift and only steps strictly greater than `lastUsedStep`.
 */
export function verifyTotp(secretB32: string, code: string, lastUsedStep: number | null, nowMs: number = Date.now()): number | null {
  if (!/^\d{6}$/.test(code)) return null;
  const secret = base32Decode(secretB32);
  const now = currentStep(nowMs);
  let matched: number | null = null;
  for (const step of [now - 1, now, now + 1]) {
    // constant work for every candidate step
    const candidate = hotp(secret, step);
    let diff = 0;
    for (let i = 0; i < 6; i++) diff |= candidate.charCodeAt(i) ^ code.charCodeAt(i);
    if (diff === 0 && (lastUsedStep === null || step > lastUsedStep)) matched = step;
  }
  return matched;
}

export function otpauthUri(secretB32: string, account: string, issuer = 'Resonance Automation'): string {
  const label = encodeURIComponent(`${issuer}:${account}`);
  const params = new URLSearchParams({ secret: secretB32, issuer, algorithm: 'SHA1', digits: '6', period: String(TOTP_STEP_SEC) });
  return `otpauth://totp/${label}?${params.toString()}`;
}
