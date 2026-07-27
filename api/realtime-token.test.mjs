// Offline unit tests for api/realtime-token.js (public Vercel-style handler).
// Uses only node:test + node:assert (no new dependencies). No real network
// calls are made: `fetch` is stubbed per-test when the handler needs it.

import { test, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';

const handler = (await import('./realtime-token.js')).default ??
  (await import('./realtime-token.js'));

const ORIGINAL_ENV = { ...process.env };
const ORIGINAL_FETCH = globalThis.fetch;

function resetEnv() {
  for (const key of Object.keys(process.env)) {
    if (!(key in ORIGINAL_ENV)) delete process.env[key];
  }
  Object.assign(process.env, ORIGINAL_ENV);
  delete process.env.LUMELLA_LOCAL_TOKEN;
  delete process.env.OPENAI_API_KEY;
  delete process.env.OPENAI_REALTIME_MODEL;
}

beforeEach(() => {
  resetEnv();
});

afterEach(() => {
  globalThis.fetch = ORIGINAL_FETCH;
});

function makeRequest({ method = 'POST', headers = {} } = {}) {
  return { method, headers };
}

function makeResponse() {
  const res = {
    statusCode: undefined,
    headers: {},
    body: undefined,
    ended: false,
    setHeader(name, value) {
      res.headers[name.toLowerCase()] = value;
    },
    end(payload) {
      res.ended = true;
      res.body = payload;
    },
  };
  return res;
}

function json(res) {
  return res.body ? JSON.parse(res.body) : undefined;
}

test('missing LUMELLA_LOCAL_TOKEN fails closed with 503', async () => {
  const req = makeRequest({ headers: { 'x-lumella-local-token': 'whatever' } });
  const res = makeResponse();
  await handler(req, res);
  assert.equal(res.statusCode, 503);
  assert.equal(json(res).error, 'server_not_configured');
});

test('missing auth header returns 401', async () => {
  process.env.LUMELLA_LOCAL_TOKEN = 'secret-value';
  const req = makeRequest({ headers: {} });
  const res = makeResponse();
  await handler(req, res);
  assert.equal(res.statusCode, 401);
  assert.equal(json(res).error, 'unauthorized');
});

test('wrong secret returns 401', async () => {
  process.env.LUMELLA_LOCAL_TOKEN = 'secret-value';
  const req = makeRequest({ headers: { 'x-lumella-local-token': 'nope' } });
  const res = makeResponse();
  await handler(req, res);
  assert.equal(res.statusCode, 401);
});

test('secret of different length returns 401 without crashing (constant-time path)', async () => {
  process.env.LUMELLA_LOCAL_TOKEN = 'a-much-longer-secret-value';
  const req = makeRequest({ headers: { 'x-lumella-local-token': 'short' } });
  const res = makeResponse();
  await assert.doesNotReject(() => handler(req, res));
  assert.equal(res.statusCode, 401);
  assert.equal(json(res).error, 'unauthorized');
});

test('GET is rejected with 405', async () => {
  process.env.LUMELLA_LOCAL_TOKEN = 'secret-value';
  const req = makeRequest({ method: 'GET' });
  const res = makeResponse();
  await handler(req, res);
  assert.equal(res.statusCode, 405);
  assert.equal(res.headers.allow, 'POST, OPTIONS');
});

test('OPTIONS short-circuits with 204 and no body', async () => {
  const req = makeRequest({ method: 'OPTIONS' });
  const res = makeResponse();
  await handler(req, res);
  assert.equal(res.statusCode, 204);
  assert.equal(res.body, undefined);
});

test('missing OPENAI_API_KEY fails closed with 503 after auth passes', async () => {
  process.env.LUMELLA_LOCAL_TOKEN = 'secret-value';
  const req = makeRequest({ headers: { 'x-lumella-local-token': 'secret-value' } });
  const res = makeResponse();
  await handler(req, res);
  assert.equal(res.statusCode, 503);
  assert.equal(json(res).error, 'server_not_configured');
});

test('expiresAt in epoch seconds is normalized to milliseconds', async () => {
  process.env.LUMELLA_LOCAL_TOKEN = 'secret-value';
  process.env.OPENAI_API_KEY = 'sk-test';
  globalThis.fetch = async () => ({
    ok: true,
    status: 200,
    json: async () => ({
      client_secret: { value: 'tok_123', expires_at: 1_700_000_000 },
    }),
  });
  const req = makeRequest({ headers: { 'x-lumella-local-token': 'secret-value' } });
  const res = makeResponse();
  await handler(req, res);
  assert.equal(res.statusCode, 200);
  const body = json(res);
  assert.equal(body.token, 'tok_123');
  assert.equal(body.expiresAt, 1_700_000_000_000);
});

test('expiresAt already in milliseconds is left unchanged', async () => {
  process.env.LUMELLA_LOCAL_TOKEN = 'secret-value';
  process.env.OPENAI_API_KEY = 'sk-test';
  globalThis.fetch = async () => ({
    ok: true,
    status: 200,
    json: async () => ({
      client_secret: { value: 'tok_456', expires_at: 1_700_000_000_000 },
    }),
  });
  const req = makeRequest({ headers: { 'x-lumella-local-token': 'secret-value' } });
  const res = makeResponse();
  await handler(req, res);
  assert.equal(res.statusCode, 200);
  assert.equal(json(res).expiresAt, 1_700_000_000_000);
});

test('upstream failure returns 502 without leaking upstream body', async () => {
  process.env.LUMELLA_LOCAL_TOKEN = 'secret-value';
  process.env.OPENAI_API_KEY = 'sk-test';
  globalThis.fetch = async () => ({
    ok: false,
    status: 500,
    json: async () => ({ error: { message: 'super secret account details' } }),
  });
  const req = makeRequest({ headers: { 'x-lumella-local-token': 'secret-value' } });
  const res = makeResponse();
  await handler(req, res);
  assert.equal(res.statusCode, 502);
  const body = json(res);
  assert.equal(body.error, 'upstream_mint_failed');
  assert.equal(JSON.stringify(body).includes('super secret'), false);
});

test('upstream response missing token returns 502', async () => {
  process.env.LUMELLA_LOCAL_TOKEN = 'secret-value';
  process.env.OPENAI_API_KEY = 'sk-test';
  globalThis.fetch = async () => ({
    ok: true,
    status: 200,
    json: async () => ({ client_secret: {} }),
  });
  const req = makeRequest({ headers: { 'x-lumella-local-token': 'secret-value' } });
  const res = makeResponse();
  await handler(req, res);
  assert.equal(res.statusCode, 502);
  assert.equal(json(res).error, 'upstream_mint_failed');
});
