package moe.astar.telegramw.client

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException

class VoiceRecorder(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var currentFile: File? = null

    fun startRecording(): Boolean {
        val outputDir = context.cacheDir
        currentFile = File.createTempFile("voice_note_", ".ogg", outputDir)

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.OGG)
            setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            setOutputFile(currentFile!!.absolutePath)
            setAudioSamplingRate(16000)
            setAudioChannels(1)
            setAudioEncodingBitRate(16000)

            try {
                prepare()
                start()
            } catch (e: IOException) {
                Log.e("VoiceRecorder", "prepare() failed", e)
                return false
            } catch (e: IllegalStateException) {
                Log.e("VoiceRecorder", "start() failed", e)
                return false
            }
        }
        return true
    }

    fun stopRecording(): String? {
        return try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            currentFile?.absolutePath
        } catch (e: Exception) {
            Log.e("VoiceRecorder", "stop() failed", e)
            null
        }
    }

    fun cancelRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            // Ignore
        }
        mediaRecorder = null
        currentFile?.delete()
        currentFile = null
    }
}
