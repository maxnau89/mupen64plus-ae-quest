#!/bin/sh
# Build the release APK locally and install it on the connected Quest.
# Usage: tools/quest-install.sh [--launch]
set -e
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

./gradlew assembleRelease --console=plain -q
adb install -r app/build/outputs/apk/release/Mupen64PlusAE-release.apk

if [ "$1" = "--launch" ]; then
    adb shell monkey -p org.mupen64plusae.v3.quest -c android.intent.category.LAUNCHER 1 > /dev/null
fi
