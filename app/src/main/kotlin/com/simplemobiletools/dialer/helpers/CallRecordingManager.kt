package com.simplemobiletools.dialer.helpers

import android.Manifest
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
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Records call-time microphone audio as PCM WAV.
 *
 * Android only exposes the actual voice-call stream to privileged/system apps
 * holding CAPTURE_AUDIO_OUTPUT. An AccessibilityService does not grant that
 * permission. For regular installations, automatic mode therefore starts with
 * MIC and falls back only when that source cannot be opened. This guarantees
 * the app captures the local microphone where Android permits it; on devices
 * that block remote-call capture, speakerphone is required to make the remote
 * party audible to the microphone.
 */
class CallRecordingManager(private val context: Context) {
    companion object {
        private const val TAG = "CallRecordingManager"
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val WAV_HEADER_SIZE = 44L
        private const val STOP_TIMEOUT_MS = 5000L
        private val SAMPLE_RATES = intArrayOf(16000, 48000, 44100, 8000)
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var currentRecordingFile: File? = null
    private var currentRecordingUri: Uri? = null
    private var currentRecordingName: String? = null
    private var tempWavFile: File? = null
    private var activeAudioSource: String? = null
    private var activeSampleRate = 16000

    @Volatile
    private var isRecording = false

    @Volatile
    private var isStarting = false

    @Volatile
    private var dataBytesWritten = 0L

    @Volatile
    private var maxCapturedAmplitude = 0

    @Volatile
    private var writerFailed = false

    @Volatile
    private var lastFailure: RecordingFailure? = null

    private fun getDefaultRecordingsDir(): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "CallRecordings")
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Could not create recordings directory: ${dir.absolutePath}")
        }
        return dir
    }

    private fun getTempDir(): File {
        val dir = File(context.cacheDir, "call_recordings_tmp")
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Could not create temporary recordings directory: ${dir.absolutePath}")
        }
        return dir
    }

    private fun hasPermission(permission: String): Boolean {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun getAudioSourcePriority(): List<Pair<Int, String>> {
        val privileged = hasPermission(Manifest.permission.CAPTURE_AUDIO_OUTPUT)
        val policySources = CallRecordingPolicy.sourcePriority(
            setting = context.config.callRecordingAudioSource,
            hasCaptureAudioOutput = privileged,
        )

        Log.i(
            TAG,
            "Audio source policy: privileged=$privileged, " +
                "sources=${policySources.joinToString()}",
        )

        return policySources.map { source ->
            when (source) {
                CallRecordingPolicy.Source.VOICE_CALL -> MediaRecorder.AudioSource.VOICE_CALL to "VOICE_CALL"
                CallRecordingPolicy.Source.VOICE_COMMUNICATION ->
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION"
                CallRecordingPolicy.Source.VOICE_RECOGNITION ->
                    MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION"
                CallRecordingPolicy.Source.MIC -> MediaRecorder.AudioSource.MIC to "MIC"
            }
        }
    }

    /**
     * Opens the first source/rate combination accepted by the device. Unlike the
     * old implementation, this does not synchronously sample every source for a
     * second on the Telecom main thread. That delay could consume most of a
     * short call before recording actually began.
     */
    @Suppress("DEPRECATION")
    private fun startRecordingWithBestSource(outputFile: File): Boolean {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            Log.i(
                TAG,
                "Audio state before capture: mode=${audioManager.mode}, " +
                    "speaker=${audioManager.isSpeakerphoneOn}",
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not inspect audio state", e)
        }

        for ((source, sourceName) in getAudioSourcePriority()) {
            for (sampleRate in SAMPLE_RATES) {
                var recorder: AudioRecord? = null
                try {
                    val minimum = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT)
                    if (minimum <= 0) continue
                    val bufferSize = maxOf(minimum * 2, 4096)

                    recorder = AudioRecord(
                        source,
                        sampleRate,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        bufferSize,
                    )
                    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                        recorder.release()
                        continue
                    }

                    recorder.startRecording()
                    if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.release()
                        continue
                    }

                    audioRecord = recorder
                    activeAudioSource = sourceName
                    activeSampleRate = sampleRate
                    isRecording = true
                    recordingThread = thread(name = "CallRecorder") {
                        writeWavFile(recorder, outputFile, sampleRate, bufferSize)
                    }
                    Log.i(TAG, "Recording started: source=$sourceName, sampleRate=$sampleRate")
                    return true
                } catch (e: SecurityException) {
                    Log.w(TAG, "Source $sourceName is not permitted", e)
                    try {
                        recorder?.release()
                    } catch (_: Exception) {
                    }
                    // A permission failure is source-specific (notably VOICE_CALL),
                    // so continue to a legal microphone source when available.
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Source $sourceName at ${sampleRate}Hz failed", e)
                    try {
                        recorder?.release()
                    } catch (_: Exception) {
                    }
                }
            }
        }

        lastFailure = RecordingFailure.AUDIO_SOURCE_UNAVAILABLE
        Log.e(TAG, "No audio source could be opened")
        return false
    }

    private fun writeWavFile(
        recorder: AudioRecord,
        outputFile: File,
        sampleRate: Int,
        bufferSizeBytes: Int,
    ) {
        val buffer = ShortArray(maxOf(bufferSizeBytes / 2, 2048))
        var localDataBytes = 0L
        var localMaxAmplitude = 0

        try {
            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { output ->
                output.write(ByteArray(WAV_HEADER_SIZE.toInt()))

                while (isRecording) {
                    val read = try {
                        recorder.read(buffer, 0, buffer.size)
                    } catch (e: Exception) {
                        if (isRecording) {
                            Log.e(TAG, "AudioRecord.read failed", e)
                            writerFailed = true
                        }
                        break
                    }

                    when {
                        read > 0 -> {
                            for (index in 0 until read) {
                                localMaxAmplitude = maxOf(localMaxAmplitude, abs(buffer[index].toInt()))
                            }
                            val bytes = shortsToBytes(buffer, read)
                            output.write(bytes)
                            localDataBytes += bytes.size
                            dataBytesWritten = localDataBytes
                            maxCapturedAmplitude = localMaxAmplitude
                        }
                        read < 0 -> {
                            if (isRecording) {
                                Log.e(TAG, "AudioRecord.read returned error $read")
                                writerFailed = true
                            }
                            break
                        }
                    }
                }
                output.flush()
            }

            updateWavHeader(outputFile, localDataBytes, sampleRate)
        } catch (e: Exception) {
            writerFailed = true
            Log.e(TAG, "Could not write WAV recording", e)
        } finally {
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            } catch (_: Exception) {
            }
            try {
                recorder.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun shortsToBytes(shorts: ShortArray, count: Int): ByteArray {
        val bytes = ByteArray(count * 2)
        val byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until count) {
            byteBuffer.putShort(shorts[index])
        }
        return bytes
    }

    private fun updateWavHeader(file: File, dataSize: Long, sampleRate: Int) {
        RandomAccessFile(file, "rw").use { output ->
            val channels = 1
            val bitsPerSample = 16
            val byteRate = sampleRate * channels * bitsPerSample / 8

            output.seek(0)
            output.write("RIFF".toByteArray())
            output.write(intToLittleEndian((dataSize + 36).toInt()))
            output.write("WAVE".toByteArray())
            output.write("fmt ".toByteArray())
            output.write(intToLittleEndian(16))
            output.write(shortToLittleEndian(1))
            output.write(shortToLittleEndian(channels))
            output.write(intToLittleEndian(sampleRate))
            output.write(intToLittleEndian(byteRate))
            output.write(shortToLittleEndian(channels * bitsPerSample / 8))
            output.write(shortToLittleEndian(bitsPerSample))
            output.write("data".toByteArray())
            output.write(intToLittleEndian(dataSize.toInt()))
        }
    }

    private fun intToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte(),
            (value shr 16 and 0xFF).toByte(),
            (value shr 24 and 0xFF).toByte(),
        )
    }

    private fun shortToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte(),
        )
    }

    @Synchronized
    fun startRecording(phoneNumber: String): Boolean {
        if (isRecording || isStarting) return false

        lastFailure = null
        resetSessionState()

        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            lastFailure = RecordingFailure.PERMISSION_MISSING
            Log.e(TAG, "RECORD_AUDIO permission is missing")
            return false
        }

        isStarting = true
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val sanitizedNumber = phoneNumber.replace(Regex("[^0-9+]"), "").ifEmpty { "unknown" }
            val filename = "call_${sanitizedNumber}_$timestamp.wav"
            currentRecordingName = filename

            val customTree = context.config.callRecordingPath.takeIf { it.isNotEmpty() }?.let { path ->
                try {
                    DocumentFile.fromTreeUri(context, Uri.parse(path))?.takeIf { it.canWrite() }
                } catch (e: Exception) {
                    Log.w(TAG, "Configured recordings folder is unavailable", e)
                    null
                }
            }

            val outputFile = if (customTree != null) {
                File(getTempDir(), filename).also { tempWavFile = it }
            } else {
                File(getDefaultRecordingsDir(), filename).also {
                    currentRecordingFile = it
                    tempWavFile = it
                }
            }

            val started = startRecordingWithBestSource(outputFile)
            if (!started) {
                outputFile.delete()
                resetSessionState()
            }
            return started
        } catch (e: Exception) {
            lastFailure = RecordingFailure.STORAGE_ERROR
            Log.e(TAG, "Could not start recording", e)
            tempWavFile?.delete()
            resetSessionState()
            return false
        } finally {
            isStarting = false
        }
    }

    @Synchronized
    fun stopRecording(): RecordingResult? {
        if (!isRecording) return null

        val name = currentRecordingName
        val source = activeAudioSource
        val sampleRate = activeSampleRate
        val outputFile = tempWavFile
        isRecording = false

        // Explicitly stop capture to unblock a pending AudioRecord.read().
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }

        try {
            recordingThread?.join(STOP_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        if (recordingThread?.isAlive == true) {
            writerFailed = true
            Log.e(TAG, "Recording writer did not stop within ${STOP_TIMEOUT_MS}ms")
        }

        Log.i(
            TAG,
            "Recording stopped: source=$source, sampleRate=$sampleRate, " +
                "bytes=$dataBytesWritten, maxAmplitude=$maxCapturedAmplitude, file=$name",
        )

        if (
            name == null || outputFile == null || writerFailed || !outputFile.exists() ||
            outputFile.length() <= WAV_HEADER_SIZE
        ) {
            lastFailure = RecordingFailure.STORAGE_ERROR
            outputFile?.delete()
            resetSessionState()
            return null
        }

        if (!CallRecordingPolicy.isUsableRecording(dataBytesWritten, maxCapturedAmplitude)) {
            lastFailure = RecordingFailure.SILENT_AUDIO
            Log.e(TAG, "Discarding silent recording from source=$source")
            outputFile.delete()
            resetSessionState()
            return null
        }

        var resultFile = currentRecordingFile
        var resultUri = currentRecordingUri
        val customPath = context.config.callRecordingPath

        if (customPath.isNotEmpty() && resultFile == null) {
            try {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(customPath))
                val document = tree?.takeIf { it.canWrite() }?.createFile("audio/wav", name)
                if (document != null) {
                    context.contentResolver.openOutputStream(document.uri)?.use { destination ->
                        outputFile.inputStream().use { sourceStream -> sourceStream.copyTo(destination) }
                    } ?: throw IllegalStateException("Could not open recordings folder output stream")
                    resultUri = document.uri
                    outputFile.delete()
                } else {
                    throw IllegalStateException("Could not create recording in selected folder")
                }
            } catch (e: Exception) {
                // Never lose a valid recording because a SAF folder became stale.
                Log.e(TAG, "Could not save to selected folder; preserving in default folder", e)
                try {
                    val fallback = File(getDefaultRecordingsDir(), name)
                    outputFile.copyTo(fallback, overwrite = true)
                    outputFile.delete()
                    resultFile = fallback
                    resultUri = Uri.fromFile(fallback)
                } catch (fallbackError: Exception) {
                    lastFailure = RecordingFailure.STORAGE_ERROR
                    Log.e(TAG, "Could not preserve recording in fallback folder", fallbackError)
                    resetSessionState()
                    return null
                }
            }
        }

        if (resultUri == null && resultFile != null) {
            resultUri = Uri.fromFile(resultFile)
        }

        val result = RecordingResult(name, resultUri, resultFile, source, sampleRate)
        resetSessionState()
        return result
    }

    private fun resetSessionState() {
        audioRecord = null
        recordingThread = null
        currentRecordingFile = null
        currentRecordingUri = null
        currentRecordingName = null
        tempWavFile = null
        activeAudioSource = null
        activeSampleRate = 16000
        isRecording = false
        dataBytesWritten = 0L
        maxCapturedAmplitude = 0
        writerFailed = false
    }

    fun isCurrentlyRecording(): Boolean = isRecording

    fun getCurrentRecordingName(): String? = currentRecordingName

    fun getActiveAudioSource(): String? = activeAudioSource

    fun getLastFailure(): RecordingFailure? = lastFailure

    fun setLastFailure(failure: RecordingFailure) {
        lastFailure = failure
    }

    fun clearLastFailure() {
        lastFailure = null
    }
}

data class RecordingResult(
    val name: String,
    val uri: Uri?,
    val file: File?,
    val audioSource: String? = null,
    val sampleRate: Int? = null,
)
