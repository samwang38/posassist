#!/bin/bash
# PosAssist 安裝器（macOS）
#
# 只會新增 <EPB_ROOT>/EPB/PosAssist/ 這個目錄與一個桌面捷徑。
# 不會修改 EPB 的任何原廠檔案，原本的 EPB 捷徑照舊可用。
#
# 非標準安裝位置可用：POSASSIST_EPB_ROOT=/path/to/EPBrowser ./安裝_PosAssist.command

set -u
cd "$(dirname "$0")" || exit 1
HERE="$(pwd)"

echo "===================================="
echo " PosAssist 安裝"
echo "===================================="
echo

# --- 1. 找 EPB 安裝目錄 ---------------------------------------------------
EPB_ROOT="${POSASSIST_EPB_ROOT:-}"
if [ -z "$EPB_ROOT" ]; then
  for candidate in /Library/EPBrowser "$HOME/EPBrowser" /Applications/EPBrowser; do
    if [ -f "$candidate/EPB/Shell/shell.jar" ]; then
      EPB_ROOT="$candidate"
      break
    fi
  done
fi

if [ -z "$EPB_ROOT" ]; then
  echo "找不到 EPB 安裝目錄。"
  printf "請輸入 EPB 安裝目錄（裡面應該有 EPB/Shell/shell.jar）："
  read -r EPB_ROOT
fi
if [ ! -f "$EPB_ROOT/EPB/Shell/shell.jar" ]; then
  echo "在 $EPB_ROOT 找不到 EPB/Shell/shell.jar，安裝中止。"
  exit 1
fi
echo "EPB 安裝目錄：$EPB_ROOT"

# --- 2. 確認這台的 EPB 設定存在 -------------------------------------------
if [ ! -f "$EPB_ROOT/EPB/Setting.xml" ]; then
  echo "找不到 $EPB_ROOT/EPB/Setting.xml，這台 EPB 可能還沒設定完成，安裝中止。"
  exit 1
fi
echo "EPB 設定檔：正常（外掛沿用這台自己的設定，不會寫入任何連線資訊）"

# --- 3. 找 Java 8 ---------------------------------------------------------
JAVA=""
JAVA_HOME_8="$(/usr/libexec/java_home -v 1.8 2>/dev/null || true)"
if [ -n "$JAVA_HOME_8" ] && [ -x "$JAVA_HOME_8/bin/java" ]; then
  JAVA="$JAVA_HOME_8/bin/java"
elif [ -x /Library/Java/JavaVirtualMachines/jdk1.8.0_251.jdk/Contents/Home/bin/java ]; then
  JAVA=/Library/Java/JavaVirtualMachines/jdk1.8.0_251.jdk/Contents/Home/bin/java
else
  echo "找不到 Java 8。EPB 本身就需要 Java 8，請先確認 EPB 能正常開啟。"
  exit 1
fi
echo "Java：$JAVA"

# --- 4. 安裝 -------------------------------------------------------------
TARGET="$EPB_ROOT/EPB/PosAssist"

if [ ! -w "$EPB_ROOT/EPB" ]; then
  echo
  echo "沒有寫入權限：$EPB_ROOT/EPB"
  echo "請改用有權限的帳號執行，或先執行："
  echo "  sudo chown -R \"$USER\" \"$EPB_ROOT/EPB\""
  exit 1
fi

if ! mkdir -p "$TARGET/logs"; then
  echo "無法建立 $TARGET，安裝中止。"
  exit 1
fi
if ! cp "$HERE/payload/posassist.jar" "$TARGET/posassist.jar"; then
  echo "無法複製 posassist.jar，安裝中止。"
  exit 1
fi
cp "$HERE/payload/驗證_PosAssist.command" "$TARGET/驗證_PosAssist.command"
chmod +x "$TARGET/驗證_PosAssist.command"
echo "已安裝：$TARGET/posassist.jar"
echo "已安裝：$TARGET/驗證_PosAssist.command"

# 設定檔：只放範本，絕不覆蓋已填好的正式設定檔
mkdir -p "$TARGET/config"
cp "$HERE/payload/config/reservation.properties.example" \
   "$TARGET/config/reservation.properties.example"
cp "$HERE/payload/config/posassist.properties.example" \
   "$TARGET/config/posassist.properties.example"
