# Changelog — Hestia fork test builds

**English** · [Français](CHANGELOG.fr.md) · [Русский](CHANGELOG.ru.md)

Changes in each unofficial test build of this fork, written for testers. The release notes of a
`fork/<version>` tag publish the section of the same name (`## <version>`); without one, they fall
back to the list of commits.

## 2.18.0-fork.3

Installs over 2.16.1-fork.2 (same "Hestia (test)" app, your devices and settings are kept). Brings
the fork up to the features of upstream Hestia 2.18.0, rewritten for the fork and extended to dimmers
and shutters where it makes sense.

### Devices that require HTTPS (new)

- **Recent Shelly devices that refuse plain HTTP** (firmware 2.0 and later, out of the box) can now be
  added. Hestia detects it, shows the device's certificate fingerprint and asks you to trust it
  once; every exchange after that checks it is still the same certificate.
- **If the certificate changes**, Hestia stops talking to the device and its tile shows
  "Certificate changed". Edit device → **Encrypted connection (HTTPS)** shows the certificate and
  lets you forget it.

### Password-protected devices (new)

- **Adding a protected device** asks for its password. It is stored encrypted on the phone, per
  device, and never goes into backups or the diagnostic log.
- Edit device → **Device password**: change or remove it. The tile says "Password required" or
  "Password refused" when something is wrong.

### Smoke detectors and protected devices

- A smoke alarm calls other devices in plain HTTP, without any password. So a device that needs a
  password or HTTPS **can no longer be chosen as an alarm cut-off target or as the ntfy relay**: it
  is greyed out in the list with the reason, and typing its address by hand is refused.
- If a device already in a detector's cut-off list becomes protected, the detector's tile shows
  **"Alarm cut-off to review"** until you uncheck it.
- A password-protected smoke detector still reports its alarm and still switches off the open
  devices you chose.

### Choose notifications per device (new)

- Edit device → **Notifications**: one switch per kind.
  - plugs and power-strip channels: schedule start, schedule end, threshold cut-off, timer end,
    presence simulation;
  - dimmers: schedule start and end;
  - shutters: scheduled movements;
  - smoke detectors: none, their alerts are always notified.
- **Applied by the device itself** (it works with the phone off) and to local notifications.
  Switching a notification off never changes what the device does: cut-offs, timers and schedules
  keep working.
- A schedule with a threshold that is running at that moment is updated at the next app launch
  (a note in Edit device says so).

### Other improvements

- Threshold cut-off notification: the charging time now comes first.
- Schedules with a threshold set up by older versions are **upgraded automatically** at launch,
  never while one is running.
- Settings: a multi-channel device (power strip, multi-light dimmer) and its channels now show as
  **one card**.

### Fixes

- One-time (single date) schedules: the year is read correctly, a far-ahead date is no longer seen as
  past, and an expired one is deleted only once.
- Demo mode no longer sends anything to its fictitious addresses.
- Schedules no longer send an empty ntfy notification.

### Good to know

- HTTPS devices, passwords and per-device notifications have **not been tested on real hardware
  yet**. If you own such a device, your feedback is very welcome — see the
  [tester guide](https://github.com/mezinster/Hestia/blob/fork/main/docs/fork/TESTING.md).
- The app's local database is upgraded on first launch. Going back to 2.16.1-fork.2 afterwards
  requires uninstalling "Hestia (test)" first.

## 2.16.1-fork.2

Installs over 2.16.1-fork.1 (same "Hestia (test)" app, your devices and settings are kept).

### Roller shutters (new)

- **New device type "Shutter"** for Shelly devices in cover mode (e.g. Plus 2PM / Pro 2PM set to
  cover, Pro Dual Cover). One tile per shutter; a dual-cover device adds both at once.
- **Tile:** ▲ ■ ▼ buttons (open, stop, close) and the state always written out: "Open", "Closed",
  "Opening… 40 %", "Stopped at 40 %", "Calibrating…".
- **Detail screen:** big Open / Stop / Close buttons, a **position slider (0–100 %)** once the
  shutter is calibrated, a **Calibrate** button (with a safety confirmation), and readable fault
  messages (obstacle, overpower, safety switch…).
- **Schedules run by the shutter itself** (they keep working with the app closed or the phone off):
  - an event = a time + days of the week, or a single date → **Open**, **Close** or **Go to N %**
    (position only on calibrated shutters);
  - **"Add a window"** creates two events at once: open in the morning and close in the evening, or
    the reverse;
  - edit, pause / resume (a paused event is removed from the shutter and kept in Hestia), delete;
    up to 10 events per shutter; duplicates and past dates are refused with a clear message;
  - schedules created in the official Shelly app show up in Hestia too;
  - the **next event** is shown on the tile: "Closed · opens at 07:30", "Open · closes Mon 21:00".
- **Notifications** for scheduled events: local notifications (if enabled), and ntfy (if enabled —
  sent by the shutter itself). Turning ntfy off removes it from the shutter's schedules, including
  for a shutter that was offline at the time, as soon as it is back.
- **Demo mode:** two demo shutters ("Living Room Shutter", calibrated; "Bedroom Shutter", not
  calibrated) to try everything without hardware. Adding or pausing an event in demo mode changes
  nothing.

### Dimmers

- **Timer:** turn a dimmer off automatically after a delay, with a countdown on the tile.
- **Name synced with the device**, like plugs and relays (renaming in Hestia renames it on the
  device and in the Shelly app).
- **Multi-channel dimmers** (e.g. Pro Dimmer 2PM, or an RGBW in 4-light mode) are grouped on one Dashboard row, one
  circle per channel.
- **Schedules run by the dimmer itself**, like plug schedules: on/off time windows, the current
  schedule shown in Detail, "off for today" when you switch it off during a window, notifications.
- **Demo mode:** a 4-channel demo dimmer ("Kitchen Lights").

### Fixes

- Settings → Language: choosing "System language" again now shows the right selection (Android 13+).

### Good to know

- Shutters and dimmer schedules have **not been tested on real hardware yet**. If you own one, your
  feedback is very welcome — see the [tester guide](https://github.com/mezinster/Hestia/blob/fork/main/docs/fork/TESTING.md).
- The app's local database is upgraded on first launch. Going back to 2.16.1-fork.1 afterwards
  requires uninstalling "Hestia (test)" first.
- The tester guide now explains what to do when Play Protect or Android blocks the installation.

## 2.16.1-fork.1

First build of the new release pipeline. Runs on Android 10 to 17.

- **Russian translation** (the app is now available in English, French and Russian).
- **Device-kind detection** when adding a device: the app checks what the device actually exposes
  (relay, dimmer…) instead of trusting the chosen type; the plug type is now labelled "Plug / relay".
- **Dimmers (first version):** on/off and brightness from the tile and the Detail screen.
