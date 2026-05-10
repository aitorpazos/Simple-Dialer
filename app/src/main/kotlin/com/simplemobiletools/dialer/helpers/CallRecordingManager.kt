package com.simplemobiletools.dialer.helpers

import android.content.Context
import android.content.pm.PackageManager
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
 * Call recording manager with intelligent audio source selection.
 *
 * Audio capture on Android depends on three factors:
 *   1. Whether the app holds CAPTURE_AUDIO_OUTPUT (signature/privileged permission)
 *   2. Whether the accessibility service is enabled (unlocks VOICE_CALL on some OEMs)
 *   3. The device's audio HAL behaviour
 *
 * When CAPTURE_AUDIO_OUTPUT is granted (privileged system app):
 *   - VOICE_CALL works reliably on virtually all devices — captures both sides
 *   - No accessibility service needed for audio capture
 *
 * When only accessibility service is enabled (non-privileged):
 *   - VOICE_CALL may work on some OEMs (Samsung, Xiaomi, OnePlus)
 *   - Falls back through VOICE_COMMUNICATION → VOICE_RECOGNITION → MIC
 *
 * Without either:
 *   - VOICE_CALL rarely works
 *   - MIC captures local side only
 *
 * IMPORTANT: We do NOT override AudioManager.mode. On Android 10+ the telecom
 * framework owns audio mode during calls. Overriding it can cause the audio HAL
 * to reset routing, resulting in silence. The system already sets MODE_IN_CALL
 * when a telephony call is active.
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
     * Check if the app has CAPTURE_AUDIO_OUTPUT permission granted.
     * This is a signature/privileged permission — only granted to system apps
     * or apps signed with the platform key.
     */
    private fun hasCaptureAudioOutput(): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.CAPTURE_AUDIO_OUTPUT) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Build the audio source priority list based on available permissions.
     *
     * With CAPTURE_AUDIO_OUTPUT: VOICE_CALL is reliable, no fallback needed
     * (but we still include fallbacks for robustness).
     *
     * With accessibility service only: try VOICE_CALL first, then fallbacks.
     *
     * Without either: VOICE_CALL unlikely to work, MIC is the safe bet.
     */
    private fun getAudioSourcePriority(): List<Pair<Int, String>> {
        val hasCapturePermission = hasCaptureAudioOutput()
        val accessibilityEnabled = CallRecordingAccessibilityService.isServiceEnabled(context)

        Log.d(TAG, "Audio source selection: CAPTURE_AUDIO_OUTPUT=$hasCapturePermission, " +
            "accessibility=$accessibilityEnabled")

        return when {
            hasCapturePermission -> {
                // Best case: privileged app with CAPTURE_AUDIO_OUTPUT
                // VOICE_CALL is guaranteed to work
                listOf(
                    MediaRecorder.AudioSource.VOICE_CALL to "VOICE_CALL",
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
                    MediaRecorder.AudioSource.MIC to "MIC",
                )
            }
            accessibilityEnabled -> {
                // Accessibility service may unlock VOICE_CALL on some OEMs
                listOf(
                    MediaRecorder.AudioSource.VOICE_CALL to "VOICE_CALL",
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
                    MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
                    MediaRecorder.AudioSource.MIC to "MIC",
                )
            }
            else -> {
                // No special permissions — MIC is the most reliable
                listOf(
                    MediaRecorder.AudioSource.VOICE_CALL to "VOICE_CALL",
                    MediaRecorder.AudioSource.MIC to "MIC",
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
                )
            }
        }
    }

    /**
     * Try each audio source in priority order until one successfully starts.
     *
     * Note: We do NOT touch AudioManager.mode. On Android 10+ the telecom
     * framework manages audio mode during calls. Overriding it interferes
     * with audio routing and can cause silence.
     */
    private fun startWithFallbackChain(configureOutput: (MediaRecorder) -> Unit): Boolean {
        val sources = getAudioSourcePriority()

        // Log current audio state for diagnostics
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            Log.d(TAG, "Current audio state: mode=${audioManager.mode}, " +
                "isMusicActive=${audioManager.isMusicActive}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not read audio state: ${e.message}")
        }

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
                Log.i(TAG, "Recording started — source=$sourceName, " +
                    "captureAudioOutput=${hasCaptureAudioOutput()}, " +
                    "accessibility=${CallRecordingAccessibilityService.isServiceEnabled(context)}, " +
                    "sampleRate=$SAMPLE_RATE")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Audio source $sourceName failed: ${e.message}")
                try { mediaRecorder?.release() } catch (_: Exception) {}
                mediaRecorder = null
            }
        }

        Log.e(TAG, "All audio sources failed — cannot record")
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
