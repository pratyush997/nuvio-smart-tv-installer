// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

/** One line of release notes, as plain text. */
data class NoteLine(val text: String, val kind: Kind) {
    enum class Kind { HEADING, BULLET, TEXT }
}

/**
 * Turns a GitHub release body (Markdown) into plain lines for the app: headings, bullets and
 * paragraphs, without links, images, HTML or emphasis marks.
 */
object ReleaseNotes {
    private val comment = Regex("<!--[\\s\\S]*?-->")
    private val image = Regex("!\\[[^\\]]*]\\([^)]*\\)")
    private val link = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val autolink = Regex("<(https?://[^>]+)>")
    private val html = Regex("</?[a-zA-Z][^>]*>")
    private val emphasis = Regex("(\\*\\*|__|\\*|_|~~|`)(?=\\S)(.+?)(?<=\\S)\\1")
    private val heading = Regex("^#{1,6}\\s+")
    private val bullet = Regex("^(?:[-*+]|\\d+[.)])\\s+")
    private val rule = Regex("^(?:-{3,}|\\*{3,}|_{3,})$")
    private val bareUrl = Regex("^https?://\\S+$")

    fun parse(markdown: String?): List<NoteLine> {
        if (markdown.isNullOrBlank()) return emptyList()
        val text = comment.replace(markdown.replace("\r", ""), "")
        val lines = ArrayList<NoteLine>()
        for (raw in text.lines()) {
            var line = raw.trim()
            if (line.isEmpty() || rule.matches(line) || line.startsWith("```") || line.startsWith("|")) continue
            line = line.removePrefix(">").trim()
            val kind = when {
                heading.containsMatchIn(line) -> NoteLine.Kind.HEADING
                bullet.containsMatchIn(line) -> NoteLine.Kind.BULLET
                else -> NoteLine.Kind.TEXT
            }
            line = line.replace(heading, "").replace(bullet, "")
            line = image.replace(line, "")
            line = link.replace(line) { it.groupValues[1] }
            line = autolink.replace(line) { it.groupValues[1] }
            line = html.replace(line, "")
            repeat(2) { line = emphasis.replace(line) { it.groupValues[2] } }
            line = line.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim()
            if (line.isEmpty() || bareUrl.matches(line)) continue
            if (kind == NoteLine.Kind.HEADING && line.equals("Full Changelog", ignoreCase = true)) continue
            if (line.startsWith("Full Changelog", ignoreCase = true)) continue
            lines += NoteLine(line, kind)
        }
        // Headings with nothing under them say nothing.
        return lines.filterIndexed { i, l -> l.kind != NoteLine.Kind.HEADING || lines.getOrNull(i + 1)?.kind.let { it != null && it != NoteLine.Kind.HEADING } }
    }

    private val setupSection = Regex("^(?:install|download|checksum|verify|sha-?256)", RegexOption.IGNORE_CASE)

    /**
     * Only what changed: drops the intro before the first heading and the install, download and
     * checksum sections (and anything under them, like credits). Falls back to every line when
     * that leaves nothing, so notes without headings still show.
     */
    fun changes(markdown: String?): List<NoteLine> {
        val lines = parse(markdown)
        val kept = ArrayList<NoteLine>()
        var keep = false
        for (line in lines) {
            if (line.kind == NoteLine.Kind.HEADING) keep = !setupSection.containsMatchIn(line.text)
            if (keep) kept += line
        }
        return kept.ifEmpty { lines }
    }

    /** A short plain summary for a notification: the first [max] points. */
    fun summary(markdown: String?, max: Int = 3): String =
        parse(markdown).filter { it.kind != NoteLine.Kind.HEADING }.take(max).joinToString("\n") { "• ${it.text}" }
}
