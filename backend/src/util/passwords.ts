import argon2 from 'argon2';

// OWASP-recommended argon2id parameters (19 MiB+). We use 64 MiB, t=3, p=1.
const OPTIONS = { type: argon2.argon2id, memoryCost: 65536, timeCost: 3, parallelism: 1 } as const;

export async function hashPassword(plain: string): Promise<string> {
  return argon2.hash(plain, OPTIONS);
}

export async function verifyPassword(hash: string, plain: string): Promise<boolean> {
  try {
    return await argon2.verify(hash, plain);
  } catch {
    return false;
  }
}

let dummyHash: Promise<string> | null = null;

/** Burns the same CPU time as a real verify when the account does not exist (prevents user enumeration by timing). */
export async function fakeVerify(plain: string): Promise<void> {
  if (!dummyHash) dummyHash = hashPassword('dummy-password-for-timing-1');
  await verifyPassword(await dummyHash, plain);
}

const COMMON = new Set(
  [
    'password', 'password1', 'password12', 'password123', 'passw0rd', 'p@ssw0rd', 'p@ssword1', 'qwerty123', 'qwerty12', 'qwertyuiop1',
    '12345678', '123456789', '1234567890', '123456789a', 'a12345678', '1q2w3e4r', '1q2w3e4r5t', 'abc12345', 'abcd1234', 'abcdef12',
    'iloveyou1', 'welcome1', 'welcome123', 'admin123', 'admin1234', 'administrator1', 'letmein1', 'monkey123', 'dragon123', 'football1',
    'baseball1', 'master123', 'sunshine1', 'shadow123', 'princess1', 'trustno1', 'superman1', 'computer1', 'internet1', 'test1234',
    'bangladesh1', 'bangladesh123', 'dhaka1234', 'dhaka123', 'bismillah1', 'bismillah123', 'allah1234', 'pakistan1', 'india1234', 'love1234',
    'resonance1', 'resonance123', 'rapti1234', 'rapti123', 'share1234', 'share123', 'shareholder1', 'qwerty1', 'zxcvbnm1', 'asdfghjkl1',
    'asdf1234', 'zaq12wsx', '1qaz2wsx', 'qazwsx123', 'q1w2e3r4', 'password2024', 'password2025', 'password2026', 'pass1234', 'pass12345',
    '11111111a', 'a1234567', 'aa123456', 'abc123456', 'abcd12345', 'mypassword1', 'secret123', 'changeme1', 'default1', 'user1234',
  ].map((s) => s.toLowerCase()),
);

export function isCommonPassword(pw: string): boolean {
  const lower = pw.toLowerCase();
  if (COMMON.has(lower)) return true;
  // Reject trivially repetitive passwords like "aaaaaaa1" or "11111111a".
  if (new Set(lower).size <= 2) return true;
  return false;
}
