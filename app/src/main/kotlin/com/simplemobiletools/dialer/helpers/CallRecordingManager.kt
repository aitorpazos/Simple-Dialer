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
 * Audio capture on Android depends on:
 *   1. Whether the app holds CAPTURE_AUDIO_OUTPUT (signature/privileged permission)
 *   2. The device's audio HAL behaviour and ROM (OEM vs AOSP-based)
 *
 * When CAPTURE_AUDIO_OUTPUT is granted (privileged system app):
 *   - VOICE_CALL works reliably — captures both sides
 *
 * When installed as a regular app (non-privileged):
 *   - VOICE_CALL starts without error on AOSP/LineageOS/e!OS but captures SILENCE.
 *     The accessibility service trick only unlocks VOICE_CALL on certain OEM ROMs
 *     (Samsung, Xiaomi, OnePlus) — not on AOSP-based ROMs.
 *   - Default "auto" mode skips VOICE_CALL and uses VOICE_COMMUNICATION → MIC.
 *   - VOICE_COMMUNICATION captures the mic stream during a call.
 *   - MIC always works and captures the local microphone (both sides audible
 *     when speakerphone is on).
 *   - User can override the audio source in Settings if their device supports
 *     VOICE_CALL without CAPTURE_AUDIO_OUTPUT.
 *
 * IMPORTANT: We do NOT override AudioManager.mode. On Android 10+ the telecom
 * framework owns audio mode during calls. Overriding it can cause the audio HAL
 * to reset routing, resulting in silence.
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
     * Without CAPTURE_AUDIO_OUTPUT (regular app install):
     *   VOICE_CALL starts without error on AOSP/LineageOS/e!OS but captures
     *   **silence** — the audio framework doesn't actually route call audio to
     *   a non-privileged recorder. The accessibility service trick only works on
     *   certain OEM ROMs (Samsung, Xiaomi, OnePlus).
     *
     *   On AOSP-based ROMs the best we can do is:
     *   - VOICE_COMMUNICATION: captures mic + may capture some call audio on
     *     devices where the audio HAL mixes it into the communication stream.
     *   - MIC: always works, captures local microphone (both sides audible
     *     when speakerphone is on).
     *
     * The user can also override the audio source manually in settings.
     */
    private fun getAudioSourcePriority(): List<Pair<Int, String>> {
        val hasCapturePermission = hasCaptureAudioOutput()
        val accessibilityEnabled = CallRecordingAccessibilityService.isServiceEnabled(context)

        Log.d(TAG, "Audio source selection: CAPTURE_AUDIO_OUTPUT=$hasCapturePermission, " +
            "accessibility=$accessibilityEnabled")

        // Check user override
        val userOverride = context.config.callRecordingAudioSource
        if (userOverride != AUDIO_SOURCE_AUTO) {
            val source = audioSourceFromSetting(userOverride)
            if (source != null) {
                Log.d(TAG, "Using user-selected audio source: $userOverride")
                return listOf(source)
            }
        }

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
            else -> {
                // Non-privileged install (regular APK on /e/OS, LineageOS, stock AOSP).
                // VOICE_CALL silently produces empty audio on these ROMs even with
                // accessibility service enabled — skip it entirely.
                // VOICE_COMMUNICATION captures the mic stream during a call and on
                // some HALs includes the remote party; MIC is the safe fallback.
                listOf(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
                    MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
                    MediaRecorder.AudioSource.MIC to "MIC",
                )
            }
        }
    }

    private fun audioSourceFromSetting(setting: String): Pair<Int, String>? {
        return when (setting) {
            AUDIO_SOURCE_VOICE_CALL -> MediaRecorder.AudioSource.VOICE_CALL to "VOICE_CALL"
            AUDIO_SOURCE_VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION"
            AUDIO_SOURCE_VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION"
            AUDIO_SOURCE_MIC -> MediaRecorder.AudioSource.MIC to "MIC"
            else -> null
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
