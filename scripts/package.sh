#!/usr/bin/env bash
# Builds the all-in-one server bundle:  dist/TheyWillTalk-<version>-<platform>.zip
#
#   mods/theywilltalk-<version>.jar         the mod
#   theywilltalk/runtime/...                llama.cpp + CUDA runtime, Qwen3-TTS server, all models
#   THEY-WILL-TALK.md                       server install guide
#   THIRD_PARTY_NOTICES.md + licenses/
#
# For servers without internet: unzip it into the server folder and start the server; the dashboard's Models page then
# has nothing left to download. (The voice server ships inside the mod jar.)
#
#   scripts/package.sh [linux-x64|windows-x64]
set -euo pipefail

PLATFORM="${1:-linux-x64}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="$(grep '^mod_version=' "$ROOT/gradle.properties" | cut -d= -f2)"
DIST="$ROOT/dist"
STAGE="$DIST/stage-$PLATFORM"
OUT="$DIST/TheyWillTalk-$VERSION-$PLATFORM.zip"

export JAVA_HOME="${JAVA_HOME:-$HOME/.local/opt/jdk-21}"
export PATH="$JAVA_HOME/bin:$PATH"

echo "== building the mod ($VERSION)"
(cd "$ROOT" && ./gradlew -q :mod:build)

echo "== assembling the runtime"
"$ROOT/scripts/fetch-runtime.sh" "$PLATFORM"

echo "== staging"
rm -rf "$STAGE"
mkdir -p "$STAGE/mods" "$STAGE/theywilltalk/runtime" "$STAGE/licenses"
cp "$ROOT/mod/build/libs/theywilltalk-$VERSION.jar" "$STAGE/mods/"
cp -a "$ROOT/runtime/$PLATFORM" "$STAGE/theywilltalk/runtime/"
mkdir -p "$STAGE/theywilltalk/runtime/models"
for d in llm qwentts tts; do
  [ -d "$ROOT/runtime/models/$d" ] && cp -aL "$ROOT/runtime/models/$d" "$STAGE/theywilltalk/runtime/models/"
done
cp "$ROOT/docs/SERVER_INSTALL.md" "$STAGE/THEY-WILL-TALK.md"
cp "$ROOT/docs/THIRD_PARTY_NOTICES.md" "$STAGE/THIRD_PARTY_NOTICES.md"
cp "$ROOT/LICENSE" "$STAGE/licenses/LICENSE-TheyWillTalk.txt"
[ -f "$STAGE/theywilltalk/runtime/$PLATFORM/llama/LICENSE" ] && cp "$STAGE/theywilltalk/runtime/$PLATFORM/llama/LICENSE" "$STAGE/licenses/LICENSE-llama.cpp.txt"
[ -f "$ROOT/.cache/qwentts-build/linux-x64/LICENSE-qwentts.cpp.txt" ] && cp "$ROOT/.cache/qwentts-build/linux-x64/LICENSE-qwentts.cpp.txt" "$STAGE/licenses/"
[ -f "$ROOT/runtime/models/tts/kokoro/LICENSE" ] && cp "$ROOT/runtime/models/tts/kokoro/LICENSE" "$STAGE/licenses/LICENSE-Kokoro.txt"

echo "== zipping (this is several GB)"
rm -f "$OUT"
python3 - "$STAGE" "$OUT" <<'PY'
import os, sys, zipfile
stage, out = sys.argv[1], sys.argv[2]
# Models and binaries hardly compress: store them, deflate the rest. Keep the executable bits for Linux.
stored = ('.gguf', '.onnx', '.so', '.bin', '.jar', '.exe', '.dll', '.zip')
with zipfile.ZipFile(out, 'w', allowZip64=True) as z:
    for root, _, files in os.walk(stage):
        for name in sorted(files):
            full = os.path.join(root, name)
            rel = os.path.relpath(full, stage)
            info = zipfile.ZipInfo.from_file(full, rel)
            method = zipfile.ZIP_STORED if name.endswith(stored) or '.so.' in name or os.access(full, os.X_OK) else zipfile.ZIP_DEFLATED
            info.compress_type = method
            with open(full, 'rb') as f, z.open(info, 'w', force_zip64=True) as dst:
                while chunk := f.read(1 << 20):
                    dst.write(chunk)
PY
du -sh "$STAGE" "$OUT"
echo "done: $OUT"
