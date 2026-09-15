# RayNeo X3 Pro — 플랫폼 하드윈 노트

이 기기에서 실제로 부딪혀서 알아낸 것들. 새 글래스 앱을 만들거나 기존 앱을 고칠 때
**여기부터 읽으면** 같은 함정을 다시 밟지 않는다. 전부 실기기(RayNeo X3 Pro,
`A06B4A043084773`) 로그로 확인된 내용이고, 추정은 그렇다고 표시했다.

---

## 1. 네이티브 글래스 앱으로 등록되는 법 (가장 중요)

### 증상
앱이 런처의 **'응용 앱'(VM app) 폴더**에 들어가고, 실행하면 이 팝업이 뜬다:

> the mobile phone connection is disconnected. Please launch App Home - Touchpad.
> Virtual machine applications need to be used in conjunction with the touchpad of
> your mobile phone to function properly.

즉 RayNeo가 우리 앱을 "글래스용 앱"이 아니라 **일반 안드로이드 앱**으로 취급해서,
폰 미러링 터치패드를 요구하는 것이다.

### 원인
Mercury SDK 연동이 하나라도 빠지면 이렇게 된다. 네 가지가 **모두** 필요하다.

### 필수 4종

1. **SDK AAR 의존성**
   ```kotlin
   // app/build.gradle.kts
   implementation(files("libs/MercuryAndroidSDK-v0.2.2-20250717110238_48b655b3.aar"))
   buildFeatures { viewBinding = true }
   ```

2. **Application에서 SDK 초기화** (Activity 시작 전에 반드시)
   ```kotlin
   class LumellaApp : Application() {
       override fun onCreate() {
           super.onCreate()
           MercurySDK.init(this)
       }
   }
   ```

3. **매니페스트 마커** — 이게 런처 분류를 결정한다
   ```xml
   <application android:name=".LumellaApp" ...>
       <meta-data android:name="com.rayneo.mercury.app" android:value="true" />
       <activity
           android:name=".MainActivity"
           android:screenOrientation="landscape"
           android:resizeableActivity="false"
           android:launchMode="singleTask"
           android:configChanges="orientation|screenSize|density|screenLayout" />
   </application>
   ```

4. **BaseMirrorActivity 상속** — 양안 렌더링
   ```kotlin
   class MainActivity : BaseMirrorActivity<ActivityMainBinding>() {
       // setContentView 호출하지 말 것 — SDK가 좌/우 눈에 각각 인플레이트한다
       // 화면 갱신은 항상 양쪽 다:
       //   mBindingPair.left.tvStatus.text = ...
       //   mBindingPair.right.tvStatus.text = ...
   }
   ```

### 클래스 계층 (javap로 확인)
```
BaseMirrorActivity<B: ViewBinding>
  → BaseEventActivity      (onClick/onDoubleClick/onSlide* 네이티브 터치 콜백)
    → BaseTouchActivity
      → BaseActivity
        → androidx.appcompat.app.AppCompatActivity
```
- **AppCompatActivity 파생**이므로 LifecycleOwner다 → CameraX `bindToLifecycle` 그대로 동작.
- 테마는 `Theme.AppCompat` 계열이어야 한다(아니면 런타임 크래시).

### 검증 방법
```bash
aapt2 dump xmltree app-debug.apk --file AndroidManifest.xml | grep -A2 rayneo.mercury
# → android:name="com.rayneo.mercury.app" android:value=true 가 보여야 함
```
설치 후 런처에서 '응용 앱' 폴더가 아닌 기본 앱 영역에 뜨는지, 팝업이 없는지 확인.

---

## 2. 좌/우 터치패드 구분

좌표가 아니라 **입력 디바이스 이름**으로 구분한다.

```kotlin
override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
    val deviceName = ev?.device?.name ?: ""   // "cyttsp5_mt" / "cyttsp6_mt"
    ...
}
```
LEGACY ELLA와 lumella 양쪽 다 이 방식. 좌표 기반으로 바꾸지 말 것.

---

## 3. AR 디스플레이 UI 원칙

**검정 = 투명이다.** 웨이브가이드 디스플레이라 켜진 픽셀만 시야에 뜬다.

