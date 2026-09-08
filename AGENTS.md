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

## luma와의 결합

```kotlin
implementation(project(":tutor-contract"))   // 계약만 컴파일 시점
runtimeOnly(project(":luma-adapter"))        // luma를 아는 쪽은 실행 시점만
```

`DependencyRuleGuardTest`가 이 규칙을 강제합니다. `runtimeOnly`를 `implementation`으로
바꾸면 테스트가 깨집니다 — 의도된 것이니 되돌리십시오.

luma가 없어도 앱은 뜹니다(`NoOpBrain` 폴백). 코치 기능만 빠집니다.

## 주소는 실행 중에 받습니다

```
1. GET lumella-token.vercel.app/v1/config  → 현재 luma 주소
2. 실패하거나 그 주소가 죽었으면 → APK에 구운 값
```

`/v1/config`의 값을 바꾸면 **모든 안경이 즉시 따라갑니다.** 촬영 중이면 대화가 끊기니
먼저 물어보십시오. `ops/publish-lan-address.sh`가 5분마다 이 값을 감시합니다.

## 빌드·테스트

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew :app:testDebugUnitTest :luma-adapter:test :contract-tests:test --rerun-tasks
```

`--rerun-tasks` 없으면 `UP-TO-DATE`로 실제 실행되지 않습니다. 344건이 기준선입니다.
(2026-09-08 맥북 실측 352건 전부 통과.)

`/usr/libexec/java_home -v 17`은 기계에 따라 실패합니다. Homebrew `openjdk@17`은 keg-only라
심볼릭 링크가 없으면 `java_home`이 못 찾습니다. 위처럼 keg 경로를 직접 주십시오.
안드로이드 스튜디오 번들 JBR(21)은 `jvmToolchain(17)`을 만족하지 못합니다.

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
