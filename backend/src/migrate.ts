import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { Db } from './db.js';

/** Walks up from this file until it finds the project root (the folder holding package.json and migrations/). */
export function projectRoot(): string {
  let dir = dirname(fileURLToPath(import.meta.url));
  for (let i = 0; i < 6; i++) {
    if (existsSync(join(dir, 'package.json')) && existsSync(join(dir, 'migrations'))) return dir;
    dir = dirname(dir);
  }
  throw new Error('project root not found');
}

export async function migrate(pool: Db, log: (msg: string) => void = () => {}): Promise<string[]> {
  const dir = join(projectRoot(), 'migrations');
  const files = migrationFiles();
  const client = await pool.connect();
  const applied: string[] = [];
  try {
    // Serialize concurrent migrators (e.g. two containers starting at once).
    await client.query('SELECT pg_advisory_lock(727274)');
    await client.query(
      'CREATE TABLE IF NOT EXISTS schema_migrations (version text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())',
    );
    const done = new Set((await client.query<{ version: string }>('SELECT version FROM schema_migrations')).rows.map((r) => r.version));
    for (const f of files) {
      if (done.has(f)) continue;
      const sql = readFileSync(join(dir, f), 'utf8');
      await client.query('BEGIN');
      try {
        await client.query(sql);
        await client.query('INSERT INTO schema_migrations (version) VALUES ($1)', [f]);
        await client.query('COMMIT');
        applied.push(f);
        log(`applied ${f}`);
      } catch (err) {
        await client.query('ROLLBACK');
        throw new Error(`migration ${f} failed: ${(err as Error).message}`);
      }
    }
  } finally {
    await client.query('SELECT pg_advisory_unlock(727274)').catch(() => {});
    client.release();
  }
  return applied;
}

function migrationFiles(): string[] {
  return readdirSync(join(projectRoot(), 'migrations'))
    .filter((f) => /^\d{3}_[a-z0-9_]+\.sql$/.test(f))
    .sort();
}

/** Throws when the database is behind the code (the runtime role cannot migrate; run the migrate job). */
export async function assertSchemaCurrent(pool: Db): Promise<void> {
  let done: Set<string>;
  try {
    done = new Set((await pool.query<{ version: string }>('SELECT version FROM schema_migrations')).rows.map((r) => r.version));
  } catch {
    throw new Error('database is not initialised: run the migrate job (node dist/src/cli/migrate.js) with the owner credentials first');
  }
  const pending = migrationFiles().filter((f) => !done.has(f));
  if (pending.length) {
    throw new Error(`database schema is behind (${pending.join(', ')}): run the migrate job with the owner credentials first`);
  }
}

/**
 * Production guard: the API must run as the restricted runtime role, never as a superuser or as the
 * owner of audit_log (an owner could disable the append-only trigger).
 */
export async function assertRestrictedRole(pool: Db): Promise<void> {
  const r = await pool.query<{ rolsuper: boolean; owns_audit: boolean; can_update_audit: boolean }>(
    `SELECT r.rolsuper,
            (SELECT tableowner FROM pg_tables WHERE schemaname = 'public' AND tablename = 'audit_log') = current_user AS owns_audit,
            has_table_privilege('public.audit_log', 'UPDATE') AS can_update_audit
       FROM pg_roles r WHERE r.rolname = current_user`,
  );
  const row = r.rows[0];
  if (!row || row.rolsuper || row.owns_audit || row.can_update_audit) {
    throw new Error(
      'refusing to start: DATABASE_URL must use the restricted runtime role (APP_DB_USER created by the migrate job), not the database owner/superuser',
    );
  }
}

const ROLE_RE = /^[a-z_][a-z0-9_]{2,40}$/;

/**
 * Creates/updates the restricted runtime role and its grants. Run by the migrate job as the owner.
 * The role can read/write business tables but on audit_log only SELECT + INSERT; it owns nothing,
 * cannot create objects, roles or databases, and is not a superuser.
 */
export async function ensureRuntimeRole(pool: Db, role: string, password: string): Promise<void> {
  if (!ROLE_RE.test(role)) throw new Error('APP_DB_USER must match ^[a-z_][a-z0-9_]{2,40}$');
  if (password.length < 16) throw new Error('APP_DB_PASSWORD must be at least 16 characters');
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    // Values travel as bound parameters into transaction-local settings, then into format(%I/%L) — never concatenated.
    await client.query(`SELECT set_config('resoshare.app_role', $1, true), set_config('resoshare.app_pw', $2, true)`, [role, password]);
    await client.query(`DO $do$
DECLARE
  r text := current_setting('resoshare.app_role');
  p text := current_setting('resoshare.app_pw');
BEGIN
  IF r = current_user THEN
    RAISE EXCEPTION 'APP_DB_USER must differ from the owner role';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
    EXECUTE format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT PASSWORD %L', r, p);
  ELSE
    EXECUTE format('ALTER ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT PASSWORD %L', r, p);
  END IF;
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), r);
  EXECUTE format('REVOKE ALL ON SCHEMA public FROM %I', r);
  EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', r);
  EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA public FROM %I', r);
  EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I', r);
  -- audit_log: append + read only. schema_migrations: read only (startup check).
  EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON audit_log FROM %I', r);
  EXECUTE format('REVOKE INSERT, UPDATE, DELETE ON schema_migrations FROM %I', r);
  EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM %I', r);
  EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO %I', r);
END
$do$`);
    await client.query('COMMIT');
  } catch (err) {
    await client.query('ROLLBACK').catch(() => {});
    throw err;
  } finally {
    client.release();
  }
}
