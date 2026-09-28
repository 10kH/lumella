# 레드팀 — POV 녹화 경로 (2026-09-28)

기기에서 직접 돌린 실패 시나리오. 빌드는 c586f56 이후 수정분 포함.

| # | 시나리오 | 결과 | 조치 |
|---|---|---|---|
| 1 | 녹화 중 `DEBUG_REC_START` 한 번 더 | **처음엔 결함**: 두 번째 시작이 새 탭을 열고, 새 시계가 이미 지나간 첫 프레임을 기다려 학습자 WAV가 계속 무음이 될 구조 | 두 번째 시작은 거부하고 로그 (`start ignored — take already running`). 기기 확인: `rtdouble` 한 벌만 생김, WAV 25.2초 vs 영상 25.4초 |
| 2 | 녹화 없이 `DEBUG_REC_STOP` | `rec not recording`, 앱 살아 있음 | 없음 |
| 3 | 튜터 답 도중 학습자 발화 주입 | 두 답 모두 프리롤 있음(lead 212, 52ms), underrun 0 — `barge-in.log` | 답이 `output_audio.done` 없이 끊기면 다음 답의 `response.created`에서 통계를 닫는다(프리롤 보장). 단 디버그 주입은 서버 VAD를 거치지 않아 서버 쪽 취소는 여기서 재현되지 않는다 |
| 4 | 녹화 중 앱 강제 종료 | **처음엔 결함**: WAV 두 개 모두 헤더 크기 0 → ffprobe "Invalid data". MP4도 moov 없음(CameraX 한계, 복구 불가) | WAV 헤더를 1초마다 갱신. 재시험: learner 12.0초, tutor 12.6초 읽힘. 단위 테스트 `aTapKilledMidTakeStillHasAReadableHeader` |
| 5 | 숫자로 끝나는 촬영 이름 (`baseline-pov-2`) | take.sh가 exit 2로 거부, 대안 이름 제시 | a577002 |
| 6 | 코어 하나를 바쁜 루프로 점유 (`STRESS=1`) | underrun 0/6, 마이크 지연·유실 0 — `../hd24-stress1.json` | 없음 |

재현되지 않은 것: 카메라 시작 실패. `GlassesCamera`의 catch가 `recordingClock.rolling(0)`을 불러
탭이 벽시계로 돈다 — 코드 경로로만 확인했다.
