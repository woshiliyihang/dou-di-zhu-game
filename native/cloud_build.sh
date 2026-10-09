#!/usr/bin/env bash
# 在云端（Codespaces / GitHub runner / 任意 Ubuntu-Debian）一键交叉编译 libvtrans-mt.so。
# 本机 Windows 干不了的不是编译器，是源码下载：GitHub 的 archive 跳转不支持 Range，
# 而代理只放行约 2 秒的单连接，分片续传用不上。云端没这两个限制。
#
# 用法（在仓库根目录）：  bash native/cloud_build.sh
# 跑完按最后一行的提示把 .so 推到 native-build 分支即可。
set -euo pipefail

# 版本钉死，别自作主张升级：
#   mt_engine.cpp 用的是 Translator(dir, Device, ComputeType, device_indices,
#   max_queued_batches, ReplicaPoolConfig) 这个六参签名 —— ReplicaPoolConfig 和
#   max_queued_batches 在 CTranslate2 4.0 里被整个删掉了，升 4.x 会直接编译失败。
CT2_TAG="${CT2_TAG:-v3.24.0}"
SP_TAG="${SP_TAG:-v0.2.0}"
NDK_VER="${NDK_VER:-r26d}"
SRC="${SRC:-/tmp/src}"
PREFIX="${PREFIX:-/tmp/native-prefix/arm64}"
JOBS="${JOBS:-8}"

cd "$(dirname "$0")/.."   # 仓库根

echo "==> 1/5 基础工具"
if ! command -v cmake >/dev/null 2>&1; then
  sudo apt-get update -qq
  sudo apt-get install -y -qq cmake ninja-build git build-essential unzip curl file
fi
cmake --version | head -1
ninja --version

echo "==> 2/5 NDK $NDK_VER"
if [ ! -d "/opt/android-ndk-$NDK_VER" ]; then
  curl -sSL -o /tmp/ndk.zip "https://dl.google.com/android/repository/android-ndk-$NDK_VER-linux.zip"
  sudo mkdir -p /opt
  sudo unzip -q -o /tmp/ndk.zip -d /opt
fi
export ANDROID_NDK_HOME="/opt/android-ndk-$NDK_VER"
[ -f "$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" ] || {
  echo "NDK 不完整: $ANDROID_NDK_HOME"; exit 1; }

echo "==> 3/5 源码（钉版本）"
mkdir -p "$SRC"
if [ ! -d "$SRC/ctranslate2/.git" ]; then
  git clone --depth 1 --branch "$CT2_TAG" --recurse-submodules \
    https://github.com/OpenNMT/CTranslate2.git "$SRC/ctranslate2"
fi
if [ ! -d "$SRC/sentencepiece/.git" ]; then
  git clone --depth 1 --branch "$SP_TAG" \
    https://github.com/google/sentencepiece.git "$SRC/sentencepiece"
fi
git -C "$SRC/ctranslate2" describe --tags --always
git -C "$SRC/sentencepiece" describe --tags --always

echo "==> 4/5 编静态依赖（sentencepiece + CTranslate2，arm64-v8a）"
JOBS="$JOBS" bash native/build_android.sh

echo "==> 5/5 编 libvtrans-mt.so 并落进 jniLibs"
JOBS="$JOBS" VTRANS_PREFIX="$PREFIX" bash native/build_vtrans_android.sh

SO="app/src/main/jniLibs/arm64-v8a/libvtrans-mt.so"
BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"

echo "################ 交付前自检（请把这几段输出原样贴回来） ################"
echo "--- 文件 ---"
ls -l "$SO"
echo "--- 动态依赖：只允许 liblog/libandroid/libm/libdl/libc，出现别的说明静态没吃干净 ---"
"$BIN/llvm-readelf" -d "$SO" | grep -E "NEEDED|SONAME" || true
echo "--- LOAD 段对齐：Android 15+ 要求 16384（0x4000） ---"
"$BIN/llvm-readelf" -l "$SO" | grep -E "LOAD|Align" | head -6
echo "--- JNI 符号，应该有 7 个 Java_com_example_vtrans_MtEngine_* ---"
"$BIN/llvm-nm" -D --defined-only "$SO" | grep "Java_com_example_vtrans" || true
JNI_N=$("$BIN/llvm-nm" -D --defined-only "$SO" | grep -c "Java_com_example_vtrans" || true)
CT2_N=$("$BIN/llvm-nm" -D --defined-only "$SO" | grep -c ctranslate2 || true)
echo "jni symbols = ${JNI_N:-0}"
echo "--- CT2 确实链进来了吗 ---"
echo "ctranslate2 symbols = ${CT2_N:-0}"

cat <<'EOF'

自检都过了就推回来（我会在本地 fetch 这个分支取 .so，不用你管 APK）：
  git checkout -B native-build
  git add -f app/src/main/jniLibs/arm64-v8a/libvtrans-mt.so
  git commit -m "native: rebuild libvtrans-mt.so (CT2 3.24 + OPUS-MT pair model)"
  git push -f origin native-build
EOF
