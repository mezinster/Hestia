# Hestia — fork with Russian, dimmers and shutters

**English** · [Français](README.fr.md) · [Русский](README.ru.md)

**Local control of Shelly home-automation devices on Android — no cloud, no account, no tracking.**

This is an **unofficial fork** of [Hestia](https://codeberg.org/kapoue/Hestia) by kapoue. It keeps
everything the original app does and adds features that are not (yet) part of it. It follows the
original closely and is regularly synced with it.

> Independence: Hestia is an independent project, with no connection to Shelly or Allterco
> Robotics. It is neither commissioned, sponsored, nor endorsed by Shelly. Shelly is a registered
> trademark of its respective owner; it is mentioned only for technical compatibility.

## What the fork adds

- **Russian translation**: the app is available in English, French and Russian.
- **Android 10** support (the original requires Android 11).
- **Device-kind detection** when adding a device: Hestia checks what the device actually exposes
  (relay, dimmer, shutter…) instead of trusting the chosen type.
- **Dimmers**: on/off and brightness, timer, name synced with the device, multi-channel dimmers on
  one row, and schedules run by the dimmer itself.
- **Roller shutters**: open / stop / close, position slider, calibration, readable fault messages,
  and schedules run by the shutter itself (open in the morning, close in the evening…).
- **Demo devices** for all of the above, to try the app without hardware.

Like the original, every schedule and timer is stored and run **by the device itself**: it keeps
working with the app closed or the phone off. Full details per version: [changelog](https://github.com/mezinster/Hestia/blob/fork/main/docs/fork/CHANGELOG.md).

## Install

1. Open the [latest release](https://github.com/mezinster/Hestia/releases/latest) on your phone.
2. Under **Assets**, download `hestia-<version>.apk` and open it.

The app appears as **"Hestia (test)"** and installs **next to** the official Hestia from F-Droid,
with its own devices and settings. There are no automatic updates: watch the repository's releases
(*Watch → Custom → Releases*). Blocked installation, updates, bug reports: see the
[tester guide](https://github.com/mezinster/Hestia/blob/fork/main/docs/fork/TESTING.md).

Shutters and dimmer schedules have not been tested on real hardware yet. If you own one, your
feedback is very welcome in the [issues](https://github.com/mezinster/Hestia/issues).

## Privacy

Same promise as the original: Hestia talks **only** to the addresses you enter, on your local
network. No network discovery, no analytics, no tracking. Two permissions only: `INTERNET` and
`ACCESS_LOCAL_NETWORK` (Android 17+). The few features that can reach the Internet (ntfy
notifications, Shelly Cloud fallback, firmware check) are **off by default**, explained in the
app, and can be turned off at any time. See the
[original README](https://codeberg.org/kapoue/Hestia) for the complete feature list.

## For developers

- `main` mirrors the original repository; `fork/main` = original + fork features (default branch).
- Fork code lives in new files where possible, so syncing with the original stays cheap.
- Releases are built and signed by GitHub Actions from `fork/*` tags:
  [release procedure](https://github.com/mezinster/Hestia/blob/fork/main/docs/fork/RELEASING.md).
- A read-only mirror is kept at [codeberg.org/mezinster/Hestia](https://codeberg.org/mezinster/Hestia).

## License

[GPLv3](https://github.com/mezinster/Hestia/blob/fork/main/LICENSE), like the original.
