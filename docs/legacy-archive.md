# TUTOR/LEGACY — 보존 아카이브

이동일: 2026-07-21. 승인 근거: ralplan 합의 계획 (세션 019f81e8, pending-approval.md) + 사용자 승인.

> 이 문서는 원래 `TUTOR/LEGACY/README.md`로 버전관리 밖에 있었다. `TUTOR/`와 `TUTOR/lumella/`는
> 중첩 repo 파손을 막으려고 일부러 git repo가 아니어서, 그 자리에 둔 문서는 디스크에만 존재했다.
> 2026-07-28에 이 레포로 옮겨 추적되게 했다.

> **경고: 이 디렉토리 트리 위에서 `git init` / 재초기화 금지.**
> 하위 항목들은 각자 독립 git repo다. 상위에 repo를 만들면 중첩 repo가 파손된다.

| 항목 | HEAD (이동 전=후 검증됨) | 원격 | 사유 |
|---|---|---|---|
| `luma-codex/` | `4104729` (clean) | https://github.com/10kH/luma-codex.git | luma의 1개월 전 사본. 현행 luma는 `TUTOR/lumella/luma`로 이관 예정(P2). |
| `ELLAs/` | `abdfb8e` (clean) | https://github.com/10kH/ELLAs.git | 단일 에이전트 ELLA 동결 백업. **GitHub에서도 archived 처리됨(2026-07-28)** — 원격이 읽기 전용이라 실수로 못 바꾼다. |
| `ELLAL/` | `8c6c1c5` (clean) | https://github.com/10kH/ELLAL.git | 완전 로컬 변종(Whisper/Ollama/Kokoro) 프로토타입, 5개월 휴면. |

> `ELLA/`는 2026-07-28에 **`TUTOR/ELLA`로 나갔다** — 현역으로 다시 쓰고 있어서 아카이브 대상이 아니다.
>
> `ELLA-hermes/`는 2026-07-28에 **로컬에서 삭제**했다(hermes 브레인 실험 중단 판정). 로컬에만 있던
> 커밋 11건을 먼저 푸시해 원격 `31bff64`로 보존한 뒤, HEAD와 원격 SHA 일치를 확인하고 지웠다.
> 되살리려면 `git clone https://github.com/10kH/ELLA-hermes.git`. launchd 에이전트
> `com.woolab.ella.hermes-local-api`는 삭제 전에 정상 제거했다(포트 8787 해제 확인).

## 복원 절차

```bash
# 남아있는 트리는 이동만 하면 된다 (각자 독립 repo다)
mv ~/workspace/TUTOR/LEGACY/ELLAs ~/workspace/ELLAs
git -C ~/workspace/ELLAs rev-parse --short HEAD   # abdfb8e 확인

# 이미 삭제한 트리는 원격에서 다시 받는다
git clone https://github.com/10kH/ELLA-hermes.git ~/workspace/ELLA-hermes
```

## 이동 시 참조 감사 기록 (2026-07-21)

- 잔류 프로젝트(ELLA-hermes, luma, 기타) 및 `~/Library/LaunchAgents/*.plist`에서 이동 대상 절대경로 참조 검색 결과: **런타임 참조 0건**.
  - `ELLA-hermes/docs/hermes/contract/fixtures/operator-execute-reject-absolute-outside-root.json` — 경로 거부 테스트용 적대적 예시 문자열(런타임 의존 아님). 무해 판정, 원문 유지.
  - 문서 참조 2건은 이동에 맞춰 갱신함: `ELLA-hermes/BACKUP_INFO.md`(source/backup 경로), `ELLA-hermes/docs/hermes/ELLA-MA-handoff.md`(repo root 경로).
  - `com.woolab.ella.hermes-local-api.plist`는 ELLA-hermes(잔류)만 참조.
- 이동한 4개 트리 **내부**의 자기참조 절대경로(예: ELLA 문서의 `/Users/woody/workspace/ELLAs`)는 4개가 함께 이동해 상대 일관성이 유지되므로 수정하지 않음(문서 기준점만 LEGACY로 바뀜).
- 보안 조치: `ELLAL/.git/config` origin URL에 평문 GitHub PAT(`ghp_…`)가 동결돼 있던 것을 발견, 토큰 없는 URL로 스크럽 완료(untracked 메타데이터라 내용 불변 원칙 무관). **해당 PAT는 GitHub에서 폐기(revoke) 필요 — 사용자 액션.**
