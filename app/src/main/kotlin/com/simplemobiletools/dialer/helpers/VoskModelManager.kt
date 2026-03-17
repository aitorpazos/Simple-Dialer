package com.simplemobiletools.dialer.helpers

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Manages download and storage of Vosk speech recognition models.
 * Models are downloaded on-demand based on the configured TTS language.
 *
 * Small models are ~40-50MB compressed, suitable for on-device call transcription.
 */
class VoskModelManager(private val context: Context) {

    companion object {
        private const val TAG = "VoskModelManager"
        private const val MODELS_DIR = "vosk-models"
        private const val BASE_URL = "https://alphacephei.com/vosk/models"

        /**
         * Map of language tags to Vosk small model names.
         * These are the lightweight models (~40-50MB) suitable for mobile use.
         */
        private val LANGUAGE_MODEL_MAP = mapOf(
            "en" to "vosk-model-small-en-us-0.15",
            "en-US" to "vosk-model-small-en-us-0.15",
            "en-GB" to "vosk-model-small-en-us-0.15",
            "es" to "vosk-model-small-es-0.42",
            "es-ES" to "vosk-model-small-es-0.42",
            "es-MX" to "vosk-model-small-es-0.42",
            "de" to "vosk-model-small-de-0.15",
            "de-DE" to "vosk-model-small-de-0.15",
            "fr" to "vosk-model-small-fr-0.22",
            "fr-FR" to "vosk-model-small-fr-0.22",
            "it" to "vosk-model-small-it-0.22",
            "it-IT" to "vosk-model-small-it-0.22",
            "pt" to "vosk-model-small-pt-0.3",
            "pt-BR" to "vosk-model-small-pt-0.3",
            "pt-PT" to "vosk-model-small-pt-0.3",
            "ru" to "vosk-model-small-ru-0.22",
            "ru-RU" to "vosk-model-small-ru-0.22",
            "zh" to "vosk-model-small-cn-0.22",
            "zh-CN" to "vosk-model-small-cn-0.22",
            "ja" to "vosk-model-small-ja-0.22",
            "ja-JP" to "vosk-model-small-ja-0.22",
            "ko" to "vosk-model-small-ko-0.22",
            "ko-KR" to "vosk-model-small-ko-0.22",
            "tr" to "vosk-model-small-tr-0.3",
            "tr-TR" to "vosk-model-small-tr-0.3",
            "nl" to "vosk-model-small-nl-0.22",
            "nl-NL" to "vosk-model-small-nl-0.22",
            "uk" to "vosk-model-small-uk-v3-small",
            "uk-UA" to "vosk-model-small-uk-v3-small",
            "ca" to "vosk-model-small-ca-0.4",
            "ca-ES" to "vosk-model-small-ca-0.4",
            "hi" to "vosk-model-small-hi-0.22",
            "hi-IN" to "vosk-model-small-hi-0.22",
            "fa" to "vosk-model-small-fa-0.5",
            "fa-IR" to "vosk-model-small-fa-0.5",
            "pl" to "vosk-model-small-pl-0.22",
            "pl-PL" to "vosk-model-small-pl-0.22",
            "vi" to "vosk-model-small-vn-0.4",
            "vi-VN" to "vosk-model-small-vn-0.4",
            "cs" to "vosk-model-small-cs-0.4-rhasspy",
            "cs-CZ" to "vosk-model-small-cs-0.4-rhasspy",
            "kk" to "vosk-model-small-kz-0.15",
            "kk-KZ" to "vosk-model-small-kz-0.15",
            "eo" to "vosk-model-small-eo-0.42",
            "sv" to "vosk-model-small-sv-rhasspy-0.15",
            "sv-SE" to "vosk-model-small-sv-rhasspy-0.15"
        )

        /**
         * Get all supported language codes.
         */
        fun getSupportedLanguages(): Set<String> {
            return LANGUAGE_MODEL_MAP.keys
        }
    }

