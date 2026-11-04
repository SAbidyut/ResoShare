// Prints freshly generated secrets for .env. Run: npm run gen-secrets
import { randomBytes } from 'node:crypto';

const b64url = (n) => randomBytes(n).toString('base64url');

console.log('# Paste into backend/.env (never commit this file)');
console.log(`JWT_SECRET=${b64url(48)}`);
console.log(`HMAC_KEY=${b64url(48)}`);
console.log(`DATA_ENC_KEY=${randomBytes(32).toString('base64')}`);
console.log(`POSTGRES_PASSWORD=${b64url(24)}`);
console.log(`POSTGRES_APP_PASSWORD=${b64url(24)}`);
console.log('');
console.log('# Back up DATA_ENC_KEY and HMAC_KEY safely: without them encrypted NID numbers,');
console.log('# KYC images, certificate QR signatures and the audit-log hash chain cannot be read or verified.');
