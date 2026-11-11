# Handoff: TV diagnostics (specs, vitals, speed test, stream stats)

Status: **idea approved, nothing built.** Date: 2026-11-11. Base commit: `edd9839` (1.3.1).

## Goal

Add to the Android app:

1. **TV specs box**: model, firmware, resolution, network type, Tizen version, Developer Mode IP.
2. **Vitals**: CPU load and free RAM.
3. **Speed test run on the TV**, with the result shown on the phone.
4. **Replay the last played stream on the TV**, with playback stats shown on the phone (bitrate, resolution, dropped frames, buffering).

## Start here (blocking spike, ~30 min)

Features 2 to 4 all need JS running inside Nuvio on the TV. Prove that this works before building anything else.

1. Add a CLI command `debug <ip>` in `cli/src/main/kotlin/tv/nuvio/installer/cli/Main.kt`.
2. In `core`, connect over SDB, find the Nuvio app id the same way `TizenInstaller.launch()` does, then run `shell:0 debug <appId>`. Log the raw output; it should include a port number.
3. Open an SDB stream to `tcp:<port>`. `SdbClient.openStream()` is private. Add a public method that returns a raw duplex stream (read and write bytes).
4. Send `GET /json HTTP/1.1` over that stream. Expect a JSON list with a `webSocketDebuggerUrl` (Chrome DevTools Protocol).
5. Do a WebSocket upgrade on a second `tcp:<port>` stream and send `Runtime.evaluate` with `navigator.userAgent`.

**Pass:** the user agent string comes back. **Fail:** record the exact `0 debug` output and the TV model/Tizen version, then go to "Fallbacks".

Run it on the user's TV: `NUVIO_CLI_DATA=cli-data ./gradlew :cli:run --args="debug <tv-ip>"`. A signing identity for that TV must already be in `cli-data/identities` (use `import`).

## Building blocks already in the codebase

| Need | Where |
|---|---|
| SDB connection + shell | `core/.../SdbClient.kt` (`connect`, `shell`, private `openStream` at ~L220) |
| Find Nuvio app id | `TizenInstaller.findApp(client, NUVIO_IDS)`, `launch()` at ~L306 |
| Tizen version | `TizenInstaller.readPlatformVersion()` runs `0 capability` |
| Port 8001 device info | `TvNetwork.fetchStatus()` / `parseStatus()`, `SamsungTvStatus` (only name, model, developerMode, developerIp, id so far) |
| Phone UI | `app/.../InstallerScreen.kt`, `InstallerViewModel.kt` |

## Plan after the spike passes

| # | Work | Estimate | Notes |
|---|---|---|---|
| 1 | Specs box | ~half a day | Extend `SamsungTvStatus` with more port 8001 `device` fields (check what the TV actually returns: e.g. `resolution`, `networkType`, `OS`, `firmwareVersion`). Add `0 capability` fields. Does **not** depend on the spike; can ship alone. |
| 2 | `TvDebugSession` in core | ~half a day | Starts debug launch, port stream, minimal WebSocket client (no new dependency if possible), `evaluate(js): String`. |
| 3 | Vitals | ~2 h | JS: `tizen.systeminfo.getPropertyValue("CPU")` and `("MEMORY")`. Temperature is not exposed; leave it out. |
| 4 | Speed test | ~2 h | JS: time a `fetch` of a fixed-size file and report Mbps. Pick a test URL that is stable and allowed by the app's `config.xml` access policy. |
| 5 | Last stream + stats | ~1 day | Inspect Nuvio's `localStorage` / IndexedDB to find the last played item. Start playback, then read stats from `webapis.avplay` (`getCurrentStreamInfo`, etc.) or `<video>.getVideoPlaybackQuality()`, depending on which player Nuvio uses. |

## Risks

1. **`0 debug` may be blocked** on some retail firmware. The spike decides this.
2. **Feature 5 depends on Nuvio internals** (storage keys, player). It can break on any Nuvio release. Put all Nuvio-specific JS in one file with a version note.
3. **Debug launch restarts Nuvio** on the TV. Warn the user in the UI.

## Fallbacks if the spike fails

1. Ship feature 1 (specs box) only; it uses no debug access.
2. Ask the Nuvio maintainers (NuvioMedia/NuvioTVSmart) for a diagnostics hook in the app.
3. Not recommended: inject a script into the WGT before signing. It changes the upstream app, which users do not expect.

## Working tree note

Uncommitted changes unrelated to this feature exist at handoff time: `core/.../SigningIdentity.kt` (modified), `CertificateExpiryTest.kt` and `TestCerts.kt` (new). Do not mix them into this feature's commits.
