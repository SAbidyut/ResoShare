import pg from 'pg';
import { createPool } from '../src/db.js';
import { ensureRuntimeRole, migrate } from '../src/migrate.js';

/** Recreates the throwaway test database, migrates it as the owner and creates the restricted runtime role. */
export default async function setup() {
  const ownerUrl = process.env.TEST_OWNER_DATABASE_URL as string;
  const url = new URL(ownerUrl);
  const dbName = url.pathname.slice(1);
  if (!/^resoshare_test[a-z0-9_]*$/.test(dbName)) throw new Error(`refusing to reset non-test database "${dbName}"`);
  const admin = new URL(url.toString());
  admin.pathname = '/postgres';
  const c = new pg.Client({ connectionString: admin.toString() });
  await c.connect();
  await c.query(`DROP DATABASE IF EXISTS ${dbName} WITH (FORCE)`);
  await c.query(`CREATE DATABASE ${dbName}`);
  await c.end();

  const owner = createPool({ databaseUrl: ownerUrl, dbSsl: false, dbPoolMax: 2 });
  await migrate(owner);
  await ensureRuntimeRole(owner, process.env.APP_DB_USER as string, process.env.APP_DB_PASSWORD as string);
  await owner.end();
}
