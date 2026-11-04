# ResoShare API contract (v1)

Single source of truth shared by the Node backend (`backend/`) and the Android apps (`android/`).
Every change here must be reflected on both sides.

## Conventions

- Base path: `/api/v1`. HTTPS only in production. JSON bodies are UTF-8, keys are camelCase.
- **Money is always an integer number of paisa** (`ratePaisa: 100000` = ৳১,০০০.০০). Never floats.
- Dates: `"YYYY-MM-DD"` in the Asia/Dhaka calendar. Timestamps: ISO-8601 UTC (`"2026-11-04T05:42:00.000Z"`).
- Public IDs are UUID strings. Human numbers: shareholder `RA-SH-00124`, certificate `RA-CERT-00124`,
  order `ORD-000123`, cash receipt `CASH-0142`.
- Auth header: `Authorization: Bearer <accessToken>`. Access tokens live 15 min. Refresh tokens are opaque,
  rotate on every refresh (a reused refresh token revokes the whole session family).
- Request bodies are validated strictly: unknown keys → `400 INVALID_INPUT`.
- Paged lists: `?cursor=<opaque>&limit=<1..50, default 20>` → `{ "items": [...], "nextCursor": "..." | null }`.
- Errors always look like:
  ```json
  { "error": { "code": "INVALID_INPUT", "message": "বাংলায় বার্তা", "fields": { "mobile": "সঠিক মোবাইল নম্বর দিন" } } }
  ```
  `fields` is optional. `message` is always user-presentable Bangla.

| HTTP | code | meaning |
|---|---|---|
| 400 | `INVALID_INPUT` | validation failed |
| 401 | `UNAUTHORIZED` | missing/invalid token or wrong credentials |
| 401 | `TOKEN_EXPIRED` | access token expired → client refreshes |
| 401 | `SESSION_EXPIRED` | refresh token invalid/expired/reused, or staff idle timeout → log out |
| 401 | `MFA_INVALID` | wrong/expired OTP |
| 403 | `FORBIDDEN` | lacks permission |
| 403 | `KYC_REQUIRED` | KYC not verified yet |
| 403 | `ACCOUNT_SUSPENDED` | account suspended |
| 403 | `PASSWORD_CHANGE_REQUIRED` | must change temp password first (only `/me/password`, `/me`, logout allowed) |
| 404 | `NOT_FOUND` | |
| 409 | `CONFLICT` | duplicate mobile/email/NID, version exists, etc. |
| 409 | `RATE_CHANGED` | `expectedRatePaisa` differs from today's rate; `fields.currentRatePaisa` holds the new rate as a string |
| 409 | `INSUFFICIENT_SHARES` | not enough remaining shares |
| 409 | `SALE_CLOSED` | share sale disabled |
| 409 | `TOO_MANY_PENDING` | user already has 3 pending orders |
| 413 | `PAYLOAD_TOO_LARGE` | |
| 423 | `LOCKED` | too many failed logins; `fields.retryAfterSec` |
| 429 | `TOO_MANY_REQUESTS` | rate limited |
| 500 | `SERVER_ERROR` | never leaks internals |

### Validation rules (server is authoritative, app mirrors them)

- `mobile`: Bangladeshi, normalised to `01XXXXXXXXX` (`^01[3-9]\d{8}$`). Bangla digits and `+88`/`88` prefix accepted on input.
- `email`: ≤ 254 chars, simple RFC shape, stored lower-case.
- `password`: 8–128 chars, at least one letter, one digit and one symbol (non-alphanumeric), not in the common-password list.
  (Applies when a password is set or changed; existing passwords keep working.)
- `fullName`, nominee `name`: 3–100 chars, letters (Bangla/Latin), space, `.`, `-`.
- `nidNumber`: 10, 13 or 17 digits. Nominee `idNumber`: `nid` → 10/13/17 digits, `birth_reg` → 17 digits.
- `dob`: valid date, holder age ≥ 18.
- `relation`: 2–30 chars.
- Nominees: 1 or 2; `sharePercent` integers 1–100 summing to exactly 100; `slot` assigned by order.
- KYC images: JPEG/PNG (checked by magic bytes), ≤ 5 MB each; server re-encodes to JPEG, strips metadata, stores AES-256-GCM encrypted.
- `quantity`: 1–10000. Bank `reference`: 3–40 chars `[A-Za-z0-9\-/]`.

