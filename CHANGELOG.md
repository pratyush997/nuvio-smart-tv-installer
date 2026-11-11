# Changelog

All notable changes to this project are listed here. Versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- **Find my TV**: scans the phone's Wi-Fi for Samsung TVs and lists each one with its name, model and whether it is ready (Developer Mode on, Host PC IP set to this phone). Tap a TV to use it. On first start, a single TV found is selected automatically.
- The app remembers your TV. When the router gives the TV a new IP, the check finds it again on the network and switches to the new address by itself.
- When the phone's IP no longer matches Host PC IP on the TV, "How to fix it" now also suggests reserving the phone's IP in the router, with a link to the router's page, so it does not happen again.
- The certificate card shows how long your TV's certificate is valid. From 30 days before it expires, the card and a notification warn you and explain what it means for Nuvio updates.
- Backups can be protected with a password (certificate card › **Add password**). Protected backups are encrypted (AES-256-GCM) and saved as `nuvio-tizen-certificates.nuviobackup`; importing one asks for the password.
- Before uploading, the app checks the saved certificate. Expired certificates are skipped. If Nuvio is on the TV and the only certificate for it has expired, the app says so and explains the fix (uninstall, then install again) instead of failing on the TV. If the certificate has never installed Nuvio on this TV, the activity log warns that the TV may refuse the update.
- When the TV refuses an update because Nuvio was installed with a different certificate, the app now offers **Import certificate** and **Uninstall from TV** instead of a generic error.
- **Several TVs, one sign-in**: after you sign in with Samsung, certificates for other TVs created within the next hour use the same sign-in. The sign-in is kept in memory only, never saved to the phone. If Samsung no longer accepts it, the app asks you to sign in again. While a sign-in is kept, the certificate card shows "Samsung sign-in saved · expires in N min", with **Forget** to drop it right away.
- **Tizen Studio certificates**: if you would rather not sign in with Samsung on the phone, create a Samsung certificate in Tizen Studio's Certificate Manager, then tap **Import** and select both `author.p12` and `distributor.p12`. The app asks for the profile's password and saves the certificate for every TV (DUID) it lists.
- **Update Nuvio automatically** (TV card, off by default): when a new Nuvio version is out, the twice-daily check installs it on the TV by itself, if the TV is on, the phone is on the same Wi-Fi and the TV's certificate is saved. It never opens the Samsung sign-in. You get a notification after each update; if it cannot update, you get the usual "tap to update" notification with the reason.
- **What's new**: the TV card shows the release notes of the Nuvio version you picked (first points, with **Show all** and a link to GitHub). The "new version" notification lists the first changes too.
- **Beta versions** (in the version menu, off by default): offers Nuvio pre-releases and treats the newest one as the latest, for the version picker, update notifications and automatic updates. With it off, pre-releases are no longer listed.
- **Type on TV** (TV card): type into the TV's on-screen keyboard from the phone, for searching in Nuvio or filling in sign-in fields. The first time, the TV asks whether to allow TV Installer; choose Allow. This uses the TV's remote control connection, so it works without Developer Mode.
- **Report a problem** (About popup, and on every error): shows a report with the app version, phone, TV and the recent activity log, with TV IDs, tokens and e-mail addresses removed. Check it, then open it as a filled-in GitHub issue or copy it.

## [1.3.1] - 2026-11-10

### Added
- **About** popup, opened from the About button at the top of the main screen: version, licence, author (Pratyush), and links to the GitHub repo and to report a problem.

### Changed
- The header now reads "TV Installer · for Nuvio", with compact **Guide** and **About** buttons that fit on phones.
- The setup guide opens the first time the app starts, for everyone (it can be skipped).
- The version tiles show just the version number, so it is no longer cut off on phones.

### Fixed
- The app could close by itself when opened without internet (the update check failed).

## [1.3.0] - 2026-11-10

### Added
- The app tells you when a new version of **TV Installer** itself is out: a banner in the app and a notification, with a direct download.
- Setup guide starts by asking which TV you have (Samsung, or LG: coming soon).
- New setup step, "Sign in with Samsung", explaining in plain words why the TV needs a certificate and what happens during the first install. Users of the desktop installer can import their certificate there.
- Live activity during an install, in plain words, with "Show details" for the technical log.
- App version shown at the bottom of the main screen.
- **Sign in with Samsung** button on the certificate card: create your TV's certificate before installing.

### Changed
- "How to fix it" is now a short reason plus numbered steps, with IP addresses highlighted.
- Install progress and results now appear at the top of the screen instead of below the TV card.

### Fixed
- TV names with quotes showed as `65&quot; Crystal UHD`.
- In an Android emulator (MSI App Player, BlueStacks), the address check told you to type the emulator's 10.0.2.x address on the TV. It now tells you to use your computer's Wi-Fi IP.

## [1.2.0] - 2026-11-10

### Changed
- New name: **TV Installer for Nuvio** ("TV Installer" under the home-screen icon).
- New original app icon (a phone sending to a TV), with a themed icon for Android 13+.
- App colours now match the new icon.

### Removed
- Nuvio's logo and wordmark. The app no longer uses NuvioMedia's artwork.

## [1.1.0] - 2026-11-10

### Added
- Setup guide in the app: five short screens from Developer Mode to the first install. Opens on first launch and from **Setup guide** later.
- Address checks: the app checks the phone's IP, the TV's IP, Developer Mode and Host PC IP, and shows what to fix.
- Automatic certificate backup to the phone's Downloads folder when a certificate is created.
- Notification when a new Nuvio version is out. Tapping it updates the TV.
- "LG TV · coming soon" label.

### Changed
- Restart instructions now say to unplug the TV, wait 5 seconds and plug it back in.
- The TV IP field starts empty instead of using a fixed address.

## [1.0.0] - 2026-11-09

### Added
- First version: install, update, launch and uninstall Nuvio TV on a Samsung Tizen TV from an Android phone.
- Samsung sign-in and certificate creation, Tizen package signing, upload over SDB.
- Import of certificates from the desktop Nuvio TV Installer; certificate backup and restore.

[1.3.1]: https://github.com/pratyush997/nuvio-smart-tv-installer/releases/tag/v1.3.1
[1.3.0]: https://github.com/pratyush997/nuvio-smart-tv-installer/releases/tag/v1.3.0
[1.2.0]: https://github.com/pratyush997/nuvio-smart-tv-installer/releases/tag/v1.2.0
[1.1.0]: https://github.com/pratyush997/nuvio-smart-tv-installer/releases/tag/v1.1.0
