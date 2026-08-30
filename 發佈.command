#!/bin/bash
# 手動發佈到 GitHub Releases。
#
# 平常不需要用這支 —— push 到 main 時 GitHub Actions 就會自動發佈。
# 這支留著給沒有網路以外的特殊情況，或想在本機先確認打包結果時用。
set -eu

REPO="$(cd "$(dirname "$0")" && pwd)"
cd "$REPO"

"$REPO/打包.command"
VERSION=$(cat "$REPO/VERSION")
PKG="PosAssist-$VERSION"

if [ "${1:-}" != "--release" ]; then
  echo
  echo "只做了建置與打包。要真的發到 GitHub 請加 --release："
  echo "  ./發佈.command --release"
  echo "（或直接 push 到 main，讓 GitHub Actions 自動發佈）"
  exit 0
fi

if gh release view "v$VERSION" >/dev/null 2>&1; then
  echo "v$VERSION 已存在，改為更新既有 release 的檔案"
  gh release upload "v$VERSION" \
    "$REPO/posassist.jar" "$REPO/dist/manifest.txt" "$REPO/dist/PosAssist.command" \
    "$REPO/dist/$PKG.zip" --clobber
else
  gh release create "v$VERSION" \
    "$REPO/posassist.jar" "$REPO/dist/manifest.txt" "$REPO/dist/PosAssist.command" \
    "$REPO/dist/$PKG.zip" \
    --title "PosAssist $VERSION" \
    --notes "門市端會在下次開啟 EPB 時自動更新到這一版。個人設定（帳密、結帳代碼、面板模式）不受影響。"
fi

echo "已發佈 v$VERSION"
