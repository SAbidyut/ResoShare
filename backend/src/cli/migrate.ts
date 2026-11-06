// One-shot migration job. Runs with the OWNER credentials (DATABASE_URL), applies pending migrations and
// (re)creates the restricted runtime role (APP_DB_USER / APP_DB_PASSWORD) that the API itself connects as.
// It does not need the API's secrets.
import { createPool } from '../db.js';
import { ensureRuntimeRole, migrate } from '../migrate.js';

const url = process.env.MIGRATION_DATABASE_URL ?? process.env.DATABASE_URL;
if (!url) {
  console.error('DATABASE_URL (owner credentials) is required');
  process.exit(2);
}
const appUser = process.env.APP_DB_USER;
const appPassword = process.env.APP_DB_PASSWORD;
if ((appUser && !appPassword) || (!appUser && appPassword)) {
  console.error('Set both APP_DB_USER and APP_DB_PASSWORD (or neither)');
  process.exit(2);
}
if (!appUser && process.env.NODE_ENV === 'production') {
  console.error('APP_DB_USER and APP_DB_PASSWORD are required in production (the API must not run as the owner)');
  process.exit(2);
}

const db = createPool({ databaseUrl: url, dbSsl: process.env.DB_SSL === 'true', dbPoolMax: 2 });
(async () => {
  const applied = await migrate(db, (m) => console.log(m));
  console.log(applied.length ? `done (${applied.length} applied)` : 'database is up to date');
  if (appUser && appPassword) {
    await ensureRuntimeRole(db, appUser, appPassword);
    console.log(`runtime role "${appUser}" is ready (no superuser, audit_log: SELECT/INSERT only)`);
  }
  await db.end();
})().catch(async (err) => {
  console.error((err as Error).message);
  await db.end().catch(() => {});
  process.exit(1);
});
