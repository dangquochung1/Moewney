package app.meowney.feature.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.meowney.AppContainer
import app.meowney.MeowneyApp
import app.meowney.core.data.NoteFull
import app.meowney.core.data.NoteSaved
import app.meowney.core.db.NoteBlockEntity
import app.meowney.core.db.NoteEntity
import app.meowney.core.db.NoteTagEntity
import app.meowney.core.notes.NoteKind
import app.meowney.core.ui.ButtonLook
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelChip
import app.meowney.core.ui.PixelDialog
import app.meowney.core.ui.PixelScreen
import app.meowney.core.ui.PixelText
import app.meowney.core.ui.PixelTextButton
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the editor would save right now. */
data class Draft(val note: NoteEntity, val blocks: List<NoteBlockEntity>, val tags: List<String>) {
    val isEmpty get() = note.title.isBlank() && blocks.isEmpty()
}

/**
 * Loads one page and saves it as the user types (debounced), and once more when the screen
 * goes away. Used by notes and diary pages.
 */
@OptIn(FlowPreview::class)
abstract class PageEditViewModel(protected val c: AppContainer) : ViewModel() {
    val page = MutableStateFlow<NoteFull?>(null)
    val closed = MutableStateFlow(false)
    val tags: StateFlow<List<NoteTagEntity>> = c.notes.tags.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    protected var noteId = 0L
    private val drafts = MutableStateFlow<Draft?>(null)
    private var saved: Draft? = null
    private val lock = Mutex()
    private var discarded = false

    protected abstract suspend fun load(): NoteFull?

    /** Called after each save (diary pages hand out coins here). */
    protected open fun onSaved(r: NoteSaved) {}

    /** Subclasses call this at the end of their own init (their fields are not set before). */
    protected fun start() {
        viewModelScope.launch {
            val p = load()
            noteId = p?.id ?: 0
            page.value = p
        }
        viewModelScope.launch { drafts.filterNotNull().debounce(600).collect { persist(it) } }
    }

    fun changed(d: Draft) {
        drafts.value = d
    }

    private suspend fun persist(d: Draft) = lock.withLock {
        if (discarded || d == saved) return@withLock
        if (noteId == 0L && d.isEmpty) return@withLock
        // keep what changed elsewhere (bin, archive) and take only the editor's own fields
        val base = if (noteId != 0L) c.notes.full(noteId)?.note else null
        if (base?.deletedAt != null) return@withLock
        val n = (base ?: d.note).copy(
            id = noteId, title = d.note.title, color = d.note.color, pinned = d.note.pinned,
            mood = d.note.mood, prompt = d.note.prompt,
        )
        val r = c.notes.save(n, d.blocks, d.tags)
        noteId = r.id
        saved = d
        onSaved(r)
    }

    private val appScope get() = (c.context.applicationContext as MeowneyApp).appScope

    override fun onCleared() {
        val d = drafts.value
        appScope.launch {
            if (d != null) persist(d)
            // a new note left empty is not kept
            val p = if (noteId != 0L) c.notes.full(noteId) else null
            if (p != null && !p.diary && p.isEmpty && p.note.deletedAt == null) c.notes.deleteForever(noteId)
        }
    }

    /** Saves what is typed, then runs [action] on the stored page and closes the editor. */
    protected fun thenClose(action: suspend (Long) -> Unit) = viewModelScope.launch {
        drafts.value?.let { persist(it) }
        if (noteId != 0L) action(noteId)
        lock.withLock { discarded = true }
        closed.value = true
    }

    fun trash() = thenClose { c.notes.trash(it) }
}

class NoteEditViewModel(c: AppContainer, private val id: Long) : PageEditViewModel(c) {
    init {
        start()
    }

    override suspend fun load(): NoteFull =
        (if (id > 0) c.notes.full(id) else null)
            ?: NoteFull(NoteEntity(kind = NoteKind.NOTE.name, createdAt = 0, updatedAt = 0), emptyList(), emptyList())

