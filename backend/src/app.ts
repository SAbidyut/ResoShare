import { randomUUID } from 'node:crypto';
import Fastify, { type FastifyInstance, type FastifyError } from 'fastify';
import helmet from '@fastify/helmet';
import cors from '@fastify/cors';
import rateLimit from '@fastify/rate-limit';
import multipart from '@fastify/multipart';
import sjson from 'secure-json-parse';
import type { Config } from './config.js';
import type { Ctx } from './context.js';
import type { Db } from './db.js';
import { ApiError, errorBody } from './errors.js';
import { Crypto } from './util/crypto.js';
import { MAX_FILE_BYTES } from './services/files.js';
import { publicRoutes, verifyPageRoute } from './routes/public.js';
import { userRoutes } from './routes/user.js';
import { adminAuthRoutes } from './routes/admin/auth.js';
import { adminOverviewRoutes } from './routes/admin/overview.js';
import { adminShareholderRoutes } from './routes/admin/shareholders.js';
import { adminOrderRoutes } from './routes/admin/orders.js';
import { adminMarketRoutes } from './routes/admin/market.js';
import { adminContentRoutes } from './routes/admin/content.js';
import { adminReportRoutes } from './routes/admin/reports.js';
import { adminStaffRoutes } from './routes/admin/staff.js';
import { adminBackupRoutes } from './routes/admin/backup.js';

export type BuildOptions = { config: Config; db: Db; logger?: boolean };

const REDACT = [
  'req.headers.authorization',
  'req.headers.cookie',
  'res.headers["set-cookie"]',
  '*.password',
  '*.currentPassword',
  '*.newPassword',
  '*.refreshToken',
  '*.accessToken',
  '*.mfaToken',
  '*.tempPassword',
  '*.nidNumber',
  '*.idNumber',
  '*.code',
  '*.secret',
];

const KNOWN_413 = new Set(['FST_REQ_FILE_TOO_LARGE', 'FST_FILES_LIMIT', 'FST_PARTS_LIMIT', 'FST_FIELDS_LIMIT', 'FST_ERR_CTP_BODY_TOO_LARGE']);

export async function buildApp(opts: BuildOptions): Promise<FastifyInstance> {
  const { config, db } = opts;
  const app = Fastify({
    logger:
      opts.logger === false
        ? false
        : {
            level: config.logLevel,
            redact: { paths: REDACT, censor: '[redacted]' },
          },
    // Fastify also accepts a hop count (number) at runtime; the types only list string | boolean.
    trustProxy: config.trustProxy as boolean | string,
    bodyLimit: 64 * 1024,
    genReqId: () => randomUUID(),
    onProtoPoisoning: 'error',
    onConstructorPoisoning: 'error',
    routerOptions: { maxParamLength: 120 },
    return503OnClosing: true,
  });

  const ctx: Ctx = { config, db, crypto: new Crypto(config.dataEncKey, config.hmacKey), log: app.log };

  await app.register(helmet, {
    global: true,
    contentSecurityPolicy: {
      useDefaults: false,
      directives: { defaultSrc: ["'none'"], frameAncestors: ["'none'"], baseUri: ["'none'"], formAction: ["'none'"] },
    },
    hsts: { maxAge: 31_536_000, includeSubDomains: true },
    crossOriginResourcePolicy: { policy: 'same-origin' },
    referrerPolicy: { policy: 'no-referrer' },
  });
  // The only clients are the mobile apps: no browser origin may call the API.
  await app.register(cors, { origin: false });

  if (config.rateLimitEnabled) {
    await app.register(rateLimit, {
      global: true,
      max: 300,
      timeWindow: '1 minute',
      errorResponseBuilder: () => {
        const e = new ApiError('TOO_MANY_REQUESTS') as ApiError & { statusCode: number };
        e.statusCode = 429;
        return e;
      },
    });
  }

  await app.register(multipart, {
    limits: { fileSize: MAX_FILE_BYTES, files: 3, fields: 2, fieldSize: 16 * 1024, parts: 5, headerPairs: 50 },
    throwFileSizeLimit: true,
  });

  // JSON parser: prototype-poisoning safe, and tolerant of an empty body on body-less POSTs.
  app.removeContentTypeParser('application/json');
  app.addContentTypeParser('application/json', { parseAs: 'string', bodyLimit: 64 * 1024 }, (_req, body, done) => {
    const text = typeof body === 'string' ? body : body.toString('utf8');
    if (text.trim() === '') return done(null, {});
    try {
      done(null, sjson.parse(text, undefined, { protoAction: 'error', constructorAction: 'error' }));
    } catch {
      done(new ApiError('INVALID_INPUT', 'অনুরোধের JSON সঠিক নয়।') as unknown as FastifyError, undefined);
    }
  });

  app.setErrorHandler((err: FastifyError | ApiError, req, reply) => {
    if (err instanceof ApiError) {
      if (err.status === 429) reply.header('retry-after', '60');
      return reply.status(err.status).send(err.toBody());
    }
    const sc = (err as FastifyError).statusCode;
    const code = (err as FastifyError).code;
    if (sc === 429) return reply.status(429).send(errorBody('TOO_MANY_REQUESTS'));
    if (sc === 413 || (code && KNOWN_413.has(code))) return reply.status(413).send(errorBody('PAYLOAD_TOO_LARGE'));
    if (sc === 404 || sc === 405) return reply.status(404).send(errorBody('NOT_FOUND'));
    if (sc && sc >= 400 && sc < 500) return reply.status(400).send(errorBody('INVALID_INPUT'));
    req.log.error({ err }, 'unhandled error');
    return reply.status(500).send(errorBody('SERVER_ERROR'));
  });

  app.setNotFoundHandler((_req, reply) => reply.status(404).send(errorBody('NOT_FOUND')));

  app.addHook('onSend', async (_req, reply, payload) => {
    reply.header('cache-control', 'no-store');
    reply.header('pragma', 'no-cache');
    reply.removeHeader('x-powered-by');
    return payload;
  });

  await app.register(
    async (api) => {
      await publicRoutes(api, ctx);
      await userRoutes(api, ctx);
      await adminAuthRoutes(api, ctx);
      await adminOverviewRoutes(api, ctx);
      await adminShareholderRoutes(api, ctx);
      await adminOrderRoutes(api, ctx);
      await adminMarketRoutes(api, ctx);
      await adminContentRoutes(api, ctx);
      await adminReportRoutes(api, ctx);
      await adminStaffRoutes(api, ctx);
      await adminBackupRoutes(api, ctx);
    },
    { prefix: '/api/v1' },
  );
  await verifyPageRoute(app, ctx);

  app.decorate('ctx', ctx);
  return app;
}

declare module 'fastify' {
  interface FastifyInstance {
    ctx: Ctx;
  }
}
