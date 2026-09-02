#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

android_imports=$(grep -rln 'import android\.' core/src | wc -l | tr -d ' ' || true)
echo "core android-import files: $android_imports"
if [ "$android_imports" != "0" ]; then
    echo "FAIL: :core must have zero android.* imports"
    exit 1
fi

JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:koverVerify

echo "gates ok"
