// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses the XML inside a package (config.xml, tizen-manifest.xml). Any file can be picked now,
 * so external entities and DTDs are never loaded. Each feature is optional: Android's parser
 * does not support all of them.
 */
internal object SafeXml {
    fun parse(text: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        runCatching { factory.isExpandEntityReferences = false }
        runCatching { factory.isXIncludeAware = false }
        listOf(
            "http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities",
            "http://apache.org/xml/features/nonvalidating/load-external-dtd",
        ).forEach { feature -> runCatching { factory.setFeature(feature, false) } }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
    }

    fun Document.elements(ns: String, local: String): List<Element> {
        val nodes = getElementsByTagNameNS(ns, local)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    fun Element.elements(ns: String, local: String): List<Element> {
        val nodes = getElementsByTagNameNS(ns, local)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    /** The text of the first of [elements] without xml:lang (the default language), else of the first one. */
    fun displayText(elements: List<Element>): String {
        val element = elements.firstOrNull { !it.hasAttributeNS("http://www.w3.org/XML/1998/namespace", "lang") } ?: elements.firstOrNull()
        return element?.textContent.orEmpty().replace(Regex("\\s+"), " ").trim()
    }
}
