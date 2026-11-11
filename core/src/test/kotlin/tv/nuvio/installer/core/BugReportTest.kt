// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BugReportTest {
    private val info = BugReportInfo("1.4.0", "Google Pixel 8, Android 16", "UA65CUE70AKLXL, Tizen 7.0", "1.2.0")

    @Test
    fun redactsPrivateDetails() {
        val log = """
            [info] TV DUID: 43CLIM2Y3FMSU
            [info] Using the saved certificate for 43CLIM2Y3FMSU.
            [info] Signed in as someone.name+tv@example.co.in
            [info] wss://192.168.1.9:8002/api?name=VFYgSW5zdGFsbGVy&token=12345678
            [info] access_token: "aBcD1234efGH5678"
            [info] MIIKAgIBAzCCCbwGCSqGSIb3DQEHAaCCCa0EggmpMIIJpTCCBBcGCSqGSIb3DQEHBqCCBAgwggQEAgEA
            [error] Connection reset by 192.168.1.9:26101
        """.trimIndent()
        val out = BugReport.redact(log, secrets = listOf("43CLIM2Y3FMSU"))
        assertFalse(out.contains("43CLIM2Y3FMSU"))
        assertFalse(out.contains("example.co.in"))
        assertFalse(out.contains("12345678"))
        assertFalse(out.contains("aBcD1234efGH5678"))
        assertFalse(out.contains("MIIKAgIBAzCC"))
        // Local IPs and the error itself stay: they are what the report is for.
        assertTrue(out.contains("Connection reset by 192.168.1.9:26101"))
        assertTrue(out.contains("TV DUID: [hidden]"))
    }

    @Test
    fun duidIsHiddenEvenWhenNotPassed() {
        assertEquals("[info] TV DUID: [hidden]", BugReport.redact("[info] TV DUID: 43CLIM2Y3FMSU"))
    }

    @Test
    fun issueUrlFillsTheForm() {
        val url = BugReport.issueUrl("owner/repo", info, listOf("[error] Upload failed"))
        assertTrue(url.startsWith("https://github.com/owner/repo/issues/new?template=bug_report.yml"))
        val params = url.substringAfter("?").split("&").associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }
        assertEquals("1.4.0", params["app-version"])
        assertEquals("Google Pixel 8, Android 16", params["phone"])
        assertEquals("UA65CUE70AKLXL, Tizen 7.0, Nuvio 1.2.0", params["tv"])
        assertEquals("[error] Upload failed", params["log"])
    }

    @Test
    fun longLogsKeepTheNewestLines() {
        val log = (1..2000).map { "[info] line $it with some text to make it longer" }
        val url = BugReport.issueUrl("owner/repo", info, log)
        assertTrue(url.length <= BugReport.MAX_URL, "length ${url.length}")
        val text = URLDecoder.decode(url.substringAfter("&log="), "UTF-8")
        assertTrue(text.startsWith("(earlier lines left out)"))
        assertTrue(text.endsWith("line 2000 with some text to make it longer"))
    }

    @Test
    fun plainTextReport() {
        val text = BugReport.text(info.copy(tv = "", nuvioOnTv = ""), listOf("[info] a", "[error] b"))
        assertEquals("App version: 1.4.0\nPhone: Google Pixel 8, Android 16\n\nActivity log:\n[info] a\n[error] b", text)
    }
}
