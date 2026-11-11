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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.nuvio.installer.core.BackupPasswordException
import tv.nuvio.installer.core.FoundTv
import tv.nuvio.installer.core.IdentityBackup
import tv.nuvio.installer.core.GitHubReleases
import tv.nuvio.installer.core.IdentityStore
import tv.nuvio.installer.core.InstallStage
import tv.nuvio.installer.core.InstallerEvents
import tv.nuvio.installer.core.InstallerException
import tv.nuvio.installer.core.IpReport
import tv.nuvio.installer.core.LogLevel
import tv.nuvio.installer.core.CertificateExpiredException
import tv.nuvio.installer.core.MissingIdentityException
import tv.nuvio.installer.core.Release
import tv.nuvio.installer.core.ReleaseChannel
import tv.nuvio.installer.core.ReleasePackage
import tv.nuvio.installer.core.RememberedTv
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

data class Outcome(val kind: OutcomeKind, val title: String, val message: String)

/** The phone's address on the local network; [ip] is null when it has none. [gateway] is the router. */
data class PhoneNetwork(val ip: String?, val prefixLength: Int, val gateway: String? = null)

const val PREFS_NAME = "installer"
const val PREF_INSTALLED_VERSION = "installedVersion"
const val PREF_NOTIFIED_TAG = "notifiedTag"
const val PREF_AUTO_UPDATE = "autoUpdate"
const val PREF_BETA_CHANNEL = "betaChannel"
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

class InstallerViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val identityStore = IdentityStore(File(application.filesDir, "identities"))
    private val certificates = SamsungCertificates(File(application.filesDir, "samsung-ca"))
    /** One Samsung sign-in, kept in memory for an hour, covers certificates for several TVs. */
    private val samsungSession = SamsungSession()

    /** Install new Nuvio versions on the TV from the background check, without asking. */
    var autoUpdate by mutableStateOf(prefs.getBoolean(PREF_AUTO_UPDATE, false))
        private set

    fun updateAutoUpdate(on: Boolean) {
        autoUpdate = on
        prefs.edit().putBoolean(PREF_AUTO_UPDATE, on).apply()
    }

    var tvIp by mutableStateOf(prefs.getString("tvIp", "").orEmpty())
        private set
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
    /** Nuvio releases as loaded from GitHub, pre-releases included. */
    private var allReleases by mutableStateOf<List<Release>>(emptyList())

    /** Offer Nuvio pre-releases (beta versions) too. */
    var betaChannel by mutableStateOf(prefs.getBoolean(PREF_BETA_CHANNEL, false))
        private set

    /** The releases on the chosen channel, newest first. */
    val releases: List<Release>
        get() = ReleaseChannel.visible(allReleases, betaChannel)

    fun updateBetaChannel(on: Boolean) {
        betaChannel = on
        prefs.edit().putBoolean(PREF_BETA_CHANNEL, on).apply()
        val selected = (source as? PackageSource.FromRelease)?.release
        // Leaving beta: a selected pre-release is no longer offered. Joining: offer the newest version.
        if (on || selected == null || selected !in releases) latestRelease?.let { source = PackageSource.FromRelease(it) }
    }
    var releasesError by mutableStateOf<String?>(null)
        private set
    var source by mutableStateOf<PackageSource?>(null)

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

    init {
        refreshIdentities()
        showGuide = !prefs.getBoolean("setupDone", false)
        loadReleases()
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

    /** Label for the main button, based on what is on the TV and what is selected. */
    val primaryLabel: String
        get() {
            val selected = source ?: return "Install"
            val target = (selected as? PackageSource.FromRelease)?.version ?: return "Install selected file"
            val installed = installedVersion
            return when {
                tvState != TvState.ONLINE -> "Install Nuvio $target"
                installed == null -> "Install Nuvio $target"
                else -> when (TizenInstaller.compareVersions(installed, target)) {
                    0 -> "Reinstall $target"
                    -1 -> "Update to $target"
                    1 -> "Install older $target"
                    else -> "Install Nuvio $target"
                }
            }
        }

    fun updateTvIp(value: String) {
        tvIp = value.trim()
        prefs.edit().putString("tvIp", tvIp).apply()
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
        showGuide = true
    }

    fun closeGuide() {
        showGuide = false
        prefs.edit().putBoolean("setupDone", true).apply()
    }

    /** Newest stable release, or the newest pre-release when there is no stable one. */
    val latestRelease: Release?
        get() = ReleaseChannel.latest(allReleases, betaChannel)

    /** One-tap update (from the "new version" notification): installs the newest release. */
    fun updateNow() {
        pendingUpdate = true
        showGuide = false
        if (releasesError != null) loadReleases()
        tryPendingUpdate()
    }

    private fun tryPendingUpdate() {
        if (!pendingUpdate || busy != null) return
        val latest = latestRelease ?: return
        pendingUpdate = false
        source = PackageSource.FromRelease(latest)
        val installed = installedVersion
        if (tvState == TvState.ONLINE && installed != null && TizenInstaller.compareVersions(installed, latest.tag_name) == 0) {
            outcome = Outcome(OutcomeKind.SUCCESS, "Already up to date", "Your TV already has Nuvio $installed.")
            return
        }
        install()
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

    fun loadReleases() {
        releasesError = null
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { GitHubReleases().recent() }
                allReleases = list
                if (source == null) latestRelease?.let { source = PackageSource.FromRelease(it) }
                tryPendingUpdate()
            } catch (e: Exception) {
                releasesError = "Couldn't load Nuvio versions. Check the internet connection."
                if (pendingUpdate) {
                    pendingUpdate = false
                    outcome = Outcome(OutcomeKind.ERROR, "Update failed", releasesError.orEmpty())
                }
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

    private fun refreshIdentities() {
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
        refreshPhoneIp()
        val ip = tvIp
        val phone = phoneNetwork
        busy = "Checking TV"
        tvState = TvState.CHECKING
        tvError = null
        ipReport = null
        viewModelScope.launch {
            try {
                var target = ip
                var check = probeTv(ip, phone)
                val phoneIp = phone.ip
                if (check.info == null && check.status == null && phoneIp != null && TvNetwork.isValidIpv4(ip)) {
                    // Nothing answered at the saved IP: the router may have given the TV a new one.
                    val remembered = rememberedTv()
                    if (remembered != null) {
                        val found = withContext(Dispatchers.IO) { TvDiscovery.scan(phoneIp, phone.prefixLength) }
                        foundTvs = found
                        val moved = TvDiscovery.findRemembered(remembered, found)
                        if (moved != null && moved.ip != ip) {
                            log.add(LogLine(LogLevel.INFO, "Your TV is now at ${moved.ip} (it was at $ip). Using the new address."))
                            target = moved.ip
                            tvIp = target
                            prefs.edit().putString("tvIp", target).apply()
                            check = probeTv(target, phone)
                        }
                    }
                }
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
            } finally {
                busy = null
                refreshIdentities()
                tryPendingUpdate()
            }
        }
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

    private fun rememberedTv(): RememberedTv? {
        val name = prefs.getString(PREF_TV_NAME, null).orEmpty()
        val id = prefs.getString(PREF_TV_ID, null)
        if (name.isEmpty() && id.isNullOrEmpty()) return null
        return RememberedTv(id, name, prefs.getString(PREF_TV_MODEL, null).orEmpty())
    }

    /** Remembered so the background update check knows what is on the TV. */
    private fun rememberInstalledVersion(info: TvInfo) {
        val version = info.installedApp?.get("app_version")?.takeIf { it.isNotBlank() }
        prefs.edit().apply {
            if (version != null) putString(PREF_INSTALLED_VERSION, version) else remove(PREF_INSTALLED_VERSION)
            putString(PREF_TV_DUID, info.duid)
        }.apply()
    }

    private fun run(label: String, block: suspend () -> Outcome) {
        if (busy != null) return
        if (tvIp.isBlank()) {
            outcome = Outcome(OutcomeKind.ERROR, "TV IP missing", "Enter the TV's IP address first.")
            return
        }
        busy = label
        outcome = null
        stage = null
        progress = null
        progressLabel = ""
        log.add(LogLine(LogLevel.INFO, "── $label ──"))
        viewModelScope.launch {
            var failed = false
            try {
                outcome = withContext(Dispatchers.IO) { block() }
            } catch (e: MissingIdentityException) {
                outcome = Outcome(
                    OutcomeKind.NEEDS_CERTIFICATE,
                    if (e is CertificateExpiredException) "Certificate expired" else "Certificate needed",
                    e.message.orEmpty(),
                )
                log.add(LogLine(LogLevel.ERROR, e.message.orEmpty()))
            } catch (e: Exception) {
                val text = if (e is InstallerException) e.message.orEmpty() else "${e.javaClass.simpleName}: ${e.message}"
                outcome = Outcome(OutcomeKind.ERROR, "$label failed", text)
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
            // After a failure, re-run the address checks so the TV card says what is wrong.
            if (failed) checkTv()
        }
    }

    /** Re-reads the TV state after an action, without failing the action. */
    private suspend fun refreshTvQuietly() {
        val info = runCatching { installer().probe(tvIp) }.getOrNull()
        withContext(Dispatchers.Main) {
            if (info != null) {
                tvInfo = info
                tvState = TvState.ONLINE
                rememberInstalledVersion(info)
            }
        }
    }

    fun install() = run("Install") {
        events.stage(InstallStage.PACKAGE)
        val wgt = preparePackage()
        val certificatesBefore = identityStore.all().map { it.first.name }.toSet()
        var backupNote = ""
        if (!TvAccess.tryAcquire()) throw InstallerException("An automatic Nuvio update is running on the TV right now. Try again in a few minutes.")
        try {
            installer().install(tvIp, wgt)
        } finally {
            TvAccess.release()
            // A certificate was created for this TV: back it up right away, even if the install failed after that.
            if (identityStore.all().any { it.first.name !in certificatesBefore }) backupNote = autoBackup()
        }
        refreshTvQuietly()
        Outcome(
            OutcomeKind.SUCCESS, "Nuvio is on your TV",
            "Version ${installedVersion ?: source?.version.orEmpty()} is installed. Open it from the TV's Apps screen.$backupNote",
        )
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

    fun launchOnTv() = run("Launch") {
        installer().launch(tvIp)
        Outcome(OutcomeKind.SUCCESS, "Launched", "Nuvio TV is opening on the TV.")
    }

    /** Samsung sign-in that creates this TV's certificate now, before any install. */
    fun createCertificate() = run("Samsung sign-in") {
        val created = installer().ensureCertificate(tvIp)
        if (!created) return@run Outcome(OutcomeKind.SUCCESS, "Certificate already saved", "This TV's certificate is already on this phone. No sign-in needed.")
        val backupNote = autoBackup()
        Outcome(OutcomeKind.SUCCESS, "Certificate created", "Your TV's certificate is saved on this phone. Installs and updates won't ask you to sign in again.$backupNote")
    }

    fun uninstall() = run("Uninstall") {
        installer().uninstall(tvIp)
        refreshTvQuietly()
        Outcome(OutcomeKind.SUCCESS, "Removed", "Nuvio TV was removed from the TV.")
    }

    fun dismissOutcome() {
        outcome = null
    }

    private fun preparePackage(): File {
        val context = getApplication<Application>()
        val downloads = File(context.cacheDir, "downloads").apply { mkdirs() }
        return when (val selected = source ?: throw InstallerException("Choose a Nuvio version or a .wgt file first.")) {
            is PackageSource.FromRelease -> {
                val asset = selected.release.wgtAsset ?: throw InstallerException("This release has no Samsung .wgt file.")
                ReleasePackage.fetch(asset, downloads, events)
            }
            is PackageSource.FromFile -> {
                val target = File(downloads, "selected-package.wgt")
                context.contentResolver.openInputStream(selected.uri)?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                } ?: throw InstallerException("Could not read the selected file.")
                events.log(LogLevel.INFO, "Using selected file ${selected.name}.")
                target
            }
        }
    }

    fun selectFile(uri: Uri) {
        val name = getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "package.wgt"
        source = PackageSource.FromFile(uri, name)
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
