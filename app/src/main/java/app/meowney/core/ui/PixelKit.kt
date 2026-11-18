package app.meowney.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ------------------------------------------------------------------ shapes

/** The soft look: rounded corners and a light shadow instead of outlines. */
object Soft {
    val Card = RoundedCornerShape(20.dp)
    val Small = RoundedCornerShape(14.dp)
    val Field = RoundedCornerShape(12.dp)
    val Pill = RoundedCornerShape(50)
}

/** Corner radius of cards (the same as [Soft.Card]). */
val CardRadius = 20.dp

/** A surface lifted off the page by a faint shadow. */
fun Modifier.softSurface(fill: Color, shape: Shape = Soft.Card, elevation: Dp = 2.dp): Modifier =
    shadow(elevation, shape, clip = false, ambientColor = Pal.Shadow, spotColor = Pal.Shadow).background(fill, shape)

// ------------------------------------------------------------------ text

@Composable
fun PixelText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalPixelType.current.body,
    color: Color = Color.Unspecified,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(
            color = if (color == Color.Unspecified) style.color else color,
            textAlign = align,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

// ------------------------------------------------------------------ panels

/** How a card is filled. [Selected] is a picked cat or option and also gets a gold rim. */
enum class CardTone(val fill: Color, val border: Color? = null) {
    Plain(Pal.Card),
    Faded(Pal.Well),
    Sky(Pal.SkyTint),
    Lemon(Pal.GoldTint),
    Selected(Pal.GoldTint, Pal.Gold),
}

/**
 * A white rounded card. [tone] picks the fill; [sprite] is kept from the pixel-frame days and
 * a "disabled" sprite means [CardTone.Faded]. [extraPadding] 2 is a normal card, 1 a tighter one.
 */
@Composable
fun PixelPanel(
    modifier: Modifier = Modifier,
    sprite: String = "ui/wood/nine_path_panel",
    extraPadding: Int = 2,
    contentAlignment: Alignment = Alignment.TopStart,
    tone: CardTone = CardTone.Plain,
    content: @Composable BoxScope.() -> Unit,
) {
    val m = LocalPixelMetrics.current
    val look = if (tone == CardTone.Plain && "disabled" in sprite) CardTone.Faded else tone
    val tight = extraPadding < 2
    val shape = if (tight) Soft.Small else Soft.Card
    Box(
        modifier = modifier
            .softSurface(look.fill, shape)
            .then(if (look.border != null) Modifier.border(1.5.dp, look.border, shape) else Modifier)
            .padding(m.unit * (if (tight) 5 else 7)),
        contentAlignment = contentAlignment,
        content = content,
    )
}

// ------------------------------------------------------------------ buttons

/** [Primary] = the gold call to action; [Plain] = white with a hairline; [Tab] = a quiet tab. */
enum class ButtonLook { Primary, Plain, Tab }

@Composable
fun PixelButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    look: ButtonLook = ButtonLook.Primary,
    padded: Boolean = true,
    shape: Shape = Soft.Small,
    content: @Composable RowScope.() -> Unit,
) {
    val m = LocalPixelMetrics.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val primary = look == ButtonLook.Primary
    val fill = when {
        !enabled -> Pal.Well
        primary -> if (pressed) Pal.GoldPressed else Pal.Gold
        else -> if (pressed) Pal.Well else Pal.Card
    }
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = m.unit * 13)
            .drawBehind {
                val outline = shape.createOutline(size, layoutDirection, this)
                // a darker lip under the gold button; it disappears while pressed
                if (primary && enabled && !pressed) translate(top = 2.dp.toPx()) { drawOutline(outline, Pal.GoldLip) }
                drawOutline(outline, fill)
            }
            .then(if (!primary && enabled) Modifier.border(1.dp, Pal.Line, shape) else Modifier)
            .clip(shape)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(
                horizontal = if (padded) m.unit * 6 else m.unit * 0,
                vertical = if (padded) m.unit * 3 else m.unit * 0,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        content = content,
    )
}

/** Labels that are really icons: steppers and "add" read better as a glyph than as a pixel letter. */
private fun glyph(text: String): ImageVector? = when (text) {
    "<", "‹" -> Icons.AutoMirrored.Filled.KeyboardArrowLeft
    ">", "›" -> Icons.AutoMirrored.Filled.KeyboardArrowRight
    "+" -> Icons.Filled.Add
    else -> null
}

@Composable
fun PixelTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    look: ButtonLook = ButtonLook.Primary,
) {
    val m = LocalPixelMetrics.current
    val ink = if (!enabled) Pal.InkFaint else if (look == ButtonLook.Primary) Pal.OnPrimary else Pal.Ink
    val icon = glyph(text)
    PixelButton(onClick, modifier, enabled, look, padded = icon == null) {
        if (icon != null) {
            Icon(icon, contentDescription = text, modifier = Modifier.padding(m.unit * 3).size(m.unit * 8), tint = ink)
        } else {
            PixelText(text, style = LocalPixelType.current.bodyBold, color = ink, align = TextAlign.Center, maxLines = 1)
        }
    }
}

/** A round icon button: back, add, settings. */
@Composable
fun RoundIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    val m = LocalPixelMetrics.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fill = if (primary) (if (pressed) Pal.GoldPressed else Pal.Gold) else (if (pressed) Pal.Well else Pal.Card)
    Box(
        modifier
            .size(m.unit * 22)
            .softSurface(fill, CircleShape)
            .clip(CircleShape)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(m.unit * 11), tint = Pal.Ink)
    }
}

// ------------------------------------------------------------------ checkbox

@Composable
fun PixelCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val m = LocalPixelMetrics.current
    Row(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Checkbox,
        ) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TickBox(checked, m.unit * 14)
        if (label != null) {
            PixelText(label, Modifier.padding(start = m.unit * 4))
        }
    }
}

/** The box of [PixelCheckbox] on its own: green with a white tick, or white with a border. */
@Composable
fun TickBox(checked: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.3f)
    Box(
        modifier
            .size(size)
            .then(if (checked) Modifier.background(Pal.Green, shape) else Modifier.background(Pal.Card, shape).border(2.dp, Pal.Line, shape)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(size * 0.7f), tint = Pal.Card)
    }
}

// ------------------------------------------------------------------ progress

/** A rounded bar: pale track, coloured fill. [heightPx] is in UI art pixels, as before. */
@Composable
fun PixelProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = Pal.Green,
    heightPx: Int = 5,
) {
    val m = LocalPixelMetrics.current
    Canvas(modifier.fillMaxWidth().height(m.dp(heightPx * m.ui))) {
        val r = CornerRadius(size.height / 2f)
        drawRoundRect(Pal.Line, Offset.Zero, size, r)
        val w = size.width * fraction.coerceIn(0f, 1f)
        // a sliver of progress still shows as a dot rather than nothing
        if (w > 0f) drawRoundRect(color, Offset.Zero, Size(w.coerceAtLeast(size.height), size.height), r)
    }
}

// ------------------------------------------------------------------ speech bubble

@Composable
fun SpeechBubble(text: String, modifier: Modifier = Modifier) {
    PixelPanel(modifier = modifier, extraPadding = 1) {
        PixelText(text, style = LocalPixelType.current.body, color = Pal.Ink)
    }
}
