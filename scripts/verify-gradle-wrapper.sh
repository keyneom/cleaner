#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROPS="$ROOT/android/gradle/wrapper/gradle-wrapper.properties"
JAR="$ROOT/android/gradle/wrapper/gradle-wrapper.jar"
test -f "$PROPS"
test -f "$JAR"
grep -q 'distributionSha256Sum=' "$PROPS"
echo "Gradle wrapper OK"
