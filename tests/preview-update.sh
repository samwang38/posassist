#!/bin/bash
# Run the real launcher update-only in a disposable installation with a fake curl.
set -eu
REPO="$(cd "$(dirname "$0")/.." && pwd)"
TEST_ROOT=$(mktemp -d)
trap 'rm -rf "$TEST_ROOT"' EXIT
mkdir -p "$TEST_ROOT/EPB/PosAssist/config" "$TEST_ROOT/EPB/Shell" "$TEST_ROOT/bin"
touch "$TEST_ROOT/EPB/Shell/shell.jar"
cp "$REPO/installer/payload/PosAssist.command" "$TEST_ROOT/EPB/PosAssist/"
cat > "$TEST_ROOT/bin/curl" <<'EOF'
#!/bin/bash
echo called >> "$UPDATE_TEST_CALLS"
exit 1
EOF
chmod +x "$TEST_ROOT/bin/curl"
export UPDATE_TEST_CALLS="$TEST_ROOT/calls"
export PATH="$TEST_ROOT/bin:$PATH"
printf 'autoUpdate=true\n' > "$TEST_ROOT/EPB/PosAssist/config/posassist.properties"
printf '1.6.0-preview.1\n' > "$TEST_ROOT/EPB/PosAssist/VERSION"
bash "$TEST_ROOT/EPB/PosAssist/PosAssist.command" --update-only >/dev/null
test ! -e "$UPDATE_TEST_CALLS"
printf '1.5.9\n' > "$TEST_ROOT/EPB/PosAssist/VERSION"
bash "$TEST_ROOT/EPB/PosAssist/PosAssist.command" --update-only >/dev/null
test -s "$UPDATE_TEST_CALLS"
printf 'autoUpdate=false\n' > "$TEST_ROOT/EPB/PosAssist/config/posassist.properties"
rm "$UPDATE_TEST_CALLS"
bash "$TEST_ROOT/EPB/PosAssist/PosAssist.command" --update-only >/dev/null
test ! -e "$UPDATE_TEST_CALLS"
echo 'Preview update: 3 checks passed'
