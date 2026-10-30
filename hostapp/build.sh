#!/usr/bin/env bash
# 从零构建可安装 APK：aapt2 → ecj → d8 → zipalign → apksigner
# 不需要 Gradle / javac。用法：bash build.sh [输出名]
set -euo pipefail

APP="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-C:/Users/XIZHENG/rea-tools/android-sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-30/android.jar"
ECJ="${ECJ_JAR:-/tmp/ecj.jar}"
OUT_NAME="${1:-QSS_NoKey_signed2.apk}"
KEYSTORE="$APP/keys/host.jks"
KS_PASS="${KS_PASS:-qsshost2026}"
KS_ALIAS="${KS_ALIAS:-host}"

W() { cygpath -w "$1"; }
# 把文件列表转成 Windows 路径、空格分隔
wlist() { while IFS= read -r f; do [ -n "$f" ] && printf '%s ' "$(cygpath -w "$f")"; done; }

rm -rf "$APP/out" "$APP/gen" "$APP/obj" "$APP/dex"
mkdir -p "$APP/out" "$APP/gen" "$APP/obj" "$APP/dex" "$APP/keys"

echo "== 1/6 aapt2 compile 资源 =="
"$BT/aapt2.exe" compile --dir "$APP/res" -o "$APP/out/res.zip"

echo "== 2/6 aapt2 link（生成 R.java + 基础 APK） =="
"$BT/aapt2.exe" link \
  -I "$(W "$PLATFORM")" \
  --manifest "$APP/AndroidManifest.xml" \
  --java "$APP/gen" \
  --min-sdk-version 23 --target-sdk-version 30 \
  -o "$APP/out/base.apk" \
  "$APP/out/res.zip"

echo "== 3/6 ecj 编译 Java（强制 -encoding UTF-8） =="
SRCS=$(find "$APP/src" "$APP/gen" -name '*.java' | wlist)
# shellcheck disable=SC2086
java -jar "$(W "$ECJ")" \
  -source 1.8 -target 1.8 -encoding UTF-8 \
  -bootclasspath "$(W "$PLATFORM")" \
  -d "$(W "$APP/obj")" \
  $SRCS

echo "== 4/6 d8 转 dex =="
CLASSES=$(find "$APP/obj" -name '*.class' | wlist)
# shellcheck disable=SC2086
"$BT/d8.bat" --min-api 23 --lib "$(W "$PLATFORM")" --output "$(W "$APP/dex")" $CLASSES

echo "== 5/6 合入 dex + zipalign =="
cp "$APP/out/base.apk" "$APP/out/unaligned.apk"
python - "$APP/out/unaligned.apk" "$APP/dex" <<'PY'
import sys, zipfile, os
apk, dexdir = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    for n in sorted(os.listdir(dexdir)):
        if n.endswith('.dex'):
            z.write(os.path.join(dexdir, n), n)
            print("   + " + n)
PY
"$BT/zipalign.exe" -p -f 4 "$APP/out/unaligned.apk" "$APP/out/aligned.apk"

echo "== 6/6 签名 =="
if [ ! -f "$KEYSTORE" ]; then
  echo "   首次运行：生成 keystore"
  KT="$(dirname "$(command -v java)")/keytool"
  "$KT" -genkeypair -v \
    -keystore "$(W "$KEYSTORE")" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=QSS Host, OU=Dev, O=QSS, L=NA, ST=NA, C=CN" >/dev/null 2>&1
fi
"$BT/apksigner.bat" sign \
  --ks "$(W "$KEYSTORE")" --ks-pass "pass:$KS_PASS" --ks-key-alias "$KS_ALIAS" \
  --key-pass "pass:$KS_PASS" --min-sdk-version 23 \
  --out "$APP/$OUT_NAME" "$APP/out/aligned.apk"

echo "== 验签 =="
"$BT/apksigner.bat" verify --print-certs "$APP/$OUT_NAME" 2>&1 \
  | grep -iE "Verifies|Verified using|Signer #1 certificate DN|SHA-256 digest" | head -6
echo
echo "✅ 交付：$APP/$OUT_NAME"
ls -la "$APP/$OUT_NAME"
