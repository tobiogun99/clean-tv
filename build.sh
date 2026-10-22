#!/usr/bin/env bash
# Builds build/cleantv.apk with nothing but the Android SDK and a JDK (17+). No Gradle needed.
# Prefer Android Studio? See build.gradle. Missing tools? Run ./setup.sh first.
set -euo pipefail
cd "$(dirname "$0")"
source ./config.sh

# --- find the tools -------------------------------------------------------
if [ -z "${JAVA_HOME:-}" ]; then
  for j in /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17 /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-17-openjdk; do
    [ -x "$j/bin/javac" ] && export JAVA_HOME="$j" && break
  done
fi
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"
command -v javac >/dev/null || { echo "No JDK found. Run ./setup.sh or set JAVA_HOME."; exit 1; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for s in "$HOME/Library/Android/sdk" /opt/homebrew/share/android-commandlinetools /usr/local/share/android-commandlinetools "$HOME/Android/Sdk"; do
    [ -d "$s/platforms" ] && SDK="$s" && break
  done
fi
PLATFORM="${PLATFORM:-android-35}"
JAR="$SDK/platforms/$PLATFORM/android.jar"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" 2>/dev/null | sort -V | tail -1)"
[ -f "$JAR" ] && [ -x "$BT/aapt2" ] || { echo "Android SDK ($PLATFORM + build-tools) not found. Run ./setup.sh or set ANDROID_HOME."; exit 1; }

# --- build ----------------------------------------------------------------
OUT=build
rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/assets"
cp -R assets/. "$OUT/assets/"
if ls fonts-private/*.[ot]tf >/dev/null 2>&1; then       # your own fonts, never committed
  mkdir -p "$OUT/assets/fonts-private" && cp fonts-private/*.[ot]tf "$OUT/assets/fonts-private/"
fi

"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml --java "$OUT/gen" \
  --rename-manifest-package "$APP_ID" \
  --min-sdk-version 26 --target-sdk-version 35 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -A "$OUT/assets" -0 ttf -0 otf \
  -o "$OUT/unsigned.apk" "$OUT/res.zip"

javac -source 8 -target 8 -bootclasspath "$JAR" -classpath "$BT/core-lambda-stubs.jar" \
  -Xlint:-options -encoding UTF-8 -d "$OUT/classes" $(find src "$OUT/gen" -name '*.java')
"$BT/d8" --release --min-api 26 --lib "$JAR" --output "$OUT" $(find "$OUT/classes" -name '*.class')
(cd "$OUT" && zip -qj unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# Your own signing key, made on the first build. Keep it: updates must be signed with the same key.
KS="${KEYSTORE:-keystore.jks}"
KS_PASS="${KEYSTORE_PASS:-android}"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -alias cleantv -storepass "$KS_PASS" -keypass "$KS_PASS" \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=$APP_NAME" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --out "$OUT/cleantv.apk" "$OUT/aligned.apk"

echo "Built $OUT/cleantv.apk ($APP_ID $VERSION_NAME, $(du -h "$OUT/cleantv.apk" | cut -f1))"
