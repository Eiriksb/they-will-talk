#!/usr/bin/env bash
# Builds qwentts.cpp's tts-server (Qwen3-TTS on GGML) with CUDA for Linux x64.
# Uses a user-space CUDA 12.8 toolchain from conda-forge (no sudo, no system CUDA needed).
# Output: .cache/qwentts-build/linux-x64/tts-server  (links only libcudart/libcublas from runtime/linux-x64/cuda)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMMIT="6a3e912"
SRC="$ROOT/.cache/src/qwentts.cpp"
OUT="$ROOT/.cache/qwentts-build/linux-x64"
ENV="${TWT_CUDA_ENV:-$HOME/.local/opt/twt-cuda-build}"
MAMBA="${MAMBA_EXE:-$HOME/.local/bin/micromamba}"
# GPUs: Pascal (61) .. Ada (89, e.g. RTX 4060 Ti) .. Blackwell (120a, e.g. RTX 50xx)
ARCHS="${CUDA_ARCHS:-61-real;75-virtual;86-real;89-real;120a-real}"

if [ ! -x "$ENV/bin/nvcc" ]; then
  echo "== installing CUDA 12.8 build toolchain into $ENV"
  if [ ! -x "$MAMBA" ]; then
    mkdir -p "$(dirname "$MAMBA")"
    curl -fsSL https://micro.mamba.pm/api/micromamba/linux-64/latest | tar -xj -C "$(dirname "$MAMBA")" --strip-components=1 bin/micromamba
  fi
  MAMBA_ROOT_PREFIX="$HOME/.local/opt/mamba" "$MAMBA" create -y -p "$ENV" -c conda-forge -c nvidia/label/cuda-12.8.1 \
    cmake ninja git "gxx_linux-64=14" "gcc_linux-64=14" sysroot_linux-64=2.28 \
    cuda-nvcc=12.8 cuda-cudart-dev=12.8 libcublas-dev cuda-version=12.8
fi

if [ ! -d "$SRC/.git" ]; then
  git clone --recurse-submodules https://github.com/ServeurpersoCom/qwentts.cpp.git "$SRC"
fi
git -C "$SRC" fetch -q origin && git -C "$SRC" checkout -q "$COMMIT" && git -C "$SRC" submodule update -q --init --recursive

cat > "$SRC/.twt-build.sh" <<EOF
set -e
cd "$SRC"
rm -rf build-twt && mkdir build-twt && cd build-twt
cmake .. -G Ninja -DCMAKE_BUILD_TYPE=Release -DGGML_CUDA=ON -DBUILD_SHARED_LIBS=OFF \\
  -DCMAKE_CUDA_COMPILER="$ENV/bin/nvcc" -DCUDAToolkit_ROOT="$ENV" -DCMAKE_CUDA_HOST_COMPILER="\$CXX" \\
  -DGGML_NATIVE=OFF -DGGML_CPU_ALL_VARIANTS=OFF -DCMAKE_CUDA_ARCHITECTURES="$ARCHS" \\
  -DGGML_OPENMP=OFF -DCMAKE_EXE_LINKER_FLAGS="-static-libstdc++ -static-libgcc" -DCMAKE_BUILD_RPATH="" -DCMAKE_SKIP_RPATH=ON
cmake --build . --config Release -j "$(nproc)" --target tts-server
EOF
MAMBA_ROOT_PREFIX="$HOME/.local/opt/mamba" "$MAMBA" run -p "$ENV" bash "$SRC/.twt-build.sh"

mkdir -p "$OUT"
cp "$(find "$SRC/build-twt" -name tts-server -type f -perm -u+x | head -1)" "$OUT/"
cp "$SRC/LICENSE" "$OUT/LICENSE-qwentts.cpp.txt"
# The conda toolchain bakes its own lib dir in as RPATH; the mod provides the bundled CUDA libs via LD_LIBRARY_PATH.
[ -x "$ENV/bin/patchelf" ] || MAMBA_ROOT_PREFIX="$HOME/.local/opt/mamba" "$MAMBA" install -y -q -p "$ENV" -c conda-forge patchelf
"$ENV/bin/patchelf" --remove-rpath "$OUT/tts-server"
echo "built $OUT/tts-server"
