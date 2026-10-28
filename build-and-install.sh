#!/usr/bin/env bash
# Build the home screen (com.salutehome) and make it the Salute TV's Home, with its DVD screensaver and
# the system's app transitions on (this firmware ships them off), so apps grow out of their tiles.
# rollback.sh switches back to the stock Sber launcher.
# Needs JDK 17 (JAVA_HOME, or java on PATH), the Android SDK and adb (see tv.sh), and network debugging on
# the TV. On Windows, run it from Git Bash. What it changes on the TV, after saving the old values to
# .tv-state (rollback.sh reads them): the default Home, notification access (now playing) and location
# (the Wi-Fi name).
# A11Y=1 also switches on the accessibility service (the volume pill and the Input key).
set -euo pipefail
cd "$(dirname "$0")"
. ./tv.sh
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"
[ -f local.properties ] || { echo "ERROR: write local.properties first (sdk.dir, weather.*, tmdb.key)" >&2; exit 1; }
./gradlew assembleRelease -q --console=plain 2>&1 | grep -v "SDK XML" || true
APK=app/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || { echo "ERROR: the build made no APK" >&2; exit 1; }
PKG=com.salutehome

# Save what's there now, once (a reinstall keeps the first, real, old values).
if [ ! -f .tv-state ]; then
  {
    echo "home=$(tv 'cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN' | tail -n 1)"
    echo "a11y=$(tv 'settings get secure enabled_accessibility_services' | tail -n 1)"
    echo "location=$(tv 'settings get secure location_mode' | tail -n 1)"
    echo "transitions=$(tv 'settings get global transition_animation_scale' | tail -n 1)"
  } > .tv-state
  echo "Saved the TV's current settings to .tv-state:"; cat .tv-state
fi

"$ADB" -s "$SERIAL" install -r "$APK" | tr -d '\r' | grep -v '^Performing'
tv "pm grant $PKG android.permission.ACCESS_FINE_LOCATION
pm grant $PKG android.permission.READ_TV_LISTINGS
pm grant $PKG android.permission.BLUETOOTH_CONNECT
cmd notification allow_listener $PKG/$PKG.media.NowPlayingListener
cmd package compile -m speed -f $PKG >/dev/null
cmd package set-home-activity --user 0 $PKG/.HomeActivity
settings put secure screensaver_components $PKG/.saver.DvdDream
settings put global transition_animation_scale 1.0"
# Android 13 shows the Wi-Fi network's name only with location on (the TV has no GPS: nothing is located).
tv "cmd location set-location-enabled true; echo location: \$(settings get secure location_mode)"

if [ "${A11Y:-0}" = 1 ]; then
  tv "pm grant $PKG android.permission.WRITE_SECURE_SETTINGS"
  tv "s=\$(settings get secure enabled_accessibility_services | tr ':' '\n' | grep -v -e '^$PKG/' -e '^null\$' | tr '\n' ':' | sed 's/:\$//'); settings put secure enabled_accessibility_services \"\${s:+\$s:}$PKG/$PKG.input.InputKeyService\"; settings put secure accessibility_enabled 1; echo accessibility: \$(settings get secure enabled_accessibility_services)"
fi

if [ "${QUIET:-0}" != 1 ]; then
  tv "am start -W -n $PKG/.HomeActivity | grep -E '^(Status|TotalTime|Error)'"
fi
HOME_NOW=$(tv 'cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN' | tail -n 1)
case "$HOME_NOW" in
  $PKG/*) echo "Home is now $HOME_NOW. Undo with: TV_HOST=$TV_HOST ./rollback.sh" ;;
  *) echo "ERROR: Home resolves to ${HOME_NOW:-nothing}, not the new home. Run ./rollback.sh." >&2; exit 1 ;;
esac
