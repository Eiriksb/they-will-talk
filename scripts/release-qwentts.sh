#!/usr/bin/env bash
# Packages the qwentts.cpp tts-server built by build-qwentts.sh for the dashboard's Models page, and prints the values
# for its entry in mod/src/main/resources/assets/theywilltalk/runtime/catalog.json (url, sha256, size).
#
#   scripts/release-qwentts.sh             package into dist/
#   scripts/release-qwentts.sh --publish   ...and upload it as a GitHub release (needs gh with push access)
#
# A published asset is never replaced: released mod versions pin its SHA-256. Build a new commit under a new tag instead.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="Eiriksb/they-will-talk"
PLATFORM="linux-x64"
COMMIT="$(grep '^COMMIT=' "$ROOT/scripts/build-qwentts.sh" | cut -d'"' -f2)"
TAG="qwentts-$COMMIT"
NAME="qwentts-tts-server-$COMMIT-$PLATFORM.tar.gz"
SRC="$ROOT/.cache/qwentts-build/$PLATFORM"
OUT="$ROOT/dist/$NAME"

[ -x "$SRC/tts-server" ] || "$ROOT/scripts/build-qwentts.sh"
mkdir -p "$ROOT/dist"
# Reproducible archive: fixed order, owner and timestamps.
tar --sort=name --owner=0 --group=0 --numeric-owner --mtime='2026-01-01 00:00Z' -C "$SRC" -cf - tts-server LICENSE-qwentts.cpp.txt \
  | gzip -n -9 > "$OUT"

SHA="$(sha256sum "$OUT" | cut -d' ' -f1)"
SIZE="$(stat -c %s "$OUT")"
URL="https://github.com/$REPO/releases/download/$TAG/$NAME"
echo "url    $URL"
echo "sha256 $SHA"
echo "size   $SIZE"

if [ "${1:-}" = "--publish" ]; then
  if gh release view "$TAG" -R "$REPO" --json assets --jq '.assets[].name' 2>/dev/null | grep -qx "$NAME"; then
    echo "$NAME is already published under $TAG; not replacing it (its SHA-256 is pinned by released mods)." >&2
    exit 1
  fi
  gh release view "$TAG" -R "$REPO" >/dev/null 2>&1 || gh release create "$TAG" -R "$REPO" \
    --title "Qwen3-TTS server (qwentts.cpp $COMMIT)" \
    --notes "Prebuilt [qwentts.cpp](https://github.com/ServeurpersoCom/qwentts.cpp) \`tts-server\` (commit $COMMIT, CUDA 12.8, GPUs from Pascal to Blackwell) for They Will Talk's dashboard. Downloaded from the Models page and checked against the SHA-256 pinned in the mod. MIT licensed, see LICENSE-qwentts.cpp.txt. It uses the CUDA runtime from the llama.cpp package."
  gh release upload "$TAG" "$OUT" -R "$REPO"
  echo "published $URL"
fi