## Shared objects

```ts
Tokens        { accessToken: string, refreshToken: string, expiresIn: number /*sec*/ }
Order         { id, orderNo, quantity: int, ratePaisa: long, amountPaisa: long, method: "cash"|"bank",
                reference: string|null, status: "pending"|"confirmed"|"cancelled", note: string|null,
                createdAt, decidedAt: string|null }
AdminOrder    = Order & { shareholderId, shareholderNo, shareholderName }
Nominee       { slot: 1|2, name, relation, idType: "nid"|"birth_reg", idNumberMasked /* "•••••••890" */, sharePercent: int }
NomineeInput  { name, relation, idType: "nid"|"birth_reg", idNumber, sharePercent: int }
Me            { id, shareholderNo, fullName, mobile, email, kycStatus: "pending"|"verified"|"rejected",
                kycNote: string|null, accountStatus: "active"|"suspended", mustChangePassword: bool,
                joinedAt, nominees: Nominee[] }
Pool          { totalShares: int, soldShares: int, remainingShares: int, saleEnabled: bool }
Notice        { id, title, body, category: "rate"|"payment"|"company"|"dividend"|"rules"|"kyc"|"message",
                createdAt, read: bool }
RuleSection   { title: string, items: string[] }
Rules         { version, effectiveDate, sections: RuleSection[] }
```

## Public (no auth)

