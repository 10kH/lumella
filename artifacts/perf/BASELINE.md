# 녹화 부하 기준선 — 2026-09-28

`ops/perf-run.sh <label> <none|audio|pov>` 한 번 = 부스 여섯 문장 한 바퀴. 튜터는 실제 realtime API로
답한다. 기기 A06B4A043084773, 망 CCC2_WLAN.

## "끊김"의 정의

| 지표 | 뜻 | 출처 |
|---|---|---|
| **playback underruns** | 재생 트랙이 비어 착용자가 끊김을 들음 | `AudioTrack.getUnderrunCount` 응답별 차이 |
| stretch | 응답의 실제 경과 ÷ 오디오 길이. 1보다 크면 늘어짐 | 첫·마지막 delta 시각 |
| **capture lateReads** | 마이크 읽기 주기가 버퍼(40ms)의 2배 초과 — 학습자 말이 샐 수 있음 | `AudioCapture` read 주기 |
| TTFA | 학습자 말 끝 → 튜터 첫 소리 | 앱 로그 |
| CPU | 기기 전체(헤더 idle 기준)와 프로세스·스레드별 | `top -H` 두 번째 반복 |

## 결과

| 모드 | CPU 평균 / 피크 (400% 중) | underrun (응답 수) | capture lateReads (최대 주기) | TTFA 중앙 | 진단 3턴 / 해제 6턴 |
|---|---|---|---|---|---|
| none | 82.6% / 178% | **2** (2/7) | 0 (59ms) | 798ms | ✓ / ✓ |
| audio | 85.6% / 243% | **1** (1/7) | 0 (57ms) | 934ms | ✓ / ✓ |
| **pov** | **322.3% / 397%** | **11** (6/7) | **3** (112ms) | 844ms | ✓ / ✓ |
| **pov-b** | **255.4% / 304%** | 1 (1/7) | 0 (56ms) | 747ms | ✓ / ✓ |

stretch는 네 모드 모두 1.00~1.03 — 튜터 말이 늘어지지는 않는다. **끊김은 underrun과 마이크 지연으로 온다.**

## POV에서 누가 CPU를 먹나 (pov, 평균)

```
vendor.qti.camera.provider   89%   (피크 107%)   ISP — 1280×720 @ 30fps
com.woolab.lumella           55%   (피크 192%)   CameraX 16%, MediaCodec_loop 6.5%, 앱 메인·OkHttp
cameraserver                  9%
media.swcodec                 9%                  AAC 인코더 — CameraX가 마이크를 한 번 더 열어 소프트웨어로
media.hwcodec                 7%                  c2.qti.avc (하드웨어 H.264)
audio HAL · AGM · audioserver ~12%
```

POV 파일: **1280×720, 29.5~29.9fps, 7.1~7.4Mbps(목표 6), AAC 48k 스테레오.**

## 읽는 법

**POV가 기기를 여유 없는 경계에 올린다.** 두 POV 실행의 차이는 그날의 전체 부하다 —
평균 322%(80%)에서는 오디오가 굶어 underrun 11회·마이크 지연 3회, 255%(64%)에서는 1회. none·audio는
80% 안팎이라 흔들려도 여유가 있다. 사진 턴(튜터가 스스로 `capture_photo`, 세그먼트 분할)은 두 POV 실행
모두 1회라 분산의 원인이 아니다. 온도는 44→51°C로 두 실행이 같고 스로틀링은 보이지 않는다.

**그래서 목표는 POV의 평균 부하를 none 수준 가까이 내리는 것이다.** 최악의 날에도 오디오 경로가
굶지 않을 여유.

## 함께 찾은 버그

- **세그먼트 이름:** `GlassesCamera.nextSegment`가 마지막 하이픈 뒤를 숫자인지 확인하지 않고 잘라,
  `perf-baseline-pov` → `perf-baseline-2.mp4`. 하이픈 든 촬영 이름은 사진 턴 뒤 전부 유실됐다. 고침 +
  테스트. 숫자로 끝나는 이름(`...-2`)은 세그먼트 번호와 구조적으로 모호해서 `take.sh`가 거부한다.
- **하네스 자신:** 라벨에 `-pov`가 들어가면 파일명 조각 매칭이 screen을 POV로 잡았다. `take.sh`의 라벨
  줄로 매칭하도록 고침.
- **첫 DNS 실패:** 새 프로세스의 첫 DNS 조회가 셸은 되는데 앱만 실패하는 일이 간헐적으로 있다.
  하네스는 READY가 아니면 최대 3회 재실행한다. 부스에서도 `launch-lumella.sh`의 Ready를 확인할 것.
