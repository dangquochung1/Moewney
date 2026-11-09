package app.meowney.feature.notes

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import app.meowney.container
import app.meowney.core.db.NoteBlockEntity
import app.meowney.core.db.NoteTagEntity
import app.meowney.core.notes.BlockType
import app.meowney.core.notes.NoteText
import app.meowney.core.notes.PhotoStyle
import app.meowney.core.notes.PixelSize
import app.meowney.core.ui.ButtonLook
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelChip
import app.meowney.core.ui.PixelDialog
import app.meowney.core.ui.PixelText
import app.meowney.core.ui.PixelTextButton
import app.meowney.core.ui.PixelTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ------------------------------------------------------------------ state

/** One block while editing. [key] is stable for the session (database ids change on every save). */
class EditBlock(val key: Long, val type: BlockType, text: String = "", checked: Boolean = false, val image: String? = null) {
    var value by mutableStateOf(TextFieldValue(text, TextRange(text.length)))
    var checked by mutableStateOf(checked)
    val focus = FocusRequester()

    fun toEntity() = NoteBlockEntity(noteId = 0, type = type.name, text = value.text, checked = checked, image = image)
}

/** The blocks of the page being edited, plus which one should get the keyboard next. */
@Stable
class EditorState(initial: List<NoteBlockEntity>) {
    private var nextKey = 1L
    val blocks = mutableStateListOf<EditBlock>()
    var focusKey by mutableStateOf<Long?>(null)
    private var lastFocused: Long? = null

    init {
        blocks.addAll(initial.map { EditBlock(nextKey++, BlockType.valueOf(it.type), it.text, it.checked, it.image) })
        if (blocks.lastOrNull()?.type != BlockType.TEXT) blocks.add(EditBlock(nextKey++, BlockType.TEXT))
    }

    /** What gets saved: photos and blocks with some text. */
    fun entities(): List<NoteBlockEntity> =
        blocks.filter { it.type == BlockType.IMAGE || it.value.text.isNotBlank() }.map { it.toEntity() }

    /** Tap on the empty paper below: type at the end. */
    fun focusLast() {
        blocks.lastOrNull { it.type == BlockType.TEXT }?.let {
            it.value = it.value.copy(selection = TextRange(it.value.text.length))
            focusKey = it.key
        }
    }

    fun focused(b: EditBlock) {
        lastFocused = b.key
    }

    private fun insert(at: Int, type: BlockType, text: String = "", image: String? = null, focus: Boolean = true): EditBlock {
        val b = EditBlock(nextKey++, type, text, image = image)
        blocks.add(at.coerceIn(0, blocks.size), b)
        if (focus && type != BlockType.IMAGE) focusKey = b.key
        return b
    }

    /** Index after which new blocks go: the block being typed in, else the end. */
    private fun anchor(): Int {
        val i = blocks.indexOfFirst { it.key == lastFocused }
        return if (i >= 0) i else blocks.lastIndex
    }

    /** "+ Chữ" / "+ Danh sách": an empty text block at the anchor turns into the new kind. */
    fun add(type: BlockType) {
        val i = anchor()
        val at = blocks.getOrNull(i)
        if (at != null && at.type == BlockType.TEXT && at.value.text.isBlank()) {
            blocks.removeAt(i)
            insert(i, type)
        } else {
            insert(i + 1, type)
        }
    }

    fun addImage(name: String) {
        val i = anchor()
        val at = blocks.getOrNull(i)
        val pos = if (at != null && at.type == BlockType.TEXT && at.value.text.isBlank()) i else i + 1
        insert(pos, BlockType.IMAGE, image = name)
        if (blocks.getOrNull(pos + 1)?.type != BlockType.TEXT) insert(pos + 1, BlockType.TEXT, focus = false)
    }

    fun remove(b: EditBlock) {
        val i = blocks.indexOf(b)
        if (i < 0) return
        blocks.removeAt(i)
        if (blocks.none { it.type == BlockType.TEXT } || blocks.last().type != BlockType.TEXT) insert(blocks.size, BlockType.TEXT, focus = false)
        blocks.getOrNull(i - 1)?.takeIf { it.type != BlockType.IMAGE }?.let { focusKey = it.key }
    }

