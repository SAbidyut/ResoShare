import { loadConfig } from './config.js';
import { createPool } from './db.js';
import { assertRestrictedRole, assertSchemaCurrent, ensureRuntimeRole, migrate } from './migrate.js';
import { buildApp } from './app.js';
import { startScheduler } from './jobs/scheduler.js';

async function main() {
  let config;
  try {
    config = loadConfig();
  } catch (err) {
    // Never start with weak or missing secrets.
    console.error((err as Error).message);
    process.exit(1);
  }
  const db = createPool(config);
  // Migrations need the OWNER role. In production they run in the separate one-shot `migrate` job;
  // the API only migrates itself when explicitly given MIGRATION_DATABASE_URL (or in development).
  if (config.migrationDatabaseUrl) {
    const owner = createPool({ ...config, databaseUrl: config.migrationDatabaseUrl, dbPoolMax: 2 });
    try {
      await migrate(owner, (m) => console.log(`[migrate] ${m}`));
      if (process.env.APP_DB_USER && process.env.APP_DB_PASSWORD) {
        await ensureRuntimeRole(owner, process.env.APP_DB_USER, process.env.APP_DB_PASSWORD);
      }
    } finally {
      await owner.end().catch(() => {});
    }
  } else if (config.env !== 'production') {
    await migrate(db, (m) => console.log(`[migrate] ${m}`));
  }
  try {
    await assertSchemaCurrent(db);
    if (config.env === 'production') await assertRestrictedRole(db);
  } catch (err) {
    console.error((err as Error).message);
    await db.end().catch(() => {});
    process.exit(1);
  }
  const app = await buildApp({ config, db });
  const stopScheduler = startScheduler(app.ctx);

  const shutdown = async (signal: string) => {
    app.log.info({ signal }, 'shutting down');
    stopScheduler();
    await app.close().catch(() => {});
    await db.end().catch(() => {});
    process.exit(0);
  };
  process.on('SIGTERM', () => void shutdown('SIGTERM'));
  process.on('SIGINT', () => void shutdown('SIGINT'));

  await app.listen({ host: config.host, port: config.port });
}

main().catch((err) => {
  console.error('fatal startup error:', (err as Error).message);
  process.exit(1);
});
