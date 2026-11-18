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
 * Cream page, white cards, green and gold as the two brand hues, red only for warnings.
 * Each hue has three tones: Deep for text, the plain name for fills, Tint for backgrounds.
 */
object Pal {
    val Night = Color(0xFF2F2929)      // outside the room walls (tile w12)
    val Canvas = Color(0xFFF7F3EA)     // page background
    val Card = Color(0xFFFFFFFF)
    val Well = Color(0xFFEFE8DA)       // text fields, tab tracks, disabled fills
    val Line = Color(0xFFE3D9C6)       // hairlines, plain-button borders, progress tracks
    val Shadow = Color(0x592A2027)     // card shadow

    val Ink = Color(0xFF2A2027)
    val InkSoft = Color(0xFF6B5446)
    val InkFaint = Color(0xFFA39483)   // placeholders, disabled labels

    val Gold = Color(0xFFF2C14E)       // primary buttons, selected tabs, coins
    val GoldPressed = Color(0xFFE3B03C)
    val GoldLip = Color(0xFFC89A2C)    // the darker edge under a gold button
    val GoldDeep = Color(0xFF8A5F00)   // gold-coloured text (5:1 on white)
    val GoldTint = Color(0xFFFFF4D6)
    val OnPrimary = Ink                // text on a gold fill

    val Green = Color(0xFF4CAF50)      // progress, ticked boxes
    val GreenDeep = Color(0xFF2E7D32)  // green text: income, "done"
    val GreenTint = Color(0xFFE8F5E9)  // icon tiles

    val Red = Color(0xFFC62828)        // warnings and delete only
    val RedTint = Color(0xFFFDECEA)

    val Sky = Color(0xFF6FA8DC)
    val SkyDeep = Color(0xFF2F6FA8)
    val SkyTint = Color(0xFFE3F0FA)
    val LilacTint = Color(0xFFECE8F3)

    // from the pixel-frame days; still used by the notebook paper, calendar grids and the room
    val WoodDark = Color(0xFF3B2A24)
    val Wood = Color(0xFF8A5536)
    val Orange = Color(0xFFE86A33)
    val Cream = Color(0xFFF6E7C8)
    val Paper = Color(0xFFEAD7B0)

    /** Chart series. A category keeps its slot from month to month; the last one is "Khác". */
    val Series = listOf(
        Color(0xFF4CAF50), Color(0xFF6FA8DC), Color(0xFFF2C14E), Color(0xFFF08DB3),
        Color(0xFFA77BCA), Color(0xFFE86A5A), Color(0xFF4DB6AC), Color(0xFFB0A89A),
    )

    /** One colour per jar, in the order of the 6-jar method. Only for telling jars apart. */
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
 * Integer scales that keep pixel art sharp.
 *
 * - [room]: screen pixels per art pixel in the cat room (room art is 176 px wide).
 * - [ui]: screen pixels per art pixel for UI chrome *and* per font pixel of body text,
 *   so panels, buttons and letters share one pixel grid.
 */
@Immutable
data class PixelMetrics(val room: Int, val ui: Int, private val density: Float, private val fontScale: Float) {
    /** Font size whose 9-pixel em is exactly [pxPerFontPixel] screen pixels per font pixel. */
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
            // Body text near 14sp, rounded to whole screen pixels per font pixel.
            val ui = (14f * density.density * density.fontScale / 9f).roundToInt().coerceAtLeast(2)
            return PixelMetrics(room, ui, density.density, density.fontScale)
        }
    }
}

@Immutable
data class PixelType(
    val caption: TextStyle,
    val body: TextStyle,
    val bodyBold: TextStyle,
    val title: TextStyle,
    val display: TextStyle,
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
        val u = metrics.ui
        fun style(px: Int, weight: FontWeight = FontWeight.Normal) =
            TextStyle(fontFamily = PixelFont, fontWeight = weight, fontSize = metrics.fontSize(px), color = Pal.Ink)
        PixelType(
            caption = style(u - 1),
            body = style(u),
            bodyBold = style(u, FontWeight.Bold),
            title = style(u + 1, FontWeight.Bold),
            display = style(u * 2, FontWeight.Bold),
        )
    }
    CompositionLocalProvider(
        LocalPixelMetrics provides metrics,
        LocalPixelType provides type,
        LocalSprites provides sprites,
        content = content,
    )
}
