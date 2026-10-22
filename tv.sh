#!/usr/bin/env bash
# Everything you do to the TV over ADB. Every change has an undo, and `reset` undoes them all.
set -euo pipefail
cd "$(dirname "$0")"
source ./config.sh

CLS=io.github.cleantv            # Java package: stays the same even if you change APP_ID
SVC="$APP_ID/$CLS.HomeKeyService"
LISTENER="$APP_ID/$CLS.MediaListener"
DREAM="$APP_ID/$CLS.ArtDream"
WALLS="/sdcard/Android/data/$APP_ID/files/wallpapers"
STATE_DISABLED=.tv-disabled     # packages `debloat` turned off, so `restore` undoes exactly those
STATE_DREAM=.tv-screensaver     # the screensaver you had before

# Extras that serve ads/recommendations or run in the background. Only ones present are touched;
# Google protects some on some builds, and those are left alone.
BLOAT=(
  com.google.android.apps.tv.dreamx           # Ambient mode screensaver (recommendations + promos)
  com.google.android.tvrecommendations        # recommendation rows provider
  com.google.android.leanbacklauncher.recommendations
  com.google.android.feedback                 # feedback / crash report uploader
  com.google.android.play.games               # Play Games
  # com.google.android.katniss                # Assistant/Gemini + voice search: only if you never use the mic
)

say() { printf '%s\n' "$*"; }
ok() { printf '  \033[32mok\033[0m    %s\n' "$*"; }
warn() { printf '  \033[33mcheck\033[0m %s\n' "$*"; }
bad() { printf '  \033[31mno\033[0m    %s\n' "$*"; }

# One TV can show up twice (IP + mDNS name). Pick one unless ANDROID_SERIAL says otherwise.
need_device() {
  [ -n "${ANDROID_SERIAL:-}" ] && return 0
  local devs
  devs=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
  if [ -z "$devs" ]; then
    say "No TV connected. Run: ./tv.sh connect"
    exit 1
  fi
  export ANDROID_SERIAL
  ANDROID_SERIAL=$(head -1 <<<"$devs")
}

sh() { adb shell "$@" | tr -d '\r'; }

a11y_remove() { # prints the enabled-services list without ours
  tr ':' '\n' <<<"$1" | grep -vxF "$SVC" | grep -v '^$' | paste -sd: - || true
}

home_service_on() {
  local cur
  cur=$(sh settings get secure enabled_accessibility_services)
  if [[ -z "$cur" || "$cur" == null ]]; then cur="$SVC"
  elif [[ ":$cur:" != *":$SVC:"* ]]; then cur="$cur:$SVC"; fi
  adb shell settings put secure enabled_accessibility_services "$cur"
  adb shell settings put secure accessibility_enabled 1
}

home_service_off() {
  local rest
  rest=$(a11y_remove "$(sh settings get secure enabled_accessibility_services)")
  if [[ -z "$rest" || "$rest" == null ]]; then adb shell settings delete secure enabled_accessibility_services >/dev/null
  else adb shell settings put secure enabled_accessibility_services "$rest"; fi
}

home_service_enabled() {
  [[ ":$(sh settings get secure enabled_accessibility_services):" == *":$SVC:"* ]]
}

