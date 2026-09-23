package com.cleaner.filter.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.os.Build
import com.cleaner.filter.capture.PresentationClock
import com.cleaner.filter.settings.AudioMode
import com.cleaner.filter.settings.FilterSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue

/**
 * Captures other apps' media audio, applies delay [PresentationClock.delayMs], optional mute windows,
 * and replays through USAGE_ASSISTANCE_ACCESSIBILITY so it can be paired with the visual mirror.
 *
 * In LEAKY_REALTIME mode, capture runs for metrics only; original speakers stay live.
 */
class FilteredAudioPipeline(
    private val context: Context,
    private val clock: PresentationClock,
) {
    private var scope: CoroutineScope? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var settings: FilterSettings = FilterSettings()

    private val delayQueue = LinkedBlockingQueue<DelayedChunk>()

    data class DelayedChunk(val pcm: ShortArray, val presentationNanos: Long)

    fun updateSettings(newSettings: FilterSettings) {
        settings = newSettings
        clock.setDelayMs(newSettings.presentationDelayMs)
    }

    fun start(projection: MediaProjection) {
        stop()
        if (!settings.audioFilterEnabled) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val sampleRate = 44100
        val channelIn = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelIn, encoding)

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val record = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelIn)
                    .setEncoding(encoding)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf * 2)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(encoding)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioRecord = record
        audioTrack = track
        scope = CoroutineScope(Dispatchers.IO + Job())

        if (settings.audioMode == AudioMode.SYNCED_DELAY) {
            duckMediaVolume(true)
        }

        record.startRecording()
        track.play()

        scope?.launch { captureLoop(record, sampleRate) }
        scope?.launch { playbackLoop(track) }
    }

    private suspend fun captureLoop(record: AudioRecord, sampleRate: Int) {
        val windowSamples = sampleRate / 50 // 20 ms
        val buffer = ShortArray(windowSamples)
        while (scope?.isActive == true) {
            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) continue
            val captureNanos = clock.captureTimestampNanos()
            val pcm = buffer.copyOf(read)
            val mute = settings.audioFilterEnabled &&
                settings.audioMode == AudioMode.SYNCED_DELAY &&
                KeywordSpotter.shouldMuteWindow(pcm, sampleRate)
            if (mute) {
                pcm.fill(0)
            }
            delayQueue.offer(
                DelayedChunk(pcm, clock.presentationTimeFor(captureNanos)),
            )
        }
    }

    private suspend fun playbackLoop(track: AudioTrack) {
        while (scope?.isActive == true) {
            val chunk = delayQueue.poll() ?: continue
            val now = android.os.SystemClock.elapsedRealtimeNanos()
            if (now < chunk.presentationNanos) {
                val waitMs = (chunk.presentationNanos - now) / 1_000_000
                kotlinx.coroutines.delay(waitMs.coerceAtMost(200))
                delayQueue.offer(chunk)
                continue
            }
            if (settings.audioMode == AudioMode.SYNCED_DELAY) {
                track.write(chunk.pcm, 0, chunk.pcm.size)
            }
        }
    }

    private fun duckMediaVolume(@Suppress("UNUSED_PARAMETER") duck: Boolean) {
        // Never change the user's media volume. Setting it to 0 made the phone feel broken.
    }

    fun stop() {
        scope?.cancel()
        scope = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        audioRecord?.release()
        audioRecord = null
        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        }
        audioTrack?.release()
        audioTrack = null
        delayQueue.clear()
        duckMediaVolume(false)
    }
}
