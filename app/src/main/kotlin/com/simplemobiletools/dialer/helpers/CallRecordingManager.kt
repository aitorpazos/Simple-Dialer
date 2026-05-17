package com.simplemobiletools.dialer.helpers

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.simplemobiletools.dialer.extensions.config
import com.simplemobiletools.dialer.services.CallRecordingAccessibilityService
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

/**
 * Call recording manager with intelligent audio source selection.
 *
 * Audio capture on Android depends on:
 *   1. Whether the app holds CAPTURE_AUDIO_OUTPUT (signature/privileged permission)
 *   2. Whether the accessibility service is enabled (unlocks VOICE_CALL on many devices)
 *   3. The device's audio HAL behaviour and ROM (OEM vs AOSP-based)
 *
 * When CAPTURE_AUDIO_OUTPUT is granted (privileged system app):
 *   - VOICE_CALL works reliably — captures both sides
 *
 * When installed as a regular app with accessibility service enabled:
 *   - VOICE_CALL is attempted first. The accessibility service signals elevated
 *     privileges to the audio framework and unlocks VOICE_CALL on many devices,
 *     including some AOSP-based ROMs (/e/OS, LineageOS on certain hardware).
 *   - Falls back to VOICE_COMMUNICATION → MIC if VOICE_CALL throws.
 *   - If VOICE_CALL starts without error but captures silence on a particular
 *     device, the user should switch audio source manually in Settings.
 *
 * When installed as a regular app without accessibility service:
 *   - VOICE_CALL is skipped (always produces silence without privileges).
 *   - VOICE_COMMUNICATION → VOICE_RECOGNITION → MIC are tried in order.
 *   - MIC captures the local microphone (both sides audible on speakerphone).
 *
 * IMPORTANT: We do NOT override AudioManager.mode. On Android 10+ the telecom
 * framework owns audio mode during calls. Overriding it can cause the audio HAL
 * to reset routing, resulting in silence.
 */
