# Hestia (test) — tester guide

[English](#english) · [Русский](#русский)

## English

### What this is

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

### Install

1. Open <https://github.com/mezinster/Hestia/releases/latest> on your phone.
2. Under **Assets**, download `hestia-<version>.apk`.
3. Open the file. If Android asks, allow installing apps from your browser or file manager.

The app appears as **« Hestia (test) »**. It installs **next to** the official Hestia from F-Droid:
both can live on the same phone, each with its own devices and settings.

### Update

Download the newer APK from the same link and install it over the old one. Your devices and settings
are kept. There are no automatic updates: check the link from time to time, or watch the repository's releases
on GitHub (Watch → Custom → Releases).

### If installation is blocked

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

### Android 17

The first time Hestia contacts a device, Android asks for permission to reach **devices on your
local network**. Hestia needs it to talk to your Shelly devices; it only contacts the IP addresses
you enter.

### Verify the download (optional)

- Each APK has a `.sha256` file next to it: `sha256sum -c hestia-<version>.apk.sha256`.
- The APK is signed with this certificate (SHA-256), which you can check with an app such as
  AppVerifier:

  ```
  17:69:2C:2F:A7:AF:58:D1:8E:90:54:B9:71:4E:B0:34:E3:86:68:B4:B5:B9:F5:45:80:4B:64:90:91:FA:DD:68
  ```

### Report a bug

Open an issue at <https://github.com/mezinster/Hestia/issues> with:

- your Android version and phone model;
- the Shelly model (and generation, if you know it);
- what you did, what you expected, what happened;
- the diagnostic log, if you're comfortable sharing it: on the **Dashboard**, tap the title at the
  top **5 times quickly** to open **Diagnostic log**, then **Share**. It contains no passwords, but it
  does contain your **local IP addresses and device names**, and an issue is **public**. Look through
  it before posting and remove anything you don't want to show, or ask in the issue for another way
  to send it.

---

## Русский

### Что это

**Неофициальная тестовая сборка** [Hestia](https://codeberg.org/kapoue/Hestia) — свободного
приложения для Android, которое управляет устройствами Shelly в твоей локальной сети (без облака и
без аккаунта). В этом форке есть функции, которых (пока) нет в официальном приложении:

- русский перевод;
- понятное сообщение, если устройством нельзя управлять (шторы, датчик, счётчик электроэнергии…);
- диммеры (устройства `light`): вкл/выкл и яркость.

Работает на **Android 10–17**.

> Независимость: Hestia — независимый проект, никак не связанный с Shelly или Allterco Robotics.
> Он не заказан, не спонсирован и не одобрен Shelly. Shelly — зарегистрированный товарный знак
> своего владельца; он упоминается только для указания технической совместимости.

### Установка

1. Открой на телефоне <https://github.com/mezinster/Hestia/releases/latest>.
2. В разделе **Assets** скачай `hestia-<версия>.apk`.
3. Открой файл. Если Android спросит, разреши установку приложений из браузера или файлового
   менеджера.

Приложение называется **«Hestia (test)»**. Оно ставится **рядом** с официальной Hestia из F-Droid:
обе версии могут быть на одном телефоне, у каждой свои устройства и настройки.

### Обновление

Скачай более новый APK по той же ссылке и установи поверх старого. Устройства и настройки
сохранятся. Автоматических обновлений нет: время от времени заглядывай по ссылке или подпишись на
релизы репозитория на GitHub (Watch → Custom → Releases).

### Если установка блокируется

- **«Приложение заблокировано для защиты устройства» (Google Play Защита):** Play Защита
  предупреждает о любом приложении от разработчика, которого она ещё не знает, — это не значит, что
  с приложением что-то не так. Нажми **Подробнее → Всё равно установить**. Если такого варианта нет:
  Play Маркет → значок профиля → **Play Защита** → ⚙️ → временно выключи **Проверять приложения с
  помощью Play Защиты**, установи приложение и снова включи проверку.
- **«Приложение не установлено»:**
  - убедись, что открываешь **самый новый** файл (`hestia-<версия>.apk` из последнего релиза):
    Android не ставит *более старую* версию поверх новой;
  - если ошибка остаётся, а «Hestia (test)» уже установлена, удали её (Настройки → Приложения) и
    установи заново — устройства придётся добавить ещё раз;
  - проверь, что файл скачался полностью (около 5 МБ) и на телефоне есть свободное место.

Официальная Hestia из F-Droid при этом не затрагивается: это отдельное приложение.

### Android 17

Когда Hestia впервые обращается к устройству, Android запрашивает разрешение на доступ к
**устройствам в локальной сети**. Оно нужно, чтобы общаться с твоими Shelly; Hestia обращается только
к тем IP-адресам, которые ты ввёл.

### Проверка загрузки (необязательно)

- Рядом с каждым APK лежит файл `.sha256`: `sha256sum -c hestia-<версия>.apk.sha256`.
- APK подписан этим сертификатом (SHA-256), его можно проверить, например, приложением AppVerifier:

  ```
  17:69:2C:2F:A7:AF:58:D1:8E:90:54:B9:71:4E:B0:34:E3:86:68:B4:B5:B9:F5:45:80:4B:64:90:91:FA:DD:68
  ```

### Сообщить об ошибке

Создай issue на <https://github.com/mezinster/Hestia/issues> и укажи:

- версию Android и модель телефона;
- модель Shelly (и поколение, если знаешь);
- что ты сделал, что ожидал и что произошло;
- журнал диагностики, если ты готов им поделиться: на экране **Панель** быстро нажми **5 раз** на
  заголовок вверху — откроется **Журнал диагностики**, затем **Поделиться**. В нём нет паролей, но
  есть твои **локальные IP-адреса и названия устройств**, а issue видны **всем**. Просмотри журнал
  перед публикацией и убери то, что не хочешь показывать, или попроси в issue другой способ его
  передать.
