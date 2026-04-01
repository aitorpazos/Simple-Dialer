package com.simplemobiletools.dialer.helpers

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.simplemobiletools.dialer.extensions.config
import com.simplemobiletools.dialer.services.CallRecordingAccessibilityService
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Call recording manager with accessibility-service-aware audio source selection.
 *
 * On many Android devices (Android 9+), the VOICE_CALL audio source is blocked for
 * third-party apps, resulting in files that have the correct duration but contain
 * silence. When the user enables our AccessibilityService, several OEMs (Samsung,
 * Xiaomi, OnePlus, Pixel, etc.) unlock the VOICE_CALL source for the default dialer,
 * allowing reliable capture of both sides of the conversation.
 *
 * Audio source priority when accessibility service is enabled:
 *   1. VOICE_CALL          — both sides, most reliable with accessibility
 *   2. VOICE_COMMUNICATION — VoIP-style capture, works on some devices
 *   3. VOICE_RECOGNITION   — high-quality mic, sometimes captures call audio
 *   4. MIC                 — always works but only captures local side
 *
 * Without accessibility service (legacy fallback):
 *   1. VOICE_CALL          — may work on some OEMs
 *   2. MIC                 — guaranteed to work (local side only)
 *   3. VOICE_COMMUNICATION — last resort
 *
 * Key reliability improvements:
 *   - Sets AudioManager mode to MODE_IN_CALL before recording to ensure the
 *     audio framework routes call audio to the recorder.
 *   - Restores the original audio mode if recording fails.
 *   - Uses 16kHz sample rate (telephony standard) instead of 44.1kHz for better
 *     compatibility with VOICE_CALL source on constrained audio HALs.
 */
class CallRecordingManager(private val context: Context) {
    companion object {
        private const val TAG = "CallRecordingManager"

        // Telephony-standard sample rate — more compatible with VOICE_CALL
        // than 44100 on many audio HALs
        private const val SAMPLE_RATE = 16000
        private const val BIT_RATE = 128000
    }

    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordingFile: File? = null
    private var currentRecordingUri: Uri? = null
    private var currentRecordingName: String? = null
    private var parcelFd: ParcelFileDescriptor? = null
    private var isRecording = false
    private var activeAudioSource: String? = null
    private var originalAudioMode: Int = AudioManager.MODE_NORMAL

