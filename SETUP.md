# Set up Clean TV on your TV, step by step

About 15 minutes the first time. You don't need to know how to code. You'll copy and paste a
few commands into a terminal, and every change can be undone with one command.

**You need**

- A Google TV (Chromecast with Google TV is the tested one; see the README for others) and its remote.
- A Mac or Linux computer **on the same Wi-Fi as the TV**. Windows works too; see
  [Windows](#windows) at the end.

Nothing is deleted from your TV. Google's own home screen stays installed as a backup.

---

## Step 1: Get Clean TV onto your computer

Open **Terminal** (Mac: press ⌘ Space, type "Terminal", press Return). Copy each grey box below,
paste it into Terminal, and press Return. Wait for each one to finish before the next.

**1. Install `adb`,** the small tool that lets your computer talk to the TV.

Mac (needs [Homebrew](https://brew.sh); install that first if `brew` isn't found):

```bash
brew install android-platform-tools
```

Linux (Ubuntu/Debian):

```bash
sudo apt install adb curl
```

**2. Download Clean TV:**

```bash
git clone https://github.com/tobiogun99/clean-tv.git && cd clean-tv
```

> No `git`? On a Mac a box pops up offering to install it: click Install and run the line again.
> Or click **Code → Download ZIP** on the GitHub page, unzip it, and in Terminal type `cd `
> (with a space), drag the unzipped folder onto the Terminal window, and press Return.

**3. Get the app itself** (the ready-made version from the Releases page):

```bash
./tv.sh download
```

You should see `Downloaded cleantv-v….apk`. That's everything for your computer.

> **Want to build it yourself instead?** Only needed if you're changing the code or adding your
> own fonts. See [Building from source](#building-from-source) at the end.

---

## Step 2: Turn on developer mode on the TV

On the TV, with the remote:

1. Open **Settings** (the gear at the top right of the home screen).
2. Go to **System → About**.
3. Scroll down to **Android TV OS build** and press OK on it **seven times**.
   A message counts down, then says *"You are now a developer"*.

This unlocks a hidden menu called Developer options. It doesn't change anything else.

---

## Step 3: Turn on wireless debugging

Still on the TV:

1. Go back to **Settings → System → Developer options**.
2. Scroll down to **Wireless debugging** and open it.
3. Choose **Enabled**. (If it asks "Allow wireless debugging on this network?", say **Allow**.)
4. Open **Pair device with pairing code**. A 6-digit code appears. **Leave this screen open.**

Wireless debugging is what lets your computer talk to the TV over Wi-Fi.

---

## Step 4: Connect your computer to the TV

Back in Terminal on your computer, type `./tv.sh connect ` followed by the 6-digit code from
the TV, then press Return. For example:

```bash
./tv.sh connect 123456
```

You should see `Connected.` The TV may briefly show your computer's name under **Paired devices**.

> **Didn't work?** Make sure the code screen is still open on the TV (codes expire when you leave
> it), and that the computer and TV are on the same Wi-Fi. See [Troubleshooting](#troubleshooting).

You only pair once. Later, if the connection drops, turning Wireless debugging back on and running
`./tv.sh connect` (no code) is usually enough.

---

## Step 5: Install Clean TV and make it your home screen

```bash
./tv.sh install
```

```bash
./tv.sh home-on
```

Your TV now shows Clean TV. Press **Home** on the remote: it should come back to Clean TV.

Check everything is working:

```bash
./tv.sh doctor
```

Green **ok** lines are good. Each **check** line tells you what to run to fix it.

> **Why does `home-on` need a special permission?** Google TV always sends the Home button to its
> own home screen. Clean TV uses an accessibility service that only watches the Home button and
> which app is in front, and opens Clean TV instead. It reads nothing else. The README explains every
> permission.

---

## Step 6 (optional): Make it yours

Your own photos as wallpaper (they're resized on the way):

```bash
./tv.sh wallpaper ~/Pictures/beach.jpg ~/Pictures/mountains.jpg
```

Weather next to the clock (type your city):

```bash
./tv.sh weather "Lisbon"
```

The "now playing" island that shows your music:

```bash
./tv.sh nowplaying-on
```

Your photos as the screensaver instead of Google's ambient mode:

```bash
./tv.sh screensaver-on
```

Turn off Google's ad-supported screensaver and a few background extras:

```bash
./tv.sh debloat
```

Make menus and app opening feel snappier (half-length animations):

```bash
./tv.sh speed
```

Everything else is on the TV: press the **gear** on Clean TV's home screen for layout, font, focus
style, wallpaper rotation and which apps are in the dock. **Hold OK** on any app for more options.

---

## Using Clean TV

- **OK** opens an app. **Hold OK** to move it, remove it from the dock, or uninstall it.
- **Home** always comes back to Clean TV. Holding Home still opens Google's side panel.
- **Google's home screen** is still there: Clean TV's **Settings → Google TV → Google TV home**.
- Top right: **Art Mode** (just the photo), **Wallpaper**, **All apps**, **Settings**.

---

## Undo

Put back Google's home screen but keep Clean TV installed:

```bash
./tv.sh home-off
```

Undo **everything** (home screen, screensaver, debloat, animation speed, permissions) and uninstall Clean TV:

```bash
./tv.sh reset
```

You can also turn **Wireless debugging** off on the TV when you're done. Clean TV keeps working
without it; you only need it to install updates or change things from your computer.

---

## Troubleshooting

**`./tv.sh connect` says it couldn't connect.**
Wireless debugging probably switched itself off. Google TV does this after sleep or a Wi-Fi
change. Turn it back on (Step 3) and run `./tv.sh connect` again. If it asks you to pair, open
**Pair device with pairing code** and run `./tv.sh connect` with the new code.

**It keeps disconnecting.**
Once connected, run:

```bash
./tv.sh stay-connected
```

That switches to a fixed connection that survives sleep until the TV restarts. Giving the TV a
fixed IP address in your router's settings also helps a lot.

**Home goes to Google's home screen again.**
Run `./tv.sh doctor`. If it says the service isn't catching keys, run:

```bash
./tv.sh home-off && ./tv.sh home-on
```

This can happen after Clean TV updates; `./tv.sh install` normally takes care of it.

**Pairing works but connecting times out.**
Some routers have trouble with the TV's regular address. Restart the router and the TV, or see
"Connection problems" in the README for the IPv6 workaround.

**A streaming app is missing from the dock.**
Open **All apps** (top right), hold OK on the app and choose **Add to dock**.

**Something else.** Run `./tv.sh doctor` and open an issue on GitHub with its output, your TV
model and Android version.

---

## Building from source

Skip this unless you want to change Clean TV or add fonts you own (put them in `fonts-private/`).

Install the build tools (Java 17 and Android's SDK, about 1 GB). It explains what it'll do and
asks before installing anything:

```bash
./setup.sh
```

At the end it prints a few `export …` lines. Copy them all, paste them into Terminal, press Return.
Then build:

```bash
./build.sh
```

You should see `Built build/cleantv.apk`. Continue with `./tv.sh install` as in Step 5.

The first build creates `keystore.jks`, your own signing key. **Keep it:** later builds must be
signed with the same key to install over the earlier one. A version you build yourself and the
ready-made download are signed differently, so to switch between them, uninstall first
(`adb uninstall io.github.cleantv`).

To use Android Studio instead, open the folder; it builds with Gradle from the same sources.

---

## Windows

`tv.sh` and `setup.sh` are for Mac and Linux. On Windows:

1. Install [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools)
   (it contains `adb`), unzip it, and open PowerShell in that folder.
2. Download the latest `cleantv-v….apk` from the [Releases page](https://github.com/tobiogun99/clean-tv/releases/latest) and rename it to `cleantv.apk`.
3. Do Steps 2 and 3 on the TV, then run these, replacing the IP, ports and code with the ones
   shown on the TV's Wireless debugging screens:

```bash
adb pair 192.168.1.50:37000 123456
```

```bash
adb connect 192.168.1.50:41000
```

```bash
adb install -r cleantv.apk
```

```bash
adb shell settings put secure enabled_accessibility_services io.github.cleantv/io.github.cleantv.HomeKeyService
```

```bash
adb shell settings put secure accessibility_enabled 1
```

Optional, the now-playing island:

```bash
adb shell cmd notification allow_listener io.github.cleantv/io.github.cleantv.MediaListener
```

To undo, put back Google's home screen and uninstall Clean TV:

```bash
adb shell settings delete secure enabled_accessibility_services
```

```bash
adb uninstall io.github.cleantv
```

(If you already had other accessibility services on, `tv.sh` keeps them; the manual command
above removes them all. Turn them back on in the TV's Accessibility settings.)
