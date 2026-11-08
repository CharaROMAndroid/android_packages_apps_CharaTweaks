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
import android.graphics.fonts.FontFileUtil
import android.graphics.fonts.FontManager
import android.graphics.fonts.FontSanitizer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.ServiceManager
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.android.internal.statusbar.IStatusBarService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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

        /** Shared with the isolated sanitizer and hardened framework installation path. */
        const val MAX_FONT_BYTES = FontSanitizer.MAX_FONT_BYTES

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

    /**
     * Only the isolated sanitizer's rewritten bytes enter this snapshot. Its sole descriptor
     * is read-only and the backing pathname is unlinked before any native font parsing.
     * The original provider URI is never reopened for installation.
     */
    class FontFilePreview internal constructor(
        internal val descriptor: ParcelFileDescriptor,
        val postScriptName: String,
        val displayName: String?,
        val sizeBytes: Long,
        val typeface: Typeface,
    )

    private val previewLock = Any()
    private var previewGeneration = 0L
    private var activePreview: FontFilePreview? = null

    sealed interface InstallResult {
        data class Success(val postScriptName: String) : InstallResult
        /** The file is gone, too large, or no longer parses as a font. */
        data object Invalid : InstallResult
        /** FontManager rejected the request; [code] is a FontManager.RESULT_* value. */
        data class Failed(val code: Int) : InstallResult
    }

    private val context: Context = context.applicationContext
    private val fontManager: FontManager? = this.context.getSystemService(FontManager::class.java)

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
     * Copy with a streaming size cap, sanitize in the isolated worker, then freeze the output.
     * No FontFileUtil or Typeface parser ever sees raw provider bytes in this process.
     */
    suspend fun inspect(uri: Uri): FontFilePreview? = withContext(Dispatchers.IO) {
        val generation = synchronized(previewLock) {
            activePreview?.descriptor?.close()
            activePreview = null
            ++previewGeneration
        }
        val descriptor = copyAndSanitize(uri) ?: return@withContext null
        var retained = false
        try {
            val size = descriptor.statSize
            if (size <= 0L || size > MAX_FONT_BYTES) return@withContext null
            val postScriptName = extractPostScriptName(descriptor) ?: return@withContext null
            val typeface = Typeface.Builder(descriptor.fileDescriptor).build()
            val preview = FontFilePreview(
                descriptor, postScriptName, queryDisplayName(uri), size, typeface,
            )
            synchronized(previewLock) {
                if (generation == previewGeneration) {
                    activePreview = preview
                    retained = true
                }
            }
            if (retained) preview else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inspect sanitized font", e)
            null
        } finally {
            if (!retained) descriptor.close()
        }
    }

    /** Invalidates in-flight inspection and closes only this installer's pending snapshot. */
    fun discardPreview() {
        synchronized(previewLock) {
            ++previewGeneration
            activePreview?.descriptor?.close()
            activePreview = null
        }
    }

    // Never parse legacy raw cache entries or guessed /data/fonts paths. The framework
    // authenticates sanitized-output receipts before reconstructing the active font map.
    fun loadInstalledTypeface(): Typeface? = null

    // ---- Install / clear -------------------------------------------------------------------

    /**
     * Hands [preview] to FontManagerService. On success the font is part of the system font
     * map for processes started from now on; running processes keep their font until they
     * restart, hence the reboot prompt.
     */
    suspend fun install(preview: FontFilePreview): InstallResult = withContext(Dispatchers.IO) {
        // Claim once under the same lock as discard. Cancellation/replacement cannot close
        // a descriptor while the service is reading it, and stale/foreign previews fail shut.
        val source = synchronized(previewLock) {
            if (activePreview !== preview) null else {
                activePreview = null
                ++previewGeneration
                preview.descriptor
            }
        } ?: return@withContext InstallResult.Invalid
        source.use { pfd ->
            if (pfd.statSize <= 0L || pfd.statSize > MAX_FONT_BYTES) {
                return@withContext InstallResult.Invalid
            }
            val manager = fontManager
                ?: return@withContext InstallResult.Failed(RESULT_EXCEPTION)
            val code = runCatching {
                Os.lseek(pfd.fileDescriptor, 0L, OsConstants.SEEK_SET)
                // The framework independently sanitizes, seals and authenticates its copy
                // BEFORE native metadata parsing. Client snapshots are not upload receipts.
                manager.installCustomFont(pfd)
            }.onFailure { Log.e(TAG, "installCustomFont failed", it) }
                .getOrDefault(RESULT_EXCEPTION)
            if (code != FontManager.RESULT_SUCCESS) {
                return@withContext InstallResult.Failed(code)
            }
            File(context.filesDir, CUSTOM_FONT_FILE).delete()
            File(context.cacheDir, CUSTOM_FONT_FILE).delete()
            InstallResult.Success(preview.postScriptName)
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
            File(context.filesDir, CUSTOM_FONT_FILE).delete()
            File(context.cacheDir, CUSTOM_FONT_FILE).delete()
            discardPreview()
        }
        return code
    }

    // ---- Internals -------------------------------------------------------------------------

    private fun copyAndSanitize(uri: Uri): ParcelFileDescriptor? {
        var raw: File? = null
        var output: File? = null
        var descriptor: ParcelFileDescriptor? = null
        try {
            val inputFile = File.createTempFile("font-input-", ".tmp", context.cacheDir)
            raw = inputFile
            val outputFile = File.createTempFile("font-output-", ".ttf", context.cacheDir)
            output = outputFile
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Unable to read font")
            input.use {
                FileOutputStream(inputFile).use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = it.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_FONT_BYTES) { "Font file exceeds size limit" }
                        out.write(buffer, 0, read)
                    }
                    require(total > 0L) { "Empty font" }
                    out.fd.sync()
                }
            }
            FontSanitizer.sanitize(context, inputFile, outputFile)
            require(outputFile.length() in 1L..MAX_FONT_BYTES) { "Invalid sanitized output size" }
            descriptor = ParcelFileDescriptor.open(outputFile, ParcelFileDescriptor.MODE_READ_ONLY)
            // No pathname or writable descriptor survives into preview or install.
            check(outputFile.delete()) { "Cannot unlink sanitized snapshot" }
            val snapshot = descriptor
            descriptor = null
            return snapshot
        } catch (e: Exception) {
            Log.e(TAG, "Could not sanitize selected font", e)
            return null
        } finally {
            descriptor?.close()
            raw?.delete()
            output?.delete()
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun extractPostScriptName(descriptor: ParcelFileDescriptor): String? = runCatching {
        ParcelFileDescriptor.AutoCloseInputStream(
            ParcelFileDescriptor.dup(descriptor.fileDescriptor),
        ).use { input ->
            val size = input.channel.size()
            if (size <= 0L || size > MAX_FONT_BYTES) return@use null
            val buffer = input.channel.map(FileChannel.MapMode.READ_ONLY, 0L, size)
            FontFileUtil.getPostScriptName(buffer, 0)
        }
    }.onFailure { Log.e(TAG, "Failed to inspect sanitized font", it) }.getOrNull()
}