| Method | Path | Body / Query | Response |
|---|---|---|---|
| GET | `/health` | | `{ "ok": true }` |
| GET | `/public/info` | | `{ companyName, hotline, email, officeAddress, todayRatePaisa, dayChangePaisa }` (v2: today's rate for the login screen; change vs yesterday) |
| GET | `/rules` | | `Rules` (current published version; 404 if never published). Public because registration links to it. |
| GET | `/verify/:certificateNo?s=<sig>` (path is **outside** `/api/v1`, i.e. `GET /verify/RA-CERT-00124?s=...`) | | HTML page (Bangla) saying valid / invalid, holder name, shares, issue date |

## Shareholder (user app)

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/auth/register` | **multipart**: part `data` (JSON string, below) + files `nidFront`, `nidBack`, `photo` | `201 { shareholderNo }` |
| POST | `/auth/login` | `{ identifier /*mobile or email*/, password, deviceId }` | `{ tokens: Tokens, me: Me }` |
| POST | `/auth/refresh` | `{ refreshToken }` | `Tokens` |
| POST | `/auth/logout` | `{ refreshToken }` | `204` |
| GET | `/me` | | `Me` |
| POST | `/me/password` | `{ currentPassword, newPassword }` | `204` (revokes all other sessions) |
| GET | `/me/dashboard` | | `Dashboard` |
| GET | `/me/orders` | paged | `{ items: Order[], nextCursor }` |
| POST | `/me/orders` | `{ quantity, method, reference: string|null, expectedRatePaisa, acceptRules: true }` | `201 Order` |
| POST | `/me/orders/:id/cancel` | | `Order` (own pending orders only) |
| GET | `/me/certificate` | | `Certificate` (404 if 0 shares) |
| PUT | `/me/nominees` | `{ nominees: NomineeInput[] }` | `Nominee[]` |
| POST | `/me/kyc` | multipart: `data` = `{ nidNumber, dob }` + `nidFront`, `nidBack`, `photo` | `204` (only when kycStatus = rejected) |
| GET | `/me/payment-info` | | `PaymentInfo` |
| GET | `/notices` | paged | `{ items: Notice[], nextCursor, unread: int }` |
| POST | `/notices/:id/read` | | `204` |
| POST | `/notices/read-all` | | `204` |
| GET | `/market/chart?period=daily|weekly|monthly|yearly` | | `Chart` |

```ts
RegisterData  { fullName, mobile, email, password, nidNumber, dob, nominees: NomineeInput[], acceptRules: true }
Dashboard     { me: Me,
                today: { date, ratePaisa, prevRatePaisa: long|null },
                holding: { shares: int, paidPaisa, valuePaisa /*shares × today's rate*/, profitPaisa /*value − paid*/,
                           profitBp: int /*profit/paid in basis points, 0 if paid = 0*/ },
                pool: Pool, unreadNotices: int, recentOrders: Order[] /*max 3, newest first*/ }
Certificate   { certificateNo, shareholderNo, fullName, shares, issuedOn, verifyUrl, companyName }
PaymentInfo   { cashEnabled, bankEnabled, officeAddress, bankName, accountName, accountNumber }
Chart         { period, points: { date, ratePaisa }[] /*ascending*/, todayRatePaisa, dayChangePaisa,
                periodChangePaisa, periodChangeBp, highPaisa, lowPaisa, monthChangePaisa, myShares }
```

Chart periods: `daily` = last 7 days (one point per day), `weekly` = last 8 weeks (rate at each week end, last = today),
`monthly` = last 12 months (rate at each month end, last = today), `yearly` = last 5 years. The rate on a day is the rate
row for that date, else the most recent earlier row. `monthChangePaisa` = today's rate − rate on the last day of the
previous month.

Business rules:
- Login is allowed while KYC is pending/rejected; buying requires `kycStatus = verified` and `accountStatus = active`.
- Order price = today's rate (Asia/Dhaka) at creation; `expectedRatePaisa` guards against a midnight rate change.
- Cash orders get `reference = "CASH-####"` generated by server; bank orders require the user's transfer reference.
- Max 3 pending orders per user. Quantity must be ≤ remaining shares and sale must be enabled.
- Shares are only allocated when an admin approves the payment (`confirmed`). Remaining = total − confirmed sold.
- Certificate number reuses the shareholder number (`RA-SH-00124` → `RA-CERT-00124`); `issuedOn` = date of the latest
  confirmed order; `verifyUrl` = `${PUBLIC_BASE_URL}/verify/<certNo>?s=<HMAC-SHA256(certNo|shares|issuedOn) hex, first 20 chars>`.
  When holdings change the old QR stops verifying.

## Admin (admin app)

Admin login is two-step: password, then TOTP (RFC 6238, 6 digits, 30 s, ±1 step, no replay).
Staff who have not enrolled get an enrollment secret on the first step and confirm it with the first code.

| Method | Path | Body | Response | Permission |
|---|---|---|---|---|
| POST | `/admin/auth/login` | `{ email, password, deviceId }` | `{ mfaToken, enrollment: { secret, otpauthUri } | null }` | – |
| POST | `/admin/auth/mfa` | `{ mfaToken, code }` | `{ tokens: Tokens, staff: StaffMe }` | – |
| POST | `/admin/auth/refresh` | `{ refreshToken }` | `Tokens` | – |
| POST | `/admin/auth/logout` | `{ refreshToken }` | `204` | – |
| GET | `/admin/me` | | `StaffMe` | any |
| POST | `/admin/me/password` | `{ currentPassword, newPassword }` | `204` | any |
| GET | `/admin/dashboard` | | `AdminDashboard` | view |
| GET | `/admin/stats/sales?period=` | | `SalesStats` | view |
| GET | `/admin/shareholders?q=&status=all|verified|pending|suspended` | paged | `{ items: ShareholderRow[], total, nextCursor, counts: { all, verified, pending, suspended } }` (v2: counts ignore `q` and `status`) | view |
| GET | `/admin/shareholders/:id/certificate` | | `Certificate` (same as `/me/certificate`; 404 if 0 shares) | view |
| GET | `/admin/shareholders/:id` | | `ShareholderDetail` | view |
| GET | `/admin/shareholders/:id/contact` | | `{ mobile, email }` (audited) | view |
| POST | `/admin/shareholders/:id/suspend` | `{ reason }` | `204` | settings_staff |
| POST | `/admin/shareholders/:id/activate` | | `204` | settings_staff |
| POST | `/admin/shareholders/:id/reset-password` | | `{ tempPassword }` | settings_staff |
| POST | `/admin/shareholders/:id/message` | `{ title, body }` | `204` | notices_rules |
| GET | `/admin/kyc-files/:fileId` | | `image/jpeg` bytes, `Cache-Control: no-store` (audited) | kyc_approve |
| GET | `/admin/approvals` | | `{ payments: AdminOrder[], kyc: KycRequest[] }` | view |
| POST | `/admin/orders/:id/approve` | | `AdminOrder` | payment_approve |
| POST | `/admin/orders/:id/reject` | `{ reason }` | `AdminOrder` | payment_approve |
| POST | `/admin/kyc/:shareholderId/approve` | | `204` | kyc_approve |
| POST | `/admin/kyc/:shareholderId/reject` | `{ reason }` | `204` | kyc_approve |
| GET | `/admin/orders?status=all|confirmed|pending|cancelled&q=` | paged | `{ items: AdminOrder[], nextCursor, todayTotalPaisa, monthTotalPaisa }` | view |
| GET | `/admin/rates` | | `RatesAdmin` | view |
| PUT | `/admin/rates/schedule` | `{ items: { date, ratePaisa }[], notify: bool }` | `RatesAdmin` | rate_change |
| GET | `/admin/pool` | | `Pool` | view |
| PUT | `/admin/pool` | `{ saleEnabled }` | `Pool` | rate_change |
| POST | `/admin/pool/issue` | `{ additionalShares }` | `Pool` | settings_staff |
| GET | `/admin/dividends` | | `{ items: Dividend[], soldShares }` | view |
| POST | `/admin/dividends` | `{ fiscalYear, perSharePaisa, distributionDate }` | `Dividend` | dividend |
| GET | `/admin/reports/:type?from=&to=` | type = `sales|shareholders|transactions|dividends|kyc` | `Report` | reports |
| GET | `/admin/notices` | paged | `{ items: SentNotice[], nextCursor }` | view |
| POST | `/admin/notices` | `{ title, body, audience: "all"|"selected", userIds: string[] }` | `SentNotice` | notices_rules |
| GET | `/admin/rules` | | `RulesAdmin` | view |
| PUT | `/admin/rules/draft` | `{ sections: RuleSection[] }` | `RulesAdmin` | notices_rules |
| POST | `/admin/rules/publish` | `{ version, effectiveDate, summary, sections, notify }` | `RulesAdmin` | notices_rules |
| GET | `/admin/staff` | | `{ items: Staff[], roles: Role[] }` | settings_staff |
| POST | `/admin/staff` | `{ name, email, mobile, roleId }` | `{ staff: Staff, tempPassword }` | settings_staff |
| PATCH | `/admin/staff/:id` | `{ roleId?, status?: "active"|"disabled" }` | `Staff` | settings_staff |
| POST | `/admin/staff/:id/reset` | | `{ tempPassword }` (also clears TOTP) | settings_staff |
| POST | `/admin/staff/:id/unlock` | | `204` (clears login throttles on all IPs; non-super admins only for staff whose permissions they all hold) | settings_staff |
| POST | `/admin/roles` | `{ name, permissions: string[] }` | `Role` | settings_staff |
| PATCH | `/admin/roles/:id` | `{ name?, permissions? }` | `Role` (system roles immutable) | settings_staff |
| GET | `/admin/audit?category=` | paged | `{ items: AuditEntry[], nextCursor }` | settings_staff |
| GET | `/admin/backup` | | `BackupStatus` | settings_staff |
| POST | `/admin/backup` | | `202 BackupStatus` (requests an immediate backup; max 1 request per 10 min → 429; audited) | settings_staff |
| GET | `/admin/settings` | | `Settings` | view |
| PUT | `/admin/settings` | `Settings` (without readonly fields) | `Settings` | settings_staff |

```ts
StaffMe        { id, name, email, roleKey, roleName, permissions: string[], mustChangePassword: bool }
AdminDashboard { today: { date, ratePaisa }, pool: Pool, shareholderCount: int,
                 pending: { payments: int, kyc: int }, recentOrders: AdminOrder[] /*max 5*/ }
SalesStats     { period, points: { date /*bucket start*/, shares: int, amountPaisa }[] /*ascending; daily 7, weekly 8,
                 monthly 12, yearly 5*/, totalShares, totalAmountPaisa, lastChangeShares /*last − previous bucket*/ }
ShareholderRow { id, shareholderNo, fullName, shares: int, kycStatus, accountStatus }
BackupStatus   { lastAt: string|null /*last finished backup*/, status: "ok"|"failed"|"running"|"never",
                 sizeBytes: long|null, keepDays: int, requestedAt: string|null /*pending manual request*/ }
ShareholderDetail { id, shareholderNo, fullName, mobileMasked, emailMasked, kycStatus, kycNote, accountStatus,
                 joinedAt, dob, nidMasked, shares, paidPaisa, valuePaisa, nominees: Nominee[], orders: Order[],
                 kycFiles: { id, kind: "nid_front"|"nid_back"|"photo" }[] }
KycRequest     { shareholderId, shareholderNo, fullName, submittedAt, nidMasked, dob, files: { id, kind }[] }
RatesAdmin     { today: { date, ratePaisa }, tomorrow: { date, ratePaisa },
                 schedule: { date, ratePaisa: long|null }[] /*exactly 15: tomorrow … today+15*/,
                 history: { date, ratePaisa, changePaisa, setByName: string|null }[] /*last 30 dates ≤ today, newest first*/ }
Dividend       { id, fiscalYear, perSharePaisa, distributionDate, totalShares, totalPaisa,
                 status: "declared"|"distributed", createdAt }
Report         { type, title, columns: string[], rows: string[][], generatedAt }
SentNotice     { id, title, body, category, audience: "all"|"selected"|"user", recipientCount, createdAt, byName }
RulesVersion   { id, version, effectiveDate, summary, status: "draft"|"published"|"archived", sections: RuleSection[],
                 updatedAt, byName }
RulesAdmin     { current: RulesVersion|null, draft: RulesVersion|null, history: RulesVersion[] /*no sections needed*/ }
Staff          { id, name, email, mobile, roleId, roleKey, roleName, status: "active"|"disabled",
                 lastActiveAt: string|null, mfaEnrolled: bool }
Role           { id, key, name, permissions: string[], isSystem: bool }
AuditEntry     { id, at, actorName, category: "rate"|"approval"|"login"|"settings"|"staff"|"shareholder"|"content",
                 message /*Bangla*/, tone: "info"|"ok"|"bad"|"warn" }
Settings       { company: { name, email, hotline },
                 payment: { cashEnabled, bankEnabled, officeAddress, bankName, accountName, accountNumber },
                 security: { autoLogoutMinutes /*3..60*/, mfaRequired: true /*readonly*/ } }
```

Permissions: `view`, `kyc_approve`, `payment_approve`, `rate_change`, `dividend`, `notices_rules`, `reports`,
`settings_staff`. System roles: `super_admin` (all), `accounts` (view, payment_approve, reports),
`kyc_officer` (view, kyc_approve), `support` (view).

Admin business rules:
- Rate schedule: only dates in `(today, today+15]` may be set; `ratePaisa` 1 … 10,000,000,000. Today's rate is never
  editable (it is what open orders were priced at). The rate for a date takes effect at 00:00 Asia/Dhaka.
  With `notify = true`, each scheduled date whose rate differs from the previous day posts an app notice
  (category `rate`) at 00:00 of that date. Buy rate = sell rate.
- Approving an order locks the pool row and fails with `INSUFFICIENT_SHARES` if remaining < quantity.
  Confirming posts a `payment` notice to that shareholder; rejecting posts one with the reason.
- KYC approve/reject posts a `kyc` notice to the shareholder.
- Dividend `fiscalYear` is 4 digits and unique; total = sold shares × per-share; payouts are recorded per holder;
  a `dividend` notice goes to all. `status` = `distributed` once `distributionDate` ≤ today.
- Rules publish: `version` must be numerically greater than the current version; publishing archives the previous
  current version and clears the draft; `notify` posts a `rules` notice to all.
- Every state-changing admin action and every login (success or failure) is written to the append-only,
  hash-chained audit log.
- Staff `autoLogoutMinutes`: a staff session idle (no API call) longer than this gets `SESSION_EXPIRED`.
