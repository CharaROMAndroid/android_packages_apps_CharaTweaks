/*
 * Copyright (C) 2025 AxionOS
 * SPDX-FileCopyrightText: crDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.crdroid.settings.preferences

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.FontSanitizer
import android.graphics.fonts.FontFileUtil
import android.graphics.fonts.FontManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.ServiceManager
import android.provider.OpenableColumns
import android.util.Log
import com.android.internal.statusbar.IStatusBarService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel

/**
 * Installs a user-supplied .ttf / .otf as the system UI font through the custom-font API of
 * [FontManager] ("base: Support custom fonts for system wide").
 *
 * The framework stores the file under /data/fonts with fs-verity, registers it as the
 * "sans-serif" custom family and puts it in front of every UI family (sans-serif*,
 * google-sans*, variable-*, roboto*, source-sans-pro*). So, unlike the previous
 * implementation, no family is replaced and no font overlay has to be forced on. The only
 * overlay rule is that a font overlay whose family isn't a UI family would hide the custom
 * font, so the caller switches the overlay back to Default.
 *
 * Nothing here shows UI. Every blocking call runs on [Dispatchers.IO] and outcomes come back
 * as values, so the caller decides how to report them.
 */
class ExternalFontInstaller(context: Context) {

    companion object {
        private const val TAG = "ExternalFontInstaller"
        private const val CUSTOM_FONT_FILE = "cust_font.ttf"
        private const val TEMP_PREVIEW_FONT = "preview_font.ttf"

        /** Match the isolated sanitizer and framework custom-font policy. */
        const val MAX_FONT_BYTES = 32L * 1024L * 1024L

        /** Family the framework registers the custom font under. */
        const val CUSTOM_FONT_FAMILY = "sans-serif"

        /**
         * Our own code for "the call didn't get through" (exception, missing service). Kept
         * clear of FontManager's RESULT_* values, which use -1..-9 and -10001..-10008.
         */
        const val RESULT_EXCEPTION = -20000

        fun rebootDevice() {
            runCatching {
                val binder = ServiceManager.getService("statusbar")
                val statusBarService = IStatusBarService.Stub.asInterface(binder)
                statusBarService.reboot(false, "system_font_change")
            }.onFailure {
                Log.e(TAG, "Failed to reboot device via statusbar service", it)
            }
        }
    }

    /** A picked font file, copied into the cache and checked, ready to preview or install. */
    class FontFilePreview internal constructor(
        internal val file: File,
        val postScriptName: String,
        val displayName: String?,
        val sizeBytes: Long,
        val typeface: Typeface,
    )

    sealed interface InstallResult {
        data class Success(val postScriptName: String) : InstallResult
        /** The file is gone, too large, or no longer parses as a font. */
        data object Invalid : InstallResult
        /** FontManager rejected the request; [code] is a FontManager.RESULT_* value. */
        data class Failed(val code: Int) : InstallResult
    }

    private val context: Context = context.applicationContext
    private val fontManager: FontManager? = this.context.getSystemService(FontManager::class.java)
    private val operationLock = Mutex()
    private val previewLock = Any()
    private var pendingPreview: FontFilePreview? = null

    // ---- State -----------------------------------------------------------------------------

