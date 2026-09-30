# lumella — 에이전트 작업 규칙

안경(RayNeo X3 Pro) 한국어 튜터 앱. `com.woolab.lumella`.
단독 repo입니다 (`Woody` 83커밋). **직접 커밋해도 됩니다.**

## 계약 테스트를 믿지 마십시오

`contract-tests/`는 **고정 표본**으로만 검증합니다. 실서버를 안 봅니다.

```
표본 녹화: 2026-07-21   라우트 40개
실서버:    2026-09-01   라우트 43개
```

이미 3개 벌어져 있습니다. 지금은 추가만 있어 안 깨지지만,
luma가 라우트를 지우거나 응답 모양을 바꾸면 **테스트는 통과하고 안경에서만 죽습니다.**

luma 응답을 다루는 코드를 고쳤다면 실서버로 확인하십시오.

```bash
curl -s http://127.0.0.1:8010/v1/capabilities | head -c 200
```

## 느린 계층은 여기 없습니다

진단·해제·지시자·학습자 기록 — 슬로우 패스 전부 — 는 `../tutor-slowpath`
(github.com/10kH/tutor-slowpath)에 있습니다. `settings.gradle.kts`의 `includeBuild`가 형제
디렉터리를 가리키므로 **두 저장소를 나란히 체크아웃해야 빌드됩니다.** ELLA도 같은 라이브러리를
씁니다. 슬로우 패스를 고칠 땐 그쪽에서 고치고, 여기서는 `TutorLanguage.KOREAN` 하나만 넘깁니다.
조립은 `SlowPathAssembly.build()` 한 곳 — MainActivity와 모듈의 통합 테스트가 같은 함수를 부릅니다.

촬영 도구의 목소리 탭과 테이크 시계(`TakeClock`, `WavTap`)도 같은 방식으로 `../tutor-capture`
(github.com/10kH/tutor-capture)에 있습니다. **체크아웃은 셋이 나란히** — lumella, tutor-slowpath,
tutor-capture. 녹음·시계 로직을 고칠 땐 그쪽에서 고치고 테스트도 그쪽에 있습니다(ELLA도 씁니다).

**카메라 코드는 두 앱에 복사본이 있습니다.** `app/src/main/java/com/woolab/lumella/camera/GlassesCamera.kt` 와 ELLA app/src/main/java/com/woolab/ella/capture/GlassesCamera.kt 는 같은 코드입니다 — 사진은 찍을 때만 바인딩, POV 24fps·무음, 사진 턴 보호, 테이크 시계 이벤트. 다른 곳은 설명 주석, 스레드 이름·로그 태그, 그리고 ELLA 쪽 사진 콜백이 회전 각도를 함께 넘기는 것뿐입니다. **한쪽을 고치면 다른 쪽도 고칩니다.** tutor-capture 는 순수 JVM 이라 CameraX 코드를 담지 못해 복사로 두었습니다(안드로이드 라이브러리 모듈로 옮기는 건 남은 일).

slow path가 부르는 서버는 **ELLA 저장소의 Vercel 함수**(`api/pedagogy-agent.js`, `language=ko`)
입니다. `local.properties`의 `PEDAGOGY_AGENT_ENDPOINT`·`REALTIME_TOKEN_SECRET`은 ELLA와 같은
값이어야 합니다. luma 브레인은 슬로우 패스에 관여하지 않습니다 — 빠른 계층 스티어링, 하단
코치 힌트, 사진 업로드만 합니다.

## luma와의 결합

```kotlin
implementation(project(":tutor-contract"))   // 계약만 컴파일 시점
runtimeOnly(project(":luma-adapter"))        // luma를 아는 쪽은 실행 시점만
```

`DependencyRuleGuardTest`가 이 규칙을 강제합니다. `runtimeOnly`를 `implementation`으로
바꾸면 테스트가 깨집니다 — 의도된 것이니 되돌리십시오.

luma가 없어도 앱은 뜹니다(`NoOpBrain` 폴백). 코치 기능만 빠집니다.

## 대화는 주제로 돌아갑니다 (2026-09-30, 맥북에서 결정)

lumella 는 **주제 유도형 자유대화**(판넬의 Chat 스킬, ETRI Tango) 기준으로 짜여 있습니다.
코드의 중심은 `voice/TopicGuidance.kt`입니다.

