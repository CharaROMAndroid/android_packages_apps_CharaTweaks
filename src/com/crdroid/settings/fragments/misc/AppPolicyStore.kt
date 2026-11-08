/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-FileCopyrightText: BaikalOS Project, Sergei Vasilev <svasiliev22@yandex.ru>
 * SPDX-License-Identifier: Apache-2.0
 *
 * UI design derived from BaikalOS (BaikalSettings) app privacy screen.
 */

package com.crdroid.settings.fragments.misc

import org.json.JSONObject

/**
 * Pure JSON helpers for the per-user Settings.Secure key chararom_app_list_hiding,
 * {"<pkg>": true}. Only a strict JSON true counts as hidden.
 */
internal object AppPolicyStore {
    const val HIDING_KEY = "chararom_app_list_hiding"

    // Same limits the framework enforces; larger content is treated as invalid.
    const val MAX_CHARS = 256 * 1024
    const val MAX_ENTRIES = 4096

    /**
     * Returns the hidden packages, an empty set for unset/blank content, or null when the stored
     * content is invalid (too large, malformed, too many entries). Callers must not overwrite
     * invalid content without the user knowing.
     */
    fun parseHiding(content: String?): Set<String>? {
        if (content.isNullOrBlank()) return emptySet()
        if (content.length > MAX_CHARS) return null
        return try {
            val json = JSONObject(content)
            if (json.length() > MAX_ENTRIES) return null
            json.keys().asSequence()
                .filter { it.isNotBlank() && json.opt(it) == true }
                .toSet()
        } catch (e: Exception) { null }
    }

    /** True if [packages] can be stored within the framework limits. */
    fun fits(packages: Set<String>): Boolean =
        packages.size <= MAX_ENTRIES && (serializeHiding(packages)?.length ?: 0) <= MAX_CHARS

    fun serializeHiding(packages: Set<String>): String? {
        if (packages.isEmpty()) return null
        val json = JSONObject()
        packages.sorted().forEach { json.put(it, true) }
        return json.toString()
    }
}
