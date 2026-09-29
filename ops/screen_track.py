#!/usr/bin/env python3
"""Builds a take's screen track on the voices' clock: one constant-30fps file whose t=0 is the
POV's (and the voices') t=0. Called by take.sh; plain python3, no packages.

  screen_track.py OUT.mp4 --seg SEG1.mp4 EPOCH1 [--seg SEG2.mp4 EPOCH2 ...]
                  [--tutor TUTOR.wav] [--pov-seg SECONDS EPOCH ...]

Why. The screen and the wearer's view are started seconds apart (take.sh starts the screen first
and then spends ~6 s confirming the POV file grows), and the FINAL used to stack them both from
t=0: in the 9/28 take the tutor's subtitle appeared 3.4 s after its voice (measured by this
script's own alignment, residual 0.17 s). And screenrecord stops at 180 s, so a take longer than
that is several segments that must be laid out at their real start times, not butted together.

How.
1. Stitch: each segment is placed at (its start - the first start), made 30 fps, and held on its
   last frame until the next one starts (screenrecord writes a frame only when the display
   changes, so a quiet screen ends early).
2. Align: the tutor's subtitle (white text) grows on the screen as the tutor speaks. Per POV
   segment (a photo turn splits the POV, and the voices follow the joined video, so the offset
   steps at every join), the offset that lines subtitle growth up with the tutor's voice onsets
   (tutor.wav) is searched near the device-clock prediction ('recording started' of that segment
   minus the screen's start, plus the first-frame latency); a segment with no turn to check uses
   the prediction with the latency measured on the others.
3. Cut the stitched screen into those stretches and join them, so the track follows the POV.
"""
import argparse
import array
import bisect
import os
import subprocess
import sys
import tempfile

FPS = 30


def probe_duration(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
                         capture_output=True, text=True).stdout.strip()
    return float(out) if out else 0.0


