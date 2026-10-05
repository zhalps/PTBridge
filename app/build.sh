#!/usr/bin/env bash
# ===========================================================================
# PTBridge 构建脚本 —— 手工工具链，不需要 Gradle / Android Studio
#
#   流程：javac  →  d8  →  aapt package  →  aapt add  →  zipalign  →  apksigner
#
# 用法：
#   1) cp build.env.example build.env     # 填好 SDK / JDK / 签名信息
#   2) bash build.sh
#
# 配置项也可以用环境变量直接给，优先级：环境变量 > build.env
#
# 产物：pageturner.apk
# ===========================================================================
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

# ---------------------------------------------------------------- 读配置
if [ -f ./build.env ]; then
    # shellcheck disable=SC1091
    . ./build.env
fi

die() { echo "✗ $*" >&2; exit 1; }

[ -n "${ANDROID_SDK:-}" ] || die "缺少 ANDROID_SDK —— 复制 build.env.example 为 build.env 再填"
[ -n "${JDK17:-}"       ] || die "缺少 JDK17 —— 指向 JDK 17 根目录"
[ -n "${KEYSTORE:-}"    ] || die "缺少 KEYSTORE —— 签名用 keystore 文件路径"

ANDROID_JAR="${ANDROID_JAR:-$ANDROID_SDK/platforms/android-30/android.jar}"
BUILD_TOOLS="${BUILD_TOOLS:-$ANDROID_SDK/build-tools/34.0.0}"
R8_JAR="${R8_JAR:-$ANDROID_SDK/cmdline-tools/lib/r8.jar}"
KS_ALIAS="${KS_ALIAS:-release}"
KS_PASS="${KS_PASS:-}"
KEY_PASS="${KEY_PASS:-$KS_PASS}"

[ -f "$ANDROID_JAR" ] || die "找不到 android.jar: $ANDROID_JAR"
[ -f "$KEYSTORE"    ] || die "找不到 keystore: $KEYSTORE"

# ------------------------------------------------- Windows 下的 .exe 兼容
pick() {   # pick <无扩展名路径>  ->  回显实际存在的可执行文件
    if [ -x "$1" ]; then echo "$1"
    elif [ -x "$1.exe" ]; then echo "$1.exe"
    else die "找不到可执行文件: $1"; fi
}
JAVA="$(pick "$JDK17/bin/java")"
JAVAC="$(pick "$JDK17/bin/javac")"
AAPT="$(pick "$BUILD_TOOLS/aapt")"
ZIPALIGN="$(pick "$BUILD_TOOLS/zipalign")"

PKG_DIR=src/com/didi/pageturner

echo "== PTBridge =="
echo "   SDK      = $ANDROID_SDK"
echo "   JDK      = $JDK17"
echo "   keystore = $KEYSTORE (alias: $KS_ALIAS)"

# ---------------------------------------------------------------- 清理
rm -rf out classes.dex unsigned.apk aligned.apk pageturner.apk
mkdir -p out

# ---------------------------------------------------------------- 1/6
echo "[1/6] javac"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
    -d out "$PKG_DIR"/*.java

# ---------------------------------------------------------------- 2/6
echo "[2/6] d8 -> classes.dex（必须喂全部 .class，含匿名内部类）"
"$JAVA" -classpath "$R8_JAR" com.android.tools.r8.D8 --release --min-api 26 \
    --output . out/com/didi/pageturner/*.class
ls -l classes.dex

# ---------------------------------------------------------------- 3/6
echo "[3/6] aapt package"
"$AAPT" package -f -M AndroidManifest.xml -I "$ANDROID_JAR" \
    -S res -F unsigned.apk

# ---------------------------------------------------------------- 4/6
echo "[4/6] aapt add classes.dex"
"$AAPT" add unsigned.apk classes.dex

# ---------------------------------------------------------------- 5/6
echo "[5/6] zipalign"
"$ZIPALIGN" -f 4 unsigned.apk aligned.apk

# ---------------------------------------------------------------- 6/6
echo "[6/6] apksigner sign"
"$JAVA" -jar "$BUILD_TOOLS/lib/apksigner.jar" sign \
    --ks "$KEYSTORE" --ks-type PKCS12 \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" --ks-key-alias "$KS_ALIAS" \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out pageturner.apk aligned.apk

"$JAVA" -jar "$BUILD_TOOLS/lib/apksigner.jar" verify pageturner.apk
echo
echo "OK -> $HERE/pageturner.apk"
