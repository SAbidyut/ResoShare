import { parseArgs } from 'node:util';
import { loadConfig } from '../config.js';
import { createPool, q1, tx } from '../db.js';
import { migrate } from '../migrate.js';
import { audit } from '../services/audit.js';
import { tempPassword } from '../util/crypto.js';
import { hashPassword } from '../util/passwords.js';
import { zEmail, zMobile, zName } from '../util/validate.js';

async function main() {
  const { values } = parseArgs({
    options: { name: { type: 'string' }, email: { type: 'string' }, mobile: { type: 'string' } },
    strict: true,
  });
  const name = zName.safeParse(values.name ?? '');
  const email = zEmail.safeParse(values.email ?? '');
  const mobile = zMobile.safeParse(values.mobile ?? '');
  if (!name.success || !email.success || !mobile.success) {
    console.error('Usage: npm run create-admin -- --name "Full Name" --email admin@example.com --mobile 01XXXXXXXXX');
    if (!name.success) console.error(' - invalid --name');
    if (!email.success) console.error(' - invalid --email');
    if (!mobile.success) console.error(' - invalid --mobile (Bangladeshi 01XXXXXXXXX)');
    process.exit(2);
  }
  const config = loadConfig();
  const db = createPool(config);
  try {
    await migrate(db);
    const exists = await q1(db, 'SELECT 1 FROM staff WHERE email = $1', [email.data]);
    if (exists) {
      console.error('A staff member with this email already exists.');
      process.exit(1);
    }
    const role = await q1<{ id: string }>(db, `SELECT id FROM roles WHERE key = 'super_admin'`);
    if (!role) throw new Error('super_admin role missing (run migrations)');
    const temp = tempPassword();
    const hash = await hashPassword(temp);
    await tx(db, async (c) => {
      const s = await q1<{ id: string }>(
        c,
        `INSERT INTO staff (name, email, mobile, password_hash, role_id, must_change_password) VALUES ($1,$2,$3,$4,$5,true) RETURNING id`,
        [name.data, email.data, mobile.data, hash, role.id],
      );
      await audit(c, {
        actorType: 'system',
        actorId: null,
        actorName: 'সিস্টেম (CLI)',
        category: 'staff',
        action: 'staff.create.cli',
        message: `সুপার অ্যাডমিন “${name.data}” তৈরি (সার্ভার কনসোল থেকে)`,
        tone: 'warn',
        meta: { staffId: s?.id ?? null },
      });
    });
    console.log('');
    console.log('Super admin created.');
    console.log(`  Email:              ${email.data}`);
    console.log(`  Temporary password: ${temp}`);
    console.log('');
    console.log('This password is shown only once. At first login in the admin app the admin must');
    console.log('scan the 2-step (TOTP) QR with an authenticator app and then set a new password.');
  } finally {
    await db.end().catch(() => {});
  }
}

main().catch((err) => {
  console.error((err as Error).message);
  process.exit(1);
});
