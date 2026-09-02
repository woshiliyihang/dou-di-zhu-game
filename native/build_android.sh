#!/usr/bin/env bash
# 交叉编译 sentencepiece(静态) + CTranslate2(静态) → arm64-v8a
# 产物：/tmp/native-prefix/arm64/{lib,include}
set -euo pipefail

NDK="${ANDROID_NDK_HOME:-$HOME/android-sdk/ndk/27.1.12297006}"
ABI="arm64-v8a"
MINSDK="28"
PREFIX="/tmp/native-prefix/arm64"
SRC="/tmp/src"
JOBS="${JOBS:-4}"

[ -f "$NDK/build/cmake/android.toolchain.cmake" ] || { echo "NDK 未找到: $NDK"; exit 1; }

COMMON_TOOLCHAIN=(
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
  -DANDROID_ABI="$ABI"
  -DANDROID_PLATFORM="android-$MINSDK"
  -DANDROID_STL=c++_static
  -DCMAKE_BUILD_TYPE=Release
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON
)

echo "################ 1/2 sentencepiece ($ABI) ################"
mkdir -p "$SRC/sentencepiece/build-$ABI" "$PREFIX"
cmake -S "$SRC/sentencepiece" -B "$SRC/sentencepiece/build-$ABI" \
  "${COMMON_TOOLCHAIN[@]}" \
  -DSPM_ENABLE_SHARED=OFF \
  -DSPM_BUILD_TEST=OFF \
  -DSPM_ENABLE_TENSORFLOW_SHARED=OFF \
  -DCMAKE_INSTALL_PREFIX="$PREFIX"
# 只编静态库：spm_* 命令行工具链接时需要 -llog，我们不需要，跳过以免整机构建失败
cmake --build "$SRC/sentencepiece/build-$ABI" -j"$JOBS" --target sentencepiece-static
mkdir -p "$PREFIX/lib" "$PREFIX/include"
cp -f "$SRC/sentencepiece/build-$ABI/src/libsentencepiece.a" "$PREFIX/lib/"
cp -f "$SRC/sentencepiece/src/sentencepiece_processor.h" \
      "$SRC/sentencepiece/src/sentencepiece_trainer.h" "$PREFIX/include/"
cp -f "$SRC/sentencepiece/src/builtin_pb/sentencepiece.pb.h" \
      "$SRC/sentencepiece/src/builtin_pb/sentencepiece_model.pb.h" "$PREFIX/include/"
echo "sentencepiece 已安装到 $PREFIX"

echo "################ 2/2 CTranslate2 ($ABI) ################"
mkdir -p "$SRC/ctranslate2/build-$ABI"
cmake -S "$SRC/ctranslate2" -B "$SRC/ctranslate2/build-$ABI" \
  "${COMMON_TOOLCHAIN[@]}" \
  -DBUILD_SHARED_LIBS=OFF \
  -DBUILD_CLI=OFF \
  -DBUILD_TESTS=OFF \
  -DWITH_MKL=OFF -DWITH_DNNL=OFF -DWITH_OPENBLAS=OFF -DWITH_ACCELERATE=OFF \
  -DWITH_CUDA=OFF -DWITH_CUDNN=OFF -DWITH_HIP=OFF -DWITH_TENSOR_PARALLEL=OFF \
  -DWITH_RUY=ON \
  -DOPENMP_RUNTIME=COMP \
  -DENABLE_CPU_DISPATCH=ON \
  -DCMAKE_INSTALL_PREFIX="$PREFIX"
cmake --build "$SRC/ctranslate2/build-$ABI" -j"$JOBS" --target install

# libctranslate2.a 依赖 ruy 与 cpu_features，二者不会随 install 一起落盘，手动拷过去
mkdir -p "$PREFIX/lib"
cp -f "$SRC"/ctranslate2/build-"$ABI"/third_party/ruy/ruy/libruy_*.a "$PREFIX/lib/" 2>/dev/null || true
cp -f "$SRC"/ctranslate2/build-"$ABI"/third_party/ruy/ruy/profiler/instrumentation.a \
      "$PREFIX/lib/libruy_profiler.a" 2>/dev/null || true
cp -f "$SRC"/ctranslate2/build-"$ABI"/third_party/cpu_features/libcpu_features.a "$PREFIX/lib/" 2>/dev/null || true
cp -f "$SRC"/ctranslate2/build-"$ABI"/third_party/ruy/third_party/cpuinfo/libcpuinfo.a \
      "$PREFIX/lib/" 2>/dev/null || true
cp -f "$SRC"/ctranslate2/build-"$ABI"/third_party/ruy/third_party/cpuinfo/deps/clog/libclog.a \
      "$PREFIX/lib/libclog.a" 2>/dev/null || true

echo "################ 产物 ################"
find "$PREFIX/lib" -name '*.a' -printf '%10s  %p\n' | sort -k2
file "$PREFIX/lib/libctranslate2.a" 2>/dev/null | cut -c1-160
echo "PREFIX=$PREFIX"
