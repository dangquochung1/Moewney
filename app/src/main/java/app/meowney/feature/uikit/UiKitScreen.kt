package app.meowney.feature.uikit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import app.meowney.core.format.formatVnd
import app.meowney.core.sprite.drawSprite
import app.meowney.core.ui.ButtonLook
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.LocalSprites
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelCheckbox
import app.meowney.core.ui.PixelPanel
import app.meowney.core.ui.PixelProgress
import app.meowney.core.ui.PixelText
import app.meowney.core.ui.PixelTextButton

private val JAR_NAMES = listOf("Thiết yếu", "Tự do tài chính", "Tiết kiệm dài hạn", "Giáo dục", "Hưởng thụ", "Cho đi")

/** Review screen for GĐ0: fonts, components and every cat animation. */
@Composable
fun UiKitScreen(onBack: () -> Unit) {
    val atlas = LocalSprites.current
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    var catColor by remember { mutableIntStateOf(1) }
    var hat by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf(true) }
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) {
            withFrameMillis { clock.longValue = it - start }
            delay(40)
        }
    }
    val anims = remember(catColor) { atlas.keys("cat/$catColor/").map { it.substringAfterLast('/') } }
    val cell = m.dp(36 * m.ui)

    LazyVerticalGrid(
        columns = GridCells.Adaptive(cell),
        modifier = Modifier
            .fillMaxSize()
            .background(Pal.Canvas)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(m.unit * 3),
        verticalArrangement = Arrangement.spacedBy(m.unit * 2),
        horizontalArrangement = Arrangement.spacedBy(m.unit * 2),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelTextButton("< Phòng", onClick = onBack, look = ButtonLook.Light)
                PixelText("  Xưởng UI", style = type.title, color = Pal.Ink)
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            PixelPanel(Modifier.fillMaxWidth(), sprite = "ui/wood/nine_path_panel_3") {
                Column(verticalArrangement = Arrangement.spacedBy(m.unit)) {
                    PixelText("Meowney", style = type.display)
                    PixelText("Sen ơi, hôm nay còn ${formatVnd(152_000)}!", style = type.title)
                    PixelText("ấ ầ ẩ ẫ ậ ắ ằ ẳ ẵ ặ ế ề ể ễ ệ ố ồ ổ ỗ ộ ơ ư đ", style = type.body)
                    PixelText("ẤẦẨẪẬ ẮẰẲẴẶ ẾỀỂỄỆ ỐỒỔỖỘ ƠƯĐ", style = type.bodyBold)
                    PixelText("Trẫm đói, Sen ghi sổ đi rồi trẫm ăn. Tiền Thiết yếu, Hưởng thụ, Tiết kiệm.", style = type.body)
                    PixelText("Chú thích nhỏ: 0123456789 + - × ÷ = % đ", style = type.caption, color = Pal.InkSoft)
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            PixelPanel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                        PixelTextButton("Ghi chi", onClick = {})
                        PixelTextButton("Huỷ", onClick = {}, look = ButtonLook.Light)
                        PixelTextButton("Khoá", onClick = {}, enabled = false)
                    }
                    PixelCheckbox(checked, { checked = it }, label = "Hôm nay Sen không tiêu gì")
                    JAR_NAMES.forEachIndexed { i, name ->
                        Column {
                            PixelText(name, style = type.caption, color = Pal.Ink)
                            PixelProgress(fraction = listOf(0.8f, 0.35f, 0.9f, 0.2f, 0.6f, 1f)[i], color = Pal.Jars[i])
                        }
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2), verticalAlignment = Alignment.CenterVertically) {
                PixelText("Mèo:", color = Pal.Ink)
                (1..6).forEach { c ->
                    PixelTextButton("$c", onClick = { catColor = c }, look = if (c == catColor) ButtonLook.Orange else ButtonLook.Light)
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2), verticalAlignment = Alignment.CenterVertically) {
                PixelText("Mũ:", color = Pal.Ink)
                listOf(null, "antlers", "santa", "santa2").forEach { h ->
                    PixelTextButton(h ?: "—", onClick = { hat = h }, look = if (h == hat) ButtonLook.Orange else ButtonLook.Light)
                }
            }
        }
        items(anims) { anim ->
            PixelPanel(sprite = "ui/wood/nine_path_bg", extraPadding = 1) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(Modifier.size(cell, m.dp(30 * m.ui))) {
                        val t = clock.longValue
                        val s = atlas["cat/$catColor/$anim"]
                        drawSprite(s, s.frameAt(t), size.width / 2, size.height, scale = m.ui)
                        hat?.let { h -> atlas.getOrNull("hat/$h/$anim")?.let { hs -> drawSprite(hs, hs.frameAt(t), size.width / 2, size.height, scale = m.ui) } }
                    }
                    PixelText(anim, style = type.caption, color = Pal.Ink, maxLines = 1)
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            PixelText("Đạo cụ (pack mới)", style = type.title, color = Pal.Ink, modifier = Modifier.padding(top = m.unit * 3))
        }
        items(atlas.keys("prop/")) { key ->
            PixelPanel(sprite = "ui/wood/nine_path_bg", extraPadding = 1) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(Modifier.size(cell, m.dp(30 * m.ui))) {
                        val s = atlas[key]
                        val k = (minOf(size.width / s.width, size.height / s.height)).toInt().coerceIn(1, m.ui * 2)
                        drawSprite(s, 0, size.width / 2, size.height, scale = k)
                    }
                    PixelText(key.removePrefix("prop/"), style = type.caption, color = Pal.Ink, maxLines = 1)
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            PixelText(
                "Chạm số để đổi mèo, chọn mũ để thử đồ. Tất cả ${anims.size} animation lấy từ assets/gfx/ui/cats.",
                style = type.caption, color = Pal.InkSoft, modifier = Modifier.padding(vertical = m.unit * 2),
            )
        }
    }
}
