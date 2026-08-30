#!/bin/bash
# 建置 + 組安裝包 + 產生 manifest.txt。不發佈。
#
# 本機發佈（發佈.command）與 GitHub Actions 都呼叫這支，
# 打包邏輯只有一份，兩邊不會走鐘。
set -eu

REPO="$(cd "$(dirname "$0")" && pwd)"
cd "$REPO"

sha256_of() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | cut -d' ' -f1
  else
    sha256sum "$1" | cut -d' ' -f1
  fi
}

"$REPO/build.command" > /dev/null
VERSION=$(cat "$REPO/VERSION")
SHA=$(sha256_of "$REPO/posassist.jar")
LAUNCHER_SHA=$(sha256_of "$REPO/installer/payload/PosAssist.command")
PKG="PosAssist-$VERSION"
OUT="$REPO/dist"

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

# 啟動腳本單獨放一份到 dist：它要當成 release 資產，門市才更新得到自己
cp "$REPO/installer/payload/PosAssist.command" "$OUT/PosAssist.command"

cat > "$OUT/manifest.txt" <<EOF
version=$VERSION
sha256=$SHA
launcher_sha256=$LAUNCHER_SHA
EOF

echo "版本 $VERSION"
echo "sha256 $SHA"
echo "啟動腳本 sha256 $LAUNCHER_SHA"
echo "安裝包 $OUT/$PKG.zip"
