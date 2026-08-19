#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="/home/wangjiafeng/构建apk依赖包/jdk"
export ANDROID_HOME="/home/wangjiafeng/构建apk依赖包/android-sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

exec "/home/wangjiafeng/构建apk依赖包/gradle/gradle-8.10.2/bin/gradle" \
    --project-dir "$PROJECT_DIR" \
    assembleDebug
