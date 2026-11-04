// Bangla presentation helpers (digits, money with Indian grouping, dates).

const BN_DIGITS = '০১২৩৪৫৬৭৮৯';
const BN_MONTHS = [
  'জানুয়ারি',
  'ফেব্রুয়ারি',
  'মার্চ',
  'এপ্রিল',
  'মে',
  'জুন',
  'জুলাই',
  'আগস্ট',
  'সেপ্টেম্বর',
  'অক্টোবর',
  'নভেম্বর',
  'ডিসেম্বর',
];

export function bnDigits(s: string | number): string {
  return String(s).replace(/[0-9]/g, (d) => BN_DIGITS[Number(d)] ?? d);
}

export function toAsciiDigits(s: string): string {
  return s.replace(/[০-৯]/g, (d) => String(BN_DIGITS.indexOf(d)));
}

/** 1234567 → "12,34,567" (Indian grouping). */
export function groupIndian(n: number): string {
  const s = String(Math.trunc(Math.abs(n)));
  if (s.length <= 3) return s;
  const last3 = s.slice(-3);
  let rest = s.slice(0, -3);
  const parts: string[] = [];
  while (rest.length > 2) {
    parts.unshift(rest.slice(-2));
    rest = rest.slice(0, -2);
  }
  if (rest) parts.unshift(rest);
  return `${parts.join(',')},${last3}`;
}

/** paisa → "৳১,০০০.০০" */
export function bnMoney(paisa: number): string {
  const neg = paisa < 0;
  const abs = Math.abs(Math.trunc(paisa));
  const taka = Math.floor(abs / 100);
  const ps = abs % 100;
  const text = `${groupIndian(taka)}.${String(ps).padStart(2, '0')}`;
  return `${neg ? '-' : ''}৳${bnDigits(text)}`;
}

/** paisa → "1000.00" (machine readable for reports). */
export function plainMoney(paisa: number): string {
  const neg = paisa < 0;
  const abs = Math.abs(Math.trunc(paisa));
  return `${neg ? '-' : ''}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, '0')}`;
}

export function bnCount(n: number): string {
  return bnDigits(groupIndian(n));
}

/** 'YYYY-MM-DD' → "০৪ নভেম্বর ২০২৬" */
export function bnDate(date: string, withYear = true): string {
  const [y, m, d] = date.split('-');
  const month = BN_MONTHS[Number(m) - 1] ?? '';
  return withYear ? `${bnDigits(d ?? '')} ${month} ${bnDigits(y ?? '')}` : `${bnDigits(d ?? '')} ${month}`;
}

export function methodName(method: string): string {
  return method === 'cash' ? 'ক্যাশ' : 'ব্যাংক ট্রান্সফার';
}

/** Bangla possessive: "রহিম" → "রহিমের", "ফাতেমা আক্তার" → "ফাতেমা আক্তারের". */
export function possessive(name: string): string {
  const n = name.trim();
  if (!n) return n;
  const last = n[n.length - 1] ?? '';
  // Names ending in a vowel sign / independent vowel take "র", consonant endings take "ের".
  if (/[া-ৌঅ-ঔaeiouAEIOU]$/.test(last)) return `${n}র`;
  return `${n}ের`;
}
