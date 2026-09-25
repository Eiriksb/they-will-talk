#!/usr/bin/env bash
# Assembles runtime/<platform> + runtime/models ahead of time: the LLM server, voice servers and models.
#
# OPTIONAL, for offline servers (see package.sh). Normally admins download the same things from the dashboard's
# Models page (assets/theywilltalk/runtime/catalog.json). Re-running is cheap: downloads are cached in .cache/downloads.
#
#   scripts/fetch-runtime.sh [linux-x64|windows-x64]
set -euo pipefail

PLATFORM="${1:-linux-x64}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RT="$ROOT/runtime"
CACHE="$ROOT/.cache/downloads"
META="$ROOT/mod/src/main/resources/assets/theywilltalk/runtime/engine-meta"
mkdir -p "$CACHE" "$RT/models/llm" "$RT/models/tts"

# ---- pinned versions ----------------------------------------------------------------------------------------------
LLAMA_BUILD="b11160"
LLM_URL="https://huggingface.co/unsloth/gemma-4-E2B-it-qat-GGUF/resolve/main/gemma-4-E2B-it-qat-UD-Q4_K_XL.gguf"
KOKORO_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2"
SUPERTONIC_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2"
QWENTTS_REPO="https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/main"
QWENTTS_FILES=(qwen-talker-1.7b-voicedesign-Q4_K_M.gguf qwen-tokenizer-12hz-Q8_0.gguf)

dl() { # url target
  local url="$1" out="$2"
  if [ -s "$out" ]; then return; fi
  echo "  downloading $(basename "$out")"
  curl -fSL --retry 5 -C - -o "$out.part" "$url"
  mv "$out.part" "$out"
}

echo "== llama.cpp $LLAMA_BUILD ($PLATFORM, CUDA)"
case "$PLATFORM" in
  linux-x64)
    dl "https://github.com/ggml-org/llama.cpp/releases/download/$LLAMA_BUILD/llama-$LLAMA_BUILD-bin-ubuntu-cuda-12.8-x64.tar.gz" "$CACHE/llama-$LLAMA_BUILD-linux.tar.gz"
    dl "https://github.com/ggml-org/llama.cpp/releases/download/$LLAMA_BUILD/cudart-llama-$LLAMA_BUILD-bin-ubuntu-cuda-12.8-x64.tar.gz" "$CACHE/cudart-$LLAMA_BUILD-linux.tar.gz"
    tmp="$(mktemp -d)"
    tar xzf "$CACHE/llama-$LLAMA_BUILD-linux.tar.gz" -C "$tmp"
    tar xzf "$CACHE/cudart-$LLAMA_BUILD-linux.tar.gz" -C "$tmp"
    rm -rf "$RT/$PLATFORM/llama" "$RT/$PLATFORM/cuda"
    mkdir -p "$RT/$PLATFORM/llama" "$RT/$PLATFORM/cuda"
    src="$(find "$tmp" -maxdepth 2 -name llama-server -printf '%h')"
    # only what llama-server needs
    cp -a "$src"/llama-server "$src"/libllama*.so* "$src"/libggml*.so* "$src"/libmtmd.so* "$src"/LICENSE "$RT/$PLATFORM/llama/"
    cp -a "$(find "$tmp" -name 'libcudart.so*' -printf '%h' -quit)"/lib* "$RT/$PLATFORM/cuda/"
    rm -rf "$tmp"
    ;;
  windows-x64)
    dl "https://github.com/ggml-org/llama.cpp/releases/download/$LLAMA_BUILD/llama-$LLAMA_BUILD-bin-win-cuda-12.4-x64.zip" "$CACHE/llama-$LLAMA_BUILD-win.zip"
    dl "https://github.com/ggml-org/llama.cpp/releases/download/$LLAMA_BUILD/cudart-llama-bin-win-cuda-12.4-x64.zip" "$CACHE/cudart-win-12.4.zip"
    rm -rf "$RT/$PLATFORM/llama" "$RT/$PLATFORM/cuda"
    mkdir -p "$RT/$PLATFORM/llama" "$RT/$PLATFORM/cuda"
    unzip -q -o "$CACHE/llama-$LLAMA_BUILD-win.zip" -d "$RT/$PLATFORM/llama"
    unzip -q -o "$CACHE/cudart-win-12.4.zip" -d "$RT/$PLATFORM/cuda"
    ;;
  *) echo "unknown platform $PLATFORM"; exit 1 ;;
