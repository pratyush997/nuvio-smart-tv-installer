// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SavedTvsTest {
    private val living = SavedTv("192.168.1.9", "Living Room", "QA55", id = "uuid-1", duid = "DUID1", installedVersion = "1.3.10")
    private val bedroom = SavedTv("192.168.1.20", "Bedroom", "UA43", id = "uuid-2", duid = "DUID2")

    @Test
    fun roundTripsThroughJson() {
        val list = listOf(living, bedroom)
        assertEquals(list, SavedTvs.decode(SavedTvs.encode(list)))
    }

    @Test
    fun tvsSavedBeforeBrandsAreSamsungAndLgRoundTrips() {
        val old = SavedTvs.decode("""[{"ip":"192.168.1.9","name":"Living Room"}]""")
        assertEquals(TvBrand.SAMSUNG, old.single().brand)
        val lg = listOf(bedroom.copy(brand = TvBrand.LG))
        assertEquals(lg, SavedTvs.decode(SavedTvs.encode(lg)))
    }

    @Test
    fun brokenOrMissingJsonIsAnEmptyList() {
        assertEquals(emptyList(), SavedTvs.decode(null))
        assertEquals(emptyList(), SavedTvs.decode("not json"))
    }

    @Test
    fun aTvThatMovedReplacesItsEntryInPlace() {
        val moved = living.copy(ip = "192.168.1.30", installedVersion = "1.3.11")
        val result = SavedTvs.upsert(listOf(living, bedroom), moved)
        assertEquals(listOf(moved, bedroom), result)
    }

    @Test
    fun unknownFieldsAreKeptAndVersionIsReplaced() {
        // The info page answered but the installer connection did not: no DUID, no version.
        val partial = SavedTv("192.168.1.9", "Living Room", "QA55", id = "uuid-1")
        val result = SavedTvs.upsert(listOf(living), partial).single()
        assertEquals("DUID1", result.duid)
        assertNull(result.installedVersion)
    }

    @Test
    fun aCheckKeepsTheReleaseTheAppInstalled() {
        // A check knows only the number the TV reports; the beta the app installed is kept.
        val onBeta = living.copy(installedVersion = "1.4.0", installedTag = "1.4.0-beta.2", installedBeta = true)
        val checked = SavedTvs.upsert(listOf(onBeta), living.copy(installedVersion = "1.4.0")).single()
        assertEquals("1.4.0-beta.2", checked.installedTag)
        assertTrue(checked.onBeta)
        assertEquals("1.4.0-beta.2", checked.installedLabel)
        assertEquals(listOf(onBeta), SavedTvs.decode(SavedTvs.encode(listOf(onBeta))))
    }

    @Test
    fun aNewTvIsAddedAtTheEnd() {
        assertEquals(listOf(living, bedroom), SavedTvs.upsert(listOf(living), bedroom))
    }

    @Test
    fun aDifferentTvAtTheSameAddressTakesItOver() {
        val newcomer = SavedTv("192.168.1.9", "Kitchen", "UA32", id = "uuid-3")
        assertEquals(listOf(bedroom, newcomer), SavedTvs.upsert(listOf(living, bedroom), newcomer))
    }

    @Test
    fun matchesByIdThenDuidThenNameThenAddress() {
        assertTrue(SavedTvs.sameTv(living, living.copy(ip = "1.2.3.4", name = "Renamed")))
        assertFalse(SavedTvs.sameTv(living, living.copy(id = "uuid-9")))
        assertTrue(SavedTvs.sameTv(living.copy(id = null), living.copy(id = null, name = "Renamed")))
        assertTrue(SavedTvs.sameTv(SavedTv("10.0.0.5"), SavedTv("10.0.0.5", "Living Room")))
        assertFalse(SavedTvs.sameTv(SavedTv("10.0.0.5"), SavedTv("10.0.0.6")))
    }

    @Test
    fun keyIsStableAcrossAddressChanges() {
        assertEquals(living.key, living.copy(ip = "192.168.1.99").key)
        assertEquals("ip:10.0.0.5", SavedTv("10.0.0.5").key)
    }

    @Test
    fun removeDropsOnlyThatTv() {
        assertEquals(listOf(bedroom), SavedTvs.remove(listOf(living, bedroom), living.copy(ip = "1.1.1.1")))
    }

    @Test
    fun theChosenAppSurvivesARecheckAndLegacyComesFromIt() {
        val chosen = living.copy(app = NuvioApp.LEGACY, hasLegacy = true)
        // A check that knows nothing about the choice keeps it; what is installed is always the check's.
        val checked = SavedTvs.upsert(listOf(chosen), SavedTv(living.ip, id = "uuid-1", hasLegacy = false)).single()
        assertEquals(NuvioApp.LEGACY, checked.chosenApp)
        assertFalse(checked.hasLegacy)
        assertEquals(NuvioApp.TV, SavedTv("10.0.0.5").chosenApp)
        assertEquals(listOf(chosen), SavedTvs.decode(SavedTvs.encode(listOf(chosen))))
    }
}
