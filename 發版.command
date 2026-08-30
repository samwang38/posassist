#!/bin/bash
# 發一個新版本。你只要跑這一支。
#
#   ./發版.command 1.5.0
#
# 它會改版號、提交、推上去，剩下的交給 GitHub Actions：
# CI 會自己編譯、算 sha256、建 Release，門市下次開 EPB 就會更新到這一版。
set -eu

REPO="$(cd "$(dirname "$0")" && pwd)"
cd "$REPO"

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
  echo "用法：./發版.command <版號>"
  echo "例：  ./發版.command 1.5.0"
  echo
  echo "目前版號：$(grep -oE 'NAME = "[^"]+"' src/com/posassist/Version.java | sed 's/.*"\(.*\)"/\1/')"
  exit 1
fi

if ! printf '%s' "$VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
  echo "版號格式要像 1.5.0"
  exit 1
fi

CURRENT=$(grep -oE 'NAME = "[^"]+"' src/com/posassist/Version.java | sed 's/.*"\(.*\)"/\1/')

# 出貨前擋一種會讓門市開不了店的寫法：$var 後面直接接中文。
# bash 3.2 在 UTF-8 語系會把全形字的位元組當成變數名的一部分，配上 set -u 直接中止，
# 而啟動腳本正是用 set -u 跑的。一律要寫成 ${var}。
BARE=$(grep -rnP '\$[A-Za-z_][A-Za-z0-9_]*[^\x00-\x7F]' --include='*.command' . 2>/dev/null \
  | grep -v '^\./dist/' || true)
if [ -n "$BARE" ]; then
  echo "有 \$變數 後面直接接非 ASCII 字元，請改成 \${變數}："
  printf '%s\n' "$BARE"
  exit 1
fi

if gh release view "v$VERSION" >/dev/null 2>&1; then
  echo "v$VERSION 已經發佈過了，請換一個版號。"
  echo "（門市是比對版號決定要不要更新，重複的版號不會觸發更新。）"
  exit 1
fi

echo "版號 $CURRENT → $VERSION"
echo
echo "這次會提交的變更："
git status --short
echo

printf "確定發佈？(y/N) "
read -r ANSWER
case "$ANSWER" in
  y|Y) ;;
  *) echo "已取消，版號沒有變動。"; exit 0 ;;
esac

# 改版號 —— Version.java 的 NAME 是唯一來源
sed -i '' "s/NAME = \"$CURRENT\"/NAME = \"$VERSION\"/" src/com/posassist/Version.java
NOW=$(grep -oE 'NAME = "[^"]+"' src/com/posassist/Version.java | sed 's/.*"\(.*\)"/\1/')
if [ "$NOW" != "$VERSION" ]; then
  echo "改版號失敗，請手動確認 src/com/posassist/Version.java"
  exit 1
fi

# 先在本機建置一次，編不過就不要推上去浪費一輪 CI
if ! ./build.command > /dev/null 2>&1; then
  echo "本機建置失敗，已把版號改回 ${CURRENT}，請先修好再發。"
  sed -i '' "s/NAME = \"$VERSION\"/NAME = \"$CURRENT\"/" src/com/posassist/Version.java
  exit 1
fi

git add -A
git commit -q -m "$VERSION"
git push -q

echo
echo "已推上去。GitHub Actions 正在建置與發佈 v${VERSION}。"
echo "看進度： gh run watch     或  https://github.com/samwang38/posassist/actions"
echo "發佈完成後，門市下次開 EPB 就會自動更新到這一版。"
