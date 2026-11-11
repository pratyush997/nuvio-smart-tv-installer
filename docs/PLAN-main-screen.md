# Plan: main screen redesign and install checks

Status: built on branch `feat/revamp` (2026-11-11), phases 1–4 done. Phase 5 (test on the TV) not done yet.

Each app gets its own card, only one gradient button shows at a time, and the bottom bar is pinned only while a job runs. Every install or update runs a step-by-step checklist first.

## Before starting

**0. Commit the other session's work first.** Done: it is commit `9b86596`. Only this plan and `InstallReadiness` (with its tests) are uncommitted.

## Final screen

```
TV Installer                         Guide  About
┌───────────────────────────────────────────────┐
│ 📺 Living Room TV   ● Connected    Switch ▾  + │
└───────────────────────────────────────────────┘
[ Getting ready… checklist / progress / result ]   ← only when there is one
┌───────────────────────────────────────────────┐
│ Nuvio TV                         Recommended  │
│ 1.3.10 on TV · 1.4.0 is out                   │
│ [        Update to 1.4.0        ]              │
│ Open on TV · What's new · Version ▾        ⋯  │
│ Auto-update  ◯                                 │
└───────────────────────────────────────────────┘
┌───────────────────────────────────────────────┐
│ Nuvio Legacy                   Community app  │
│ Not on this TV                                │
│ [ Install ]                                ⋯  │
└───────────────────────────────────────────────┘
Type on TV ›    Certificate & backup ›    Activity ›
```

**Rules:**
- **One gradient button**, picked in this order:
  1. Find my TV
  2. Check again
  3. Update Nuvio TV
  4. Open the installed app (Nuvio TV first, then Legacy)
  5. Install Nuvio TV, only when neither app is on the TV

  So a TV with only Legacy gets **Open Nuvio Legacy**, not a push to install the other app. Every other action is a soft button or a text link.
- **App cards when the TV is off:** greyed, with "Connect your TV first".
- **Legacy card:**
  - If the TV is too old, it shows "Needs a 2018 or newer TV" and no button.
  - The first tap on Install shows the existing Legacy notice.
- **⋯ menu on each card:** Reinstall, Uninstall, Choose a file.
- **Auto-update:** a toggle in the Nuvio TV card only.
- **Bottom bar:** shown only during checks or an install: "Check 3 of 7 · Developer Mode on", then "Step 4 of 5 · Upload".

## Install checks

Tapping Install or Update on either app card runs these checks in order, one by one with a spinner. The checks stop at the first failure and show how to fix it; the bottom button then reads **Check again** (or **Find my TV** when no TV is picked). When all pass, the install starts without another tap.

- **What ticks at once:** only row 1 (Wi-Fi) and row 6 when the release is already loaded. Rows 2–5 always ask the TV again (about 1–2 s): the TV may have been turned off since the last check, and an old result would let the install fail halfway through the upload.
- **One job at a time:** the checks and the install that follows count as one job. Nothing else (Check TV, a second tap, a notification update) can start in between.

| # | Check | Nuvio TV | Nuvio Legacy |
|---|---|---|---|
| 1 | Phone on Wi-Fi | same | same |
| 2–5 | TV found, Developer Mode on, Host PC IP is this phone, TV accepts this phone | same | same |
| 6 | File for this TV | Release has a .wgt | TV's Tizen is 4 or newer, and the release has a .tpk for it (or a .wgt on Tizen 5.5+) |
| 7 | Samsung sign-in | Looks for Nuvio TV's existing install | Looks for Legacy's existing install |

- Row 6 comes after row 5: only a working connection gives the TV's Tizen version, which Legacy needs to choose its file. A file the user picked passes row 6; the installer checks what's inside it.
- Row 7 passes when this TV already has a saved certificate. With none, it shows an info icon ("Samsung's page opens during the install") and the install does the sign-in. When the TV needs a certificate this phone doesn't have, the existing "Certificate needed" card appears (Import certificate / Uninstall from TV). Row 7 is worked out from what rows 2–5 already read (the TV's DUID and installed apps), with no extra connection.

## Build phases

### Phase 1: Core logic and tests (about 1 h)

1. **[InstallReadiness.kt](../core/src/main/kotlin/tv/nuvio/installer/core/InstallReadiness.kt)** (already written, with 8 passing tests in [InstallReadinessTest.kt](../core/src/test/kotlin/tv/nuvio/installer/core/InstallReadinessTest.kt)):
   - Rename the version check to `PACKAGE`.
   - Add `wifiRow(phoneIp)` for row 1.
   - Add `packageRow(app, release, isFile, tizen)`, built on `packageFor`.
   - Titles and `signInRow` take the app ("Nuvio Legacy file for this TV").
2. **Public preflight in [TizenInstaller.kt](../core/src/main/kotlin/tv/nuvio/installer/core/TizenInstaller.kt):** add `preflight(duid, installed, app)`, built on the private `validCandidates`. Row 7 calls it with the DUID and installed app from `tvInfo`, so it needs no connection.
3. **New `MainAction.pick(…)`:** a pure function that decides which button is highlighted (FindTv, CheckAgain, Update(app), Open(app), Install(app)) from the TV state and both apps' states, in the order under **Rules**.
4. **Tests:**
   - Wi-Fi row fails with no phone IP.
   - Legacy on Tizen 3 fails, on Tizen 6.5 gets the 65 build, and Nuvio TV passes on any Tizen.
   - The highlight order holds for each state in the rules above, including: only Legacy on the TV gives Open(LEGACY); neither app gives Install(TV).

