// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import java.net.URLEncoder

/** What a bug report says about the setup. Blank fields are left out. */
data class BugReportInfo(
    val appVersion: String,
    val phone: String,
    val tv: String,
    val nuvioOnTv: String = "",
)

/**
 * Builds bug reports for the GitHub issue form (.github/ISSUE_TEMPLATE/bug_report.yml), with
 * private details taken out of the activity log first.
 */
object BugReport {
    /** Longest issue URL to open; GitHub and browsers cope with about 8 KB. */
    const val MAX_URL = 7_500

    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val tokenParam = Regex("((?:access_)?token[\"']?\\s*[=:]\\s*[\"']?)[A-Za-z0-9._~+/=-]+", RegexOption.IGNORE_CASE)
    private val blob = Regex("[A-Za-z0-9+/=_-]{48,}")
    private val duidLine = Regex("(DUID:?\\s*)[A-Z0-9]{8,}", RegexOption.IGNORE_CASE)

    /** Removes [secrets] (DUIDs, tokens), e-mail addresses, token values and long encoded data. */
    fun redact(text: String, secrets: Collection<String> = emptyList()): String {
        var out = text
        for (secret in secrets.filter { it.length >= 4 }.sortedByDescending { it.length }) out = out.replace(secret, "[hidden]")
        out = email.replace(out, "[email]")
        out = tokenParam.replace(out) { it.groupValues[1] + "[hidden]" }
        out = duidLine.replace(out) { it.groupValues[1] + "[hidden]" }
        out = blob.replace(out, "[data]")
        return out
    }

    /** The full report as plain text, for copying or sharing. */
    fun text(info: BugReportInfo, log: List<String>): String = buildString {
        appendLine("App version: ${info.appVersion}")
        appendLine("Phone: ${info.phone}")
        if (info.tv.isNotBlank()) appendLine("TV: ${info.tv}")
        if (info.nuvioOnTv.isNotBlank()) appendLine("Nuvio on TV: ${info.nuvioOnTv}")
        appendLine()
        appendLine("Activity log:")
        log.forEach { appendLine(it) }
    }.trimEnd()

    /**
     * A link that opens the bug report form with the fields filled in. The log keeps its newest
     * lines when the whole log would make the link too long.
     */
    fun issueUrl(repo: String, info: BugReportInfo, log: List<String>, maxLength: Int = MAX_URL): String {
        val tv = listOf(info.tv, info.nuvioOnTv.takeIf { it.isNotBlank() }?.let { "Nuvio $it" }).filter { !it.isNullOrBlank() }.joinToString(", ")
        val base = "https://github.com/$repo/issues/new?template=bug_report.yml" +
            "&labels=bug" +
            "&app-version=" + encode(info.appVersion) +
            "&phone=" + encode(info.phone) +
            (if (tv.isNotBlank()) "&tv=" + encode(tv) else "")
        var lines = log
        while (true) {
            val body = if (lines.size < log.size) listOf("(earlier lines left out)") + lines else lines
            val url = if (body.isEmpty()) base else base + "&log=" + encode(body.joinToString("\n"))
            if (url.length <= maxLength || lines.isEmpty()) return url
            lines = lines.drop(maxOf(1, lines.size / 10))
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
