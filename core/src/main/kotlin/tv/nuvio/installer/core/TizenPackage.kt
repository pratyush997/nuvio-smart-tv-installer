// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A Samsung package of either kind: a web app (.wgt, described by config.xml) or a native app
 * (.tpk, described by tizen-manifest.xml). Both are zip files and are signed the same way.
 */
enum class PackageKind(val extension: String) { WGT("wgt"), TPK("tpk") }

object TizenPackage {
    private const val PACKAGES_NS = "http://tizen.org/ns/packages"
    private val PACKAGE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

    /** What the zip contains decides the kind, not the file name (a picked file can be named anything). */
    fun kind(file: File): PackageKind = try {
        ZipFile(file).use { zip ->
            when {
                zip.getEntry("config.xml") != null -> PackageKind.WGT
                zip.getEntry("tizen-manifest.xml") != null -> PackageKind.TPK
                else -> throw InstallerException("This is not a Samsung app: it has neither config.xml nor tizen-manifest.xml.")
            }
        }
    } catch (e: java.util.zip.ZipException) {
        throw InstallerException("This is not a Samsung app package (.wgt or .tpk).", e)
    }

    /** Reads a .tpk's package id, app id, version and minimum Tizen version from tizen-manifest.xml. */
    fun tpkMetadata(file: File): PackageMetadata {
        val text = ZipFile(file).use { zip ->
            val entry = zip.getEntry("tizen-manifest.xml") ?: throw InstallerException("Invalid Samsung package: tizen-manifest.xml is missing.")
            zip.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) }
        }
        return tpkMetadataFromManifest(text)
    }

    fun tpkMetadataFromManifest(text: String): PackageMetadata {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val doc = runCatching { factory.newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))) }
            .getOrElse { throw InstallerException("tizen-manifest.xml could not be read.", it) }
        val root = doc.documentElement
        if (root.localName != "manifest") throw InstallerException("tizen-manifest.xml has no <manifest> element.")
        val packageId = root.getAttribute("package").trim()
        if (!PACKAGE_ID.matches(packageId)) throw InstallerException("Unexpected Samsung package id \"$packageId\".")
        val appId = listOf("ui-application", "service-application", "widget-application", "watch-application")
            .flatMap { name ->
                val nodes = doc.getElementsByTagNameNS(PACKAGES_NS, name)
                (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("appid").trim() }
            }
            .firstOrNull { it.isNotEmpty() } ?: packageId
        return PackageMetadata(
            packageId = packageId,
            appId = appId,
            version = root.getAttribute("version").trim(),
            requiredVersion = root.getAttribute("api-version").trim(),
        )
    }

    /**
     * Reads (and, for Nuvio TV, validates) the package. Only Nuvio TV's .wgt gets the desktop
     * installer's service checks; other packages just need readable ids.
     */
    fun read(file: File, app: NuvioApp, events: InstallerEvents): Pair<PackageKind, PackageMetadata> {
        val kind = kind(file)
        val metadata = if (kind == PackageKind.TPK) tpkMetadata(file) else WgtPackage.metadata(file)
        if (metadata.packageId.isBlank() || metadata.appId.isBlank()) throw InstallerException("The package has no package or app id.")
        // Before Nuvio TV's own checks, so a Legacy file gets this message rather than a list of missing services.
        val other = NuvioApp.entries.firstOrNull { it != app && (metadata.packageId in it.ids || metadata.appId in it.ids) }
        // "Nuvio" is one of Nuvio TV's loose ids; Legacy's own ids are exact, so only they decide.
        if (other == NuvioApp.LEGACY || (other == NuvioApp.TV && metadata.packageId.startsWith("NuvioTV001"))) {
            throw InstallerException("This file is ${other.label}, but ${app.label} is selected. Choose ${other.label} first, then install.")
        }
        if (kind == PackageKind.WGT && app == NuvioApp.TV) return kind to WgtPackage.validate(file, events)
        return kind to metadata
    }
}
