package com.example.soundboard

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.abs

object WaveformLoader {

    data class Waveform(val peaks: FloatArray, val durationMs: Long)

    fun load(context: Context, uri: Uri, buckets: Int = 120, onDone: (Waveform?) -> Unit) {
        val appContext = context.applicationContext
        thread {
            val result = try {
                extract(appContext, uri, buckets)
            } catch (e: Exception) {
                Log.e(TAG, "waveform extract failed for $uri", e)
                null
            }
            Handler(Looper.getMainLooper()).post { onDone(result) }
        }
    }

    private fun extract(context: Context, uri: Uri, buckets: Int): Waveform? {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val trackIndex = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return null
        extractor.selectTrack(trackIndex)
        val inputFormat = extractor.getTrackFormat(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
        val durationUs =
            if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) inputFormat.getLong(MediaFormat.KEY_DURATION) else 0L
        val sampleRate =
            if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        val channels =
            if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

        val codec = MediaCodec.createDecoderByType(mime)
        val chunkPeaks = ArrayList<Float>()
        var sampleCount = 0L
        var chunkMax = 0f
        var chunkSamples = 0
        val chunkSize = 512
        try {
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)
                        if (buf != null) {
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEOS = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                    outIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf != null && info.size > 0) {
                            outBuf.order(ByteOrder.LITTLE_ENDIAN)
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            while (outBuf.remaining() >= 2) {
                                val a = abs(outBuf.short.toInt()) / 32768f
                                if (a > chunkMax) chunkMax = a
                                chunkSamples++
                                sampleCount++
                                if (chunkSamples >= chunkSize) {
                                    chunkPeaks.add(chunkMax)
                                    chunkMax = 0f
                                    chunkSamples = 0
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                    }
                }
            }
            if (chunkSamples > 0) chunkPeaks.add(chunkMax)
            codec.stop()
        } finally {
            codec.release()
            extractor.release()
        }
        if (chunkPeaks.isEmpty()) return null
        val peaks = FloatArray(buckets)
        for (i in 0 until buckets) {
            val from = (i.toLong() * chunkPeaks.size / buckets).toInt()
            val to = (((i + 1).toLong() * chunkPeaks.size / buckets).toInt()).coerceAtMost(chunkPeaks.size)
            var m = 0f
            for (j in from until to) {
                if (chunkPeaks[j] > m) m = chunkPeaks[j]
            }
            peaks[i] = m
        }
        val durationMs = if (durationUs > 0) {
            durationUs / 1000
        } else {
            sampleCount * 1000L / (sampleRate.toLong() * channels.coerceAtLeast(1))
        }
        return Waveform(peaks, durationMs.coerceAtLeast(1))
    }

    private const val TAG = "WaveformLoader"
}