cp "$HERE/payload/config/codes.txt.example" "$TARGET/config/codes.txt.example"
if [ -f "$TARGET/config/codes.txt" ]; then
  echo "結帳代碼：沿用既有的 config/codes.txt（未覆蓋）"
else
  echo "結帳代碼：尚未建立。可用面板右上角「編輯」新增，或從 codes.txt.example 複製"
fi
if [ -f "$TARGET/config/posassist.properties" ]; then
  echo "面板設定：沿用既有的 config/posassist.properties（未覆蓋）"
else
  echo "面板設定：使用預設（結帳時嵌進左側欄）。要改回浮動視窗請填 config/posassist.properties"
fi
if [ -f "$TARGET/config/reservation.properties" ]; then
  chmod 600 "$TARGET/config/reservation.properties" 2>/dev/null || true
  echo "預約設定：沿用既有的 config/reservation.properties（未覆蓋）"
else
  echo "預約設定：尚未設定（稍後可用設定視窗填寫）"
fi

# --- 5. 啟動器與版本 ------------------------------------------------------
# 啟動器是獨立的 payload 檔（含自動更新邏輯），直接複製，不再由安裝器內嵌產生
cp "$HERE/payload/PosAssist.command" "$TARGET/PosAssist.command"
chmod +x "$TARGET/PosAssist.command"
cp "$HERE/payload/VERSION" "$TARGET/VERSION"
echo "已安裝：$TARGET/PosAssist.command（版本 $(cat "$TARGET/VERSION")）"

# --- 6. 桌面捷徑 ----------------------------------------------------------
DESKTOP="$HOME/Desktop"
if [ -d "$DESKTOP" ]; then
  ln -sf "$TARGET/PosAssist.command" "$DESKTOP/EPB（含輔助面板）.command"
  echo "已建立桌面捷徑：EPB（含輔助面板）.command"
fi

# --- 7. 自我檢查 ----------------------------------------------------------
echo
echo "------------------------------------"
echo " 自我檢查"
echo "------------------------------------"
cd "$EPB_ROOT/EPB/Shell" || exit 1
"$JAVA" -Dfile.encoding=UTF-8 -Djava.awt.headless=true \
  -Dposassist.logDir="$TARGET/logs" \
  -cp "../PosAssist/posassist.jar:shell.jar:lib/*" \
  com.posassist.SelfTest
STATUS=$?

echo
if [ $STATUS -ne 0 ]; then
  echo "安裝完成，但自我檢查有項目失敗（見上方）。"
  echo "外掛掛不上時 EPB 仍會照常運作，但請把輸出貼回給開發者。"
  exit $STATUS
fi

# --- 8. 首次設定 ----------------------------------------------------------
# 不必手改文字檔：直接把設定視窗叫起來。可以跳過，之後在面板上按「設定」也一樣。
if [ ! -f "$TARGET/config/reservation.properties" ] && [ -z "${POSASSIST_NO_SETUP:-}" ]; then
  echo "------------------------------------"
  echo " 首次設定"
  echo "------------------------------------"
  echo "要現在設定預約系統帳密嗎？設定後查會員時會一併顯示他的近期預約。"
  echo "（跳過也沒關係，之後在面板的預約區按「設定」就能補。）"
  printf "現在設定？(Y/n) "
  read -r ANSWER
  case "${ANSWER:-y}" in
    n|N) echo "已跳過。之後可在面板上按「設定」。" ;;
    *)
      # 一定要帶 EPB 的 lib：解析登入回應要用裡面的 org.json，
      # 只給 posassist.jar 的話「測試連線」會一直失敗
      "$JAVA" -Dfile.encoding=UTF-8 \
        -Dposassist.logDir="$TARGET/logs" \
        -cp "$TARGET/posassist.jar:$EPB_ROOT/EPB/Shell/lib/*" \
        com.posassist.SettingsDialog || true
      ;;
  esac
  echo
fi

echo "安裝完成。用桌面的「EPB（含輔助面板）」開啟即可。"
echo "原本的 EPB 捷徑沒有任何變動，隨時可以改用它。"
echo "之後開啟時會自動檢查更新；不想要的話在 config/posassist.properties 設 autoUpdate=false。"
exit 0
