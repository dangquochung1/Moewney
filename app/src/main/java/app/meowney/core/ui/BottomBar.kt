package app.meowney.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.meowney.R

/** The six places of the bottom bar: the cat (home) and the five parts of the app. */
enum class MainTab(val label: String) {
    Home("Mèo"),
    Money("Tiền"),
    Habits("Thói quen"),
    Calendar("Lịch"),
    Notes("Sổ tay"),
    Gift("Quà"),
}

/**
 * The bar at the bottom of home and the five main pages. The current tab sits on a Golden
 * Dune pill; [badges] puts a small red mark ("3", "!", "•") on a tab.
 */
@Composable
fun BottomBar(current: MainTab, badges: Map<MainTab, String>, onSelect: (MainTab) -> Unit, modifier: Modifier = Modifier) {
    val type = LocalPixelType.current
    Row(
        modifier
            .fillMaxWidth()
            .background(Pal.Card)
            .drawBehind { drawLine(Pal.Line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        for (tab in MainTab.entries) {
            val on = tab == current
            Column(
                Modifier
                    .weight(1f)
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .height(30.dp)
                            .widthIn(min = 48.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(if (on) Pal.Selected else Pal.Card),
                        contentAlignment = Alignment.Center,
                    ) {
                        val tint = if (on) Pal.Ink else Pal.InkSoft
                        val size = Modifier.size(22.dp)
                        when (tab) {
                            MainTab.Home -> Icon(painterResource(R.drawable.ic_nav_cat), null, size, tint)
                            MainTab.Money -> Icon(painterResource(R.drawable.ic_nav_wallet), null, size, tint)
                            MainTab.Habits -> Icon(Icons.Outlined.CheckCircle, null, size, tint)
                            MainTab.Calendar -> Icon(Icons.Outlined.DateRange, null, size, tint)
                            MainTab.Notes -> Icon(painterResource(R.drawable.ic_nav_book), null, size, tint)
                            MainTab.Gift -> Icon(painterResource(R.drawable.ic_nav_gift), null, size, tint)
                        }
                    }
                    badges[tab]?.let { b ->
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 4.dp, y = (-2).dp)
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(Pal.Red),
                            contentAlignment = Alignment.Center,
                        ) {
                            PixelText(b, style = type.caption.copy(fontSize = type.caption.fontSize * 0.85f), color = Pal.Card, align = TextAlign.Center, maxLines = 1)
                        }
                    }
                }
                PixelText(
                    tab.label,
                    style = if (on) type.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold) else type.caption,
                    color = if (on) Pal.Ink else Pal.InkSoft,
                    align = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
