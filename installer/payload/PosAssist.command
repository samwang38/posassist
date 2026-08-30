#!/bin/bash
# EPB + POS 輔助面板。
#
# 原本的 EPB 捷徑完全沒動，外掛出任何問題改用原本那個開就一切正常。
#
# 開啟時會檢查有沒有新版並自動更新。更新只發生在這裡 —— EPB 還沒啟動之前，
# 所以絕不會在結帳中途換掉程式。任何一步失敗都直接用現有版本開，不中斷營業。
#
# 不想自動更新：config/posassist.properties 設 autoUpdate=false，就完全不連網。

set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EPB_DIR="$(dirname "$SCRIPT_DIR")"
SHELL_DIR="$EPB_DIR/Shell"
LOG="$SCRIPT_DIR/logs/posassist.log"

# 可用環境變數覆寫，方便測試與將來換發佈位置
MANIFEST_URL="${POSASSIST_MANIFEST_URL:-https://github.com/samwang38/posassist/releases/latest/download/manifest.txt}"
JAR_URL="${POSASSIST_JAR_URL:-https://github.com/samwang38/posassist/releases/latest/download/posassist.jar}"
LAUNCHER_URL="${POSASSIST_LAUNCHER_URL:-https://github.com/samwang38/posassist/releases/latest/download/PosAssist.command}"
CHECK_TIMEOUT=5
DOWNLOAD_TIMEOUT=20

log() {
  mkdir -p "$SCRIPT_DIR/logs" 2>/dev/null || return 0
  printf '%s INFO  [更新] %s\n' "$(date '+%Y-%m-%d %H:%M:%S.000')" "$1" >> "$LOG" 2>/dev/null || true
}

# --- 啟動腳本自我更新 -----------------------------------------------------
# 比對的是雜湊而不是版號：這支腳本自己壞掉時，門市連 EPB 都開不起來，
# 不能只有「版號不同」才修得到。
# 換上去的要下次開店才生效 —— bash 邊讀邊執行，這一次跑的還是舊的那份。
# 一樣任何異常都 return 0。
update_launcher() {
  local want="${1:-}"
  local self="$SCRIPT_DIR/PosAssist.command"
  [ -n "$want" ] || return 0
  [ -f "$self" ] || return 0

  local have
  have=$(shasum -a 256 "$self" 2>/dev/null | cut -d' ' -f1)
  [ -n "$have" ] || return 0
  [ "$have" != "$want" ] || return 0

  local tmp="$self.new"
  rm -f "$tmp"
  if ! curl -fsSL --max-time "$DOWNLOAD_TIMEOUT" "$LAUNCHER_URL" -o "$tmp" 2>/dev/null; then
    rm -f "$tmp"
    log "啟動腳本下載失敗，維持現有版本"
    return 0
  fi

  local got
  got=$(shasum -a 256 "$tmp" 2>/dev/null | cut -d' ' -f1)
  if [ "$got" != "$want" ]; then
    rm -f "$tmp"
    log "啟動腳本雜湊不符，放棄更新"
    return 0
  fi

  # 雜湊只證明檔案傳輸沒壞，還要確定它真的是這支腳本、而且語法過得去。
  # 換上一份跑不起來的啟動腳本，等於門市開不了店。
  if ! head -1 "$tmp" | grep -q '^#!/bin/bash' \
     || ! grep -q 'POSASSIST_MANIFEST_URL' "$tmp" \
     || ! /bin/bash -n "$tmp" 2>/dev/null; then
    rm -f "$tmp"
    log "啟動腳本內容非預期，放棄更新"
    return 0
  fi

  cp -p "$self" "$self.prev" 2>/dev/null || true
  chmod +x "$tmp" 2>/dev/null || true
  # 一定要用 mv 換掉整個檔（換 inode），不能原地覆寫 —— 正在跑的這份還在讀舊檔
  if mv -f "$tmp" "$self" 2>/dev/null; then
    log "啟動腳本已更新，下次開店生效（上一版留在 PosAssist.command.prev）"
  else
    rm -f "$tmp"
    log "啟動腳本換版失敗，維持現有版本"
  fi
  return 0
}

