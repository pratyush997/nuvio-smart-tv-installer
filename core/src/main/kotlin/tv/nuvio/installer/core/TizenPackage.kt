// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import org.w3c.dom.Document
import tv.nuvio.installer.core.SafeXml.elements
import java.io.File
import java.util.zip.ZipFile

/**
 * A Samsung package of either kind: a web app (.wgt, described by config.xml) or a native app
 * (.tpk, described by tizen-manifest.xml). Both are zip files and are signed the same way.
 */
enum class PackageKind(val extension: String) { WGT("wgt"), TPK("tpk") }

/**
 * What a picked package is. [name] is the app's own name from its manifest (may be empty).
 * [known] is the Nuvio app this file is, or null for any other app. [size] is in bytes.
 */
data class PackageInfo(val kind: PackageKind, val metadata: PackageMetadata, val name: String, val known: NuvioApp?, val size: Long) {
    /** What to call the app: its name, else its package id. */
    val label: String get() = name.ifBlank { metadata.packageId }
}

object TizenPackage {
    private const val PACKAGES_NS = "http://tizen.org/ns/packages"
    private val PACKAGE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    private val WGT_PACKAGE_ID = Regex("^[A-Za-z0-9]{10}$")
    private val WGT_APP_NAME = Regex("^[A-Za-z0-9_-]{1,52}$")
    /** "1", "1.0", "1.2.3" or "1.2.3.4"; empty is allowed (the TV decides). */
    private val VERSION = Regex("^(\\d{1,5}(\\.\\d{1,5}){0,3})?$")
    private const val MAX_NAME = 60

    /** What the zip contains decides the kind, not the file name (a picked file can be named anything). */
    fun kind(file: File): PackageKind = try {
        ZipFile(file).use { zip ->
            when {
                zip.getEntry("config.xml") != null -> PackageKind.WGT
                zip.getEntry("tizen-manifest.xml") != null -> PackageKind.TPK
                else -> throw InstallerException("This is not a Samsung TV app: it has neither config.xml nor tizen-manifest.xml.")
            }
        }
    } catch (e: java.util.zip.ZipException) {
        throw InstallerException("This is not a Samsung TV app (.wgt or .tpk file).", e)
    }

    /** Reads a .tpk's package id, app id, version and minimum Tizen version from tizen-manifest.xml. */
    fun tpkMetadata(file: File): PackageMetadata = tpkInfo(file).first

    fun tpkMetadataFromManifest(text: String): PackageMetadata = tpkMetadata(parseManifest(text))

    private fun tpkInfo(file: File): Pair<PackageMetadata, String> {
        val text = ZipFile(file).use { zip ->
            val entry = zip.getEntry("tizen-manifest.xml") ?: throw InstallerException("Invalid Samsung package: tizen-manifest.xml is missing.")
            zip.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) }
        }
        val doc = parseManifest(text)
        return tpkMetadata(doc) to SafeXml.displayText(doc.elements(PACKAGES_NS, "label"))
    }

    private fun parseManifest(text: String): Document {
        val doc = runCatching { SafeXml.parse(text) }.getOrElse { throw InstallerException("tizen-manifest.xml could not be read.", it) }
        if (doc.documentElement.localName != "manifest") throw InstallerException("tizen-manifest.xml has no <manifest> element.")
        return doc
    }

    private fun tpkMetadata(doc: Document): PackageMetadata {
        val root = doc.documentElement
        val packageId = root.getAttribute("package").trim()
        if (!PACKAGE_ID.matches(packageId)) throw InstallerException("Unexpected Samsung package id \"${packageId.take(MAX_NAME)}\".")
        val appId = listOf("ui-application", "service-application", "widget-application", "watch-application")
            .flatMap { name -> doc.elements(PACKAGES_NS, name).map { it.getAttribute("appid").trim() } }
            .firstOrNull { it.isNotEmpty() } ?: packageId
        return PackageMetadata(
            packageId = packageId,
            appId = appId,
            version = root.getAttribute("version").trim(),
            requiredVersion = root.getAttribute("api-version").trim(),
        )
    }

    /**
     * Throws unless the ids and versions look like Samsung's: they go into commands run on the
     * TV, so anything else is refused, whatever app the file is.
     */
    fun checkIds(kind: PackageKind, metadata: PackageMetadata) {
        val (packageId, appId) = metadata
        val idsOk = when (kind) {
            PackageKind.WGT -> WGT_PACKAGE_ID.matches(packageId) &&
                appId.startsWith("$packageId.") && WGT_APP_NAME.matches(appId.removePrefix("$packageId."))
            PackageKind.TPK -> PACKAGE_ID.matches(packageId) && PACKAGE_ID.matches(appId)
        }
        if (!idsOk) {
            throw InstallerException("This file has an unusual app id (\"${appId.take(MAX_NAME)}\"), so it can't be installed.")
        }
        if (!VERSION.matches(metadata.version)) {
            throw InstallerException("This file has an unusual version (\"${metadata.version.take(MAX_NAME)}\"), so it can't be installed.")
        }
        if (!VERSION.matches(metadata.requiredVersion)) {
            throw InstallerException("This file names an unusual Tizen version (\"${metadata.requiredVersion.take(MAX_NAME)}\"), so it can't be installed.")
        }
    }

    /**
     * The Nuvio app a package with [metadata] is, or null for any other app. Legacy's ids are
     * exact. Nuvio TV's list has loose ones like "Nuvio", so only a .wgt counts as Nuvio TV.
     */
    fun knownApp(kind: PackageKind, metadata: PackageMetadata): NuvioApp? {
        val ids = listOf(metadata.packageId, metadata.appId)
        return when {
            ids.any { it in NuvioApp.LEGACY.ids } -> NuvioApp.LEGACY
            kind == PackageKind.WGT && ids.any { it in NuvioApp.TV.ids } -> NuvioApp.TV
            else -> null
        }
    }

    /** Opens any picked package: what it is, its name, and which Nuvio app it is, if any. */
    fun inspect(file: File): PackageInfo {
        val kind = kind(file)
        val (metadata, name) = if (kind == PackageKind.TPK) tpkInfo(file) else WgtPackage.info(file)
        checkIds(kind, metadata)
        return PackageInfo(kind, metadata, name.take(MAX_NAME).trim(), knownApp(kind, metadata), file.length())
    }

    /**
     * Reads (and, for Nuvio TV, validates) the package for [app]'s card. A file that is not [app]
     * is refused. Only Nuvio TV's .wgt gets the desktop installer's service checks.
     */
    fun read(file: File, app: NuvioApp, events: InstallerEvents): Pair<PackageKind, PackageMetadata> {
        val kind = kind(file)
        val metadata = if (kind == PackageKind.TPK) tpkMetadata(file) else WgtPackage.metadata(file)
        if (metadata.packageId.isBlank() || metadata.appId.isBlank()) throw InstallerException("The package has no package or app id.")
        checkIds(kind, metadata)
        // Before Nuvio TV's own checks, so a Legacy file gets this message rather than a list of missing services.
        when (val known = knownApp(kind, metadata)) {
            app -> Unit
            null -> throw InstallerException("This file is not ${app.label}. To install another app, use the Another app card.")
            else -> throw InstallerException("This file is ${known.label}, but ${app.label} is selected. Choose ${known.label} first, then install.")
        }
        if (kind == PackageKind.WGT && app == NuvioApp.TV) return kind to WgtPackage.validate(file, events)
        return kind to metadata
    }
}
