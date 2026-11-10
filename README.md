# Nuvio TV Installer for Samsung TVs (Android)

An Android app that installs and updates [Nuvio TV](https://github.com/NuvioMedia/NuvioTVSmart) on a Samsung Tizen TV straight from your phone. No PC, no Tizen Studio.

It does the same job as the Samsung part of the desktop [Nuvio TV Installer](https://github.com/NuvioMedia/NuvioTVSmart-Installer), on Android.

> **Unofficial.** This is a community project. It is not made or endorsed by NuvioMedia or Samsung.

**LG TVs:** not supported yet. Coming soon.

## Download

Get the APK from the [Releases page](../../releases) and open it on your phone. Android asks you to allow installing apps from your browser or file manager the first time.

Requires Android 8.0 or newer.

## What you need

- A Samsung smart TV running Tizen, and its remote
- The phone and the TV on the same Wi-Fi (not a guest network)
- A Samsung account (free). You sign in once, in your browser.

## First-time setup (about 10 minutes)

The app walks you through this in its built-in setup guide. The steps are:

1. **Turn on Developer Mode on the TV.** Open **Apps**, press `1 2 3 4 5` on the remote, switch Developer Mode **On**, and type your phone's IP (the app shows it) as **Host PC IP**.
2. **Restart the TV at the plug.** Unplug it from power, wait 5 seconds, plug it back in. Turning it off with the remote is not enough.
3. **Enter the TV's IP in the app.** On the TV: Settings › General (or Connection) › Network › Network Status › IP Settings. The app checks the connection and tells you what to fix if it fails.
4. **Tap Install.** The app downloads Nuvio, opens the Samsung sign-in once, signs the package for your TV and installs it.

## Updating

When a new Nuvio version is out, the app sends a notification. Tap it and the TV is updated. You can also open the app and tap **Update**.

Updates do not need the Samsung sign-in again.

## Your signing certificate

Samsung TVs only accept an update signed with the same certificate as the first install. The app creates that certificate during the first install and keeps it on your phone.

- A backup (`nuvio-tizen-certificates.zip`) is saved to your phone's **Downloads** folder automatically when the certificate is created.
- Keep that file. If you lose the certificate, you must uninstall Nuvio from the TV before you can install it again.
- Already installed Nuvio with the desktop installer? Import its certificate file (`samsung-tv-certificate-….json`) with **Import** in the app.
- The backup contains private keys. Do not share it.

## Troubleshooting

Tap the refresh button next to the TV IP. The app checks each of these and shows a fix for the first one that fails:

| Check | Usual fix |
| --- | --- |
| Phone has a Wi-Fi address | Connect the phone to the same Wi-Fi as the TV |
| TV IP is valid and on the same network | Re-read the IP from the TV's network settings |
| A Samsung TV answers at that IP | Turn the TV on; check the IP |
| Developer Mode is on | Apps › `1 2 3 4 5` › Developer Mode On, then restart at the plug |
| Host PC IP matches the phone | Change Host PC IP to the phone's current IP, then restart at the plug |

The most common cause of "it stopped working" is the phone getting a new IP from the router. The TV only accepts connections from the Host PC IP you typed. Reserving a fixed IP for your phone in the router settings avoids this.

The **Activity log** at the bottom of the app shows every command and the TV's replies. Copy it into a bug report.

## Privacy

- The Samsung sign-in happens on Samsung's website in your browser. The app receives an access token from it, uses it to request the certificate for your TV from Samsung, and does not store your password.
- Certificates stay on your phone (and in the backup file you control).
- The app talks to: your TV, GitHub (to list and download Nuvio releases), and Samsung (sign-in and certificates). There is no analytics or tracking.

## How it works

1. Downloads the Nuvio `.wgt` from GitHub releases and verifies its SHA-256.
2. Connects to the TV over SDB (port 26101), reads the TV's DUID, Tizen version and installed apps.
3. Creates a Samsung author and distributor certificate for that DUID (first install only).
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

Issues and pull requests are welcome. For a bug, include the activity log and your TV model and year. Run `./gradlew :core:test` before opening a pull request.

## Credits

- [NuvioMedia](https://github.com/NuvioMedia) for Nuvio TV and the desktop installer whose Samsung flow this app follows.
- The Nuvio name and logo belong to NuvioMedia.
- Samsung, Tizen and Galaxy are trademarks of Samsung Electronics.

## License

[GNU General Public License v3.0](LICENSE)
