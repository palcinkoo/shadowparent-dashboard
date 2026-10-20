#!/bin/bash
# One-shot APK build: payload -> encrypted DEX -> container APK.
# Works on a normal x86_64 dev machine and on GitHub Actions runners.
set -e
cd "$(dirname "$0")/.."

export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
GRADLE="./android/gradlew"

echo "[1/4] payload APK"
(cd android && $GRADLE :payload:assembleRelease)

echo "[2/4] encrypt payload DEX"
mkdir -p build/payload_extract
PAY=$(ls payload/build/outputs/apk/release/*.apk | head -1)
(cd build/payload_extract && unzip -o "../../$PAY" classes.dex)
python3 tools/encrypt_payload.py build/payload_extract/classes.dex android/app/src/main/assets/payload.dex.enc

echo "[3/4] container APK"
(cd android && $GRADLE :app:assembleRelease)

echo "[4/4] collect"
mkdir -p build
cp android/app/build/outputs/apk/release/app-release.apk build/android_UI.apk
cp "$PAY" build/payload.apk
ls -la build/*.apk
