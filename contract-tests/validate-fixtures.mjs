#!/usr/bin/env node
// G004: consumer-driven contract fixture validator (hermes-pattern sibling —
// see ELLA-hermes/scripts/validate-hermes-contract-fixtures.mjs for the style
// this is modeled on). Zero deps, Node >=20.
//
// Modes:
//   node validate-fixtures.mjs                          -> offline schema/invariant validation
//   node validate-fixtures.mjs --live http://host:port   -> replays key-free fixtures against a live server
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const fixtureDir = path.join(__dirname, 'fixtures');
const compatFile = path.join(__dirname, 'COMPAT.md');

const args = process.argv.slice(2);
const liveIndex = args.indexOf('--live');
const liveBaseUrl = liveIndex >= 0 ? args[liveIndex + 1] : null;
if (liveIndex >= 0 && !liveBaseUrl) {
  console.error('--live requires a base URL, e.g. --live http://127.0.0.1:8010');
  process.exit(2);
}

function readJson(file) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (error) {
    throw new Error(`${file}: ${error.message}`);
  }
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

/** Resolves a dot/bracket path like "response.body.coachEvidence.corrections[0].original" against `root`. */
function resolvePath(root, pathExpr) {
  const segments = pathExpr
    .replace(/\[(\d+)\]/g, '.$1')
    .split('.')
    .filter((s) => s.length > 0);
  let current = root;
  for (const segment of segments) {
    if (current === null || current === undefined) return { found: false, value: undefined };
    current = current[segment];
  }
  return { found: true, value: current };
}

function typeOf(value) {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'array';
  return typeof value; // 'string' | 'number' | 'boolean' | 'object' | 'undefined'
}

/** Runs one fixture's `invariants` against a `{ request, response }`-shaped root (real or replayed). */
function checkInvariants(fixture, root, label) {
  for (const inv of fixture.invariants) {
    const { path: pathExpr, type, minLength, equalsLength, equals, contains } = inv;
    const { found, value } = resolvePath(root, pathExpr);
    if (type === 'null') {
      assert(found && value === null, `${label}: ${fixture.id}: ${pathExpr} must be null`);
      continue;
    }
    assert(found && value !== undefined, `${label}: ${fixture.id}: ${pathExpr} is missing`);
    if (type !== undefined) {
      assert(typeOf(value) === type, `${label}: ${fixture.id}: ${pathExpr} must be ${type}, got ${typeOf(value)}`);
    }
    if (minLength !== undefined) {
      assert(
        (typeof value === 'string' || Array.isArray(value)) && value.length >= minLength,
        `${label}: ${fixture.id}: ${pathExpr} must have length >= ${minLength}`,
      );
    }
    if (equalsLength !== undefined) {
      assert(
        (typeof value === 'string' || Array.isArray(value)) && value.length === equalsLength,
        `${label}: ${fixture.id}: ${pathExpr} must have length === ${equalsLength}`,
      );
    }
    if (equals !== undefined) {
      assert(
        JSON.stringify(value) === JSON.stringify(equals),
        `${label}: ${fixture.id}: ${pathExpr} must equal ${JSON.stringify(equals)}, got ${JSON.stringify(value)}`,
      );
    }
    if (contains !== undefined) {
      assert(
        Array.isArray(value) && value.includes(contains),
        `${label}: ${fixture.id}: ${pathExpr} must contain ${JSON.stringify(contains)}`,
      );
    }
  }
}