- 루트 배경은 반드시 `#000000` (밝은 배경 = 시야를 가리는 빛 덩어리)
- 상태는 큰 글자 하나(48sp bold)를 중앙에, 힌트는 작게(18sp, `#888888`) 하단에
- 텍스트에 접두사 붙이지 말 것 (`앱이름: THINKING` ❌ → `Thinking...` ✅)
- `adb shell screencap`은 이 하드웨어에서 **검정으로만 나온다**(AR 오버레이 합성 방식 때문).
  육안 확인이나 logcat으로 검증할 것.

---

## 4. 카메라 — 진짜 원인은 **액티비티 라이프사이클**이었다

### 증상
`takePicture()`가 영원히 안 끝나거나(`Issue the next TakePictureRequest` 이후 무응답),
`Failed to submit capture request`로 실패한다. 화면은 "Capturing..."에서 멈춘다.

### 근본 원인 (2026-07-28 실측 확정)
**글래스 디스플레이가 꺼져 있으면 액티비티 Lifecycle이 `CREATED`에 머문다.**
CameraX `bindToLifecycle`은 **STARTED 이상**일 때만 실제로 카메라를 연다. 그래서:

| 디스플레이 | Lifecycle | cameraState | 결과 |
|---|---|---|---|
| 꺼짐 | `CREATED` | (이벤트 없음) | 카메라 미개방 → 촬영 불가 |
| 켜짐 | `RESUMED` | `CLOSED→OPENING→OPEN` | **정상 촬영** ✅ |

`dumpsys media.camera`가 촬영 시도 중에도 `Device 0 is closed, no client instance`를 보이면
이 상태다 — 앱이 카메라 클라이언트가 되지도 못한 것이다.
`dumpsys activity activities`의 `visibleRequested=false`도 같은 신호다.

> ⚠️ **이전 판 문서의 두 진단은 틀렸다.**
> ① "camera2는 실패하고 CameraX만 동작" → 둘 다 같은 라이프사이클 문제였다.
> ② "CameraX도 이 기기에선 촬영이 안 된다" → 디스플레이 꺼진 상태를 측정한 것이다.
> 하드웨어 레벨은 `LIMITED`로 정상이고, 화면만 켜져 있으면 4032x3024 촬영이 잘 된다.

### 그래서 지켜야 할 것
1. **촬영 전 디스플레이가 켜져 있어야 한다.** adb 테스트 시:
   ```bash
   adb shell input keyevent KEYCODE_WAKEUP
   adb shell svc power stayon true
   ```
2. **CameraState를 기다린 뒤 촬영한다.** `bindToLifecycle`은 세션 구성 완료 전에 반환한다 —
   곧바로 `takePicture`하면 `Failed to submit capture request`가 난다.
   `camera.cameraInfo.cameraState`가 `OPEN`이 될 때까지 기다리고, 그래도 실패하면 짧게 재시도한다.
3. **촬영할 때만 bind하고 끝나면 unbind한다.** 카메라는 하나뿐이라 한 앱만 소유할 수 있다.
   앱 수명 내내 붙들면 다른 앱(ELLA 등)이 촬영을 못 한다. 배터리에도 불리하다.

### 검증된 동작 (2026-07-28)
글래스 카메라 촬영 → `analyzeImage` → luma `/v1/images/analyze` → 실제 장면 캡션 수신:
`"A person sitting at a desk with a thoughtful expression..."` + salientElements 3종.
이어서 coach 턴의 `coachEvidence.visual`로 그대로 전달됨.

### 재현 (착용 불필요, 단 화면은 켜야 함)
좌측 터치패드 탭은 SELinux 때문에 주입 불가(§6-1) → 디버그 빌드의 브로드캐스트 훅 사용:
```bash
adb shell input keyevent KEYCODE_WAKEUP && adb shell svc power stayon true
adb shell am start -n com.woolab.lumella/.MainActivity
adb shell am broadcast -a com.woolab.lumella.DEBUG_CAPTURE_PHOTO
adb logcat -d -s lumella:V | grep -iE "lifecycle|cameraState|capture"
```
`lifecycle=RESUMED`와 `cameraState=OPEN`이 보이면 정상 경로다.

## 3-1. 착용·마이크 없이 검증하는 법 (디버그 브로드캐스트 3종 + `model-saw.jpg`)

