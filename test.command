#!/bin/bash
set -eu
REPO="$(cd "$(dirname "$0")" && pwd)"
"$REPO/build.command"
JDK="${JAVA_HOME:-}"
if [ -z "$JDK" ]; then JDK="$(/usr/libexec/java_home -v 1.8)"; fi
mkdir -p "$REPO/build/tests"
"$JDK/bin/javac" -encoding UTF-8 -source 1.8 -target 1.8 -cp "$REPO/build" -d "$REPO/build/tests" "$REPO"/tests/*.java
"$JDK/bin/java" -Djava.awt.headless=true -cp "$REPO/build:$REPO/build/tests" com.posassist.InventoryTest
"$JDK/bin/java" -Djava.awt.headless=true -cp "$REPO/build:$REPO/build/tests" com.posassist.SelfTestConfigTest
"$JDK/bin/java" -Djava.awt.headless=true -cp "$REPO/build:$REPO/build/tests" com.posassist.VipQueryTest
bash "$REPO/tests/preview-update.sh"
