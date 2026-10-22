#!/usr/bin/env bash
# One-time toolchain setup for ./build.sh (and ./tv.sh): a JDK 17, the Android SDK command-line
# tools with platforms;android-35 + build-tools;35.0.0, and adb.
#
#   macOS          Homebrew: openjdk@17, android-commandlinetools, android-platform-tools
#   Debian/Ubuntu  apt: openjdk-17-jdk-headless, adb; Google's command-line tools into ~/Android/Sdk
#
# Safe to run again: anything already installed is skipped. Pass -y to skip the questions
# (that also accepts the Android SDK licences for you).
set -euo pipefail

PLATFORM="platforms;android-35"
BUILD_TOOLS="build-tools;35.0.0"
# Google's command-line tools for Linux (from dl.google.com/android/repository/repository2-3.xml)
CLT_ZIP="commandlinetools-linux-16111833_latest.zip"
CLT_SHA1="e025545c62a8e64c7559119566a569fb1dec5f60"

YES=0
for a in "$@"; do
  case "$a" in
    -y|--yes) YES=1 ;;
    -h|--help) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $a (try --help)"; exit 1 ;;
  esac
done

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
skip() { printf '  - %s (already there)\n' "$*"; }
confirm() {
  [ "$YES" = 1 ] && return 0
  if [ ! -t 0 ]; then echo "Not running interactively; re-run with -y to proceed."; exit 1; fi
  read -r -p "Continue? [Y/n] " r
  case "$r" in ""|y|Y|yes|YES) ;; *) echo "Nothing changed."; exit 1 ;; esac
}

# Installs the SDK packages and makes sure the licences are accepted.
sdk_packages() {   # $1 = sdkmanager binary, $2 = SDK root
  local sm="$1" root="$2" need=()
  [ -f "$root/platforms/android-35/android.jar" ] && skip "$PLATFORM" || need+=("$PLATFORM")
  [ -x "$root/build-tools/35.0.0/aapt2" ] && skip "$BUILD_TOOLS" || need+=("$BUILD_TOOLS")
  if [ "$YES" = 1 ]; then
    yes 2>/dev/null | "$sm" --sdk_root="$root" --licenses >/dev/null || true
  elif [ ! -f "$root/licenses/android-sdk-license" ]; then
    say "Android SDK licences: read them and answer y to accept."
    "$sm" --sdk_root="$root" --licenses
  fi
  if [ ${#need[@]} -gt 0 ]; then
    say "Installing ${need[*]} into $root"
    "$sm" --sdk_root="$root" "${need[@]}"
  fi
}

case "$(uname -s)" in
# ---------------------------------------------------------------------------
Darwin)
  if ! command -v brew >/dev/null; then
    echo "Homebrew is needed. Install it from https://brew.sh, then run ./setup.sh again."
    exit 1
  fi
  BREW="$(brew --prefix)"
  SDK="$BREW/share/android-commandlinetools"   # where the cask keeps the SDK; build.sh looks here
  say "This will set up, using Homebrew:"
  echo "  1. JDK 17                     brew install openjdk@17"
  echo "  2. Android command-line tools brew install --cask android-commandlinetools"
  echo "  3. adb                        brew install --cask android-platform-tools"
  echo "  4. Android SDK packages       sdkmanager \"$PLATFORM\" \"$BUILD_TOOLS\" into"
  echo "                                $SDK (asks you to accept Google's SDK licences)"
  echo "Already installed pieces are skipped."
  confirm

  say "Homebrew packages"
  if brew list --formula openjdk@17 >/dev/null 2>&1; then skip "openjdk@17"; else brew install openjdk@17; fi
  for c in android-commandlinetools android-platform-tools; do
    if brew list --cask "$c" >/dev/null 2>&1; then skip "$c"; else brew install --cask "$c"; fi
  done

  JAVA="$(brew --prefix openjdk@17)"
  export JAVA_HOME="$JAVA" PATH="$JAVA/bin:$PATH"
  SM="$(command -v sdkmanager || echo "$BREW/bin/sdkmanager")"
  say "Android SDK packages"
  sdk_packages "$SM" "$SDK"
  ;;
# ---------------------------------------------------------------------------
Linux)
  if ! command -v apt-get >/dev/null; then
    echo "This script knows Debian/Ubuntu (apt). On other distros install a JDK 17, adb and the"
    echo "Android command-line tools yourself, then: sdkmanager \"$PLATFORM\" \"$BUILD_TOOLS\""
    exit 1
  fi
  [ "$(uname -m)" = x86_64 ] || echo "Warning: Google's build-tools only ship for x86_64 Linux; on $(uname -m) aapt2 may not run."
  SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"   # build.sh looks in ~/Android/Sdk
  SUDO=""; [ "$(id -u)" = 0 ] || SUDO="sudo"
  say "This will set up:"
  echo "  1. JDK 17, adb, unzip, curl   $SUDO apt-get install openjdk-17-jdk-headless adb unzip curl"
  echo "  2. Android command-line tools download $CLT_ZIP (~180 MB) from Google"
  echo "                                into $SDK/cmdline-tools/latest"
  echo "  3. Android SDK packages       sdkmanager \"$PLATFORM\" \"$BUILD_TOOLS\""
  echo "                                (asks you to accept Google's SDK licences)"
  echo "Already installed pieces are skipped."
  confirm

  say "apt packages"
  pkgs=()
  for p in openjdk-17-jdk-headless adb unzip curl; do
    if dpkg -s "$p" >/dev/null 2>&1; then skip "$p"; else pkgs+=("$p"); fi
  done
  if [ ${#pkgs[@]} -gt 0 ]; then
    $SUDO apt-get update
    $SUDO apt-get install -y "${pkgs[@]}"
  fi
  JAVA="/usr/lib/jvm/java-17-openjdk-$(dpkg --print-architecture)"
  [ -x "$JAVA/bin/javac" ] || JAVA="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
  export JAVA_HOME="$JAVA" PATH="$JAVA/bin:$PATH"

  say "Android command-line tools"
  SM="$SDK/cmdline-tools/latest/bin/sdkmanager"
  if [ -x "$SM" ]; then
    skip "$SDK/cmdline-tools/latest"
  else
    tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
    curl -fL --progress-bar -o "$tmp/clt.zip" "https://dl.google.com/android/repository/$CLT_ZIP"
    echo "$CLT_SHA1  $tmp/clt.zip" | sha1sum -c --quiet - || { echo "Checksum mismatch, aborting."; exit 1; }
    unzip -q "$tmp/clt.zip" -d "$tmp"
    mkdir -p "$SDK/cmdline-tools"
    rm -rf "$SDK/cmdline-tools/latest"
    mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  fi

  say "Android SDK packages"
  sdk_packages "$SM" "$SDK"
  ;;
# ---------------------------------------------------------------------------
*)
  echo "Unsupported system: $(uname -s). You need a JDK 17, adb, and the Android SDK with"
  echo "\"$PLATFORM\" and \"$BUILD_TOOLS\"; then set JAVA_HOME and ANDROID_HOME."
  exit 1 ;;
esac

say "Done. ./build.sh finds these on its own; for other tools (Android Studio, Gradle, your shell)"
echo "add this to your ~/.zshrc or ~/.bashrc:"
echo
echo "  export JAVA_HOME=\"$JAVA\""
echo "  export ANDROID_HOME=\"$SDK\""
echo "  export PATH=\"\$JAVA_HOME/bin:\$PATH\""
echo
echo "Next: ./build.sh"