    /** Enter in a checklist line: split it, or leave the list when the line is empty. */
    fun checkChanged(b: EditBlock, v: TextFieldValue) {
        val nl = v.text.indexOf('\n')
        if (nl < 0) {
            b.value = v
            return
        }
        val before = v.text.substring(0, nl)
        val after = v.text.substring(nl + 1).replace("\n", "")
        val i = blocks.indexOf(b)
        if (before.isBlank() && after.isBlank()) {
            blocks.removeAt(i)
            insert(i, BlockType.TEXT)
            return
        }
        b.value = TextFieldValue(before, TextRange(before.length))
        insert(i + 1, BlockType.CHECK, after).value = TextFieldValue(after, TextRange(0))
    }

    fun backspaceOnEmpty(b: EditBlock): Boolean {
        if (b.value.text.isNotEmpty()) return false
        val i = blocks.indexOf(b)
        if (b.type == BlockType.TEXT && blocks.count { it.type == BlockType.TEXT } <= 1) return false
        blocks.removeAt(i)
        if (blocks.isEmpty() || blocks.last().type != BlockType.TEXT) insert(blocks.size, BlockType.TEXT, focus = false)
        blocks.getOrNull(i - 1)?.takeIf { it.type != BlockType.IMAGE }?.let { prev ->
            prev.value = prev.value.copy(selection = TextRange(prev.value.text.length))
            focusKey = prev.key
        }
        return true
    }
}

// ------------------------------------------------------------------ editor

/** The page body: text, checklist lines and photos, one under the other. */
@Composable
fun BlockEditor(state: EditorState, placeholder: String, onPhoto: (String) -> Unit, modifier: Modifier = Modifier) {
    val m = LocalPixelMetrics.current
    LaunchedEffect(state.focusKey) {
        val k = state.focusKey ?: return@LaunchedEffect
        withFrameNanos { }
        state.blocks.firstOrNull { it.key == k }?.let { runCatching { it.focus.requestFocus() } }
        state.focusKey = null
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
        val onlyText = state.blocks.size == 1
        for (b in state.blocks) {
            key(b.key) {
                when (b.type) {
                    BlockType.TEXT -> TextBlock(b, state, if (onlyText) placeholder else "")
                    BlockType.CHECK -> CheckBlock(b, state)
                    BlockType.IMAGE -> PhotoBlock(b, state, onPhoto)
                }
            }
        }
    }
}

private fun Modifier.blockKeys(b: EditBlock, state: EditorState) = this
    .focusRequester(b.focus)
    .onFocusChanged { if (it.isFocused) state.focused(b) }
    .onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && e.key == Key.Backspace && state.backspaceOnEmpty(b) }

@Composable
private fun TextBlock(b: EditBlock, state: EditorState, placeholder: String) {
    val type = LocalPixelType.current
    Box(Modifier.fillMaxWidth()) {
        if (b.value.text.isEmpty() && placeholder.isNotEmpty()) PixelText(placeholder, style = type.body, color = Pal.InkSoft)
        BasicTextField(
            value = b.value,
            onValueChange = { b.value = it },
            textStyle = type.body.copy(color = Pal.Ink),
            cursorBrush = SolidColor(Pal.Ink),
            modifier = Modifier.fillMaxWidth().blockKeys(b, state),
        )
    }
}

@Composable
private fun CheckBlock(b: EditBlock, state: EditorState) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    var focused by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
        MiniCheck(b.checked, Modifier.padding(top = m.unit).clickable { b.checked = !b.checked }, px = 9)
        BasicTextField(
            value = b.value,
            onValueChange = { state.checkChanged(b, it) },
            textStyle = type.body.copy(
                color = if (b.checked) Pal.InkSoft else Pal.Ink,
                textDecoration = if (b.checked) TextDecoration.LineThrough else null,
            ),
            cursorBrush = SolidColor(Pal.Ink),
            modifier = Modifier.weight(1f).blockKeys(b, state).onFocusChanged { focused = it.isFocused },
        )
        if (focused) PixelText("×", style = type.bodyBold, color = Pal.InkSoft, modifier = Modifier.clickable { state.remove(b) }.padding(horizontal = m.unit * 2))
    }
}

