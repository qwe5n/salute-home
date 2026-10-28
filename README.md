# Salute Home

A home screen for the Sber Salute TV (a CVTE board on Android 13), in the style of Apple TV: a dock of
apps whose glow takes each app's colour, shelves of what to watch, and a top bar for the TV's own settings.
It began as the `home/` app of [Baron Home](https://github.com/sethihq/baron-tv), see [NOTICE.md](NOTICE.md).

It is made for, and tested on, the Salute TV only. Some parts call that firmware's own screens (the source
panel, the Sound and Picture panels) and fall back to Android's settings where those are missing; on other
TVs expect rough edges.

## What's on it

- **The dock**, with a glow under it drawn in each app's colour, all equally bright, and a full list of apps.
- **Shelves:** YouTube (the video left half-watched and your home feed, once signed in), trending films and
  shows (TMDB with a key, TVmaze without; they open the app you choose), and what apps publish to Android's TV listings (Continue
  watching and their recommendations).
- **The top bar:** clock, weather (Open-Meteo), Wi-Fi, Bluetooth, sound, picture settings and inputs.
- **Now playing:** the music or video that's playing, with controls, a music mode and synced lyrics (LRCLIB).
- **A DVD screensaver**, and a night shade that dims the screen from 23:00 to 06:00.
- **With the accessibility service on:** a volume pill for the remote's volume keys, and the Input key
  opening the source panel from any app.

## Install

You need:

- JDK 17 (`JAVA_HOME`, or `java` on `PATH`) and the Android SDK, platform 34;
- `adb`: on `PATH`, or in the SDK's `platform-tools`, or set `ADB=/path/to/adb`;
- network debugging on the TV, and its IP address;
- on Windows, Git Bash to run the scripts.

Write `local.properties` in the repo root (it is ignored by git; keep it out of commits):

```properties
sdk.dir=/path/to/Android/Sdk
weather.lat=55.7558
weather.lon=37.6173
weather.place=Москва
# Optional: a TMDB API key for the trending shelf. Without one it uses TVmaze.
tmdb.key=
# Optional: the app the trending shelf's posters open, by package (e.g. com.stremio.one). Without it, the first
# app for films and series in the dock, YouTube aside.
shelf.app=
```

**Installing it for someone else with a coding agent?** Before the first build, ask the person which app
they watch films and series in, find its package on the TV (`adb shell pm list packages`), and set
`shelf.app` to it. Don't assume Stremio or any other service. Ask for their city too, for the weather.

Then build and install, which also makes it the TV's Home:

```bash
TV_HOST=192.168.1.50 ./build-and-install.sh
```

The first time, the TV should ask to allow this computer's debugging: accept it and run the script again.
On one Salute TV the prompt never came for a new key; if it doesn't, point adb at a key the TV already
trusts, `ADB_VENDOR_KEYS=/path/to/adbkey`. Before
its first change the script saves the TV's old Home, accessibility services, location and transition
settings to `.tv-state`. It turns location on, because Android 13 shows the Wi-Fi network's name only then
(the TV has no GPS).

`A11Y=1` also switches on the accessibility service for the volume pill and the Input key. Do it in a second
run after the first install, once the TV has rebooted and come back to the new Home:

```bash
A11Y=1 TV_HOST=192.168.1.50 ./build-and-install.sh
```

To go back to the stock Sber launcher, with the saved settings (`UNINSTALL=1` also removes the app):

```bash
TV_HOST=192.168.1.50 ./rollback.sh
```

`measure.sh` reports the cold start time, memory and dropped frames on the TV.

When you're done, turn network debugging off on the TV: its ADB port is open to the whole network.

## YouTube sign-in

The YouTube shelf uses YouTube's unofficial TV API, the one the YouTube TV app uses, so it may stop working
when YouTube changes it. `home/YouTube.kt` holds that app's public OAuth client ID and secret, the same ones
other open-source YouTube TV clients ship; they are not a secret of this project. You sign in the TV way:
the shelf shows a code that you type at google.com/device on a phone, so no password reaches the TV. The
tokens stay in the app's private storage.

## Build and test

```bash
./gradlew testReleaseUnitTest lintRelease assembleRelease
```

## Licence

MIT, see [LICENSE](LICENSE). Fonts, icons, app logos and data services are listed in
[NOTICE.md](NOTICE.md) with their own licences.
