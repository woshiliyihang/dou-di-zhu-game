#!/usr/bin/env bash
# 交叉编译 libvtrans-mt.so（arm64-v8a）并放进 app/src/main/jniLibs。
# 前置：native/build_android.sh 已产出 /tmp/native-prefix/arm64
set -euo pipefail

NDK="${ANDROID_NDK_HOME:-$HOME/android-sdk/ndk/27.1.12297006}"
PREFIX="${VTRANS_PREFIX:-/tmp/native-prefix/arm64}"
ABI="arm64-v8a"
OUT_DIR="app/src/main/jniLibs/${ABI}"
JOBS="${JOBS:-4}"

[ -f "$NDK/build/cmake/android.toolchain.cmake" ] || { echo "NDK 未找到: $NDK"; exit 1; }
[ -f "$PREFIX/lib/libctranslate2.a" ] || { echo "依赖缺失: $PREFIX（先跑 native/build_android.sh）"; exit 1; }

rm -rf /tmp/native-build/android
cmake -S app/src/main/cpp -B /tmp/native-build/android \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" \
  -DANDROID_PLATFORM=android-28 \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DVTRANS_PREFIX="$PREFIX" \
  -DVTRANS_BUILD_JNI=ON
cmake --build /tmp/native-build/android -j"$JOBS" --target vtrans-mt

mkdir -p "$OUT_DIR"
cp -f /tmp/native-build/android/out/libvtrans-mt.so "$OUT_DIR/"
# 去掉符号表：50MB -> ~13MB，对运行无影响
"${NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" \
  --strip-unneeded "$OUT_DIR/libvtrans-mt.so"

echo "################ 校验 ################"
file "$OUT_DIR/libvtrans-mt.so"
echo "--- LOAD 段对齐（Android 15+ 要求 16384）---"
"${NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -l \
  "$OUT_DIR/libvtrans-mt.so" | grep -E "LOAD|Align" | head -6
echo "--- JNI 导出符号 ---"
"${NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm" -D --defined-only \
  "$OUT_DIR/libvtrans-mt.so" | grep -E "Java_com_example_vtrans" || true
echo "--- 动态依赖 ---"
"${NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -d \
  "$OUT_DIR/libvtrans-mt.so" | grep NEEDED || true
ls -la "$OUT_DIR/libvtrans-mt.so"