    fun setArchived(archived: Boolean) = thenClose { c.notes.setArchived(it, archived) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteEditScreen(vm: NoteEditViewModel, onBack: () -> Unit) {
    val loaded by vm.page.collectAsState()
    val closed by vm.closed.collectAsState()
    LaunchedEffect(closed) { if (closed) onBack() }
    val p = loaded ?: return
    val allTags by vm.tags.collectAsState()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current

    val state = remember { EditorState(p.blocks) }
    var title by remember { mutableStateOf(p.note.title) }
    var color by remember { mutableIntStateOf(p.note.color) }
    var pinned by remember { mutableStateOf(p.note.pinned) }
    val tags = remember { mutableStateListOf<String>().apply { addAll(p.tags.map { it.name }) } }
    var menu by remember { mutableStateOf(false) }
    var tagging by remember { mutableStateOf(false) }
    var addingPhoto by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state) {
        snapshotFlow { Draft(p.note.copy(title = title, color = color, pinned = pinned), state.entities(), tags.toList()) }
            .drop(1)
            .collect { vm.changed(it) }
    }

    PixelScreen(
        if (p.id == 0L) "Ghi chú mới" else "Ghi chú",
        onBack = onBack,
        actions = {
            PixelChip(if (pinned) "Đã ghim" else "Ghim", selected = pinned) { pinned = !pinned }
            PixelTextButton("⋯", onClick = { menu = true }, look = ButtonLook.Light)
        },
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .paperSheet(paper(color), m.ui.toFloat())
                .padding(start = m.unit * 4, end = m.unit * 4, top = m.unit * 3, bottom = m.unit * 4)
                .tapToType(state)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(m.unit * 2),
        ) {
            Box(Modifier.fillMaxWidth()) {
                if (title.isEmpty()) PixelText("Tiêu đề", style = type.title, color = Pal.InkSoft)
                BasicTextField(
                    title, { title = it.replace("\n", "").take(80) },
                    textStyle = type.title.copy(color = Pal.Ink), cursorBrush = SolidColor(Pal.Ink),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit * 2), verticalArrangement = Arrangement.spacedBy(m.unit)) {
                for (t in tags) PixelChip("#$t", selected = true) { tagging = true }
                PixelChip(if (tags.isEmpty()) "+ Tag" else "+") { tagging = true }
            }
            BlockEditor(state, "Viết gì đó...", onPhoto = { viewing = it })
        }
        EditorToolbar(state, onAddPhoto = { addingPhoto = true }, Modifier.padding(vertical = m.unit * 2))
    }

    PhotoAdder(addingPhoto, onDismiss = { addingPhoto = false }, onAdded = { state.addImage(it) })
    viewing?.let { PhotoViewer(it) { viewing = null } }
    if (tagging) {
        TagDialog(allTags, tags) {
            tags.clear()
            tags.addAll(it)
            tagging = false
        }
    }
    if (menu) {
        PixelDialog(onDismiss = { menu = false }) {
            PixelText("Màu giấy", style = type.bodyBold, color = Pal.Ink)
            PaperPicker(color) { color = it }
            if (p.id != 0L || title.isNotBlank() || state.entities().isNotEmpty()) {
                PixelTextButton(if (p.note.archived) "Bỏ lưu trữ" else "Lưu trữ", onClick = {
                    menu = false
                    vm.setArchived(!p.note.archived)
                }, look = ButtonLook.Light, modifier = Modifier.fillMaxWidth())
                PixelTextButton("Xoá (vào thùng rác)", onClick = {
                    menu = false
                    vm.trash()
                }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** Six paper swatches. */
@Composable
fun PaperPicker(selected: Int, onPick: (Int) -> Unit) {
    val m = LocalPixelMetrics.current
    Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2), verticalAlignment = Alignment.CenterVertically) {
        NOTE_PAPERS.forEachIndexed { i, col ->
            Canvas(
                Modifier.size(m.unit * 14)
                    .border(m.unit, if (i == selected) Pal.Orange else Pal.WoodDark)
                    .clickable { onPick(i) },
            ) {
                val inset = m.ui * 2f
                drawRect(col, androidx.compose.ui.geometry.Offset(inset, inset), androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset))
            }
        }
    }
}
