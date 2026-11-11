// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.app

import android.content.SharedPreferences
import tv.nuvio.installer.core.SavedTv
import tv.nuvio.installer.core.SavedTvs

private const val PREF_SAVED_TVS = "savedTvs"

/**
 * The TVs this phone has set up, in the app's preferences. Shared by the screen and the
 * background update check, so every change re-reads the stored list first.
 */
object TvList {
    /** Reads the list. Before multi-TV support only one TV was stored; that one becomes the first entry. */
    fun load(prefs: SharedPreferences): List<SavedTv> {
        prefs.getString(PREF_SAVED_TVS, null)?.let { return SavedTvs.decode(it) }
        val ip = prefs.getString("tvIp", null)?.trim().orEmpty()
        if (ip.isEmpty()) return emptyList()
        val legacy = SavedTv(
            ip = ip,
            name = prefs.getString(PREF_TV_NAME, null).orEmpty(),
            model = prefs.getString(PREF_TV_MODEL, null).orEmpty(),
            id = prefs.getString(PREF_TV_ID, null),
            duid = prefs.getString(PREF_TV_DUID, null),
            installedVersion = prefs.getString(PREF_INSTALLED_VERSION, null),
        )
        return listOf(legacy).also { save(prefs, it) }
    }

    fun save(prefs: SharedPreferences, tvs: List<SavedTv>) {
        prefs.edit().putString(PREF_SAVED_TVS, SavedTvs.encode(tvs)).apply()
    }

    @Synchronized
    fun update(prefs: SharedPreferences, change: (List<SavedTv>) -> List<SavedTv>): List<SavedTv> =
        change(load(prefs)).also { save(prefs, it) }
}
