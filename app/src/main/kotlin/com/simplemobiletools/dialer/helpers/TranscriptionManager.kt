package com.simplemobiletools.dialer.helpers

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.simplemobiletools.dialer.extensions.config
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages call transcription storage and retrieval.
 * Transcription files are stored alongside recordings as .txt files with the same base name.
 */
class TranscriptionManager(private val context: Context) {

    companion object {
        private const val TAG = "TranscriptionManager"
        private const val TRANSCRIPTION_EXT = ".txt"
    }

    private fun getDefaultTranscriptionsDir(): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "CallRecordings")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Find the recording filename for a call by matching phone number and approximate timestamp.
     * Recording filenames follow: call_{sanitizedNumber}_{yyyyMMdd_HHmmss}.m4a
     *
     * @param phoneNumber the phone number from the call
     * @param startTimestampSec call start timestamp in seconds (from RecentCall.startTS)
     * @return the recording filename (e.g. "call_+123_20260317_080000.m4a") or null
     */
    fun findRecordingForCall(phoneNumber: String, startTimestampSec: Int): String? {
        val sanitizedNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
        val prefix = "call_${sanitizedNumber}_"

        Log.d(TAG, "findRecordingForCall: phoneNumber=$phoneNumber, sanitized=$sanitizedNumber, prefix=$prefix, startTS=$startTimestampSec")

        // Check default directory
        val dir = getDefaultTranscriptionsDir()
        Log.d(TAG, "Checking default dir: ${dir.absolutePath}, exists=${dir.exists()}, files=${dir.listFiles()?.size ?: 0}")
        val match = findMatchingFile(dir, prefix, startTimestampSec)
        if (match != null) {
            Log.d(TAG, "Found match in default dir: ${match.name}")
            return match.name
        }

        // Check custom SAF directory
        val customUriString = context.config.callRecordingPath
        Log.d(TAG, "Checking custom SAF path: '$customUriString'")
        if (customUriString.isNotEmpty()) {
            try {
                val treeUri = Uri.parse(customUriString)
                val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
                if (treeDoc != null) {
                    val allFiles = treeDoc.listFiles()
                    val prefixMatches = allFiles.filter { it.name?.startsWith(prefix) == true && it.name?.endsWith(".m4a") == true }
                    Log.d(TAG, "SAF dir has ${allFiles.size} files, ${prefixMatches.size} match prefix '$prefix'")
                    prefixMatches.forEach { Log.d(TAG, "  SAF candidate: ${it.name}") }
                    val matchDoc = prefixMatches
                        .mapNotNull { doc ->
                            val name = doc.name ?: return@mapNotNull null
                            val ts = extractTimestampFromFilename(name) ?: return@mapNotNull null
                            Pair(name, ts)
                        }
                        .filter { (_, ts) -> Math.abs(ts - startTimestampSec.toLong()) < 120 }
                        .minByOrNull { (_, ts) -> Math.abs(ts - startTimestampSec.toLong()) }
                    if (matchDoc != null) {
                        Log.d(TAG, "Found match in SAF dir: ${matchDoc.first}")
                        return matchDoc.first
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "SAF lookup failed", e)
            }
        }

        Log.d(TAG, "No recording found for $phoneNumber at $startTimestampSec")
        return null
    }

    private fun findMatchingFile(dir: File, prefix: String, startTimestampSec: Int): File? {
        if (!dir.exists()) return null
        return dir.listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".m4a") }
            ?.mapNotNull { file ->
                val ts = extractTimestampFromFilename(file.name) ?: return@mapNotNull null
                Pair(file, ts)
            }
            ?.filter { (_, ts) -> Math.abs(ts - startTimestampSec.toLong()) < 120 }
            ?.minByOrNull { (_, ts) -> Math.abs(ts - startTimestampSec.toLong()) }
            ?.first
    }

    /**
     * Extract unix timestamp (seconds) from a recording filename like call_+123_20260317_080000.m4a
     */
    private fun extractTimestampFromFilename(filename: String): Long? {
        val regex = Regex("""call_.+_(\d{8}_\d{6})\.m4a""")
        val match = regex.find(filename) ?: return null
        return try {
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            sdf.parse(match.groupValues[1])?.time?.div(1000)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Get a shareable URI for a recording by name.
     * Checks both default directory and custom SAF directory.
     */
    fun getRecordingUriByName(recordingName: String): Uri? {
        // Check default directory first
        val file = File(getDefaultTranscriptionsDir(), recordingName)
        if (file.exists()) {
            return try {
                FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file
                )
            } catch (_: Exception) {
                Uri.fromFile(file)
            }
        }

        // Check custom SAF directory
        val customUriString = context.config.callRecordingPath
        if (customUriString.isNotEmpty()) {
            try {
                val treeUri = Uri.parse(customUriString)
                val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
                val doc = treeDoc?.listFiles()?.firstOrNull { it.name == recordingName }
                if (doc != null) return doc.uri
            } catch (_: Exception) {}
        }

        return null
    }

    /**
     * Get the transcription file path for a given recording name.
     * E.g., "call_+123_20260317_080000.m4a" → "call_+123_20260317_080000.txt"
     */
    fun getTranscriptionFile(recordingName: String): File {
        val baseName = recordingName.substringBeforeLast(".")
        return File(getDefaultTranscriptionsDir(), baseName + TRANSCRIPTION_EXT)
    }

    /**
     * Save transcription text for a recording.
     */
    fun saveTranscription(recordingName: String, text: String): Boolean {
        return try {
            val file = getTranscriptionFile(recordingName)
            file.writeText(text)
            Log.d(TAG, "Transcription saved: ${file.absolutePath} (${text.length} chars)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save transcription", e)
            false
        }
    }

    /**
     * Load transcription text for a recording, or null if not available.
     */
    fun loadTranscription(recordingName: String): String? {
        return try {
            val file = getTranscriptionFile(recordingName)
            if (file.exists() && file.length() > 0) {
                file.readText()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load transcription", e)
            null
        }
    }

    /**
     * Check if a transcription exists for a recording.
     */
    fun hasTranscription(recordingName: String): Boolean {
        return try {
            val file = getTranscriptionFile(recordingName)
            val exists = file.exists() && file.length() > 0
            Log.d(TAG, "hasTranscription($recordingName): file=${file.absolutePath}, exists=${file.exists()}, size=${if (file.exists()) file.length() else -1}, result=$exists")
            exists
        } catch (e: Exception) {
            Log.e(TAG, "hasTranscription check failed", e)
            false
        }
    }

    /**
     * Determine the language to use for speech recognition.
     * Uses TTS language setting as the source of truth.
     * Falls back to device locale.
     */
    fun getTranscriptionLanguage(): String {
        val ttsLang = context.config.ttsLanguage
        if (ttsLang.isNotEmpty()) {
            return ttsLang
        }
        return Locale.getDefault().toLanguageTag()
    }

    /**
     * Get the recording URI for transcription.
     * Handles both file:// and content:// URIs.
     */
    fun getRecordingUri(recordingResult: RecordingResult): Uri? {
        return recordingResult.uri ?: recordingResult.file?.let { Uri.fromFile(it) }
    }
}
