package app.meowney.core.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import app.meowney.MainActivity
import app.meowney.R
import app.meowney.container
import app.meowney.core.sprite.SpriteAtlas
import app.meowney.core.sprite.drawSprite
import app.meowney.core.ui.Pal
import app.meowney.widget.EXTRA_OPEN
import app.meowney.widget.WidgetArt
import java.time.LocalDate

/**
 * Every notification is a message from the cat: its face in the mood of the message, a title
 * like "Mít đang tức giận", and the text. Shown with MessagingStyle on channels loud enough
 * to pop up on screen, the way a chat message does.
 */
object CatNotifier {
    const val CH_REMIND = "cat_remind"
    const val CH_ALERT = "cat_alert"
    const val CH_DAILY = "cat_daily"

    /** Channels from before the cat spoke; their importance cannot be raised, so they go. */
    private val OLD_CHANNELS = listOf("due", "reminder", "habits", "calendar")
    private const val PREFS = "cat_notifier"
    private const val KEY_ANGRY_DAY = "angry_day"

    @Volatile private var channelsReady = false

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        OLD_CHANNELS.forEach { nm.deleteNotificationChannel(it) }
        nm.createNotificationChannel(
            NotificationChannel(CH_REMIND, "Mèo nhắc việc", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Sự kiện sắp đến giờ, việc đến hạn, giờ nhắc thói quen"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Mèo giận và lo", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Việc quá hạn, hoá đơn đến hạn, lọ bị âm"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DAILY, "Mèo chào hỏi", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tin buổi sáng, lời khen, lúc mèo nhớ Sen"
            },
        )
    }

    fun allowed(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun channel(tone: CatTone) = when (tone) {
        CatTone.REMIND, CatTone.HABIT -> CH_REMIND
        CatTone.ANGRY, CatTone.WORRIED -> CH_ALERT
        CatTone.SAD, CatTone.HAPPY, CatTone.MORNING -> CH_DAILY
    }

    private fun pose(tone: CatTone) = when (tone) {
        CatTone.REMIND -> "sit"
        CatTone.ANGRY -> "hiss_l"
        CatTone.WORRIED -> "stand"
        CatTone.SAD -> "sad"
        CatTone.HAPPY -> "meow_sit"
        CatTone.MORNING -> "yawn_sit"
        CatTone.HABIT -> "wash_sit"
    }

    private fun tint(tone: CatTone): Color = when (tone) {
        CatTone.REMIND, CatTone.MORNING -> Pal.GoldTint
        CatTone.ANGRY -> Pal.RedTint
        CatTone.WORRIED -> Pal.SkyTint
        CatTone.SAD -> Pal.LilacTint
        CatTone.HAPPY, CatTone.HABIT -> Pal.GreenTint
    }

    private fun accent(tone: CatTone): Color = when (tone) {
        CatTone.ANGRY -> Pal.Red
        CatTone.WORRIED -> Pal.SkyDeep
        else -> Pal.GreenDeep
    }

    /**
     * The cat's face for a message: the sprite of that mood on a soft round tint, blown up so
     * the head fills the circle (Android shows message avatars at about 24 dp).
     */
    fun avatar(atlas: SpriteAtlas, catColor: Int, tone: CatTone, sizePx: Int = 192): Bitmap = WidgetArt.paint(sizePx, sizePx) {
        val c = Offset(sizePx / 2f, sizePx / 2f)
        drawCircle(tint(tone), sizePx / 2f, c)
        val cat = atlas.getOrNull("cat/$catColor/${pose(tone)}") ?: atlas["cat/$catColor/sit"]
        val scale = (sizePx * 1.25f / cat.height).toInt().coerceAtLeast(1)
        val circle = androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, sizePx / 2f)) }
        clipPath(circle) {
            // ears a little below the top edge, the body runs off the bottom
            drawSprite(cat, 0, c.x, sizePx * 0.1f + cat.height * scale, scale = scale)
        }
    }

    /** Opens the app, optionally straight on a screen ([open] is an EXTRA_OPEN value). */
    fun openIntent(context: Context, requestCode: Int, open: String? = null): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java)
            .apply { if (open != null) putExtra(EXTRA_OPEN, open) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Shows [nudge] as a message from the cat. Returns false when notifications are off or
     * this kind of message is muted in Settings. [configure] adds actions.
     */
    suspend fun show(
        context: Context,
        id: Int,
        nudge: Nudge,
        open: PendingIntent = openIntent(context, id),
        force: Boolean = false,
        configure: NotificationCompat.Builder.() -> Unit = {},
    ): Boolean {
        if (!allowed(context)) return false
        // a worker can run before the app was opened after an update
        if (!channelsReady) {
            createChannels(context)
            channelsReady = true
        }
        val c = context.container
        val settings = c.settings.now()
        if (!force && nudge.tone.name in settings.mutedTones) return false
        val title = NudgeEngine.title(nudge.tone, settings.catName)
        val face = avatar(c.sprites, settings.catColor, nudge.tone)
        val cat = Person.Builder().setName(title).setIcon(IconCompat.createWithBitmap(face)).setImportant(true).build()
        val me = Person.Builder().setName("Sen").build()
        val loud = channel(nudge.tone) != CH_DAILY
        val b = NotificationCompat.Builder(context, channel(nudge.tone))
            .setSmallIcon(R.drawable.ic_stat_cat)
            .setColor(accent(nudge.tone).toArgb())
            .setLargeIcon(face)
            .setContentTitle(title)
            .setContentText(nudge.text)
            .setStyle(NotificationCompat.MessagingStyle(me).addMessage(nudge.text, System.currentTimeMillis(), cat))
            .setCategory(if (nudge.tone == CatTone.REMIND || nudge.tone == CatTone.HABIT) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (loud) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(open)
            .setAutoCancel(true)
        b.configure()
        NotificationManagerCompat.from(context).notify(id, b.build())
        return true
    }

    /** The cat gets angry at most once a day. */
    fun angryToday(context: Context, today: LocalDate = LocalDate.now()) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_ANGRY_DAY, -1) == today.toEpochDay()

    fun markAngry(context: Context, today: LocalDate = LocalDate.now()) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_ANGRY_DAY, today.toEpochDay()).apply()
}
