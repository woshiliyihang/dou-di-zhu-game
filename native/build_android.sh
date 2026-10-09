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

# —— 关于 SGEMM 后端：这里已经踩过一次真机崩溃，结论写在代码旁边才不会再踩 ——
# 读 CT2 v3.24.0 的 CMakeLists.txt（第 357-382 行）可以确认：WITH_DNNL / WITH_OPENBLAS /
# WITH_MKL 统统只是 find_path + find_library，找不到就 message(FATAL_ERROR ...)。
# 它们**不会**帮你把后端源码拉下来编译。所以正确顺序是：先自己交叉编译出一个 arm64
# 的 BLAS 静态库放进 $PREFIX/lib，再打开对应开关。
# 而四个后端全关时，src/cpu/primitives.cc 走到 default 分支：
#   throw std::runtime_error("No SGEMM backend on CPU")
# 上一版交付的库就是这样在真机第一次调用翻译时 abort 的（只开 RUY 挡不住：
# RUY 在 CT2 里只做 INT8 GEMM，FP32 的 SGEMM 没人接）。
BACKEND_FLAGS=()
BACKEND_SYM=""
if [ -f "$PREFIX/lib/libdnnl.a" ]; then
  echo "SGEMM 后端：oneDNN（$PREFIX/lib/libdnnl.a）"
  BACKEND_FLAGS=(-DWITH_DNNL=ON -DDNNL_INCLUDE_DIR="$PREFIX/include" -DDNNL_LIBRARY="$PREFIX/lib/libdnnl.a")
  BACKEND_SYM="dnnl_"
elif [ -f "$PREFIX/lib/libopenblas.a" ]; then
  echo "SGEMM 后端：OpenBLAS（$PREFIX/lib/libopenblas.a）"
  BACKEND_FLAGS=(-DWITH_OPENBLAS=ON -DOPENBLAS_INCLUDE_DIR="$PREFIX/include" -DOPENBLAS_LIBRARY="$PREFIX/lib/libopenblas.a")
  BACKEND_SYM="cblas_sgemm"
else
  cat >&2 <<'MISSING_BACKEND'
ERROR: $PREFIX/lib 里没有任何 SGEMM 后端库，就地停下。
再往下只会编出一个“加载能过、第一次翻译就 abort”的 libvtrans-mt.so，那比没有更糟。

先交叉编译一个后端、把产物放进 $PREFIX/lib（二选一），再重跑本脚本：

  # 首选 oneDNN（CT2 在 ARM 上推荐，需要 Linux host）
  git clone --depth 1 https://github.com/oneapi-src/oneDNN.git
  cmake -S oneDNN -B oneDNN/build \
    -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release -DDNNL_BUILD_TESTS=OFF -DDNNL_BUILD_EXAMPLES=OFF \
    -DDNNL_LIBRARY_TYPE=STATIC
  cmake --build oneDNN/build -j8 --target install
  # 结果把 include/dnnl.h 与 libdnnl.a 放到 $PREFIX/include 与 $PREFIX/lib
  # （名字必须是 libdnnl.a，本脚本靠它识别后端）

  # 或者 OpenBLAS（只要能提供 cblas_sgemm）：编完命名成 libopenblas.a 放进 $PREFIX/lib

注意 MKL 与 ACCELERATE 在 Android 上没有对应实现，永远保持 OFF。
MISSING_BACKEND
  exit 1
fi

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
  -DCMAKE_INSTALL_PREFIX="$PREFIX" \
  "${BACKEND_FLAGS[@]}"
# ↑ 基础行把四个后端一律写 OFF，再由 BACKEND_FLAGS 在命令行末尾覆盖成要的那个：
#   cmake 后面的 -D 会覆盖前面的，这个顺序不能颠倒。
# CT2 v3.24.0 的 git submodule 只有 cxxopts/thrust/googletest/cpu_features/spdlog/ruy，
# **里面没有 oneDNN**，所以 --recurse-submodules 拉不出后端，别指望它。
cmake --build "$SRC/ctranslate2/build-$ABI" -j"$JOBS" --target install

# 后端自检：确认开关真的生效了，而不是只看它被打开过。
CT2_A="$PREFIX/lib/libctranslate2.a"
if ! command -v llvm-nm >/dev/null 2>&1; then
  LLVM_NM=$(find "$ANDROID_NDK_HOME" -name 'llvm-nm' -type f 2>/dev/null | head -1)
else
  LLVM_NM=llvm-nm
fi
# 用 -u（未定义符号）而不是 --defined-only：后端是个独立静态库，CT2 只“引用”它，
# 所以引用数 > 0 才说明后端真的被编进去了。
BACKEND_REFS=$($LLVM_NM -u "$CT2_A" 2>/dev/null | grep -c "$BACKEND_SYM" || true)
echo "SGEMM 后端检查：libctranslate2.a 引用 $BACKEND_SYM 的个数 = ${BACKEND_REFS}"
if [ "${BACKEND_REFS:-0}" -lt 1 ]; then
  echo "ERROR: libctranslate2.a 没引用任何 $BACKEND_SYM，等于无后端，上真机必 abort" >&2; exit 1
fi

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