    /**
     * PostScript name of the active custom font, straight from FontManagerService. It changes
     * as soon as a font is installed or cleared, before the reboot, so it matches what the
     * next boot will use. Null when none is active or the service can't be asked.
     */
    fun customFontName(): String? = runCatching { fontManager?.customFontName }
        .onFailure { Log.e(TAG, "getCustomFontName failed", it) }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() }

    // ---- Preview ---------------------------------------------------------------------------

    /**
     * Sanitizes a bounded private snapshot of [uri] before any native preview parsing.
     * Returns null when copying, isolation, sanitation or sanitized metadata validation fails.
     */
    suspend fun inspect(uri: Uri): FontFilePreview? = withContext(Dispatchers.IO) {
        operationLock.withLock {
            discardPreview()
            val file = copyAndSanitize(uri) ?: return@withLock null
            var retained = false
            try {
                // Neither native metadata parsing nor Typeface sees the original URI bytes.
                val postScriptName = extractPostScriptName(file) ?: return@withLock null
                val typeface = runCatching { Typeface.createFromFile(file) }
                    .onFailure { Log.e(TAG, "Failed to create sanitized preview", it) }
                    .getOrNull() ?: return@withLock null
                val preview = FontFilePreview(file, postScriptName, queryDisplayName(uri),
                    file.length(), typeface)
                synchronized(previewLock) { pendingPreview = preview }
                retained = true
                preview
            } finally {
                if (!retained) file.delete()
            }
        }
    }

    /** Deletes only this installer's owned sanitized snapshot. */
    fun discardPreview() {
        synchronized(previewLock) {
            pendingPreview?.file?.delete()
            pendingPreview = null
        }
    }

    /**
     * Use the active system font map, never parse a legacy raw cache or /data/fonts path.
     * Before the requested reboot this deliberately shows the currently running font.
     */
    fun loadInstalledTypeface(): Typeface? =
        runCatching { Typeface.create(CUSTOM_FONT_FAMILY, Typeface.NORMAL) }.getOrNull()

    // ---- Install / clear -------------------------------------------------------------------

    /**
     * Hands [preview] to FontManagerService. On success the font is part of the system font
     * map for processes started from now on; running processes keep their font until they
     * restart, hence the reboot prompt.
     */
    suspend fun install(preview: FontFilePreview): InstallResult = withContext(Dispatchers.IO) {
        operationLock.withLock {
            val source = synchronized(previewLock) {
                if (pendingPreview !== preview) null
                else preview.file.also { pendingPreview = null }
            } ?: return@withLock InstallResult.Invalid
            try {
                if (!source.isFile || source.length() <= 0 || source.length() > MAX_FONT_BYTES) {
                    return@withLock InstallResult.Invalid
                }
                // This is the exact sanitized snapshot shown at confirmation, not a URI re-read.
                val postScriptName = extractPostScriptName(source)
                    ?: return@withLock InstallResult.Invalid
                val manager = fontManager
                    ?: return@withLock InstallResult.Failed(RESULT_EXCEPTION)
                val code = runCatching {
                    ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use {
                        manager.installCustomFont(it)
                    }
                }.onFailure { Log.e(TAG, "installCustomFont failed", it) }
                    .getOrDefault(RESULT_EXCEPTION)
                if (code != FontManager.RESULT_SUCCESS) return@withLock InstallResult.Failed(code)
                deleteLegacyCopies()
                InstallResult.Success(postScriptName)
            } finally {
                source.delete()
            }
        }
    }

    /**
     * Removes the custom font from the system font configuration. Blocking: call off the main
     * thread. Returns a FontManager.RESULT_* value or [RESULT_EXCEPTION].
     */
    fun clearCustomFont(): Int {
        val code = runCatching { fontManager?.clearCustomFont() ?: RESULT_EXCEPTION }
            .onFailure { Log.e(TAG, "clearCustomFont failed", it) }
            .getOrDefault(RESULT_EXCEPTION)
        if (code == FontManager.RESULT_SUCCESS) {
            deleteLegacyCopies()
            discardPreview()
        }
        return code
    }

    // ---- Internals -------------------------------------------------------------------------

    /**
     * Copy a bounded URI snapshot, then rewrite it through the isolated sanitizer.
     * The framework client transports bytes via read-only shared memory, not a cache-file FD.
     */
    private fun copyAndSanitize(uri: Uri): File? {
        var raw: File? = null
        var output: File? = null
        try {
            raw = File.createTempFile("font-input-", ".tmp", context.cacheDir)
            output = File.createTempFile("font-output-", ".ttf", context.cacheDir)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(raw).use { sink ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_FONT_BYTES) { "Font exceeds 32 MiB" }
                        sink.write(buffer, 0, read)
                    }
                    require(total > 0) { "Empty font" }
                    sink.fd.sync()
                }
            } ?: error("Unable to read font")
            FontSanitizer.sanitize(context, raw, output)
            return output
        } catch (e: Exception) {
            Log.e(TAG, "Could not sanitize selected font", e)
            output?.delete()
            return null
        } finally {
            raw?.delete()
        }
    }

    /** No legacy private copy is ever used as parser input. */
    private fun deleteLegacyCopies() {
        for (dir in listOf(context.filesDir, context.cacheDir)) {
            File(dir, CUSTOM_FONT_FILE).delete()
            File(dir, "$CUSTOM_FONT_FILE.tmp").delete()
        }
        File(context.cacheDir, TEMP_PREVIEW_FONT).delete()
        File(context.cacheDir, "$TEMP_PREVIEW_FONT.tmp").delete()
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun extractPostScriptName(fontFile: File): String? = runCatching {
        if (!fontFile.isFile || fontFile.length() <= 0L || fontFile.length() > MAX_FONT_BYTES) {
            return@runCatching null
        }
        FileInputStream(fontFile).use { fis ->
            val size = fis.channel.size()
            if (size <= 0L || size > MAX_FONT_BYTES) return@use null
            val buffer = fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
            FontFileUtil.getPostScriptName(buffer, 0)
        }
    }.onFailure {
        Log.e(TAG, "Failed to inspect font file", it)
    }.getOrNull()

}
