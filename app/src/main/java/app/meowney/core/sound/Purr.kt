package app.meowney.core.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The cat purrs while Sen strokes it: a looping sound that fades in on [start] and out on
 * [stop]. The clip is 7 s (about 1.2 MB once decoded, too big for SoundPool), so it plays
 * through MediaPlayer on its own thread like [Music].
 */
class Purr(private val context: Context) {
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "meowney-purr").apply { isDaemon = true } }

    @Volatile
    var enabled = true
        set(value) {
            field = value
            if (!value) worker.execute { release() }
        }

    // touched only on the worker thread
    private var player: MediaPlayer? = null
    private var volume = 0f
    private var target = 0f
    private var fading: ScheduledFuture<*>? = null

    fun start() {
        if (!enabled) return
        worker.execute {
            val p = player ?: open() ?: return@execute
            target = VOLUME
            if (!p.isPlaying) runCatching { p.start() }
            fade()
        }
    }

    fun stop() {
        worker.execute {
            target = 0f
            fade()
        }
    }

    private fun open(): MediaPlayer? = runCatching {
        context.assets.openFd("sfx/purr.mp3").use { fd ->
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                isLooping = true
                volume = 0f
                setVolume(0f, 0f)
                prepare()
            }
        }
    }.getOrNull().also { player = it }

    /** Steps the volume towards [target]; pauses once silent. */
    private fun fade() {
        if (fading != null) return
        fading = worker.scheduleWithFixedDelay({
            val p = player
            if (p == null) {
                fading?.cancel(false)
                fading = null
                return@scheduleWithFixedDelay
            }
            volume = if (volume < target) minOf(target, volume + STEP) else maxOf(target, volume - STEP)
            runCatching { p.setVolume(volume, volume) }
            if (volume == target) {
                if (target == 0f) runCatching { if (p.isPlaying) p.pause() }
                fading?.cancel(false)
                fading = null
            }
        }, 0, TICK_MS, TimeUnit.MILLISECONDS)
    }

    private fun release() {
        fading?.cancel(false)
        fading = null
        runCatching { player?.release() }
        player = null
        volume = 0f
        target = 0f
    }

    private companion object {
        const val VOLUME = 0.8f
        const val STEP = 0.1f
        const val TICK_MS = 40L
    }
}
