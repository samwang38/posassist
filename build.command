#!/bin/bash
# 建置 posassist.jar。
# 外掛零編譯期依賴 EPB，所以刻意不帶任何 EPB classpath —— 這是設計不變量，別加。
set -eu

REPO="$(cd "$(dirname "$0")" && pwd)"

JAVA_HOME_8="$(/usr/libexec/java_home -v 1.8 2>/dev/null || true)"
if [ -z "$JAVA_HOME_8" ]; then
  JAVA_HOME_8=/Library/Java/JavaVirtualMachines/jdk1.8.0_251.jdk/Contents/Home
fi
if [ ! -x "$JAVA_HOME_8/bin/javac" ]; then
  echo "找不到 Java 8，無法建置"
  exit 1
fi

rm -rf "$REPO/build"
mkdir -p "$REPO/build"

"$JAVA_HOME_8/bin/javac" -encoding UTF-8 -source 1.8 -target 1.8 \
  -d "$REPO/build" "$REPO"/src/com/posassist/*.java

# 資源檔（憑證）一起進 jar
mkdir -p "$REPO/build/com/posassist/trust"
cp "$REPO"/src/com/posassist/trust/*.pem "$REPO/build/com/posassist/trust/"

"$JAVA_HOME_8/bin/jar" cfe "$REPO/posassist.jar" \
  com.posassist.Launcher -C "$REPO/build" com

# 版本號以 Version.java 為唯一來源，避免兩邊對不起來
VERSION=$(grep -oE 'NAME = "[^"]+"' "$REPO/src/com/posassist/Version.java" | sed 's/.*"\(.*\)"/\1/')
echo "$VERSION" > "$REPO/VERSION"

echo "已建置 posassist.jar（版本 $VERSION）"
echo "sha256 $(shasum -a 256 "$REPO/posassist.jar" | cut -d' ' -f1)"