function validateFixtureShape(fixture, file) {
  assert(fixture.schemaVersion === 1, `${file}: schemaVersion must be 1`);
  assert(typeof fixture.id === 'string' && fixture.id.length > 0, `${file}: id must be a non-empty string`);
  assert(['key-free', 'live-model'].includes(fixture.grade), `${file}: grade must be "key-free" or "live-model"`);
  assert(typeof fixture.source === 'string' && fixture.source.length > 0, `${file}: source must be a non-empty string`);
  assert(fixture.request && typeof fixture.request === 'object', `${file}: request is required`);
  assert(fixture.response && typeof fixture.response === 'object', `${file}: response is required`);
  assert(typeof fixture.request.method === 'string', `${file}: request.method must be a string`);
  assert(typeof fixture.request.path === 'string', `${file}: request.path must be a string`);
  assert(typeof fixture.request.headers === 'object' && fixture.request.headers !== null, `${file}: request.headers must be an object`);
  assert(typeof fixture.response.status === 'number', `${file}: response.status must be a number`);
  assert('body' in fixture.response, `${file}: response.body is required (may be null)`);
  assert(Array.isArray(fixture.invariants) && fixture.invariants.length > 0, `${file}: invariants must be a non-empty array`);

  // No raw secrets: redacted tokens must literally read "<redacted>", never a live-looking token.
  const text = JSON.stringify(fixture);
  const secretPatterns = [/"accessToken":\s*"(?!<redacted>)[^"]{6,}"/, /"refreshToken":\s*"(?!<redacted>)[^"]{6,}"/];
  for (const pattern of secretPatterns) {
    assert(!pattern.test(text), `${file}: appears to leak an unredacted token`);
  }

  checkInvariants(fixture, fixture, file);
}

function loadFixtures() {
  const files = fs.readdirSync(fixtureDir).filter((name) => name.endsWith('.json')).sort();
  assert(files.length > 0, `no fixtures found under ${fixtureDir}`);
  return files.map((name) => {
    const file = path.join(fixtureDir, name);
    const fixture = readJson(file);
    validateFixtureShape(fixture, file);
    return { name, file, fixture };
  });
}

function checkCompat() {
  if (!fs.existsSync(compatFile)) {
    console.warn('COMPAT check: no COMPAT.md found, skipping.');
    return;
  }
  const text = fs.readFileSync(compatFile, 'utf8');
  const match = text.match(/luma commit\s*\|\s*`([0-9a-f]{7,40})`/i);
  if (!match) {
    console.warn('COMPAT check: could not find a pinned "luma commit" row in COMPAT.md, skipping.');
    return;
  }
  const pinnedSha = match[1];
  let liveSha;
  try {
    liveSha = execFileSync('git', ['-C', path.join(__dirname, '..', '..', 'luma'), 'rev-parse', 'HEAD'], {
      encoding: 'utf8',
    }).trim();
  } catch (error) {
    console.warn(`COMPAT check: could not resolve ../../luma HEAD (${error.message}), skipping.`);
    return;
  }
  if (liveSha.startsWith(pinnedSha) || pinnedSha.startsWith(liveSha)) {
    console.log(`COMPAT check: luma HEAD ${liveSha} matches COMPAT.md pin ${pinnedSha}.`);
    return;
  }
  // A worktree ahead of the pin (uncommitted work in progress, or a later commit)
  // is expected pre-commit — warn, do not fail. See COMPAT.md "Update rule".
  console.warn(
    `COMPAT check: WARNING — luma HEAD ${liveSha} does not match COMPAT.md pin ${pinnedSha}. ` +
      'This is expected when luma has advanced ahead of the pin pre-commit (see COMPAT.md "Update rule"); ' +
      'it only blocks the pin from being bumped, not this validation run.',
  );
}

function runOffline() {
  const fixtures = loadFixtures();
  for (const { name, fixture } of fixtures) {
    console.log(`PASS (offline) ${name} [${fixture.grade}]`);
  }
  checkCompat();
  console.log(`Validated ${fixtures.length} contract fixtures (offline).`);
}

async function freshLogin(baseUrl) {
  const response = await fetch(`${baseUrl}/v1/auth/session`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ provider: 'email', email: 'learner@luma.app', password: 'luma1234', client: 'glasses' }),
  });
  assert(response.status === 200, `fresh login failed with status ${response.status}`);
  const body = await response.json();
  assert(typeof body.accessToken === 'string' && body.accessToken.length > 0, 'fresh login did not return an accessToken');
  return body.accessToken;
}

/**
 * Builds the live request for a fixture. `steering-nonempty-coach` is
 * test-client-recorded (its exact ETRI-correction shape depends on a
 * monkeypatched vendor branch that only exists in the pytest ASGI client,
 * not the plain live server), so `--live` replays it as a live smoke call
 * against the real ETRI-backed route instead of asserting the exact
 * recorded correction — it is exempted from the strict shape re-check and
 * only exercised for reachability/auth-plumbing purposes.
 */
