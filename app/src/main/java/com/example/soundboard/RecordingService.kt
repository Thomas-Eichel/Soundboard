package com.example.soundboard

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

class RecordingService : Service() {

    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var readThread: Thread? = null
    @Volatile private var reading = false
    private var pcm = ByteArrayOutputStream()
    private var outputFile: File? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopRecordingInternal()
            broadcastState(STATE_STOPPED)
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> {
                stopRecordingInternal()
                broadcastState(STATE_STOPPED)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRecordingInternal()
        super.onDestroy()
    }

    private fun startRecording(intent: Intent) {
        if (Build.VERSION.SDK_INT < 29) {
            broadcastState(STATE_ERROR)
            stopSelf()
            return
        }
        startInForeground()
        val rc = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        if (rc != Activity.RESULT_OK || data == null) {
            broadcastState(STATE_ERROR)
            stopSelf()
            return
        }
        try {
            val mgr = getSystemService(MediaProjectionManager::class.java)
            val proj = mgr.getMediaProjection(rc, data)
            projection = proj
            proj.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
            val config = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val ar = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .build()
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord init failed: ${ar.state}")
            }
            audioRecord = ar
            val dir = File(filesDir, "media")
            dir.mkdirs()
            outputFile = File(dir, "audio_${System.currentTimeMillis()}_aufnahme.mp3")
            pcm = ByteArrayOutputStream()
            reading = true
            ar.startRecording()
            readThread = thread {
                val buf = ByteArray(minBuf)
                while (reading) {
                    val n = try {
                        ar.read(buf, 0, buf.size)
                    } catch (_: Exception) {
                        break
                    }
                    if (n > 0) pcm.write(buf, 0, n)
                }
            }
            broadcastState(STATE_STARTED)
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            stopRecordingInternal()
            broadcastState(STATE_ERROR)
            stopSelf()
        }
    }

    private fun stopRecordingInternal() {
        reading = false
        try {
            readThread?.join(500)
        } catch (_: Exception) {
        }
        readThread = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        try {
            projection?.unregisterCallback(projectionCallback)
        } catch (_: Exception) {
        }
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        val file = outputFile
        if (file != null && pcm.size() > 0) {
            try {
                writeWav(file, pcm.toByteArray())
            } catch (e: Exception) {
                Log.e(TAG, "writeWav failed", e)
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun writeWav(file: File, data: ByteArray) {
        FileOutputStream(file).use { out ->
            val byteRate = SAMPLE_RATE * 2
            out.write("RIFF".toByteArray())
            writeIntLE(out, 36 + data.size)
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            writeIntLE(out, 16)
            writeShortLE(out, 1)
            writeShortLE(out, 1)
            writeIntLE(out, SAMPLE_RATE)
            writeIntLE(out, byteRate)
            writeShortLE(out, 2)
            writeShortLE(out, 16)
            out.write("data".toByteArray())
            writeIntLE(out, data.size)
            out.write(data)
        }
    }

    private fun writeIntLE(out: java.io.OutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 24) and 0xFF)
    }

    private fun writeShortLE(out: java.io.OutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    private fun broadcastState(state: String) {
        val i = Intent(BROADCAST_STATE).setPackage(packageName)
        i.putExtra(EXTRA_STATE, state)
        if (state == STATE_STOPPED) {
            outputFile?.let { f -> i.putExtra(EXTRA_FILE, f.absolutePath) }
        }
        sendBroadcast(i)
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.recording_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_notification))
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_START = "com.example.soundboard.action.START_RECORDING"
        const val ACTION_STOP = "com.example.soundboard.action.STOP_RECORDING"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val BROADCAST_STATE = "com.example.soundboard.REC_STATE"
        const val EXTRA_STATE = "state"
        const val EXTRA_FILE = "file"
        const val STATE_STARTED = "started"
        const val STATE_STOPPED = "stopped"
        const val STATE_ERROR = "error"
        const val CHANNEL_ID = "recording"
        const val NOTIFICATION_ID = 42
        const val SAMPLE_RATE = 44100
        private const val TAG = "RecordingService"
    }
}
