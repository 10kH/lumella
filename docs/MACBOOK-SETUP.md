# 맥북에서 이어서 작업하기

촬영을 나가려고 맥북으로 옮길 때 필요한 것만 적었다. 2026-09-07 기준.

## 0. 핵심 — 맥미니는 집에 두고 켜둔다

맥북으로 **서버를 옮기지 않는다.** 옮길 필요가 없다.

```
집 (맥미니)                        밖 (맥북 + 안경)
  luma-api      :8010                안경 → OpenAI 직결 (음성)
  token-service :8788                안경 → Vercel (토큰)
  cloudflared  ──── 터널 ────────→   안경 → 터널 → 맥미니 (코치)
  lan-address                        맥북 → 무선 ADB로 안경 화면 녹화
  luma-backup   04:30
```

터널이 **나가는 연결**이라 맥미니의 IP가 무엇이든, 어느 망에 있든 밖에서 닿는다.
`ops/luma-tunnel.sh:24`가 `http://127.0.0.1:8010`을 가리킨다 — LAN 주소를 안 쓴다.

### 나오기 전에 반드시 — 잠자기를 끈다

**지금 맥미니는 `sleep 1`이다.** 1분 놀면 잠든다. 지금 안 자는 이유는
`ops/luma-tunnel.sh:56`의 `caffeinate -i cloudflared`가 막고 있어서다.

문제는 그 방어가 **터널과 한 몸**이라는 것이다. 터널이 죽으면 방어도 같이 풀리고,
1분 뒤 맥미니가 잠들어 **서버 전체가 멎는다.** 밖에서는 손쓸 방법이 없다.

`luma-api` 명의로 걸린 잠자기 방어는 0건이다. 즉 단일 실패점이다.

```bash
sudo pmset -a sleep 0 disksleep 0    # 비밀번호 필요 — 손으로 실행
pmset -g | grep -E "^ +sleep"        # sleep 0 확인
launchctl list | grep woolab         # 5종 다 있는지
```

`sleep 0`은 화면만 꺼지고 기계는 계속 돈다. 전원(AC)에 꽂혀 있으니 문제없다.

정전 대비까지 하려면 함께 켠다.

```bash
sudo pmset -a autorestart 1          # 전원 복구 시 자동 재기동
```

## 1. 레포 네 개 받기

```bash
mkdir -p ~/workspace && cd ~/workspace
gh repo clone 10kH/luma
gh repo clone 10kH/lumella
gh repo clone 10kH/ELLA
gh repo clone 10kH/aaai27
```

| 레포 | 무엇 | 맥북에서 |
|---|---|---|
| `lumella` | 안경 앱 | **빌드·설치** — 주로 씀 |
| `aaai27` | 논문·영상 시나리오 | **편집** — 마감 9/18 |
| `luma` | 서버 두뇌 | 안 돌림 (맥미니가 함) |
| `ELLA` | 이전 세대 앱 | 참고용 |

`luma`는 **공유 레포다. 직접 커밋하지 않는다.** PR로만. `luma/AGENTS.md` 참조.

## 2. 맥북에 없는 것 — 손으로 만든다

레포에 추적되지 않는 파일이 셋 있다. 비밀값이라 일부러 뺐다.

### `lumella/local.properties` (필수)

앱을 빌드하려면 있어야 한다. 맥미니에서 값을 복사해 온다.

```bash
# 맥미니에서
cat ~/workspace/lumella/local.properties
```

키는 일곱이지만 **맥미니에서 실제로 가져와야 하는 값은 `localToken` 하나뿐이다.**
나머지 여섯은 이 레포 안에서 확정된다.

```properties
sdk.dir=/Users/<사용자>/Library/Android/sdk    # 맥북 경로로 고칠 것
lumella.tokenServiceBaseUrl=https://lumella-token.vercel.app
lumella.lumaBaseUrl=http://192.168.35.170:8010   # 대체 주소, 안내판이 우선
lumella.localToken=<맥미니에서 복사 — 유일한 비밀값>
lumella.brainClassName=com.woolab.lumella.adapter.LumaTutorBrain
lumella.brainEmail=learner@luma.app
lumella.brainPassword=luma1234
```

`sdk.dir`만 맥북 경로로 바꾼다. 나머지는 그대로.

- `brainClassName`은 `BrainFactory.DEFAULT_BRAIN_CLASS_NAME`과 같은 값이다.
- `brainEmail`/`brainPassword`는 **비밀값이 아니다.** `luma-api/src/luma_api/seeds/bootstrap.py`가
  심는 공개 데모 계정이고 `luma/README.md`에도 적혀 있다. 맥미니를 못 열어도 채울 수 있다.
