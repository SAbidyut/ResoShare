# ResoShare API (backend)

Node 22 + TypeScript + Fastify 5 + PostgreSQL 16. Implements `../docs/API.md` for the
shareholder app and the admin app. Every business rule and every security check lives here;
the apps are only clients.

## Security design (summary)

| Area | What is done |
|---|---|
| Passwords | argon2id (64 MiB, t=3); policy: 8–128 chars, letter + digit, common-password list. |
| Login | Same error for unknown user / wrong password (dummy hash for equal timing). 5 failures → lock 15 min, doubling per lock (max 24 h). Per-IP rate limits on login/register/refresh/verify + global 300/min. |
| Tokens | 15-min HS256 access JWT bound to a server-side session (`sid`) and `token_version`, checked on **every** request (revocation is instant). Opaque 256-bit refresh tokens stored as SHA-256, rotated on every use; reuse revokes the whole session. |
| Admin 2FA | TOTP (RFC 6238) mandatory for every staff member, enrolled at first login; single-use 5-minute challenge, max 5 tries, no code replay. Staff idle timeout (settings, default 10 min). Temp passwords must be changed first. |
| RBAC | 8 permissions, checked per route. Nobody can grant a permission they don't hold, edit themselves, touch a super admin unless super admin, or edit system roles. |
| PII | NID / nominee ID numbers AES-256-GCM encrypted + HMAC blind index for uniqueness. KYC images: magic-byte check, re-encoded to JPEG (metadata stripped), encrypted on disk, served only to `kyc_approve` with `no-store` and an audit entry. Mobile/email masked in admin views and exports; full contact reveal is audited. |
| Integrity | Money is BIGINT paisa. Order approval locks the share pool row (no overselling). Prices are locked at order time with a `RATE_CHANGED` guard. Duplicate bank references are rejected. |
| Audit | Append-only `audit_log` (DB trigger blocks UPDATE/DELETE/TRUNCATE) with a SHA-256 hash chain (`verifyAuditChain`). |
| Certificates | QR → `/verify/RA-CERT-xxxxx?s=<HMAC>`; the signature covers number + shares + issue date, so old/forged QR codes fail. Page is escaped and served with a hash-based CSP. |
| HTTP | Strict zod schemas (unknown keys rejected), prototype-poisoning-safe JSON, 64 KB JSON limit, 5 MB/file, helmet headers + HSTS, CORS closed, `Cache-Control: no-store`, no stack traces in responses, log redaction of secrets/PII. |
| Ops | Refuses to boot with missing/weak secrets or http `PUBLIC_BASE_URL`. Non-root, read-only container, no capabilities; DB on an internal network only. |

## Deploy (Docker, recommended)

Requirements: a Linux VPS with Docker, and a DNS **A record** for your API domain (e.g. `share.rapti.net`)
pointing to it. Ports 80/443 open.

```bash
git clone <repo> && cd ResoShare/backend
cp .env.example .env
node scripts/gen-secrets.mjs          # paste the 4 values into .env, set DOMAIN
docker compose up -d --build          # Caddy fetches the HTTPS certificate automatically
docker compose logs -f api            # migrations run automatically on start

# create the first super admin (prints a one-time temporary password)
docker compose exec api node dist/src/cli/create-admin.js --name "ডি এম সাইফুল ইসলাম" --email admin@rapti.net --mobile 01XXXXXXXXX
```

Then open the **admin app**, log in with that email + temporary password, scan the QR with an
authenticator app (Google Authenticator, Microsoft Authenticator, …), enter the 6-digit code and
set a new password. Then fill **সেটিংস → পেমেন্ট মাধ্যম** (office address, bank details).

The Android apps must be built with `RESO_API_BASE_URL=https://<DOMAIN>/` in `android/gradle.properties`
(the app adds `api/v1/…` itself).

**Back up** `.env` (especially `DATA_ENC_KEY` and `HMAC_KEY`) and the `dbdata` + `uploads`
volumes. Without the keys, encrypted NIDs/KYC images cannot be read and certificate QRs stop verifying.

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
npm run dev
```

Scripts: `build`, `start`, `migrate`, `create-admin`, `gen-secrets`, `test`, `typecheck`.

## Tests

Integration tests run against a real PostgreSQL (`TEST_DATABASE_URL`, default
`postgres://postgres@127.0.0.1:54329/resoshare_test`; the database is dropped and recreated).

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
src/jobs/scheduler.ts    midnight rate notices, cleanup
migrations/*.sql         schema + seed (roles, pool 10,000 shares, ৳১,০০০.০০ rate, rules v1.0)
```
