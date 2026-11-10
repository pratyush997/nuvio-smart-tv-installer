# Changelog

All notable changes to this project are listed here. Versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

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

[1.2.0]: https://github.com/pratyush997/nuvio-smart-tv-installer/releases/tag/v1.2.0
[1.1.0]: https://github.com/pratyush997/nuvio-smart-tv-installer/releases/tag/v1.1.0
