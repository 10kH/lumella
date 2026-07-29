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
| R4 오늘의 표현 저장 | **부분가능** | 데이터는 메모리에 존재하나 **버려지고**, 저장소가 없음 |

**어느 것도 기술적으로 막혀 있지 않다.** 다만 R2·R3·R4는 "품질을 올리는" 문제가 아니라
"경로를 새로 잇는" 문제다. 프롬프트만 고쳐서 되는 건 R3 일부뿐이다.

### 대상 확정: ELLA

요구사항은 **영어 원어민 친구**이므로 대상은 `TUTOR/ELLA`다.

> **초판 정정**: 이 문서 초판은 ELLA와 lumella의 차이(영어/한국어, GPT/ETRI, 프롬프트 자유도)를
> "서로 반대의 결함"으로 서술했다. **틀렸다.** 그 셋은 두 제품의 **의도된 정체성**이지 고칠 대상이 아니다.
> ELLA=영어·GPT·높은 자유도, lumella=한국어·ETRI 경유. 이 요구사항은 ELLA에만 해당한다.
> 아래 판정은 ELLA 단독 기준으로 다시 매겼다.

ELLA 기준으로 바뀌는 전제:
- **luma의 주제 이력·DB를 쓸 수 없다** (연결이 없는 게 정상이다). 저장은 로컬 또는 Vercel 측 신설.
- 서버는 무상태 프록시뿐이다 — `api/`에 `_auth.js`/`judge.js`/`pedagogy-agent.js`/`realtime-token.js`,
  DB·KV 참조 **0건**.
- 앱에 영속 저장 수단이 **0건** (`SharedPreferences|Room|filesDir|openFileOutput|DataStore` 검색 무결과).

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

### 저장소 (ELLA 기준)

영속 저장소 **0건**. `LearnerStateStore`는 `AtomicReference` 하나(`LearnerStateStore.kt:22`)라
프로세스와 함께 사라진다. 즉 앱을 다시 켜면 "지난번에 무슨 주제를 했는가"가 **0**이다.

luma에는 세션별 주제 이력이 쌓이지만 **ELLA는 luma를 쓰지 않는다(설계상 정상)**. 그러므로
R4와 같은 저장소를 공유하는 게 자연스럽다 — 최근 주제 라벨 N개짜리 링버퍼면 충분하고,
DB도 서버도 필요 없다.

주제 라벨은 별도 분석을 돌릴 필요 없이 **오프너를 만들 때 모델에게 라벨을 함께 반환시켜** 저장하면 된다.

### 왜 프롬프트만으로는 안 되는가

배제 목록 없이 "새로운 주제로 시작해"라고만 지시하면 모델은 날씨·주말·취미로 수렴한다(추론).
더 근본적으로는 **그 프롬프트를 실행할 트리거(`response.create`)가 세션 시작 시점에 없다.**
따라서 R2는 (a) 오프너 트리거 신설 + (b) 최근 주제 링버퍼, 두 개가 모두 있어야 성립한다.

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

## R4. 오늘의 표현 저장 — 부분가능 (ELLA 기준 재판정)

### 좋은 소식: 데이터는 이미 손 닿는 곳에 있다

초판은 "저장할 데이터가 생성되지 않는다"고 적었으나, ELLA 기준으로 다시 보면 **원재료가 이미 메모리에 모여 있다.**

- 어시스턴트 발화(사용자가 실제로 들은 recast)는 `ellaTranscriptBuffer`에 델타로 누적된다
  (`MainActivity.kt:508`), `RESPONSE_DONE`에서 로그로 찍은 뒤 **`setLength(0)`으로 즉시 폐기**
  (`MainActivity.kt:451-453`)
- 사용자 발화 원문은 `INPUT_TRANSCRIPT_COMPLETED`에서 확보되고 `pendingTurnBinder.completeTurn(transcript)`로
  **턴 ID에 이미 묶인다** (`MainActivity.kt:515-521`)

→ 즉 **(원문, recast) 짝을 만들 재료가 같은 턴 안에 이미 갖춰져 있고, 버려지는 지점이 정확히 한 곳이다.**
포착은 `RESPONSE_DONE` 분기 한 곳에 훅을 거는 수준이다.

### 진짜 병목 두 가지

**(1) 저장소가 없다.** 앱에 영속 수단 0건, 서버는 무상태 프록시. 둘 중 하나를 신설해야 한다.

