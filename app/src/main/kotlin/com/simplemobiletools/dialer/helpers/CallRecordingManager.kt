package com.simplemobiletools.dialer.helpers

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import com.simplemobiletools.dialer.extensions.config
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class CallRecordingManager(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordingFile: File? = null
    private var isRecording = false

    fun getRecordingsDir(): File {
        val customPath = context.config.callRecordingPath
        val dir = if (customPath.isNotEmpty()) {
            File(customPath)
        } else {
            File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "CallRecordings")
        }
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun startRecording(phoneNumber: String): Boolean {
        if (isRecording) return false

        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val sanitizedNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
            val filename = "call_${sanitizedNumber}_$timestamp.m4a"
            currentRecordingFile = File(getRecordingsDir(), filename)

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(currentRecordingFile!!.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            cleanup()
            return false
        }
    }

    fun stopRecording(): File? {
        if (!isRecording) return null

        val file = currentRecordingFile
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        cleanup()
        return if (file?.exists() == true && file.length() > 0) file else null
    }

    private fun cleanup() {
        mediaRecorder = null
        isRecording = false
    }

    fun isCurrentlyRecording() = isRecording

    fun getCurrentRecordingFile() = currentRecordingFile
}
