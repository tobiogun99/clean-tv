# Working on Clean TV with a coding agent

Clean TV is a Google TV launcher in plain Java: no AndroidX, no Kotlin, no libraries. Keep it that way;
size and startup speed are the point.

## Map

| File | What it is |
| --- | --- |
| `LauncherActivity.java` | The home screen: layout, dock/list, top controls, All apps, wallpaper picker, Art Mode, Settings host |
| `SettingsPanel.java` | Settings overview and pages, with the live preview |
| `Island.java`, `NowCard.java`, `Sessions.java`, `Track.java` | Now playing: the island, the Art Mode/screensaver card, session tracking, metadata |
| `App.java` | Installed apps and white-logo extraction from icons |
| `Wall.java`, `Walls.java`, `Frost.java` | Wallpaper decoding (at screen size), the blurred copy and the frosted-glass views |
| `Weather.java` | Open-Meteo weather next to the clock |
| `Fonts.java` | Font choice; every label registers so a change restyles the whole screen |
| `HomeKeyService.java`, `Stock.java` | Catches Home (accessibility service); finds/opens the stock launcher |
| `ArtDream.java` | The screensaver |
| `build.sh`, `config.sh` | Build with raw SDK tools; app ID and version |
| `tv.sh` | Everything done to the TV over ADB, each with an undo |

## Rules

- **Never commit** `keystore.jks`, anything in `fonts-private/`, wallpapers, or `.tv-*` files.
  Never put a font, photo or logo in the repo without a licence that allows redistribution, and
  record it in `THIRD_PARTY_NOTICES.md`.
- **Don't add dependencies.** If something seems to need one, write the small part you need.
- **Anything that changes the TV goes in `tv.sh` with an undo**, and `reset` must undo it too.
- **Decode images at screen size or smaller** (`Walls.decode`). Low-end Chromecasts have 1.5 GB.
- **Focus alone never launches or saves anything.** OK applies; Back goes up one level; Home goes home.
- **Keep animations to transforms and opacity**, short (under ~300 ms), and never delay a launch.

## Testing

- `./build.sh` must pass. There are no unit tests; check on a TV.
- Screenshots: `adb exec-out screencap -p > shot.png`. Recordings: `adb shell screenrecord`.
- **Injected keys (`adb shell input keyevent`) skip the Home-button service.** Anything about Home
  must be checked with the physical remote; read `adb logcat` while the person presses buttons.
- After `adb install`, the service stays bound but stops catching keys until it is toggled.
  `./tv.sh install` does this; if you install another way, run `./tv.sh home-off && ./tv.sh home-on`.
- `./tv.sh doctor` is the first thing to run when something "doesn't work".
- Ask before rebooting the TV, disabling packages, or doing anything that interrupts someone
  watching it. Check what's on screen first:
  `adb shell dumpsys activity activities | grep topResumedActivity`.
