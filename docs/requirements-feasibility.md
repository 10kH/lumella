# 「원어민 친구 에이전트」 요구사항 — 기술 실현가능성 검증

대상: `TUTOR/요구사항.md` (황금하, 2026-07-28 15:57)
검증일: 2026-07-29 · 방법: 코드베이스 정독 + 실기기 계측 + 라이브 API 프로브

> 이 문서는 **구현이 아니라 판정**이다. 각 항목은 근거(파일:행 또는 실측 출력)를 달았고,
> 확인된 사실과 추론을 구분했다.

---

## 총평

| 요구 | 판정 | 진짜 병목 |
|---|---|---|
| R1 핸즈프리 상태 전환 | **가능** | 없음. 재료가 이미 다 있고 미사용 상태 |
| R2 매번 새로운 주제 | **부분가능** | 에이전트가 먼저 말하는 경로가 **아예 없음** |
| R3 먼저 recast | **부분가능(조건부)** | 같은 턴 안에서는 slow path로 **구조적 불가** |
| R4 오늘의 표현 저장 | **부분가능** | 저장할 데이터가 **생성조차 되지 않음** |

**어느 것도 기술적으로 막혀 있지 않다.** 다만 R2·R3·R4는 "품질을 올리는" 문제가 아니라
"경로를 새로 잇는" 문제다. 프롬프트만 고쳐서 되는 건 R3 일부뿐이다.

### 선행 결정: 어느 앱을 베이스로 삼는가

이 결정이 나머지 전부의 난이도를 바꾼다. 지금 살아있는 두 앱은 요구사항에 대해 **정확히 반대의 결함**을 갖는다.

| | `TUTOR/ELLA` | `TUTOR/lumella/glasses` |
|---|---|---|
| 언어 방향 | **영어 튜터** (요구사항과 일치) | 한국어 튜터로 반전됨 |
| 백엔드 | luma **미연결** (검색 0건) | luma 완비 (인증·턴·이미지·세션종료) |
| 영속 저장소 | 없음 | luma DB 20테이블 |
| 프롬프트 위생 | 프로덕션 `SYSTEM_PROMPT`에 NSFW 지시 다수 | 문제 없음 |

- ELLA 기반 → R2·R4에 **저장소를 새로 만들어야** 한다(로컬 SharedPreferences 수준으로 가능)
- lumella 기반 → 저장소는 있으나 **언어 방향을 되돌리는** 작업이 별도로 필요하다

---

## R1. 핸즈프리 상태 전환 — 가능

요구: 터치 없이 Ready → Listening → Speaking → Ready, 촬영은 음성으로.

### 확인된 사실

| 항목 | 결과 | 근거 |
|---|---|---|
| 에코 제거(AEC) | **하드웨어 지원** | 기기 `/vendor/etc/audio_effects.xml`의 `<preprocess><stream type="voice_communication"><apply effect="aec"/><apply effect="ns"/>` — Qualcomm Fluence |
| VAD 이벤트 | **이미 파싱됨, 미사용** | `RealtimeProtocol.kt:45-46` speech_started/stopped 매핑 존재. 앱 핸들러 검색 결과 0건 |
| 짧은 침묵 임계 | **수용됨** | 라이브 프로브: `silence_duration_ms:500` 그대로 에코백 (현재 앱은 10000) |
| 음성 명령 촬영 | **수용됨** | 라이브 프로브: `tools:[capture_photo]` 등록 성공, `tool_choice:auto` |
| 끼어들기 | **지원** | 서버 에코백에 `interrupt_response:true` |

### 핵심: D-4를 깨지 않고 달성된다

핸즈프리라고 해서 `create_response:true`(서버 자동 응답)로 바꿀 필요가 없다.
`create_response:false`를 유지한 채 **`speech_stopped` 이벤트를 받아 앱이 `response.create`를 발행**하면
자동 턴 전환과 조종 텍스트 주입 채널을 **둘 다** 지킬 수 있다.

이게 중요한 이유: `create_response:true`로 가면 서버가 알아서 응답을 만들어버려
per-turn instructions를 실을 지점이 사라지고, D-4 불변식(두뇌 텍스트는 조종으로만 관여)이 무너진다.

### 필요한 변경

1. `AudioSource.MIC` → `VOICE_COMMUNICATION` (AEC/NS 활성화) — `AudioCapture.kt:44`
2. 연속 마이크 스트리밍 (현재는 탭에서만 시작 — `MainActivity.kt:169, 371`)
3. `speech_stopped` 핸들러 → `response.create` 발행
4. `silence_duration_ms` 10000 → 500~800
5. `capture_photo` tool 등록 + `function_call` 이벤트 처리

### 미검증 / 위험

- **연속 스트리밍 비용**: 현재는 탭한 구간만 업로드하지만 핸즈프리는 상시 업로드다.
  구조상 과금이 상시 발생한다. **실측 필요** — 이 문서는 추정치를 적지 않는다.
