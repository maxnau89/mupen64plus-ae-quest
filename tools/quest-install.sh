#!/bin/sh
# Build the release APK locally and install it on all connected devices (Quest and e.g. an emulator
# used as netplay client).
# Usage: tools/quest-install.sh [--launch]
set -e
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

./gradlew assembleRelease --console=plain -q

for serial in $(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }'); do
    echo "Installing on $serial"
    adb -s "$serial" install -r app/build/outputs/apk/release/Mupen64PlusAE-release.apk
    if [ "$1" = "--launch" ]; then
        adb -s "$serial" shell monkey -p org.mupen64plusae.v3.quest -c android.intent.category.LAUNCHER 1 > /dev/null
    fi
done
