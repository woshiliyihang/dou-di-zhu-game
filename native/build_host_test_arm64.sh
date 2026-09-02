#!/usr/bin/env bash
# 编一个 arm64 版的 host_test，用 qemu-aarch64-static 在本机跑起来。
# 目的：验证真正要进 APK 的 arm64 产物（libctranslate2/ruy/NEON 内核）能正确出译文，
# 而不只是"编译过了"。
set -euo pipefail

NDK="${ANDROID_NDK_HOME:-$HOME/android-sdk/ndk/27.1.12297006}"
PREFIX="/tmp/native-prefix/arm64"
JOBS="${JOBS:-4}"
SYSROOT="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"

[ -f "$NDK/build/cmake/android.toolchain.cmake" ] || { echo "NDK 未找到: $NDK"; exit 1; }
[ -f "$PREFIX/lib/libctranslate2.a" ] || { echo "依赖缺失: $PREFIX（先跑 native/build_android.sh）"; exit 1; }

rm -rf /tmp/native-build/arm64-test
cmake -S app/src/main/cpp -B /tmp/native-build/arm64-test \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-28 \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DVTRANS_PREFIX="$PREFIX" \
  -DVTRANS_BUILD_JNI=OFF \
  -DVTRANS_BUILD_HOST_TEST=ON \
  -DCMAKE_EXE_LINKER_FLAGS="-static -static-openmp"
cmake --build /tmp/native-build/arm64-test -j"$JOBS" --target host_test

echo "################ 用 qemu 跑 arm64 版 host_test ################"
echo "qemu-aarch64-static -L $SYSROOT /tmp/native-build/arm64-test/host_test <模型目录>"
