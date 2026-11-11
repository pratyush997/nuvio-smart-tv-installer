// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import java.io.File

data class TvInfo(val duid: String, val platformVersion: String, val installedApp: Map<String, String>?)

/** Thrown when Nuvio is already on the TV but no certificate that could update it is available. */
open class MissingIdentityException(message: String, cause: Throwable? = null) : InstallerException(message, cause)

/** Thrown when Nuvio is on the TV and the only saved certificates for it have expired. */
class CertificateExpiredException(message: String) : MissingIdentityException(message)

/**
 * Installs Nuvio TV on a Samsung Tizen TV over the network, the same way the desktop
 * Nuvio TV Installer does: validate the WGT, obtain (or create) the Samsung signing identity for the
 * TV's DUID, sign the package, upload it over SDB and run vd_appinstall.
 */
class TizenInstaller(
    private val workDirectory: File,
    private val identities: IdentityStore,
    private val certificates: SamsungCertificates,
    private val events: InstallerEvents,
    /** Opens the Samsung account login page (in a browser) for [url]. */
    private val openLogin: (url: String) -> Unit,
    private val loginReturnUrl: String? = null,
) {
    companion object {
        val NUVIO_IDS = listOf("NuvioTV001.NuvioTV", "NuvioTV001", "NuvioTV001.Nuvio", "Nuvio", "NuvioTV.NuvioTV", "NuvioTV")
        private const val REMOTE_DIR = "/home/owner/share/tmp/sdk_tools"
        private val COMMAND_COMPLETE = Regex("spend time|install failed|uninstall failed|download failed|check certificate error|invalid certificate chain", RegexOption.IGNORE_CASE)
        private val UNINSTALL_COMPLETE = Regex("spend time|uninstall failed|uninstall completed|completed|failed|error", RegexOption.IGNORE_CASE)
        private val FAILED_LINE = Regex("install failed|uninstall failed|download failed|check certificate error|invalid certificate chain|error", RegexOption.IGNORE_CASE)

        fun isCertificateRejection(text: String): Boolean =
            !isPlatformIncompatibility(text) && (
                Regex("install failed\\s*\\[\\s*(?:118012|118(?:\\s*,[^\\]]*)?)\\s*]", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
                    Regex("check certificate error|invalid certificate chain|rejected the signed package", RegexOption.IGNORE_CASE).containsMatchIn(text)
                )

        fun isPlatformIncompatibility(text: String): Boolean =
            Regex("install failed\\s*\\[\\s*(?:118019|118\\s*,\\s*-?19)\\s*]", RegexOption.IGNORE_CASE).containsMatchIn(text)

        fun parseVersion(value: String): List<Int>? {
            // Not \b: in "v1.2.0" there is no word boundary between "v" and "1".
            val m = Regex("(?<![\\d.])(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?").find(value) ?: return null
            return listOf(m.groupValues[1].toInt(), m.groupValues[2].ifEmpty { "0" }.toInt(), m.groupValues[3].ifEmpty { "0" }.toInt())
        }

        fun compareVersions(left: String, right: String): Int? {
            val l = parseVersion(left) ?: return null
            val r = parseVersion(right) ?: return null
            for (i in 0..2) if (l[i] != r[i]) return if (l[i] > r[i]) 1 else -1
            return 0
        }

        fun parseAppList(output: String): List<Map<String, String>> =
            output.split("---------------------------------------------------------------------------------------------")
                .map { it.replace("--------------", "").replace("-------------", "").replace(Regex("\\s+="), "=").replace("\r", "").trim() }
                .filter { it.isNotEmpty() }
                .map { entry ->
                    entry.lines().mapNotNull { line ->
                        val i = line.indexOf('=')
                        if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
                    }.toMap()
                }
                .filter { it.isNotEmpty() }

        private fun isClosedOutput(output: String) = output.lines().any { it.trim().equals("closed", ignoreCase = true) }
    }

    // ------------------------------------------------------------------ TV helpers

    private fun connect(ip: String): SdbClient {
        events.info("Connecting to Samsung TV $ip:${SdbClient.DEFAULT_PORT}...")
        val client = SdbClient.connect(ip.trim())
        events.success("Connected to the TV.")
        return client
    }

    private fun shell(client: SdbClient, command: String, options: SdbClient.ShellOptions = SdbClient.ShellOptions(), log: Boolean = true): String {
        events.command("$ $command")
        val output = client.shell(command, options)
        if (log && output.isNotBlank()) events.output(output.trimEnd())
        return output
    }

    private fun readDuid(client: SdbClient): String {
        val duid = shell(client, "0 getduid", SdbClient.ShellOptions(timeoutMs = 10_000))
            .lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.equals("closed", true) }
            ?: throw InstallerException("Unable to read the Samsung TV DUID.")
        events.info("TV DUID: $duid")
        return duid
    }

    private fun readPlatformVersion(client: SdbClient): String = runCatching {
        val output = shell(client, "0 capability", SdbClient.ShellOptions(timeoutMs = 10_000), log = false)
        Regex("(?:^|\\n)\\s*platform_version\\s*[:=]\\s*([^\\s\\r\\n]+)", RegexOption.IGNORE_CASE).find(output)?.groupValues?.get(1)?.trim().orEmpty()
    }.getOrDefault("")

    private fun listApps(client: SdbClient): List<Map<String, String>> =
        parseAppList(shell(client, "0 vd_applist", SdbClient.ShellOptions(timeoutMs = 30_000), log = false))

    private fun findApp(client: SdbClient, ids: Collection<String>): Map<String, String>? =
        listApps(client).firstOrNull { app ->
            listOf("app_id", "app_tizen_id", "app_package_id", "package_id").any { key -> app[key]?.trim() in ids }
        }

    private fun failIfOutputFailed(output: String, context: String) {
        val failed = output.lines().map { it.trim() }.firstOrNull { FAILED_LINE.containsMatchIn(it) || it.equals("closed", true) } ?: return
        if (failed.equals("closed", true)) throw InstallerException("$context: the TV closed the command before it finished.")
        if (isCertificateRejection(output)) {
            throw InstallerException("$context: $failed\n\nThe TV rejected the signed package (certificate mismatch or DUID mismatch).")
        }
        throw InstallerException("$context: $failed")
    }

    // ------------------------------------------------------------------ public actions

    /** Connects and reports the TV's DUID, Tizen version and installed Nuvio version. */
    fun probe(ip: String): TvInfo = connect(ip).use { client ->
        val duid = readDuid(client)
        val version = readPlatformVersion(client)
        if (version.isNotEmpty()) events.info("TV reports Tizen $version.")
        val app = runCatching { findApp(client, NUVIO_IDS) }.getOrNull()
        events.info(if (app != null) "Nuvio TV ${app["app_version"].orEmpty()} is installed." else "Nuvio TV is not installed.")
        val known = identities.candidatesFor(duid).isNotEmpty()
        events.info(if (known) "A signing certificate for this TV is saved on this device." else "No signing certificate for this TV is saved on this device yet.")
        TvInfo(duid, version, app)
    }

    fun install(ip: String, wgt: File) {
        events.stage(InstallStage.PACKAGE)
        val metadata = WgtPackage.validate(wgt, events)
        events.info("Package ${metadata.packageId} (${metadata.appId}) version ${metadata.version}.")

        events.stage(InstallStage.CONNECT)
        connect(ip).use { client ->
            val duid = readDuid(client)
            val platform = readPlatformVersion(client)
            if (platform.isNotEmpty() && metadata.requiredVersion.isNotEmpty()) {
                if ((compareVersions(platform, metadata.requiredVersion) ?: 0) < 0) {
                    throw InstallerException("This TV runs Tizen $platform but the package needs Tizen ${metadata.requiredVersion} or later.")
                }
                events.info("TV reports Tizen $platform; package needs ${metadata.requiredVersion}+.")
            }

            events.stage(InstallStage.SIGN)
            val installed = runCatching { findApp(client, NUVIO_IDS + metadata.packageId + metadata.appId) }.getOrNull()
            val preflight = CertificatePreflight.check(validCandidates(duid), installed)
            preflight.notes.forEach(events::info)
            preflight.blocked?.let { throw it }
            val candidates = preflight.usable.ifEmpty { listOf(createIdentity(duid)) }
            if (preflight.usable.isNotEmpty()) {
                events.info(if (candidates.size == 1) "Using the saved certificate for this TV." else "Found ${candidates.size} saved certificates for this TV.")
            }

            var lastError: Exception? = null
            for ((index, identity) in candidates.withIndex()) {
                val signed = File(workDirectory, "signed/${metadata.packageId}-${metadata.version}-signed.wgt")
                try {
                    events.info("Signing the package${if (candidates.size > 1) " (certificate ${index + 1}/${candidates.size})" else ""}...")
                    TizenSigner.signWgt(wgt, signed, identity.author(), identity.distributor())
                    events.success("Package signed.")
                    events.stage(InstallStage.UPLOAD)
                    upload(client, signed)
                    events.stage(InstallStage.INSTALL)
                    installUploaded(client, metadata)
                    identities.save(
                        identity.copy(
                            duid = duid,
                            lastSuccessfulInstallAt = java.time.Instant.now().toString(),
                            lastSuccessfulPackageId = metadata.packageId,
                        )
                    )
                    events.success("Nuvio TV ${metadata.version} is installed. Open it from the TV's Apps screen.")
                    return
                } catch (e: Exception) {
                    lastError = e
                    val rejected = e is InstallerException && isCertificateRejection(e.message.orEmpty())
                    if (rejected && index + 1 < candidates.size) {
                        events.info("The TV rejected this certificate; trying the next saved one.")
                        continue
                    }
                    if (rejected && installed != null) throw CertificatePreflight.rejectedUpdate(installed, e)
                    throw e
                } finally {
                    signed.delete()
                }
            }
            throw lastError ?: InstallerException("Installation failed.")
        }
    }

    private fun validCandidates(duid: String): List<SigningIdentity> =
        identities.candidatesFor(duid).filter { identity ->
            identity.hasValidAuthorChain().also { valid ->
                if (!valid) events.info("Ignoring a saved certificate for this TV with an invalid certificate chain.")
            }
        }

    /**
     * Creates the signing certificate for the TV at [ip] without installing anything: reads the
     * TV's DUID, then runs the Samsung sign-in. Returns false when a usable one is already saved.
     * Expired certificates do not count. Refuses when Nuvio is already on the TV under a
     * certificate this device does not have (or only has expired), because a new certificate
     * could never update that install.
     */
    fun ensureCertificate(ip: String): Boolean {
        val (duid, installed) = connect(ip).use { client ->
            readDuid(client) to runCatching { findApp(client, NUVIO_IDS) }.getOrNull()
        }
        val preflight = CertificatePreflight.check(validCandidates(duid), installed)
        preflight.notes.forEach(events::info)
        preflight.blocked?.let { throw it }
        if (preflight.usable.isNotEmpty()) {
            events.success("A certificate for this TV is already saved on this device.")
            return false
        }
        createIdentity(duid)
        return true
    }

    private fun createIdentity(duid: String): SigningIdentity {
        events.info("No certificate for this TV yet. Sign in with your Samsung account to create one.")
        val server = SamsungLoginServer(returnUrl = loginReturnUrl)
        val access = server.use {
            it.start()
            events.waitingForLogin(true)
            try {
                openLogin(SamsungLoginServer.AUTH_URL)
                it.await()
            } finally {
                events.waitingForLogin(false)
            }
        }
        events.success("Samsung login received.")
        val identity = certificates.createIdentity(access, duid, events)
        identities.save(identity)
        events.success("Samsung certificate created and saved for this TV. Back it up from the menu so future updates keep working.")
        return identity
    }

    private fun upload(client: SdbClient, signed: File) {
        val remote = "$REMOTE_DIR/nuvio-package.wgt"
        val setup = runCatching { shell(client, "0 mkdir -p $REMOTE_DIR", SdbClient.ShellOptions(noOutputSuccessMs = 1500)) }.getOrDefault("closed")
        if (!isClosedOutput(setup)) {
            runCatching { shell(client, "0 rm -f $remote", SdbClient.ShellOptions(noOutputSuccessMs = 1500)) }
        } else {
            events.info("The TV closed the folder setup command; continuing with the upload.")
        }
        val data = signed.readBytes()
        events.command("$ push ${signed.name} $remote")
        client.push(remote, data) { done, total -> events.progress("Uploading to TV", done, total) }

        val listing = runCatching {
            shell(client, "0 ls -l $remote", SdbClient.ShellOptions(idleAfterDataMs = 800, noOutputSuccessMs = 2500, timeoutMs = 10_000), log = false)
        }.getOrDefault("")
        val remoteSize = listing.trim().split(Regex("\\s+")).getOrNull(4)?.toLongOrNull() ?: 0L
        when {
            isClosedOutput(listing) || remoteSize == 0L -> events.info("Upload finished (${data.size} bytes).")
            remoteSize != data.size.toLong() -> throw InstallerException("Upload failed: the TV has $remoteSize bytes, expected ${data.size}.")
            else -> events.success("Uploaded ${data.size} bytes.")
        }
    }

    private fun installUploaded(client: SdbClient, metadata: PackageMetadata) {
        val remote = "$REMOTE_DIR/nuvio-package.wgt"
        val ids = listOf(metadata.packageId, metadata.appId).filter { it.isNotBlank() }.distinct()
        var certificateError: Exception? = null
        var lastError: Exception? = null
        for (id in ids) {
            try {
                events.info("Installing on the TV (this can take a minute)...")
                val output = shell(client, "0 vd_appinstall $id $remote", SdbClient.ShellOptions(timeoutMs = 180_000, completeWhen = COMMAND_COMPLETE))
                if (isPlatformIncompatibility(output)) throw InstallerException("This TV's Tizen version is too old for this Nuvio package.")
                failIfOutputFailed(output, "vd_appinstall $id")
                verifyInstalled(client, metadata)
                return
            } catch (e: InstallerException) {
                if (isPlatformIncompatibility(e.message.orEmpty())) throw e
                if (isCertificateRejection(e.message.orEmpty())) {
                    // 118012 is also returned for an unknown identifier; try the other id first.
                    certificateError = certificateError ?: e
                    continue
                }
                lastError = e
                events.error(e.message.orEmpty())
            }
        }
        throw certificateError ?: lastError ?: InstallerException("vd_appinstall failed.")
    }

    private fun verifyInstalled(client: SdbClient, metadata: PackageMetadata) {
        val app = try {
            findApp(client, listOf(metadata.packageId, metadata.appId))
        } catch (e: Exception) {
            throw InstallerException("The install command finished, but the installed app list could not be read to confirm it: ${e.message}")
        } ?: throw InstallerException("The install command finished but Nuvio TV is not listed on the TV. Retry the install.")
        val installed = app["app_version"].orEmpty()
        val cmp = compareVersions(installed, metadata.version)
        if (cmp != null && cmp != 0) {
            throw InstallerException("The TV still reports Nuvio TV $installed, expected ${metadata.version}. The update was not applied.")
        }
        events.success("Verified on the TV: ${app["app_id"] ?: metadata.appId} ${installed}".trim())
    }

    fun launch(ip: String) = connect(ip).use { client ->
        val app = findApp(client, NUVIO_IDS) ?: throw InstallerException("Nuvio TV is not installed on this TV.")
        val id = app["app_id"] ?: app["app_tizen_id"] ?: NUVIO_IDS.first()
        val output = shell(client, "0 was_execute $id", SdbClient.ShellOptions(timeoutMs = 30_000))
        failIfOutputFailed(output, "Launch failed")
        events.success("Nuvio TV launched on the TV.")
    }

    fun uninstall(ip: String) = connect(ip).use { client ->
        val app = findApp(client, NUVIO_IDS) ?: run {
            events.info("Nuvio TV is not installed on this TV.")
            return@use
        }
        val ids = listOfNotNull(app["app_package_id"], app["package_id"], app["app_tizen_id"]?.substringBefore('.'), app["app_id"])
            .filter { it.isNotBlank() }.distinct()
        for (id in ids) {
            val output = runCatching {
                shell(client, "0 vd_appuninstall $id", SdbClient.ShellOptions(timeoutMs = 120_000, completeWhen = UNINSTALL_COMPLETE))
            }.getOrElse { e -> events.info("vd_appuninstall $id: ${e.message}"); "" }
            if (findApp(client, NUVIO_IDS) == null) {
                events.success("Nuvio TV was removed from the TV.")
                return@use
            }
            if (output.isNotBlank()) events.info("The TV did not remove the app with id $id.")
        }
        throw InstallerException("The TV did not allow removing Nuvio over the network. Remove it from the TV instead: Apps > Settings (gear) > Nuvio TV > Delete.")
    }
}
