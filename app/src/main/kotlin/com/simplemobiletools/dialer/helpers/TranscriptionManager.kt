package com.simplemobiletools.dialer.helpers

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.simplemobiletools.dialer.extensions.config
import java.io.File

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
            file.exists() && file.length() > 0
        } catch (e: Exception) {
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
        return java.util.Locale.getDefault().toLanguageTag()
    }

    /**
     * Get the recording URI for transcription.
     * Handles both file:// and content:// URIs.
     */
    fun getRecordingUri(recordingResult: RecordingResult): Uri? {
        return recordingResult.uri ?: recordingResult.file?.let { Uri.fromFile(it) }
    }
}