@Composable
private fun PhotoBlock(b: EditBlock, state: EditorState, onPhoto: (String) -> Unit) {
    val m = LocalPixelMetrics.current
    var asking by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        NotePhoto(b.image!!, Modifier.clickable { onPhoto(b.image) })
        PixelTextButton("×", onClick = { asking = true }, look = ButtonLook.Light, modifier = Modifier.align(Alignment.TopEnd).padding(m.unit))
    }
    if (asking) {
        PixelDialog(onDismiss = { asking = false }) {
            PixelText("Bỏ ảnh này khỏi trang?", style = LocalPixelType.current.bodyBold, color = Pal.Ink)
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                PixelTextButton("Giữ", onClick = { asking = false }, look = ButtonLook.Light, modifier = Modifier.weight(1f))
                PixelTextButton("Bỏ ảnh", onClick = { asking = false; state.remove(b) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Tap on the paper outside any block: type at the end of the page. */
@Composable
fun Modifier.tapToType(state: EditorState): Modifier =
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.focusLast() }

/** "+ Chữ / + Danh sách / + Ảnh" under the page. */
@Composable
fun EditorToolbar(state: EditorState, onAddPhoto: () -> Unit, modifier: Modifier = Modifier) {
    val m = LocalPixelMetrics.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.unit)) {
        PixelTextButton("+ Chữ", onClick = { state.add(BlockType.TEXT) }, look = ButtonLook.Light, modifier = Modifier.weight(1f))
        PixelTextButton("+ Danh sách", onClick = { state.add(BlockType.CHECK) }, look = ButtonLook.Light, modifier = Modifier.weight(1.4f))
        PixelTextButton("+ Ảnh", onClick = onAddPhoto, look = ButtonLook.Light, modifier = Modifier.weight(1f))
    }
}

/** A photo filling the screen; tap anywhere to close. */
@Composable
fun PhotoViewer(name: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            NotePhoto(name, maxSide = 2048)
        }
    }
}

// ------------------------------------------------------------------ photos

/**
 * "Thêm ảnh": pick from the gallery (no permission needed) or take a new one with the system
 * camera, then choose Gốc / Pixel / Pixel ấm. [onAdded] gets the stored file name.
 */
@Composable
fun PhotoAdder(open: Boolean, onDismiss: () -> Unit, onAdded: (String) -> Unit) {
    val ctx = LocalContext.current
    val m = LocalPixelMetrics.current
    var picked by remember { mutableStateOf<Uri?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) picked = uri }
    val cameraUri = remember {
        val f = File(File(ctx.cacheDir, "camera").apply { mkdirs() }, "shot.jpg")
        FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    }
    val shoot = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) picked = cameraUri }
    if (open) {
        PixelDialog(onDismiss) {
            PixelText("Thêm ảnh", style = LocalPixelType.current.title, color = Pal.Ink)
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                PixelTextButton("Thư viện", onClick = {
                    onDismiss()
                    ctx.container.notesLock.ownTrip()
                    pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, modifier = Modifier.weight(1f))
                PixelTextButton("Chụp ảnh", onClick = {
                    onDismiss()
                    ctx.container.notesLock.ownTrip()
                    runCatching { shoot.launch(cameraUri) }
                }, modifier = Modifier.weight(1f))
            }
        }
    }
    picked?.let { uri ->
        PhotoStyleDialog(uri, onDismiss = { picked = null }, onAdded = {
            picked = null
            onAdded(it)
        })
    }
}

