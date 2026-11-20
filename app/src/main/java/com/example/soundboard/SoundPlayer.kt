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

    private var player: MediaPlayer? = MediaPlayer()
    private var focusRequest: AudioFocusRequest? = null
    private var current: Sound? = null
    private var fading = false
    private var fadeSteps = 0
    private var fadeStepIndex = 0
    private var fadeFrom = 1f
    private var fadeTo = 0f
    private var fadeStopAtEnd = false
    private var lastVolume = 1f

    var onStopped: (() -> Unit)? = null

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
            val endLimit = when {
                c.endSec > 0 -> (c.endSec * 1000.0).toLong()
                total > 0 -> total
                else -> Long.MAX_VALUE
            }
            val remaining = endLimit - pos
            if (remaining <= 0) {
                stopInternal()
                return
            }
            val fadeOutMs = (c.fadeOutSec * 1000.0).toLong()
            if (c.fadeOutSec > 0 && !fading && remaining <= fadeOutMs) {
                startFade(lastVolume, 0f, remaining, stopAtEnd = true)
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private val fadeRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            fadeStepIndex++
            if (fadeStepIndex >= fadeSteps) {
                setVolumeSafe(fadeTo)
                if (fadeStopAtEnd && fadeTo == 0f) stopInternal() else fading = false
                return
            }
            val t = fadeStepIndex.toFloat() / fadeSteps
            val volume = fadeFrom + (fadeTo - fadeFrom) * t
            if (setVolumeSafe(volume)) {
                handler.postDelayed(this, FADE_STEP_MS)
            } else if (fadeStopAtEnd && fadeTo == 0f) {
                stopInternal()
            } else {
                fading = false
            }
        }
    }

    private fun setVolumeSafe(v: Float): Boolean {
        lastVolume = v
        return try {
            player?.setVolume(v, v)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun play(sound: Sound) {
        val uri = sound.uri ?: return
        stopInternal()
        current = sound
        fading = false
        val p = player ?: MediaPlayer().also { player = it }
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        val path = uri.path
        if (uri.scheme == "file" && path != null) {
            p.setDataSource(path)
        } else {
            p.setDataSource(context, uri)
        }
        p.setOnPreparedListener { mp ->
            val startMs = (sound.startSec * 1000.0).toLong()
            if (startMs > 0) {
                try {
                    mp.seekTo(startMs.toInt())
                } catch (_: Exception) {
                }
            }
            val fadeInMs = (sound.fadeInSec * 1000.0).toLong()
            if (fadeInMs > 0) {
                setVolumeSafe(0f)
            } else {
                setVolumeSafe(1f)
            }
            mp.start()
            requestFocus()
            if (fadeInMs > 0) {
                startFade(0f, 1f, fadeInMs, stopAtEnd = false)
            }
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

    fun stopWithQuickFade() {
        if (current == null) return
        if (fading) {
            stopInternal()
            return
        }
        handler.removeCallbacks(watchdog)
        startFade(lastVolume, 0f, QUICK_FADE_MS, stopAtEnd = true)
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
        fadeFrom = 1f
        fadeTo = 0f
        fadeStopAtEnd = false
        lastVolume = 1f
    }

    private fun requestFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_CAN_DUCK)
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
                    AudioManager.AUDIOFOCUS_GAIN -> setVolumeSafe(lastVolume)
                }
            }
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun startFade(from: Float, to: Float, durationMs: Long, stopAtEnd: Boolean) {
        handler.removeCallbacks(fadeRunnable)
        fading = true
        fadeFrom = from
        fadeTo = to
        fadeStopAtEnd = stopAtEnd
        fadeSteps = (durationMs / FADE_STEP_MS).toInt().coerceAtLeast(1)
        fadeStepIndex = 0
        handler.post(fadeRunnable)
    }

    private fun stopInternal() {
        val wasPlaying = current != null
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
        fadeFrom = 1f
        fadeTo = 0f
        fadeStopAtEnd = false
        lastVolume = 1f
        if (wasPlaying) onStopped?.invoke()
    }

    companion object {
        private const val FADE_STEP_MS = 25L
        private const val WATCHDOG_INTERVAL_MS = 50L
        private const val QUICK_FADE_MS = 120L
        private const val DUCK_VOLUME = 0.2f
    }
}