- `localToken`만 Vercel 환경변수 `LUMELLA_LOCAL_TOKEN`과 짝이 맞아야 한다.
  틀리면 401, 비면 앱이 `TOKEN-FAIL`로 떨어진다(크래시는 아니다).

### `luma-api/.env`, `~/.config/lumella/tunnel.env`

**맥북에 필요 없다.** 서버와 터널은 맥미니가 돌린다.

## 3. 빌드 환경

```bash
brew install --cask android-studio
brew install openjdk@17 gh ffmpeg
```

`JAVA_HOME`을 `~/.zshrc`에 넣어둔다. JDK 17이 아니면 빌드가 깨진다.

이 맥북에서는 **`/usr/libexec/java_home -v 17`이 실패한다.** Homebrew `openjdk@17`은
keg-only라 `/Library/Java/JavaVirtualMachines`에 심볼릭 링크가 없고, `java_home`은 그걸
못 본다. `java -version`이 "Unable to locate a Java Runtime"으로 나와도 JDK는 깔려
있는 것이다.

그렇다고 keg 경로를 박으면 **맥미니에서 죽는다.** 맥미니는 Temurin을 시스템 JDK로
깔아 `java_home`이 정상으로 찾고, keg 경로는 아예 없다. 둘 다 도는 형태로 쓴다
(sudo 불필요).

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home)"
export PATH="$JAVA_HOME/bin:$PATH"
```

```
맥미니   Temurin 시스템 JDK    java_home이 찾음 → 그걸 쓴다
맥북     Homebrew openjdk@17   java_home 실패 → keg 경로로 떨어진다
```

안드로이드 스튜디오 번들 JBR(21)로는 안 된다. `app/build.gradle.kts:71`의
`jvmToolchain(17)`이 정확히 17을 요구하는데, `settings.gradle.kts`에 foojay 툴체인
프로비저닝 플러그인이 없어 자동 내려받기도 없다. 17이 없으면 이렇게 죽는다.

```
Cannot find a Java installation ... matching: {languageVersion=17}
Toolchain download repositories have not been configured.
```

```bash
cd ~/workspace/lumella
ops/install-glasses.sh --wireless
```

**빌드만 하고 설치를 빠뜨리는 실수**가 잦았다. 그래서 한 명령으로 묶었다 —
빌드·깨우기·설치·버전 확인·무선 전환까지 한다. 손으로 하면 이 둘이다.

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 다른 맥에서 설치할 때 — 서명이 걸린다

디버그 APK는 `~/.android/debug.keystore`로 서명된다. **이 키는 기계마다 다르다.**
맥미니가 설치해둔 앱 위에 맥북이 덮어쓰려 하면 이렇게 거부당한다.

```
INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match
```

재서명은 불가능하다. 둘 중 하나다.

- 한쪽 `~/.android/debug.keystore`를 다른 쪽으로 복사해 **통일한다** (기존 앱·데이터 유지)
- `ops/install-glasses.sh --force` — 지우고 새로 깐다. 온디바이스 데이터는 날아가지만
  대화 기록은 맥미니 luma에 있으니 복구된다

adb 인증 키(`~/.android/adbkey`)도 마찬가지다. 처음 꽂는 맥이면 안경에
**"Allow USB debugging?"**가 뜼다. "Always allow"를 눌러야 한다.

> **2026-09-08 실측.** 두 키가 같을 거라는 추측은 틀렸다. adb 키만 같고
> (`adbkey.pub`에 `woody@Woody-M3A.local` — 통째 이전된 것, 그래서 인증 프롬프트 없음)
> **서명 키는 달랐다.** 실제로 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`가 났다.
>
> ```
> 맥북   SHA1 dff5882a7f298b24ef6c1fcb7adccbf9954330b6
> 맥미니 SHA1 d5ee8c245118b34ae9ebd79b7105d125970363e1
> ```
>
> `--force`로 밀었으니 지금 안경에 깐린 건 **맥북 서명**이다. 따라서 다음에
> 맥미니가 설치하려 하면 대칭으로 거부당한다. 방향은 **맥북 → 맥미니**다.
>
> 양쪽 SSH가 닫혀 있어 `scp`는 안 된다(22/445 모두 closed 확인). **에어드롭으로 옮긴다.**

**손으로 `mv` 하지 않는다.** 기존 키를 치우기만 하면 다음 빌드에서 Gradle이
새 키를 자동 생성한다. 지문이 또 달라지는데 경고가 없어서, 다음 설치가 같은 이유로
실패한 것처럼 보인다. 검증하며 교체하는 스크립트를 쓴다.

```bash
# 1. 상대 맥의 ~/.android/debug.keystore 를 에어드롭
# 2. ~/Desktop/keystore-inbox/ 에 넣고
ops/adopt-keystore.sh
```

