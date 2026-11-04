-- Security hardening (audit round 1).

-- M1: one live order per bank transfer reference, enforced by the database (race-free across accounts).
CREATE UNIQUE INDEX orders_bank_ref_unique ON orders (lower(reference))
  WHERE method = 'bank' AND status <> 'cancelled';

-- L5: an NID only blocks other accounts while its KYC is pending or verified (no squatting with a rejected account).
ALTER TABLE shareholders DROP CONSTRAINT shareholders_nid_hash_key;
CREATE UNIQUE INDEX shareholders_nid_active_unique ON shareholders (nid_hash) WHERE kyc_status <> 'rejected';
CREATE INDEX shareholders_nid_hash_idx ON shareholders (nid_hash);

-- M2/L1: login throttling per (account, client IP) instead of a global account lock.
CREATE TABLE login_throttle (
  subject_type text NOT NULL CHECK (subject_type IN ('user','staff')),
  subject_id   uuid NOT NULL,
  ip           text NOT NULL,
  failures     integer NOT NULL DEFAULT 0,
  lock_count   integer NOT NULL DEFAULT 0,
  locked_until timestamptz,
  window_start timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (subject_type, subject_id, ip)
);

-- Account-wide failure log for the non-escalating global safety cap.
CREATE TABLE login_failures (
  id           bigserial PRIMARY KEY,
  subject_type text NOT NULL CHECK (subject_type IN ('user','staff')),
  subject_id   uuid NOT NULL,
  ip           text NOT NULL,
  at           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX login_failures_subject_idx ON login_failures (subject_type, subject_id, at);

-- The old per-account counters are no longer used for locking.
UPDATE shareholders SET failed_logins = 0, lock_count = 0, locked_until = NULL;
UPDATE staff SET failed_logins = 0, lock_count = 0, locked_until = NULL;