const LIVE_EXEMPT_STRICT_SHAPE = new Set(['steering-nonempty-coach']);

// Demo account documented in luma-api/README.md "Demo Accounts" — not a
// secret; fixtures still store the redacted placeholder for hygiene, so the
// live replay substitutes the real value only for the login fixture itself.
const DEMO_PASSWORD = 'luma1234';

/**
 * `/v1/orchestrator/turn` without an OPENAI_API_KEY is genuinely
 * non-deterministic pre-key: LUMA_ORCHESTRATOR_SELECTION_MODE=model tries an
 * OpenAI tool-selection call first, which always fails auth; the recovery
 * path it falls back to sometimes lands on a deterministic default route
 * (200) and sometimes lands on the `fallback_tutor` skill, which hard-fails
 * 503 FALLBACK_UNAVAILABLE because that skill has nowhere left to recover to
 * without a working GPT-5.4 fallback. Retrying a few times rides out that
 * key-free flakiness instead of asserting determinism the backend does not
 * provide without a key.
 */
const RETRYABLE_PATHS = new Set(['/v1/orchestrator/turn']);
const MAX_ATTEMPTS = 4;


async function replayLive(fixture, baseUrl, token) {
  const headers = { ...fixture.request.headers };
  delete headers.Authorization;
  if (fixture.request.headers.Authorization) headers.Authorization = `Bearer ${token}`;
  const init = { method: fixture.request.method, headers };
  let requestBody = fixture.request.body;
  if (fixture.id === 'auth-session' && requestBody && typeof requestBody === 'object') {
    requestBody = { ...requestBody, password: DEMO_PASSWORD };
  }
  if (requestBody !== null && requestBody !== undefined && fixture.request.method !== 'GET') {
    init.body = JSON.stringify(requestBody);
  }
  const response = await fetch(`${baseUrl}${fixture.request.path}`, init);
  const status = response.status;
  let body = null;
  const text = await response.text();
  if (text.length > 0) {
    try {
      body = JSON.parse(text);
    } catch {
      body = text;
    }
  }
  return { status, body };
}

async function replayLiveWithRetry(fixture, baseUrl, token) {
  const attempts = RETRYABLE_PATHS.has(fixture.request.path) ? MAX_ATTEMPTS : 1;
  let last;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    last = await replayLive(fixture, baseUrl, token);
    if (last.status === fixture.response.status) return last;
    if (attempt < attempts) {
      console.warn(`  retrying ${fixture.id} (attempt ${attempt} got ${last.status}, expected ${fixture.response.status}, key-free selection is non-deterministic pre-key)`);
      await new Promise((resolve) => setTimeout(resolve, 300));
    }
  }
  return last;
}

async function runLive(baseUrl) {
  const fixtures = loadFixtures().filter(({ fixture }) => fixture.grade === 'key-free');
  const token = await freshLogin(baseUrl);
  let failures = 0;
  for (const { name, fixture } of fixtures) {
    try {
      const replayed = await replayLiveWithRetry(fixture, baseUrl, token);
      assert(replayed.status === fixture.response.status, `${name}: expected status ${fixture.response.status}, got ${replayed.status}`);
      if (!LIVE_EXEMPT_STRICT_SHAPE.has(fixture.id)) {
        checkInvariants(fixture, { request: fixture.request, response: replayed }, `${name} (live)`);
      } else {
        assert(replayed.body && typeof replayed.body === 'object', `${name}: expected a JSON object body from live replay`);
      }
      console.log(`PASS (live) ${name} [${fixture.grade}]`);
    } catch (error) {
      failures += 1;
      console.error(`FAIL (live) ${name}: ${error.message}`);
    }
  }
  checkCompat();
  console.log(`Replayed ${fixtures.length} key-free contract fixtures against ${baseUrl}: ${fixtures.length - failures} passed, ${failures} failed.`);
  if (failures > 0) process.exit(1);
}

try {
  if (liveBaseUrl) {
    await runLive(liveBaseUrl.replace(/\/$/, ''));
  } else {
    runOffline();
  }
} catch (error) {
  console.error(`FAIL: ${error.message}`);
  process.exit(1);
}
