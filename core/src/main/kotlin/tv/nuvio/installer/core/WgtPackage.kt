package tv.nuvio.installer.core

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

data class PackageMetadata(
    val packageId: String,
    val appId: String,
    val version: String,
    val requiredVersion: String,
)

/** Reads and validates a Nuvio TV Tizen widget (.wgt), mirroring the desktop installer's checks. */
object WgtPackage {
    private const val WIDGETS_NS = "http://www.w3.org/ns/widgets"
    private const val TIZEN_NS = "http://tizen.org/ns/widgets"
    private const val ENGINEFS_SERVICE = "services/tizen/enginefs-service.js"
    private val ENGINEFS_RUNTIME = listOf(
        "services/tizen/runtime/media-http.cjs",
        "services/tizen/runtime/tx3g-subtitle-parser.cjs",
        "services/tizen/runtime/tx3g-subtitle-service.cjs",
        "services/tizen/runtime/embedded-text-subtitle-parser.cjs",
    )
    private const val ENGINEFS_PORT = 2710
    private const val PLUGIN_SERVICE = "services/tizen/plugin-service.js"
    private const val PLUGIN_HTTP = "services/plugin-http.cjs"
    private const val PLUGIN_PORT = 2711
    private const val PLUGIN_BRIDGE = "services/tizen/wrt-service-bridge.js"
    private val FALLBACK_PORT_LOGIC = Regex("11470|11471|FALLBACK_PORT|candidateIndex")

    private class Contents(val files: Map<String, ByteArray>) {
        fun has(name: String) = files.containsKey(name)
        fun text(name: String) = files[name]?.toString(Charsets.UTF_8)
    }

    private fun load(file: File): Contents = ZipFile(file).use { zip ->
        Contents(zip.entries().asSequence().filter { !it.isDirectory }.associate { e -> e.name to zip.getInputStream(e).use { it.readBytes() } })
    }

    private fun parseXml(text: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
    }

    private fun Document.elements(ns: String, local: String): List<Element> {
        val nodes = getElementsByTagNameNS(ns, local)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.elements(ns: String, local: String): List<Element> {
        val nodes = getElementsByTagNameNS(ns, local)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    fun metadata(file: File): PackageMetadata {
        val config = ZipFile(file).use { zip ->
            val entry = zip.getEntry("config.xml") ?: throw InstallerException("Invalid Samsung package: config.xml is missing.")
            zip.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) }
        }
        return metadataFromConfig(config)
    }

    private fun metadataFromConfig(config: String): PackageMetadata {
        val doc = parseXml(config)
        val application = doc.elements(TIZEN_NS, "application").firstOrNull()
            ?: throw InstallerException("config.xml does not contain tizen:application.")
        return PackageMetadata(
            packageId = application.getAttribute("package"),
            appId = application.getAttribute("id"),
            version = doc.documentElement.getAttribute("version"),
            requiredVersion = application.getAttribute("required_version"),
        )
    }

