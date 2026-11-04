import { createCipheriv, createDecipheriv, createHash, createHmac, randomBytes, timingSafeEqual } from 'node:crypto';

const ALG = 'aes-256-gcm';
const IV_LEN = 12;
const TAG_LEN = 16;
const FILE_MAGIC = Buffer.from('RSF1');

export class Crypto {
  private readonly encKey: Buffer;
  private readonly nidIndexKey: Buffer;
  private readonly certKey: Buffer;

  constructor(dataEncKey: Buffer, hmacKey: Buffer) {
    if (dataEncKey.length !== 32) throw new Error('DATA_ENC_KEY must be 32 bytes');
    this.encKey = dataEncKey;
    // Purpose-separated sub keys so one key never serves two jobs.
    this.nidIndexKey = createHmac('sha256', hmacKey).update('resoshare/nid-index/v1').digest();
    this.certKey = createHmac('sha256', hmacKey).update('resoshare/certificate/v1').digest();
  }

  /** Encrypt a short UTF-8 string → "v1.<iv>.<tag>.<ct>" (base64url parts). */
  encryptText(plain: string): string {
    const iv = randomBytes(IV_LEN);
    const c = createCipheriv(ALG, this.encKey, iv);
    const ct = Buffer.concat([c.update(plain, 'utf8'), c.final()]);
    const tag = c.getAuthTag();
    return `v1.${iv.toString('base64url')}.${tag.toString('base64url')}.${ct.toString('base64url')}`;
  }

  decryptText(blob: string): string {
    const [v, ivB, tagB, ctB] = blob.split('.');
    if (v !== 'v1' || !ivB || !tagB || ctB === undefined) throw new Error('bad ciphertext');
    const d = createDecipheriv(ALG, this.encKey, Buffer.from(ivB, 'base64url'));
    d.setAuthTag(Buffer.from(tagB, 'base64url'));
    return Buffer.concat([d.update(Buffer.from(ctB, 'base64url')), d.final()]).toString('utf8');
  }

  encryptFile(data: Buffer): Buffer {
    const iv = randomBytes(IV_LEN);
    const c = createCipheriv(ALG, this.encKey, iv);
    const ct = Buffer.concat([c.update(data), c.final()]);
    return Buffer.concat([FILE_MAGIC, iv, c.getAuthTag(), ct]);
  }

  decryptFile(blob: Buffer): Buffer {
    if (blob.length < FILE_MAGIC.length + IV_LEN + TAG_LEN || !blob.subarray(0, 4).equals(FILE_MAGIC)) {
      throw new Error('bad file ciphertext');
    }
    const iv = blob.subarray(4, 4 + IV_LEN);
    const tag = blob.subarray(4 + IV_LEN, 4 + IV_LEN + TAG_LEN);
    const ct = blob.subarray(4 + IV_LEN + TAG_LEN);
    const d = createDecipheriv(ALG, this.encKey, iv);
    d.setAuthTag(tag);
    return Buffer.concat([d.update(ct), d.final()]);
  }

  /** Deterministic blind index for uniqueness checks on encrypted identifiers. */
  nidIndex(nid: string): string {
    return createHmac('sha256', this.nidIndexKey).update(nid).digest('hex');
  }

  certSignature(certNo: string, shares: number, issuedOn: string): string {
    return createHmac('sha256', this.certKey).update(`${certNo}|${shares}|${issuedOn}`).digest('hex').slice(0, 20);
  }
}

export function sha256Hex(s: string | Buffer): string {
  return createHash('sha256').update(s).digest('hex');
}

export function randomToken(bytes = 32): string {
  return randomBytes(bytes).toString('base64url');
}

export function safeEqual(a: string, b: string): boolean {
  const ab = Buffer.from(a);
  const bb = Buffer.from(b);
  if (ab.length !== bb.length) return false;
  return timingSafeEqual(ab, bb);
}

const TEMP_ALPHABET_LETTERS = 'abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ';
const TEMP_ALPHABET_DIGITS = '23456789';

/** Random temporary password: 12 chars, always contains letters and digits. */
export function tempPassword(): string {
  const all = TEMP_ALPHABET_LETTERS + TEMP_ALPHABET_DIGITS;
  const pick = (alphabet: string) => {
    // rejection sampling to avoid modulo bias
    const max = 256 - (256 % alphabet.length);
    for (;;) {
      const b = randomBytes(1)[0] ?? 0;
      if (b < max) return alphabet[b % alphabet.length] ?? 'x';
    }
  };
  const chars = [pick(TEMP_ALPHABET_LETTERS), pick(TEMP_ALPHABET_DIGITS)];
  while (chars.length < 12) chars.push(pick(all));
  // Fisher–Yates shuffle with crypto randomness
  for (let i = chars.length - 1; i > 0; i--) {
    const j = (randomBytes(1)[0] ?? 0) % (i + 1);
    [chars[i], chars[j]] = [chars[j] as string, chars[i] as string];
  }
  return chars.join('');
}
