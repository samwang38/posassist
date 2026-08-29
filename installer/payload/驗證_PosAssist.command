#!/bin/bash
# 自我檢查：確認外掛依賴的掛載點在這台 EPB 上都還在。
# 不需登入、不需 POS 權限。EPB 改版後想確認外掛還能不能用就跑這支。
set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EPB_DIR="$(dirname "$SCRIPT_DIR")"
SHELL_DIR="$EPB_DIR/Shell"

if [ ! -f "$SHELL_DIR/shell.jar" ]; then
  echo "找不到 $SHELL_DIR/shell.jar"
  exit 1
fi

JAVA_HOME_8="$(/usr/libexec/java_home -v 1.8 2>/dev/null || true)"
if [ -n "$JAVA_HOME_8" ] && [ -x "$JAVA_HOME_8/bin/java" ]; then
  JAVA="$JAVA_HOME_8/bin/java"
else
  JAVA=/Library/Java/JavaVirtualMachines/jdk1.8.0_251.jdk/Contents/Home/bin/java
fi

cd "$SHELL_DIR" || exit 1
"$JAVA" -Dfile.encoding=UTF-8 -Djava.awt.headless=true \
  -Dposassist.logDir="$SCRIPT_DIR/logs" \
  -cp "../PosAssist/posassist.jar:shell.jar:lib/*" \
  com.posassist.SelfTest
STATUS=$?

echo
if [ $STATUS -eq 0 ]; then
  echo "全部通過，外掛可以正常使用。"
else
  echo "有項目失敗，請把上面輸出貼回給開發者。"
  echo "外掛掛不上時 EPB 仍會照常運作。"
fi
exit $STATUS
