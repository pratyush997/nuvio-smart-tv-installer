# Changelog

All notable changes to this project are listed here. Versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- **Find my TV**: scans the phone's Wi-Fi for Samsung TVs and lists each one with its name, model and whether it is ready (Developer Mode on, Host PC IP set to this phone). Tap a TV to use it. On first start, a single TV found is selected automatically.

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
