// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.cli

import tv.nuvio.installer.core.IdentityStore
import tv.nuvio.installer.core.InstallerEvents
import tv.nuvio.installer.core.LogLevel
import tv.nuvio.installer.core.SamsungCertificates
import tv.nuvio.installer.core.SigningIdentity
import tv.nuvio.installer.core.TizenInstaller
import tv.nuvio.installer.core.TizenSigner
import java.awt.Desktop
import java.io.File
import java.net.URI
import kotlin.system.exitProcess

/** Desktop test harness for the shared installer core. Data lives in ./cli-data. */
fun main(args: Array<String>) {
    val data = File(System.getenv("NUVIO_CLI_DATA") ?: "cli-data")
    val events = object : InstallerEvents {
        private var lastPercent = -1
        override fun log(level: LogLevel, text: String) = println("[${level.name.lowercase()}] $text")
        override fun progress(label: String, done: Long, total: Long) {
            if (total <= 0) return
            val percent = (done * 100 / total).toInt()
            if (percent / 10 != lastPercent / 10 || percent == 100) println("[progress] $label $percent%")
            lastPercent = percent
        }
    }
    val installer = TizenInstaller(
        File(data, "work"), IdentityStore(File(data, "identities")), SamsungCertificates(File(data, "ca")), events,
        openLogin = { url ->
        println("Open this URL to sign in to Samsung: $url")
        runCatching { Desktop.getDesktop().browse(URI(url)) }
    })
    try {
        when (args.getOrNull(0)) {
            "probe" -> println(installer.probe(args[1]))
            "install" -> installer.install(args[1], File(args[2]))
            "ca" -> { SamsungCertificates(File(data, "ca")).ensureAuthorities(events); println(File(data, "ca").list()?.sorted()) }
            "releases" -> tv.nuvio.installer.core.GitHubReleases().recent().forEach { println("${it.label} -> ${it.wgtAsset?.name} ${it.wgtAsset?.digest}") }
            "validate" -> println(tv.nuvio.installer.core.WgtPackage.validate(File(args[1]), events))
            "launch" -> installer.launch(args[1])
            "uninstall" -> installer.uninstall(args[1])
            "import" -> println("Imported identity for DUID " + IdentityStore(File(data, "identities")).import(File(args[1]).readText()).duid)
            "sign" -> {
                val identity = SigningIdentity.parse(File(args[2]).readText())
                TizenSigner.signWgt(File(args[1]), File(args[3]), identity.author(), identity.distributor())
                println("Signed ${args[3]} (author chain valid: ${identity.hasValidAuthorChain()})")
            }
            else -> {
                println("usage: probe <ip> | install <ip> <wgt> | launch <ip> | uninstall <ip> | import <identity.json> | sign <in.wgt> <identity.json> <out.wgt>")
                exitProcess(2)
            }
        }
    } catch (e: Exception) {
        println("[failed] ${e.message}")
        exitProcess(1)
    }
}
