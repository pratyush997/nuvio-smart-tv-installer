// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.app

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.nuvio.installer.core.BackupPasswordException
import tv.nuvio.installer.core.BugReport
import tv.nuvio.installer.core.BugReportInfo
import tv.nuvio.installer.core.FoundTv
import tv.nuvio.installer.core.IdentityBackup
import tv.nuvio.installer.core.GitHubReleases
import tv.nuvio.installer.core.IdentityStore
import tv.nuvio.installer.core.InstallReadiness
import tv.nuvio.installer.core.InstallStage
import tv.nuvio.installer.core.InstallerEvents
import tv.nuvio.installer.core.MainAction
import tv.nuvio.installer.core.ReadyCheck
import tv.nuvio.installer.core.ReadyResult
import tv.nuvio.installer.core.ReadyState
import tv.nuvio.installer.core.InstallerException
import tv.nuvio.installer.core.IpReport
import tv.nuvio.installer.core.LogLevel
import tv.nuvio.installer.core.CertificateExpiredException
import tv.nuvio.installer.core.MissingIdentityException
import tv.nuvio.installer.core.NuvioApp
import tv.nuvio.installer.core.PackagePick
import tv.nuvio.installer.core.Release
import tv.nuvio.installer.core.ReleaseChannel
import tv.nuvio.installer.core.ReleasePackage
import tv.nuvio.installer.core.SamsungRemote
import tv.nuvio.installer.core.SavedTv
import tv.nuvio.installer.core.SavedTvs
import tv.nuvio.installer.core.SamsungSession
import tv.nuvio.installer.core.SamsungCertificates
import tv.nuvio.installer.core.SamsungLoginServer
import tv.nuvio.installer.core.SamsungTvStatus
import tv.nuvio.installer.core.TizenInstaller
import tv.nuvio.installer.core.TizenStudioCertificate
import tv.nuvio.installer.core.TizenStudioPasswordException
import tv.nuvio.installer.core.TvDiscovery
import tv.nuvio.installer.core.TvInfo
import tv.nuvio.installer.core.TvNetwork
import tv.nuvio.installer.core.downloadFile
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.time.Instant

data class LogLine(val level: LogLevel, val text: String)

sealed interface PackageSource {
    val version: String

    data class FromRelease(val release: Release) : PackageSource {
        override val version get() = release.tag_name
    }

    data class FromFile(val uri: Uri, val name: String) : PackageSource {
        override val version get() = name
    }
}

enum class TvState { UNKNOWN, CHECKING, ONLINE, OFFLINE }

enum class OutcomeKind { SUCCESS, ERROR, NEEDS_CERTIFICATE }

/** A finished job's result. [app]: the app it was about, for "Uninstall from TV" on a certificate problem. */
data class Outcome(val kind: OutcomeKind, val title: String, val message: String, val app: NuvioApp? = null)

/** The phone's address on the local network; [ip] is null when it has none. [gateway] is the router. */
data class PhoneNetwork(val ip: String?, val prefixLength: Int, val gateway: String? = null)

const val PREFS_NAME = "installer"
const val PREF_INSTALLED_VERSION = "installedVersion"
const val PREF_NOTIFIED_TAG = "notifiedTag"
const val PREF_AUTO_UPDATE = "autoUpdate"
const val PREF_THEME_MODE = "themeMode"
const val PREF_TV_DUID = "tvDuid"
private const val PREF_BACKUP_PASSWORD = "backupPassword"

/** A picked certificate file needs a password; the UI asks for it. */
sealed class ImportPasswordRequest(val wrongPassword: Boolean) {
    /** A protected backup made by this app. */
    class Backup(val bytes: ByteArray, wrongPassword: Boolean) : ImportPasswordRequest(wrongPassword)

    /** A Tizen Studio author.p12 + distributor.p12; [part] says which file the password is for. */
    class TizenStudio(
        val author: ByteArray,
        val distributor: ByteArray,
        val part: TizenStudioCertificate.Part,
        val authorPassword: String?,
        wrongPassword: Boolean,
    ) : ImportPasswordRequest(wrongPassword)
}
const val PREF_APP_NOTIFIED_TAG = "appNotifiedTag"
const val PREF_TV_ID = "tvId"
const val PREF_TV_NAME = "tvName"
const val PREF_TV_MODEL = "tvModel"
private const val PREF_APP_DISMISSED_TAG = "appDismissedTag"
private const val PREF_LEGACY_NOTICE = "legacyNoticeSeen"

/** [InstallerViewModel.busy] while the install checks run. */
const val BUSY_CHECKS = "Checking"

/** Pause between checklist rows, so each tick can be seen. */
private const val CHECK_STEP_MS = 250L

class InstallerViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val identityStore = IdentityStore(File(application.filesDir, "identities"))
    private val certificates = SamsungCertificates(File(application.filesDir, "samsung-ca"))
    /**
     * One Samsung sign-in covers certificates for several TVs. It is saved encrypted on this phone
     * for as long as Samsung says the token is valid, so closing the app does not lose it.
     */
    private val samsungSession = SamsungSession(store = KeystoreSignInStore(application))

    /** The "Type on TV" dialog is open. */
    var typeOnTvOpen by mutableStateOf(false)
        private set

    /** What the last "Type on TV" send did, for the dialog. */
    var typeOnTvStatus by mutableStateOf<String?>(null)
        private set

    var typeOnTvSending by mutableStateOf(false)
        private set

    fun openTypeOnTv() {
        typeOnTvStatus = null
        typeOnTvOpen = true
    }

    fun closeTypeOnTv() {
        typeOnTvOpen = false
    }

    /**
     * Sends [text] to the TV's open keyboard, then presses Enter when [enter] is set. The first
     * time, the TV asks whether to allow this phone; its answer (a token) is saved per TV.
     */
    fun sendTextToTv(text: String, enter: Boolean) {
        if (typeOnTvSending || tvIp.isBlank()) return
        typeOnTvSending = true
        val tokenKey = "remoteToken:" + (currentTv?.id ?: prefs.getString(PREF_TV_ID, null) ?: tvIp)
        val saved = prefs.getString(tokenKey, null)
        typeOnTvStatus = if (saved == null) "If the TV asks whether to allow TV Installer, choose Allow." else "Sending…"
        viewModelScope.launch {
            typeOnTvStatus = try {
                val token = withContext(Dispatchers.IO) {
                    SamsungRemote.connect(tvIp, token = saved).use { remote ->
                        if (text.isNotEmpty()) remote.sendText(text)
                        if (enter) remote.pressKey("KEY_ENTER")
                        remote.token
                    }
                }
                if (token != null && token != saved) prefs.edit().putString(tokenKey, token).apply()
                when {
                    text.isEmpty() -> "Pressed Enter on the TV."
                    enter -> "Sent \"$text\" and pressed Enter."
                    else -> "Sent \"$text\". Nothing on the TV? Open a search box first, so its keyboard is showing."
                }
            } catch (e: Exception) {
                e.message ?: "Could not reach the TV."
            } finally {
                typeOnTvSending = false
            }
        }
    }

    /** Install new Nuvio versions on the TV from the background check, without asking. */
    var autoUpdate by mutableStateOf(prefs.getBoolean(PREF_AUTO_UPDATE, false))
        private set

    fun updateAutoUpdate(on: Boolean) {
        autoUpdate = on
        prefs.edit().putBoolean(PREF_AUTO_UPDATE, on).apply()
    }

    var tvIp by mutableStateOf(prefs.getString("tvIp", "").orEmpty())
        private set

    /** Every TV set up from this phone, in the order they were added. */
    var savedTvs by mutableStateOf(TvList.load(prefs))
        private set

    /** The selected TV's entry in [savedTvs], or null for an address not saved yet. */
    val currentTv: SavedTv?
        get() = SavedTvs.atIp(savedTvs, tvIp)

    /** The one-time "About Nuvio Legacy" notice was accepted. */
    var legacyNoticeSeen by mutableStateOf(prefs.getBoolean(PREF_LEGACY_NOTICE, false))
        private set

    fun acknowledgeLegacyNotice() {
        legacyNoticeSeen = true
        prefs.edit().putBoolean(PREF_LEGACY_NOTICE, true).apply()
    }

    /** What each app's card installs: a release, or a file the user picked. No entry: nothing loaded yet. */
    var sources by mutableStateOf<Map<NuvioApp, PackageSource>>(emptyMap())
        private set

    fun source(app: NuvioApp): PackageSource? = sources[app]

    fun chooseSource(app: NuvioApp, source: PackageSource) {
        sources = sources + (app to source)
    }

    // ---- Install checks (see InstallReadiness) ----

    /** The install checks done so far, in order; empty when none ran. */
    var checks by mutableStateOf<List<ReadyResult>>(emptyList())
        private set

    /** The app the checks are for; null when there is no checklist. */
    var checksApp by mutableStateOf<NuvioApp?>(null)
        private set

    /** The check running now (spinner), or null. */
    var checkRunning by mutableStateOf<ReadyCheck?>(null)
        private set

    /** Set when the last checks stopped at a failure; the highlighted button then re-runs them. */
    var checkFailed by mutableStateOf<NuvioApp?>(null)
        private set

    /** True while the install checks run. */
    val checking: Boolean
        get() = busy == BUSY_CHECKS

    /** The failed check, when the checklist stopped at one. */
    val failedCheck: ReadyResult?
        get() = checks.lastOrNull()?.takeIf { it.state == ReadyState.FAIL }

    fun dismissChecks() {
        if (checking) return
        checks = emptyList()
        checksApp = null
        checkFailed = null
    }

    /** The address the selected TV had before the last check found it somewhere else. */
    var tvMovedFrom by mutableStateOf<String?>(null)
        private set

    /** Where the setup guide opens: 0 for the full guide, later steps when adding another TV. */
    var guideStartStep by mutableStateOf(0)
        private set

    /** The TV selected before "Add a TV", restored if the guide is closed without picking a new one. */
    private var tvBeforeAdd: String? = null

    /** The TV a notification asked to update (its [SavedTv.key]); selected before the update starts. */
    private var pendingUpdateTv: String? = null
    private var phoneNetwork = phoneNetwork()
    var phoneIp by mutableStateOf(phoneNetwork.ip ?: "unknown")
        private set
    /** Result of the last phone/TV address check. */
    var ipReport by mutableStateOf<IpReport?>(null)
        private set
    var tvName by mutableStateOf("")
        private set
    /** True while the first-time setup guide is on screen. */
    var showGuide by mutableStateOf(false)
        private set
    /** Set when the UI should open the "save backup" dialog (automatic backup could not write to Downloads). */
    var backupRequest by mutableStateOf(false)
        private set
    var lastBackup by mutableStateOf(prefs.getString("lastBackup", null))
        private set
    /**
     * Backups are encrypted with this password when set. It is kept in the app's private storage
     * so automatic backups need no typing; the file in Downloads is what it protects.
     */
    private var backupPassword: String? = prefs.getString(PREF_BACKUP_PASSWORD, null)?.takeIf { it.isNotEmpty() }
    var backupProtected by mutableStateOf(backupPassword != null)
        private set
    var importPasswordRequest by mutableStateOf<ImportPasswordRequest?>(null)
        private set
    val backupFileName: String
        get() = if (backupProtected) IdentityBackup.ENCRYPTED_FILE_NAME else IdentityBackup.FILE_NAME
    private var pendingUpdate = false
    /** A newer version of this app on GitHub, or null. */
    var appUpdate by mutableStateOf<AppUpdate?>(null)
        private set
    /** Result of this app's own update check, for the About popup (the banner above can be dismissed). */
    var appUpdateStatus by mutableStateOf<AppUpdateStatus>(AppUpdateStatus.Checking)
        private set
    /** Each app's releases as loaded from GitHub, newest first. No entry: not loaded yet. */
    private var releasesByApp by mutableStateOf<Map<NuvioApp, List<Release>>>(emptyMap())
    private var releaseErrors by mutableStateOf<Map<NuvioApp, String>>(emptyMap())

    /**
     * [app]'s releases, newest first. For Nuvio TV pre-releases are included: all are offered in
     * the version menu; only full releases count as "latest" (see [latestRelease]).
     */
    fun releases(app: NuvioApp): List<Release> = releasesByApp[app].orEmpty()

    fun releasesError(app: NuvioApp): String? = releaseErrors[app]

    /** Nuvio TV's releases, for update notifications (which are about Nuvio TV only). */
    private val tvReleases: List<Release>
        get() = releasesByApp[NuvioApp.TV].orEmpty()

    var tvState by mutableStateOf(TvState.UNKNOWN)
        private set
    var tvError by mutableStateOf<String?>(null)
        private set
    var tvInfo by mutableStateOf<TvInfo?>(null)
        private set
    /** True while the network scan for TVs runs. */
    var scanning by mutableStateOf(false)
        private set
    /** TVs found by the last scan; null before the first scan. */
    var foundTvs by mutableStateOf<List<FoundTv>?>(null)
        private set

    /** Non-null while an action runs; the action's name. */
    var busy by mutableStateOf<String?>(null)
        private set
    var stage by mutableStateOf<InstallStage?>(null)
        private set
    var progressLabel by mutableStateOf("")
        private set
    var progress by mutableStateOf<Float?>(null)
        private set
    var waitingForLogin by mutableStateOf(false)
        private set
    var outcome by mutableStateOf<Outcome?>(null)
        private set
    var identityCount by mutableStateOf(0)
        private set
    var hasIdentityForTv by mutableStateOf(false)
        private set
    /** When the certificate for this TV stops working (or, with no TV connected, the soonest of all saved ones). */
    var certExpiresAt by mutableStateOf<Instant?>(null)
        private set
    val log = mutableStateListOf<LogLine>()

    /** Set by the activity: opens the Samsung login page. */
    var loginOpener: ((String) -> Unit)? = null

    /** When the saved Samsung sign-in expires; null when none is saved. Declared before init, which sets it. */
    var signInExpiresAt by mutableStateOf<Instant?>(null)
        private set

    init {
        refreshIdentities()
        showGuide = !prefs.getBoolean("setupDone", false)
        NuvioApp.entries.forEach(::loadReleases)
        checkAppUpdate()
        if (tvIp.isNotBlank()) checkTv() else scanForTvs(autoSelect = true)
    }

    /**
     * Looks for Samsung TVs on the phone's Wi-Fi. With [autoSelect], a single TV found while no
     * TV IP is set yet is selected and checked straight away.
     */
    fun scanForTvs(autoSelect: Boolean = false) {
        if (scanning) return
        refreshPhoneIp()
        val phone = phoneNetwork
        val ip = phone.ip
        if (ip == null) {
            foundTvs = emptyList()
            return
        }
        scanning = true
        viewModelScope.launch {
            try {
                val found = withContext(Dispatchers.IO) { TvDiscovery.scan(ip, phone.prefixLength) }
                foundTvs = found
                log.add(LogLine(LogLevel.INFO, "Network scan: ${found.size} Samsung TV${if (found.size == 1) "" else "s"} found" +
                    found.joinToString("") { "\n  ${it.ip} ${it.status.name}" }))
                if (autoSelect && tvIp.isBlank() && found.size == 1) selectTv(found.single())
            } finally {
                scanning = false
            }
        }
    }

    /** Uses a TV from the scan results. */
    fun selectTv(tv: FoundTv) {
        if (busy != null) return
        updateTvIp(tv.ip)
        checkTv()
    }

    /** Switches to another saved TV. */
    fun selectSavedTv(tv: SavedTv) {
        if (busy != null) return
        updateTvIp(tv.ip)
        checkTv()
    }

    /** Takes [tv] off the list. Its certificate stays on the phone, so adding it again needs no sign-in. */
    fun removeTv(tv: SavedTv) {
        if (busy != null) return
        val wasSelected = currentTv?.let { SavedTvs.sameTv(it, tv) } == true
        savedTvs = TvList.update(prefs) { SavedTvs.remove(it, tv) }
        if (!wasSelected) return
        val next = savedTvs.firstOrNull()
        if (next != null) selectSavedTv(next) else updateTvIp("")
    }

    /** Header button: Auto, then Light, then Dark, then Auto again. Saved for the next start. */
    fun nextThemeMode() {
        val next = ThemeMode.entries[(Palette.mode.ordinal + 1) % ThemeMode.entries.size]
        Palette.mode = next
        prefs.edit().putString(PREF_THEME_MODE, next.name).apply()
    }

    /** Opens the setup guide at the TV steps, with no TV selected, so another TV can be set up. */
    fun addTv() {
        if (busy != null) return
        tvBeforeAdd = tvIp.takeIf { it.isNotBlank() }
        updateTvIp("")
        foundTvs = null
        guideStartStep = 2
        showGuide = true
    }

    /** Never throws: an offline phone just shows "couldn't check" in About. */
    fun checkAppUpdate() {
        appUpdateStatus = AppUpdateStatus.Checking
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { UpdateCheck.newerApp() } }
            val update = result.getOrNull()
            appUpdateStatus = when {
                result.isFailure -> AppUpdateStatus.Failed
                update != null -> AppUpdateStatus.Available(update)
                else -> AppUpdateStatus.UpToDate
            }
            if (update != null && prefs.getString(PREF_APP_DISMISSED_TAG, null) != update.tag) appUpdate = update
        }
    }

    fun dismissAppUpdate() {
        appUpdate?.let { prefs.edit().putString(PREF_APP_DISMISSED_TAG, it.tag).apply() }
        appUpdate = null
    }

    val installedVersion: String?
        get() = tvInfo?.installedApp?.get("app_version")?.takeIf { it.isNotBlank() }

    /** The selected TV, when it runs a beta this app installed (see [SavedTv.onBeta]). */
    private val tvOnBeta: SavedTv?
        get() = currentTv?.takeIf { it.onBeta && installedVersion != null && it.installedVersion == installedVersion }

    /** What is on the TV, as the user knows it: "1.4.0-beta.2" for a beta, else the number the TV reports. */
    val installedLabel: String?
        get() = tvOnBeta?.installedLabel ?: installedVersion

    /** True when [app] is on the TV (as of the last check). */
    fun isInstalled(app: NuvioApp): Boolean = tvInfo?.app(app) != null

    /**
     * What [app]'s card says is on the TV: Nuvio TV's version, or just "Installed" for Legacy,
     * whose TV-reported version is not the release number. Null when it is not installed.
     */
    fun installedLabel(app: NuvioApp): String? = when {
        app == NuvioApp.TV -> installedLabel
        isInstalled(app) -> "Installed"
        else -> null
    }

    /** True when Nuvio TV is on the connected TV and a newer version is out. */
    val updateAvailable: Boolean
        get() {
            if (tvState != TvState.ONLINE || installedVersion == null) return false
            val latest = latestRelease(NuvioApp.TV) ?: return false
            return compareWithInstalled(latest) == -1
        }

    /**
     * True when [app]'s card button should open the app rather than install: it is on the TV and
     * what is chosen is what is installed. Reinstalling is in the card's menu.
     */
    fun opensOnTv(app: NuvioApp): Boolean {
        if (tvState != TvState.ONLINE || !isInstalled(app)) return false
        val release = (sources[app] as? PackageSource.FromRelease)?.release ?: return sources[app] == null
        return !app.tracksVersions || compareWithInstalled(release) == 0
    }

    /** The highlighted button on the main screen. */
    val mainAction: MainAction
        get() = MainAction.pick(
            hasTv = tvIp.isNotBlank(),
            online = tvState == TvState.ONLINE,
            apps = NuvioApp.entries.associateWith { MainAction.AppState(isInstalled(it), it == NuvioApp.TV && updateAvailable) },
            failedChecks = checkFailed,
        )

    /** How [release] compares with what is on the TV: -1 when it is newer, null when unknown. */
    private fun compareWithInstalled(release: Release): Int? {
        val installed = installedVersion ?: return null
        val beta = tvOnBeta
        return if (beta != null) ReleaseChannel.compare(beta.installedTag.orEmpty(), true, release.tag_name, release.prerelease)
        else ReleaseChannel.compare(installed, false, release.tag_name, release.prerelease)
    }

    /** Label for [app]'s install button, based on what is on the TV and what is chosen. */
    fun buttonLabel(app: NuvioApp): String {
        val selected = sources[app] ?: return "Install ${app.label}"
        val release = (selected as? PackageSource.FromRelease)?.release ?: return "Install selected file"
        val target = selected.version
        if (!app.tracksVersions) {
            return if (tvState == TvState.ONLINE && isInstalled(app)) "Reinstall ${app.label}" else "Install ${app.label}"
        }
        return when {
            tvState != TvState.ONLINE -> "Install Nuvio $target"
            installedVersion == null -> "Install Nuvio $target"
            else -> when (compareWithInstalled(release)) {
                0 -> "Reinstall $target"
                -1 -> "Update to $target"
                1 -> "Install older $target"
                else -> "Install Nuvio $target"
            }
        }
    }

    fun updateTvIp(value: String) {
        val changed = value.trim() != tvIp
        tvIp = value.trim()
        tvMovedFrom = null
        prefs.edit().putString("tvIp", tvIp).apply()
        // The checklist was for the TV that was selected before.
        if (changed && !checking) dismissChecks()
        if (tvState != TvState.CHECKING) {
            tvState = TvState.UNKNOWN
            tvInfo = null
            tvError = null
            ipReport = null
        }
    }

    fun refreshPhoneIp() {
        phoneNetwork = phoneNetwork()
        phoneIp = phoneNetwork.ip ?: "unknown"
    }

    fun openGuide() {
        guideStartStep = 0
        showGuide = true
    }

    fun closeGuide() {
        showGuide = false
        prefs.edit().putBoolean("setupDone", true).apply()
        // "Add a TV" was left without picking one: go back to the TV that was selected.
        val previous = tvBeforeAdd
        tvBeforeAdd = null
        if (tvIp.isBlank() && previous != null) {
            updateTvIp(previous)
            checkTv()
        }
    }

    /**
     * The newest release for the selected TV: the newest full release, or, while the TV runs a
     * beta, the newest version overall (see [ReleaseChannel.latestFor]).
     */
    fun latestRelease(app: NuvioApp): Release? =
        if (app == NuvioApp.TV) ReleaseChannel.latestFor(releases(app), currentTv) else ReleaseChannel.latest(releases(app), beta = false)

    /**
     * One-tap update (from the "new version" notification): installs the newest release on the
     * TV with [tvKey] (a [SavedTv.key]), or on the selected TV when null.
     */
    fun updateNow(tvKey: String? = null) {
        pendingUpdate = true
        pendingUpdateTv = tvKey
        showGuide = false
        if (releasesError(NuvioApp.TV) != null) loadReleases(NuvioApp.TV)
        tryPendingUpdate()
    }

    private fun tryPendingUpdate() {
        if (!pendingUpdate || busy != null || tvReleases.isEmpty()) return
        val wanted = pendingUpdateTv?.let { SavedTvs.withKey(savedTvs, it) }
        pendingUpdateTv = null
        if (wanted != null && currentTv?.key != wanted.key) {
            // Switch to that TV first; the check that follows calls this again.
            selectSavedTv(wanted)
            return
        }
        pendingUpdate = false
        // Update notifications are about Nuvio TV.
        val target = latestRelease(NuvioApp.TV) ?: return
        chooseSource(NuvioApp.TV, PackageSource.FromRelease(target))
        if (tvState == TvState.ONLINE && (compareWithInstalled(target) ?: -1) >= 0) {
            outcome = Outcome(OutcomeKind.SUCCESS, "Already up to date", "Your TV already has Nuvio $installedLabel.")
            return
        }
        install(NuvioApp.TV)
    }

    fun backupRequestHandled() {
        backupRequest = false
    }

    /** True once, after Nuvio is on the TV, when Android 13+ still needs the notification permission. */
    fun shouldAskForNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < 33 || installedVersion == null || prefs.getBoolean("notificationsAsked", false)) return false
        prefs.edit().putBoolean("notificationsAsked", true).apply()
        return !UpdateCheck.canNotify(getApplication())
    }

    fun loadReleases(app: NuvioApp) {
        viewModelScope.launch { fetchReleases(app) }
    }

    /** Loads [app]'s releases and waits for them; on failure [releaseErrors] says why. */
    private suspend fun fetchReleases(app: NuvioApp) {
        releaseErrors = releaseErrors - app
        try {
            val list = withContext(Dispatchers.IO) { GitHubReleases.of(app) }
            releasesByApp = releasesByApp + (app to list)
            if (sources[app] == null) latestRelease(app)?.let { chooseSource(app, PackageSource.FromRelease(it)) }
            if (app == NuvioApp.TV) tryPendingUpdate()
        } catch (e: Exception) {
            val error = "Couldn't load ${app.label} versions. Check the internet connection."
            releaseErrors = releaseErrors + (app to error)
            if (pendingUpdate && app == NuvioApp.TV) {
                pendingUpdate = false
                outcome = Outcome(OutcomeKind.ERROR, "Update failed", error)
            }
        }
    }

    private val events = object : InstallerEvents {
        override fun log(level: LogLevel, text: String) {
            viewModelScope.launch(Dispatchers.Main) { log.add(LogLine(level, text)) }
        }

        override fun progress(label: String, done: Long, total: Long) {
            viewModelScope.launch(Dispatchers.Main) {
                progressLabel = label
                progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
            }
        }

        override fun stage(stage: InstallStage) {
            viewModelScope.launch(Dispatchers.Main) {
                this@InstallerViewModel.stage = stage
                progress = null
                progressLabel = ""
            }
        }

        override fun waitingForLogin(waiting: Boolean) {
            viewModelScope.launch(Dispatchers.Main) { waitingForLogin = waiting }
        }
    }

    private fun installer() = TizenInstaller(
        workDirectory = File(getApplication<Application>().cacheDir, "work"),
        identities = identityStore,
        certificates = certificates,
        events = events,
        openLogin = { url -> viewModelScope.launch(Dispatchers.Main) { loginOpener?.invoke(url) } },
        loginReturnUrl = "nuvio-tizen-installer://done",
        session = samsungSession,
    )

    fun reopenLogin() {
        loginOpener?.invoke(SamsungLoginServer.AUTH_URL)
    }

    /** Re-reads [signInExpiresAt]; also clears it once the hour is over. */
    fun refreshSignIn() {
        signInExpiresAt = samsungSession.expiresAt()
    }

    /** Drops the kept Samsung sign-in: the next new certificate asks for a sign-in again. */
    fun forgetSignIn() {
        samsungSession.forget()
        refreshSignIn()
    }

    private fun refreshIdentities() {
        refreshSignIn()
        val all = identityStore.all().filter { it.second.isUsable }
        identityCount = all.size
        val duid = tvInfo?.duid
        hasIdentityForTv = duid?.let { all.any { it.second.duid == duid } } ?: false
        certExpiresAt = if (duid != null) {
            identityStore.candidatesFor(duid).firstOrNull()?.expiresAt()
        } else {
            all.mapNotNull { it.second.expiresAt() }.minOrNull()
        }
    }

    private class TvCheck(val info: TvInfo?, val error: String?, val status: SamsungTvStatus?)

    /** Checks the phone and TV addresses, asks the TV how Developer Mode is set, and tries the installer connection. */
    fun checkTv() {
        if (busy != null || tvIp.isBlank()) return
        busy = "Checking TV"
        // "Check again" after failed install checks: this check replaces them.
        dismissChecks()
        viewModelScope.launch {
            try {
                probeAndReport()
            } finally {
                busy = null
                refreshIdentities()
                tryPendingUpdate()
            }
        }
    }

    /**
     * The TV check behind [checkTv] and the install checks, waited for: probes the TV (finding it
     * again when the router gave it a new address), then updates [tvState], [tvInfo] and
     * [ipReport]. The caller holds [busy].
     */
    private suspend fun probeAndReport(): TvCheck {
        refreshPhoneIp()
        val ip = tvIp
        val phone = phoneNetwork
        tvState = TvState.CHECKING
        tvError = null
        ipReport = null
        var target = ip
        var check = probeTv(ip, phone)
        val phoneIp = phone.ip
        val expected = SavedTvs.atIp(savedTvs, ip)
        // A different TV answers at this saved address: the router swapped the two TVs' addresses.
        val otherTv = expected?.id != null && check.status?.id != null && check.status?.id != expected.id
        var keepList = otherTv
        if ((otherTv || check.info == null && check.status == null) && phoneIp != null && TvNetwork.isValidIpv4(ip)) {
            // The router may have given the TV a new address: look for it on the Wi-Fi.
            val remembered = expected?.remembered
            if (remembered != null) {
                val found = withContext(Dispatchers.IO) { TvDiscovery.scan(phoneIp, phone.prefixLength) }
                foundTvs = found
                val moved = TvDiscovery.findRemembered(remembered, found)
                if (moved != null && moved.ip != ip) {
                    log.add(LogLine(LogLevel.INFO, "Your TV is now at ${moved.ip} (it was at $ip). Using the new address."))
                    target = moved.ip
                    tvIp = target
                    tvMovedFrom = ip
                    prefs.edit().putString("tvIp", target).apply()
                    check = probeTv(target, phone)
                    keepList = false
                }
            }
        }
        // Not when the saved TV is missing and another one answered: that would replace it in the list.
        if (!keepList && (check.status != null || check.info != null)) saveTv(target, check.status, check.info)
        val report = TvNetwork.report(
            phone.ip, phone.prefixLength, target, check.status, check.info != null, check.error,
            prefs.getString("lastGoodPhoneIp", null), phone.gateway,
        )
        ipReport = report
        tvName = check.status?.name.orEmpty()
        tvInfo = check.info
        check.status?.let(::rememberTv)
        if (check.info != null) {
            tvState = TvState.ONLINE
            phone.ip?.let { prefs.edit().putString("lastGoodPhoneIp", it).apply() }
            rememberInstalledVersion(check.info)
        } else {
            tvState = TvState.OFFLINE
            tvError = report.fix?.text ?: check.error
            log.add(LogLine(LogLevel.ERROR, report.rows.lastOrNull()?.text.orEmpty()))
            report.fix?.let { log.add(LogLine(LogLevel.INFO, it.text)) }
        }
        refreshIdentities()
        return check
    }

    /** Asks the TV's info port and tries the installer connection, in parallel. */
    private suspend fun probeTv(ip: String, phone: PhoneNetwork): TvCheck {
        if (!TvNetwork.isValidIpv4(ip) || ip == phone.ip) return TvCheck(null, null, null)
        return withContext(Dispatchers.IO) {
            val status = async { runCatching { TvNetwork.fetchStatus(ip) }.getOrNull() }
            try {
                TvCheck(installer().probe(ip), null, status.await())
            } catch (e: Exception) {
                TvCheck(null, e.message ?: e.javaClass.simpleName, status.await())
            }
        }
    }

    /** Remembers which TV answered, so it can be found again after the router gives it a new IP. */
    private fun rememberTv(status: SamsungTvStatus) {
        if (status.name.isEmpty()) return
        prefs.edit()
            .putString(PREF_TV_ID, status.id)
            .putString(PREF_TV_NAME, status.name)
            .putString(PREF_TV_MODEL, status.model)
            .apply()
    }

    /**
     * Adds or updates the TV at [ip] in [savedTvs]. With [info] null (the installer connection
     * failed), the saved Nuvio version is kept: it is unknown, not "not installed".
     */
    private fun saveTv(ip: String, status: SamsungTvStatus?, info: TvInfo?) {
        val version = info?.installedApp?.get("app_version")?.takeIf { it.isNotBlank() }
        val candidate = SavedTv(ip, status?.name.orEmpty(), status?.model.orEmpty(), status?.id, info?.duid)
        savedTvs = TvList.update(prefs) { list ->
            val old = list.firstOrNull { SavedTvs.sameTv(it, candidate) }
            SavedTvs.upsert(
                list,
                candidate.copy(
                    installedVersion = if (info != null) version else old?.installedVersion,
                    hasLegacy = if (info != null) info.app(NuvioApp.LEGACY) != null else old?.hasLegacy == true,
                    // The per-TV app choice is no longer used (each app has its own card); null keeps what was saved.
                    app = null,
                ),
            )
        }
    }

    /**
     * Remembers which GitHub release was put on the selected TV, so updates know whether it runs
     * a beta. Installing a file forgets it.
     */
    private fun rememberInstalledRelease(installed: PackageSource?, app: NuvioApp) {
        // The tag and beta flag are Nuvio TV's; Legacy updates itself.
        if (app != NuvioApp.TV) return
        val release = (installed as? PackageSource.FromRelease)?.release
        savedTvs = TvList.update(prefs) { list ->
            list.map { if (it.ip == tvIp) it.copy(installedTag = release?.tag_name, installedBeta = release?.prerelease == true) else it }
        }
    }

    /** Remembered so the background update check knows what is on the TV. */
    private fun rememberInstalledVersion(info: TvInfo) {
        val version = info.installedApp?.get("app_version")?.takeIf { it.isNotBlank() }
        prefs.edit().apply {
            if (version != null) putString(PREF_INSTALLED_VERSION, version) else remove(PREF_INSTALLED_VERSION)
            putString(PREF_TV_DUID, info.duid)
        }.apply()
    }

    /** Runs [block] for [app] as one job: nothing else starts until it ends. */
    private fun run(label: String, app: NuvioApp, block: suspend () -> Outcome) {
        if (busy != null) return
        if (tvIp.isBlank()) {
            outcome = Outcome(OutcomeKind.ERROR, "TV IP missing", "Enter the TV's IP address first.")
            return
        }
        busy = label
        viewModelScope.launch {
            // After a failure, re-run the address checks so the TV card says what is wrong.
            if (!runHeld(label, app, block)) checkTv()
        }
    }

    /**
     * The work of [run], for a caller that already holds [busy] (the install checks): runs
     * [block], shows its outcome and releases [busy]. Returns false when it failed.
     */
    private suspend fun runHeld(label: String, app: NuvioApp, block: suspend () -> Outcome): Boolean {
        busy = label
        outcome = null
        stage = null
        progress = null
        progressLabel = ""
        log.add(LogLine(LogLevel.INFO, "── $label ──"))
        var failed = false
        try {
            outcome = withContext(Dispatchers.IO) { block() }
        } catch (e: MissingIdentityException) {
            outcome = Outcome(
                OutcomeKind.NEEDS_CERTIFICATE,
                if (e is CertificateExpiredException) "Certificate expired" else "Certificate needed",
                e.message.orEmpty(),
                app,
            )
            log.add(LogLine(LogLevel.ERROR, e.message.orEmpty()))
        } catch (e: Exception) {
            val text = if (e is InstallerException) e.message.orEmpty() else "${e.javaClass.simpleName}: ${e.message}"
            outcome = Outcome(OutcomeKind.ERROR, "$label failed", text, app)
            log.add(LogLine(LogLevel.ERROR, text))
            failed = true
        } finally {
            busy = null
            stage = null
            progress = null
            progressLabel = ""
            waitingForLogin = false
            refreshIdentities()
        }
        return !failed
    }

    /** Re-reads the TV state after an action, without failing the action. */
    private suspend fun refreshTvQuietly() {
        val info = runCatching { installer().probe(tvIp) }.getOrNull()
        withContext(Dispatchers.Main) {
            if (info != null) {
                tvInfo = info
                tvState = TvState.ONLINE
                rememberInstalledVersion(info)
                saveTv(tvIp, null, info)
            }
        }
    }

    /**
     * Installs (or updates) [app]: runs the install checks first, then, when they all pass, the
     * install itself. Checks and install are one job: [busy] is held from the tap to the end.
     */
    fun install(app: NuvioApp) {
        if (busy != null) return
        if (tvIp.isBlank()) {
            outcome = Outcome(OutcomeKind.ERROR, "No TV yet", "Tap Find my TV, or type the TV's IP address.")
            return
        }
        busy = BUSY_CHECKS
        outcome = null
        checks = emptyList()
        checksApp = app
        checkFailed = null
        viewModelScope.launch {
            val passed = try {
                runChecks(app)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Show it as the failed row, so the checklist offers Check again.
                val text = e.message ?: e.javaClass.simpleName
                checks = checks + ReadyResult(checkRunning ?: ReadyCheck.TV, ReadyState.FAIL, text)
                log.add(LogLine(LogLevel.ERROR, "Checks stopped: $text"))
                false
            } finally {
                checkRunning = null
            }
            if (!passed) {
                checkFailed = app
                busy = null
                return@launch
            }
            checks = emptyList()
            checksApp = null
            // The checklist already says what is wrong when the install fails: no extra TV check.
            runHeld("Install", app) { installNow(app) }
        }
    }

    /**
     * The 7 install checks, in order, stopping at the first failure. Rows that need no waiting
     * (Wi-Fi, an already loaded release) tick at once; the TV rows always ask the TV again, since
     * it may have been turned off since the last check. Returns true when all passed.
     */
    private suspend fun runChecks(app: NuvioApp): Boolean {
        suspend fun show(row: ReadyResult, wait: Boolean): Boolean {
            if (wait) delay(CHECK_STEP_MS)
            checks = checks + row
            if (row.state == ReadyState.FAIL) {
                log.add(LogLine(LogLevel.ERROR, "${row.check.title(app)}: ${row.detail}"))
                return false
            }
            return true
        }
        log.add(LogLine(LogLevel.INFO, "── Checking before installing ${app.label} ──"))

        // 1. Wi-Fi
        refreshPhoneIp()
        if (!show(InstallReadiness.wifiRow(phoneNetwork.ip), wait = false)) return false

        // 2–5. The TV, asked again every time.
        checkRunning = ReadyCheck.TV
        val tv = probeAndReport()
        val tvRows = InstallReadiness.tvRows(tv.status, phoneNetwork.ip, connected = tv.info != null)
        for (row in tvRows) {
            checkRunning = row.check
            if (!show(row, wait = true)) return false
        }
        val info = tv.info ?: return false

        // 6. The file for this TV.
        checkRunning = ReadyCheck.PACKAGE
        val loaded = releasesByApp[app] != null
        if (!loaded && sources[app] !is PackageSource.FromFile) fetchReleases(app)
        val source = sources[app]
        val packageRow = InstallReadiness.packageRow(
            app, (source as? PackageSource.FromRelease)?.release, source is PackageSource.FromFile,
            info.platformVersion, releaseErrors[app],
        )
        if (!show(packageRow, wait = !loaded)) return false

        // 7. Samsung sign-in, from what the TV check read: no extra connection.
        checkRunning = ReadyCheck.SIGN_IN
        val preflight = withContext(Dispatchers.IO) { installer().preflight(info.duid, info.app(app), app) }
        val signIn = InstallReadiness.signInRow(preflight, app)
        if (!show(signIn, wait = true)) {
            preflight.blocked?.let { e ->
                outcome = Outcome(
                    OutcomeKind.NEEDS_CERTIFICATE,
                    if (e is CertificateExpiredException) "Certificate expired" else "Certificate needed",
                    e.message.orEmpty(),
                    app,
                )
            }
            return false
        }
        delay(CHECK_STEP_MS)
        return true
    }

    /** The install itself, after the checks passed; runs on the IO dispatcher. */
    private suspend fun installNow(app: NuvioApp): Outcome {
        val installing = sources[app]
        events.stage(InstallStage.PACKAGE)
        // The checks just read the TV's Tizen version, which picks Legacy's file.
        val file = preparePackage(app, tvInfo?.platformVersion?.takeIf { it.isNotBlank() })
        val certificatesBefore = identityStore.all().map { it.first.name }.toSet()
        var backupNote = ""
        if (!TvAccess.tryAcquire()) throw InstallerException("An automatic Nuvio update is running on the TV right now. Try again in a few minutes.")
        try {
            installer().install(tvIp, file, app = app)
        } finally {
            TvAccess.release()
            // A certificate was created for this TV: back it up right away, even if the install failed after that.
            if (identityStore.all().any { it.first.name !in certificatesBefore }) backupNote = autoBackup()
        }
        refreshTvQuietly()
        withContext(Dispatchers.Main) { rememberInstalledRelease(installing, app) }
        return if (app == NuvioApp.TV) {
            Outcome(
                OutcomeKind.SUCCESS, "Nuvio is on your TV",
                "Version ${installedLabel ?: installing?.version.orEmpty()} is installed. Open it from the TV's Apps screen.$backupNote",
            )
        } else {
            Outcome(
                OutcomeKind.SUCCESS, "${app.label} is on your TV",
                "Open it from the TV's Apps screen. It updates itself from now on.$backupNote",
            )
        }
    }

    /**
     * Saves the certificate backup to the phone's Downloads folder without asking. Where that is
     * not possible (Android 9 and older), asks the UI to open the save dialog instead.
     */
    private suspend fun autoBackup(): String {
        val saved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) runCatching {
            val resolver = getApplication<Application>().contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, backupFileName)
                put(MediaStore.Downloads.MIME_TYPE, if (backupProtected) "application/octet-stream" else "application/zip")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw InstallerException("Downloads is not available.")
            try {
                resolver.openOutputStream(uri)?.use { IdentityBackup.write(identityStore, it, backupPassword) }
                    ?: throw InstallerException("Could not write the backup file.")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        }.isSuccess else false
        return if (saved) {
            val where = "Downloads/$backupFileName"
            prefs.edit().putString("lastBackup", where).apply()
            withContext(Dispatchers.Main) { lastBackup = where }
            events.log(LogLevel.SUCCESS, "Certificate backup saved to $where.")
            "\n\nYour certificate was backed up to $where. Keep that file: updates need it."
        } else {
            withContext(Dispatchers.Main) { backupRequest = true }
            events.log(LogLevel.INFO, "Choose where to save the certificate backup.")
            "\n\nSave the certificate backup when asked: updates need it."
        }
    }

    fun launchOnTv(app: NuvioApp) {
        run("Launch", app) {
            installer().launch(tvIp, app)
            Outcome(OutcomeKind.SUCCESS, "Launched", "${app.label} is opening on the TV.")
        }
    }

    /** Samsung sign-in that creates this TV's certificate now, before any install. */
    fun createCertificate(app: NuvioApp = NuvioApp.TV) {
        run("Samsung sign-in", app) { certificateOutcome(app) }
    }

    private suspend fun certificateOutcome(app: NuvioApp): Outcome {
        val created = installer().ensureCertificate(tvIp, app)
        if (!created) return Outcome(OutcomeKind.SUCCESS, "Certificate already saved", "This TV's certificate is already on this phone. No sign-in needed.")
        val backupNote = autoBackup()
        return Outcome(OutcomeKind.SUCCESS, "Certificate created", "Your TV's certificate is saved on this phone. Installs and updates won't ask you to sign in again.$backupNote")
    }

    fun uninstall(app: NuvioApp) {
        run("Uninstall", app) {
            installer().uninstall(tvIp, app)
            refreshTvQuietly()
            Outcome(OutcomeKind.SUCCESS, "Removed", "${app.label} was removed from the TV.")
        }
    }

    fun dismissOutcome() {
        outcome = null
    }

    /** Downloads (or copies) the package for [app]; [tizen] is the TV's version, which picks Legacy's file. */
    private fun preparePackage(app: NuvioApp, tizen: String?): File {
        val context = getApplication<Application>()
        val downloads = File(context.cacheDir, "downloads").apply { mkdirs() }
        return when (val selected = sources[app] ?: throw InstallerException("Choose a ${app.label} version or a file first.")) {
            is PackageSource.FromRelease -> when (val pick = app.packageFor(selected.release, tizen)) {
                is PackagePick.Found -> ReleasePackage.fetch(pick.asset, downloads, events)
                is PackagePick.Missing -> throw InstallerException(pick.reason)
            }
            is PackageSource.FromFile -> {
                // .wgt or .tpk: the installer tells them apart by what is inside.
                val target = File(downloads, "selected-package")
                context.contentResolver.openInputStream(selected.uri)?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                } ?: throw InstallerException("Could not read the selected file.")
                events.log(LogLevel.INFO, "Using selected file ${selected.name}.")
                target
            }
        }
    }

    fun selectFile(app: NuvioApp, uri: Uri) {
        val name = getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "package.wgt"
        chooseSource(app, PackageSource.FromFile(uri, name))
    }

    /**
     * Imports what the user picked: a desktop-installer certificate .json, a backup made by this
     * app, or the author.p12 and distributor.p12 from Tizen Studio. A protected backup is tried
     * with this phone's backup password first, then the user is asked for it.
     */
    fun importIdentities(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val files = try {
                withContext(Dispatchers.IO) { uris.map { displayName(it) to readUri(it) } }
            } catch (e: Exception) {
                outcome = Outcome(OutcomeKind.ERROR, "Import failed", e.message ?: "Import failed.")
                return@launch
            }
            val p12 = files.filter { it.first.endsWith(".p12", ignoreCase = true) || it.first.endsWith(".pfx", ignoreCase = true) }
            when {
                p12.size == 2 && files.size == 2 -> {
                    val (first, second) = p12
                    val authorFirst = TizenStudioCertificate.authorFirst(first.first, second.first)
                    if (authorFirst == null) {
                        outcome = Outcome(
                            OutcomeKind.ERROR, "Import failed",
                            "Could not tell which file is author.p12 and which is distributor.p12. Keep their original names and pick both again.",
                        )
                        return@launch
                    }
                    val (author, distributor) = if (authorFirst) first.second to second.second else second.second to first.second
                    importTizenStudio(author, distributor, authorPassword = null, distributorPassword = null)
                }
                p12.isNotEmpty() -> outcome = Outcome(
                    OutcomeKind.ERROR, "Pick both files",
                    "A Tizen Studio certificate has two files. Tap Import again and select both author.p12 and distributor.p12 " +
                        "from the certificate profile folder (long-press the first file, then tap the second).",
                )
                files.size > 1 -> outcome = Outcome(OutcomeKind.ERROR, "Pick one file", "Pick one certificate file or backup at a time.")
                else -> importBackup(files.single().second, backupPassword, askOnWrongPassword = false)
            }
        }
    }

    private fun readUri(uri: Uri): ByteArray =
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw InstallerException("Could not read the selected file.")

    private fun displayName(uri: Uri): String = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    /** Called from the password prompt; null cancels. */
    fun answerImportPassword(password: String?) {
        val request = importPasswordRequest ?: return
        importPasswordRequest = null
        if (password == null) return
        viewModelScope.launch {
            when (request) {
                is ImportPasswordRequest.Backup -> importBackup(request.bytes, password, askOnWrongPassword = true)
                is ImportPasswordRequest.TizenStudio -> when (request.part) {
                    TizenStudioCertificate.Part.AUTHOR -> importTizenStudio(request.author, request.distributor, password, null)
                    TizenStudioCertificate.Part.DISTRIBUTOR -> importTizenStudio(request.author, request.distributor, request.authorPassword, password)
                }
            }
        }
    }

    private suspend fun importBackup(bytes: ByteArray, password: String?, askOnWrongPassword: Boolean) {
        try {
            val count = withContext(Dispatchers.IO) { IdentityBackup.import(identityStore, bytes, password) }
            refreshIdentities()
            outcome = Outcome(
                OutcomeKind.SUCCESS, "Certificate imported",
                "Imported $count certificate${if (count == 1) "" else "s"}. You can install or update now."
            )
            log.add(LogLine(LogLevel.SUCCESS, "Imported $count certificate file(s)."))
        } catch (e: BackupPasswordException) {
            // The saved password did not open it (a backup from another phone): ask without calling it wrong.
            importPasswordRequest = ImportPasswordRequest.Backup(bytes, wrongPassword = e.wrongPassword && askOnWrongPassword)
        } catch (e: Exception) {
            outcome = Outcome(OutcomeKind.ERROR, "Import failed", e.message ?: "Import failed.")
        }
    }

    private suspend fun importTizenStudio(author: ByteArray, distributor: ByteArray, authorPassword: String?, distributorPassword: String?) {
        try {
            val saved = withContext(Dispatchers.IO) {
                TizenStudioCertificate.import(identityStore, author, distributor, authorPassword, distributorPassword) {
                    certificates.authorities(events)
                }
            }
            refreshIdentities()
            val duids = saved.map { it.duid }
            val current = tvInfo?.duid
            val forThisTv = when {
                current == null -> ""
                current in duids -> " It covers the TV you selected."
                else -> " None of them is the TV you selected (DUID $current): add that DUID to the certificate in Tizen Studio."
            }
            outcome = Outcome(
                OutcomeKind.SUCCESS, "Tizen Studio certificate imported",
                "Saved for ${duids.size} TV${if (duids.size == 1) "" else "s"} (${duids.joinToString()}).$forThisTv",
            )
            log.add(LogLine(LogLevel.SUCCESS, "Imported a Tizen Studio certificate for ${duids.joinToString()}."))
        } catch (e: TizenStudioPasswordException) {
            // The distributor password is asked for only when the author one does not open it.
            val wrong = e.wrongPassword && (e.file == TizenStudioCertificate.Part.AUTHOR || distributorPassword != null)
            importPasswordRequest = ImportPasswordRequest.TizenStudio(author, distributor, e.file, authorPassword, wrong)
        } catch (e: Exception) {
            outcome = Outcome(OutcomeKind.ERROR, "Import failed", e.message ?: "Import failed.")
        }
    }

    /** Sets (or with null, removes) the password that protects backups. */
    fun setBackupPassword(password: String?) {
        backupPassword = password?.takeIf { it.isNotEmpty() }
        prefs.edit().putString(PREF_BACKUP_PASSWORD, backupPassword).apply()
        backupProtected = backupPassword != null
        outcome = Outcome(
            OutcomeKind.SUCCESS,
            if (backupProtected) "Backups are protected" else "Backup password removed",
            if (backupProtected) {
                "New backups need this password to open. Tap Back up to save a protected copy now, then delete older unprotected " +
                    "backups (${IdentityBackup.FILE_NAME}) from Downloads. If you forget the password, the backup cannot be opened."
            } else {
                "New backups are plain .zip files again. Anyone with the file can use your TV's certificate, so keep it private."
            },
        )
    }

    /** Writes all saved certificates to a backup file the user chose. */
    fun exportIdentities(uri: Uri) {
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    if (identityStore.all().isEmpty()) throw InstallerException("There are no certificates to back up yet.")
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { IdentityBackup.write(identityStore, it, backupPassword) }
                        ?: throw InstallerException("Could not write the backup file.")
                }
                lastBackup = "the file you chose"
                prefs.edit().putString("lastBackup", lastBackup).apply()
                val protectedNote = if (backupProtected) " It is protected with your backup password." else ""
                outcome = Outcome(OutcomeKind.SUCCESS, "Backup saved", "Saved $count certificate file(s).$protectedNote Keep it safe: updates need it.")
            } catch (e: Exception) {
                outcome = Outcome(OutcomeKind.ERROR, "Backup failed", e.message ?: "Backup failed.")
            }
        }
    }

    /**
     * A bug report with the setup and the newest activity log lines, with TV IDs, tokens and
     * e-mail addresses taken out. The user sees it before anything is sent.
     */
    fun bugReport(): Pair<String, String> {
        val tv = listOfNotNull(
            prefs.getString(PREF_TV_MODEL, null)?.takeIf { it.isNotBlank() },
            tvInfo?.platformVersion?.takeIf { it.isNotBlank() }?.let { "Tizen $it" },
        ).joinToString(", ")
        val info = BugReportInfo(
            appVersion = BuildConfig.VERSION_NAME,
            phone = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
            tv = tv,
            nuvioOnTv = listOfNotNull(installedVersion, "Nuvio Legacy".takeIf { tvInfo?.app(NuvioApp.LEGACY) != null }).joinToString(", "),
        )
        val secrets = buildList {
            identityStore.all().forEach { add(it.second.duid) }
            tvInfo?.duid?.let(::add)
            prefs.getString(PREF_TV_DUID, null)?.let(::add)
            savedTvs.forEach { tv -> tv.duid?.let(::add); tv.id?.let(::add) }
            prefs.all.filterKeys { it.startsWith("remoteToken:") }.values.forEach { (it as? String)?.let(::add) }
        }.filter { it.isNotBlank() }.distinct()
        val lines = log.takeLast(300).map { BugReport.redact("[${it.level.name.lowercase()}] ${it.text}", secrets) }
        return BugReport.text(info, lines) to BugReport.issueUrl(UpdateCheck.APP_REPO, info, lines)
    }

    fun logText(): String = log.joinToString("\n") { "[${it.level.name.lowercase()}] ${it.text}" }

    fun clearLog() = log.clear()

    /** The phone's Wi-Fi (or Ethernet) address, ignoring mobile data and VPNs. */
    private fun phoneNetwork(): PhoneNetwork {
        val manager = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        if (manager != null) {
            runCatching {
                @Suppress("DEPRECATION")
                for (network in manager.allNetworks) {
                    val capabilities = manager.getNetworkCapabilities(network) ?: continue
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                    if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    ) continue
                    val properties = manager.getLinkProperties(network) ?: continue
                    val link = properties.linkAddresses
                        .firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress } ?: continue
                    val gateway = properties.routes
                        .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address && !it.gateway!!.isAnyLocalAddress }
                        ?.gateway?.hostAddress
                    return PhoneNetwork(link.address.hostAddress, link.prefixLength, gateway)
                }
            }
        }
        return PhoneNetwork(localIpv4(), 24)
    }

    private fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()
}
