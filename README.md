<img src="docs/icon.svg" width="96" alt="" align="right">

# TV Installer for Nuvio

**Samsung TVs · Android**

[![Latest release](https://img.shields.io/github/v/release/pratyush997/nuvio-smart-tv-installer)](https://github.com/pratyush997/nuvio-smart-tv-installer/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/pratyush997/nuvio-smart-tv-installer/total)](https://github.com/pratyush997/nuvio-smart-tv-installer/releases)
[![License: GPL-3.0-only](https://img.shields.io/badge/license-GPL--3.0--only-blue)](LICENSE)

An Android app that installs and updates [Nuvio TV](https://github.com/NuvioMedia/NuvioTVSmart) on a Samsung Tizen TV straight from your phone. No PC, no Tizen Studio.

It can also install **Nuvio Legacy**, an unofficial native rewrite by a community developer ([iqui27/nuvio-native-legacy](https://github.com/iqui27/nuvio-native-legacy)) that tends to run smoother on older TVs. Nuvio TV is the official app and the one we recommend. The two install side by side.

It does the same job as the Samsung part of the desktop [Nuvio TV Installer](https://github.com/NuvioMedia/NuvioTVSmart-Installer), on Android.

**LG TVs:** not supported yet. Coming soon.

## About

TV Installer for Nuvio is made by **Pratyush** ([@pratyush997](https://github.com/pratyush997)). It is free and open source under the GPL-3.0.

- Source code: https://github.com/pratyush997/nuvio-smart-tv-installer
- Download: [latest release](https://github.com/pratyush997/nuvio-smart-tv-installer/releases/latest)
- Report a problem: [issues](https://github.com/pratyush997/nuvio-smart-tv-installer/issues/new/choose)

## Download

Get the APK from the [latest release](https://github.com/pratyush997/nuvio-smart-tv-installer/releases/latest) and open it on your phone. Android asks you to allow installing apps from your browser or file manager the first time.

Requires Android 8.0 or newer.

## What you need

- A Samsung smart TV running Tizen, and its remote
- The phone and the TV on the same Wi-Fi (not a guest network)
- A Samsung account (free). You sign in once, in your browser.

## First-time setup (about 10 minutes)

The app walks you through this in its built-in setup guide. The steps are:

1. **Pick your TV.** Samsung is supported; LG is coming soon.
2. **Turn on Developer Mode on the TV.** Open **Apps**, press `1 2 3 4 5` on the remote, switch Developer Mode **On**, and type your phone's IP (the app shows it) as **Host PC IP**.
3. **Restart the TV at the plug.** Unplug it from power, wait 5 seconds, plug it back in. Turning it off with the remote is not enough.
4. **Pick your TV in the app.** Tap **Find my TV** and choose it from the list. Not listed? Type its IP: on the TV, Settings › General (or Connection) › Network › Network Status › IP Settings. The app checks the connection and tells you what to fix if it fails.
5. **Sign in with Samsung and install.** The app downloads Nuvio, opens the Samsung sign-in once so Samsung can create a certificate for your TV, signs the package and installs it. You can watch each step as it happens.

## Updating

When a new Nuvio TV version is out, the app sends a notification. Tap it and the TV is updated. You can also open the app and tap **Update Nuvio TV**. Updates do not need the Samsung sign-in again.

- **Update Nuvio automatically** (off by default): the app installs new versions by itself when the TV is on and on the same Wi-Fi. You get a notification after each update.
- **What's new** on the app's card shows the release notes for the version you picked.
- **Beta versions** are listed in the version menu, marked "beta". A TV on a full release only gets full releases. Install a beta, and that TV gets newer betas until you install a full release again.

Nuvio Legacy updates itself on the TV, so the app shows it as "Installed" without a version and doesn't send update notifications for it.

### More than one TV

Tap **+** on the TV card and follow the guide for the new TV. Every TV uses this phone's IP as Host PC IP. Switch between TVs with **Switch**. Update notifications come one per TV.

All your TVs share one certificate (up to 10 TVs), so Nuvio keeps updating on the TVs you already have. While your Samsung sign-in is saved (the certificate card shows when it expires), adding a TV needs no new sign-in.

When a new version of this app is out, it shows a banner and sends a notification. Tap **Download**, then open the downloaded file to update. Your TV setup and certificate are kept.

## Your signing certificate

Samsung TVs only accept an update signed with the same certificate as the first install. The app creates that certificate during the first install and keeps it on your phone.

- You can create it ahead of time with **Sign in with Samsung** under **Certificate & backup** (the TV must be connected), or let the first install do it.
- A backup (`nuvio-tizen-certificates.zip`) is saved to your phone's **Downloads** folder automatically when the certificate is created.
- Keep that file. If you lose the certificate, you must uninstall Nuvio from the TV before you can install it again.
- Already installed Nuvio with the desktop installer? Import its certificate file (`samsung-tv-certificate-….json`) with **Import** in the app.
- The backup contains private keys. Do not share it. To protect it, tap **Add password** under **Certificate & backup**: backups are then encrypted and saved as `nuvio-tizen-certificates.nuviobackup`. If you forget the password, the backup cannot be opened.
- The certificate card shows how long the certificate is valid. From 30 days before it expires, the app warns you.
- Prefer not to sign in on the phone? Create a certificate in Tizen Studio's Certificate Manager, tap **Import** and select both `author.p12` and `distributor.p12`.
- While a Samsung sign-in is saved, the card shows "Samsung sign-in saved · expires in …". Tap **Forget** to delete it right away.

## More in the app

- **Type on TV**: type into the TV's on-screen keyboard from your phone, for searching in Nuvio or filling in sign-ins. This works without Developer Mode. The first time, choose **Allow** on the TV.
- **Report a problem** (About, and on every error): makes a bug report with TV IDs, tokens and e-mail addresses removed. Check it, then open it as a GitHub issue or copy it.
- **Light mode**: follows the phone. The button next to **Guide** switches between Auto, Light and Dark.

## Troubleshooting

Every install or update runs these checks first, one by one, and stops at the first one that fails with a fix and **Check again**:

| Check | Usual fix |
| --- | --- |
| Phone has a Wi-Fi address | Connect the phone to the same Wi-Fi as the TV |
| TV IP is valid and on the same network | Re-read the IP from the TV's network settings |
| A Samsung TV answers at that IP | Turn the TV on; check the IP |
| Developer Mode is on | Apps › `1 2 3 4 5` › Developer Mode On, then restart at the plug |
| Host PC IP matches the phone | Change Host PC IP to the phone's current IP, then restart at the plug |
| There is a file for this TV | Nuvio Legacy needs a 2018 or newer TV (Tizen 4 or later); pick another version if a release has no file for your TV |
| Samsung sign-in | Nothing to do: Samsung's page opens during the install. If the TV needs a certificate this phone doesn't have, import it or uninstall Nuvio from the TV |

The most common cause of "it stopped working" is the phone getting a new IP from the router. The TV only accepts connections from the Host PC IP you typed. Reserving a fixed IP for your phone in the router settings avoids this; the app suggests it, with a link to the router's page, when it sees the phone's IP change.

The app remembers your TV. When the router gives the TV a new IP, the app finds the TV again and switches to the new address by itself.

**Activity** at the bottom of the app shows every command and the TV's replies. To report a bug, tap **Report a problem**: it includes the recent activity.

## Privacy

- The Samsung sign-in happens on Samsung's website in your browser. The app never sees your password. It keeps the sign-in until Samsung says it expires, encrypted with a key from the Android Keystore and never included in Android backups. **Forget** deletes it.
- Certificates stay on your phone (and in the backup file you control).
- The app talks to: your TV, GitHub (to list and download Nuvio TV and Nuvio Legacy releases, and to check for updates to this app), and Samsung (sign-in and certificates). There is no analytics or tracking.

## How it works

1. Downloads the Nuvio TV `.wgt` (or the Nuvio Legacy `.tpk` for your TV's Tizen version) from GitHub releases and checks its SHA-256 against the one GitHub lists.
2. Connects to the TV over SDB (port 26101), reads the TV's DUID, Tizen version and installed apps.
3. Creates a Samsung author certificate, plus a distributor certificate that lists all your TVs (first install, or when you add a TV).
4. Signs the package (Tizen XML-DSig), uploads it and runs the TV's installer.
5. Reads the installed app list back to confirm the version.

## Build from source

Requirements: JDK 17 and the Android SDK (API 35).

```sh
./gradlew :core:test            # unit tests for the installer core
./gradlew :app:assembleDebug    # debug APK in app/build/outputs/apk/debug/
./gradlew :app:assembleRelease  # release APK
```

Release signing: put a `keystore.properties` and your `.jks` file in a folder outside the repository and point `local.properties` at it:

```properties
# local.properties (not committed)
keystore.dir=C:/path/to/your/keystore-folder
```

```properties
# keystore.properties
storeFile=release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without a keystore, the release build is signed with the debug key.

### Project layout

| Module | What it is |
| --- | --- |
| `core` | Pure Kotlin/JVM installer: SDB client, Samsung login and certificates, Tizen signer, IP checks |
| `app` | The Android app (Jetpack Compose) |
| `cli` | Desktop command-line harness that runs `core` against a TV, for testing |

The CLI:

```sh
./gradlew :cli:run --args="probe 192.168.1.20"
./gradlew :cli:run --args="install 192.168.1.20 path/to/Nuvio.wgt"
```

The TV only accepts connections from its Host PC IP, so set that to your computer's IP when using the CLI.

## Contributing

Issues and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md). Report security problems privately, as described in [SECURITY.md](SECURITY.md).

Changes in each version are listed in [CHANGELOG.md](CHANGELOG.md).

## Credits

- [NuvioMedia](https://github.com/NuvioMedia) for Nuvio TV and the desktop [Nuvio TV Installer](https://github.com/NuvioMedia/NuvioTVSmart-Installer). The Samsung install flow in `core` (SDB commands, certificate requests, package signing) is a Kotlin port of that installer, which is published under the GPL-3.0.
- The Nuvio name belongs to NuvioMedia and is used only to say which app this installs. This project is not affiliated with or endorsed by NuvioMedia. The app icon is original to this project.
- Samsung and Tizen are trademarks of Samsung Electronics. LG and webOS are trademarks of LG Electronics.

## License

Copyright (C) 2026 Pratyush (pratyush997) and contributors.

This program is free software: you can redistribute it and/or modify it under the terms of the [GNU General Public License, version 3](LICENSE) (`GPL-3.0-only`), as published by the Free Software Foundation.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

Third-party libraries keep their own licences: AndroidX, Jetpack Compose and kotlinx (Apache-2.0), Bouncy Castle (MIT-style Bouncy Castle Licence). All are compatible with GPL-3.0.
