# P5 E2E 게이트 결과 (2026-07-21)

환경: JDK 17 (Temurin 17.0.18), Android SDK android-34, luma HEAD `da111e5`(+미커밋 W-1/W-2), RayNeo X3 Pro 실기기 (adb `A06B4A043084773`).

| # | 게이트 | 결과 | 증거 |
|---|---|---|---|
| 1 | `./gradlew assembleDebug` | ✅ PASS | `app-debug.apk` 7,271,505B 생성 |
| 2 | `./gradlew test` 전 모듈 | ✅ PASS | :app 74+ / :tutor-contract 7 / :luma-adapter 21 / :contract-tests 4, 실패 0 |
| 3 | 라이브 fixture + capabilities + COMPAT | ✅ PASS | `validate-fixtures.mjs --live` 7/7 (steering-nonempty 포함), COMPAT 핀 `da111e5` 일치. turn-default는 key-free 비결정성으로 2회 재시도 후 통과(문서화된 RETRYABLE 경로) |
| 4 | 온디바이스 스모크 | 🟡 PARTIAL | 설치 `Success` + 기동 성공(pid 8292, fullscreen visible). **풀 체크리스트(음성 왕복→코치 스티어링→이미지 턴→degrade→재연결→RE_ANCHOR)는 디바이스 배선 미구현으로 미실행** → 서브골 G006(P5.4b)으로 이관 |
| 5 | 비밀 감사 | ✅ PASS | 실키 grep 0건, `.env.local`/`local.properties` 미추적(gitignore 3건 확인) |

## 게이트 4 상세

- 완료: `adb install -r` → Success; LAUNCHER 기동 → `com.woolab.lumella` pid 8292, Task visible=true fullscreen.
- 미실행 사유: `MainActivity`는 P2 placeholder, `RealtimeTransport`는 인터페이스만 존재(P3에서 디바이스 배선 의도적 이연). 풀 스모크는 G006에서:
  1. RealtimeTransport 실구현 (OpenAI realtime WS, token-service 경유)
  2. RayNeo 오디오/터치/카메라 배선 + LumaTutorBrain 런타임 DI
  3. 풀 체크리스트 재실행 (fast-path 왕복은 `token-service/.env.local`의 OPENAI_API_KEY 필요; 실음성 턴은 착용자 필요 — 마이크 근접 게이팅)

## 재실행 명령

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
cd TUTOR/lumella/glasses
./gradlew assembleDebug test
# luma-api 기동 후:
node contract-tests/validate-fixtures.mjs --live http://127.0.0.1:8010
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## G006 배선 후 갱신 (2026-07-21, 2차 패스)

디바이스 배선 완료: OpenAI realtime WS transport(단일 instructions 채널), RayNeo 오디오/터치/카메라, BrainFactory 런타임 DI(NoOpBrain fail-closed), MainActivity 상태머신(CONNECTING/READY/DEGRADED/TOKEN-FAIL), network_security_config(범위 한정 cleartext). 전 모듈 테스트 green(:app 108).

실기기 재검증(A06B4A043084773, pid 8648):
- 설치·기동·생존 ✅, cleartext 차단 오류 0건(스코프 설정 적용 확인)
- 무키 상태 기대 동작 ✅: `status=CONNECTING → TOKEN-FAIL`, 사유 `Failed to connect to /127.0.0.1:8788`(connection refused — 정직한 fail-closed, 무크래시)

### 잔여 항목 (human-only 의존)
| 항목 | 필요한 사용자 액션 |
|---|---|
| fast-path 라이브 왕복 → 코치 스티어링 → 이미지 턴 → degrade/재연결/RE_ANCHOR 풀 체크리스트 | ① `token-service/.env.local`에 `OPENAI_API_KEY` 제공 ② `local.properties`의 base URL을 Mac LAN IP로 + `network_security_config.xml`에 LAN IP 1줄 추가 ③ token-service·luma-api 기동 |
| 실음성 턴 검증 | RayNeo **착용자** 필요 (마이크 근접 게이팅 — 미착용 시 무음) |
| 첫 라이브 런 확인사항 | session.update(GA shape + beta header) 수락 여부 — WS error 이벤트 감시 (dev-loop.md 명시) |

## 60분 세션 만료 자가복구 (2026-07-21 3차 패스)

1시간 소크 중 실측: OpenAI realtime 하드 리밋 `session_expired`("maximum duration of 60 minutes") → CLOSED 후 복구 불가 확인. 수정: OpenAiRealtimeTransport 자동 재연결(비클라이언트 종료 시 1s→2s→…30s 캡 백오프, READY 시 리셋, 신선한 ephemeral 토큰 재발급, close()는 재연결 억제, onClosing/onClosed 중복 시 단일 예약). 유닛 테스트 4종 추가, 실기기 재배포 후 READY 재확인.

### 자가복구 라이브 실증 (2026-07-22 09:03, 실기기)

60분 경계에서 자동 재연결 실증: `09:03:14.622 session_expired→CLOSED` → `09:03:15.632 CONNECTING`(백오프 1.0s 정확) → `09:03:17.502 READY`(신선 토큰+새 WS). 앱 무재시작(pid 유지), 총 복구 시간 ~2.9s. 스모크 체크리스트의 "토큰 만료→재연결 성공(D-6)" 항목의 세션-만료 변형이 라이브 증거로 충족됨.
