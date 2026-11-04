import { loadConfig } from './config.js';
import { createPool } from './db.js';
import { migrate } from './migrate.js';
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
  await migrate(db, (m) => console.log(`[migrate] ${m}`));
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
