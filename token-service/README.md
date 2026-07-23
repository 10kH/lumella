# lumella-token-service

Zero-dependency local server that mints short-lived OpenAI realtime client
secrets for the glasses fast path. It never talks to luma — its only job is
keeping `OPENAI_API_KEY` off the device.

## Run

```bash
cd token-service
cp .env.example .env.local   # fill in OPENAI_API_KEY + LUMELLA_LOCAL_TOKEN
node server.mjs
# or: npm start
```

Defaults to `http://127.0.0.1:8788`. Node >=20 required (uses global `fetch`).

### LAN-bind dev note

To let a physical RayNeo device on the same LAN reach the service, bind to
the Mac's LAN interface instead of loopback:

```bash
BIND=0.0.0.0 node server.mjs
```

Then point the glasses client at `http://<mac-lan-ip>:8788`. Do this only on
trusted networks — the local-token auth is a pairing secret, not
transport-level hardening (see `contract-tests/COMPAT.md` v2 deferred items
for mTLS/signed-token hardening plans).

## Endpoints

- `GET /healthz` — liveness + configured model + uptime.
- `POST /v1/realtime/token` — mints a realtime client secret. Requires
  header `X-Lumella-Local-Token: <LUMELLA_LOCAL_TOKEN>`. Client caches the
  returned token and refreshes it **60s before `expiresAt`**.

## Smoke examples

```bash
# healthz
curl -s http://127.0.0.1:8788/healthz
# => {"ok":true,"model":"gpt-realtime","uptime":0}

# missing auth header -> 401
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:8788/v1/realtime/token
# => 401

# happy path (shape; requires OPENAI_API_KEY configured)
curl -s -X POST http://127.0.0.1:8788/v1/realtime/token \
  -H "X-Lumella-Local-Token: $LUMELLA_LOCAL_TOKEN"
# => {"token":"...","expiresAt":1234567890,"model":"gpt-realtime"}
```

If `LUMELLA_LOCAL_TOKEN` is unset server-side, the route fails closed with
`503` regardless of what header is presented. If the token header is present
but `OPENAI_API_KEY` is unset, the route also fails closed with `503`
(checked before any upstream call is attempted). Upstream OpenAI failures
surface as a generic `502` — the response never echoes the API key or raw
upstream body.