| 선택지 | 장점 | 단점 |
|---|---|---|
| 로컬(SharedPreferences/Room) | 서버 변경 0, 오프라인 동작, 개인정보가 기기 밖으로 안 나감 | 기기 교체·앱 삭제 시 소실, 웹에서 못 봄 |
| Vercel 측 신설(KV/Postgres/Blob) | 어디서든 조회, 백업 용이 | 신규 인프라 + 인증 설계 필요 |

요구사항에 "사용자 메모리에 저장"이라고만 되어 있어 **열람 경로 요구가 불명확하다.**
나중에 꺼내 보는 화면이 필요한지가 이 선택을 가른다 — 확인 필요.

**(2) 지금 생산되는 recast가 정확히 "제외 대상"이다.**
서버 프롬프트가 `"You are a grammar-analysis component ... {errors:[{span,type,recast}]}"`로
고정돼 있다(`api/pedagogy-agent.js:12-15`). 자연스러움·수준 상승 지시가 없다.
요구사항은 *"단순히 문법만 조금 수정한 표현은 제외"* 이므로, **현재 산출물은 대부분 버려야 할 것들이다.**

또한 `type`은 열거형이 아니라 모델 자유 출력이라 기계 판정이 불가하다
(관측값: `verb tense`, `tense`, `t`).

→ 프롬프트를 structured output으로 확장해 `changeKind`(문법수정/자연스러움격상/관용표현),
`reusability`, `worthSaving`를 받아야 한다. 기존 파서는 필요한 키만 읽으므로 **가법적 변경**이다.

### "하루의 끝" 감지

- 훅은 있다: `exitApp()`(`MainActivity.kt:973`), `onDestroy()`(`:1214`)
- 그러나 강제종료·크래시·저메모리 킬에서는 `onDestroy`가 보장되지 않는다
- ELLA 서버에는 스케줄러가 없다(무상태)

→ 로컬 저장을 택하면 **턴마다 즉시 append**하고 "하루의 끝"은 단지 *선별·요약 시점*으로 다루는 편이
안전하다. 종료 훅에 저장을 의존하면 앱이 죽는 순간 그날 학습이 통째로 사라진다.

### 번역 요청 식별

요구사항의 두 번째 저장원(*한국어 → 영어 번역 요청 시 제공된 문장*)은 현재 별도 신호가 없다.
다만 한글 포함 판정 코드가 이미 있어(`pedagogy/SteeringComposer.kt:18-22 containsKorean`)
사용자 전사에 적용해 `source=translation` 태깅에 재사용할 수 있다.

## 권고 순서 (ELLA 기준)

1. **R1 핸즈프리** — 재료가 이미 다 있고 체감 효과가 가장 크다.
   단 **연속 스트리밍 비용을 먼저 실측**할 것. 과금 구조가 탭 방식과 근본적으로 다르다.
2. **R3 페르소나 경로** — 문자열 변경만으로 같은 턴 recast 달성.
   동시에 slow path의 즉시 교정 주입을 끄고 역할을 R4로 넘긴다(이중 지적 방지).
3. **R4 포착 배선** — `RESPONSE_DONE`에서 버려지는 `ellaTranscriptBuffer`를
   턴 ID로 사용자 원문과 짝지어 남긴다. 저장소는 로컬로 시작해도 충분하다.
   이어서 pedagogy 프롬프트를 structured output으로 확장해 품질 필터를 만든다.
4. **R2 오프너** — 오프너 트리거 신설 + R4와 같은 로컬 저장소에 최근 주제 링버퍼.

R3와 R4는 같은 데이터(원문↔recast 짝)를 쓰고, R2와 R4는 같은 저장소를 쓴다.
따라서 **R3+R4를 묶고, R2를 그 저장소 위에 얹는** 순서가 중복 작업이 없다.

### 열린 질문 (요구사항 문서만으로는 판단 불가)

- **"사용자 메모리에 저장"의 열람 경로**: 나중에 앱/웹에서 꺼내 보는 화면이 필요한가?
  필요하면 저장소가 서버여야 하고, 아니면 로컬로 충분하다.
- **이어폰 효과음의 구체 형태**: 어떤 상태 전환에 어떤 소리인가. 현재 자산·트리거 모두 없다.
- **"하루"의 경계**: 자정인가, 마지막 대화 후 N시간인가, 앱 종료인가.

## 검증 방법 기록

- 실기기 계측: RayNeo X3 Pro `A06B4A043084773`, `adb shell cat /vendor/etc/audio_effects.xml`
- 라이브 API 프로브: `wss://api.openai.com/v1/realtime?model=gpt-realtime`에
  `session.update`(server_vad 500ms + tools) 전송 후 `session.updated` 에코백 확인
- 코드 조사: architect 3개 병렬 세션, 읽기 전용, 근거를 파일:행으로 요구