`adb shell screencap`이 이 기기에서 검은 화면만 준다는 것(`ops/screen-dump.sh` 헤더 참고)과
안경을 쓰지 않으면 마이크가 0.00ms 오디오를 반환한다는 것(§5)을 합치면, 착용도 발화도 못 하는
상태에서 음성 대화 로직을 어떻게 검증하냐는 문제가 남는다. 디버그 빌드에만 등록되는 브로드캐스트
훅 3종이 그 답이다. `DEBUG_CAPTURE_PHOTO`(§3) 외에 세 개가 더 있다:

```bash
# 학습자 발화 없이 한 턴을 실제로 돌린다 (마이크 우회, 실제 모델 호출)
adb shell am broadcast -a com.woolab.lumella.DEBUG_SAY --es text "'이 방에 대해 이야기해 줘.'"

# 카메라 대신 파일의 사진을 모델에게 보여준다
# 앱 전용 디렉터리로 먼저 밀어넣는다. 리시버가 exported라 임의 경로를 읽게 두면
# 아무 앱이나 lumella의 uid로 파일을 읽어 올릴 수 있어, 경로는 파일명만 취한다.
adb push scene.jpg /storage/emulated/0/Android/data/com.woolab.lumella/files/
adb shell am broadcast -a com.woolab.lumella.DEBUG_SEE --es path scene.jpg
adb shell am broadcast -a com.woolab.lumella.DEBUG_SEE --es path scene.jpg --es ask "'이 첨부 그림 속 도형과 색만 말해줘. 새로 찍지 말고.'"

# 착용자 없이 자막 레이아웃만 확인한다 (샘플 텍스트로 채움)
adb shell am broadcast -a com.woolab.lumella.DEBUG_SUBTITLE
```

**안쪽 따옴표는 필수다.** `am`은 인자를 공백 기준으로 쪼개므로, `--es text` 값 안에 공백이
있으면 `'...'`로 한 번 더 감싸지 않으면 첫 단어만 전달된다.

`DEBUG_SEE`(그리고 실제 `capture_photo` 경로)를 쓰는 디버그 빌드는 모델에게 실제로 보여준
다운스케일 프레임을 매 턴 `/storage/emulated/0/Android/data/com.woolab.lumella/files/model-saw.jpg`에
저장한다. `adb pull`로 이 파일을 받아 눈으로 보는 것이, 모델이 **정확하게 묘사한 것**인지
**자신 있게 지어낸 것**인지 구분하는 유일한 방법이다 — 로그의 캡션 텍스트만으로는 둘을 구별할
수 없다. 실제로 2026-08-05에 이 실패를 그대로 겪었다: 캄캄한 방에서 사진 한 장 찍지 않고도
"책상 위에 노트북과 커피잔이 있다"는 캡션을 완전한 확신으로 냈다. 이 실패가 `capture_photo`를
언급하고 지어낸 묘사를 금지하는 페르소나 문구(`OpenAiRealtimeTransport.DEFAULT_SESSION_INSTRUCTIONS`)의
직접적인 계기였다.

```bash
adb pull /storage/emulated/0/Android/data/com.woolab.lumella/files/model-saw.jpg
```

## 4-1. Mercury SDK가 요구하는 런타임 의존성 (누락 시 슬라이드에서 크래시)

`BaseMirrorActivity`를 상속하면 SDK의 터치 파이프라인(`BaseEventActivity.mappingAction`)이
**`androidx.lifecycle.LifecycleOwnerKt`(lifecycleScope)를 런타임에 해석**한다. APK에 없으면
**첫 슬라이드 제스처에서** 즉사한다:

```
NoClassDefFoundError: Landroidx/lifecycle/LifecycleOwnerKt;
  at BaseEventActivity.mappingAction
  at BaseEventActivity.onSlideContinuous
  at TouchDispatcherX3.onMotionEvent
```

```kotlin
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
```

**탭은 이 경로를 타지 않는다.** 그래서 adb 탭 테스트로는 절대 안 잡히고, 실제로 착용한
손가락이 미세하게 미끄러질 때만 터진다(2026-07-28 필드 리포트로 발견). LEGACY ELLA는
처음부터 이 둘을 갖고 있어서 무사했다.

확인법:
```bash
unzip -p app-debug.apk classes.dex | strings | grep LifecycleOwnerKt
```

## 4-2. 오디오는 USAGE_ASSISTANT로 선언할 것 (음악 앱 소환 방지)

