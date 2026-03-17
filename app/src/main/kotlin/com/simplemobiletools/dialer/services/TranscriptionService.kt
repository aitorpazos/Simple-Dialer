package com.simplemobiletools.dialer.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.simplemobiletools.dialer.R
import com.simplemobiletools.dialer.extensions.config
import com.simplemobiletools.dialer.helpers.TranscriptionManager
import com.simplemobiletools.dialer.helpers.VoskModelManager
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Foreground service that transcribes a call recording using Vosk offline STT.
 *
 * Flow:
 * 1. Receives recording URI and name via intent extras
 * 2. Ensures the Vosk model for the configured language is downloaded
 * 3. Decodes the m4a recording to 16kHz mono PCM
 * 4. Runs Vosk recognizer on the PCM data
 * 5. Saves the transcription text alongside the recording
 */
class TranscriptionService : Service() {

    companion object {
        private const val TAG = "TranscriptionService"
        private const val CHANNEL_ID = "transcription_channel"
        private const val NOTIFICATION_ID = 6000
        private const val TARGET_SAMPLE_RATE = 16000

        const val EXTRA_RECORDING_URI = "extra_recording_uri"
        const val EXTRA_RECORDING_NAME = "extra_recording_name"

        fun createIntent(context: Context, recordingUri: Uri, recordingName: String): Intent {
            return Intent(context, TranscriptionService::class.java).apply {
                putExtra(EXTRA_RECORDING_URI, recordingUri.toString())
                putExtra(EXTRA_RECORDING_NAME, recordingName)
            }
        }
    }

    private val transcriptionManager by lazy { TranscriptionManager(this) }
    private val modelManager by lazy { VoskModelManager(this) }

    @Volatile
    private var isRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uriStr = intent?.getStringExtra(EXTRA_RECORDING_URI)
        val recordingName = intent?.getStringExtra(EXTRA_RECORDING_NAME)

        if (uriStr == null || recordingName == null) {
            Log.e(TAG, "Missing recording URI or name")
            stopSelf()
            return START_NOT_STICKY
        }

