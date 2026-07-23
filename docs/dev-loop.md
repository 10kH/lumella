# glasses dev loop

## Credential placement

| Credential | Location |
|---|---|
| `OPENAI_API_KEY` | `token-service/.env.local` **only**. Never in luma, never in glasses/Android config, never committed. |
| `LUMA_ETRI_*` / other luma backend keys | `../luma/luma-api/.env` (luma's own env, outside glasses' tree). |
| Android app config | **zero** keys. The glasses app holds no API credentials — it only holds the local pairing token (see below). |

## Local auth pairing

The glasses app authenticates to `token-service` with a shared local
secret, not an OpenAI key:

- `token-service`: `LUMELLA_LOCAL_TOKEN` env var (via `.env.local`).
- glasses app: `local.properties` key `lumella.localToken` (gitignored,
  same value).
- Requests present it as header `X-Lumella-Local-Token`.

Rotate by changing both sides together; a mismatch fails closed with `401`
(and `503` if the service side is unset).

## Fixture grades

- **(i) key-free deterministic** — no external API calls, no
  `OPENAI_API_KEY`/`LUMA_ETRI_*` required. Fully deterministic. **Gate
  default** — this is what CI/pin-advance runs (see
  `contract-tests/COMPAT.md`).
- **(ii) live-model** — hits a real model/API. Schema-shape assertions
  only (never exact-content assertions, since live model output isn't
  stable). Manually tagged/run; not part of the automated gate.

## Base URLs

| Consumer | Target | URL |
|---|---|---|
| RayNeo physical device | Mac dev machine | `http://<mac-lan-ip>:<port>` (see LAN-bind note in `token-service/README.md`) |
| Android emulator | Host loopback alias | `http://10.0.2.2:<port>` |
| token-service | — | `:8788` |
| luma-api | — | `:8010` |

## Android app config keys (plan G006)

`local.properties` (gitignored — see "Credential placement" above) additionally carries the
device-wiring keys `AppConfig`/`app/build.gradle.kts` read into `BuildConfig` fields:

| `local.properties` key | `BuildConfig` field | Default | Purpose |
|---|---|---|---|
| `lumella.tokenServiceBaseUrl` | `TOKEN_SERVICE_BASE_URL` | `http://10.0.2.2:8788` | token-service base URL (real RayNeo: use the Mac LAN IP, see the Base URLs table). |
| `lumella.lumaBaseUrl` | `LUMA_BASE_URL` | `http://10.0.2.2:8010` | luma-api base URL, used only for `BrainCredentials.baseUrl`. |
| `lumella.localToken` | `LUMELLA_LOCAL_TOKEN` | (empty) | the shared local pairing secret (`X-Lumella-Local-Token`), NOT an OpenAI key. |
| `lumella.brainClassName` | `BRAIN_CLASS_NAME` | `com.woolab.lumella.adapter.LumaTutorBrain` | FQCN `BrainFactory` reflectively instantiates for runtime DI (see `README.md` "Dependency rule"). |
| `lumella.brainEmail` / `lumella.brainPassword` | `BRAIN_EMAIL` / `BRAIN_PASSWORD` | (empty) | luma account credentials passed to `TutorBrain.connect` via `BrainCredentials`. |

None of these are OpenAI credentials — the Android app still holds zero API keys. Missing/blank
values fail closed: an empty `lumella.localToken` yields `401` from token-service (or `503` if
the service side is also unset, e.g. no `OPENAI_API_KEY`), which `OpenAiRealtimeTransport`
surfaces as the `TOKEN-FAIL` status; a missing/unresolvable `lumella.brainClassName` falls back
to `NoOpBrain` (voice-only `DEGRADED`) rather than crashing.

## Device prereq

Before any P5 (on-device) smoke pass:

```bash
adb devices
```

Confirm the target device/emulator shows `device` (not `unauthorized` /
`offline`) before proceeding.

## luma boot

From `TUTOR/lumella/` (glasses never edits luma; only runs it):

```bash
cd ../luma && python3 scripts/dev_stack.py up --profile usb --port 8010
```

or, running the API directly:

```bash
cd ../luma/luma-api && uvicorn src.main:app --port 8010
```

(Exact `uvicorn` module path may differ — check `luma-api/pyproject.toml`
entrypoint if `src.main:app` doesn't resolve.)

## G006 on-device smoke amendment

`MainActivity` now bootstraps the full voice loop (see `docs/smoke-checklist.md` gate 4). Full
procedure:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
cd TUTOR/lumella/glasses
./gradlew :app:testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p com.woolab.lumella -c android.intent.category.LAUNCHER 1
adb shell pidof com.woolab.lumella
adb logcat -d | grep -i lumella
```

Without `token-service/.env.local`'s `OPENAI_API_KEY` provisioned, expect the status view (and
logcat) to settle on `TOKEN-FAIL` (token-service unreachable/`503`) — this is the correct
fail-closed outcome, not a bug. With a valid key + reachable `token-service`/`luma-api`, expect
`CONNECTING` -> `READY`; right-tap starts/stops a speech turn, right-double-tap ends the
session, left-tap captures a photo for the next turn's grounding.

### Network-security note (plan G006 P1)

`app/src/main/res/xml/network_security_config.xml` blocks cleartext HTTP by default and only
allows it for `127.0.0.1` and `10.0.2.2` (emulator loopback alias). A **physical RayNeo device**
on the same LAN as the dev Mac needs the Mac's LAN IP added to that file as one extra
`<domain>` line before smoke-testing, e.g. if the Mac's LAN IP is `192.168.1.42`:

```xml
<domain includeSubdomains="false">192.168.1.42</domain>
```

Add it inside the existing `<domain-config>` block, rebuild, and remove it again once the
on-device pass is done (or use an https tunnel instead so no edit here is needed at all — see
the comment in that file). Never widen this to a subnet/wildcard or set a blanket
`android:usesCleartextTraffic="true"`.

### First keyed live run: watch WS error events (plan G006 P4 follow-up)

The Realtime WS `session.update` payload shape (GA-shaped, paired with the `OpenAI-Beta`
header) is pinned by `RealtimeProtocolTest`/`OpenAiRealtimeTransportTest` but has not yet been
validated against a live `OPENAI_API_KEY`. The **first** keyed live smoke run must watch for WS
`error` events immediately after `session.update` is sent (`adb logcat -d | grep -i lumella`,
looking for `Realtime transport error:` from `MainActivity`'s `onError` listener) — if the
server rejects the pinned shape/header combination live, it will surface there even though the
unit tests stay green. Record the outcome (clean `READY` vs. an `error` event) in the smoke
notes; do not assume the pinned unit-test shape is live-correct until this has actually been
observed once.