RayNeo에는 BLE 미디어 브리지(`BleMusicEventAdapter`, `MusicStateMachine`, `MusicEventRepositoryImpl`)가
있고, 페어링된 폰의 AVRCP 재생 상태를 **3초마다 폴링**한다.

```
BleMusicEventAdapter$startAutoRefreshLoop: 音乐定时刷新---
handlePlaybackStateChanged[true]: PlaybackState {state=2, ...}
  from artist[true]: album:, title:<폰에서 마지막에 틀던 곡>
  albumArtUri: content://com.android.bluetooth.avrcpcontroller.AvrcpCoverArtProvider?device=<폰 MAC>
```

### 규칙
```kotlin
AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ASSISTANT)   // ❌ USAGE_MEDIA
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()
```
그리고 **`play()`는 첫 오디오 청크가 올 때까지 미룬다.** 트랙 생성만으로는 무해하지만
PLAYING 진입 자체가 신호가 된다.

`USAGE_MEDIA`로 선언한 스트림이 재생을 시작하면 이 브리지가 "플레이어가 재생 시작"으로 해석해
폰에 AVRCP play를 중계하고, 폰에서 음악 앱이 열린다(2026-07-28 실사용 리포트).
튜터 음성은 의미상 미디어가 아니므로 `USAGE_ASSISTANT`가 맞다.

### ⚠️ 로그 해석 주의 — 저자의 오판 기록
초판에 "수정 후 미디어 이벤트 0건"이라고 적었으나 **그 측정은 무의미했다.**
위 폴링 루프는 **우리 앱과 무관하게 자체 타이머로 돌며**, 폰 블루투스가 연결돼 있지 않으면
로그가 아예 안 나온다. 즉 "0건"은 고쳐서가 아니라 **폰이 안 붙어 있어서** 나온 숫자였다.

폰이 붙은 상태에서 다시 재면 3초마다 이벤트가 쌓인다(60초에 약 56건). 그러나 이는 정상이며,
판단 기준은 이벤트 수가 아니라 **`ActivityTaskManager: START`에 음악 앱이 뜨는가**다.

```bash
adb logcat -d | grep -iE "ActivityTaskManager.*START"   # 우리 앱만 있어야 정상
```

## 4-3. 폰 음악 정보가 글래스에 계속 뜨는 것 끄기

`com.rayneo.media`(시스템 앱, `MediaCoreService`)가 페어링된 폰의 AVRCP 상태를 3초마다 폴링해
곡 제목·앨범아트를 글래스에 표시한다. 튜터 대화 중에는 방해가 된다.

```bash
adb shell pm disable-user --user 0 com.rayneo.media   # 끄기
adb shell pm enable com.rayneo.media                  # 되돌리기
```

**되돌릴 수 있다.** 실측(2026-07-29): 비활성화 후 프로세스 종료, 20초간 미디어 이벤트 0건,
우리 앱은 카메라 초기화·WS 연결·핸즈프리 녹음 모두 정상, 크래시 0건.

> 이 패키지는 **폰 미디어 브리지 전용**이다. 글래스 자체의 스피커/마이크 오디오 경로는
> 오디오 HAL이 담당하므로 영향받지 않는다. 폰 음악을 글래스에서 제어할 일이 생기면 다시 켜면 된다.

## 5. 마이크는 착용 감지형이다

안경을 **쓰지 않으면 마이크가 0.00ms 오디오**를 반환한다. adb로는 음성 턴을 흉내낼 수 없다.
→ 음성 E2E 검증은 반드시 사람이 착용해야 한다. 자동화 불가.

부수 효과: 말 없이 탭하면 서버가 `input_audio_buffer_commit_empty`를 던진다.
클라이언트에서 **오디오가 실제로 쌓였을 때만 commit**하도록 가드할 것.

---

## 6. WiFi가 조용히 끊긴다

`dumpsys wifi`는 SSID를 계속 보여주는데 실제로는 죽어 있는 경우가 있다.
믿을 수 있는 확인:
```bash
adb shell ip addr show wlan0 | grep "inet "   # IP 없으면 죽은 것
adb shell ip route                            # 비어 있으면 기본 경로 없음
```
복구:
```bash
adb shell svc wifi disable && sleep 3 && adb shell svc wifi enable
```
**이게 앱의 네트워크 오류로 오인되기 쉽다.** 앱 탓하기 전에 여기부터 확인.

---

## 7. HTTP 로컬 서버에 붙으려면 cleartext 허용이 필요하다

