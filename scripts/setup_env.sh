#!/usr/bin/env bash
# 一次性搭建 Android 构建环境（GitHub Codespaces / Ubuntu 22.04）
# 产出：$ANDROID_SDK_ROOT（默认 ~/android-sdk）+ JDK17 + 打印 Gradle 要用的 org.gradle.java.home
set -euo pipefail

SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-11076708_latest.zip"
NDK_VERSION="27.1.12297006"
BUILD_TOOLS="35.0.0"
PLATFORM="35"

echo "==> [1/5] 安装 JDK17 与 unzip"
sudo apt-get update -qq
sudo apt-get install -y -qq openjdk-17-jdk-headless unzip bzip2 cmake ninja-build
JAVA17="$(dirname "$(dirname "$(readlink -f "$(which javac17 || ls /usr/lib/jvm/java-17-openjdk-amd64/bin/javac)")")")"
[ -x "$JAVA17/bin/javac" ] || JAVA17="/usr/lib/jvm/java-17-openjdk-amd64"
"$JAVA17/bin/javac" -version
export JAVA_HOME="$JAVA17"
export PATH="$JAVA_HOME/bin:$PATH"

echo "==> [2/5] 下载 cmdline-tools"
mkdir -p "$SDK_ROOT/cmdline-tools"
if [ ! -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
  curl -fsSL -o /tmp/cmdline-tools.zip \
    "https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP}"
  rm -rf "$SDK_ROOT/cmdline-tools/latest"
  unzip -q -o /tmp/cmdline-tools.zip -d "$SDK_ROOT/cmdline-tools"
  if [ -d "$SDK_ROOT/cmdline-tools/cmdline-tools" ]; then
    mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
  fi
fi
SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
"$SDKMANAGER" --version

echo "==> [3/5] 接受 licenses"
yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses > /dev/null 2>&1 || true

echo "==> [4/5] 安装 platform / build-tools / NDK（约 4GB，请耐心）"
"$SDKMANAGER" --sdk_root="$SDK_ROOT" \
  "platform-tools" "platforms;android-${PLATFORM}" \
  "build-tools;${BUILD_TOOLS}" "ndk;${NDK_VERSION}"

echo "==> [5/5] 完成"
cat <<EOF

------------------------------------------------------------------------
ANDROID_SDK_ROOT : $SDK_ROOT
ANDROID_NDK_HOME : $SDK_ROOT/ndk/$NDK_VERSION
JAVA_HOME (17)   : $JAVA17

请把这两行写进 /workspaces/dou-di-zhu-game/local.properties 与 gradle.properties：
  sdk.dir=$SDK_ROOT
  org.gradle.java.home=$JAVA17
------------------------------------------------------------------------
EOF
