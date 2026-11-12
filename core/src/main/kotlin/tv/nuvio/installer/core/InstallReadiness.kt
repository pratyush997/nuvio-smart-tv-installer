// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

/** What is checked before an install starts, in order. Each check needs the ones before it to pass. */
enum class ReadyCheck {
    WIFI,
    TV,
    DEVELOPER_MODE,
    HOST_IP,
    CONNECTION,
    PACKAGE,
    SIGN_IN;

    /** The row's title in the checklist for installing [app]. */
    fun title(app: NuvioApp): String = when (this) {
        WIFI -> "Phone on Wi-Fi"
        TV -> "TV found"
        DEVELOPER_MODE -> "Developer Mode on"
        HOST_IP -> "Host IP is this phone"
        CONNECTION -> "TV accepts this phone"
        PACKAGE -> "${app.label} file for this TV"
        SIGN_IN -> "Samsung sign-in"
    }
}

/**
 * [PASS] and [FAIL] are what they say. [UNSURE]: the TV did not say, the next check decides.
 * [LATER]: not done yet, but the install does it (the Samsung sign-in).
 */
enum class ReadyState { PASS, FAIL, UNSURE, LATER }

data class ReadyResult(val check: ReadyCheck, val state: ReadyState, val detail: String = "")

object InstallReadiness {
    /** Row 1: the phone needs a Wi-Fi address to reach the TV. */
    fun wifiRow(phoneIp: String?): ReadyResult =
        if (phoneIp.isNullOrBlank()) ReadyResult(ReadyCheck.WIFI, ReadyState.FAIL, "Connect the phone to the same Wi-Fi as the TV")
        else ReadyResult(ReadyCheck.WIFI, ReadyState.PASS, phoneIp)

    /**
     * The TV rows (found, Developer Mode, Host IP, connection) from one TV check, stopping at
     * the first failure. [status] is what the TV's info port said (null: nothing answered),
     * [connected] whether the installer connection worked.
     */
    fun tvRows(status: SamsungTvStatus?, phoneIp: String?, connected: Boolean): List<ReadyResult> {
        val tvName = status?.name.orEmpty()
        if (connected) {
            return listOf(
                ReadyResult(ReadyCheck.TV, ReadyState.PASS, tvName),
                ReadyResult(ReadyCheck.DEVELOPER_MODE, ReadyState.PASS),
                ReadyResult(ReadyCheck.HOST_IP, ReadyState.PASS),
                ReadyResult(ReadyCheck.CONNECTION, ReadyState.PASS),
            )
        }
        if (status == null) return listOf(ReadyResult(ReadyCheck.TV, ReadyState.FAIL, "No TV answered. Is it on?"))

        val rows = arrayListOf(ReadyResult(ReadyCheck.TV, ReadyState.PASS, tvName))
        when (status.developerMode) {
            true -> rows += ReadyResult(ReadyCheck.DEVELOPER_MODE, ReadyState.PASS)
            false -> return rows + ReadyResult(ReadyCheck.DEVELOPER_MODE, ReadyState.FAIL, "It is off on the TV")
            null -> rows += ReadyResult(ReadyCheck.DEVELOPER_MODE, ReadyState.UNSURE, "The TV did not say")
        }
        val hostIp = status.developerIp
        rows += when {
            phoneIp != null && TvNetwork.isEmulatorAddress(phoneIp) ->
                ReadyResult(ReadyCheck.HOST_IP, ReadyState.UNSURE, "It must be your computer's IP")
            hostIp == null -> ReadyResult(ReadyCheck.HOST_IP, ReadyState.UNSURE, "The TV did not say")
            hostIp == phoneIp -> ReadyResult(ReadyCheck.HOST_IP, ReadyState.PASS)
            else -> return rows + ReadyResult(
                ReadyCheck.HOST_IP, ReadyState.FAIL,
                "The TV has $hostIp, this phone is ${phoneIp ?: "not on Wi-Fi"}",
            )
        }
        return rows + ReadyResult(ReadyCheck.CONNECTION, ReadyState.FAIL, "The TV did not let the phone in")
    }

    /**
     * Row 6: is there a file of [app] for this TV? [isFile]: the user picked a file (the installer
     * checks what is inside it). Otherwise [release] is the chosen version (null: none could be
     * loaded, [loadError] says why) and [tizen] the TV's Tizen version, which picks Legacy's file.
     */
    fun packageRow(app: NuvioApp, release: Release?, isFile: Boolean, tizen: String?, loadError: String? = null): ReadyResult {
        if (isFile) return ReadyResult(ReadyCheck.PACKAGE, ReadyState.PASS, "The file you picked")
        if (release == null) {
            return ReadyResult(ReadyCheck.PACKAGE, ReadyState.FAIL, loadError ?: "Couldn't load ${app.label} versions. Check the internet connection.")
        }
        return when (val pick = app.packageFor(release, tizen)) {
            is PackagePick.Found -> ReadyResult(ReadyCheck.PACKAGE, ReadyState.PASS, pick.asset.name)
            is PackagePick.Missing -> ReadyResult(ReadyCheck.PACKAGE, ReadyState.FAIL, pick.reason)
        }
    }

    /** Row 7, from the certificate check for [app] on this TV. */
    fun signInRow(preflight: Preflight, app: NuvioApp): ReadyResult = when {
        preflight.blocked != null -> ReadyResult(ReadyCheck.SIGN_IN, ReadyState.FAIL, "This TV needs the certificate ${app.label} was installed with")
        preflight.usable.isNotEmpty() -> ReadyResult(ReadyCheck.SIGN_IN, ReadyState.PASS, "Already done for this TV")
        else -> ReadyResult(ReadyCheck.SIGN_IN, ReadyState.LATER, "Samsung's page opens during the install")
    }
}

/** The one highlighted button on the main screen. */
sealed interface MainAction {
    /** No TV picked yet. */
    data object FindTv : MainAction

    /** The TV can't be reached, or the last install checks failed. [app]: re-run that app's install checks; null: check the TV. */
    data class CheckAgain(val app: NuvioApp?) : MainAction

    data class Update(val app: NuvioApp) : MainAction
    data class Open(val app: NuvioApp) : MainAction
    data class Install(val app: NuvioApp) : MainAction

    /** What one app looks like on the selected TV. [updateAvailable] is only ever true for apps that track versions. */
    data class AppState(val installed: Boolean, val updateAvailable: Boolean = false)

    companion object {
        /**
         * Picks the highlighted button: Find my TV, Check again, Update Nuvio TV, Open an installed
         * app (Nuvio TV first), and Install Nuvio TV only when neither app is on the TV.
         * [failedChecks]: the app whose install checks failed last, if any.
         */
        fun pick(hasTv: Boolean, online: Boolean, apps: Map<NuvioApp, AppState>, failedChecks: NuvioApp? = null): MainAction {
            if (!hasTv) return FindTv
            if (failedChecks != null) return CheckAgain(failedChecks)
            if (!online) return CheckAgain(null)
            NuvioApp.entries.firstOrNull { apps[it]?.installed == true && apps[it]?.updateAvailable == true }?.let { return Update(it) }
            NuvioApp.entries.firstOrNull { apps[it]?.installed == true }?.let { return Open(it) }
            return Install(NuvioApp.TV)
        }
    }
}
