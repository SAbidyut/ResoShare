const DOT = '•';

/** 01911234567 → "01911-••••••" */
export function maskMobile(mobile: string): string {
  if (mobile.length < 6) return DOT.repeat(mobile.length);
  return `${mobile.slice(0, 5)}-${DOT.repeat(mobile.length - 5)}`;
}

/** rahim@example.com → "r•••@example.com" */
export function maskEmail(email: string): string {
  const at = email.indexOf('@');
  if (at <= 0) return DOT.repeat(3);
  return `${email[0]}${DOT.repeat(3)}${email.slice(at)}`;
}

/** 1234567890 → "•••••••890" */
export function maskId(id: string): string {
  if (id.length <= 3) return DOT.repeat(id.length);
  return `${DOT.repeat(id.length - 3)}${id.slice(-3)}`;
}
