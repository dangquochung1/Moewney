package app.meowney.core.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/** Short sound effects from assets/sfx. Loaded once; silent when sound is off. */
class Sfx(context: Context) {
    enum class Sound(val file: String) {
        Coin("coin.wav"),
        Gold("gold.wav"),
        Alert("alert.wav"),
        Click("click.wav"),
        Success("success.wav"),
    }

    var enabled = true

    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()

    private val ids: Map<Sound, Int> = Sound.entries.mapNotNull { s ->
        runCatching { context.assets.openFd("sfx/${s.file}").use { s to pool.load(it, 1) } }.getOrNull()
    }.toMap()

    fun play(sound: Sound, volume: Float = 0.7f) {
        if (!enabled) return
        ids[sound]?.let { pool.play(it, volume, volume, 1, 0, 1f) }
    }
}
