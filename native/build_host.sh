#!/usr/bin/env bash
# 编译 x86 host 版依赖 + host_test，用于验证 mt_engine.cpp 的逻辑正确性。
# 产物：/tmp/native-prefix/host、/tmp/native-build/host/host_test
set -euo pipefail

PREFIX="/tmp/native-prefix/host"
SRC="/tmp/src"
JOBS="${JOBS:-4}"

COMMON=(
  -DCMAKE_BUILD_TYPE=Release
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON
  -DCMAKE_INSTALL_PREFIX="$PREFIX"
)

echo "################ 1/3 sentencepiece (host) ################"
mkdir -p "$SRC/sentencepiece/build-host" "$PREFIX"
cmake -S "$SRC/sentencepiece" -B "$SRC/sentencepiece/build-host" \
  "${COMMON[@]}" \
  -DSPM_ENABLE_SHARED=OFF -DSPM_BUILD_TEST=OFF
cmake --build "$SRC/sentencepiece/build-host" -j"$JOBS" --target sentencepiece-static
mkdir -p "$PREFIX/lib" "$PREFIX/include"
cp -f "$SRC/sentencepiece/build-host/src/libsentencepiece.a" "$PREFIX/lib/"
cp -f "$SRC/sentencepiece/src/sentencepiece_processor.h" \
      "$SRC/sentencepiece/src/sentencepiece_trainer.h" "$PREFIX/include/"
cp -f "$SRC/sentencepiece/src/builtin_pb/sentencepiece.pb.h" \
      "$SRC/sentencepiece/src/builtin_pb/sentencepiece_model.pb.h" "$PREFIX/include/"

echo "################ 2/3 CTranslate2 (host) ################"
mkdir -p "$SRC/ctranslate2/build-host"
cmake -S "$SRC/ctranslate2" -B "$SRC/ctranslate2/build-host" \
  "${COMMON[@]}" \
  -DBUILD_SHARED_LIBS=OFF -DBUILD_CLI=OFF -DBUILD_TESTS=OFF \
  -DWITH_MKL=OFF -DWITH_DNNL=OFF -DWITH_OPENBLAS=OFF -DWITH_ACCELERATE=OFF \
  -DWITH_CUDA=OFF -DWITH_CUDNN=OFF -DWITH_HIP=OFF -DWITH_TENSOR_PARALLEL=OFF \
  -DWITH_RUY=ON -DOPENMP_RUNTIME=COMP -DENABLE_CPU_DISPATCH=ON
cmake --build "$SRC/ctranslate2/build-host" -j"$JOBS" --target install

# libctranslate2.a 依赖 ruy 与 cpu_features，二者不会随 install 一起落盘，手动拷过去
mkdir -p "$PREFIX/lib"
cp -f "$SRC"/ctranslate2/build-host/third_party/ruy/ruy/libruy_*.a "$PREFIX/lib/" 2>/dev/null || true
cp -f "$SRC"/ctranslate2/build-host/third_party/ruy/ruy/profiler/instrumentation.a \
      "$PREFIX/lib/libruy_profiler.a" 2>/dev/null || true
cp -f "$SRC"/ctranslate2/build-host/third_party/cpu_features/libcpu_features.a "$PREFIX/lib/"
cp -f "$SRC"/ctranslate2/build-host/third_party/ruy/third_party/cpuinfo/libcpuinfo.a \
      "$PREFIX/lib/" 2>/dev/null || true
cp -f "$SRC"/ctranslate2/build-host/third_party/ruy/third_party/cpuinfo/deps/clog/libclog.a \
      "$PREFIX/lib/libclog.a" 2>/dev/null || true

echo "################ 3/3 host_test ################"
mkdir -p /tmp/native-build/host
cmake -S app/src/main/cpp -B /tmp/native-build/host \
  -DCMAKE_BUILD_TYPE=Release \
  -DVTRANS_PREFIX="$PREFIX" \
  -DVTRANS_BUILD_JNI=OFF \
  -DVTRANS_BUILD_HOST_TEST=ON
cmake --build /tmp/native-build/host -j"$JOBS" --target host_test

echo "################ 完成 ################"
ls -la /tmp/native-build/host/host_test
echo "用法: /tmp/native-build/host/host_test /tmp/models/nllb-600m-ct2-int8 4 1"
