#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main"
OUT="$ROOT/artifacts/build"
APK_DIR="$ROOT/artifacts/apk"
APK="$APK_DIR/wordlite-debug.apk"
ANDROID_JAR="$ROOT/tools/android-35.jar"
KEYSTORE="$ROOT/tools/debug.keystore"
AAPT2="${AAPT2:-$ROOT/tools/aapt2-host.sh}"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$APK_DIR"

if [ ! -x "$AAPT2" ]; then
  echo "aapt2 not found: $AAPT2" >&2
  exit 1
fi

echo "== resources =="
"$AAPT2" compile --dir "$SRC/res" -o "$OUT/res.zip"
"$AAPT2" link -o "$OUT/wordlite.unsigned.apk" --manifest "$SRC/AndroidManifest.xml" \
  -I "$ANDROID_JAR" --java "$OUT/gen" "$OUT/res.zip" \
  -A "$SRC/assets" -0 ttc -0 ttf -0 otf

echo "== javac =="
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.list"
javac -source 8 -target 8 -encoding UTF-8 -classpath "$ANDROID_JAR" \
  -d "$OUT/classes" @"$OUT/sources.list"

echo "== dex =="
find "$OUT/classes" -name '*.class' -print0 | xargs -0 java -cp "$ROOT/tools/d8.jar" \
  com.android.tools.r8.D8 --min-api 23 --lib "$ANDROID_JAR" --output "$OUT/dex"
cp "$OUT/dex/classes.dex" "$OUT/classes.dex"
cd "$OUT"
python3 - <<'PY'
import zipfile
apk = "wordlite.unsigned.apk"
with zipfile.ZipFile(apk, "a") as z:
    z.write("classes.dex", "classes.dex")
PY

if ! keytool -list -keystore "$KEYSTORE" -storepass android -alias wordlite >/dev/null 2>&1; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias wordlite -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=WordLite Debug,O=Rikkahub,C=CN" >/dev/null 2>&1
fi

echo "== align + sign =="
zipalign -f 4 "$OUT/wordlite.unsigned.apk" "$OUT/wordlite.aligned.apk"
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias wordlite --out "$APK" "$OUT/wordlite.aligned.apk"
apksigner verify --verbose "$APK"
echo "APK: $APK"
