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
| public token endpoint (Vercel) | — | `https://lumella-token.vercel.app` |

## Public token endpoint (off-LAN use)

`token-service` also runs as a public Vercel function (`api/realtime-token.js`), deployed
2026-07-28 (commit `57d4178`), so the glasses work away from the Mac's LAN:

- Contract is identical to the local service: `POST /v1/realtime/token` ->
  `{token, expiresAt, model}` (`expiresAt` normalized to epoch ms). `vercel.json` rewrites the
  path so local and remote share one client contract; `.vercelignore` keeps the Android project
  out of the upload.
- Auth: same shared secret as local (`X-Lumella-Local-Token`), constant-time compared. The
  Vercel project's env var `LUMELLA_LOCAL_TOKEN` **must hold the same value** as the device's
  `local.properties` `lumella.localToken` (and the local `token-service/.env.local`'s
  `LUMELLA_LOCAL_TOKEN`, if you also run local) — a mismatch fails closed with `401`, unset
  fails closed with `503`. Unlike the legacy ELLA endpoint it is deliberately **not** anonymous.
- Choosing local vs. remote: set `local.properties`' `lumella.tokenServiceBaseUrl` to
  `http://<mac-lan-ip>:8788` when at home (Mac reachable on the same LAN, `launchd`-managed
  local service — see below) or to `https://lumella-token.vercel.app` when away from home (LAN
  unreachable, remote required). The `BuildConfig` default baked into `app/build.gradle.kts` is
  still the emulator-only `http://10.0.2.2:8788` — always override it in `local.properties` for
  any real-device run; the tracked default alone does not reproduce either working setup.
- Live-verified (2026-07-28): anonymous request 401, wrong secret 401, `GET` 405, correct secret
  mints a token with millisecond `expiresAt`. On-device the app reached the WS over the public
  internet and stopped only at the account's `insufficient_quota` (see smoke-checklist.md).

## Local token-service supervision (launchd)

Running `token-service` by hand ties its lifetime to the shell — closing the terminal kills it
and the glasses then show `TOKEN-FAIL`, which looks like a credential problem but is just a dead
local process (observed repeatedly). `ops/launchd/manage.sh` keeps it up as a Mac LaunchAgent:

```bash
ops/launchd/manage.sh install     # render plist + load + start + health-check
ops/launchd/manage.sh status      # launchctl state + /healthz
ops/launchd/manage.sh logs        # tail service logs
ops/launchd/manage.sh restart     # kickstart the running service
ops/launchd/manage.sh uninstall   # stop + unload + remove the LaunchAgent
```

`install` requires `token-service/.env.local` to already hold `OPENAI_API_KEY` +
`LUMELLA_LOCAL_TOKEN` (fails fast otherwise); secrets stay in that gitignored file, never in the
script or the rendered plist. Logs land under `tmp/token-service/`.

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
`android:usesCleartextTraffic="true"`. (This LAN-IP dance only applies to the token-service
`http://` path; using the public token endpoint from "Public token endpoint (off-LAN use)" above
avoids it entirely for token minting, but `luma-api` traffic still needs the same LAN-cleartext
handling since it has no public equivalent.)

### GA `session.update` shape: live-validated (was: unverified)

The Realtime WS `session.update` payload shape (GA-shaped, paired with the `OpenAI-Beta`
header) is pinned by `RealtimeProtocolTest`/`OpenAiRealtimeTransportTest` **and has been
validated against a live `OPENAI_API_KEY`** — this is no longer an open question:

- 2026-07-22 09:03, real device: clean live `READY` observed, including a `session_expired` →
  automatic reconnect → fresh-token `READY` cycle (~2.9s total), no WS `error` event on
  `session.update`. Confirmed again over a 24h+ soak (24+ reconnect cycles, no shape rejection).
- 2026-07-23, real device, worn: full voice E2E reached a live luma coach turn (session
  `orch_36bebef`, mode=coach) from real speech — see `smoke-checklist.md` "착용 E2E 실증" for the
  transcript evidence.

If a future OpenAI API change breaks the shape again, it will surface as a WS `error` event
right after `session.update` (`adb logcat -d | grep -i lumella`, look for `Realtime transport
error:` from `MainActivity`'s `onError` listener) even though the unit tests stay green — worth
knowing, but not an open risk as of this writing.

## 플랫폼 함정 (필독)

RayNeo 하드웨어에서 실제로 겪은 함정 모음 — 네이티브 앱 등록, 터치패드 구분, AR UI 원칙,
카메라, 착용 감지 마이크, WiFi 무언 단절, cleartext, Realtime API 함정, 그리고
"토큰 오류"의 3가지 서로 다른 원인 구분: [`rayneo-platform-notes.md`](rayneo-platform-notes.md)

## 재부팅 후 자동 기동 (launchd)

세 서비스가 사용자 로그인 시 자동으로 뜬다. 수동 기동은 필요 없다.

| 에이전트 | 역할 | 설치 |
|---|---|---|
| `com.woolab.lumella.luma-api` | 코치 엔진(:8010) | `ops/launchd/manage.sh luma-install` |
| `com.woolab.lumella.luma-tunnel` | 공개 터널 + URL 재게시 | `ops/launchd/manage.sh tunnel-install` |
| `com.woolab.lumella.token-service` | 로컬 토큰 발급(:8788) | `ops/launchd/manage.sh install` |

셋 다 `KeepAlive`라 죽으면 자동 재시작한다(실측: luma-api를 SIGKILL하면 ~20초 내 새 pid로 부활).

> **왜 luma-api까지 여기서 관리하나**: 터널만 살아있고 뒤에 아무것도 없으면 글래스는
> 조용히 voice-only로 떨어지고, 원인을 가리키는 에러가 아무데도 안 남는다. 이 에이전트는
> luma 저장소를 전혀 건드리지 않고 기동만 담당한다.

상태 확인: `manage.sh luma-status` / `tunnel-status` / `status`

## 프로젝트 문서 지도

- [`STATUS.md`](STATUS.md) — 두 레포(luma/glasses) 현황과 과제 계층
- [`lumella-overview.md`](lumella-overview.md) — 2-레포 구조와 결합 규칙(HTTP `/v1` 계약 + COMPAT 핀)
- [`legacy-archive.md`](legacy-archive.md) — `TUTOR/LEGACY` 보존 트리 목록과 복원 절차
- [`rayneo-platform-notes.md`](rayneo-platform-notes.md) — RayNeo 하드웨어 함정 모음(필독)
- [`porting-map.md`](porting-map.md) — ELLA-MA에서 가져온 구성요소 대응표
- [`smoke-checklist.md`](smoke-checklist.md) — 릴리스 전 확인 항목