class CallRecordingManager(private val context: Context) {
    companion object {
        private const val TAG = "CallRecordingManager"

        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // Silence detection: check amplitude after this many milliseconds
        private const val SILENCE_CHECK_MS = 3000L
        // If max amplitude is below this threshold after SILENCE_CHECK_MS, consider it silent
        private const val SILENCE_THRESHOLD = 50
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var currentRecordingFile: File? = null
    private var currentRecordingUri: Uri? = null
    private var currentRecordingName: String? = null
    @Volatile private var isRecording = false
    private var activeAudioSource: String? = null
    // Temp WAV file — we always write to a local file first, then copy to SAF if needed
    private var tempWavFile: File? = null

    private fun getDefaultRecordingsDir(): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "CallRecordings")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getTempDir(): File {
        val dir = File(context.cacheDir, "call_recordings_tmp")
        if (!dir.exists()) dir.mkdirs()
        return dir
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
     *   - If the accessibility service is enabled, VOICE_CALL is attempted first.
     *     The accessibility service signals elevated privileges to the audio
     *     framework and unlocks VOICE_CALL on many devices — including some
     *     AOSP-based ROMs (/e/OS, LineageOS on certain hardware).
     *   - If VOICE_CALL produces silence (starts without error but captures no
     *     audio), the user should switch to a different source in Settings.
     *   - Without accessibility, VOICE_CALL is skipped entirely since it will
     *     always produce silence on non-privileged installs.
     *   - VOICE_COMMUNICATION captures the mic stream during a call.
     *   - MIC always works and captures the local microphone (both sides audible
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
            accessibilityEnabled -> {
                // Accessibility service is running — try VOICE_CALL first.
                // On many devices (including some AOSP-based ROMs like /e/OS on
                // Fairphone), the accessibility service unlocks VOICE_CALL for
                // third-party apps. If it produces silence on a particular device,
                // the user can override to VOICE_COMMUNICATION or MIC in Settings.
                listOf(
                    MediaRecorder.AudioSource.VOICE_CALL to "VOICE_CALL",
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
                    MediaRecorder.AudioSource.MIC to "MIC",
                )
            }
            else -> {
                // No special permissions and no accessibility service.
                // VOICE_CALL will silently produce empty audio — skip it.
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
     * Try each audio source in priority order. For each source, create an
     * AudioRecord, read a few seconds of PCM data, and check if it's silent.
     * If silent, try the next source. Once a non-silent source is found (or
     * all sources are exhausted), commit to recording with the best one.
     *
     * We use AudioRecord instead of MediaRecorder because:
     * 1. MediaRecorder.setAudioSource(VOICE_CALL) silently succeeds on AOSP
     *    ROMs but records silence — there's no way to detect this.
     * 2. AudioRecord gives us direct PCM buffer access so we can check
     *    amplitude in real-time and switch sources if needed.
     */
    private fun startRecordingWithBestSource(outputFile: File): Boolean {
        val sources = getAudioSourcePriority()
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            .coerceAtLeast(4096)

        // Log current audio state for diagnostics
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            Log.d(TAG, "Audio state: mode=${audioManager.mode}, " +
                "speakerOn=${audioManager.isSpeakerphoneOn}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not read audio state: ${e.message}")
        }

        for ((source, sourceName) in sources) {
            try {
                val recorder = AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "AudioRecord failed to initialize with source=$sourceName")
                    recorder.release()
                    continue
                }

                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    Log.w(TAG, "AudioRecord failed to start recording with source=$sourceName")
                    recorder.release()
                    continue
                }

                // Quick silence check: read ~1 second of audio and check amplitude
                val checkBuffer = ShortArray(SAMPLE_RATE) // 1 second at 16kHz
                var totalRead = 0
                var maxAmp = 0
                val checkStartMs = System.currentTimeMillis()
                while (totalRead < checkBuffer.size && (System.currentTimeMillis() - checkStartMs) < 2000) {
                    val remaining = checkBuffer.size - totalRead
                    val read = recorder.read(checkBuffer, totalRead, remaining)
                    if (read > 0) {
                        for (i in totalRead until totalRead + read) {
                            val amp = Math.abs(checkBuffer[i].toInt())
                            if (amp > maxAmp) maxAmp = amp
                        }
                        totalRead += read
                    } else if (read < 0) {
                        break
                    }
                }

                Log.i(TAG, "Source $sourceName: 1s check amplitude=$maxAmp (threshold=$SILENCE_THRESHOLD)")

                if (maxAmp < SILENCE_THRESHOLD && sources.indexOf(source to sourceName) < sources.size - 1) {
                    // Silent and there are more sources to try
                    Log.w(TAG, "Source $sourceName appears silent, trying next source")
                    recorder.stop()
                    recorder.release()
                    continue
                }

                // Either non-silent or last source — commit to this one
                audioRecord = recorder
                activeAudioSource = sourceName
                Log.i(TAG, "Recording committed — source=$sourceName, initialAmplitude=$maxAmp")

                // Start the background recording thread
                isRecording = true
                recordingThread = thread(name = "CallRecorder") {
                    writeWavFile(recorder, outputFile, checkBuffer, totalRead)
                }

                return true
            } catch (e: Exception) {
                Log.w(TAG, "Audio source $sourceName failed: ${e.message}")
            }
        }

