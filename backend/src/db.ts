import pg from 'pg';
import type { Config } from './config.js';

// BIGINT / NUMERIC → JS number. All money values are paisa and stay far below 2^53.
pg.types.setTypeParser(20, (v) => Number(v));
pg.types.setTypeParser(1700, (v) => Number(v));
// DATE stays a plain 'YYYY-MM-DD' string (no implicit timezone conversion).
pg.types.setTypeParser(1082, (v) => v);

export type Db = pg.Pool;
export type Client = pg.PoolClient | pg.Pool;

export function createPool(config: Pick<Config, 'databaseUrl' | 'dbSsl' | 'dbPoolMax'>): Db {
  return new pg.Pool({
    connectionString: config.databaseUrl,
    max: config.dbPoolMax,
    ssl: config.dbSsl ? { rejectUnauthorized: true } : undefined,
    statement_timeout: 15_000,
    idle_in_transaction_session_timeout: 30_000,
    application_name: 'resoshare-api',
  });
}

export async function q<T extends pg.QueryResultRow = pg.QueryResultRow>(
  client: Client,
  sql: string,
  params: unknown[] = [],
): Promise<T[]> {
  const res = await client.query<T>(sql, params as unknown[]);
  return res.rows;
}

export async function q1<T extends pg.QueryResultRow = pg.QueryResultRow>(
  client: Client,
  sql: string,
  params: unknown[] = [],
): Promise<T | undefined> {
  const rows = await q<T>(client, sql, params);
  return rows[0];
}

export async function tx<T>(pool: Db, fn: (c: pg.PoolClient) => Promise<T>): Promise<T> {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const out = await fn(client);
    await client.query('COMMIT');
    return out;
  } catch (err) {
    try {
      await client.query('ROLLBACK');
    } catch {
      /* ignore */
    }
    throw err;
  } finally {
    client.release();
  }
}
