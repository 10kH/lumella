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

## 4. 카메라 — 한 번에 한 앱만 잡을 수 있다 (중요)

### 증상
`takePicture()`를 불렀는데 성공/실패 콜백이 **둘 다 오지 않는다.** 화면은 "Capturing..."에서 멈춘다.
logcat: `takePictureInternal` → `TakePictureManager: Issue the next TakePictureRequest.` 이후 무응답.

### 원인: 카메라 점유 충돌
글래스에는 카메라가 하나뿐이고 **한 앱만 잡을 수 있다.** CameraX `bindToLifecycle`을 Activity 생성 시점에
호출하고 유지하면, 그 앱이 살아있는 동안 카메라를 계속 점유한다. 다른 앱이 촬영을 요청하면
**에러도 없이 큐에만 쌓이고 영원히 안 끝난다.**

2026-07-28 실제 사례: ELLA와 LUMELLA를 둘 다 설치했더니 ELLA의 사진 첨부가 "Capturing..."에서 멈췄다.
두 앱 모두 `onCreate`에서 카메라를 바인딩하고 계속 붙들고 있었기 때문. 먼저 잡은 쪽이 이긴다.

### 원칙: 촬영할 때만 잡고 즉시 놓아라
```kotlin
// 촬영 요청 시점에 bind → takePicture → 콜백에서 즉시 unbindAll()
provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, capture)
capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
    override fun onCaptureSuccess(image: ImageProxy) { /* ... */ provider.unbindAll() }
    override fun onError(e: ImageCaptureException) { provider.unbindAll() }
})
```
비용은 촬영당 바인드 지연(1초 미만)뿐이고, 배터리에도 유리하다.
`bindToLifecycle`/`unbindAll`은 **메인 스레드**에서 호출해야 한다.

### 그 외 확인된 사실
- 직접 만든 **camera2** 단발 캡처는 이 기기에서 프레임을 못 받고 약 6초 뒤 조용히 끊긴다 → **CameraX를 쓸 것.**
- 액티비티가 **포그라운드**여야 한다. 백그라운드면 `Camera2CameraControlImp: Camera is not active`로 실패한다.
- Mercury SDK에는 카메라 API가 없다(api/core/databinding/ext/focus/touch/ui/util) — AOSP CameraX가 정답 경로다.

### 재현 (착용 불필요)
좌측 터치패드 탭은 SELinux 때문에 주입할 수 없으므로(§6-1) 디버그 빌드의 브로드캐스트 훅을 쓴다:
```bash
adb shell am start -n com.woolab.lumella/.MainActivity   # 포그라운드 필수
adb shell am broadcast -a com.woolab.lumella.DEBUG_CAPTURE_PHOTO
adb logcat -d --pid=$(adb shell pidof com.woolab.lumella) | grep -iE "lumella|TakePicture"
```
**다른 카메라 사용 앱(ELLA 등)을 먼저 force-stop**하고 테스트할 것.

### 파이프라인의 나머지는 검증됨
촬영 이후 단계는 정상이다: `/v1/images/analyze`는 **OpenAI 크레딧 없이도** 200 + 유효한 `imageId`를
반환한다(luma 폴백 경로, 2026-07-28 실측 `img_b8f8720…`).

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

## 참고 파일

- 개발 루프/설정: [`dev-loop.md`](dev-loop.md)
- 스모크 체크리스트: [`smoke-checklist.md`](smoke-checklist.md)
- token-service 상시 실행: [`../ops/launchd/manage.sh`](../ops/launchd/manage.sh)
- 검증된 레퍼런스 구현: `TUTOR/ELLA` (Mercury 연동·CameraX·터치 구분의 원본)