targetSdk 34는 평문 HTTP를 전부 막는다. Mac의 token-service/luma-api는 평문이므로
`res/xml/network_security_config.xml`에 **해당 호스트만** 열어준다
(`usesCleartextTraffic="true"` 전체 허용은 쓰지 말 것). 실기기는 Mac의 LAN IP를
한 줄 추가해야 하고, IP가 바뀌면 다시 넣어야 한다.

---

## 8. OpenAI Realtime API 관련 (이 앱 특유)

| 함정 | 증상 | 해결 |
|---|---|---|
| beta 프로토콜 폐지 | `beta_api_shape_disabled` | `OpenAI-Beta: realtime=v1` 헤더를 **보내지 말 것**. GA 엔드포인트+GA 세션 형태만 사용 |
| `expires_at` 단위 | 항상 "이미 만료된 토큰" | 서버가 **epoch 초**로 준다. 클라이언트가 ms로 비교하면 전부 만료 판정 → 토큰 서비스에서 ms로 정규화 |
| 60분 하드 리밋 | 1시간마다 `session_expired` | 정상 동작이다. 자동 재연결(백오프 1s→30s, 토큰 재발급)로 흡수. 실측 26사이클 무결점 |
| 좀비 소켓 | 재연결 ~20초 후 가짜 DEGRADED | 소켓 세대(generation) 가드로 낡은 콜백 무시 |
| 계정 오류 재시도 폭주 | `insufficient_quota`인데 4초마다 무한 재연결 | 계정 레벨 오류(`insufficient_quota`/`invalid_api_key`/`account_deactivated`)는 **재연결하지 말 것** |

---

## 9. "토큰 오류"의 3가지 서로 다른 원인 — 구분해서 보기

`TOKEN-FAIL`이 떴다고 API 문제가 아니다. 순서대로 확인:

| # | 원인 | 확인 방법 | 해결 |
|---|---|---|---|
| 1 | Mac의 token-service가 죽음 | `curl localhost:8788/healthz` | `ops/launchd/manage.sh install` (상시 실행) |
| 2 | 글래스 네트워크 끊김 | `adb shell ip addr show wlan0` | WiFi 재연결(§6) |
| 3 | OpenAI 크레딧 소진 | 토큰은 발급되는데 WS가 즉시 `insufficient_quota` | 결제 충전 |

**1·2번은 우리 인프라 문제, 3번만 진짜 API 문제다.** 실제로 셋 다 한 번씩 겪었고,
전부 화면에는 비슷하게 보였다. 지금은 3번이 `No API credit`으로 따로 표시된다.

참고: 토큰 **발급**(`/v1/realtime/client_secrets`)은 크레딧이 없어도 200을 준다.
`/v1/models` 조회도 통과한다. 그래서 "키는 살아있는데 대화만 안 되는" 상태가 생긴다.
크레딧 유무를 확인하려면 **실제 WS를 열어봐야** 한다.

---

## 10. 이 기기는 한 번에 앱 하나만 돌린다 (2026-09-10 규명)

**런처가 배경으로 밀린 앱을 의도적으로 죽인다.** 카메라 앱이든 설정 앱이든, 무엇이든
전면을 가져가면 lumella가 종료된다.

```
ActivityManager: Killing com.woolab.lumella (adj 700): stop ... due to from pid 1814
Mercury: BackgroundAppManager$forceStopApp com.woolab.lumella success
                                           ↑ pid 1814 = mercury.launcher
```

처음엔 카메라 탓으로 봤고, 그 다음엔 오디오 독점 탓으로 봤다. **둘 다 틀렸다.**
설정 앱을 띄워도 똑같이 죽는 것으로 갈렸다.

### 무엇이 살아남는가

| 방식 | 전면 점유 | lumella |
|---|---|---|
| `screenrecord` (셸 명령) | 안 함 | **생존** |
| scrcpy 화면 미러링 (셸) | 안 함 | **생존** |
| 앱 내부 CameraX 녹화 (§11) | 안 함 | **생존** |
| 카메라 앱 | 함 | 죽음 |
| 설정 앱 | 함 | 죽음 |

**셸 명령은 앱이 아니라 전면을 안 뺏는다.** 그래서 화면 녹화는 처음부터 문제가 없었다.

### 막힌 우회로 (다시 시도하지 말 것)

