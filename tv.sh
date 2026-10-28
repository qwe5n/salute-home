# Sourced by build-and-install.sh, rollback.sh and measure.sh: finds adb, connects to the TV at $TV_HOST
# over network ADB, and defines tv '<command>', which runs a shell command on the TV.
# adb is $ADB if set, else the one on PATH, else platform-tools in the Android SDK (ANDROID_HOME, or
# sdk.dir in local.properties).
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
export MSYS_NO_PATHCONV=1

if [ -z "${ADB:-}" ]; then
  if command -v adb >/dev/null 2>&1; then
    ADB=adb
  else
    sdk=${ANDROID_HOME:-$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null | sed 's/\\:/:/g; s/\\\\/\//g')}
    for a in "$sdk/platform-tools/adb" "$sdk/platform-tools/adb.exe"; do
      [ -x "$a" ] && ADB=$a && break
    done
  fi
fi
[ -n "${ADB:-}" ] || { echo "ERROR: no adb found: install Android platform-tools, or set ADB=/path/to/adb" >&2; exit 1; }

case "$TV_HOST" in *:*) SERIAL=$TV_HOST ;; *) SERIAL=$TV_HOST:5555 ;; esac
"$ADB" connect "$SERIAL" >/dev/null 2>&1 || true
state=$("$ADB" -s "$SERIAL" get-state 2>&1 | tr -d '\r' || true)
case "$state" in
  device) ;;
  *unauthorized*)
    echo "ERROR: the TV hasn't allowed this computer yet: accept the debugging prompt on the TV, then run again." >&2
    echo "No prompt? Set ADB_VENDOR_KEYS to an adb key the TV already trusts." >&2
    exit 1 ;;
  *)
    echo "ERROR: can't reach the TV at $SERIAL ($state). Is network debugging on and the IP right?" >&2
    exit 1 ;;
esac

tv() { "$ADB" -s "$SERIAL" shell "$@" | tr -d '\r'; }
