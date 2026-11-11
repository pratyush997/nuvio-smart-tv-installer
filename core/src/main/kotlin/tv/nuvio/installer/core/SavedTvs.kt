// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Who made the TV. Only Samsung can be installed to today; LG is planned. */
@Serializable
enum class TvBrand(val label: String) { SAMSUNG("Samsung"), LG("LG") }

/**
 * A TV the app has connected to. [ip] is its last known address; [id], [name] and [model] come
 * from the TV's info page and find it again after the router gives it a new address. [duid] and
 * [installedVersion] come from the installer connection; [installedVersion] is null when Nuvio
 * was not on the TV at the last check. [brand] is Samsung for every TV saved before LG support.
 */
@Serializable
data class SavedTv(
    val ip: String,
    val name: String = "",
    val model: String = "",
    val id: String? = null,
    val duid: String? = null,
    val installedVersion: String? = null,
    val brand: TvBrand = TvBrand.SAMSUNG,
) {
    val remembered: RememberedTv get() = RememberedTv(id, name, model)

    /** What the user sees: the TV's name, or its address when it has none. */
    val label: String get() = name.ifBlank { ip }

    /** Stable per TV across address changes; used for per-TV settings and notifications. */
    val key: String get() = id?.takeIf { it.isNotBlank() } ?: duid?.takeIf { it.isNotBlank() } ?: "ip:$ip"
}

/** The list of TVs, stored as JSON. */
object SavedTvs {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(SavedTv.serializer())

    fun decode(text: String?): List<SavedTv> =
        if (text.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyList())

    fun encode(tvs: List<SavedTv>): String = json.encodeToString(serializer, tvs)

    /**
     * True when [a] and [b] are the same TV: same id, else same DUID, else same name and model
     * (when neither has an id), else same address.
     */
    fun sameTv(a: SavedTv, b: SavedTv): Boolean {
        val idA = a.id?.takeIf { it.isNotBlank() }
        val idB = b.id?.takeIf { it.isNotBlank() }
        if (idA != null && idB != null) return idA == idB
        val duidA = a.duid?.takeIf { it.isNotBlank() }
        val duidB = b.duid?.takeIf { it.isNotBlank() }
        if (duidA != null && duidB != null) return duidA == duidB
        if (a.name.isNotBlank() && b.name.isNotBlank()) return a.name == b.name && a.model == b.model
        return a.ip == b.ip
    }

    /**
     * Adds [tv], or replaces the entry for the same TV (keeping its place in the list). Fields
     * [tv] does not know (blank or null id, name, model, DUID) are kept from
     * the old entry; [installedVersion] always comes from [tv]. Another entry left at the same
     * address is dropped: that address now belongs to [tv].
     */
    fun upsert(tvs: List<SavedTv>, tv: SavedTv): List<SavedTv> {
        val index = tvs.indexOfFirst { sameTv(it, tv) }
        val merged = if (index < 0) tv else {
            val old = tvs[index]
            tv.copy(
                name = tv.name.ifBlank { old.name },
                model = tv.model.ifBlank { old.model },
                id = tv.id?.takeIf { it.isNotBlank() } ?: old.id,
                duid = tv.duid?.takeIf { it.isNotBlank() } ?: old.duid,
            )
        }
        val result = ArrayList<SavedTv>(tvs.size + 1)
        tvs.forEachIndexed { i, old ->
            when {
                i == index -> result += merged
                old.ip == merged.ip -> Unit
                else -> result += old
            }
        }
        if (index < 0) result += merged
        return result
    }

    fun remove(tvs: List<SavedTv>, tv: SavedTv): List<SavedTv> = tvs.filterNot { sameTv(it, tv) }

    fun atIp(tvs: List<SavedTv>, ip: String): SavedTv? = tvs.firstOrNull { it.ip == ip }

    fun withKey(tvs: List<SavedTv>, key: String): SavedTv? = tvs.firstOrNull { it.key == key }
}
