# Hestia (test) — tester guide

**English** · [Français](TESTING.fr.md) · [Русский](TESTING.ru.md)

## What this is

An **unofficial test build** of [Hestia](https://codeberg.org/kapoue/Hestia), the free Android app
that controls Shelly devices on your local network (no cloud, no account). This fork adds features
that are not (yet) in the official app:

- Russian translation;
- a clear message when a device can't be controlled (shutter, sensor, energy meter…);
- dimmers (`light` devices): on/off and brightness.

It runs on **Android 10 to 17**.

> Independence: Hestia is an independent project, with no connection to Shelly or Allterco
> Robotics. It is neither commissioned, sponsored, nor endorsed by Shelly. Shelly is a registered
> trademark of its respective owner; it is mentioned only for technical compatibility.

## Install

1. Open <https://github.com/mezinster/Hestia/releases/latest> on your phone.
2. Under **Assets**, download `hestia-<version>.apk`.
3. Open the file. If Android asks, allow installing apps from your browser or file manager.

The app appears as **« Hestia (test) »**. It installs **next to** the official Hestia from F-Droid:
both can live on the same phone, each with its own devices and settings.

## Update

Download the newer APK from the same link and install it over the old one. Your devices and settings
are kept. There are no automatic updates: check the link from time to time, or watch the repository's releases
on GitHub (Watch → Custom → Releases).

## If installation is blocked

- **"App blocked to protect your device" (Google Play Protect):** Play Protect warns about any app
  from a developer it doesn't know yet — it doesn't mean something is wrong with the app. Tap
  **More details → Install anyway**. If that option isn't offered: Play Store → your profile picture
  → **Play Protect** → ⚙️ → temporarily turn off **Scan apps with Play Protect**, install, then turn
  it back on.
- **"App not installed":**
  - make sure you open the **newest** file (`hestia-<version>.apk` from the latest release): Android
    refuses to install an *older* version over a newer one;
  - if it still fails and « Hestia (test) » is already installed, uninstall it (Settings → Apps) and
    install again — you'll need to add your devices again;
  - check that the download is complete (about 5 MB) and that the phone has free space.

The official Hestia from F-Droid is never affected: it's a separate app.

## Android 17

The first time Hestia contacts a device, Android asks for permission to reach **devices on your
local network**. Hestia needs it to talk to your Shelly devices; it only contacts the IP addresses
you enter.

## Verify the download (optional)

- Each APK has a `.sha256` file next to it: `sha256sum -c hestia-<version>.apk.sha256`.
- The APK is signed with this certificate (SHA-256), which you can check with an app such as
  AppVerifier:

  ```
  17:69:2C:2F:A7:AF:58:D1:8E:90:54:B9:71:4E:B0:34:E3:86:68:B4:B5:B9:F5:45:80:4B:64:90:91:FA:DD:68
  ```

## Report a bug

Open an issue at <https://github.com/mezinster/Hestia/issues> with:

- your Android version and phone model;
- the Shelly model (and generation, if you know it);
- what you did, what you expected, what happened;
- the diagnostic log, if you're comfortable sharing it: on the **Dashboard**, tap the title at the
  top **5 times quickly** to open **Diagnostic log**, then **Share**. It contains no passwords, but it
  does contain your **local IP addresses and device names**, and an issue is **public**. Look through
  it before posting and remove anything you don't want to show, or ask in the issue for another way
  to send it.