받은 파일을 먼저 읽어 keystore가 아니면 거부하고(에어드롭이 끊긴 경우), 같은 키면
안 건드리고, 교체할 때만 기존 키를 시각 도장 찍어 백업한 뒤 교체 후 다시 읽어 확인한다.
방향은 무관하다 — 양쪽 어느 맥에서든 돌린다.

## 4. 안경 무선 연결 — 촬영의 핵심

케이블이 프레임에 들어가면 안 되니 무선으로 붙인다.

**최초 1회만 USB로:**

```bash
adb tcpip 5555
adb shell "ip addr show wlan0 | grep 'inet '"    # IP 확인
adb connect <그 IP>:5555
```

USB를 뽑아도 유지된다. `service.adb.tcp.port=5555`가 기기에 남아 **재부팅 후에도** 산다.

**촬영할 때마다:**

```bash
adb devices                                       # <IP>:5555 보여야 함
adb -s <IP>:5555 shell "screenrecord --time-limit 180 /sdcard/take1.mp4"
adb -s <IP>:5555 pull /sdcard/take1.mp4 ~/shots/
```

기기 IP는 DHCP라 망이 바뀌면 달라진다. **케이블 없이 찾을 수 있다** — 5555 포트를 스캔하면 된다.

```bash
ops/preflight.sh --find     # 새 주소를 찾아 붙이고 전 구간을 점검한다
```

찾는 순서는 두 단계다. 먼저 브로드캐스트 ping으로 ARP를 채우고 **응답한 호스트만**
두드린다. 거기서 못 찾으면(핫스팟에 방금 붙어 ARP가 비었을 때) 넷마스크를 읽어
**실제 서브넷만** 쒸다. 모두 병렬 16개로 제한한다.

2026-09-08 실측(집 `/24`, 안경 1대):

```
ARP 경로      2.0초   후보 5개로 좁혀짐
전체 스윈    17.9초   254개 병렬
(수정 전 순차  4분 17초)
```

아이폰 핫스팟은 `/28`라 스윈해도 14개다.

### 밖에서 찍을 때 망 구성

폰 핫스팟 하나면 된다.

```
폰 핫스팟
  ├── 안경  → 인터넷 (OpenAI 직결 / Vercel / 터널)
  └── 맥북  → 같은 핫스팟 (무선 ADB용)
```

맥북과 안경이 **같은 망**이어야 화면 녹화가 된다. 맥미니는 집에 있어도 무관하다.

중요한 구분이다. **대화는 맥북과 무관하다.** 안경이 인터넷에 직접 붙어
음성(OpenAI)·토큰(Vercel)·코치(터널)를 처리한다. 맥북은 화면 녹화에만 쓴다.
따라서 맥북이 안경을 못 봐도 **대화는 정상이고 녹화만 불가능하다.**

**안경 와이파이는 이미 저장돼 있다** (2026-09-08 확인). `cmd wifi list-networks`에
`iPhone`(id 5)과 `Hotspot`(id 3)이 있다. 핫스팟을 켜면 안경이 알아서 붙으니
렌즈 UI로 새 망을 잡을 일이 없다. 없으면 케이블 꽂고 미리 넣어둔다.

```bash
adb shell cmd wifi list-networks        # 저장된 망 확인
```

### 핫스팟 실측 (2026-09-09, 현장)

예상과 다르다. 아이폰 핫스팟에서는 **안경이 IPv4 주소를 아예 못 받는다.**

```
맥북   192.0.0.2  netmask 0xffffffff  → /32 점대점, GW 192.0.0.1
안경   IPv4 없음. IPv6만 (SLAAC 성공, DHCPv4 실패)
       2001:e60:...:915a/64  — 맥북과 같은 /64
```

**그래도 전부 된다.** IPv4가 없어도 통신사 NAT64가 번역해준다(맥북에 `clat46`
주소가 그 증거). 실측으로 확인한 것:

```
안경 → OpenAI / Vercel   ping 응답
uc571 상태                  status=READY, "Listening..."
adb                      [안경 v6]:5555 로 연결됨
녹화                     무선으로 11프레임/11.2초, ~/shots로 pull 정상
```

그래서 `--find`는 **IPv6 이웃을 먼저** 본다(`ndp -an`, 실측 2.1초). v4만 훑던 이전
판은 이 조건에서 아무것도 못 찾고 격리라고 오진했다.

**무선 adb는 재부팅·망 변경에 꺼진다.** 앱은 멀쩡한데 `service.adb.tcp.port`가
비어있어 `Connection refused`가 난다. USB로 한 번 다시 연다.

```bash
adb tcpip 5555
```

**매장 와이파이를 조심한다.** 안경이 저장된 매장 AP(카페 등)에 먼저 붙으면
캅티브 포털 때문에 주소를 못 받고 `ASSOCIATING`만 반복한다. 더 나쁜 건
**adb로 망을 못 바꿄다는 것**이다. RayNeo가 셀 사용자 권한을 막아둔다.

