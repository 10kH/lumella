#!/usr/bin/env bash
# Read what is actually on the glasses screen, without being able to look at them.
#
# Why this exists: `adb shell screencap` returns solid black on this hardware — the AR
# waveguide overlay is not composited into the framebuffer — so the usual way of checking a
# layout remotely does not work. The accessibility view tree does contain the live text of
# every TextView, which is enough to verify what a wearer would be reading.
#
# Prints one line per non-empty text node: view id, on-screen bounds, and the text. The same
# view appears twice because the glasses render each eye separately (left pane x<640,
# right pane x>=640) — seeing both is how you confirm binocular rendering is intact.
#
#   ops/screen-dump.sh            # one snapshot
#   ops/screen-dump.sh -w         # re-read every 2s until Ctrl-C
#   ops/screen-dump.sh --svg      # also write ops/glasses-preview.svg, a to-scale picture
#
# To inspect the subtitles without a wearer, put known text on screen first:
#   adb shell am broadcast -a com.woolab.lumella.DEBUG_SUBTITLE
#   adb shell am broadcast -a com.woolab.lumella.DEBUG_SUBTITLE --es tutor "'your text here'"

set -euo pipefail

snapshot() {
  local want_svg="${1:-}"
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || {
    echo "ERROR: uiautomator dump failed (device connected? app in foreground?)" >&2
    return 1
  }
  adb pull /sdcard/ui.xml /tmp/lumella-ui.xml >/dev/null 2>&1
  WANT_SVG="$want_svg" SVG_DEST="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/glasses-preview.svg" python3 - <<'PY'
import os
import re
import html
try:
    x = open('/tmp/lumella-ui.xml', encoding='utf-8').read()
except OSError:
    raise SystemExit("no dump file")

rows = []
for m in re.finditer(r'<node[^>]*>', x):
    tag = m.group(0)
    # uiautomator quotes attributes with SINGLE quotes when the value contains a double
    # quote (a tutor reply quoting a sentence does exactly that). Matching only double-quoted
    # attributes made every such subtitle read as absent — an hour was spent chasing a
    # "cleared" subtitle that was on screen the whole time.
    text = re.search(r'text="([^"]*)"', tag) or re.search(r"text='([^']*)'", tag)
    if not text or not text.group(1):
        continue
    rid = re.search(r'resource-id="([^"]*)"', tag) or re.search(r"resource-id='([^']*)'", tag)
    bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    name = rid.group(1).split('/')[-1] if rid and rid.group(1) else '(no id)'
    box = tuple(int(v) for v in bounds.groups()) if bounds else None
    eye = ''
    if box:
        eye = 'L' if box[0] < 640 else 'R'
    rows.append((name, eye, box, text.group(1)))

if not rows:
    print('  (no text on screen)')
for name, eye, box, t in rows:
    b = f'[{box[0]},{box[1]}][{box[2]},{box[3]}]' if box else ''
    print(f'  {eye}  {name:14} {b:26} "{t}"')

if not os.environ.get('WANT_SVG'):
    raise SystemExit(0)

# Draw the same data to scale so the layout can be eyeballed in a browser. Sizes and
# colours mirror activity_main.xml; wrapping here is an approximation, so trust the
# printed rows for exact content and this picture only for proportion and placement.
STYLE = {
    'tvStatus': (48, '#FFFFFF', '700'),
    'tvSubtitle': (24, '#FFFFFF', '400'),
    'tvUserEcho': (18, '#7FB3FF', '400'),
    'tvHint': (18, '#888888', '400'),
}
FAMILY = "'Apple SD Gothic Neo','Noto Sans KR',sans-serif"
W, H = 1280, 480
out = [
    f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">',
    f'<rect width="{W}" height="{H}" fill="#000"/>',
    f'<line x1="640" y1="0" x2="640" y2="{H}" stroke="#222" stroke-width="1"/>',
    '<text x="6" y="14" font-size="11" fill="#333" font-family="monospace">left eye</text>',
    '<text x="646" y="14" font-size="11" fill="#333" font-family="monospace">right eye</text>',
]


def is_wide(ch):
    cp = ord(ch)
    return (0x1100 <= cp <= 0x115F or 0x2E80 <= cp <= 0x303E or 0x3041 <= cp <= 0x33FF
            or 0x3400 <= cp <= 0x4DBF or 0x4E00 <= cp <= 0x9FFF or 0xAC00 <= cp <= 0xD7A3
            or 0xFF00 <= cp <= 0xFF60)


for name, eye, box, text in rows:
    if not box:
        continue
    size, colour, weight = STYLE.get(name, (18, '#CCCCCC', '400'))
    x0, y0, x1, _ = box
    # Latin glyphs run about half an em; wide scripts a full em.
    per_line = max(1, int((x1 - x0) / (size * 0.5)))
    lines, cur, used = [], '', 0
    for word in text.split(' '):
        cost = sum(2 if is_wide(c) else 1 for c in word) + (1 if cur else 0)
        if used + cost > per_line and cur:
            lines.append(cur)
            cur, used = word, cost
        else:
            cur = f'{cur} {word}'.strip()
            used += cost
    if cur:
        lines.append(cur)
    for i, line in enumerate(lines):
        y = y0 + size * 0.85 + i * size * 1.28
        out.append(
            f'<text x="{(x0 + x1) / 2:.0f}" y="{y:.0f}" font-size="{size}" fill="{colour}" '
            f'font-weight="{weight}" font-family="{FAMILY}" text-anchor="middle">'
            f'{html.escape(line)}</text>'
        )
out.append('</svg>')

dest = os.environ.get('SVG_DEST', 'glasses-preview.svg')
with open(dest, 'w', encoding='utf-8') as fh:
    fh.write('\n'.join(out))
print(f'\n  wrote {dest}')
PY
}

case "${1:-}" in
  -w)
    while true; do
      printf '\n--- %s ---\n' "$(date '+%H:%M:%S')"
      snapshot || true
      sleep 2
    done
    ;;
  --svg)
    snapshot svg
    ;;
  '')
    snapshot
    ;;
  *)
    echo "usage: ops/screen-dump.sh [-w | --svg]" >&2
    exit 2
    ;;
esac
