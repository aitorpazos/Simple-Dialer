package com.simplemobiletools.dialer.helpers

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.simplemobiletools.dialer.extensions.config
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class CallRecordingManager(private val context: Context) {
    companion object {
        private const val TAG = "CallRecordingManager"

        /**
         * Audio sources to try, in priority order:
         * 1. VOICE_CALL — captures both sides (requires system-level permission, works on some OEMs)
         * 2. MIC — physical microphone, always produces audible output
         * 3. VOICE_COMMUNICATION — VoIP mic stream, often silent during phone calls
         */
        private val AUDIO_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        )
    }

    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordingFile: File? = null
    private var currentRecordingUri: Uri? = null
    private var currentRecordingName: String? = null
    private var parcelFd: ParcelFileDescriptor? = null
    private var isRecording = false

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
     * Try each audio source in [AUDIO_SOURCES] until one successfully starts.
     * Configures output to the given file descriptor.
     */
    private fun startWithFallbackChain(configureOutput: (MediaRecorder) -> Unit): Boolean {
        for (source in AUDIO_SOURCES) {
            val sourceName = audioSourceName(source)
            try {
                mediaRecorder?.release()
                mediaRecorder = createMediaRecorder()

                mediaRecorder!!.apply {
                    setAudioSource(source)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(44100)
                    setAudioEncodingBitRate(128000)
                    configureOutput(this)
                    prepare()
                    start()
                }

                Log.i(TAG, "Recording started with audio source: $sourceName")
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

    private fun audioSourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
        MediaRecorder.AudioSource.MIC -> "MIC"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        else -> "UNKNOWN($source)"
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
                            // Clean up SAF file on failure
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
    }

    fun isCurrentlyRecording() = isRecording

    fun getCurrentRecordingName() = currentRecordingName
}

data class RecordingResult(
    val name: String,
    val uri: Uri?,
    val file: File?
)
