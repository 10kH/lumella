#!/usr/bin/env node
// lumella-token-service — zero-dependency local token minter for the glasses
// fast path (OpenAI realtime voice). luma is NEVER consulted here; this
// server's only job is to hand the glasses client a short-lived realtime
// client secret without ever exposing OPENAI_API_KEY to the device.
//
// Style reference: ELLA-hermes/scripts/hermes-local-api-server.mjs
// (zero-dep node:http server, KEY=VALUE .env loader, no npm deps).

import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// ---------------------------------------------------------------------------
// Config: env + token-service/.env.local (optional, gitignored). Real
// process.env always wins over the file so operators can override inline.
// ---------------------------------------------------------------------------

function parseEnvValue(raw) {
  const trimmed = raw.trim();
  if (
    (trimmed.startsWith('"') && trimmed.endsWith('"')) ||
    (trimmed.startsWith("'") && trimmed.endsWith("'"))
  ) {
    return trimmed.slice(1, -1);
  }
  return trimmed;
}

function loadEnvFile(filePath) {
  if (!fs.existsSync(filePath)) return;
  const text = fs.readFileSync(filePath, 'utf8');
  for (const line of text.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const match = trimmed.match(/^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$/);
    if (!match) continue;
    const [, key, rawValue] = match;
    if (process.env[key] === undefined) {
      process.env[key] = parseEnvValue(rawValue);
    }
  }
}

loadEnvFile(path.join(__dirname, '.env.local'));

const PORT = Number.parseInt(process.env.PORT || '8788', 10);
const BIND = process.env.BIND || '127.0.0.1';
const OPENAI_REALTIME_MODEL = process.env.OPENAI_REALTIME_MODEL || 'gpt-realtime';
const OPENAI_API_KEY = process.env.OPENAI_API_KEY || '';
const LUMELLA_LOCAL_TOKEN = process.env.LUMELLA_LOCAL_TOKEN || '';

const OPENAI_REALTIME_CLIENT_SECRETS_URL = 'https://api.openai.com/v1/realtime/client_secrets';

const startedAt = Date.now();

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function sendJson(response, statusCode, payload) {
  response.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
  });
  response.end(JSON.stringify(payload));
}

async function bufferRequestBody(request) {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  return Buffer.concat(chunks).toString('utf8');
}

function timingSafeEqual(a, b) {
  const bufA = Buffer.from(String(a ?? ''), 'utf8');
  const bufB = Buffer.from(String(b ?? ''), 'utf8');
  if (bufA.length !== bufB.length) {
    // Still run a comparison of equal-length buffers to keep timing roughly
    // constant; length mismatch alone is not a meaningful secret leak here.
    crypto.timingSafeEqual(bufA, bufA);
    return false;
  }
  return crypto.timingSafeEqual(bufA, bufB);
}

// ---------------------------------------------------------------------------
// Handlers
// ---------------------------------------------------------------------------

function handleHealthz(_request, response) {
  sendJson(response, 200, {
    ok: true,
    model: OPENAI_REALTIME_MODEL,
    uptime: Math.round((Date.now() - startedAt) / 1000),
  });
}

async function handleMintToken(request, response) {
  // Fail-closed: an unset local pairing token means nobody can authenticate,
  // so refuse the whole route rather than silently accepting any caller.
  if (!LUMELLA_LOCAL_TOKEN) {
    sendJson(response, 503, {
      error: 'server_not_configured',
      message: 'LUMELLA_LOCAL_TOKEN is not configured.',
    });
    return;
  }

  const presented = request.headers['x-lumella-local-token'];
  if (!presented || !timingSafeEqual(presented, LUMELLA_LOCAL_TOKEN)) {
    sendJson(response, 401, { error: 'unauthorized' });
    return;
  }

  if (!OPENAI_API_KEY) {
    sendJson(response, 503, {
      error: 'server_not_configured',
      message: 'OPENAI_API_KEY is not configured.',
    });
    return;
  }

  // Drain the body even though the current mint contract takes no client
  // input; keeps the connection well-behaved for chunked POSTs.
  await bufferRequestBody(request);

  try {
    const upstream = await fetch(OPENAI_REALTIME_CLIENT_SECRETS_URL, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${OPENAI_API_KEY}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        session: {
          type: 'realtime',
          model: OPENAI_REALTIME_MODEL,
        },
      }),
    });

    if (!upstream.ok) {
      // Never forward the upstream body: it can echo back request details
      // and, in error paths, partial auth context. Log only status/length.
      console.error(
        `[lumella-token-service] upstream mint failed status=${upstream.status} bodyLength=${
          (await upstream.text()).length
        }`,
      );
      sendJson(response, 502, {
        error: 'upstream_mint_failed',
        message: 'Failed to mint realtime token from upstream provider.',
      });
      return;
    }

    const payload = await upstream.json();
    // OpenAI's client_secrets response nests the secret + expiry; map to a
    // flat shape. Client caches this and MUST refresh >=60s before expiresAt.
    // CONTRACT: expiresAt is epoch MILLISECONDS. OpenAI emits epoch seconds
    // (~1.7e9); normalize here so the Kotlin client's millisecond clock
    // comparison (TokenServiceCredentialProvider) is unit-consistent.
    const token = payload?.client_secret?.value ?? payload?.value ?? null;
    const rawExpires =
      payload?.client_secret?.expires_at ?? payload?.expires_at ?? null;
    // Values below 10^12 are epoch-seconds; at/above are already milliseconds.
    const expiresAt =
      typeof rawExpires === 'number' && rawExpires > 0 && rawExpires < 1e12
        ? rawExpires * 1000
        : rawExpires;

    if (!token) {
      console.error('[lumella-token-service] upstream response missing client_secret.value');
      sendJson(response, 502, {
        error: 'upstream_mint_failed',
        message: 'Upstream response did not include a client secret.',
      });
      return;
    }

    console.log(
      `[lumella-token-service] minted token tokenLength=${token.length} expiresAt=${expiresAt}`,
    );

    sendJson(response, 200, {
      token,
      expiresAt,
      model: OPENAI_REALTIME_MODEL,
    });
  } catch (error) {
    console.error(
      `[lumella-token-service] mint request threw: ${error instanceof Error ? error.message : 'unknown error'}`,
    );
    sendJson(response, 502, {
      error: 'upstream_mint_failed',
      message: 'Failed to mint realtime token from upstream provider.',
    });
  }
}

// ---------------------------------------------------------------------------
// Server
// ---------------------------------------------------------------------------

const server = http.createServer(async (request, response) => {
  const url = new URL(request.url || '/', 'http://localhost');

  if (request.method === 'GET' && url.pathname === '/healthz') {
    handleHealthz(request, response);
    return;
  }

  if (request.method === 'POST' && url.pathname === '/v1/realtime/token') {
    await handleMintToken(request, response);
    return;
  }

  sendJson(response, 404, { error: 'not_found' });
});

server.listen(PORT, BIND, () => {
  console.log(`[lumella-token-service] listening on http://${BIND}:${PORT}`);
});
