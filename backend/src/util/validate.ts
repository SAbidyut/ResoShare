import { z } from 'zod';
import { ApiError } from '../errors.js';
import { toAsciiDigits } from './bn.js';
import { ageOn, dhakaDate, isValidDate } from './time.js';
import { isCommonPassword } from './passwords.js';

export function parse<T extends z.ZodType>(schema: T, data: unknown): z.output<T> {
  const r = schema.safeParse(data);
  if (r.success) return r.data;
  const fields: Record<string, string> = {};
  let unknownKeys = false;
  for (const issue of r.error.issues) {
    if (issue.code === 'unrecognized_keys') {
      unknownKeys = true;
      continue;
    }
    const key = issue.path.length ? issue.path.map(String).join('.') : '_';
    if (!fields[key]) fields[key] = issue.message.length < 200 && /[ঀ-৿]/.test(issue.message) ? issue.message : 'সঠিক তথ্য দিন';
  }
  if (unknownKeys && Object.keys(fields).length === 0) fields._ = 'অজানা তথ্য পাঠানো হয়েছে';
  throw new ApiError('INVALID_INPUT', undefined, fields);
}

/** Normalises Bangladeshi mobile numbers to 01XXXXXXXXX or returns null. */
export function normalizeMobile(input: string): string | null {
  let s = toAsciiDigits(input).replace(/[\s\-()]/g, '');
  if (s.startsWith('+880')) s = s.slice(3);
  else if (s.startsWith('880')) s = s.slice(2);
  return /^01[3-9]\d{8}$/.test(s) ? s : null;
}

const NAME_RE = /^[\p{L}\p{M}][\p{L}\p{M} .\-]*$/u;
const EMAIL_RE = /^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$/;

export const zName = z
  .string()
  .transform((s) => s.trim().replace(/\s+/g, ' '))
  .pipe(z.string().min(3, 'নাম কমপক্ষে ৩ অক্ষরের হতে হবে').max(100, 'নাম অনেক বড়').regex(NAME_RE, 'নামে শুধু অক্ষর, স্পেস, ডট ও হাইফেন থাকতে পারে'));

export const zMobile = z
  .string()
  .max(20, 'সঠিক মোবাইল নম্বর দিন')
  .transform((s, ctx) => {
    const m = normalizeMobile(s);
    if (!m) {
      ctx.addIssue({ code: 'custom', message: 'সঠিক মোবাইল নম্বর দিন (০১XXXXXXXXX)' });
      return z.NEVER;
    }
    return m;
  });

export const zEmail = z
  .string()
  .transform((s) => s.trim().toLowerCase())
  .pipe(z.string().max(254, 'ইমেইল অনেক বড়').regex(EMAIL_RE, 'সঠিক ইমেইল দিন'));

export const PASSWORD_SHAPE_MSG = 'পাসওয়ার্ডে অক্ষর, সংখ্যা ও একটি চিহ্ন (যেমন @ # !) রাখুন';

/** At least one letter, one digit and one symbol (anything that is not a letter or digit). */
export function isStrongShape(s: string): boolean {
  return /\p{L}/u.test(s) && /\p{Nd}/u.test(s) && /[^\p{L}\p{N}\p{M}]/u.test(s);
}

export const zPassword = z
  .string()
  .min(8, 'পাসওয়ার্ড কমপক্ষে ৮ অক্ষরের হতে হবে')
  .max(128, 'পাসওয়ার্ড অনেক বড়')
  .refine((s) => isStrongShape(s), PASSWORD_SHAPE_MSG)
  .refine((s) => !isCommonPassword(s), 'এই পাসওয়ার্ডটি খুব সাধারণ, অন্য পাসওয়ার্ড দিন');

/** Password field on login – only bounded, never echoes policy. */
export const zLoginPassword = z.string().min(1, 'পাসওয়ার্ড দিন').max(128, 'পাসওয়ার্ড অনেক বড়');

const digitsOnly = (s: string) => toAsciiDigits(s).replace(/[\s-]/g, '');

export const zNid = z
  .string()
  .max(30)
  .transform(digitsOnly)
  .pipe(z.string().regex(/^(\d{10}|\d{13}|\d{17})$/, 'NID নম্বর ১০, ১৩ বা ১৭ অঙ্কের হতে হবে'));

