#!/usr/bin/env bash
# 다른 맥에서 받아온 debug.keystore를 채택한다.
#
# 왜 필요한가: 두 맥이 각자 다른 debug.keystore로 앱에 서명한다. 안경에 A가 서명한
# 앱이 깔려 있으면 B는 설치를 거부당한다(INSTALL_FAILED_UPDATE_INCOMPATIBLE).
# 한쪽 키를 다른 쪽에 복사해 양쪽이 같은 키로 서명하면 영구히 해결된다.
#
# 왜 스크립트인가: 그냥 mv로 기존 키를 치워두면 다음 빌드에서 Gradle이 새 키를
# 자동 생성한다. 지문이 또 달라지는데 눈에 안 띈다. 받아온 파일을 먼저 검증하고
# 교체까지 한 번에 해야 그 사이가 안 생긴다.
#
# 사용법:
#   1. 상대 맥의 ~/.android/debug.keystore 를 에어드롭으로 보낸다
#   2. ~/Desktop/keystore-inbox/ 에 넣는다 (기본 위치, 인자로 바꿀 수 있다)
#   3. 이 스크립트를 실행한다

set -euo pipefail

INCOMING="${1:-$HOME/Desktop/keystore-inbox/debug.keystore}"
TARGET="$HOME/.android/debug.keystore"

fingerprint() {
  keytool -list -v -keystore "$1" -storepass android -alias androiddebugkey 2>/dev/null \
    | grep -i "SHA1:" | head -1 | sed 's/.*SHA1: //' | tr -d ': ' | tr 'A-Z' 'a-z'
}

if [ ! -f "$INCOMING" ]; then
  echo "받아온 파일이 없다: $INCOMING" >&2
  echo "에어드롭으로 상대 맥의 ~/.android/debug.keystore 를 보내고 그 자리에 두어라." >&2
  exit 1
fi

NEW=$(fingerprint "$INCOMING")
if [ -z "$NEW" ]; then
  echo "받아온 파일을 keystore로 읽지 못했다: $INCOMING" >&2
  echo "에어드롭이 중간에 끊겼거나 다른 파일일 수 있다." >&2
  exit 1
fi

OLD=""
[ -f "$TARGET" ] && OLD=$(fingerprint "$TARGET")

echo "현재 이 맥: ${OLD:-(없음)}"
echo "받아온 파일: $NEW"

if [ "$OLD" = "$NEW" ]; then
  echo "이미 같은 키다. 할 일이 없다."
  exit 0
fi

if [ -n "$OLD" ]; then
  BACKUP="$TARGET.$(date +%Y%m%d-%H%M%S).bak"
  cp -p "$TARGET" "$BACKUP"
  echo "기존 키 백업: $BACKUP"
fi

cp -p "$INCOMING" "$TARGET"
CHECK=$(fingerprint "$TARGET")

if [ "$CHECK" != "$NEW" ]; then
  echo "교체 후 지문이 다르다. 복사가 잘못됐다." >&2
  exit 1
fi

echo "채택 완료: $CHECK"
echo
echo "이제 이 맥에서 빌드하면 상대 맥과 같은 키로 서명된다."
echo "안경에 이미 깔린 앱이 그 키로 서명돼 있으면 --force 없이 덮어쓸 수 있다."
