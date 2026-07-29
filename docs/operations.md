# 운영 · 지속성 노트

이 시스템은 맥미니 한 대에 의존한다. **git에 없는 것**과 **맥이 죽으면 사라지는 것**을 여기 정리한다.

---

## 1. 재부팅 후 자동 기동

네 개의 LaunchAgent가 있다. 설치는 각각 한 번만 하면 된다.

| 에이전트 | 역할 | 설치 | 재시작 |
|---|---|---|---|
| `com.woolab.lumella.luma-api` | 코치 엔진 (:8010) | `manage.sh luma-install` | KeepAlive |
| `com.woolab.lumella.luma-tunnel` | 공개 터널 + URL 재게시 | `manage.sh tunnel-install` | KeepAlive |
| `com.woolab.lumella.token-service` | 로컬 토큰 발급 (:8788) | `manage.sh install` | KeepAlive |
| `com.woolab.lumella.luma-backup` | 매일 04:30 백업 | `manage.sh backup-install` | 1회성 |

상태: `manage.sh luma-status` / `tunnel-status` / `status`

> ⚠️ **LaunchAgent는 사용자 로그인 후에 뜬다.** 이 맥은 자동 로그인이 꺼져 있는 것으로 보인다
> (`/etc/kcpassword` 부재). 즉 **재부팅하면 로그인 전까지 luma·터널이 전부 죽어 있다.**
> 밖에서 쓰다가 정전이라도 나면 복구가 안 된다.
>
> 선택지:
> - 자동 로그인 켜기 — 간단하지만 물리적 접근자가 곧 로그인 상태를 얻는다
> - LaunchDaemon(`/Library/LaunchDaemons`)으로 전환 — 로그인 없이 부팅 시 뜨지만 sudo 필요,
>   그리고 터널 스크립트가 쓰는 `vercel` CLI 인증이 사용자 계정에 묶여 있어 재설계가 필요하다
>
> 지금은 **로그인 필요** 상태로 두고 이 사실을 기록해둔다.

---

## 2. 백업 (git에 없고 재생성 불가한 데이터)

`ops/backup-luma.sh` — 매일 04:30, `~/Backups/luma/`에 14일치 보관.

무엇을 지키나:
- `data/luma.db` — 사용자 332명, 학습 세션 392건, 학습자 상태, 이미지 분석 기록
- `data/uploads`, `data/raw-media` — 업로드된 이미지 (DB의 `image_analyses`가 참조하므로 함께 없으면 복원해도 깨진다)

왜 `cp`가 아니라 `sqlite3 .backup`인가: 살아있는 DB를 그냥 복사하면 쓰기 도중 상태가 섞여
**복원할 때가 되어서야 깨진 걸 알게 된다.** 백업 직후 `PRAGMA integrity_check`로 검증하고,
실패하면 그 자리에서 에러를 낸다.

복원:
```bash
ops/launchd/manage.sh luma-uninstall
tar xzf ~/Backups/luma/luma-<stamp>.tar.gz -C <luma-api>/data/   # luma.db, uploads, raw-media
ops/launchd/manage.sh luma-install
```

검증 이력: 2026-07-29 스냅샷을 별도 경로에 풀어 `integrity=ok`, users/sessions/images가
원본과 일치(332/392/37)함을 확인했다.

---

## 3. 이 맥에만 있는 비밀·설정 (백업 대상)

전부 gitignore돼 있어 저장소에는 없다. 맥이 죽으면 **다시 만들어야** 한다.

| 파일 | 담고 있는 것 | 잃으면 |
|---|---|---|
| `luma/luma-api/.env` | OpenAI 키, ETRI 설정, 모델 선택 등 37줄 | ETRI 설정 재수집 필요 |
| `glasses/token-service/.env.local` | OpenAI 키, `LUMELLA_LOCAL_TOKEN` | 토큰 서비스 공유 시크릿 재발급 → Vercel 환경변수도 함께 교체 |
| `glasses/local.properties` | 토큰 서비스 주소, 공유 시크릿, 데모 계정 | 앱 재빌드 설정 |
| `ELLA/local.properties` | Vercel 엔드포인트, `REALTIME_TOKEN_SECRET` | ELLA 3개 엔드포인트 인증 재설정 |

**API 키 자체는 제공자 대시보드에서 재발급 가능하지만, 우리가 생성한 공유 시크릿은 아니다.**
Vercel 환경변수(`LUMELLA_LOCAL_TOKEN`, `REALTIME_TOKEN_SECRET`, `LUMA_BASE_URL`)와 짝이 맞아야
하므로, 한쪽만 잃어도 양쪽을 새로 맞춰야 한다.

→ 이 네 파일은 암호 관리자나 개인 백업에 따로 보관할 것. (저장소에 넣지 말 것)

---

## 4. 알려진 취약점

| 항목 | 현황 | 영향 |
|---|---|---|
| 데모 계정 `luma1234` | 터널로 공개망 노출, **로그인 시도 제한 없음** | 대입 공격 시 크레딧 소모 API 탈취 |
| `/docs`, `/openapi.json` | 공개 | 42개 경로 전부 노출(공격 지도) |
| quick tunnel URL | 재시작마다 변경 | 자동 재게시로 흡수되나, 앱 실행 중 변경 시 다음 실행까지 구 URL 사용 |
| 집 인터넷 | 단일 경로 | 끊기면 코치 불가(음성은 Vercel이라 유지) |

가장 시급한 것은 **데모 계정 비밀번호**다. luma 코드를 건드리지 않고 DB와 앱 설정만 바꾸면 된다.
