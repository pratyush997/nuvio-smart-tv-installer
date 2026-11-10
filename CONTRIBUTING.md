# Contributing

Thanks for helping. Bug reports, fixes and new TV support are all welcome.

## Reporting a bug

Open an [issue](../../issues/new/choose) and include:

- The app version (shown on the release you installed)
- Your phone model and Android version
- Your TV model and year (on the TV: Settings › Support › About This TV)
- The **Activity log** from the app (tap **Copy** in the log card)

Remove anything private from the log before posting. The log shows IP addresses on your home network and your TV's ID (DUID).

Never post your certificate file (`samsung-tv-certificate-*.json` or `nuvio-tizen-certificates.zip`). It contains private keys.

## Pull requests

1. Fork the repo and create a branch from `main`.
2. Keep the change focused on one thing.
3. Run the tests: `./gradlew :core:test`
4. Build the app: `./gradlew :app:assembleDebug`
5. Test on a real phone and TV if your change touches installing, signing or the TV connection, and say in the pull request which TV you tested on.

### Code

- Kotlin, official code style (`kotlin.code.style=official`).
- Installer logic belongs in `core` (plain Kotlin/JVM, no Android). The Android UI belongs in `app`.
- New source files start with the licence header:
  ```kotlin
  // SPDX-License-Identifier: GPL-3.0-only
  // Copyright (C) 2026 Pratyush (pratyush997) and contributors
  ```
- Text shown to users should say what to do next, in plain words.

## Licence

By contributing, you agree that your contribution is licensed under the [GNU General Public License v3.0](LICENSE), the same as the rest of the project.