# --- 自動更新 -------------------------------------------------------------
# 任何異常一律 return 0：更新失敗絕不能擋住開店。
auto_update() {
  local cfg="$SCRIPT_DIR/config/posassist.properties"
  if [ -f "$cfg" ] && grep -qiE '^[[:space:]]*autoUpdate[[:space:]]*=[[:space:]]*false' "$cfg"; then
    return 0
  fi
  if [ ! -w "$SCRIPT_DIR" ]; then
    log "安裝目錄不可寫，略過更新"
    return 0
  fi

  local local_ver=""
  [ -f "$SCRIPT_DIR/VERSION" ] && local_ver=$(head -1 "$SCRIPT_DIR/VERSION" | tr -d '[:space:]')

  local manifest
  manifest=$(curl -fsSL --max-time "$CHECK_TIMEOUT" "$MANIFEST_URL" 2>/dev/null) || {
    log "檢查更新失敗（可能沒網路），用現有版本 ${local_ver:-未知}"
    return 0
  }

  local remote_ver remote_sha remote_launcher
  remote_ver=$(printf '%s\n' "$manifest" | grep -E '^version=' | head -1 | cut -d= -f2- | tr -d '[:space:]')
  remote_sha=$(printf '%s\n' "$manifest" | grep -E '^sha256=' | head -1 | cut -d= -f2- | tr -d '[:space:]')
  remote_launcher=$(printf '%s\n' "$manifest" | grep -E '^launcher_sha256=' | head -1 | cut -d= -f2- | tr -d '[:space:]')

  if [ -z "$remote_ver" ] || [ -z "$remote_sha" ]; then
    log "manifest 格式非預期，略過更新"
    return 0
  fi

  # 先修啟動腳本再看版號：版號一樣但腳本壞掉的情況也要救得回來
  update_launcher "$remote_launcher"

  if [ "$remote_ver" = "$local_ver" ]; then
    return 0
  fi

  log "發現新版 ${remote_ver}（目前 ${local_ver:-未知}），下載中"
  local tmp="$SCRIPT_DIR/posassist.jar.new"
  rm -f "$tmp"
  if ! curl -fsSL --max-time "$DOWNLOAD_TIMEOUT" "$JAR_URL" -o "$tmp" 2>/dev/null; then
    rm -f "$tmp"
    log "下載失敗，維持現有版本"
    return 0
  fi

  local got
  got=$(shasum -a 256 "$tmp" 2>/dev/null | cut -d' ' -f1)
  if [ "$got" != "$remote_sha" ]; then
    rm -f "$tmp"
    log "雜湊不符，放棄更新（預期 ${remote_sha}，實得 ${got:-無})"
    return 0
  fi

  # 雜湊對了還要確認真的是可用的 jar，避免換上一個開不起來的檔案
  if ! unzip -l "$tmp" 2>/dev/null | grep -q "com/posassist/Launcher.class"; then
    rm -f "$tmp"
    log "檔案不是合法的 posassist jar，放棄更新"
    return 0
  fi

  # 舊版留著，出事可以直接換回來
  if [ -f "$SCRIPT_DIR/posassist.jar" ]; then
    mv -f "$SCRIPT_DIR/posassist.jar" "$SCRIPT_DIR/posassist.jar.prev" 2>/dev/null || true
  fi
  if mv -f "$tmp" "$SCRIPT_DIR/posassist.jar" 2>/dev/null; then
    printf '%s\n' "$remote_ver" > "$SCRIPT_DIR/VERSION"
    log "已更新到 ${remote_ver}（上一版留在 posassist.jar.prev）"
  else
    rm -f "$tmp"
    log "換版失敗，維持現有版本"
  fi
  return 0
}

# --- 啟動 -----------------------------------------------------------------

if [ ! -f "$SHELL_DIR/shell.jar" ]; then
  echo "找不到 $SHELL_DIR/shell.jar，請確認 PosAssist 裝在 <EPB_ROOT>/EPB/PosAssist/ 底下"
  exit 1
fi
if [ -e "$EPB_DIR/Patching.lock" ]; then
  echo "EPB 正在更新中，請稍候再開"
  exit 1
fi

# 用子殼跑：更新裡面出任何事都只結束子殼，開店這條路不會被擋住
( auto_update ) || true

# 支援用途：只跑一次更新檢查、不開 EPB，方便現場診斷更新有沒有問題
if [ "${1:-}" = "--update-only" ]; then
  echo "更新檢查完成，目前版本 $(cat "$SCRIPT_DIR/VERSION" 2>/dev/null || echo 未知)"
  echo "詳情見 $LOG"
  exit 0
fi

JAVA_HOME_8="$(/usr/libexec/java_home -v 1.8 2>/dev/null || true)"
if [ -n "$JAVA_HOME_8" ] && [ -x "$JAVA_HOME_8/bin/java" ]; then
  JAVA="$JAVA_HOME_8/bin/java"
elif [ -x /Library/Java/JavaVirtualMachines/jdk1.8.0_251.jdk/Contents/Home/bin/java ]; then
  JAVA=/Library/Java/JavaVirtualMachines/jdk1.8.0_251.jdk/Contents/Home/bin/java
else
  echo "找不到 Java 8。EPB 本身就需要 Java 8，請先確認 EPB 能正常開啟。"
  exit 1
fi

# EPB 要求工作目錄是 Shell/，否則找不到 Setting.xml 與 log/
cd "$SHELL_DIR" || exit 1

exec "$JAVA" \
  -Xms256m -Xmx1024m \
  -Dfile.encoding=UTF-8 \
  -Dposassist.appCode="${POSASSIST_APP_CODE:-POSN}" \
  -Dposassist.logDir="$SCRIPT_DIR/logs" \
  -cp "../PosAssist/posassist.jar:shell.jar:lib/*:../Trans/lib/*" \
  com.posassist.Launcher