esac

echo "== villager brain (LLM)"
dl "$LLM_URL" "$CACHE/$(basename "$LLM_URL")"
ln -f "$CACHE/$(basename "$LLM_URL")" "$RT/models/llm/" 2>/dev/null || cp "$CACHE/$(basename "$LLM_URL")" "$RT/models/llm/"

echo "== fallback voices: Kokoro (CPU, via the voice server)"
dl "$KOKORO_URL" "$CACHE/kokoro-multi-lang-v1_0.tar.bz2"
rm -rf "$RT/models/tts/kokoro" "$RT/models/tts/supertonic"
tmp="$(mktemp -d)"
tar xjf "$CACHE/kokoro-multi-lang-v1_0.tar.bz2" -C "$tmp"
mv "$tmp"/kokoro-multi-lang-v1_0 "$RT/models/tts/kokoro"
rm -rf "$RT/models/tts/kokoro/dict" "$RT/models/tts/kokoro/"*.fst "$RT/models/tts/kokoro/lexicon-zh.txt"   # English only
cp "$META/kokoro.json" "$RT/models/tts/kokoro/twt-engine.json"
# Optional extra engine (OpenRAIL-licensed model, not bundled by default): TWT_WITH_SUPERTONIC=1
if [ "${TWT_WITH_SUPERTONIC:-0}" = "1" ]; then
  dl "$SUPERTONIC_URL" "$CACHE/supertonic-3.tar.bz2"
  tar xjf "$CACHE/supertonic-3.tar.bz2" -C "$tmp"
  mv "$tmp"/sherpa-onnx-supertonic-3-tts-int8-* "$RT/models/tts/supertonic"
  cp "$META/supertonic.json" "$RT/models/tts/supertonic/twt-engine.json"
fi
rm -rf "$tmp"

echo "== expressive voices: Qwen3-TTS (GPU, qwentts.cpp)"
mkdir -p "$RT/models/qwentts"
for f in "${QWENTTS_FILES[@]}"; do
  mkdir -p "$CACHE/qwentts"
  dl "$QWENTTS_REPO/$f" "$CACHE/qwentts/$f"
  ln -f "$CACHE/qwentts/$f" "$RT/models/qwentts/$f" 2>/dev/null || cp "$CACHE/qwentts/$f" "$RT/models/qwentts/$f"
done
if [ "$PLATFORM" = "linux-x64" ]; then
  if [ ! -x "$ROOT/.cache/qwentts-build/linux-x64/tts-server" ]; then
    "$ROOT/scripts/build-qwentts.sh"
  fi
  mkdir -p "$RT/$PLATFORM/qwentts"
  cp --remove-destination "$ROOT/.cache/qwentts-build/linux-x64/tts-server" "$RT/$PLATFORM/qwentts/"
else
  if [ -f "$ROOT/.cache/qwentts-build/windows-x64/tts-server.exe" ]; then
    mkdir -p "$RT/$PLATFORM/qwentts"
    cp "$ROOT/.cache/qwentts-build/windows-x64/"* "$RT/$PLATFORM/qwentts/"
  else
    echo "  (no Windows qwentts build found - build it with .github/workflows/runtime-windows.yml; Kokoro voices still work)"
  fi
fi

du -sh "$RT"/* "$RT"/models/* 2>/dev/null
echo "runtime ready for $PLATFORM in $RT"
