# TUTOR/LEGACY — 보존 아카이브

이동일: 2026-07-21. 승인 근거: ralplan 합의 계획 (세션 019f81e8, pending-approval.md) + 사용자 승인.

> 이 문서는 원래 `TUTOR/LEGACY/README.md`로 버전관리 밖에 있었다. `TUTOR/`와 `TUTOR/lumella/`는
> 중첩 repo 파손을 막으려고 일부러 git repo가 아니어서, 그 자리에 둔 문서는 디스크에만 존재했다.
> 2026-07-28에 이 레포로 옮겨 추적되게 했다.

> **경고: 이 디렉토리 트리 위에서 `git init` / 재초기화 금지.**
> 하위 항목들은 각자 독립 git repo다. 상위에 repo를 만들면 중첩 repo가 파손된다.

| 항목 | HEAD (이동 전=후 검증됨) | 원격 | 사유 |
|---|---|---|---|
| `ELLAs/` | `abdfb8e` (clean) | https://github.com/10kH/ELLAs.git | 단일 에이전트 ELLA 동결 백업. **GitHub에서도 archived 처리됨(2026-07-28)** — 원격이 읽기 전용이라 실수로 못 바꾼다. |

> `ELLA/`는 2026-07-28에 **`TUTOR/ELLA`로 나갔다** — 현역으로 다시 쓰고 있어서 아카이브 대상이 아니다.

## 2026-07-28에 완전 폐기한 것 (복구 불가)

아래는 로컬과 GitHub **양쪽 모두** 삭제했다. 사용자 판단으로 되살릴 필요가 없다고 결정한 것들이며,
사본이 남아있지 않다.

| 저장소 | 마지막 HEAD | 폐기 사유 |
|---|---|---|
| `ELLA-hermes` (id 1272852074) | `31bff64` (2026-07-28) | hermes 브레인 실험 중단. 로컬 전용 커밋 11건을 먼저 푸시해 보존한 뒤, launchd 에이전트 `com.woolab.ella.hermes-local-api` 제거(포트 8787 해제 확인) → 로컬 삭제 → 원격 삭제 순으로 진행. |
| `ELLAL` (id 1144695381) | `8c6c1c5` (2026-01-29) | 완전 로컬 변종(Whisper/Ollama/Kokoro) 프로토타입, 6개월 휴면. 고유 데이터는 `server/data/ellal_memory.db`(2.5MB, 1월 기록)뿐이었고 함께 폐기. |

`luma-codex`는 **삭제하지 않는다.** 독립 저장소가 아니라 현행 `10kH/luma`로 이름이 바뀐 뒤 남은
리다이렉트다(API가 `Moved Permanently` 반환, `ls-remote`에 luma의 브랜치·PR이 그대로 보인다).
그 이름으로 삭제를 시도하면 **현행 엔진 저장소가 지워진다.**

## 복원 절차

```bash
# 남아있는 트리(ELLAs)는 이동만 하면 된다 — 독립 repo다
mv ~/workspace/TUTOR/LEGACY/ELLAs ~/workspace/ELLAs
git -C ~/workspace/ELLAs rev-parse --short HEAD   # abdfb8e 확인
```

`ELLAs`의 GitHub 저장소는 archived 상태다. 수정하려면 먼저 unarchive해야 한다 —
"동결 baseline, 내용 불변" 원칙을 플랫폼이 강제하도록 일부러 걸어둔 것이다.

## 이동 시 참조 감사 기록 (2026-07-21)

- 잔류 프로젝트(ELLA-hermes, luma, 기타) 및 `~/Library/LaunchAgents/*.plist`에서 이동 대상 절대경로 참조 검색 결과: **런타임 참조 0건**.
  - `ELLA-hermes/docs/hermes/contract/fixtures/operator-execute-reject-absolute-outside-root.json` — 경로 거부 테스트용 적대적 예시 문자열(런타임 의존 아님). 무해 판정, 원문 유지.
  - 문서 참조 2건은 이동에 맞춰 갱신함: `ELLA-hermes/BACKUP_INFO.md`(source/backup 경로), `ELLA-hermes/docs/hermes/ELLA-MA-handoff.md`(repo root 경로).
  - `com.woolab.ella.hermes-local-api.plist`는 ELLA-hermes(잔류)만 참조.
- 이동한 4개 트리 **내부**의 자기참조 절대경로(예: ELLA 문서의 `/Users/woody/workspace/ELLAs`)는 4개가 함께 이동해 상대 일관성이 유지되므로 수정하지 않음(문서 기준점만 LEGACY로 바뀜).
- 보안 조치: `ELLAL/.git/config` origin URL에 평문 GitHub PAT(`ghp_…`)가 동결돼 있던 것을 발견, 토큰 없는 URL로 스크럽 완료(untracked 메타데이터라 내용 불변 원칙 무관). **해당 PAT는 GitHub에서 폐기(revoke) 필요 — 사용자 액션.**