export const zDate = z.string().refine(isValidDate, 'সঠিক তারিখ দিন (YYYY-MM-DD)');

export const zDob = zDate.refine((d) => ageOn(d, dhakaDate()) >= 18, 'বয়স কমপক্ষে ১৮ বছর হতে হবে').refine((d) => ageOn(d, dhakaDate()) <= 120, 'সঠিক জন্মতারিখ দিন');

export const zNomineeInput = z
  .object({
    name: zName,
    relation: z
      .string()
      .transform((s) => s.trim())
      .pipe(z.string().min(2, 'সম্পর্ক লিখুন').max(30, 'সম্পর্ক অনেক বড়').regex(NAME_RE, 'সম্পর্কে শুধু অক্ষর থাকতে পারে')),
    idType: z.enum(['nid', 'birth_reg'], { error: 'পরিচয়পত্রের ধরন বাছাই করুন' }),
    idNumber: z.string().max(30).transform(digitsOnly),
    sharePercent: z.number().int().min(1, 'শতাংশ ১–১০০ হতে হবে').max(100, 'শতাংশ ১–১০০ হতে হবে'),
  })
  .strict()
  .superRefine((n, ctx) => {
    const ok = n.idType === 'nid' ? /^(\d{10}|\d{13}|\d{17})$/.test(n.idNumber) : /^\d{17}$/.test(n.idNumber);
    if (!ok) {
      ctx.addIssue({
        code: 'custom',
        path: ['idNumber'],
        message: n.idType === 'nid' ? 'NID নম্বর ১০, ১৩ বা ১৭ অঙ্কের হতে হবে' : 'জন্ম নিবন্ধন নম্বর ১৭ অঙ্কের হতে হবে',
      });
    }
  });

export const zNominees = z
  .array(zNomineeInput)
  .min(1, 'কমপক্ষে ১ জন নমিনি দিন')
  .max(2, 'সর্বোচ্চ ২ জন নমিনি দেওয়া যাবে')
  .refine((arr) => arr.reduce((s, n) => s + n.sharePercent, 0) === 100, 'নমিনিদের মোট ভাগ ১০০% হতে হবে');

export const zUuid = z.string().regex(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i);

export const zReason = z
  .string()
  .transform((s) => s.trim())
  .pipe(z.string().min(3, 'কারণ লিখুন (কমপক্ষে ৩ অক্ষর)').max(300, 'কারণ অনেক বড়'));

export const zDeviceId = z.string().min(8).max(100).regex(/^[A-Za-z0-9._:-]+$/);

export function idParam(params: unknown): string {
  const id = (params as { id?: unknown } | undefined)?.id;
  const r = zUuid.safeParse(id);
  if (!r.success) throw new ApiError('NOT_FOUND');
  return r.data.toLowerCase();
}

export function uuidParam(params: unknown, name: string): string {
  const v = (params as Record<string, unknown> | undefined)?.[name];
  const r = zUuid.safeParse(v);
  if (!r.success) throw new ApiError('NOT_FOUND');
  return r.data.toLowerCase();
}

// ---- cursor pagination ----

export type Cursor = { t: string; id: string };

export const zPageQuery = z
  .object({
    cursor: z.string().max(200).optional(),
    limit: z.coerce.number().int().min(1).max(50).default(20),
  })
  .loose();

export function decodeCursor(c: string | undefined): Cursor | null {
  if (!c) return null;
  try {
    const obj = JSON.parse(Buffer.from(c, 'base64url').toString('utf8')) as unknown;
    const r = z.object({ t: z.string().max(40), id: z.string().max(40) }).strict().safeParse(obj);
    if (!r.success || Number.isNaN(Date.parse(r.data.t))) throw new Error('bad');
    return r.data;
  } catch {
    throw new ApiError('INVALID_INPUT', undefined, { cursor: 'সঠিক তথ্য দিন' });
  }
}

export function encodeCursor(t: Date | string, id: string): string {
  const ts = t instanceof Date ? t.toISOString() : t;
  return Buffer.from(JSON.stringify({ t: ts, id }), 'utf8').toString('base64url');
}

/** Escapes LIKE wildcards in user search input. */
export function likePattern(q: string): string {
  return `%${q.replace(/[\\%_]/g, (m) => `\\${m}`)}%`;
}
