package com.example.soundboard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

class SoundPlayer(private val context: Context) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var current: Sound? = null
    private var fading = false
    private var fadeSteps = 0
    private var fadeStepIndex = 0

    private val watchdog = object : Runnable {
        override fun run() {
            val p = player ?: return
            val c = current ?: return
            val pos = try {
                p.currentPosition.toLong()
            } catch (_: Exception) {
                0L
            }
            val total = try {
                p.duration.toLong()
            } catch (_: Exception) {
                -1L
            }
            val limitMs = (c.durationSec * 1000.0).toLong()
            val remaining = when {
                limitMs > 0 && (total <= 0 || limitMs < total) -> limitMs - pos
                total > 0 -> total - pos
                else -> Long.MAX_VALUE
            }
            if (remaining <= 0) {
                stopInternal()
                return
            }
            if (c.fadeOut && !fading && remaining <= FADE_MS) {
                startFade(remaining)
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private val fadeRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            fadeStepIndex++
            if (fadeStepIndex >= fadeSteps) {
                stopInternal()
                return
            }
            val volume = 1f - fadeStepIndex.toFloat() / fadeSteps
            try {
                p.setVolume(volume, volume)
            } catch (_: Exception) {
                stopInternal()
                return
            }
            handler.postDelayed(this, FADE_STEP_MS)
        }
    }

    fun play(sound: Sound) {
        if (sound.uri == null) return
        stopInternal()
        requestFocus()
        current = sound
        fading = false
        val p = player ?: MediaPlayer().also { player = it }
        p.reset()
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        p.setDataSource(context, sound.uri)
        p.setOnPreparedListener { mp ->
            mp.start()
            handler.post(watchdog)
        }
        p.setOnCompletionListener {
            stopInternal()
        }
        p.setOnErrorListener { _, _, _ ->
            stopInternal()
            true
        }
        p.prepareAsync()
    }

    fun stop() {
        stopInternal()
    }

    fun release() {
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(fadeRunnable)
        player?.release()
        player = null
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        current = null
        fading = false
        fadeSteps = 0
        fadeStepIndex = 0
    }

    private fun requestFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> stopInternal()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                        try {
                            player?.setVolume(DUCK_VOLUME, DUCK_VOLUME)
                        } catch (_: Exception) {
                        }
                }
            }
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun startFade(remainingMs: Long) {
        fading = true
        try {
            player?.setVolume(1f, 1f)
        } catch (_: Exception) {
        }
        fadeSteps = (remainingMs / FADE_STEP_MS).toInt().coerceAtLeast(1)
        fadeStepIndex = 0
        handler.post(fadeRunnable)
    }

    private fun stopInternal() {
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(fadeRunnable)
        player?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (_: Exception) {
            }
            try {
                it.reset()
            } catch (_: Exception) {
            }
        }
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        current = null
        fading = false
        fadeSteps = 0
        fadeStepIndex = 0
    }

    companion object {
        private const val FADE_MS = 500L
        private const val FADE_STEP_MS = 25L
        private const val WATCHDOG_INTERVAL_MS = 50L
        private const val DUCK_VOLUME = 0.2f
    }
}
