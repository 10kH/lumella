// Offline unit tests for api/config.js (public Vercel-style handler). Uses only node:test
// + node:assert (no new dependencies, no real network).

import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';

const handler = (await import('./config.js')).default ??
  (await import('./config.js'));

const ORIGINAL_ENV = { ...process.env };

function resetEnv() {
  for (const key of Object.keys(process.env)) {
    if (!(key in ORIGINAL_ENV)) delete process.env[key];
  }
  Object.assign(process.env, ORIGINAL_ENV);
  delete process.env.LUMELLA_LOCAL_TOKEN;
  delete process.env.LUMA_BASE_URL;
}

beforeEach(() => {
  resetEnv();
});

function makeRequest({ method = 'GET', headers = {} } = {}) {
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
  assert.equal(json(res).error, 'unauthorized');
});

test('missing LUMELLA_LOCAL_TOKEN fails closed with 503', async () => {
  const req = makeRequest({ headers: { 'x-lumella-local-token': 'whatever' } });
  const res = makeResponse();
  await handler(req, res);
  assert.equal(res.statusCode, 503);
  assert.equal(json(res).error, 'server_not_configured');
});

test('POST is rejected with 405', async () => {
  process.env.LUMELLA_LOCAL_TOKEN = 'secret-value';
  const req = makeRequest({ method: 'POST', headers: { 'x-lumella-local-token': 'secret-value' } });
  const res = makeResponse();
  await handler(req, res);
  assert.equal(res.statusCode, 405);
  assert.equal(res.headers.allow, 'GET, OPTIONS');
});

test('OPTIONS short-circuits with 204 and no body', async () => {
  const req = makeRequest({ method: 'OPTIONS' });
  const res = makeResponse();
  await handler(req, res);
  assert.equal(res.statusCode, 204);
  assert.equal(res.body, undefined);
});

test('authorized GET returns 200 with lumaBaseUrl and schemaRev', async () => {
  process.env.LUMELLA_LOCAL_TOKEN = 'secret-value';
  process.env.LUMA_BASE_URL = 'https://random-words.trycloudflare.com';
  const req = makeRequest({ headers: { 'x-lumella-local-token': 'secret-value' } });
  const res = makeResponse();
  await handler(req, res);
  assert.equal(res.statusCode, 200);
  assert.equal(res.headers['cache-control'], 'no-store');
  const body = json(res);
  assert.equal(body.lumaBaseUrl, 'https://random-words.trycloudflare.com');
  assert.equal(body.schemaRev, 1);
});

test('LUMA_BASE_URL unset returns lumaBaseUrl null', async () => {
  process.env.LUMELLA_LOCAL_TOKEN = 'secret-value';
  const req = makeRequest({ headers: { 'x-lumella-local-token': 'secret-value' } });
  const res = makeResponse();
  await handler(req, res);
  assert.equal(res.statusCode, 200);
  const body = json(res);
  assert.equal(body.lumaBaseUrl, null);
  assert.equal(body.schemaRev, 1);
});