case "${1:-help}" in

  connect) # ./tv.sh connect [pairing-code]   or   ./tv.sh connect <ip:port>
    if [[ "${2:-}" == *:* ]]; then adb connect "$2"; adb devices; exit 0; fi
    services=$(adb mdns services 2>/dev/null || true)
    pair=$(grep _adb-tls-pairing <<<"$services" | awk '{print $NF}' | head -1 || true)
    conn=$(grep _adb-tls-connect <<<"$services" | awk '{print $NF}' | head -1 || true)
    if [ -n "${2:-}" ]; then
      [ -n "$pair" ] || { say "The TV isn't showing a pairing code. On the TV: Developer options > Wireless debugging > Pair device with pairing code, then run this again."; exit 1; }
      adb pair "$pair" "$2"
    fi
    if [ -n "$conn" ]; then
      adb connect "$conn" || true
    fi
    if adb devices | awk 'NR > 1 && $2 == "device"' | grep -q .; then
      say "Connected."
      adb devices
    else
      say "Couldn't connect. On the TV:"
      say "  1. Settings > System > About: click 'Android TV OS build' 7 times (enables Developer options)"
      say "  2. Settings > System > Developer options: turn on Wireless debugging"
      say "  3. Pair device with pairing code, then: ./tv.sh connect <the 6-digit code>"
      say "If pairing worked but connecting times out, see 'Connection problems' in the README (IPv6 fallback)."
      exit 1
    fi ;;

  stay-connected) # fixed port 5555 until the TV restarts: no more pairing codes or changing ports
    need_device
    ip=$(sh ip -4 addr show wlan0 | awk '/inet /{print $2}' | cut -d/ -f1)
    [ -n "$ip" ] || ip=$(sh ip -4 addr show eth0 | awk '/inet /{print $2}' | cut -d/ -f1)
    adb tcpip 5555 >/dev/null
    sleep 3
    adb connect "$ip:5555"
    say "From now on (until the TV restarts): ./tv.sh connect $ip:5555"
    say "Anyone on your network could also try that port; the TV asks you to approve new computers." ;;

  download)       # get the latest ready-made app from GitHub (no build tools needed)
    url=$(curl -fsSL https://api.github.com/repos/tobiogun99/clean-tv/releases/latest \
      | grep -oE '"browser_download_url": *"[^"]+\.apk"' | head -1 | sed -E 's/.*"(https[^"]+)"/\1/')
    [ -n "$url" ] || { say "Couldn't find a release. Check your internet, or build it yourself: ./build.sh"; exit 1; }
    mkdir -p build
    curl -fL --progress-bar -o build/cleantv.apk "$url"
    say "Downloaded $(basename "$url"). Next: ./tv.sh install" ;;

  install)        # install build/cleantv.apk (re-arms the Home button after updates)
    need_device
    [ -f build/cleantv.apk ] || { say "No app to install yet. Run ./tv.sh download (or ./build.sh) first."; exit 1; }
    adb install -r build/cleantv.apk
    # An app update leaves the service bound but silently drops its key filter; re-enabling restores it.
    if home_service_enabled; then
      home_service_off
      sleep 1
      home_service_on
    fi ;;

  home-on) # Home button -> Clean TV; the stock launcher stays installed as a backup
    need_device
    home_service_on
    adb shell am start -n "$APP_ID/$CLS.LauncherActivity" >/dev/null
    say "Home now opens $APP_NAME. Stock home: Settings > Google TV > Google TV home (or ./tv.sh home-off)." ;;

  home-off) # back to the stock home screen; Clean TV stays installed
    need_device
    home_service_off
    adb shell input keyevent KEYCODE_HOME
    say "The stock home screen is back." ;;

  debloat)        # turn off ad/recommendation extras (undo: restore)
    need_device
    enabled=$(sh pm list packages -e)
    disabled=$(sh pm list packages -d)
    for p in "${BLOAT[@]}"; do
      if grep -qx "package:$p" <<<"$enabled"; then
        if adb shell pm disable-user --user 0 "$p" 2>&1 | grep -q "new state: disabled-user"; then
          echo "$p" >> "$STATE_DISABLED"; say "off  $p"
        else
          say "lock $p (protected on this TV, left as is)"
        fi
      elif grep -qx "package:$p" <<<"$disabled"; then
        say "off  $p (already)"
      else
        say "skip $p (not on this TV)"
      fi
    done ;;

  restore)        # turn back on everything debloat turned off
    need_device
    [[ -f "$STATE_DISABLED" ]] || { say "Nothing to restore."; exit 0; }
    sort -u "$STATE_DISABLED" | while read -r p; do adb shell pm enable "$p" >/dev/null && say "on   $p"; done
    rm "$STATE_DISABLED" ;;

  speed)       # system animations at half length: menus and app opens feel snappier
    need_device
    for k in window_animation_scale transition_animation_scale animator_duration_scale; do
      adb shell settings put global "$k" 0.5; done
    say "Animations at 0.5x." ;;

  speed-reset)    # animations back to normal
    need_device
    for k in window_animation_scale transition_animation_scale animator_duration_scale; do
      adb shell settings put global "$k" 1; done
    say "Animations back to 1x." ;;

  screensaver-on)   # your wallpapers as the screensaver
    need_device
    [[ -f "$STATE_DREAM" ]] || sh settings get secure screensaver_components > "$STATE_DREAM"
    adb shell settings put secure screensaver_components "$DREAM"
    adb shell settings put secure screensaver_enabled 1
    say "Screensaver: $APP_NAME Art." ;;

  screensaver-off) # back to the screensaver you had
    need_device
    old=$(cat "$STATE_DREAM" 2>/dev/null || echo null)
    if [[ "$old" == null || -z "$old" ]]; then adb shell settings delete secure screensaver_components >/dev/null
    else adb shell settings put secure screensaver_components "$old"; fi
    rm -f "$STATE_DREAM"
    say "Screensaver restored." ;;

  nowplaying-on)    # lets Clean TV see what's playing (it never reads notifications)
    need_device
    adb shell cmd notification allow_listener "$LISTENER"
    say "Now playing on." ;;

  nowplaying-off) # revoke the now-playing permission
    need_device
    adb shell cmd notification disallow_listener "$LISTENER"
    say "Now playing off." ;;

  weather)          # ./tv.sh weather "Lisbon"   (looked up here; only coordinates go to the TV)
    need_device
    shift
    hit=$(curl -fsG "https://geocoding-api.open-meteo.com/v1/search" --data-urlencode "name=$*" -d count=1 \
      | python3 -c 'import json,sys; r=json.load(sys.stdin).get("results") or []; print("%s %s %s, %s" % (r[0]["latitude"], r[0]["longitude"], r[0]["name"], r[0].get("admin1", r[0].get("country",""))) if r else "")')
    [[ -n "$hit" ]] || { say "Couldn't find \"$*\"."; exit 1; }
    read -r lat lon place <<<"$hit"
    adb shell am start -n "$APP_ID/$CLS.LauncherActivity" --ef lat "$lat" --ef lon "$lon" >/dev/null
    say "Weather for $place." ;;

  wallpaper)        # ./tv.sh wallpaper photo.jpg [more.jpg ...]   (resized for the TV first if you have sips/convert)
    need_device
    shift
    adb shell mkdir -p "$WALLS"
    tmp=$(mktemp -d)
    for f in "$@"; do
      out="$tmp/$(basename "$f")"
      if command -v sips >/dev/null; then sips -s format jpeg -s formatOptions 88 -Z 2560 "$f" --out "$out" >/dev/null 2>&1 || cp "$f" "$out"
      elif command -v convert >/dev/null; then convert "$f" -resize '2560x2560>' -quality 88 "$out" || cp "$f" "$out"
      else cp "$f" "$out"; fi
      adb push "$out" "$WALLS/" >/dev/null && say "added $(basename "$f")"
    done
    rm -rf "$tmp" ;;

  doctor)           # what's set up, what isn't, and what to run about it
    say "$APP_NAME doctor"
    if ! adb devices | awk 'NR > 1 && $2 == "device"' | grep -q .; then bad "no TV connected (./tv.sh connect)"; exit 1; fi
    need_device
    ok "connected: $ANDROID_SERIAL ($(sh getprop ro.product.model), Android $(sh getprop ro.build.version.release))"
    ver=$(sh dumpsys package "$APP_ID" | awk -F= '/versionName/{print $2; exit}')
    if [ -n "$ver" ]; then ok "$APP_NAME $ver installed"; else bad "$APP_NAME not installed (./build.sh && ./tv.sh install)"; fi
    if home_service_enabled; then
      a11y=$(sh dumpsys accessibility)
      if grep -q "$CLS.HomeKeyService\|$APP_NAME Home button" <<<"$a11y"; then ok "Home button service running"
      else warn "Home button service enabled but not running yet (wait a few seconds, or ./tv.sh home-on)"; fi
      if grep -q KeyboardInterceptor <<<"$a11y"; then ok "Home key is being caught"
      else warn "service is on but not catching keys; run ./tv.sh home-off && ./tv.sh home-on"; fi
    else
      warn "Home button still goes to the stock home (./tv.sh home-on)"
    fi
    homes=$(sh cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME | awk -F= '/packageName=/{print $2}' | sort -u | paste -sd, -)
    say "  info  home apps on this TV: $homes"
    if [[ "$(sh settings get secure enabled_notification_listeners)" == *"$CLS.MediaListener"* ]]; then ok "now playing on"
    else warn "now playing off (./tv.sh nowplaying-on)"; fi
    if [[ "$(sh settings get secure screensaver_components)" == *"$CLS.ArtDream"* ]]; then ok "screensaver is $APP_NAME Art"
    else say "  info  screensaver: $(sh settings get secure screensaver_components) (./tv.sh screensaver-on to use your wallpapers)"; fi
    n=$(sh "ls $WALLS 2>/dev/null | wc -l" | tr -d ' ')
    if [ "${n:-0}" -gt 0 ]; then ok "$n wallpapers"; else say "  info  no wallpapers yet (./tv.sh wallpaper photo.jpg)"; fi
    say "  info  animation speed: $(sh settings get global animator_duration_scale | sed 's/null/1/')x"
    [ -f "$STATE_DISABLED" ] && say "  info  turned off by debloat: $(paste -sd, "$STATE_DISABLED")"
    true ;;

  reset)            # undo everything this script has done, then uninstall
    need_device
    if [ "${2:-}" != "-y" ]; then
      read -r -p "Put the TV back exactly as it was and uninstall $APP_NAME? [y/N] " a
      [[ "$a" == y || "$a" == Y ]] || exit 0
    fi
    home_service_off
    adb shell cmd notification disallow_listener "$LISTENER" 2>/dev/null || true
    "$0" screensaver-off
    "$0" restore
    "$0" speed-reset
    adb uninstall "$APP_ID" >/dev/null 2>&1 && say "$APP_NAME uninstalled." || say "$APP_NAME wasn't installed."
    adb shell input keyevent KEYCODE_HOME
    say "Done. The TV is back to how it was." ;;

  apps)             # review what's enabled before adding to BLOAT
    need_device
    sh pm list packages -e | sed 's/^package://' | sort ;;

  *)
    say "Usage: ./tv.sh <command>"
    sed -n 's/^  \([a-z-]*\)) *#* *\(.*\)$/  \1  \2/p' "$0" | sed 's/  $//' ;;
esac
