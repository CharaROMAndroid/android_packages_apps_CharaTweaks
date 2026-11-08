/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-FileCopyrightText: BaikalOS Project, Sergei Vasilev <svasiliev22@yandex.ru>
 * SPDX-License-Identifier: Apache-2.0
 *
 * UI design derived from BaikalOS (BaikalSettings) app privacy / app performance screens.
 */

package com.crdroid.settings.fragments.misc

import org.json.JSONObject

/**
 * Pure JSON helpers for the per-user app policy keys in Settings.Secure:
 *
 *   chararom_app_list_hiding           {"<pkg>": true}            (false / default omitted)
 *   chararom_app_performance_profiles  {"<pkg>": "<mode>"}        (default omitted)
 *
 * Unknown / malformed entries are dropped on parse; entries for other packages are preserved on
 * write because the whole map is round-tripped.
 */
internal object AppPolicyStore {
    const val HIDING_KEY = "chararom_app_list_hiding"
    const val PERFORMANCE_KEY = "chararom_app_performance_profiles"

    const val MODE_DEFAULT = "default"
    const val MODE_POWER_SAVE = "power_save"
    const val MODE_BALANCED = "balanced"
    const val MODE_PERFORMANCE = "performance"
    val ALL_MODES = listOf(MODE_DEFAULT, MODE_POWER_SAVE, MODE_BALANCED, MODE_PERFORMANCE)

    fun parseHiding(content: String?): Set<String> {
        if (content.isNullOrBlank()) return emptySet()
        return try {
            val json = JSONObject(content)
            json.keys().asSequence()
                .filter { json.optBoolean(it, false) && it.isNotBlank() }
                .toSet()
        } catch (e: Exception) { emptySet() }
    }

    fun serializeHiding(packages: Set<String>): String? {
        if (packages.isEmpty()) return null
        val json = JSONObject()
        packages.sorted().forEach { json.put(it, true) }
        return json.toString()
    }

    fun parsePerformance(content: String?): Map<String, String> {
        if (content.isNullOrBlank()) return emptyMap()
        return try {
            val json = JSONObject(content)
            val out = linkedMapOf<String, String>()
            json.keys().forEach { pkg ->
                val mode = json.optString(pkg, MODE_DEFAULT)
                if (pkg.isNotBlank() && mode in ALL_MODES && mode != MODE_DEFAULT) out[pkg] = mode
            }
            out
        } catch (e: Exception) { emptyMap() }
    }

    fun serializePerformance(profiles: Map<String, String>): String? {
        val kept = profiles.filter { it.value in ALL_MODES && it.value != MODE_DEFAULT }
        if (kept.isEmpty()) return null
        val json = JSONObject()
        kept.toSortedMap().forEach { (k, v) -> json.put(k, v) }
        return json.toString()
    }
}