```
connect-network-id  → SecurityException: Uid 2000 does not have access
forget-network      → Forget failed
```

안경 렌즈 UI나 RayNeo 앱으로 직접 옮겨야 한다. 나가기 전에 매장 망을 잊어두면
그 상황이 안 생긴다.

**격리는 마지막에 의심한다.** 아이폰 "호환성 최대화"를 켜면 2.4GHz로 내려가며
클라이언트 격리가 걸리는 경우가 있다고 알려져 있다. 다만 2026-09-09 실측에서는
2437MHz(2.4GHz)였는데도 **격리가 없었다** — IPv6로 서로 보였고 ping도 0% 손실이었다.
v4/v6 둘 다 못 찾을 때만 의심할 것.

### 녹화 실측 (2026-09-08, 무선)

```
정지 화면 10초   → nb_frames=1     문서가 말한 그대로, 고장 아님
자막 6회 변경    → nb_frames=13    10.6초, 대화가 오가면 쌓인다
```

`screencap`은 순흑이지만 **`screenrecord`는 AR 오버레이를 제대로 잡는다.**
양안(1280x480)이 한 프레임에 들어온다.

받을 폴더를 먼저 만들어둔다. 없는 경로로 `adb pull`하면 그 이름의 **파일**이 되어
다음 테이크가 앞에 것을 덮는다.

```bash
mkdir -p ~/shots
```

### 화면 꺼짐은 촬영을 깨지 않는다 (2026-09-08 실측)

`settings get system screen_off_timeout`이 **60000(1분)**으로 나온다. 그대로라면
180초 촬영 중간에 화면이 꾸지만, 앱을 전면에 두고 185초 방치해도 `mWakefulness=Awake`다.
그 설정값은 앱이 떠 있는 동안 적용되지 않는다. **따로 바꿀 필요 없다.**

다만 앱이 꺼져 있으면 이야기가 다르다. 런처 상태에서는 꾸니 명령 전에 깨운다.

```bash
adb shell input keyevent KEYCODE_WAKEUP
```

앱이 잡은 건 `PARTIAL_WAKE_LOCK`(`AudioIn`, CPU만)이다. 화면을 붙잡는 잠금이 아니니
위 실측은 잠금 덕이 아니라 기기 펌웨어 동작이다. 펌웨어가 바뀌면 재확인해야 한다.

## 5. 나가기 전 점검 — 한 줄

```bash
ops/preflight.sh            # 집에서든 밖에서든
ops/preflight.sh --find     # 케이블 없이 안경 주소까지 찾아서
```

다섯 구간을 순서대로 보고 맨 앞 끊긴 곳에서 멈춰 이유를 말한다.

```
1 맥북 인터넷
2 토큰 서비스 + 로컬 토큰      401이면 Vercel 환경변수와 불일치
3 luma (터널 너머 맥미니)      죽은 터널은 200을 계속 내므로 직접 찔러본다
4 안경 adb                    ← 유일하게 같은 망이 필요한 구간. 녹화전용
5 앱 설치 · 화면 상태         Ready / Listening... 이어야 정상
```

수동으로 보려면 이전 방식도 그대로 유효하다.

```bash
TOK=$(grep '^lumella.localToken=' ~/workspace/lumella/local.properties | cut -d= -f2-)
curl -s -H "X-Lumella-Local-Token: $TOK" https://lumella-token.vercel.app/v1/config
curl -s <위에서 받은 주소>/v1/capabilities     # coach: true
adb devices
```

## 6. 자주 걸리는 것

**안경 와이파이가 재부팅하면 꺼진다.** 세 번 확인했다. 첫 점검 항목.

**화면이 꺼져 있으면** 명령이 안 먹는다.

```bash
adb -s <IP>:5555 shell "input keyevent KEYCODE_WAKEUP"
```

**`am broadcast`에 `-p com.woolab.lumella`를 빠뜨리지 않는다.**

**`.local` 주소를 쓰지 않는다.** 통신사 DNS가 `Woody-M4M.local`을 공인 IP로 해석해
남의 서버에 붙는다. 항상 숫자 IP나 터널 주소를 쓴다.

**녹화 프레임이 1개로 나오는 건 고장이 아니다.** 화면이 안 변하면 그렇게 나온다.
대화가 오가는 동안 찍어야 프레임이 쌓인다.

## 7. 맥미니에만 남는 것

옮기지 않는다. 참고로만.

- `~/Backups/luma/` — 2.9GB, 매일 04:30 자동
- `luma.db` — 실 대화 기록. 터널로 접근하니 복사 불필요
- LaunchAgent 5종 — 맥미니 전용
