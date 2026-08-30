#!/bin/bash
# 移除 PosAssist。EPB 回到完全原始的狀態。
#
# 安全設計：只刪安裝器自己放進去的東西。目錄裡出現任何不認得的檔案
# （例如有人拿它當開發目錄放了原始碼），就整個停手，不做遞迴刪除。
set -u

EPB_ROOT="${POSASSIST_EPB_ROOT:-}"
if [ -z "$EPB_ROOT" ]; then
  for candidate in /Library/EPBrowser "$HOME/EPBrowser" /Applications/EPBrowser; do
    if [ -d "$candidate/EPB/PosAssist" ]; then
      EPB_ROOT="$candidate"
      break
    fi
  done
fi

if [ -z "$EPB_ROOT" ] || [ ! -d "$EPB_ROOT/EPB/PosAssist" ]; then
  echo "找不到已安裝的 PosAssist，不需要移除。"
  exit 0
fi

TARGET="$EPB_ROOT/EPB/PosAssist"

# 白名單：只有安裝器會產生的項目
UNEXPECTED=""
for entry in "$TARGET"/* "$TARGET"/.[!.]*; do
  [ -e "$entry" ] || continue
  case "$(basename "$entry")" in
    posassist.jar|posassist.jar.prev|PosAssist.command|驗證_PosAssist.command|VERSION|config|logs|.DS_Store) ;;
    *) UNEXPECTED="$UNEXPECTED  $(basename "$entry")
" ;;
  esac
done

if [ -n "$UNEXPECTED" ]; then
  echo "停手：$TARGET 裡有不是安裝器放的東西："
  printf '%s' "$UNEXPECTED"
  echo "為避免誤刪，這支腳本不會動它。請自行確認後手動處理。"
  exit 1
fi

echo "將移除：$TARGET"
printf "確定嗎？(y/N) "
read -r ANSWER
case "$ANSWER" in
  y|Y) ;;
  *) echo "已取消。"; exit 0 ;;
esac

rm -f "$TARGET/posassist.jar" "$TARGET/posassist.jar.prev" \
      "$TARGET/PosAssist.command" "$TARGET/驗證_PosAssist.command" \
      "$TARGET/VERSION" "$TARGET/.DS_Store"
rm -f "$TARGET/config/reservation.properties.example" \
      "$TARGET/config/posassist.properties.example" \
      "$TARGET/config/codes.txt.example"

# 使用者自己的東西一律保留：門市帳密、面板設定、結帳代碼清單。
# 重裝或升級時不必重填，也避免誤刪店員辛苦建好的代碼。
KEPT=""
for f in reservation.properties posassist.properties codes.txt codes.txt.bak codes.pins.txt panel.state; do
  [ -f "$TARGET/config/$f" ] && KEPT="$KEPT  config/$f
"
done
if [ -n "$KEPT" ]; then
  echo "以下是你自己的設定，保留未刪："
  printf '%s' "$KEPT"
else
  rmdir "$TARGET/config" 2>/dev/null || true
fi
rm -rf "$TARGET/logs"
rmdir "$TARGET" 2>/dev/null || echo "（$TARGET 尚有殘留檔案，未刪除目錄本身）"
rm -f "$HOME/Desktop/EPB（含輔助面板）.command"
echo "已移除。EPB 的原廠檔案從頭到尾沒有被動過，原本的捷徑照常使用。"
