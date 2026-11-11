// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReleaseNotesTest {
    private val body = """
        <!-- Release notes generated -->
        ## What's Changed
        * **Player**: faster start with [ExoPlayer-like](https://example.com) buffering by @someone in https://github.com/x/y/pull/12
        * Fix `subtitle` offset &amp; sync
        - Added _Continue watching_ row

        ![screenshot](https://example.com/a.png)

        ### Known issues
        ---
        1. Tizen 4 TVs may need a restart.

        **Full Changelog**: https://github.com/x/y/compare/v1.2.0...v1.3.0
    """.trimIndent()

    @Test
    fun parsesHeadingsBulletsAndText() {
        val lines = ReleaseNotes.parse(body)
        assertEquals(
            listOf(
                NoteLine("What's Changed", NoteLine.Kind.HEADING),
                NoteLine("Player: faster start with ExoPlayer-like buffering by @someone in https://github.com/x/y/pull/12", NoteLine.Kind.BULLET),
                NoteLine("Fix subtitle offset & sync", NoteLine.Kind.BULLET),
                NoteLine("Added Continue watching row", NoteLine.Kind.BULLET),
                NoteLine("Known issues", NoteLine.Kind.HEADING),
                NoteLine("Tizen 4 TVs may need a restart.", NoteLine.Kind.BULLET),
            ),
            lines,
        )
    }

    @Test
    fun emptyBodies() {
        assertTrue(ReleaseNotes.parse(null).isEmpty())
        assertTrue(ReleaseNotes.parse("  \n ").isEmpty())
        assertEquals("", ReleaseNotes.summary(null))
    }

    @Test
    fun headingWithoutContentIsDropped() {
        assertEquals(
            listOf(NoteLine("Next", NoteLine.Kind.HEADING), NoteLine("Only text", NoteLine.Kind.TEXT)),
            ReleaseNotes.parse("## Empty\n\n## Next\nOnly text\n## Trailing"),
        )
    }

    @Test
    fun summaryTakesTheFirstPoints() {
        assertEquals(
            "• Player: faster start with ExoPlayer-like buffering by @someone in https://github.com/x/y/pull/12\n• Fix subtitle offset & sync",
            ReleaseNotes.summary(body, max = 2),
        )
    }

    @Test
    fun releaseJsonKeepsTheBody() {
        val release = Json { ignoreUnknownKeys = true }.decodeFromString(Release.serializer(), """{"id":1,"tag_name":"v1.3.0","body":"* one"}""")
        assertEquals("* one", release.body)
    }
}
