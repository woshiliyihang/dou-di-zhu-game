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
  -DWITH_MKL=OFF -DWITH_DNNL=ON -DWITH_OPENBLAS=OFF -DWITH_ACCELERATE=OFF \
  -DWITH_CUDA=OFF -DWITH_CUDNN=OFF -DWITH_HIP=OFF -DWITH_TENSOR_PARALLEL=OFF \
  -DWITH_RUY=ON \
  -DOPENMP_RUNTIME=COMP \
  -DENABLE_CPU_DISPATCH=ON \
  -DCMAKE_INSTALL_PREFIX="$PREFIX"
# ↑ 这一行是上一版真机崩溃的直接原因，不要再改回去：
#   上一版把 MKL/DNNL/OPENBLAS/ACCELERATE 全关、只开 RUY，结果 sgemm.cc 走到
#   “无后端”分支，运行时抛 std::runtime_error("No SGEMM backend on CPU") 并 abort。
#   RUY 不能当 SGEMM 用：它在 CT2 里只负责 INT8 GEMM（量化算子），FP32 矩阵乘
#   必须有人接，否则加载 INT8 模型后第一句翻译就死。
#   oneDNN（DNNL）是 CT2 在 ARM 上的推荐后端，且支持 AArch64 交叉编译。
#   MKL 没有 Android/arm64 版本，所以只能靠 DNNL，继续保持 OFF。
# CT2 v3.24.0 没有 deps 汇总 target；其 third_party 依赖（含 oneDNN）随 install 的目标依赖图构建。
# 注意：oneDNN 源码在 CT2 仓的 git submodule 里，clone 必须带 --recurse-submodules，
# 否则 WITH_DNNL=ON 会被 CMake 默默退回成无后端（那就又编出一个会 abort 的库）。
cmake --build "$SRC/ctranslate2/build-$ABI" -j"$JOBS" --target install

# 后端自检：没后端就不要往下走，否则白编一轮还得等真机崩了才知道
DNNL_LIB=$(find "$SRC/ctranslate2/build-$ABI" -name 'libdnnl*.a' -o -name 'libonednn*.a' 2>/dev/null | head -1)
CT2_A="$PREFIX/lib/libctranslate2.a"
if [ -z "${DNNL_LIB}" ]; then
  echo "ERROR: 没找到 oneDNN 静态库（libdnnl.a），WITH_DNNL=ON 实际并未生效" >&2; exit 1
fi
if ! command -v llvm-nm >/dev/null 2>&1; then
  LLVM_NM=$(find "$ANDROID_NDK_HOME" -name 'llvm-nm' -type f 2>/dev/null | head -1)
else
  LLVM_NM=llvm-nm
fi
DNNL_REFS=$($LLVM_NM -u "$CT2_A" 2>/dev/null | grep -c dnnl || true)
echo "SGEMM 后端检查：libdnnl=${DNNL_LIB}  libctranslate2.a 引用的 dnnl 符号=${DNNL_REFS}"
# 这里用 -u（未定义符号）而不是 --defined-only：oneDNN 自己是个独立静态库，
# CT2 只“引用”dnnl_*。所以引用数 > 0 才说明 WITH_DNNL=ON 真的生效了。
if [ "$DNNL_REFS" -lt 1 ]; then
  echo "ERROR: libctranslate2.a 里没有引用任何 dnnl 符号，等于无后端，上真机必 abort" >&2; exit 1
fi

# libctranslate2.a 依赖 ruy 与 cpu_features，二者不会随 install 一起落盘，手动拷过去
mkdir -p "$PREFIX/lib"
# oneDNN 同理：它是独立静态库，而 libctranslate2.a 里引用了成百个 dnnl_*。
# 不把它放进 $PREFIX/lib，下一步链 libvtrans-mt.so 时会直接 undefined symbol。
cp -f "$DNNL_LIB" "$PREFIX/lib/" 2>/dev/null || true
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
