# ResoShare API (backend)

Node 22 + TypeScript + Fastify 5 + PostgreSQL 16. Implements `../docs/API.md` for the
shareholder app and the admin app. Every business rule and every security check lives here;
the apps are only clients.

## Security design (summary)

| Area | What is done |
|---|---|
| Passwords | argon2id (64 MiB, t=3); policy: 8–128 chars, letter + digit, common-password list. |
| Login | One generic 401 for unknown user, wrong password **and** throttled attempts (dummy hash for equal timing). Throttling per (account, client IP): 5 failures → that IP is locked 15 min, doubling, max 1 h — other IPs (the real owner) are unaffected. Non-escalating account-wide cap: ≥30 failures/h from all IPs → 15 min. Attempts are reserved atomically before verification (bursts can't exceed the limit). Staff counters reset only after a correct OTP. `POST /admin/staff/:id/unlock` clears locks. Per-IP rate limits on login/register/refresh/verify + global 300/min. Registration duplicates get one generic message (no field named). |
| Tokens | 15-min HS256 access JWT bound to a server-side session (`sid`) and `token_version`, checked on **every** request (revocation is instant). Opaque 256-bit refresh tokens stored as SHA-256, rotated on every use; reuse revokes the whole session. |
| Admin 2FA | TOTP (RFC 6238) mandatory for every staff member, enrolled at first login; single-use 5-minute challenge, max 5 tries, no code replay. Staff idle timeout (settings, default 10 min). Temp passwords must be changed first. |
| RBAC | 8 permissions, checked per route. Nobody can grant a permission they don't hold, edit themselves, or edit system roles. Changing/disabling/resetting/unlocking another staff member requires super admin or holding **every** permission of that member's role. Changes that could remove the last super admin are serialized. Payment details (bank account, office address) can only be changed by a super admin and trigger an app notice to all shareholders. |
| PII | NID / nominee ID numbers AES-256-GCM encrypted + HMAC blind index for uniqueness. KYC images: magic-byte check, re-encoded to JPEG (metadata stripped), encrypted on disk, served only to `kyc_approve` with `no-store` and an audit entry. Mobile/email masked in admin views and exports; full contact reveal is audited; search by mobile/email only matches the exact full value and is audited. An NID only blocks other accounts while its KYC is pending/verified. AES-GCM tags are length-checked. |
| Integrity | Money is BIGINT paisa. Order approval locks the share pool row (no overselling). Prices are locked at order time with a `RATE_CHANGED` guard. A bank transfer reference can back only one live order (unique index + re-check at approval). A shareholder is notified when the office resets their password. |
| Audit | Append-only `audit_log`: the API's DB role has only SELECT/INSERT on it, and a trigger blocks UPDATE/DELETE/TRUNCATE for everyone else. Each row is chained with HMAC-SHA256 under an HKDF subkey of `HMAC_KEY` that never touches the DB, so DB access alone cannot forge the chain. The chain is re-verified at boot and daily; a mismatch logs an error event `audit_chain_mismatch`. |
| Certificates | QR → `/verify/RA-CERT-xxxxx?s=<HMAC>`; the signature covers number + shares + issue date, so old/forged QR codes fail. Page is escaped and served with a hash-based CSP. |
| HTTP | Strict zod schemas (unknown keys rejected), prototype-poisoning-safe JSON, 64 KB JSON limit, 5 MB/file, helmet headers + HSTS, CORS closed, `Cache-Control: no-store`, no stack traces in responses, log redaction of secrets/PII. |
| Ops | Refuses to boot with missing/weak secrets, http `PUBLIC_BASE_URL`, a pending migration, or (in production) a superuser/owner DB login. The API connects as a restricted runtime role (`resoshare_app`: no superuser, owns nothing); migrations run in a separate one-shot job with the owner credentials, which the API container never receives. Non-root, read-only containers with all capabilities dropped (Caddy keeps only `NET_BIND_SERVICE`); DB on an internal network only. |

## Deploy (Docker, recommended)

Requirements: a Linux VPS with Docker, and a DNS **A record** for your API domain (e.g. `share.rapti.net`)
pointing to it. Ports 80/443 open.

```bash
git clone <repo> && cd ResoShare/backend
cp .env.example .env
node scripts/gen-secrets.mjs          # paste the 5 values into .env, set DOMAIN
docker compose up -d --build          # `migrate` runs once (owner role), then `api` starts as resoshare_app
docker compose logs migrate api       # Caddy fetches the HTTPS certificate automatically

# create the first super admin (prints a one-time temporary password)
docker compose exec api node dist/src/cli/create-admin.js --name "ডি এম সাইফুল ইসলাম" --email admin@rapti.net --mobile 01XXXXXXXXX
```

Then open the **admin app**, log in with that email + temporary password, scan the QR with an
authenticator app (Google Authenticator, Microsoft Authenticator, …), enter the 6-digit code and
set a new password. Then fill **সেটিংস → পেমেন্ট মাধ্যম** (office address, bank details).

The Android apps must be built with `RESO_API_BASE_URL=https://<DOMAIN>/` in `android/gradle.properties`
(the app adds `api/v1/…` itself).

**Back up** `.env` (especially `DATA_ENC_KEY` and `HMAC_KEY`) and the `dbdata` + `uploads`
volumes. Without the keys, encrypted NIDs/KYC images cannot be read, certificate QRs stop verifying and
the audit-log chain cannot be checked.

After pulling a new version run `docker compose up -d --build` again: the `migrate` job applies new
migrations and refreshes the runtime role's grants before the API starts.

```bash
docker compose exec db pg_dump -U resoshare resoshare | gzip > backup-$(date +%F).sql.gz
```

Keep the API hostname **DNS-only (grey cloud)** in Cloudflare so Caddy can obtain and renew its own
certificate and per-IP rate limits see real client IPs. If you do put Cloudflare's proxy (orange cloud) in
front, configure Caddy `trusted_proxies` with the Cloudflare IP ranges and `client_ip_headers CF-Connecting-IP`,
otherwise every request appears to come from a Cloudflare IP.

## Local development

```bash
npm ci
cp .env.example .env    # fill secrets + DATABASE_URL, NODE_ENV=development, PUBLIC_BASE_URL=http://localhost:3000, UPLOAD_DIR=/abs/path
npm run dev             # in development the API migrates itself with DATABASE_URL
```

Scripts: `build`, `start`, `migrate`, `create-admin`, `gen-secrets`, `test`, `typecheck`.

## Tests

Integration tests run against a real PostgreSQL (`TEST_DATABASE_URL` = owner/superuser URL, default
`postgres://postgres@127.0.0.1:54329/resoshare_test`; the database is dropped and recreated). The
suite migrates as the owner and then runs the API as the restricted runtime role `resoshare_test_app`,
so missing grants show up as test failures.

```bash
npm test
```

## Layout

```
src/app.ts               Fastify setup: helmet, rate limit, multipart, error envelope
src/config.ts            env validation (refuses weak secrets)
src/routes/              public, user, admin/* route modules
src/services/            auth/sessions, audit chain, notices, holdings, chart, files (KYC)
src/util/                crypto (AES-GCM, HMAC), TOTP, validation, Bangla formatting
src/jobs/scheduler.ts    midnight rate notices, cleanup, audit-chain verification
src/services/throttle.ts per-(account, IP) login throttling + account-wide cap
src/cli/migrate.ts       one-shot migrate job (owner creds) + runtime role grants
migrations/*.sql         schema + seed (roles, pool 10,000 shares, ৳১,০০০.০০ rate, rules v1.0)
```
