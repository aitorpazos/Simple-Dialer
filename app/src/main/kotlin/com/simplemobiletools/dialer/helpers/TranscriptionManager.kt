package com.simplemobiletools.dialer.helpers

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.simplemobiletools.dialer.extensions.config
import com.simplemobiletools.dialer.models.RecentCall
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
     * Recording filenames follow: call_{sanitizedNumber}_{yyyyMMdd_HHmmss}.wav (or .m4a for legacy)
     *
     * @param phoneNumber the phone number from the call
     * @param startTimestampSec call start timestamp in seconds (from RecentCall.startTS)
     * @return the recording filename (e.g. "call_+123_20260317_080000.wav") or null
     */
    fun findRecordingForCall(phoneNumber: String, startTimestampSec: Int): String? {
        val lookup = RecordingLookup(0, phoneNumber, startTimestampSec)
        return findRecordings(listOf(lookup))[lookup.id]
    }

    /**
     * Resolve recordings for a group of calls with a single directory scan.
     * This can involve slow SAF provider I/O and must be called off the main thread.
     */
    fun findRecordingsForCalls(calls: List<RecentCall>): Map<Int, String> {
        val lookups = calls.map { RecordingLookup(it.id, it.phoneNumber, it.startTS) }
        return findRecordings(lookups)
    }

    private fun findRecordings(lookups: List<RecordingLookup>): Map<Int, String> {
        if (lookups.isEmpty()) return emptyMap()

        val matches = mutableMapOf<Int, String>()
        var unmatched = matchRecordings(
            lookups,
            getDefaultTranscriptionsDir().listFiles().orEmpty().mapNotNull { toRecordingCandidate(it.name) },
            matches
        )

        val customUriString = context.config.callRecordingPath
        if (unmatched.isNotEmpty() && customUriString.isNotEmpty()) {
            try {
                val treeDoc = DocumentFile.fromTreeUri(context, Uri.parse(customUriString))
                val candidates = treeDoc?.listFiles().orEmpty().mapNotNull { document ->
                    document.name?.let(::toRecordingCandidate)
                }
                unmatched = matchRecordings(unmatched, candidates, matches)
            } catch (e: Exception) {
                Log.e(TAG, "SAF lookup failed", e)
            }
        }

        if (unmatched.isNotEmpty()) {
            Log.d(TAG, "No recording found for ${unmatched.size} of ${lookups.size} calls")
        }
        return matches
    }

    private fun matchRecordings(
        lookups: List<RecordingLookup>,
        candidates: List<RecordingCandidate>,
        matches: MutableMap<Int, String>
    ): List<RecordingLookup> {
        val candidatesByNumber = candidates.groupBy { it.phoneNumber }
        return lookups.filter { lookup ->
            val sanitizedNumber = lookup.phoneNumber.replace(Regex("[^0-9+]"), "")
            val match = candidatesByNumber[sanitizedNumber]
                ?.asSequence()
                ?.filter { Math.abs(it.timestamp - lookup.startTimestampSec.toLong()) < 120 }
                ?.minByOrNull { Math.abs(it.timestamp - lookup.startTimestampSec.toLong()) }

            if (match != null) {
                matches[lookup.id] = match.name
                false
            } else {
                true
            }
        }
    }

    private fun toRecordingCandidate(name: String): RecordingCandidate? {
        if (!isRecordingFile(name)) return null
        val match = Regex("""^call_(.+)_(\d{8}_\d{6})\.(wav|m4a)$""").find(name) ?: return null
        val timestamp = extractTimestampFromFilename(name) ?: return null
        return RecordingCandidate(name, match.groupValues[1], timestamp)
    }

    private data class RecordingLookup(val id: Int, val phoneNumber: String, val startTimestampSec: Int)
    private data class RecordingCandidate(val name: String, val phoneNumber: String, val timestamp: Long)

    /**
     * Check if a filename is a recording file (supports .wav and legacy .m4a)
     */
    private fun isRecordingFile(name: String): Boolean {
        return name.endsWith(".wav") || name.endsWith(".m4a")
    }

    /**
     * Extract unix timestamp (seconds) from a recording filename like call_+123_20260317_080000.wav
     * Also supports legacy .m4a extension.
     */
    private fun extractTimestampFromFilename(filename: String): Long? {
        val regex = Regex("""call_.+_(\d{8}_\d{6})\.(wav|m4a)""")
        val match = regex.find(filename) ?: return null
        return try {
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            sdf.parse(match.groupValues[1])?.time?.div(1000)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Get a URI for a recording by name, for use within this app.
     * Returns file:// for default dir, SAF content:// for custom dir.
     * Checks both default directory and custom SAF directory.
     *
     * NOTE: For sharing with other apps, use [getShareableRecordingUri] instead
     * which wraps default-dir files in a FileProvider URI.
     */
    fun getRecordingUriByName(recordingName: String): Uri? {
        // Check default directory first — use file:// for within-app access
        val file = File(getDefaultTranscriptionsDir(), recordingName)
        if (file.exists()) {
            return Uri.fromFile(file)
        }

        // Check custom SAF directory — returns content:// document URI
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
     * Get a shareable URI for a recording (for sharing with other apps via Intent).
     * Wraps default-dir files in FileProvider; SAF URIs are already shareable.
     */
    fun getShareableRecordingUri(recordingName: String): Uri? {
        // Check default directory — wrap in FileProvider for cross-app sharing
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
     * E.g., "call_+123_20260317_080000.wav" → "call_+123_20260317_080000.txt"
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
