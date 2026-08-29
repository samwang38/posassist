#!/bin/bash
# 建置 → 組安裝包 → 發到 GitHub Releases。
#
# 版本號的唯一來源是 src/com/posassist/Version.java 的 NAME，改那裡就好。
# 發出去的 manifest.txt 帶 sha256，門市的啟動器會用它驗證下載的 jar。
set -eu

REPO="$(cd "$(dirname "$0")" && pwd)"
cd "$REPO"

"$REPO/build.command" > /dev/null
VERSION=$(cat "$REPO/VERSION")
SHA=$(shasum -a 256 "$REPO/posassist.jar" | cut -d' ' -f1)
PKG="PosAssist-$VERSION"
OUT="$REPO/dist"

echo "版本 $VERSION"
echo "sha256 $SHA"

# --- 組安裝包 -------------------------------------------------------------
rm -rf "$OUT/$PKG" "$OUT/$PKG.zip"
mkdir -p "$OUT/$PKG/payload/config"

cp "$REPO/installer/安裝_PosAssist.command" "$OUT/$PKG/"
cp "$REPO/installer/移除_PosAssist.command" "$OUT/$PKG/"
cp "$REPO/installer/README.md" "$OUT/$PKG/"
cp "$REPO/installer/payload/PosAssist.command" "$OUT/$PKG/payload/"
cp "$REPO/installer/payload/驗證_PosAssist.command" "$OUT/$PKG/payload/"
cp "$REPO"/installer/payload/config/*.example "$OUT/$PKG/payload/config/"
cp "$REPO/posassist.jar" "$OUT/$PKG/payload/"
cp "$REPO/VERSION" "$OUT/$PKG/payload/"
chmod +x "$OUT/$PKG"/*.command "$OUT/$PKG/payload"/*.command

# 安裝包裡絕不能有任何真實設定檔
if find "$OUT/$PKG" -name "*.properties" -not -name "*.example" | grep -q .; then
  echo "中止：安裝包裡出現了非範本的設定檔"
  exit 1
fi
if find "$OUT/$PKG" -name "codes.txt" | grep -q .; then
  echo "中止：安裝包裡出現了 codes.txt"
  exit 1
fi

(cd "$OUT" && zip -qr "$PKG.zip" "$PKG" -x '*.DS_Store')

# --- manifest -------------------------------------------------------------
cat > "$OUT/manifest.txt" <<EOF
version=$VERSION
sha256=$SHA
EOF

echo "安裝包 $OUT/$PKG.zip"

# --- 發佈 -----------------------------------------------------------------
if [ "${1:-}" != "--release" ]; then
  echo
  echo "只做了建置與打包。要真的發到 GitHub 請加 --release："
  echo "  ./發佈.command --release"
  exit 0
fi

if gh release view "v$VERSION" >/dev/null 2>&1; then
  echo "v$VERSION 已存在，改為更新既有 release 的檔案"
  gh release upload "v$VERSION" \
    "$REPO/posassist.jar" "$OUT/manifest.txt" "$OUT/$PKG.zip" --clobber
else
  gh release create "v$VERSION" \
    "$REPO/posassist.jar" "$OUT/manifest.txt" "$OUT/$PKG.zip" \
    --title "PosAssist $VERSION" \
    --notes "門市端會在下次開啟 EPB 時自動更新到這一版。個人設定（帳密、結帳代碼、面板模式）不受影響。"
fi

echo "已發佈 v$VERSION"
