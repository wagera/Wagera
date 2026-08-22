#!/usr/bin/env bash
# AstraUpscale - Gradle'siz APK derleme betigi.
# Android SDK build-tools araclarini dogrudan kullanir: aapt2 -> javac -> d8 -> zipalign -> apksigner.
#
# Gerekli ortam degiskenleri:
#   ANDROID_SDK_ROOT   Android SDK dizini (varsayilan: /opt/android-sdk)
# Istege bagli:
#   BUILD_TOOLS        build-tools surumu (varsayilan: 34.0.0)
#   PLATFORM           derleme hedefi (varsayilan: android-34)
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
BUILD_TOOLS="${BUILD_TOOLS:-35.0.0}"  # 34.0.0 icindeki d8, JDK 21 ile enum siniflarinda cokuyor
PLATFORM="${PLATFORM:-android-34}"
BT="$SDK/build-tools/$BUILD_TOOLS"
ANDROID_JAR="$SDK/platforms/$PLATFORM/android.jar"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="$ROOT/app/src/main"
OUT="${OUT_DIR:-$ROOT/build}"
KEYSTORE="${KEYSTORE:-$OUT/astraupscale.keystore}"
APK_NAME="${APK_NAME:-AstraUpscale}"
KEY_ALIAS="${KEY_ALIAS:-astraupscale}"
KEY_PASS="${KEY_PASS:-astraupscale}"

for tool in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner"; do
    [ -x "$tool" ] || { echo "bulunamadi: $tool"; exit 1; }
done
[ -f "$ANDROID_JAR" ] || { echo "bulunamadi: $ANDROID_JAR"; exit 1; }

echo "==> temizlik"
rm -rf "$OUT/res" "$OUT/classes" "$OUT/dex" "$OUT/assets" "$OUT/base.apk" "$OUT/unsigned.apk" "$OUT/aligned.apk"
mkdir -p "$OUT/res" "$OUT/classes" "$OUT/dex"

# MODELS ile ince surum uretilebilir (orn. MODELS="realesr-animevideov3-x4 realcugan-up2x-no-denoise").
# Uygulama paketlenmemis modelleri listede gostermez.
ASSET_DIR="$APP/assets"
if [ -n "${MODELS:-}" ]; then
    ASSET_DIR="$OUT/assets"
    rm -rf "$ASSET_DIR"; mkdir -p "$ASSET_DIR/models"
    for m in $MODELS; do
        cp "$APP/assets/models/$m.param" "$APP/assets/models/$m.bin" "$ASSET_DIR/models/"
    done
    echo "==> ince surum modelleri: $MODELS"
fi

echo "==> kaynaklar derleniyor (aapt2 compile)"
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res/resources.zip"

echo "==> kaynaklar baglaniyor (aapt2 link)"
"$BT/aapt2" link \
    -I "$ANDROID_JAR" \
    --manifest "$APP/AndroidManifest.xml" \
    -A "$ASSET_DIR" \
    --java "$OUT/classes" \
    --min-sdk-version 24 \
    --target-sdk-version 34 \
    --version-code 6 \
    --version-name 5.0 \
    -0 .param \
    -o "$OUT/base.apk" \
    "$OUT/res/resources.zip"

echo "==> Java derleniyor"
find "$APP/java" "$OUT/classes" -name '*.java' > "$OUT/sources.txt"
# JDK 21 ile --release ve -bootclasspath birlikte kullanilamaz; android.jar
# siniflar yolunda verilir, java.* API'leri --release 11 ile sinirlanir.
javac -nowarn -encoding UTF-8 --release 11 \
    -classpath "$ANDROID_JAR" \
    -d "$OUT/classes" \
    @"$OUT/sources.txt"

echo "==> dex uretiliyor (d8)"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --release --min-api 24 --lib "$ANDROID_JAR" --output "$OUT/dex" @"$OUT/classes.txt"

echo "==> APK paketleniyor"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
( cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" classes*.dex )
# Yerel kitapliklar sikistirilmadan eklenir (extractNativeLibs=false ile hizalanabilmesi icin)
if [ -d "$APP/jniLibs" ]; then
    TMPLIB="$OUT/libtree"
    rm -rf "$TMPLIB"; mkdir -p "$TMPLIB"
    cp -r "$APP/jniLibs" "$TMPLIB/lib"
    # ABIS ile tek mimarili paket uretilebilir (orn. ABIS="arm64-v8a")
    if [ -n "${ABIS:-}" ]; then
        for d in "$TMPLIB"/lib/*/; do
            abi="$(basename "$d")"
            case " $ABIS " in *" $abi "*) ;; *) rm -rf "$d" ;; esac
        done
    fi
    ( cd "$TMPLIB" && find lib -name '*.so' | sort | zip -q -X -0 "$OUT/unsigned.apk" -@ )
fi

echo "==> hizalama (zipalign)"
"$BT/zipalign" -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
    echo "==> imza anahtari uretiliyor"
    keytool -genkeypair -v -keystore "$KEYSTORE" -alias "$KEY_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$KEY_PASS" -keypass "$KEY_PASS" \
        -dname "CN=AstraUpscale, OU=AstraUpscale, O=AstraUpscale, L=, S=, C=TR" >/dev/null 2>&1
fi

echo "==> imzalaniyor (apksigner)"
"$BT/apksigner" sign \
    --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$KEY_PASS" --key-pass "pass:$KEY_PASS" \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$OUT/$APK_NAME.apk" "$OUT/aligned.apk"

"$BT/apksigner" verify --print-certs "$OUT/$APK_NAME.apk" > /dev/null
echo "==> hazir: $OUT/$APK_NAME.apk ($(du -h "$OUT/$APK_NAME.apk" | cut -f1))"
