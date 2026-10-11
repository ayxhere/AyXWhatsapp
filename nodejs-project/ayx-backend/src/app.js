// Express application wiring.
// Security posture:
// - helmet security headers
// - CORS restricted to https://imayx.in and https://hello.imayx.in only
// - 1MB JSON body limit
// - rate limiters on sensitive endpoints
// - error handler that NEVER leaks stack traces / secrets in production

import express from 'express';
import helmet from 'helmet';
import cors from 'cors';
import cookieParser from 'cookie-parser';
import { config } from './config.js';
import { ensureUploadDir } from './services/media.js';
import { adminAuth } from './auth-admin.js';
import installationsRouter from './routes/installations.js';
import clientRouter from './routes/client.js';
import messagesRouter from './routes/messages.js';
import adminAuthRouter from './routes/admin-auth.js';
import adminRouter from './routes/admin.js';
import localRouter from './routes/local.js';

export async function createApp() {
  await ensureUploadDir().catch((err) => {
    console.error('[boot] could not create upload dir:', err.message);
  });

  const app = express();

  // Behind Cloudflare Tunnel; trust one proxy hop for correct client IPs.
  app.set('trust proxy', 1);
  app.disable('x-powered-by');

  app.use(helmet());
  app.use(
    cors({
      origin: (origin, cb) => {
        // Same-origin / non-browser requests have no Origin header.
        if (!origin) return cb(null, true);
        if (config.corsOrigins.includes(origin)) return cb(null, true);
        return cb(new Error('CORS origin not allowed'));
      },
      credentials: true,
    })
  );
  app.use(cookieParser());
  app.use(express.json({ limit: '1mb' }));

  // --- v1 routes -----------------------------------------------------------
  app.use('/v1/installations', installationsRouter);
  app.use('/v1/messages', messagesRouter);
  app.use('/v1', clientRouter); // /v1/health, /v1/group/bootstrap, /v1/sync, ...
  app.use('/v1/admin', adminAuthRouter); // /v1/admin/login, /v1/admin/logout
  app.use('/v1/admin', adminAuth, adminRouter);
  app.use('/v1/local', localRouter); // one-time admin bootstrap (token-guarded) // everything else: session + CSRF enforced

  // --- 404 ------------------------------------------------------------------
  app.use((req, res) => {
    res.status(404).json({ error: { code: 'not_found', message: 'Not found.' } });
  });

  // --- central error handler -------------------------------------------------
  // Never leaks stack traces, SQL, or secrets in production responses.
  // eslint-disable-next-line no-unused-vars
  app.use((err, req, res, next) => {
    const status = err.status && Number.isInteger(err.status) ? err.status : 500;
    const code = err.code && typeof err.code === 'string' ? err.code : 'internal_error';

    if (status >= 500) {
      console.error('[error]', err);
    }

    // Malformed JSON body
    if (err.type === 'entity.parse.failed' || err instanceof SyntaxError) {
      return res.status(400).json({
        error: { code: 'malformed_json', message: 'Request body is not valid JSON.' },
      });
    }
    // Multer upload errors
    if (err.name === 'MulterError') {
      const codeOut = err.code === 'LIMIT_FILE_SIZE' ? 'file_too_large' : 'upload_error';
      return res.status(422).json({
        error: { code: codeOut, message: 'File upload failed.' },
      });
    }
    // CORS rejection
    if (err.message === 'CORS origin not allowed') {
      return res.status(403).json({
        error: { code: 'forbidden', message: 'Origin not allowed.' },
      });
    }

    if (status >= 500) {
      return res.status(500).json({
        error: { code: 'internal_error', message: 'Something went wrong.' },
      });
    }
    res.status(status).json({
      error: { code, message: err.message || 'Request failed.' },
    });
  });

  return app;
}