    private fun getDefaultRecordingsDir(): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "CallRecordings")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun createMediaRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
    }

    /**
     * Build the audio source priority list based on whether the accessibility
     * service is currently enabled.
     */
    private fun getAudioSourcePriority(): List<Pair<Int, String>> {
        val accessibilityEnabled = CallRecordingAccessibilityService.isServiceEnabled(context)

        return if (accessibilityEnabled) {
            listOf(
                MediaRecorder.AudioSource.VOICE_CALL to "VOICE_CALL",
                MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
                MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
                MediaRecorder.AudioSource.MIC to "MIC",
            )
        } else {
            listOf(
                MediaRecorder.AudioSource.VOICE_CALL to "VOICE_CALL",
                MediaRecorder.AudioSource.MIC to "MIC",
                MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
            )
        }
    }

    /**
     * Ensure the audio framework is in the right mode for call recording.
     * This is critical — many devices only route call audio to the recorder
     * when AudioManager.mode is MODE_IN_CALL or MODE_IN_COMMUNICATION.
     */
    private fun ensureAudioModeForRecording() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            originalAudioMode = audioManager.mode
            // MODE_IN_CALL tells the audio HAL that a telephony call is active,
            // which is required for VOICE_CALL source to capture both sides.
            if (audioManager.mode != AudioManager.MODE_IN_CALL &&
                audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.mode = AudioManager.MODE_IN_CALL
                Log.d(TAG, "Set audio mode to MODE_IN_CALL (was $originalAudioMode)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not set audio mode: ${e.message}")
        }
    }

    /**
     * Try each audio source in priority order until one successfully starts.
     */
    private fun startWithFallbackChain(configureOutput: (MediaRecorder) -> Unit): Boolean {
        val sources = getAudioSourcePriority()
        val accessibilityEnabled = CallRecordingAccessibilityService.isServiceEnabled(context)

        // Ensure audio mode is correct before attempting to start recording
        ensureAudioModeForRecording()

        for ((source, sourceName) in sources) {
            try {
                mediaRecorder?.release()
                mediaRecorder = createMediaRecorder()

                mediaRecorder!!.apply {
                    setAudioSource(source)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(SAMPLE_RATE)
                    setAudioEncodingBitRate(BIT_RATE)
                    setAudioChannels(1) // Mono — telephony is mono
                    configureOutput(this)
                    prepare()
                    start()
                }

                activeAudioSource = sourceName
                Log.i(TAG, "Recording started — source=$sourceName, accessibility=$accessibilityEnabled, " +
                    "sampleRate=$SAMPLE_RATE, audioMode=${(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode}")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Audio source $sourceName failed: ${e.message}")
                try { mediaRecorder?.release() } catch (_: Exception) {}
                mediaRecorder = null
            }
        }

        Log.e(TAG, "All audio sources failed — cannot record (accessibility=$accessibilityEnabled)")
        return false
    }

    fun startRecording(phoneNumber: String): Boolean {
        if (isRecording) return false

        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val sanitizedNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
            val filename = "call_${sanitizedNumber}_$timestamp.m4a"
            currentRecordingName = filename

            val customUriString = context.config.callRecordingPath
            if (customUriString.isNotEmpty()) {
                // Use SAF DocumentFile for custom folder
                val treeUri = Uri.parse(customUriString)
                val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
                if (treeDoc != null && treeDoc.canWrite()) {
                    val newDoc = treeDoc.createFile("audio/mp4", filename)
                    if (newDoc != null) {
                        currentRecordingUri = newDoc.uri
                        parcelFd = context.contentResolver.openFileDescriptor(newDoc.uri, "rw")

                        val started = startWithFallbackChain { recorder ->
                            recorder.setOutputFile(parcelFd!!.fileDescriptor)
                        }

                        if (started) {
                            isRecording = true
                            return true
                        } else {
                            try { parcelFd?.close() } catch (_: Exception) {}
                            try { newDoc.delete() } catch (_: Exception) {}
                            parcelFd = null
                            currentRecordingUri = null
                            return false
                        }
                    }
                }
                // If SAF folder is not writable, fall through to default
            }

            // Default: use app-private storage
            currentRecordingFile = File(getDefaultRecordingsDir(), filename)
            currentRecordingUri = null

            val started = startWithFallbackChain { recorder ->
                recorder.setOutputFile(currentRecordingFile!!.absolutePath)
            }

            if (started) {
                isRecording = true
                return true
            } else {
                currentRecordingFile?.delete()
                currentRecordingFile = null
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            cleanup()
            return false
        }
    }

    fun stopRecording(): RecordingResult? {
        if (!isRecording) return null

        val name = currentRecordingName
        val uri = currentRecordingUri
        val file = currentRecordingFile
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            parcelFd?.close()
        } catch (_: Exception) {}

        Log.i(TAG, "Recording stopped — source=$activeAudioSource, file=${name ?: "null"}")
        cleanup()

        if (name == null) return null

        // Build the URI: SAF uri if available, otherwise file URI
        val resultUri = uri ?: if (file != null) {
            Uri.fromFile(file)
        } else null

        return RecordingResult(name, resultUri, file)
    }

    private fun cleanup() {
        mediaRecorder = null
        parcelFd = null
        isRecording = false
        activeAudioSource = null
    }

    fun isCurrentlyRecording() = isRecording

    fun getCurrentRecordingName() = currentRecordingName

    /**
     * Returns the audio source currently being used, or null if not recording.
     * Useful for diagnostics.
     */
    fun getActiveAudioSource(): String? = activeAudioSource
}

data class RecordingResult(
    val name: String,
    val uri: Uri?,
    val file: File?
)