        Log.e(TAG, "All audio sources failed — cannot record")
        return false
    }

    /**
     * Write PCM data from AudioRecord to a WAV file.
     * Includes the initial check buffer that was already read during source probing.
     */
    private fun writeWavFile(recorder: AudioRecord, outputFile: File, initialBuffer: ShortArray, initialCount: Int) {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            .coerceAtLeast(4096)
        val buffer = ShortArray(bufferSize / 2)

        try {
            FileOutputStream(outputFile).use { fos ->
                // Write WAV header placeholder (44 bytes) — we'll update it when done
                val header = ByteArray(44)
                fos.write(header)

                var totalDataBytes = 0L

                // Write the initial check buffer first
                if (initialCount > 0) {
                    val bytes = shortsToBytes(initialBuffer, initialCount)
                    fos.write(bytes)
                    totalDataBytes += bytes.size
                }

                // Continue recording
                while (isRecording) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val bytes = shortsToBytes(buffer, read)
                        fos.write(bytes)
                        totalDataBytes += bytes.size
                    } else if (read < 0) {
                        Log.e(TAG, "AudioRecord.read error: $read")
                        break
                    }
                }

                // Flush and update WAV header with correct sizes
                fos.flush()

                // Update WAV header
                updateWavHeader(outputFile, totalDataBytes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing WAV file", e)
        } finally {
            try {
                recorder.stop()
            } catch (_: Exception) {}
            try {
                recorder.release()
            } catch (_: Exception) {}
        }
    }

    private fun shortsToBytes(shorts: ShortArray, count: Int): ByteArray {
        val bytes = ByteArray(count * 2)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            bb.putShort(shorts[i])
        }
        return bytes
    }

    private fun updateWavHeader(file: File, dataSize: Long) {
        try {
            RandomAccessFile(file, "rw").use { raf ->
                val totalSize = dataSize + 36 // 44 - 8 header bytes
                val channels = 1
                val bitsPerSample = 16
                val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8

                raf.seek(0)
                raf.write("RIFF".toByteArray())
                raf.write(intToLittleEndian(totalSize.toInt()))
                raf.write("WAVE".toByteArray())
                raf.write("fmt ".toByteArray())
                raf.write(intToLittleEndian(16)) // PCM chunk size
                raf.write(shortToLittleEndian(1)) // PCM format
                raf.write(shortToLittleEndian(channels))
                raf.write(intToLittleEndian(SAMPLE_RATE))
                raf.write(intToLittleEndian(byteRate))
                raf.write(shortToLittleEndian(channels * bitsPerSample / 8)) // block align
                raf.write(shortToLittleEndian(bitsPerSample))
                raf.write("data".toByteArray())
                raf.write(intToLittleEndian(dataSize.toInt()))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update WAV header", e)
        }
    }

    private fun intToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte(),
            (value shr 16 and 0xFF).toByte(),
            (value shr 24 and 0xFF).toByte()
        )
    }

    private fun shortToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte()
        )
    }

    fun startRecording(phoneNumber: String): Boolean {
        if (isRecording) return false

        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val sanitizedNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
            val filename = "call_${sanitizedNumber}_$timestamp.wav"
            currentRecordingName = filename

            // Always record to a temp file first, then copy to SAF if needed
            val tempFile = File(getTempDir(), filename)
            tempWavFile = tempFile

            val customUriString = context.config.callRecordingPath
            if (customUriString.isNotEmpty()) {
                val treeUri = Uri.parse(customUriString)
                val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
                if (treeDoc != null && treeDoc.canWrite()) {
                    // We'll copy to SAF on stop
                    currentRecordingUri = null // set on stop
                    val started = startRecordingWithBestSource(tempFile)
                    if (started) {
                        return true
                    } else {
                        tempFile.delete()
                        tempWavFile = null
                        return false
                    }
                }
            }

            // Default: use app-private storage
            val defaultDir = getDefaultRecordingsDir()
            currentRecordingFile = File(defaultDir, filename)
            // Record directly to the default location
            tempWavFile = currentRecordingFile
            val started = startRecordingWithBestSource(currentRecordingFile!!)
            if (started) {
                return true
            } else {
                currentRecordingFile?.delete()
                currentRecordingFile = null
                tempWavFile = null
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
        isRecording = false

        // Wait for the recording thread to finish writing
        try {
            recordingThread?.join(5000)
        } catch (_: Exception) {}
        recordingThread = null

        Log.i(TAG, "Recording stopped — source=$activeAudioSource, file=${name ?: "null"}")

        if (name == null) {
            cleanup()
            return null
        }

        val tempFile = tempWavFile
        var resultUri: Uri? = currentRecordingUri
        var resultFile: File? = currentRecordingFile

        // If recording to SAF, copy the temp file to the SAF folder
        val customUriString = context.config.callRecordingPath
        if (customUriString.isNotEmpty() && tempFile != null && tempFile.exists() && resultFile == null) {
            try {
                val treeUri = Uri.parse(customUriString)
                val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
                if (treeDoc != null && treeDoc.canWrite()) {
                    val newDoc = treeDoc.createFile("audio/wav", name)
                    if (newDoc != null) {
                        context.contentResolver.openOutputStream(newDoc.uri)?.use { out ->
                            tempFile.inputStream().use { inp -> inp.copyTo(out) }
                        }
                        resultUri = newDoc.uri
                        Log.i(TAG, "Copied recording to SAF: ${newDoc.uri}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy recording to SAF", e)
            }
            // Clean up temp file
            try { tempFile.delete() } catch (_: Exception) {}
        }

        // If we didn't write to SAF and the file is in the default dir, set resultFile
        if (resultFile == null && tempFile != null && tempFile.exists()) {
            resultFile = tempFile
        }

        // Build final URI
        if (resultUri == null && resultFile != null) {
            resultUri = Uri.fromFile(resultFile)
        }

        cleanup()
        return RecordingResult(name, resultUri, resultFile)
    }

    private fun cleanup() {
        audioRecord = null
        recordingThread = null
        isRecording = false
        activeAudioSource = null
        tempWavFile = null
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