```
대화 시작   튜터가 먼저 말한다 (앱이 켜진 첫 READY, 그리고 10분 idle 뒤 깨어난 첫 READY)
  주제 없음      "오늘은 어떤 얘기할까요?" + 예시 (지난번 고른 주제, luma 프로필 관심 주제 — 한국어로)
  주제 있음      그 주제로 첫 질문 (운영자가 --topic 으로 미리 정한 경우)
  돌아온 학습자   "계속 ○○ 얘기할까요, 다른 얘기할까요?"
주제 정하기  말로만. set_topic 도구 — 학습자가 고를 때만, 지나가는 말엔 부르지 않음, "" 은 주제 없이
주제가 정해지면  매 턴 지시에 주제 + luma 에 topicHint → 주제 대화는 Tango (topic_chat)
             Tango 의 다음 질문(coachEvidence.topicGuide, luma #13)이 다음 턴 목소리의 방향 (한 턴 뒤에서 이끎)
             안경 안내 줄에 "주제: ○○", 주제가 바뀌면 luma 세션 새로
기억        말로 고른 주제는 files/recent-topics.txt (--reset 이 지움). 운영자 preset 은 기억하지 않음
```

- 목소리는 늘 실시간 모델입니다. Tango 의 말을 그대로 읽지 않습니다(D-4). 리캐스트가 먼저인 규칙은 그대로입니다.
- 첫 질문 앞에는 system 메시지를 하나 넣습니다(`sendSystemNote`). 빈 대화에 `response.create` 를 보내면
  서버가 절반 넘게 server_error 로 실패합니다(9/30 측정 6/8 실패 → 넣은 뒤 0/8). 빼지 마십시오.
- 첫 질문은 `session.updated` 뒤에 보냅니다. READY 는 `session.created` 에서도 오지만 그때 보내면 실패합니다.
- 운영: `ops/launch-lumella.sh --topic "…"` / `--no-topic` / `--hold-opener`(촬영: `take.sh --opener` 가 녹화 뒤 첫 질문을
  시킴), `ops/shoot-preflight.sh --no-topic`(촬영) 또는 `--topic "…"`(주제를 미리 정하는 부스).
- 로그: `첫 질문: …`, `대화 주제: ○○ (음성으로 정함)`, `주제 코치 turn N: <Tango>`, `주제 유도 적용 turn N ← 코치 turn M`.
  영상 조립기(aaai27 `video-aifesta/assemble.py`)가 이 줄들을 읽습니다 — 문구를 바꾸면 거기도 바꾸십시오.
- 실측 스크립트는 aaai27 `artifacts/demo-scenario/`: `opener_probe.py`(첫 질문·주제 고르기), `topic_voice_probe.py`
  (리캐스트·주제 복귀), `topic_route_probe.py`(luma 라우팅). 페르소나를 바꾸면 다시 돌리십시오.
- 알려진 문제: 옆길(수업 밖 질문) 뒤 luma 라우터가 Tango 로 잘 안 돌아옵니다(3/12) — luma 이슈 #14.

## 주소는 실행 중에 받습니다

```
1. GET lumella-token.vercel.app/v1/config  → 현재 luma 주소
2. 실패하거나 그 주소가 죽었으면 → APK에 구운 값
```

`/v1/config`의 값을 바꾸면 **모든 안경이 즉시 따라갑니다.** 촬영 중이면 대화가 끊기니
먼저 물어보십시오. `ops/publish-lan-address.sh`가 5분마다 이 값을 감시합니다.

## 빌드·테스트

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home)"
./gradlew :app:testDebugUnitTest :luma-adapter:test :contract-tests:test --rerun-tasks
```

`--rerun-tasks` 없으면 `UP-TO-DATE`로 실제 실행되지 않습니다. 344건이 기준선입니다.
(2026-09-08 맥북 실측 352건 전부 통과.)

**두 맥의 JDK 설치 방식이 다릅니다.** 그래서 한 경로를 박지 않고 위처럼 폴백을 둡니다.

```
맥미니   Temurin 시스템 JDK    java_home -v 17 이 찾음
맥북     Homebrew openjdk@17   keg-only → java_home 실패, 폴백 경로로
```

한쪽 경로를 그대로 쓰면 다른 맥에서 죽습니다. 안드로이드 스튜디오 번들 JBR(21)은
`jvmToolchain(17)`을 만족하지 못합니다.

`LumaTutorBrainTest`의 dedupe 항목이 드물게 실패합니다. 단독 재실행으로 통과하면
간헐적 실패이니 그대로 두십시오.

## 기기 확인

```bash
adb shell "dumpsys wifi | grep -m1 'Wi-Fi is'"   # 재부팅하면 꺼져 있음
adb shell input keyevent KEYCODE_WAKEUP           # 화면 꺼졌으면 먼저
ops/screen-dump.sh                                # screencap은 순흑이라 무용
```

`am broadcast`에는 `-p com.woolab.lumella`가 필수입니다. 없으면 `result=0`이 나오는데
앱에는 안 닿습니다.
