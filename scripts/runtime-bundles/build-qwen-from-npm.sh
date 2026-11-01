#!/usr/bin/env bash
set -euo pipefail

# Package the pinned Qwen Code npm payload into a runtime overlay. The published
# package ships zero regular dependencies, so the official registry tarball is
# the complete runtime. Optional native add-ons (sharp, node-pty, clipboard,
# audio-capture) are intentionally excluded: PRoot cannot load them and the
# headless `-p` sessions PocketDev runs never use them. No npm or network
# dependency resolution is required beyond this single signed tarball.
VERSION="${POCKETDEV_QWEN_VERSION:-0.26.5}"
BUNDLE_VERSION="${POCKETDEV_QWEN_BUNDLE_VERSION:-2026.11.1}"
# sha512 integrity reported by registry.npmjs.org for this exact tarball.
INTEGRITY_SHA512_B64="iqcBqLQDmKvOtRcVvF78l7bf0NpdymGFBo0qNY0ausPezht+PLXkh15vnIILaBKtM4BabtHSv60xWftMomJ7hw=="
ARCHIVE="pocketdev-qwen-arm64-${BUNDLE_VERSION}.tar.zst"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TARBALL="qwen-code-${VERSION}.tgz"
URL="https://registry.npmjs.org/@qwen-code/qwen-code/-/qwen-code-${VERSION}.tgz"

command -v zstd >/dev/null || { echo "zstd is required" >&2; exit 1; }
command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }

temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT
mkdir -p "$temp_dir/payload" "$temp_dir/stage" dist/runtime-bundles

if [[ ! -f "dist/runtime-bundles/$TARBALL" ]]; then
  echo "Downloading $URL"
  curl -fL --retry 3 -o "$temp_dir/$TARBALL" "$URL"
else
  cp "dist/runtime-bundles/$TARBALL" "$temp_dir/$TARBALL"
fi

actual="$(openssl dgst -sha512 -binary "$temp_dir/$TARBALL" | openssl base64 -A)"
[[ "$actual" == "$INTEGRITY_SHA512_B64" ]] || {
  echo "Qwen Code tarball checksum mismatch" >&2
  exit 1
}

tar -xf "$temp_dir/$TARBALL" -C "$temp_dir/stage"
[[ -f "$temp_dir/stage/package/cli-entry.js" ]] || {
  echo "npm package layout changed; cli-entry.js not found" >&2
  exit 1
}

payload="$temp_dir/payload"
mkdir -p "$payload/usr/local/lib/qwen/node_modules/@qwen-code" "$payload/usr/local/lib/qwen/node_modules/.bin" "$payload/usr/local/bin"
cp -a "$temp_dir/stage/package" "$payload/usr/local/lib/qwen/node_modules/@qwen-code/qwen-code"
chmod 0755 "$payload/usr/local/lib/qwen/node_modules/@qwen-code/qwen-code/cli-entry.js"
ln -s ../@qwen-code/qwen-code/cli-entry.js "$payload/usr/local/lib/qwen/node_modules/.bin/qwen"
ln -s /usr/local/lib/qwen/node_modules/.bin/qwen "$payload/usr/local/bin/qwen"
printf '%s' "$VERSION" > "$payload/.pocket-qwen-version"

python3 "$SCRIPT_DIR/create_deterministic_tar.py" "$payload" "$temp_dir/payload.tar"
zstd -19 -T0 -f "$temp_dir/payload.tar" -o "dist/runtime-bundles/$ARCHIVE"

shasum -a 256 "dist/runtime-bundles/$ARCHIVE"
wc -c "dist/runtime-bundles/$ARCHIVE" "$temp_dir/payload.tar"
echo "Qwen Code bundle created: dist/runtime-bundles/$ARCHIVE"
