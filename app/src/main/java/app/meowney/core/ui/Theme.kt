package app.meowney.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.meowney.R
import app.meowney.core.sprite.SpriteAtlas
import kotlin.math.roundToInt

/**
 * Colours. The six named ones are the palette Sen picked (sample/colorpallete.jpg); the rest
 * are roles built on them. Text is always dark on white or light fills; sprites keep their
 * own colours.
 */
object Pal {
    val LemonCream = Color(0xFFFFF6D6)
    val CoralReef = Color(0xFFFFB5A7)
    val OceanMist = Color(0xFFB7DCD6)
    val MorningSky = Color(0xFFD6E4FA)
    val GoldenDune = Color(0xFFF4D9A6)
    val SunsetClay = Color(0xFFE07A5F)

    /** Page background. */
    val Canvas = LemonCream
    val Card = Color(0xFFFFFFFF)

    /** Thin borders of cards, fields and grid lines. */
    val Line = Color(0xFFEADBB8)
    val Ink = Color(0xFF26201E)
    val InkSoft = Color(0xFF7A6E66)

    /** Main buttons (dark text on them). */
    val Primary = CoralReef

    /** A chosen chip, tab or day. */
    val Selected = GoldenDune

    /** Ticks, progress, "done". */
    val Mint = OceanMist
    val Sky = MorningSky

    /** Emphasis as a fill: today, badges, the busiest bar. */
    val Accent = SunsetClay

    /** Emphasis in text: headings, "chọn thử thách >", streaks (readable on white). */
    val AccentText = Color(0xFFB4532F)

    /** Good news in text and small fills (money in, "đã có", all habits done). */
    val Green = Color(0xFF3F8C72)

    /** Warnings: over the limit, overdue, badges, hearts. */
    val Red = Color(0xFFD0533F)

    /** The empty part of bars and faded cells. */
    val Track = Color(0xFFF2EAD6)

    /** Dark backdrop of the wake-up intro. */
    val Night = Color(0xFF2B2833)

    // Names from the wood-theme days, now pointing at the roles above.
    val Orange = SunsetClay
    val Gold = AccentText
    val Wood = AccentText
    val WoodDark = Line
    val Paper = Track
    val Cream = Card

    /** One colour per jar, in the order of the 6-jar method (kept bold so charts read apart). */
    val Jars = listOf(
        Color(0xFF6FA8DC), // Thiết yếu
        Color(0xFFF2C14E), // Tự do tài chính
        Color(0xFF5CBF6A), // Tiết kiệm dài hạn
        Color(0xFFA77BCA), // Giáo dục
        Color(0xFFF08DB3), // Hưởng thụ
        Color(0xFFE86A5A), // Cho đi
    )
}

/**
 * Sizes that keep pixel art sharp.
 *
 * - [room]: screen pixels per art pixel for full-screen art.
 * - [ui]: screen pixels per art pixel for sprites shown in the layout (icons, cats, chests);
 *   [unit] (one such pixel in dp) is also the spacing step of the layout.
 */
@Immutable
data class PixelMetrics(val room: Int, val ui: Int, private val density: Float, private val fontScale: Float) {
    /** Font size whose 9-pixel em is exactly [pxPerFontPixel] screen pixels per font pixel (the pixel font). */
    fun fontSize(pxPerFontPixel: Int): TextUnit = (9f * pxPerFontPixel / (density * fontScale)).sp

    /** Converts screen pixels to dp for layout. */
    fun dp(px: Int) = androidx.compose.ui.unit.Dp(px / density)

    /** One UI art pixel, in dp. */
    val unit get() = dp(ui)

    companion object {
        const val ROOM_WIDTH = 176
        const val ROOM_HEIGHT = 288

        fun from(widthPx: Int, heightPx: Int, density: Density): PixelMetrics {
            val room = minOf(widthPx / ROOM_WIDTH, heightPx / ROOM_HEIGHT).coerceAtLeast(1)
            val ui = (14f * density.density * density.fontScale / 9f).roundToInt().coerceAtLeast(2)
            return PixelMetrics(room, ui, density.density, density.fontScale)
        }
    }
}

/** Text styles: Nunito for everything read or tapped, the pixel font only to decorate. */
@Immutable
data class PixelType(
    val caption: TextStyle,
    val body: TextStyle,
    val bodyBold: TextStyle,
    val title: TextStyle,
    val display: TextStyle,
    /** Decoration in the pixel font: "TUẦN NÀY.", the MEOWNEY intro. */
    val pixelTitle: TextStyle,
    val pixelDisplay: TextStyle,
)

val AppFont = FontFamily(
    Font(R.font.nunito_regular, FontWeight.Normal),
    Font(R.font.nunito_semibold, FontWeight.SemiBold),
    Font(R.font.nunito_bold, FontWeight.Bold),
    Font(R.font.nunito_extrabold, FontWeight.ExtraBold),
)

val PixelFont = FontFamily(
    Font(R.font.meowney_pixel_regular, FontWeight.Normal),
    Font(R.font.meowney_pixel_bold, FontWeight.Bold),
)

val LocalPixelMetrics = staticCompositionLocalOf<PixelMetrics> { error("MeowneyTheme missing") }
val LocalPixelType = staticCompositionLocalOf<PixelType> { error("MeowneyTheme missing") }
val LocalSprites = staticCompositionLocalOf<SpriteAtlas> { error("Sprites not loaded") }

@Composable
fun MeowneyTheme(sprites: SpriteAtlas, content: @Composable () -> Unit) {
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val metrics = remember(config.screenWidthDp, config.screenHeightDp, density) {
        PixelMetrics.from(
            (config.screenWidthDp * density.density).roundToInt(),
            (config.screenHeightDp * density.density).roundToInt(),
            density,
        )
    }
    val type = remember(metrics) {
        fun text(size: Int, weight: FontWeight, line: Int = size + 6) =
            TextStyle(fontFamily = AppFont, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, color = Pal.Ink)
        fun pixel(px: Int) = TextStyle(fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = metrics.fontSize(px), color = Pal.Ink)
        PixelType(
            caption = text(12, FontWeight.SemiBold, 16),
            body = text(14, FontWeight.Normal),
            bodyBold = text(14, FontWeight.Bold),
            title = text(17, FontWeight.ExtraBold, 22),
            display = text(28, FontWeight.ExtraBold, 34),
            pixelTitle = pixel(metrics.ui * 2),
            pixelDisplay = pixel(metrics.ui * 3),
        )
    }
    CompositionLocalProvider(
        LocalPixelMetrics provides metrics,
        LocalPixelType provides type,
        LocalSprites provides sprites,
        content = content,
    )
}
