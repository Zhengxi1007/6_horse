#!/usr/bin/env bash
# 编译 + 运行离线映射校验（纯 JVM，不需要手机/模拟器）。
# 前提：bash build-check.sh 已生成 build/
set -euo pipefail
# 本脚本位于 <模块根>/tests/ 下，故模块根取上一级
HERE="$(cd "$(dirname "$0")/.." && pwd)"
TESTS="$HERE/tests"

SRC_WIN="$(cygpath -w "$HERE/src")"
BUILD_WIN="$(cygpath -w "$HERE/build")"
TB="$(cygpath -w "$HERE/testbuild")"
JAR_WIN="$(cygpath -w "${ANDROID_SDK_ROOT:-C:/Users/XIZHENG/rea-tools/android-sdk}/platforms/android-${API:-30}/android.jar")"
ECJ_WIN="$(cygpath -w "${ECJ:-/tmp/ecj.jar}")"

rm -rf "$HERE/testbuild" && mkdir -p "$HERE/testbuild"

java -jar "$ECJ_WIN" \
  -source 1.8 -target 1.8 \
  -encoding UTF-8 \
  -bootclasspath "$JAR_WIN" \
  -classpath "$SRC_WIN;$BUILD_WIN" \
  -d "$TB" \
  "$(cygpath -w "$TESTS/MappingCheck.java")"

echo "== RUN =="
# -Dfile.encoding=UTF-8 保证中文断言在控制台正常显示（Windows 默认 GBK 会乱码）
java -Dfile.encoding=UTF-8 -cp "$BUILD_WIN;$TB" MappingCheck