        val recordingUri = Uri.parse(uriStr)

        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.transcription_in_progress)))

        if (isRunning) {
            Log.w(TAG, "Transcription already in progress, ignoring")
            return START_NOT_STICKY
        }

        isRunning = true

        Thread {
            try {
                transcribe(recordingUri, recordingName)
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
            } finally {
                isRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()

        return START_NOT_STICKY
    }

    private fun transcribe(recordingUri: Uri, recordingName: String) {
        val languageTag = transcriptionManager.getTranscriptionLanguage()
        val modelName = modelManager.resolveModelName(languageTag)

        // Download model if not present
        if (!modelManager.isModelReady(modelName)) {
            Log.d(TAG, "Model not found, downloading: $modelName")
            updateNotification(getString(R.string.transcription_downloading_model))

            val success = modelManager.downloadModel(modelName) { progress ->
                val pct = (progress * 100).toInt()
                updateNotification(getString(R.string.transcription_downloading_model_progress, pct))
            }

            if (!success) {
                Log.e(TAG, "Failed to download model: $modelName")
                return
            }
        }

        // Decode audio to 16kHz mono PCM
        updateNotification(getString(R.string.transcription_decoding_audio))
        val pcmData = decodeAudioToPcm16k(recordingUri)
        if (pcmData == null || pcmData.isEmpty()) {
            Log.e(TAG, "Failed to decode audio")
            return
        }

        Log.d(TAG, "Decoded ${pcmData.size} bytes of PCM data")

        // Run Vosk recognizer
        updateNotification(getString(R.string.transcription_in_progress))
        val modelPath = modelManager.getModelDir(modelName).absolutePath
        val transcriptionText = runVoskRecognizer(modelPath, pcmData)

        if (transcriptionText.isNullOrBlank()) {
            Log.w(TAG, "Transcription produced empty result")
            transcriptionManager.saveTranscription(recordingName, getString(R.string.transcription_empty))
        } else {
            Log.d(TAG, "Transcription complete: ${transcriptionText.length} chars")
            transcriptionManager.saveTranscription(recordingName, transcriptionText)
        }
    }

    /**
     * Decode an audio file (m4a/AAC) to 16kHz mono 16-bit PCM.
     */
    private fun decodeAudioToPcm16k(uri: Uri): ByteArray? {
        try {
            val extractor = MediaExtractor()

            // Handle both content:// and file:// URIs
            if (uri.scheme == "content") {
                val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return null
                extractor.setDataSource(pfd.fileDescriptor)
                pfd.close()
            } else {
                extractor.setDataSource(uri.path!!)
            }

            // Find audio track
            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    break
                }
            }

            if (audioTrackIndex < 0) {
                Log.e(TAG, "No audio track found")
                extractor.release()
                return null
            }

            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = format.getString(MediaFormat.KEY_MIME)!!

            Log.d(TAG, "Audio: $mime, ${sampleRate}Hz, ${channelCount}ch")

            // Create decoder
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val outputStream = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                // Feed input
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // Drain output
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outputIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }

                    val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                    val pcmBytes = ByteArray(bufferInfo.size)
                    outputBuffer.get(pcmBytes)
                    outputStream.write(pcmBytes)

                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            val rawPcm = outputStream.toByteArray()

            // Convert to mono if stereo
            val monoPcm = if (channelCount > 1) {
                convertToMono(rawPcm, channelCount)
            } else {
                rawPcm
            }

            // Resample to 16kHz if needed
            return if (sampleRate != TARGET_SAMPLE_RATE) {
                resample(monoPcm, sampleRate, TARGET_SAMPLE_RATE)
            } else {
                monoPcm
            }

        } catch (e: Exception) {
            Log.e(TAG, "Audio decode failed", e)
            return null
        }
    }

    /**
     * Convert interleaved stereo 16-bit PCM to mono by averaging channels.
     */
    private fun convertToMono(stereoData: ByteArray, channels: Int): ByteArray {
        val shortBuffer = ByteBuffer.wrap(stereoData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val totalSamples = shortBuffer.remaining()
        val monoSamples = totalSamples / channels
        val monoBuffer = ByteBuffer.allocate(monoSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        val monoShorts = monoBuffer.asShortBuffer()

        for (i in 0 until monoSamples) {
            var sum = 0L
            for (ch in 0 until channels) {
                sum += shortBuffer.get(i * channels + ch)
            }
            monoShorts.put((sum / channels).toInt().toShort())
        }

        return monoBuffer.array()
    }

    /**
     * Simple linear resampling of 16-bit PCM data.
     * Good enough for speech recognition (not audiophile quality).
     */
    private fun resample(data: ByteArray, fromRate: Int, toRate: Int): ByteArray {
        val shortBuffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val inputSamples = shortBuffer.remaining()
        val inputArray = ShortArray(inputSamples)
        shortBuffer.get(inputArray)

        val ratio = fromRate.toDouble() / toRate
        val outputSamples = (inputSamples / ratio).toInt()
        val outputBuffer = ByteBuffer.allocate(outputSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        val outputShorts = outputBuffer.asShortBuffer()

        for (i in 0 until outputSamples) {
            val srcPos = i * ratio
            val srcIndex = srcPos.toInt()
            val frac = srcPos - srcIndex

            if (srcIndex + 1 < inputSamples) {
                val sample = (inputArray[srcIndex] * (1 - frac) + inputArray[srcIndex + 1] * frac).toInt().toShort()
                outputShorts.put(sample)
            } else if (srcIndex < inputSamples) {
                outputShorts.put(inputArray[srcIndex])
            }
        }

        return outputBuffer.array()
    }

    /**
     * Run Vosk recognizer on PCM data and return the full transcription text.
     */
    private fun runVoskRecognizer(modelPath: String, pcmData: ByteArray): String? {
        return try {
            val model = Model(modelPath)
            val recognizer = Recognizer(model, TARGET_SAMPLE_RATE.toFloat())

            // Feed audio in chunks
            val chunkSize = 4096
            var offset = 0
            while (offset < pcmData.size) {
                val end = minOf(offset + chunkSize, pcmData.size)
                val chunk = pcmData.copyOfRange(offset, end)
                recognizer.acceptWaveForm(chunk, chunk.size)
                offset = end
            }

            // Get final result
            val finalResult = recognizer.finalResult

            recognizer.close()
            model.close()

            // Parse JSON result — Vosk returns {"text": "..."}
            parseVoskText(finalResult)

        } catch (e: Exception) {
            Log.e(TAG, "Vosk recognition failed", e)
            null
        }
    }

    /**
     * Parse the text field from Vosk JSON result.
     * Vosk returns: {"text": "the transcribed text"}
     */
    private fun parseVoskText(json: String?): String? {
        if (json.isNullOrBlank()) return null
        // Simple JSON parsing to avoid adding a JSON dependency
        val textMatch = Regex(""""text"\s*:\s*"([^"]*)"""").find(json)
        return textMatch?.groupValues?.get(1)?.trim()?.ifEmpty { null }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.transcription_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.transcription_channel_description)
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_phone_vector)
            .setContentTitle(getString(R.string.transcription_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