    private fun getModelsDir(): File {
        val dir = File(context.getExternalFilesDir(null), MODELS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Resolve the model name for a given language tag.
     * Tries exact match first, then base language.
     * Falls back to English if no match found.
     */
    fun resolveModelName(languageTag: String): String {
        // Try exact match
        LANGUAGE_MODEL_MAP[languageTag]?.let { return it }

        // Try base language (e.g., "en-US" → "en")
        val baseLang = languageTag.split("-", "_").first()
        LANGUAGE_MODEL_MAP[baseLang]?.let { return it }

        // Fallback to English
        Log.w(TAG, "No Vosk model for language '$languageTag', falling back to English")
        return LANGUAGE_MODEL_MAP["en"]!!
    }

    /**
     * Get the download URL for a model.
     */
    fun getModelUrl(modelName: String): String {
        return "$BASE_URL/$modelName.zip"
    }

    /**
     * Get the local directory where a model is stored.
     */
    fun getModelDir(modelName: String): File {
        return File(getModelsDir(), modelName)
    }

    /**
     * Check if a model is already downloaded and ready.
     */
    fun isModelReady(modelName: String): Boolean {
        val modelDir = getModelDir(modelName)
        // Vosk models contain at minimum: am/final.mdl or equivalent
        return modelDir.exists() && modelDir.isDirectory && modelDir.listFiles()?.isNotEmpty() == true
    }

    /**
     * Check if a model is ready for the given language.
     */
    fun isModelReadyForLanguage(languageTag: String): Boolean {
        val modelName = resolveModelName(languageTag)
        return isModelReady(modelName)
    }

    /**
     * Download and extract a Vosk model.
     * This is a blocking operation — call from a background thread.
     *
     * @param modelName The model name (e.g., "vosk-model-small-en-us-0.15")
     * @param progressCallback Optional callback with progress (0.0 to 1.0)
     * @return true if download and extraction succeeded
     */
    fun downloadModel(modelName: String, progressCallback: ((Float) -> Unit)? = null): Boolean {
        val url = getModelUrl(modelName)
        val modelDir = getModelDir(modelName)

        // Clean up any partial download
        if (modelDir.exists()) {
            modelDir.deleteRecursively()
        }

        Log.d(TAG, "Downloading model: $url")

        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Download failed: HTTP ${connection.responseCode}")
                return false
            }

            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L

            val tempZip = File(getModelsDir(), "$modelName.zip.tmp")

            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(tempZip).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            progressCallback?.invoke(downloadedBytes.toFloat() / totalBytes)
                        }
                    }
                }
            }

            Log.d(TAG, "Download complete, extracting...")

            // Extract zip
            val extractDir = getModelsDir()
            ZipInputStream(tempZip.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val outFile = File(extractDir, entry.name)

                    // Security: prevent zip slip
                    if (!outFile.canonicalPath.startsWith(extractDir.canonicalPath)) {
                        Log.e(TAG, "Zip slip detected, skipping: ${entry.name}")
                        entry = zip.nextEntry
                        continue
                    }

                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            val buf = ByteArray(8192)
                            var len: Int
                            while (zip.read(buf).also { len = it } != -1) {
                                fos.write(buf, 0, len)
                            }
                        }
                    }
                    entry = zip.nextEntry
                }
            }

            // Clean up temp zip
            tempZip.delete()

            val success = isModelReady(modelName)
            Log.d(TAG, "Model extraction ${if (success) "succeeded" else "failed"}: $modelName")
            return success

        } catch (e: Exception) {
            Log.e(TAG, "Failed to download model: $modelName", e)
            // Clean up on failure
            modelDir.deleteRecursively()
            File(getModelsDir(), "$modelName.zip.tmp").delete()
            return false
        }
    }

    /**
     * Delete a downloaded model to free space.
     */
    fun deleteModel(modelName: String): Boolean {
        val modelDir = getModelDir(modelName)
        return if (modelDir.exists()) {
            modelDir.deleteRecursively()
        } else {
            true
        }
    }

    /**
     * Get total size of downloaded models in bytes.
     */
    fun getTotalModelsSize(): Long {
        return getModelsDir().walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * Get list of downloaded model names.
     */
    fun getDownloadedModels(): List<String> {
        return getModelsDir().listFiles()
            ?.filter { it.isDirectory && it.listFiles()?.isNotEmpty() == true }
            ?.map { it.name }
            ?: emptyList()
    }
}
