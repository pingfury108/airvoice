#!/usr/bin/env bash
# 在远端 Linux 服务器上安装 Android SDK（cmdline-tools + platform-34 + build-tools + adb）
# 用法: rdev bash scripts/setup-android-sdk.sh
set -euo pipefail

SDK_ROOT="$HOME/Android/Sdk"
CMDLINE_ZIP="commandlinetools-linux-11076708_latest.zip"

if [ ! -d "$SDK_ROOT/cmdline-tools/latest" ]; then
    echo "==> 下载 Android cmdline-tools"
    mkdir -p "$SDK_ROOT/cmdline-tools"
    curl -fL --retry 3 -o /tmp/$CMDLINE_ZIP "https://dl.google.com/android/repository/$CMDLINE_ZIP"
    unzip -q -o /tmp/$CMDLINE_ZIP -d "$SDK_ROOT/cmdline-tools"
    mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
    rm /tmp/$CMDLINE_ZIP
fi

export ANDROID_HOME="$SDK_ROOT"
export PATH="$SDK_ROOT/cmdline-tools/latest/bin:$SDK_ROOT/platform-tools:$PATH"

echo "==> 安装 platform-34 / build-tools / platform-tools"
yes | sdkmanager --licenses >/dev/null || true
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

# 写入项目 local.properties（gitignore，不随 rdev 同步）
echo "sdk.dir=$SDK_ROOT" > local.properties

echo "==> 完成"
adb version
java -version 2>&1 | head -1 || echo "注意: 未找到 java，请确认 mise 已激活（rdev 会自动 mise x）"