    /** Throws when the package lacks Nuvio's local Tizen services (same rules as the desktop installer). */
    fun validate(file: File, events: InstallerEvents): PackageMetadata {
        val zip = load(file)
        val configText = zip.text("config.xml") ?: throw InstallerException("Invalid Samsung WGT: config.xml is missing.")
        val metadata = metadataFromConfig(configText)
        if (!Regex("^[A-Za-z0-9]{10}$").matches(metadata.packageId) || !metadata.appId.startsWith("${metadata.packageId}.")) {
            throw InstallerException("Unexpected Samsung package id \"${metadata.packageId}\" / app id \"${metadata.appId}\".")
        }
        val doc = parseXml(configText)
        val features = doc.elements(WIDGETS_NS, "feature")
        val services = doc.elements(TIZEN_NS, "service")
        fun serviceWithContent(src: String, id: String? = null) = services.firstOrNull { service ->
            (id == null || service.getAttribute("id") == id) &&
                service.elements(TIZEN_NS, "content").any { it.getAttribute("src") == src }
        }
        val hasWebService = features.any { it.getAttribute("name") == "http://tizen.org/feature/web.service" }

        // EngineFS (local torrent / P2P) service
        val missing = ArrayList<String>()
        if (!zip.has("main.js")) missing += "main.js"
        (listOf(ENGINEFS_SERVICE) + ENGINEFS_RUNTIME).filterNot { zip.has(it) }.forEach { missing += it }
        val engineFs = zip.text(ENGINEFS_SERVICE).orEmpty()
        if (!Regex("process\\.env\\.PORT\\s*=\\s*process\\.env\\.PORT\\s*\\|\\|\\s*[\"']$ENGINEFS_PORT[\"']").containsMatchIn(engineFs)) {
            missing += "EngineFS fixed port $ENGINEFS_PORT"
        }
        if (FALLBACK_PORT_LOGIC.containsMatchIn(engineFs)) missing += "EngineFS fallback port logic"
        if (!hasWebService) missing += "config.xml feature http://tizen.org/feature/web.service"
        if (serviceWithContent(ENGINEFS_SERVICE) == null) missing += "config.xml tizen:service -> $ENGINEFS_SERVICE"
        if (missing.isNotEmpty()) throw InstallerException("Samsung WGT is missing the local Tizen P2P Web Service: ${missing.joinToString(", ")}.")
        events.info("Package includes the local Tizen P2P Web Service.")

        // Plugin service (only when the package ships or enables it)
        val mainJs = zip.text("main.js").orEmpty()
        val pluginEnabledFlag = Regex("__NUVIO_TIZEN_PLUGIN_SERVICE_ENABLED__\\s*=\\s*true\\b")
        val pluginEnabled = pluginEnabledFlag.containsMatchIn(mainJs)
        if (pluginEnabled || zip.has(PLUGIN_SERVICE) || zip.has(PLUGIN_HTTP) || zip.has(PLUGIN_BRIDGE)) {
            val pm = ArrayList<String>()
            if (!zip.has(PLUGIN_SERVICE)) pm += PLUGIN_SERVICE
            if (!zip.has(PLUGIN_HTTP)) pm += PLUGIN_HTTP
            val pluginSource = zip.text(PLUGIN_SERVICE).orEmpty()
            if (!pluginSource.contains("require(\"../plugin-http.cjs\")")) pm += "PluginService canonical relative plugin-http loader"
            if (!pluginSource.contains("var DEFAULT_PORT = $PLUGIN_PORT;")) pm += "PluginService fixed port $PLUGIN_PORT"
            if (FALLBACK_PORT_LOGIC.containsMatchIn(pluginSource)) pm += "PluginService fallback port logic"
            val pluginServiceId = "${metadata.packageId}.PluginService"
            if (!hasWebService) pm += "config.xml feature http://tizen.org/feature/web.service"
            val privileges = doc.elements(TIZEN_NS, "privilege")
            if (privileges.none { it.getAttribute("name") == "http://tizen.org/privilege/application.launch" }) {
                pm += "config.xml privilege http://tizen.org/privilege/application.launch"
            }
            if (serviceWithContent(PLUGIN_SERVICE, pluginServiceId) == null) pm += "config.xml tizen:service -> $pluginServiceId"
            val indexHtml = zip.text("index.html").orEmpty()
            val bridge = Regex(
                "<script\\b[^>]*\\btype=[\"']module[\"'][^>]*>[\\s\\S]*?\\bimport\\s+\\*\\s+as\\s+service\\s+from\\s+[\"']wrt:service[\"']\\s*;?",
                RegexOption.IGNORE_CASE
            )
            if (!bridge.containsMatchIn(indexHtml)) pm += "index.html wrt:service module bridge"
            if (zip.has(PLUGIN_BRIDGE)) pm += "$PLUGIN_BRIDGE must not be packaged"
            if (!pluginEnabled) pm += "main.js PluginService enable flag"
            if (!mainJs.contains("\"$pluginServiceId\"")) pm += "main.js PluginService id $pluginServiceId"
            listOf(
                "services/tizen/plugin-http.cjs",
                "assets/libs/quickjs-emscripten-legacy.global.js",
                "assets/libs/quickjs-emscripten-legacy.LICENSE",
            ).filter { zip.has(it) }.forEach { pm += "$it must not be packaged" }
            if (Regex("require\\([\"']\\./plugin-service\\.js[\"']\\)").containsMatchIn(engineFs)) {
                pm += "independent EngineFS and PluginService lifecycles"
            }
            if (pm.isNotEmpty()) throw InstallerException("Samsung WGT is missing the local Tizen Plugin Web Service contract: ${pm.joinToString(", ")}.")
            events.info("Package includes the local Tizen Plugin Web Service.")
        }
        return metadata
    }
}
