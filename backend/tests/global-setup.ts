import pg from 'pg';

/** Recreates the throwaway test database before the run. */
export default async function setup() {
  const url = new URL(process.env.DATABASE_URL as string);
  const dbName = url.pathname.slice(1);
  if (!/^resoshare_test[a-z0-9_]*$/.test(dbName)) throw new Error(`refusing to reset non-test database "${dbName}"`);
  const admin = new URL(url.toString());
  admin.pathname = '/postgres';
  const c = new pg.Client({ connectionString: admin.toString() });
  await c.connect();
  await c.query(`DROP DATABASE IF EXISTS ${dbName} WITH (FORCE)`);
  await c.query(`CREATE DATABASE ${dbName}`);
  await c.end();
}