### Phase 2: View model (about 2 h)

5. **Replace the single `selectedApp` / `source`** with one selected version per app (`sources: Map<NuvioApp, PackageSource>`).
   - `install`, `launchOnTv`, `uninstall` and `createCertificate` take the app.
   - Everything that reads `selectedApp` takes the app instead: `releases(app)`, `releasesError(app)`, `latestRelease(app)`, `buttonLabel(app)` (was `primaryLabel`), `isInstalled(app)` and `installedLabel(app)` (were `selectedInstalled*`). That is about 21 places in the Screen and 4 in [SetupGuide.kt](../app/src/main/java/tv/nuvio/installer/app/SetupGuide.kt).
   - `loadReleases` sets the default version for each app as its releases arrive (today it only does so for the selected app).
   - The per-TV saved app choice is ignored; the field stays so old saved data still loads.
6. **Split two functions into waitable versions:**
   - `checkTv()` gets a waitable `probeAndReport()` holding **all** of today's `checkTv` body, including the rescan that finds a TV the router moved to a new address. Otherwise Install would stop at row 2 where Check again would have found the TV.
   - `loadReleases(app)` gets a waitable `fetchReleases(app)`.
7. **Make the checks and install one job:**
   - `run()` gets a suspend version that does the work without taking or releasing `busy`.
   - `install(app)` takes `busy` once, runs the checks, then calls the suspend version. `busy` is released only at the end.
   - A failed install no longer starts `checkTv()` on its own; the checklist already shows what is wrong. Launch, uninstall and sign-in keep that behaviour.
8. **Add the checklist state:** `checks`, `checkFix`, `checkAction`.
   - `install(app)` runs the 7 checks one by one, about 250 ms apart. Rows 1 and 6 tick at once when they can (see **Install checks**); rows 2–5 always ask the TV.
   - Row 7 uses `preflight(duid, installed, app)` with what rows 2–5 read.
   - It stops at the first failure. When all pass, the existing install runs.
9. **Add `mainAction`**, read from `MainAction.pick`.
10. **Remove the extra connection** that `install()` makes to read the Tizen version. The checks already have it.

### Phase 3: Screen (about 1.5–2 h)

11. **TV card becomes compact:**
   - Name, status dot, **Switch ▾** (opens the saved-TV list), and **+**.
   - The IP field and Find my TV show only when there's no TV, or behind a "Change address" link.
   - When the TV is off: the fix steps, **Check again** and a "Setup guide" link.
12. **New app card** (used for both apps):
    - Status line, one button, links (Open on TV, What's new, Version ▾), and the ⋯ menu.
    - The version picker and file picker move here from `VersionTile`.
13. **New checklist card** at the top, plus one fix box (`FixBox`) shared with `IpReportView`.
14. **Bottom bar shows progress only.** The fixed Install button is removed.
15. **"More" row:** Type on TV (only when connected), Certificate & backup (the existing card, collapsed), Activity (the existing log).
16. **Remove** `AppChoice`, the `InfoTile` pair, and the old Open/Uninstall row.

### Phase 4: Wiring and build (about 30 min)

17. **Setup guide and notifications:**
    - The guide's last step calls `install(NuvioApp.TV)`, and its button text and version read `buttonLabel(NuvioApp.TV)` and Nuvio TV's version (today `vm.primaryLabel` and `vm.source`).
    - Notification updates call `install(NuvioApp.TV)` on that TV.
18. Add a [CHANGELOG.md](../CHANGELOG.md) line, and update the main-screen text in the README if it describes the button.
19. Run `:core:test`, build the debug APK, and fix any build errors.

### Phase 5: Test on the TV (about 25 min)

| # | Setup | Expected |
|---|---|---|
| 1 | Everything ready, Nuvio TV installed | Gradient button is **Open on TV** or **Update**; Legacy shows a soft **Install** |
| 2 | Developer Mode off | Checklist stops at row 3 with the fix; button reads **Check again** |
| 3 | Host PC IP set to another device | Stops at row 4, showing both IPs |
| 4 | Install Legacy on the 2023 TV (Tizen 7) | Notice shows, checks pass, the 65 build installs |
| 5 | TV unplugged | TV card is red, app cards greyed, **Check again** |
| 6 | Check the TV, unplug it, then tap Update | Stops at row 2 (rows 2–5 ask the TV again), not partway through the upload |
| 7 | Double-tap Install, or tap Check TV during the checks | Only one job runs |

Type on TV (built in commit `4bb1dd3`) has never been tried on the real TV; test it in the same session.

## Scope

- **Total:** about 5–6 h of building, plus a 25-minute TV test.
- **Biggest risk:** Phase 3. [InstallerScreen.kt](../app/src/main/java/tv/nuvio/installer/app/InstallerScreen.kt) is about 1,700 lines and most of it changes. Phases 1–2 leave the screen mostly as it is and can be tested on their own.
- **Not in scope:** LG, TV diagnostics (parked, see [HANDOFF-tv-diagnostics.md](HANDOFF-tv-diagnostics.md)), any change to how installs work once the checks pass.
- **Commits:** none until asked.
