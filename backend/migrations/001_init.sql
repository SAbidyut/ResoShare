-- ResoShare schema v1. All money columns are BIGINT paisa.

CREATE SEQUENCE shareholder_no_seq START 101;
CREATE SEQUENCE order_no_seq START 1;
CREATE SEQUENCE cash_ref_seq START 1;

CREATE TABLE roles (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  key         text NOT NULL UNIQUE CHECK (key ~ '^[a-z0-9_]{2,40}$'),
  name        text NOT NULL CHECK (char_length(name) BETWEEN 2 AND 60),
  permissions text[] NOT NULL DEFAULT '{}',
  is_system   boolean NOT NULL DEFAULT false,
  created_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT roles_permissions_valid CHECK (
    permissions <@ ARRAY['view','kyc_approve','payment_approve','rate_change','dividend','notices_rules','reports','settings_staff']::text[]
  )
);

CREATE TABLE staff (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name                 text NOT NULL,
  email                text NOT NULL UNIQUE,
  mobile               text NOT NULL,
  password_hash        text NOT NULL,
  role_id              uuid NOT NULL REFERENCES roles(id),
  status               text NOT NULL DEFAULT 'active' CHECK (status IN ('active','disabled')),
  totp_secret_enc      text,
  totp_enabled         boolean NOT NULL DEFAULT false,
  totp_last_step       bigint,
  must_change_password boolean NOT NULL DEFAULT true,
  token_version        integer NOT NULL DEFAULT 0,
  failed_logins        integer NOT NULL DEFAULT 0,
  lock_count           integer NOT NULL DEFAULT 0,
  locked_until         timestamptz,
  last_active_at       timestamptz,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE shareholders (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  shareholder_seq      integer NOT NULL UNIQUE DEFAULT nextval('shareholder_no_seq'),
  full_name            text NOT NULL,
  mobile               text NOT NULL UNIQUE,
  email                text NOT NULL UNIQUE,
  password_hash        text NOT NULL,
  nid_enc              text NOT NULL,
  nid_hash             text NOT NULL UNIQUE,
  nid_last3            text NOT NULL,
  nid_len              smallint NOT NULL,
  dob                  date NOT NULL,
  kyc_status           text NOT NULL DEFAULT 'pending' CHECK (kyc_status IN ('pending','verified','rejected')),
  kyc_note             text,
  kyc_submitted_at     timestamptz NOT NULL DEFAULT now(),
  kyc_decided_at       timestamptz,
  kyc_decided_by       uuid REFERENCES staff(id),
  account_status       text NOT NULL DEFAULT 'active' CHECK (account_status IN ('active','suspended')),
  suspend_reason       text,
  must_change_password boolean NOT NULL DEFAULT false,
  token_version        integer NOT NULL DEFAULT 0,
  failed_logins        integer NOT NULL DEFAULT 0,
  lock_count           integer NOT NULL DEFAULT 0,
  locked_until         timestamptz,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX shareholders_created_idx ON shareholders (created_at DESC, id DESC);
CREATE INDEX shareholders_kyc_idx ON shareholders (kyc_status, kyc_submitted_at);

CREATE TABLE nominees (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  shareholder_id uuid NOT NULL REFERENCES shareholders(id) ON DELETE CASCADE,
  slot           smallint NOT NULL CHECK (slot IN (1,2)),
  name           text NOT NULL,
  relation       text NOT NULL,
  id_type        text NOT NULL CHECK (id_type IN ('nid','birth_reg')),
  id_number_enc  text NOT NULL,
  id_last3       text NOT NULL,
  id_len         smallint NOT NULL,
  share_percent  smallint NOT NULL CHECK (share_percent BETWEEN 1 AND 100),
  UNIQUE (shareholder_id, slot)
);

CREATE TABLE kyc_files (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  shareholder_id uuid NOT NULL REFERENCES shareholders(id) ON DELETE CASCADE,
  kind           text NOT NULL CHECK (kind IN ('nid_front','nid_back','photo')),
  storage_name   text NOT NULL UNIQUE CHECK (storage_name ~ '^[a-f0-9]{48}\.bin$'),
  size_bytes     integer NOT NULL,
  active         boolean NOT NULL DEFAULT true,
  created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX kyc_files_holder_idx ON kyc_files (shareholder_id) WHERE active;

-- Sessions = a login on one device. Refresh tokens rotate inside a session (= token family).
CREATE TABLE sessions (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  subject_type   text NOT NULL CHECK (subject_type IN ('user','staff')),
  subject_id     uuid NOT NULL,
  device_id      text,
  ip             text,
  user_agent     text,
  created_at     timestamptz NOT NULL DEFAULT now(),
  last_active_at timestamptz NOT NULL DEFAULT now(),
  expires_at     timestamptz NOT NULL,
  revoked_at     timestamptz,
  revoke_reason  text
);
CREATE INDEX sessions_subject_idx ON sessions (subject_type, subject_id) WHERE revoked_at IS NULL;

CREATE TABLE refresh_tokens (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id  uuid NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  token_hash  text NOT NULL UNIQUE,
  created_at  timestamptz NOT NULL DEFAULT now(),
  used_at     timestamptz,
  expires_at  timestamptz NOT NULL
);
CREATE INDEX refresh_tokens_session_idx ON refresh_tokens (session_id);

CREATE TABLE mfa_challenges (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  staff_id           uuid NOT NULL REFERENCES staff(id) ON DELETE CASCADE,
  pending_secret_enc text,
  device_id          text,
  attempts           integer NOT NULL DEFAULT 0,
  created_at         timestamptz NOT NULL DEFAULT now(),
  expires_at         timestamptz NOT NULL,
  used_at            timestamptz
);

CREATE TABLE share_pool (
  id           smallint PRIMARY KEY CHECK (id = 1),
  total_shares integer NOT NULL CHECK (total_shares >= 0),
  sale_enabled boolean NOT NULL DEFAULT true,
  updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE rates (
  rate_date     date PRIMARY KEY,
  rate_paisa    bigint NOT NULL CHECK (rate_paisa BETWEEN 1 AND 10000000000),
  notify        boolean NOT NULL DEFAULT false,
  notice_posted boolean NOT NULL DEFAULT false,
  set_by        uuid REFERENCES staff(id),
  set_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE orders (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  order_seq      bigint NOT NULL UNIQUE DEFAULT nextval('order_no_seq'),
  shareholder_id uuid NOT NULL REFERENCES shareholders(id),
  quantity       integer NOT NULL CHECK (quantity BETWEEN 1 AND 10000),
  rate_paisa     bigint NOT NULL CHECK (rate_paisa > 0),
  amount_paisa   bigint NOT NULL CHECK (amount_paisa = rate_paisa * quantity),
  method         text NOT NULL CHECK (method IN ('cash','bank')),
  reference      text,
  status         text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','confirmed','cancelled')),
  note           text,
  created_at     timestamptz NOT NULL DEFAULT now(),
  decided_at     timestamptz,
  decided_by     uuid REFERENCES staff(id)
);
CREATE INDEX orders_holder_idx ON orders (shareholder_id, created_at DESC, id DESC);
CREATE INDEX orders_holder_status_idx ON orders (shareholder_id, status);
CREATE INDEX orders_created_idx ON orders (created_at DESC, id DESC);
CREATE INDEX orders_status_idx ON orders (status, created_at);
CREATE INDEX orders_decided_idx ON orders (decided_at) WHERE status = 'confirmed';

CREATE TABLE notices (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  title           text NOT NULL,
  body            text NOT NULL,
  category        text NOT NULL CHECK (category IN ('rate','payment','company','dividend','rules','kyc','message')),
  audience        text NOT NULL CHECK (audience IN ('all','selected','user')),
  recipient_count integer NOT NULL DEFAULT 0,
  created_by      uuid REFERENCES staff(id),
  created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX notices_created_idx ON notices (created_at DESC, id DESC);

CREATE TABLE notice_recipients (
  notice_id      uuid NOT NULL REFERENCES notices(id) ON DELETE CASCADE,
  shareholder_id uuid NOT NULL REFERENCES shareholders(id) ON DELETE CASCADE,
  PRIMARY KEY (notice_id, shareholder_id)
);
CREATE INDEX notice_recipients_holder_idx ON notice_recipients (shareholder_id);

CREATE TABLE notice_reads (
  notice_id      uuid NOT NULL REFERENCES notices(id) ON DELETE CASCADE,
  shareholder_id uuid NOT NULL REFERENCES shareholders(id) ON DELETE CASCADE,
  read_at        timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (notice_id, shareholder_id)
);

CREATE TABLE rules_versions (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  version        text,
  effective_date date,
  summary        text NOT NULL DEFAULT '',
  status         text NOT NULL CHECK (status IN ('draft','published','archived')),
  sections       jsonb NOT NULL,
  created_by     uuid REFERENCES staff(id),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  published_at   timestamptz
);
CREATE UNIQUE INDEX rules_one_draft ON rules_versions ((status)) WHERE status = 'draft';
CREATE UNIQUE INDEX rules_one_current ON rules_versions ((status)) WHERE status = 'published';
CREATE UNIQUE INDEX rules_version_unique ON rules_versions (version) WHERE status <> 'draft';

CREATE TABLE dividends (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  fiscal_year       text NOT NULL UNIQUE CHECK (fiscal_year ~ '^\d{4}$'),
  per_share_paisa   bigint NOT NULL CHECK (per_share_paisa > 0),
  distribution_date date NOT NULL,
  total_shares      integer NOT NULL,
  total_paisa       bigint NOT NULL,
  created_by        uuid REFERENCES staff(id),
  created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE dividend_payouts (
  dividend_id    uuid NOT NULL REFERENCES dividends(id) ON DELETE CASCADE,
  shareholder_id uuid NOT NULL REFERENCES shareholders(id),
  shares         integer NOT NULL,
  amount_paisa   bigint NOT NULL,
  PRIMARY KEY (dividend_id, shareholder_id)
);

CREATE TABLE settings (
  key        text PRIMARY KEY,
  value      jsonb NOT NULL,
  updated_by uuid REFERENCES staff(id),
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- Append-only, hash-chained audit log.
CREATE TABLE audit_log (
  id         bigserial PRIMARY KEY,
  at         timestamptz NOT NULL,
  actor_type text NOT NULL CHECK (actor_type IN ('staff','user','system','anonymous')),
  actor_id   uuid,
  actor_name text NOT NULL,
  category   text NOT NULL CHECK (category IN ('rate','approval','login','settings','staff','shareholder','content')),
  action     text NOT NULL,
  message    text NOT NULL,
  tone       text NOT NULL CHECK (tone IN ('info','ok','bad','warn')),
  ip         text,
  meta       jsonb NOT NULL DEFAULT '{}'::jsonb,
  prev_hash  text NOT NULL,
  hash       text NOT NULL UNIQUE
);
CREATE INDEX audit_log_category_idx ON audit_log (category, id DESC);

CREATE FUNCTION audit_log_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'audit_log is append-only';
END;
$$;

CREATE TRIGGER audit_log_no_update BEFORE UPDATE OR DELETE ON audit_log
  FOR EACH ROW EXECUTE FUNCTION audit_log_immutable();
CREATE TRIGGER audit_log_no_truncate BEFORE TRUNCATE ON audit_log
  FOR EACH STATEMENT EXECUTE FUNCTION audit_log_immutable();