| 시도 | 결과 |
|---|---|
| `pm revoke` 마이크 권한 | `SYSTEM_FIXED`라 거부 |
| `appops set ... RECORD_AUDIO deny` | 적용은 되나 **여전히 죽는다** — 오디오가 원인이 아니라는 증거 |
| `dumpsys deviceidle whitelist +` | 등록되나 무효. 런처가 안드로이드 정책을 무시하고 자체 판단 |
| `scrcpy --video-source=camera` | `CameraAccessException: Broken pipe (-32)`. lumella를 내려도 동일 — 펌웨어가 camera2 스트림 설정을 거부. 모든 해상도·카메라 id 실패 |
| 셸에서 카메라 직접 | 도구 없음. `/system/bin`에 `screenrecord`만 있고 카메라 바이너리는 없다 |

### adb로 와이파이를 못 바꾼다

셸 사용자에게 권한이 없다. 매장 공용 AP에 붙어 캡티브 포털에 걸리면 **렌즈 UI나
RayNeo 앱으로만** 빠져나올 수 있다.

```
cmd wifi connect-network-id  → SecurityException: Uid 2000 does not have access
cmd wifi forget-network      → Forget failed
cmd wifi add-network         → 권한 통과. 다만 open|owe|wpa2|wpa3 뿐이다
```

나가기 전에 매장 망을 미리 잊어두면 이 상황이 안 생긴다.

**`add-network`만은 예외다** (2026-09-14 확인). 입자 오류를 내지 `SecurityException`을
안 낸다 — 권한은 통과한다. PSK 망이라면 케이블로 미리 넣어둘 수 있다.

```bash
adb shell cmd wifi add-network <SSID> wpa2 '<비밀번호>'
```

다만 **기업망(WPA2-EAP)은 못 넣는다.** 문법이 `open|owe|wpa2|wpa3`뿐이라
계정·인증서를 받을 자리가 없다. 연구소는 주변이 전부 EAP에 SSID도 숨겨져 있어
**폰 핫스팟이 유일한 경로였다.**

---

## 11. 1인칭 영상은 앱 안에서 찍는다

§10 때문에 카메라 앱을 쓸 수 없다. `GlassesCamera`가 CameraX `VideoCapture`로 직접
녹화한다. 같은 프로세스라 전면이 안 바뀌고, 런처가 죽일 대상이 없다.

```bash
adb shell am broadcast -p com.woolab.lumella -a com.woolab.lumella.DEBUG_REC_START --es name pov1
adb shell am broadcast -p com.woolab.lumella -a com.woolab.lumella.DEBUG_REC_STOP
adb pull /storage/emulated/0/Android/data/com.woolab.lumella/files/pov1.mp4 ~/shots/
```

실측: **1280×720 H.264 30fps, 6Mbps.** 녹화 전후 PID 동일.

### 오디오는 담긴다 — 단, 마이크로는 튜터가 안 들어온다

처음엔 "오디오 입력이 하나라 담을 수 없다"고 썼는데 **시험해보지도 않은 추론이었다.**
한 프로세스 안에서는 `maxActiveCount: 1`이 적용되지 않는다(2026-09-14 실측).

다만 영상의 오디오는 **마이크**라 학습자 목소리만 들어온다. 마이크가
`VOICE_COMMUNICATION`으로 열려 있어 에코 제거가 스피커의 튜터 음성을 지운다. 그건 끕 수
없다 — 없으면 튜터 목소리가 되돌아가 서버 VAD가 학습자 발화로 오인한다(`AudioCapture` 주석).

그래서 `AudioPlayback`이 **스피커로 보내는 PCM을 그대로 WAV로 받아쓴다.** 소리로
되잡는 게 아니라 원천에서 받는다.

```
영상 오디오   마이크 (학습자)
-tutor.wav    AudioTrack PCM (튜터)
-pov-MIXED    둘을 합친 것
```

**WAV는 영상 시계를 따른다.** 말할 때만 쓰면 172.3초 테이크가 68.7초 파일이 돼
믹스하면 점점 앞서간다. 침묵을 채우되, **사진 턴으로 녹화가 멈춘 구간은 제외한다**
(`onSegmentGap`). 뱅시계로 카운트하면 경계마다 1.5초씩 밀렸다 — 지금은 0.14초다.

