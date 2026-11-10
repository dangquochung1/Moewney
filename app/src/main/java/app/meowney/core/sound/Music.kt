package app.meowney.core.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import java.util.concurrent.Executors

/**
 * Background music for the home screen: one looping track at a low volume. The home screen
 * starts it when shown and pauses it when it goes away, so it never plays over other apps.
 *
 * Every MediaPlayer call runs on one background thread: preparing audio can block for a
 * while (slow storage, a busy audio service) and must never stall the UI.
 */
class Music(private val context: Context) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "meowney-music").apply { isDaemon = true } }

    @Volatile
    var enabled = true
        set(value) {
            field = value
            if (!value) stop()
        }

    // touched only on the worker thread
    private var player: MediaPlayer? = null
    private var track: String? = null

    /** Plays [file] from assets/music (resumes it if it is the one already loaded). */
    fun play(file: String) {
        if (!enabled) return
        worker.execute {
            if (!enabled) return@execute
            val p = player
            if (p != null && track == file) {
                if (!p.isPlaying) runCatching { p.start() }
                return@execute
            }
            release()
            player = runCatching {
                context.assets.openFd("music/$file").use { fd ->
                    MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                        )
                        setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                        isLooping = true
                        setVolume(VOLUME, VOLUME)
                        prepare()
                        start()
                    }
                }
            }.getOrNull()
            track = file
        }
    }

    fun pause() {
        worker.execute { runCatching { player?.takeIf { it.isPlaying }?.pause() } }
    }

    fun stop() {
        worker.execute { release() }
    }

    private fun release() {
        runCatching { player?.release() }
        player = null
        track = null
    }

    companion object {
        const val VOLUME = 0.35f
    }
}
