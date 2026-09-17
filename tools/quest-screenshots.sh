#!/bin/sh
# Pull screenshots taken inside the headset into docs/screenshots/.
#
# The Quest compositor can't be captured with `adb screencap`, so in-VR shots have to be taken with
# the headset itself: press the Meta button, pick Camera, then Take Photo. The captures land in
# /sdcard/Oculus/Screenshots and this script copies the ones taken today into the repo.
#
# Usage: tools/quest-screenshots.sh [YYYYMMDD]
set -e
cd "$(dirname "$0")/.."

day="${1:-$(date +%Y%m%d)}"
out="docs/screenshots/vr"
mkdir -p "$out"

serial=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')
if [ -z "$serial" ]; then
    echo "No device connected" >&2
    exit 1
fi

found=0
for file in $(adb -s "$serial" shell ls /sdcard/Oculus/Screenshots/ | tr -d '\r' | grep "$day" || true); do
    echo "Pulling $file"
    adb -s "$serial" pull "/sdcard/Oculus/Screenshots/$file" "$out/$file" > /dev/null
    found=$((found + 1))
done

if [ "$found" -eq 0 ]; then
    echo "No captures from $day in /sdcard/Oculus/Screenshots" >&2
    exit 1
fi
echo "$found capture(s) in $out"