재개 시점은 `VideoRecordEvent.Start`에 묶어야 한다. 요청한 순간에 풀면 인코더 기동
시간이 녹화된 것으로 계산돼 어긋남이 절반만 잡혔다(1.5 → 0.73 → 0.14초).

### 회전 — 치수를 믿지 말 것

안경이 세로를 기본 방향으로 보고해서 그냥 찍으면 `rotation=-90`이 붙어 눕는다.
**`ROTATION_180`이 정답이다.**

```
ROTATION_90    메타는 사라지고 1280×720이 되지만 내용은 여전히 누움  ← 속기 쉬움
ROTATION_270   rotation=-180, 거꾸로 섬
ROTATION_180   바로 섬
```

첫 둘에 두 번 속았다. 치수가 가로라고 고쳐진 게 아니다 — **프레임을 눈으로 볼 것.**

### 사진 턴은 녹화를 분절한다

이 카메라는 `VideoCapture` 옆에 어떤 use case도 안 붙인다.

```
VideoCapture + ImageCapture   거부
VideoCapture + ImageAnalysis  거부
  → "No supported surface combination is found for camera device - Id : 0"
```

녹화 중인 MP4는 moov atom이 없어 프레임도 못 꾼다. 그래서 **조각을 닫고 → 마지막
프레임을 꺼내고 → 다음 조각을 연다.** 한 테이크가 `take.mp4`, `take-2.mp4`로 나뉘고
`take.sh`가 자동으로 이어 붙인다(`-c copy`, 재인코딩 없음).

전환에 약 1.2초 걸리고 그동안 화면이 멈춘다. 하드웨어 제약이니 **사진은 문장 중간이
아니라 턴 사이에** 찍는다.

### 인코더는 하나다 — 비트레이트를 제한한다

1920×1080으로 둘 다 돌리면 `cameraserver`가 116% CPU를 쓰고 4코어 중 유휴가 77%만
남는다. 튜터 응답이 늘어진다. **6Mbps로 제한**하니 84% / 유휴 138%, TTFA 730ms → 668ms.
상한을 낮추는 게 아니라 비트레이트만 제한한다 — 해상도도 레이어도 안 버렸다.

디버그 브로드캐스트 전용이라 터치패드에는 없다.

### 용량

| 방식 | 규격 | 분당 |
|---|---|---|
| 1인칭 (앱 내장) | H.264 1280×720 @6Mbps | **약 50MB** |
| 카메라 앱 | HEVC 2432×1824 | 141MB |
| 화면 녹화 | H.264 1280×480 | **0.26MB** |

비트레이트 제한 전엔 1920×1080에 분당 189MB였다.

`/sdcard` 여유 21G 기준 1인칭 **114분**. 화면 녹화는 사실상 공짜다.
테이크당 3분으로 36테이크를 찍으면 20GB라 **빠듯하다 — 테이크마다 회수할 것.**

1인칭이 카메라 앱보다 해상도는 낮은데 용량이 큰 건 H.264라서다. 용량이 걸리면
HEVC로 바꾸는 수가 있다.

### 녹화는 인터넷 없이도 된다 (2026-09-14 실측)

연구소에서 망에 못 붙은 채로 `ops/take.sh`를 통째로 돌렸다.

```
화면 녹화   15프레임 / 10.9초     자막·에코 전부 기록됨
1인칭      473프레임 / 15.8초    1920×1080 가로, 47.8MB
자막 덤프   정상
```

**녹화 기계장치는 adb와 앱만 있으면 도는다.** 다만 화면 왼쪽 위에 빨간
`Token error`가 박히고, 자막은 `DEBUG_SUBTITLE`로 밀어넣은 가짜다. 음성·토큰·코치가
전부 인터넷을 요구하므로 **영상이 증명해야 할 내용은 오프라인에 존재할 수 없다.**

그래도 쓸 데가 있다 — 대화가 필요 없는 컷(시야 샷·마무리)과 구도·조명 연습은
인터넷 없이 찍을 수 있다.

---

## 참고 파일

- 개발 루프/설정: [`dev-loop.md`](dev-loop.md)
- 스모크 체크리스트: [`smoke-checklist.md`](smoke-checklist.md)
- token-service 상시 실행: [`../ops/launchd/manage.sh`](../ops/launchd/manage.sh)
- 검증된 레퍼런스 구현: `TUTOR/ELLA` (Mercury 연동·CameraX·터치 구분의 원본)
