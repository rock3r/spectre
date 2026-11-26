#!/usr/bin/env bash
set -euo pipefail

for tool in gst-launch-1.0 gst-inspect-1.0 ffprobe ffmpeg; do
  command -v "$tool" >/dev/null || { echo "missing required test tool: $tool" >&2; exit 1; }
done
work="$(mktemp -d)"
gst-inspect-1.0 pipewiresrc >"$work/pipewiresrc.txt"
grep -q 'keepalive-time.*Periodically send last buffer' "$work/pipewiresrc.txt"
trap 'rm -rf "$work"' EXIT
record_for() {
  local output="$1"; shift
  gst-launch-1.0 -q -e "$@" \
    ! videocrop top=8 bottom=8 left=16 right=16 \
    ! videoconvert ! x264enc tune=zerolatency speed-preset=ultrafast \
    ! h264parse ! mp4mux faststart=true ! filesink location="$output"
}

# One damage buffer followed by an actual idle interval. imagefreeze is the test fixture's
# clocked stand-in for pipewiresrc keepalive-time; it accepts exactly one input buffer here.
record_for "$work/static.mp4" \
  videotestsrc num-buffers=1 pattern=red \
  ! video/x-raw,width=64,height=48,framerate=30/1 \
  ! imagefreeze is-live=true num-buffers=36

ffprobe -v error -select_streams v:0 -show_entries frame=pts_time \
  -of csv=p=0 "$work/static.mp4" >"$work/static-pts.txt"
read -r frames first_pts last_pts < <(
  awk '$1 ~ /^-?[0-9]+([.][0-9]+)?$/ { if (!count) first=$1; last=$1; count++ }
       END { print count, first, last }' "$work/static-pts.txt"
)
awk -v frames="$frames" -v first="$first_pts" -v last="$last_pts" 'BEGIN {
  duration=last-first+(1/30);
  if (frames < 30 || frames > 42 || first < -0.001 || first > 0.100 || duration < 1.0 || duration > 1.5) exit 1;
}'

# Cropping must preserve the full static frame, including its first and last pixels.
ffmpeg -v error -i "$work/static.mp4" -frames:v 1 -f rawvideo -pix_fmt rgb24 "$work/frame.rgb"
python3 - "$work/frame.rgb" <<'PY'
from pathlib import Path
import sys
pixels = Path(sys.argv[1]).read_bytes()
assert len(pixels) == 32 * 32 * 3, len(pixels)
for offset in (0, len(pixels) - 3):
    red, green, blue = pixels[offset:offset + 3]
    assert red > 220 and green < 40 and blue < 40, (red, green, blue)
PY

# The same encoder chain must continue to pass real changes rather than freezing the first frame.
record_for "$work/dynamic.mp4" \
  videotestsrc is-live=true num-buffers=36 pattern=ball \
  ! video/x-raw,width=64,height=48,framerate=30/1
mapfile -t hashes < <(ffmpeg -v error -i "$work/dynamic.mp4" -f framemd5 - | awk -F', ' '/^[0-9]+,/{print $NF}')
test "${#hashes[@]}" -ge 30
test "${hashes[0]}" != "${hashes[${#hashes[@]}-1]}"

printf 'static frames=%s first_pts=%s last_pts=%s; dynamic frames=%s\n' \
  "$frames" "$first_pts" "$last_pts" "${#hashes[@]}"
