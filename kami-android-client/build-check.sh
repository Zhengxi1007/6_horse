#!/usr/bin/env bash
# 用 Eclipse ecj + 本机 android.jar 校验本模块能否编译（本机只有 JRE、没有 javac）。
# 用法：bash build-check.sh        # 默认 android-30
#       API=28 bash build-check.sh # 换 compileSdk
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC_WIN="$(cygpath -w "$HERE/src")"
OUT_WIN="$(cygpath -w "$HERE/build")"
rm -rf "$HERE/build" && mkdir -p "$HERE/build"

SDK_ROOT="${ANDROID_SDK_ROOT:-C:/Users/XIZHENG/rea-tools/android-sdk}"
API="${API:-30}"
JAR_WIN="$(cygpath -w "$SDK_ROOT/platforms/android-$API/android.jar")"
ECJ_WIN="$(cygpath -w "${ECJ:-/tmp/ecj.jar}")"
[ -f "$ECJ_WIN" ] || { echo "找不到 ecj：$ECJ_WIN（先下载 ecj.jar 或设 ECJ= 路径）"; exit 2; }
[ -f "$JAR_WIN" ] || { echo "找不到 android.jar：$JAR_WIN（设 SDK_ROOT= / API= ）"; exit 2; }

# ecj 是 Windows 原生程序，参数一律走 argfile，绕开 MSYS 路径/引号转换
ARGFILE="$(cygpath -w "$HERE/build/args.txt")"
{
  printf -- '-source\n1.8\n-target\n1.8\n-encoding\nUTF-8\n-deprecation\n'
  printf -- '-bootclasspath\n%s\n' "$JAR_WIN"
  printf -- '-classpath\n%s\n' "$SRC_WIN"
  printf -- '-d\n%s\n' "$OUT_WIN"
  # 源文件路径转成 Windows 反斜杠形式（注意：不能用 sed 拼替换串，
  # 因为 Windows 路径里的 \U \D 等会被 GNU sed 当作替换语法）
  while IFS= read -r rel; do
    printf '%s\\%s\n' "$SRC_WIN" "${rel//\//\\}"
  done < <(cd "$HERE/src" && find . -name '*.java' | sed 's|^\./||' | sort)
} > "$ARGFILE"

echo "== compileSdk android-$API =="
java -jar "$ECJ_WIN" "@$ARGFILE"
n=$(find "$HERE/build" -name '*.class' | wc -l)
echo "OK: 编译通过，生成 $n 个 class"