@Composable
private fun PhotoStyleDialog(uri: Uri, onDismiss: () -> Unit, onAdded: (String) -> Unit) {
    val c = LocalContext.current.container
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val scope = rememberCoroutineScope()
    var style by remember { mutableStateOf(PhotoStyle.ORIGINAL) }
    var size by remember { mutableStateOf(PixelSize.MID) }
    var busy by remember { mutableStateOf(false) }
    val src by produceState<Result<Bitmap>?>(null, uri) { value = runCatching { c.noteImages.decode(uri, 768) } }
    val previews by produceState(emptyMap<PhotoStyle, ImageBitmap>(), src, size) {
        val s = src?.getOrNull() ?: return@produceState
        value = withContext(Dispatchers.Default) { PhotoStyle.entries.associateWith { c.noteImages.render(s, it, size).asImageBitmap() } }
    }
    PixelDialog(onDismiss) {
        PixelText("Kiểu ảnh", style = type.title, color = Pal.Ink)
        val r = src
        when {
            r == null -> PixelText("Đang mở ảnh...", style = type.body, color = Pal.InkSoft)
            r.isFailure -> PixelText("Không đọc được ảnh này.", style = type.body, color = Pal.Red)
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                    for (st in PhotoStyle.entries) {
                        Column(
                            Modifier.weight(1f).clickable { style = st },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(m.unit),
                        ) {
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(1f)
                                    .border(m.unit, if (style == st) Pal.Orange else Pal.WoodDark),
                            ) {
                                previews[st]?.let {
                                    Image(
                                        it, null, Modifier.fillMaxWidth().aspectRatio(1f).padding(m.unit),
                                        contentScale = ContentScale.Crop,
                                        filterQuality = if (st == PhotoStyle.ORIGINAL) FilterQuality.Low else FilterQuality.None,
                                    )
                                }
                            }
                            PixelText(st.label, style = if (style == st) type.bodyBold else type.body, color = if (style == st) Pal.Orange else Pal.Ink, align = TextAlign.Center)
                        }
                    }
                }
                if (style != PhotoStyle.ORIGINAL) {
                    Row(horizontalArrangement = Arrangement.spacedBy(m.unit)) {
                        for (sz in PixelSize.entries) PixelChip(sz.label, selected = size == sz) { size = sz }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            PixelTextButton("Huỷ", onClick = onDismiss, look = ButtonLook.Light, modifier = Modifier.weight(1f))
            PixelTextButton(if (busy) "..." else "Thêm", enabled = !busy && r?.isSuccess == true, modifier = Modifier.weight(1f), onClick = {
                busy = true
                scope.launch {
                    val name = runCatching {
                        if (style == PhotoStyle.ORIGINAL) c.noteImages.import(uri, style, size)
                        else c.noteImages.save(withContext(Dispatchers.Default) { c.noteImages.render(r!!.getOrThrow(), style, size) }, style)
                    }.getOrNull()
                    busy = false
                    if (name != null) onAdded(name) else onDismiss()
                }
            })
        }
    }
}

// ------------------------------------------------------------------ tags

/** Pick tags from the ones in use or type a new one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagDialog(all: List<NoteTagEntity>, selected: List<String>, onDone: (List<String>) -> Unit) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val chosen = remember { mutableStateListOf<String>().apply { addAll(selected) } }
    var new by remember { mutableStateOf("") }
    val names = (all.map { it.name } + chosen).distinctBy { it.lowercase() }
    fun isOn(n: String) = chosen.any { it.equals(n, ignoreCase = true) }
    fun finish() {
        val t = NoteText.cleanTag(new)
        onDone(if (t.isNotBlank() && !isOn(t)) chosen + t else chosen.toList())
    }
    PixelDialog(onDismiss = ::finish) {
        PixelText("Tag", style = type.title, color = Pal.Ink)
        if (names.isEmpty()) PixelText("Chưa có tag nào. Gõ tag mới bên dưới.", style = type.caption, color = Pal.InkSoft)
        Column(Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit), verticalArrangement = Arrangement.spacedBy(m.unit)) {
                for (n in names) PixelChip("#$n", selected = isOn(n)) {
                    if (isOn(n)) chosen.removeAll { it.equals(n, ignoreCase = true) } else chosen.add(n)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit)) {
            PixelTextField(new, { new = it.take(24) }, Modifier.weight(1f), placeholder = "Tag mới")
            PixelTextButton("Thêm", onClick = {
                val t = NoteText.cleanTag(new)
                if (t.isNotBlank() && !isOn(t)) chosen.add(t)
                new = ""
            }, look = ButtonLook.Light)
        }
        PixelTextButton("Xong", onClick = ::finish, modifier = Modifier.fillMaxWidth())
    }
}
