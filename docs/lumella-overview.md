# lumella — 글래스 한국어 튜터 (luma 엔진 + ELLA 외피)

구성일: 2026-07-21. 승인 근거: ralplan 합의 계획(세션 019f81e8) + ultragoal 실행 런.

> **경고: 이 디렉토리(`TUTOR/lumella/`) 위에서 `git init` / 재초기화 금지.**
> 하위에 독립 git repo 2개가 중첩돼 있다. 상위는 의도적으로 비버저닝이다.

## 구조 (E-C: 중첩 독립 repo 2개)

| 경로 | 정체 | repo |
|---|---|---|
| `luma/` | 튜터링 엔진 (FastAPI `luma-api` + Next.js `luma-web`). **독립 업데이트 케이던스.** | 독립 git (HEAD 핀은 `glasses/contract-tests/COMPAT.md`) |
| `glasses/` | RayNeo X3 Pro 글래스 앱 (ELLA-MA fast/slow 외피). 원격(승인 후): `10kH/lumella` | 독립 git (신규) |

결합은 **HTTP `/v1` 계약뿐**이다. glasses는 `luma-adapter` 모듈만 luma를 알고,
`tutor-contract`의 `TutorBrain` 포트(5-op + connect) 뒤에서 소비한다.
호환성 방어: contract fixtures(steering-nonempty 의무) + `/v1/capabilities` 협상 + StalenessGuard + COMPAT.md SHA 핀.

## 아키텍처 요지

- **fast path**: OpenAI realtime 음성 세션이 대화를 단독 소유(<500ms 목표). 토큰은 luma 무관 `glasses/token-service`(:8788)가 발급.
- **slow path**: luma가 W-1 `response_mode=coach`로 교정/스티어링 증거만 공급. luma 다운/스큐/미지원 시 voice-only degrade — 튜터는 침묵하지 않는다.
- v1 제품 언어: **한국어 튜터링** (ETRI 경로 직결).

## 주의

- `luma/ELLA-main/`은 **reference-frozen** — 구 글래스 클라이언트 참고용, 빌드 비대상. 신규 개발은 전부 `glasses/`. (repo 내 README 마킹은 첫 승인 luma 커밋에 포함 예정.)
- `luma/` HEAD 이동 검증 기록: 이동 전=후 `da111e5`, clean (2026-07-21).
- 레거시 계보: `TUTOR/LEGACY/` (ELLA=ELLA-MA, ELLAs, ELLAL, luma-codex).