- 오프너 재생 중 자기 목소리 유입: AEC가 완화하나 순서 설계 필요(응답 완료 후 녹음 시작)
- 이어폰 효과음: 요구사항의 "이어폰에서는 음성 안내나 효과음" — 현재 재생 경로는
  `AudioPlayback` 하나뿐이고 효과음 자산·트리거가 없다. 신규 작업이나 난이도는 낮다.

---

## R2. 매번 새로운 주제 — 부분가능

### 핵심 발견: 오프너 경로가 존재하지 않는다

요구사항은 "오프너가 단조롭다"를 고치라는 것이지만, **현재는 오프너 자체가 없다.**

- 두 앱 모두 `create_response:false`로 세션을 연다
  (`ELLA MainActivity.kt:583-588`, `lumella OpenAiRealtimeTransport.kt:436-437`)
- `response.create`가 나가는 유일한 지점은 **사용자 발화 커밋 직후**
  (`ELLA MainActivity.kt:788-802`, `lumella MainActivity.kt:341-349`)
- 세션 준비 완료 핸들러는 상태 텍스트만 바꾼다 (`ELLA MainActivity.kt:412-418`)

→ **사용자가 먼저 말하지 않으면 대화가 시작되지 않는다.** "오늘 어때"조차 나오지 않는다.

### 저장소

- 글래스 앱: 영속 저장소 **0건**. `SharedPreferences`/`Room`/`filesDir` 검색 무결과.
  `LearnerStateStore`는 `AtomicReference` 하나(`LearnerStateStore.kt:22`), 프로세스와 함께 소멸.
- luma 서버: **이미 있다.** `orchestrator_sessions.title`(매 턴 갱신, `service.py:1056`),
  `learner_states.interest_scores`(`learner_state.py:309-319`).
  조회 API도 열려 있다 — `GET /v1/archive/orchestrator`(최근 20세션 주제), `GET /v1/learner-state`.
  → **DB 스키마 변경 불필요.**

### 죽은 계약

`BrainSession.starterPrompt`(`TutorBrain.kt:16-20`)는 "오프너를 서버가 내려준다"는 설계 의도가
남아 있으나 **생산자·소비자가 모두 없다**. 살려 쓰면 된다.

### 역풍

luma의 개인화 정책이 **연속성 편향**이다 — `_continuity_guidance`의 모든 분기가
"기존 관심사 재사용 / 주제 전환 전에 한 번 더 파고들기"를 지시한다(`personalization.py:117-145`).
게다가 `interest_scores`에 감쇠가 없어 상위 주제가 고착된다.
다양성과 목적함수가 반대이므로, 오프너 경로에서는 이 정책을 우회해야 한다.

---

## R3. 먼저 recast — 부분가능(조건부)

### 구조적 사실: 같은 턴 안에서는 slow path로 불가

turn N의 `response.create`는 **turn N의 전사가 도착하기 전에** 전송된다.
따라서 turn N의 발화 분석 결과가 turn N의 응답에 반영될 방법이 현재 코드에 없다.

현행 교정 도달 경로(ELLA):
```
발화 → commit → response.create(N)   ← 여기서 이미 응답 생성 시작
     → 전사 도착 → slowPathQueue
     → RESPONSE_DONE 후에야 drain      ← 모델이 말을 다 끝낸 뒤 분석 출발
     → 다음 턴 N+1 지시문에 "Try: ..." 주입
```
지연 하한 **1턴**. 오프라인 하네스 실측은 100% age=1이나, 그 하네스는 slow path를
동기 완료시키도록 설계돼 있어(`BlockingPedagogyAgentClient`) 구조상 1이 나올 수밖에 없다.
**실기기 왕복 실측치는 저장소에 없다.**

### 실현 경로: 실시간 모델에게 직접 지시

같은 턴 recast가 가능한 **유일한** 경로는 실시간 모델 페르소나다. 모델이 오디오를 직접 듣기 때문에
전사를 기다릴 필요가 없다. 추가 지연 0ms, D-4 무해.

**함정**: `response.create.instructions`는 session instructions를 **덮어쓴다**
(`CleanTutorPersona.kt:28-33`에 명시). 규칙을 SESSION_PERSONA에만 넣으면
조종이 발생하는 턴 — 정확히 교정이 필요한 턴 — 에서 규칙이 증발한다.
**PERSONA_SUMMARY에도 반드시 넣어야 한다.**

### 역할 중복 해소

모델이 즉시 recast하고 slow path도 "Try: ..."를 주입하면 같은 오류가 두 번 지적된다 —
요구사항의 "따라하시오 금지" 취지를 이중으로 위반한다.

→ slow path를 **즉시 교정에서 손 떼게 하고** R4용 표현 선별·장기 추적으로 재정의하는 것이 맞다.
이는 기존 설계 주석("fast lane IS the realtime model itself")과도 정합적이다.

### D-4 가드에 구멍이 있다 (별건이지만 기록)

