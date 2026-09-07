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

키는 일곱이다.

```properties
sdk.dir=/Users/<사용자>/Library/Android/sdk    # 맥북 경로로 고칠 것
lumella.tokenServiceBaseUrl=https://lumella-token.vercel.app
lumella.lumaBaseUrl=http://192.168.35.170:8010   # 대체 주소, 안내판이 우선
lumella.localToken=<맥미니에서 복사>
lumella.brainClassName=...
lumella.brainEmail=...
lumella.brainPassword=<맥미니에서 복사>
```

`sdk.dir`만 맥북 경로로 바꾼다. 나머지는 그대로.

### `luma-api/.env`, `~/.config/lumella/tunnel.env`

**맥북에 필요 없다.** 서버와 터널은 맥미니가 돌린다.

## 3. 빌드 환경

```bash
brew install --cask android-studio temurin@17
brew install gh ffmpeg
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

`JAVA_HOME`을 `~/.zshrc`에 넣어둔다. JDK 17이 아니면 빌드가 깨진다.

```bash
cd ~/workspace/lumella
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**빌드만 하고 설치를 빠뜨리는 실수**가 잦았다. 항상 둘을 같이 한다.

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

기기 IP는 DHCP라 망이 바뀌면 달라진다. 안 붙으면 USB로 한 번 꽂아 IP를 다시 본다.

### 밖에서 찍을 때 망 구성

폰 핫스팟 하나면 된다.

```
폰 핫스팟
  ├── 안경  → 인터넷 (OpenAI 직결 / Vercel / 터널)
  └── 맥북  → 같은 핫스팟 (무선 ADB용)
```

맥북과 안경이 **같은 망**이어야 화면 녹화가 된다. 맥미니는 집에 있어도 무관하다.

## 5. 나가기 전 점검 — 3분

```bash
# 안내판이 맥미니를 가리키나
TOK=$(grep '^lumella.localToken=' ~/workspace/lumella/local.properties | cut -d= -f2-)
curl -s -H "X-Lumella-Local-Token: $TOK" https://lumella-token.vercel.app/v1/config

# 그 주소가 응답하나 (coach: true 나와야 함)
curl -s <위에서 받은 주소>/v1/capabilities

# 안경이 무선으로 붙나
adb devices
```

셋 다 되면 나가도 된다.

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
