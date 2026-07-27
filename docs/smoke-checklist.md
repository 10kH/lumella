# P5 E2E 게이트 결과 (2026-07-21 최초 게이트, 2026-07-28 갱신)

환경: JDK 17 (Temurin 17.0.18), Android SDK android-34, luma HEAD `da111e5`(+미커밋 W-1/W-2), RayNeo X3 Pro 실기기 (adb `A06B4A043084773`).

| # | 게이트 | 결과 | 증거 |
|---|---|---|---|
| 1 | `./gradlew assembleDebug` | ✅ PASS | `app-debug.apk` 7,271,505B 생성 |
| 2 | `./gradlew test` 전 모듈 | ✅ PASS | :app 74+ / :tutor-contract 7 / :luma-adapter 21 / :contract-tests 4, 실패 0 (2026-07-28 기준 :app 226개로 증가) |
| 3 | 라이브 fixture + capabilities + COMPAT | ✅ PASS | `validate-fixtures.mjs --live` 7/7 (steering-nonempty 포함), COMPAT 핀 `d61ad3d` 일치(PR#2 머지 후 전진, 커밋 15a3db7). turn-default는 key-free 비결정성으로 2회 재시도 후 통과(문서화된 RETRYABLE 경로) |
| 4 | 온디바이스 스모크 | ✅ PASS(2026-07-23 착용 E2E) | 아래 "착용 E2E 실증" 참고 |
| 5 | 비밀 감사 | ✅ PASS | 실키 grep 0건, `.env.local`/`local.properties` 미추적(gitignore 3건 확인) |

## 게이트 4 상세 (2026-07-21 최초 패스 — 배경 기록)

- 완료: `adb install -r` → Success; LAUNCHER 기동 → `com.woolab.lumella` pid 8292, Task visible=true fullscreen.
- 당시 미실행 사유: `MainActivity`는 P2 placeholder, `RealtimeTransport`는 인터페이스만 존재(P3에서 디바이스 배선 의도적 이연). 풀 스모크는 G006에서:
  1. RealtimeTransport 실구현 (OpenAI realtime WS, token-service 경유)
  2. RayNeo 오디오/터치/카메라 배선 + LumaTutorBrain 런타임 DI
  3. 풀 체크리스트 재실행

이후 아래 항목들이 순차로 완료되어 게이트 4는 PASS로 전환됨 — 상세는 이 문서 하단 및 `dev-loop.md`.

## 재실행 명령

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
cd TUTOR/lumella/glasses
./gradlew assembleDebug test
# luma-api 기동 후:
node contract-tests/validate-fixtures.mjs --live http://127.0.0.1:8010
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## G006 배선 완료 (2026-07-21, 2차 패스)

디바이스 배선 완료: OpenAI realtime WS transport(단일 instructions 채널), RayNeo 오디오/터치/카메라, BrainFactory 런타임 DI(NoOpBrain fail-closed), MainActivity 상태머신(CONNECTING/READY/DEGRADED/TOKEN-FAIL), network_security_config(범위 한정 cleartext). 전 모듈 테스트 green(:app 108).

실기기 재검증(A06B4A043084773, pid 8648):
- 설치·기동·생존 ✅, cleartext 차단 오류 0건(스코프 설정 적용 확인)
- 무키 상태 기대 동작 ✅: `status=CONNECTING → TOKEN-FAIL`, 사유 `Failed to connect to /127.0.0.1:8788`(connection refused — 정직한 fail-closed, 무크래시)

## 60분 세션 만료 자가복구 (2026-07-21 3차 패스, 2026-07-23/28 라이브 실증으로 확증)

1시간 소크 중 실측: OpenAI realtime 하드 리밋 `session_expired`("maximum duration of 60 minutes") → CLOSED 후 복구 불가 확인. 수정: OpenAiRealtimeTransport 자동 재연결(비클라이언트 종료 시 1s→2s→…30s 캡 백오프, READY 시 리셋, 신선한 ephemeral 토큰 재발급, close()는 재연결 억제, onClosing/onClosed 중복 시 단일 예약). 유닛 테스트 4종 추가, 실기기 재배포 후 READY 재확인.

### 자가복구 라이브 실증 (2026-07-22 09:03, 실기기)

60분 경계에서 자동 재연결 실증: `09:03:14.622 session_expired→CLOSED` → `09:03:15.632 CONNECTING`(백오프 1.0s 정확) → `09:03:17.502 READY`(신선 토큰+새 WS). 앱 무재시작(pid 유지), 총 복구 시간 ~2.9s. 스모크 체크리스트의 "토큰 만료→재연결 성공(D-6)" 항목의 세션-만료 변형이 라이브 증거로 충족됨.

이후 24시간+ 무재시작 연속 가동 소크에서 동일 패턴이 **24+ 연속 사이클** 재확인됨(만료→~1s 재연결→~3s READY), 좀비 소켓 가드(만료 구소켓 미폐쇄로 인한 스퓨리어스 DEGRADED/중복 재연결)도 별도 결함으로 발견·수정·검증됨.

## 착용 E2E 실증 (2026-07-23)

첫 착용 테스트(22:00 KST)에서 실발화 전사가 luma coach 턴에 도달함을 라이브로 확인:
- 세션 `orch_36bebef`. 실발화 예: "Hi, I wanna practice my English."(영어) / "근데 우리 이거 한국어 연습하는 앱 아닌가?"(한국어) — mode=coach로 응답 도달, turnCount 3에서 시작해 이어지는 라운드에서 turn 4~7까지 서버 증거로 검증(한국어 2턴 + 영어 1턴 구성).
- 착용 중 발견·즉시 수정한 결함 2건(커밋 `0b6c811`): (1) 페르소나가 LEGACY ELLA 영어 튜터 잔재로 남아 v1 한국어 튜터링 결정과 모순 — 페르소나 교체 + code-switch 로직 반전(한국어 회피 시 한국어로 격려). (2) 무발화 탭이 `input_audio_buffer_commit_empty` 서버 오류를 유발 — 클라이언트 빈 커밋 가드 추가.
- 이후 CameraX 교체(`be295f2`, camera2 무읍 실패 원인 규명 후 LEGACY 검증 레시피로 대체), UI 복원(`ebde6e9`, 스캐폴드 밝은 테마→검정 풀스크린 미니멀 + LEGACY 색 팔레트)까지 순차 검증 완료.
- 잔여였던 좌탭 이미지 턴(imageId 배선) 실사용 검증은 착용자의 사진 촬영+발화 1회가 필요 — 아래 "최종 잔여 블로커" 참고.

## 그 외 배선/운영 변경 (2026-07-23 ~ 2026-07-28, 커밋 순)

| 항목 | 내용 | 커밋 |
|---|---|---|
| RayNeo 네이티브 앱 전환 | RayNeo가 LUMELLA를 '가상머신 앱'으로 분류하며 터치패드 팝업 표시 — 근본원인은 Mercury SDK 미연동. LEGACY ELLA 검증 방식(Mercury AAR, `LumellaApp`의 `MercurySDK.init`, `com.rayneo.mercury.app` 매니페스트 마커, `BaseMirrorActivity`로 양안 렌더링) 채택으로 해소 | `31496e5` |
| 유휴 세션 타임아웃 | 무착용 상태로 24/7 세션 유지가 낭비적(과금은 없었으나) — 10분 무동작 시 클라이언트측 소켓 종료(재연결 억제) + "Idle - tap to wake" 표시, 우측 탭으로 웨이크 후 자동 재개 | `31496e5` |
| `ACCOUNT_BLOCKED` 상태 추가 | 계정 크레딧 소진(`insufficient_quota`) 등 계정 단위 영구 오류가 4초 주기로 무한 재연결을 일으킴 — `insufficient_quota`/`invalid_api_key`/`account_deactivated` 감지 시 재연결 억제하고 별도 `ACCOUNT_BLOCKED`("No API credit") 상태로 표시. 미지 오류 코드는 기존 재시도 유지 | `e62d5f5` |
| launchd 상시실행 token-service | 수동 실행 시 셸 종료와 함께 죽어 TOKEN-FAIL을 유발하던 문제 — `ops/launchd/manage.sh install`로 LaunchAgent 등록, KeepAlive로 재기동 보장 | `e62d5f5` |
| 공개 토큰 엔드포인트 배포 | 기존엔 Mac LAN 주소로만 토큰 발급이 가능해 집 밖에서는 항상 TOKEN-FAIL — Vercel 함수(`api/realtime-token.js`)로 local token-service와 동일 계약을 공개 인터넷에 배포(`X-Lumella-Local-Token` 공유 시크릿 게이트, 상수시간 비교, 미설정 시 fail-closed 503). 라이브 검증: 무인증 401 / 오인증 401 / GET 405 / 정상 200(ms 단위 만료) | `57d4178` |

## 최종 잔여 블로커 (2026-07-28 기준)

소프트웨어·인프라·배포 측 잔여는 없음(코드·테스트·배포 전부 완료·검증됨). 남은 항목은 사람만 할 수 있는 것 3건뿐:

1. **OpenAI 계정 크레딧 충전** — 실측: `/v1/models`는 200(무과금)이나 realtime WS는 101 업그레이드 직후 `insufficient_quota`로 1013 종료. Vercel 키·사용자 제공 키 양쪽 동일 증상 → 계정 단위 문제이며 코드/배포와 무관. 충전 전까지는 어떤 코드 수정으로도 해소되지 않음.
2. **착용 발화 1회** — 크레딧 충전 후, 좌탭 사진 캡처 + 발화로 이미지 턴(`imageId` 스티어링 합류, 45s 상한)의 실사용 검증만 남음. 마이크가 착용 게이팅(미착용 시 무음)이라 사람 없이는 재현 불가.
3. **구 GitHub PAT 폐기** — LEGACY ELLAL이 쓰던 구 Personal Access Token이 아직 GitHub 측에서 살아있음(수 차례 세션에서 재확인됨) — 사용자가 GitHub 설정에서 직접 revoke해야 함.
