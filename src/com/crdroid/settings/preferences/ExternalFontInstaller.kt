/*
 * Copyright (C) 2025 AxionOS
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
import android.graphics.fonts.FontFamilyUpdateRequest
import android.graphics.fonts.FontFileUpdateRequest
import android.graphics.fonts.FontFileUtil
import android.graphics.fonts.FontSanitizer
import android.graphics.fonts.FontManager
import android.graphics.fonts.FontStyle
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.ServiceManager
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.android.internal.statusbar.IStatusBarService
import com.android.internal.util.crdroid.ThemeUtils
import com.android.settings.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ExternalFontInstaller(private val context: Context) {

    companion object {
        private const val TAG = "ExternalFontInstaller"
        private const val CUSTOM_FONT_FILE = "cust_font.ttf"
        private const val TEMP_PREVIEW_FONT = "preview_font.ttf"
        private const val MAX_FONT_BYTES = 32L * 1024L * 1024L
        private const val OVERLAY_CATEGORY_FONT = "android.theme.customization.font"
        private const val DEFAULT_FONT_PACKAGE = "android"
        const val DEFAULT_FONT_FAMILY = "google-sans-flex"
        private const val DEFAULT_FONT_OVERLAY = "com.android.theme.font.googlesansflex"

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

    // The same installer instance owns the sanitized preview snapshot through confirmation.
    private var previewSnapshot: File? = null
    private var previewUri: Uri? = null

    private val fontManager: FontManager = context.getSystemService(FontManager::class.java)

    suspend fun loadTypefaceFromUri(uri: Uri): Typeface? = withContext(Dispatchers.IO) {
        previewSnapshot?.delete()
        previewSnapshot = null
        previewUri = null
        val tempFile = copyAndSanitize(uri) ?: return@withContext null
        val postScriptName = extractPostScriptName(tempFile) ?: run {
            tempFile.delete()
            return@withContext null
        }
        return@withContext runCatching {
            Typeface.createFromFile(tempFile).also {
                previewSnapshot = tempFile
                previewUri = uri
            }
        }.onFailure {
            Log.e(TAG, "Failed to create preview typeface for $postScriptName", it)
            tempFile.delete()
        }.getOrNull()
    }

    // Never parse legacy raw cache entries when opening the picker. The system's active
    // Typeface is already reconstructed from authenticated, sanitized output after reboot.
    fun loadInstalledTypeface(): Typeface? = null

    suspend fun installFontFromUri(uri: Uri): String? {
        // Stage under a separate name so a bad pick never clobbers the installed font
        val stagedFile = withContext(Dispatchers.IO) {
            if (previewUri == uri) {
                previewSnapshot?.takeIf { it.isFile }?.also {
                    previewSnapshot = null
                    previewUri = null
                }
            } else {
                copyAndSanitize(uri)
            }
        } ?: run {
            showToast(R.string.toast_failed_apply_font)
            return null
        }

        val postScriptName = withContext(Dispatchers.IO) { extractPostScriptName(stagedFile) } ?: run {
            showToast(R.string.toast_invalid_font_file)
            stagedFile.delete()
            return null
        }

        val applied = withContext(Dispatchers.IO) { applyFontToSystem(stagedFile, postScriptName) }
        if (!applied) {
            showToast(R.string.toast_failed_apply_font)
            stagedFile.delete()
            return null
        }

        withContext(Dispatchers.IO) {
            val installed = File(context.cacheDir, CUSTOM_FONT_FILE)
            try {
                Files.move(stagedFile.toPath(), installed.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                Log.w(TAG, "Could not keep a local copy of the installed font", e)
                stagedFile.delete()
            }
        }

        updateThemeOverlays()
        cleanupPreviewFont()
        return postScriptName
    }

    private suspend fun copyAndSanitize(uri: Uri): File? = withContext(Dispatchers.IO) {
        // Unique files avoid two picker jobs replacing each other's input or preview.
        val raw = File.createTempFile("font-input-", ".tmp", context.cacheDir)
        var sanitized: File? = null
        try {
            val outputFile = File.createTempFile("font-output-", ".ttf", context.cacheDir)
            sanitized = outputFile
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(raw).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_FONT_BYTES) { "Font file exceeds size limit" }
                        output.write(buffer, 0, read)
                    }
                    require(total > 0) { "Empty font" }
                    output.fd.sync()
                }
            } ?: throw IllegalArgumentException("Unable to read font")
            // No FontFileUtil, Typeface or other native font parser sees raw URI bytes here.
            FontSanitizer.sanitize(context, raw, outputFile)
            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Could not sanitize selected font", e)
            sanitized?.delete()
            null
        } finally {
            raw.delete()
        }
    }

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

    private fun applyFontToSystem(fontFile: File, postScriptName: String): Boolean {
        return runCatching {
            ParcelFileDescriptor.open(fontFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                val fontFileUpdateRequest = FontFileUpdateRequest(pfd, ByteArray(0))

                val fontRegular = FontFamilyUpdateRequest.Font.Builder(
                    postScriptName,
                    FontStyle()
                ).build()

                val familyRegular = FontFamilyUpdateRequest.FontFamily.Builder(
                    DEFAULT_FONT_FAMILY,
                    listOf(fontRegular)
                ).build()

                val updateRequest = FontFamilyUpdateRequest.Builder()
                    .addFontFileUpdateRequest(fontFileUpdateRequest)
                    .addFontFamily(familyRegular)
                    .build()

                val result = fontManager.updateFontFamily(
                    updateRequest,
                    fontManager.fontConfig.configVersion
                )
                if (result != FontManager.RESULT_SUCCESS) {
                    Log.e(TAG, "updateFontFamily failed with result $result")
                }
                result == FontManager.RESULT_SUCCESS
            }
        }.getOrElse {
            Log.e(TAG, "Failed to update system font", it)
            false
        }
    }

    private fun updateThemeOverlays() {
        val resolver = context.contentResolver
        val userId = UserHandle.myUserId()

        val json = runCatching {
            val current = Settings.Secure.getStringForUser(
                resolver,
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                userId
            )
            if (current.isNullOrEmpty()) JSONObject() else JSONObject(current)
        }.getOrElse { JSONObject() }

        runCatching {
            if (json.has(OVERLAY_CATEGORY_FONT)) json.remove(OVERLAY_CATEGORY_FONT)
            json.put(OVERLAY_CATEGORY_FONT, DEFAULT_FONT_OVERLAY)

            Settings.Secure.putStringForUser(
                resolver,
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                json.toString(),
                userId
            )
        }.onFailure { Log.e(TAG, "Failed to persist custom font overlay", it) }
    }

    private fun showToast(resId: Int) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }

    fun resetFontUpdates() {
        fontManager.clearUpdates()
        ThemeUtils.getInstance(context).setOverlayEnabled(
            OVERLAY_CATEGORY_FONT, DEFAULT_FONT_PACKAGE, DEFAULT_FONT_PACKAGE
        )
        cleanupPreviewFont()
    }

    fun discardPreview() {
        previewSnapshot?.delete()
        previewSnapshot = null
        previewUri = null
    }

    private fun cleanupPreviewFont() {
        discardPreview()
        try {
            val previewFile = File(context.cacheDir, TEMP_PREVIEW_FONT)
            if (previewFile.exists()) previewFile.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup preview font", e)
        }
    }
}
