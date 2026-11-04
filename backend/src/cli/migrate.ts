import { loadConfig } from '../config.js';
import { createPool } from '../db.js';
import { migrate } from '../migrate.js';

const config = loadConfig();
const db = createPool(config);
migrate(db, (m) => console.log(m))
  .then((applied) => {
    console.log(applied.length ? `done (${applied.length} applied)` : 'database is up to date');
    return db.end();
  })
  .catch(async (err) => {
    console.error((err as Error).message);
    await db.end().catch(() => {});
    process.exit(1);
  });
