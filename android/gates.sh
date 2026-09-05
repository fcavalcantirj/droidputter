#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

android_imports=$(grep -rln 'import android\.' core/src | wc -l | tr -d ' ' || true)
echo "core android-import files: $android_imports"
if [ "$android_imports" != "0" ]; then
    echo "FAIL: :core must have zero android.* imports"
    exit 1
fi

# The Mac builds with Android Studio's JBR; on a GitHub runner (android.yml) that path does not exist and gradlew
# aborts on an invalid JAVA_HOME (7/7 red runs before 2026-09-05), so use the JBR only when it is there.
JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
[ -d "$JBR" ] && export JAVA_HOME="$JBR"
./gradlew --no-daemon :core:koverVerify

echo "gates ok"
