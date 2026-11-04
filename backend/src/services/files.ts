import { randomBytes } from 'node:crypto';
import { mkdir, readFile, unlink, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import sharp from 'sharp';
import type { Ctx } from '../context.js';
import { ApiError } from '../errors.js';

export type KycKind = 'nid_front' | 'nid_back' | 'photo';
export const KYC_KINDS: Record<string, KycKind> = { nidFront: 'nid_front', nidBack: 'nid_back', photo: 'photo' };
export const MAX_FILE_BYTES = 5 * 1024 * 1024;

const JPEG = Buffer.from([0xff, 0xd8, 0xff]);
const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

export function isAllowedImage(buf: Buffer): boolean {
  return buf.subarray(0, 3).equals(JPEG) || buf.subarray(0, 8).equals(PNG);
}

/** Validates and re-encodes an uploaded image: JPEG output, metadata stripped, max 2000px. */
export async function sanitizeImage(buf: Buffer): Promise<Buffer> {
  if (buf.length === 0 || buf.length > MAX_FILE_BYTES) throw new ApiError('PAYLOAD_TOO_LARGE');
  if (!isAllowedImage(buf)) throw new ApiError('INVALID_INPUT', 'শুধু JPG বা PNG ছবি দেওয়া যাবে।');
  try {
    const meta = await sharp(buf, { limitInputPixels: 40_000_000, failOn: 'error' }).metadata();
    if (!meta.width || !meta.height || meta.width < 100 || meta.height < 100) {
      throw new ApiError('INVALID_INPUT', 'ছবিটি অনেক ছোট বা অস্পষ্ট।');
    }
    return await sharp(buf, { limitInputPixels: 40_000_000, failOn: 'error' })
      .rotate()
      .resize(2000, 2000, { fit: 'inside', withoutEnlargement: true })
      .jpeg({ quality: 85, mozjpeg: true })
      .toBuffer();
  } catch (err) {
    if (err instanceof ApiError) throw err;
    throw new ApiError('INVALID_INPUT', 'ছবিটি পড়া যাচ্ছে না। অন্য ছবি দিন।');
  }
}

export async function storeEncrypted(ctx: Ctx, jpeg: Buffer): Promise<{ storageName: string; size: number }> {
  await mkdir(ctx.config.uploadDir, { recursive: true, mode: 0o700 });
  const storageName = `${randomBytes(24).toString('hex')}.bin`;
  await writeFile(join(ctx.config.uploadDir, storageName), ctx.crypto.encryptFile(jpeg), { mode: 0o600, flag: 'wx' });
  return { storageName, size: jpeg.length };
}

export async function readDecrypted(ctx: Ctx, storageName: string): Promise<Buffer> {
  if (!/^[a-f0-9]{48}\.bin$/.test(storageName)) throw new ApiError('NOT_FOUND');
  const blob = await readFile(join(ctx.config.uploadDir, storageName));
  return ctx.crypto.decryptFile(blob);
}

export async function removeStored(ctx: Ctx, storageNames: string[]): Promise<void> {
  for (const n of storageNames) {
    if (!/^[a-f0-9]{48}\.bin$/.test(n)) continue;
    await unlink(join(ctx.config.uploadDir, n)).catch(() => {});
  }
}