현재 불변식 테스트는 `RealtimeTransport` 인터페이스의 **메서드 수**만 검사한다.
구체 클래스에 `conversation.item.create` + `role:"assistant"`를 넣으면
**테스트는 초록불인데 불변식은 위반**된다. 가드를 "assistant role 아이템을 생성하지 않음"까지
확장할 것을 권고한다.

---

## R4. 오늘의 표현 저장 — 부분가능

### 핵심 발견: 저장할 데이터가 생성되지 않는다

요구사항이 저장하라는 것은 **사용자가 실제로 들은 자연스러운 recast 문장**이다. 그런데:

- ELLA: 어시스턴트 발화를 Logcat에 찍고 **즉시 폐기** (`MainActivity.kt:451-453`)
- lumella: `AUDIO_TRANSCRIPT_DELTA`를 파싱해놓고 `else -> Unit`으로 **폐기**
  (`OpenAiRealtimeTransport.kt:387-418`)
- `TurnEvidence.assistantTranscript` 필드는 계약에 있으나 **아무도 채우지 않는다**
  (`TurnEvidenceAssembler.kt:31-35`, `LumaTutorBrain.kt:135-142`)

즉 "오늘 배운 표현"의 원재료가 파이프라인 어디에도 남지 않는다.

### 기존 테이블 재사용 — 전부 부적합

| 후보 | 판정 |
|---|---|
| `learner_states.review_queue` | 허용값이 3개 스킬로 고정(`learner_state.py:22-26`). 표현 문장은 조용히 drop |
| `session_summaries.top_corrections` | 이슈 라벨 + 상한 3개. 게다가 **안경 자유대화는 legacy 세션을 만들지 않아 요약 자체가 생성되지 않음** |
| `image_analyses.key_expressions` | 이미지 분석 전용 |

→ **신규 테이블 필요.** 비용은 낮다(`create_all` 자동 생성, 선례 존재).
단 연구 데이터셋 보존정책(`retention_until`)에 묶으면 만료 삭제되므로 정책 분리 필요.

### 품질 필터("단순 문법 수정 제외") — 현재 데이터로는 불가

- 서버 프롬프트가 **"문법 분석 컴포넌트"로 고정**돼 있다(`api/pedagogy-agent.js:12-15`).
  자연스러움/수준 상승 지시가 없으므로, **생산되는 recast가 정확히 요구사항의 제외 대상이다.**
- `type`은 열거형이 아니라 모델 자유 출력이라 정규화 불가(관측값: `verb tense`, `tense`, `t`)
- lumella는 더 나쁘다 — coach corrections는 ETRI 시나리오 라우트에서만 나오고,
  자유대화는 "NEVER produce corrections"라고 docstring에 명시(`coach.py:11-13`)

→ 프롬프트를 structured output으로 확장해 `changeKind`/`reusability`/`worthSaving`을 받아야 한다.
기존 파서는 필요한 키만 읽으므로 **가법적 변경**이며 기존 경로를 깨지 않는다.

### "하루의 끝" 감지

- `endSession` 훅은 있고, UI 스레드 결함은 수정됨(`MainActivity.kt:534-543`)
- 그러나 강제종료/크래시/저메모리 킬에서는 `onDestroy`가 보장되지 않는다
- luma에 스케줄러가 **0건** — 서버가 스스로 하루 종료를 판정하는 장치가 없다

→ 클라이언트 teardown을 유일 트리거로 삼으면 안 된다.
**서버측 유휴/날짜경계 롤업**으로 정의하고 클라이언트 신호는 힌트로만 쓸 것.

---

## 권고 순서

1. **베이스 앱 확정** — 이게 없으면 나머지 견적이 안 나온다
2. **R1 핸즈프리** — 재료가 다 있어 가장 확실하고, 요구사항 중 체감 효과가 가장 크다.
   단 연속 스트리밍 비용을 먼저 실측할 것
3. **R3 페르소나 경로** — 문자열 변경만으로 같은 턴 recast 달성. 동시에 slow path의
   즉시 교정 주입을 끄고 역할을 R4로 이관
4. **R4 데이터 포착** — 어시스턴트 발화 캡처 배선(`assistantTranscript`)부터.
   저장 스키마와 품질 필터는 그 다음
5. **R2 오프너** — 오프너 경로 신설 + 최근 주제 배제 목록

R3와 R4는 같은 데이터 파이프를 공유하므로 묶어서 진행하는 편이 낫다.

---

## 검증 방법 기록

- 실기기 계측: RayNeo X3 Pro `A06B4A043084773`, `adb shell cat /vendor/etc/audio_effects.xml`
- 라이브 API 프로브: `wss://api.openai.com/v1/realtime?model=gpt-realtime`에
  `session.update`(server_vad 500ms + tools) 전송 후 `session.updated` 에코백 확인
- 코드 조사: architect 3개 병렬 세션, 읽기 전용, 근거를 파일:행으로 요구
