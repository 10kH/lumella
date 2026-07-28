# lumella 프로젝트 현황 — 2026-07-23

> 이 문서는 원래 `TUTOR/lumella/STATUS.md`로 버전관리 밖에 있었다(`TUTOR/lumella/`는 중첩 repo를
> 막으려고 git repo가 아니다). 2026-07-28에 이 레포로 옮겨 추적되게 했다.

> 우산 레벨 상태 문서 (이 디렉토리는 의도적 비버저닝 — E-C 토폴로지).
> 레포 2개: 엔진 [`10kH/luma`](https://github.com/10kH/luma) + 글래스 앱 [`10kH/lumella`](https://github.com/10kH/lumella).
> 결합은 HTTP `/v1` 계약 + `glasses/contract-tests/COMPAT.md` SHA 핀뿐.

## 한 줄 요약

RayNeo X3 Pro에서 **ELLA 외피(저지연 realtime 음성) + luma 두뇌(한국어 교수법 코치)** 구조의 글래스 튜터가
실기기에서 24시간+ 무재시작 가동 중. 서버측 전 계층 라이브 검증 완료, **착용 발화 E2E만 잔여**.

---

## 레포 1: `10kH/luma` — 튜터링 엔진

- **HEAD**: `dd7c19e` (main, 150 커밋, clean)
- **구성**: FastAPI `luma-api` + Next.js `luma-web` + `ELLA-main`(구 글래스 클라이언트, reference-frozen)

### 진척

| 영역 | 상태 |
|---|---|
| 오케스트레이터 (classify→plan→dispatch, gpt-5.4-mini) | ✅ 성숙 + 실키 라이브 검증 |
| ETRI 한국어 루트 (scenario/free/topic/reading/작문) | ✅ 성숙, 라이브 응답 확인 |
| **W-1** `responseMode=coach` → `coachEvidence` | ✅ `dd7c19e` landed — 구조화 마커만 사용(산문 채굴 금지), 발화 억제는 응답 레이어만(데이터셋 원문 보존), 라이브 검증 |
| **W-2** `GET /v1/capabilities` (공개) | ✅ landed — `{schemaRev, coach, routes[]}` |
| 테스트 | 관련 스위트 406 green (`tests/test_eval_*` 실패는 사전 존재 — stash 대조로 증명) |
| web-tolerance | luma-web 무변경·무영향 (grep 0, additive 스키마) |

### 향후 과제

1. **라이브 ETRI incorrect 마커 관찰** — 코치 corrections의 실데이터 소스. 4회 시도에서 미발생(벤더 거동, 보수적 마킹). 발생 시 live-model 등급 fixture 추가.
2. `/v1/capabilities` routes 목록을 계약 관련 서브셋으로 축소 (v2, 정보 노출 최소화).
3. FastAPI 버전 핀/레인지 명시 — capabilities 워커가 private `_IncludedRouter`에 의존 (가드 테스트로 방어 중).
4. (선택) `/v1/orchestrator/turn` 멱등 키 — 어댑터의 at-most-once 제출을 success-latch로 개선 가능하게.

---

## 레포 2: `10kH/lumella` — 글래스 앱

- **HEAD**: `5d06a41` (main, 초기 커밋 94파일, clean)
- **구성**: Gradle 4모듈 + `token-service/` + `contract-tests/fixtures/` 7종 + docs

### 아키텍처 불변식 (전부 테스트로 강제)

- **D-4**: 두뇌/스티어링 텍스트는 `response.create` instructions 단일 채널로만 발화에 관여 (단일 메서드 `RealtimeTransport`)
- **의존 규칙**: `:app`은 `:tutor-contract`만 컴파일 의존, `:luma-adapter`는 런타임 DI (`DependencyRuleGuardTest` + classpath 검증)
- **fail-closed**: 키/서버/두뇌 부재 시 가시적 degrade, 침묵·크래시·증거 조작 금지
- **비밀 경계**: `OPENAI_API_KEY`는 `token-service/.env.local`(서버측)만, 디바이스엔 ephemeral 토큰뿐 (sk- 이중 가드)

### 진척

| 모듈 | 상태 |
|---|---|
| `:app` (main 27 / test 16 파일) | ✅ ELLA-MA 15파일 포팅 + 디바이스 배선(realtime WS·오디오 24kHz·camera2·터치) + 자가복구, 106+ tests |
| `:tutor-contract` | ✅ TutorBrain 포트(connect/startSession/submit/fetchSteering/analyzeImage/endSession) |
| `:luma-adapter` | ✅ LumaTutorBrain — auth/디바이스/heartbeat/D-7 RESUME<30분/tolerant reader/coach 증류, 21 tests |
| `:contract-tests` | ✅ fixture 7종(key-free) + `validate-fixtures.mjs`(offline/--live) + steering-nonempty 기계 게이트 |
| `token-service` | ✅ :8788, epoch-ms `expiresAt` 계약, 페어링 헤더 인증 |
| COMPAT 핀 | `dd7c19e` / `v1-coach` (coach·capabilities PRESENT) |

### 라이브 실증 (실기기 RayNeo X3 Pro)

- 설치·기동·READY, **24시간+ 무재시작** 연속 가동
- **60분 세션 만료 자가복구 24+연속 사이클** (만료→~1s 재연결→~3s READY), 좀비 소켓 가드 검증
- 실토큰 민트(TTL 갱신), luma glasses 세션 활성, 무키 시 fail-closed(TOKEN-FAIL) 확인
- 라이브 코치 턴: free_chat(confidence 0.98)·scenario(실 ETRI) — W-1 계약 준수 확인

### 소크에서 잡은 결함 4건 (전부 수정·회귀 테스트·재검증)

1. `expiresAt` epoch 초/ms 단위 불일치 → 항상 만료 오판정
2. `OpenAI-Beta: realtime=v1` 헤더 → GA API `beta_api_shape_disabled` 거부
3. 60분 `session_expired` 후 재연결 부재 → 죽은 세션 종착
4. 만료 구소켓 미폐쇄 → 좀비 pinger의 스퓨리어스 DEGRADED + 중복 재연결

### 향후 과제

**즉시 (G006 마감)**
1. **착용 발화 E2E** — 사람이 쓰고 우측 탭→한국어 발화: STT 전사→realtime 응답→turn evidence→다음 턴 스티어링 합류 검증. *(유일한 잔여, 인간 전용)*
2. ELLAL 구 GitHub PAT revoke *(사용자, GitHub 설정)*

**단기 (v1 마감 후)**
3. 세션 만료-복구 중 진행 대화 연속성 (재연결 시 대화 컨텍스트 재주입 — 현재는 새 세션)
4. AudioTrack 재생을 WS 리더 스레드에서 분리 (백프레셔 큐)
5. DependencyRuleGuard를 문자열 매칭→classpath 해석 기반으로 강화
6. `BRAIN_EMAIL/PASSWORD`의 BuildConfig 노출 정리 (프로덕션 전 필수)

**중기 (v2, COMPAT 기록과 일치)**
7. `/glasses/realtime` WS 재활용 검토
8. LAN 민트 강화 (mTLS/서명 토큰 — 현 정적 헤더는 dev 전용)
9. 이미지 턴 실사용 검증 (캡처→analyzeImage→imageId 스티어링, 45s 상한)
10. ELLA-MA 논문 노선과의 접점: lumella 실사용 데이터로 코치 스티어링 밀도/타이밍 평가 (LEGACY/ELLA의 eval 하니스 재사용 후보)

---

## 운영 메모

- 로컬 가동: `luma-api` :8010 + `token-service` :8788 (Mac LAN `192.168.35.170`, persistent monitor)
- 디바이스 dev 절차: `glasses/docs/dev-loop.md` / 스모크 기록: `glasses/docs/smoke-checklist.md`
- 감사 추적: `.gjc/_session-019f81e8*/ultragoal/` (goals 6개: 5 complete + G006 착용 대기)
- 레거시 계보: `TUTOR/LEGACY/` (ELLA=ELLA-MA·EMNLP 산출물, ELLAs, ELLAL, luma-codex — 복원 절차는 LEGACY/README.md)