def stitch(segs, out):
    """segs: [(path, epoch)] sorted by epoch. Writes a CFR 30 fps file; returns its duration."""
    t0 = segs[0][1]
    inputs, chains, labels = [], [], []
    for i, (path, t) in enumerate(segs):
        inputs += ["-i", path]
        chain = f"[{i}:v]setpts=PTS-STARTPTS,fps={FPS}"
        if i + 1 < len(segs):
            span = max(0.0, segs[i + 1][1] - t)
            chain += f",tpad=stop_mode=clone:stop_duration={span:.3f},trim=duration={span:.3f}"
        chain += f",setpts=PTS-STARTPTS[v{i}]"
        chains.append(chain)
        labels.append(f"[v{i}]")
    graph = ";".join(chains) + f";{''.join(labels)}concat=n={len(segs)}:v=1:a=0[v]"
    subprocess.run(["ffmpeg", "-v", "error", "-y", *inputs, "-filter_complex", graph, "-map", "[v]",
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-r", str(FPS), out], check=True)
    return probe_duration(out)


def subtitle_appearances(path):
    """Times (s) at which white text appears in the tutor-subtitle band of the left eye."""
    # Left eye is 640x480 of the 1280x480 capture; the subtitle band sits between the status line
    # and the coach line. Keep only near-white pixels (the learner echo is blue, the router's
    # reason grey, the status orange and above the band), then average down.
    vf = ("fps=10,crop=600:210:20:90,format=rgb24,lutrgb=r='if(gt(val,190),255,0)',format=gbrp,extractplanes=r,"
          "scale=60:21:flags=area")
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-vf", vf, "-f", "rawvideo", "-pix_fmt", "gray", "-"],
                         capture_output=True).stdout
    n = 60 * 21
    ink = [sum(raw[i:i + n]) / (n * 255.0) for i in range(0, len(raw) - n + 1, n)]
    # The subtitle streams in as the tutor speaks, so the band's white ink grows in steps. A reply
    # starts at the first growth after a quiet spell. Growth only: the band also CLEARS when the
    # learner speaks, and the previous reply may stay up until the next replaces it (retention),
    # so "appears from empty" misses turns (it found 5 of 7 in the 9/28 take).
    out, last = [], -9.0
    for i in range(1, len(ink)):
        if ink[i] - ink[i - 1] > 0.002:
            t = i / 10.0
            if t - last > 1.5:
                out.append(t)
            last = t
    return out


def voice_onsets(wav):
    """Times (s) at which the tutor starts speaking after at least 2 s of silence."""
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", wav, "-ac", "1", "-ar", "1000", "-f", "s16le", "-"],
                         capture_output=True).stdout
    a = array.array("h")
    a.frombytes(raw[: len(raw) // 2 * 2])
    hop = 20
    rms = []
    for i in range(0, len(a) - hop, hop):
        s = 0
        for v in a[i:i + hop]:
            s += v * v
        rms.append((s / hop) ** 0.5)
    if not rms:
        return []
    loud = sorted(rms)[int(len(rms) * 0.95)]
    thr = max(100.0, loud * 0.1)
    out, last = [], -9.0
    for i in range(1, len(rms)):
        t = i * hop / 1000.0
        on = rms[i] > thr
        if on and rms[i - 1] <= thr and t - last > 2.0:
            out.append(t)
        if on:
            last = t
    return out


def fit(appear, onsets, lo, hi):
    """Best offset in [lo, hi] (screen_time = voice_time + offset): (offset, median residual,
    matched) or None."""
    if not appear or not onsets:
        return None
    best = None
    k0, k1 = int(round(lo * 100)), int(round(hi * 100))
    for k in range(k0, k1 + 1):
        off = k * 0.01
        d = []
        for o in onsets:
            x = o + off
            j = bisect.bisect_left(appear, x)
            d.append(min(abs(appear[m] - x) for m in (j - 1, j) if 0 <= m < len(appear)))
        d.sort()
        med = d[len(d) // 2]
        good = sum(1 for v in d if v < 0.5)
        key = (-good, med)
        if best is None or key < best[0]:
            best = (key, off, med, good)
    _, off, med, good = best
    return off, med, good


def cut(src, pieces, out):
    """pieces: [(start_in_src, duration)] -> concatenated file (a negative start holds frame 0)."""
    chains, labels = [], []
    for i, (st, du) in enumerate(pieces):
        if st >= 0:
            chains.append(f"[0:v]trim=start={st:.3f}:duration={du:.3f},setpts=PTS-STARTPTS[p{i}]")
        else:
            chains.append(f"[0:v]tpad=start_mode=clone:start_duration={-st:.3f},trim=duration={du:.3f},"
                          f"setpts=PTS-STARTPTS[p{i}]")
        labels.append(f"[p{i}]")
    # The screen may end before the last piece does (screenrecord writes only on change): hold
    # its last frame so every piece is full length.
    graph = f"[0:v]tpad=stop_mode=clone:stop=-1,split={len(pieces)}" + "".join(f"[s{i}]" for i in range(len(pieces)))
    graph += ";" + ";".join(c.replace("[0:v]", f"[s{i}]", 1) for i, c in enumerate(chains))
    graph += f";{''.join(labels)}concat=n={len(pieces)}:v=1:a=0[v]"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", src, "-filter_complex", graph, "-map", "[v]",
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-r", str(FPS), out], check=True)


FIRST_FRAME_S = 1.0   # 'recording started' to the POV's first frame, when no turn says otherwise


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--seg", nargs=2, action="append", metavar=("MP4", "EPOCH"), required=True)
    ap.add_argument("--tutor")
    ap.add_argument("--pov-seg", nargs=2, action="append", metavar=("SECONDS", "EPOCH"),
                    help="each POV segment in order: its length and its 'recording started' device time")
    args = ap.parse_args()

    segs = sorted(((p, float(t)) for p, t in args.seg if os.path.exists(p) and os.path.getsize(p) > 0),
                  key=lambda s: s[1])
    if not segs:
        print("screen_track: no screen segments", file=sys.stderr)
        return 1
    t0 = segs[0][1]
    tmp = tempfile.NamedTemporaryFile(suffix=".mp4", delete=False).name
    total = stitch(segs, tmp)
    appear = subtitle_appearances(tmp)
    onsets = voice_onsets(args.tutor) if args.tutor and os.path.exists(args.tutor) else []

    if args.pov_seg:
        # The POV is several segments whenever a photo is taken (the camera is lent to the still
        # capture), and the voices follow the joined video, so the screen's offset steps at every
        # join. Map each POV segment to its own stretch of the screen.
        povs = [(float(d), float(e)) for d, e in args.pov_seg]
        rows, j = [], 0.0
        for d, e in povs:
            predicted = (e - t0) - j + FIRST_FRAME_S
            ons = [o for o in onsets if j <= o < j + d]
            f = fit(appear, ons, predicted - 1.5, predicted + 1.5)
            ok = f is not None and f[2] >= 1 and f[1] < 0.35
            rows.append([j, d, e, f[0] if ok else None, f])
            j += d
        lat = sorted(r[3] - ((r[2] - t0) - r[0]) for r in rows if r[3] is not None)
        latency = lat[len(lat) // 2] if lat else FIRST_FRAME_S
        pieces, notes = [], []
        for r in rows:
            jk, d, e, off, f = r
            how = f"{f[2]} turns, {f[1]:.2f}s" if off is not None else f"device clock + {latency:.2f}s"
            if off is None:
                off = (e - t0) - jk + latency
            pieces.append((jk + off, d))
            notes.append(f"{off:+.2f}s ({how})")
        cut(tmp, pieces, args.out)
        summary = "per POV segment " + ", ".join(notes)
    else:
        f = fit(appear, onsets, -20.0, 20.0) if len(onsets) >= 3 else None
        off = f[0] if f and f[2] >= 3 and f[1] < 0.5 else 0.0
        cut(tmp, [(off, max(0.1, total - max(off, 0.0)))], args.out)
        summary = (f"shifted {off:+.2f}s (subtitles vs tutor voice, {f[2]} turns, {f[1]:.2f}s)" if f and off
                   else "not shifted (too few tutor turns to align)")
    os.unlink(tmp)
    print(f"  screen {args.out}  ({len(segs)} segment{'s' if len(segs) > 1 else ''}, {total:.1f}s; {summary})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
