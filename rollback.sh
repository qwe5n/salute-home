#!/usr/bin/env bash
# Put the TV back to the stock Sber launcher as Home (re-enabled), with the old accessibility services and
# location setting. The new home stays installed but unused; UNINSTALL=1 removes it.
set -euo pipefail
cd "$(dirname "$0")"
. ./tv.sh
PKG=com.salutehome
home=ru.sberdevices.starlauncher/.ui.mainscreen.MainActivity
a11y=null
location=
transitions=0.0
[ -f .tv-state ] && . ./.tv-state
case "$home" in */*) ;; *) home=ru.sberdevices.starlauncher/.ui.mainscreen.MainActivity ;; esac
tv "pm enable ${home%%/*}" || true
tv "cmd package set-home-activity --user 0 $home
settings put secure enabled_accessibility_services $a11y
cmd notification disallow_listener $PKG/$PKG.media.NowPlayingListener
settings delete secure screensaver_components
settings put global transition_animation_scale $transitions"
[ "$location" = 0 ] && tv "cmd location set-location-enabled false"
[ "${UNINSTALL:-0}" = 1 ] && tv "pm uninstall $PKG"
tv "input keyevent KEYCODE_HOME; cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN | tail -n 1"
echo "Back to the old home."
