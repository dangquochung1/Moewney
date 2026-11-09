package app.meowney.feature.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.meowney.AppContainer
import app.meowney.core.data.NoteFull
import app.meowney.core.db.NoteTagEntity
import app.meowney.core.ui.ButtonLook
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelChip
import app.meowney.core.ui.PixelDialog
import app.meowney.core.ui.PixelScreen
import app.meowney.core.ui.PixelText
import app.meowney.core.ui.PixelTextButton
import app.meowney.core.ui.PixelTextField
import app.meowney.core.ui.shortDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class NotesActions(
    val onBack: () -> Unit,
    val onNote: (Long) -> Unit,
    val onNewNote: () -> Unit,
    val onDiary: (LocalDate, Int?) -> Unit,
    val onSearch: () -> Unit,
    val onStats: () -> Unit,
    val onBin: (archive: Boolean) -> Unit,
)

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModel(val c: AppContainer, initialTab: Int) : ViewModel() {
    val tab = MutableStateFlow(initialTab)
    val tagFilter = MutableStateFlow<Long?>(null)
    val cards: StateFlow<List<NoteFull>?> = tagFilter.flatMapLatest { c.notes.cards(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val tags: StateFlow<List<NoteTagEntity>> = c.notes.tags.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun renameTag(t: NoteTagEntity, name: String) = viewModelScope.launch { c.notes.renameTag(t, name) }
    fun deleteTag(t: NoteTagEntity) = viewModelScope.launch {
        if (tagFilter.value == t.id) tagFilter.value = null
        c.notes.deleteTag(t.id)
    }
}

@Composable
fun NotesScreen(vm: NotesViewModel, diary: DiaryViewModel, actions: NotesActions) {
    val tab by vm.tab.collectAsState()
    val m = LocalPixelMetrics.current
    var menu by remember { mutableStateOf(false) }
    var managingTags by remember { mutableStateOf(false) }

    PixelScreen(
        "Sổ tay",
        onBack = actions.onBack,
        actions = {
            PixelTextButton("Tìm", onClick = actions.onSearch, look = ButtonLook.Light)
            PixelTextButton("⋯", onClick = { menu = true }, look = ButtonLook.Light)
        },
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = m.unit * 2), horizontalArrangement = Arrangement.spacedBy(m.unit)) {
            PixelChip("Nhật ký", selected = tab == 0, modifier = Modifier.weight(1f)) { vm.tab.value = 0 }
            PixelChip("Ghi chú", selected = tab == 1, modifier = Modifier.weight(1f)) { vm.tab.value = 1 }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == 0) DiaryTab(diary, actions) else NotesGrid(vm, actions)
        }
    }

    if (menu) {
        PixelDialog(onDismiss = { menu = false }) {
            for ((label, go) in listOf(
                "Thống kê tâm trạng" to actions.onStats,
                "Quản lý tag" to { managingTags = true },
                "Lưu trữ" to { actions.onBin(true) },
                "Thùng rác" to { actions.onBin(false) },
            )) {
                PixelTextButton(label, onClick = { menu = false; go() }, look = ButtonLook.Light, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    if (managingTags) TagManageDialog(vm) { managingTags = false }
}

@Composable
private fun NotesGrid(vm: NotesViewModel, actions: NotesActions) {
    val list by vm.cards.collectAsState()
    val tags by vm.tags.collectAsState()
    val filter by vm.tagFilter.collectAsState()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    Column(Modifier.fillMaxSize()) {
        if (tags.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = m.unit * 2), horizontalArrangement = Arrangement.spacedBy(m.unit)) {
                PixelChip("Tất cả", selected = filter == null) { vm.tagFilter.value = null }
                for (t in tags) PixelChip("#${t.name}", selected = filter == t.id) { vm.tagFilter.value = if (filter == t.id) null else t.id }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val cards = list
            if (cards != null && cards.isEmpty()) {
                PixelText(
                    if (filter == null) "Chưa có ghi chú nào.\nBấm \"+ Ghi chú\" để viết, trẫm giữ hộ." else "Không có ghi chú nào gắn tag này.",
                    style = type.body, color = Pal.Paper, modifier = Modifier.align(Alignment.Center),
                )
            }
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                verticalItemSpacing = m.unit * 3,
                horizontalArrangement = Arrangement.spacedBy(m.unit * 3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = m.unit * 2, bottom = m.unit * 24),
            ) {
                items(cards.orEmpty(), key = { it.id }) { n ->
                    NoteCard(n, Modifier.clickable { actions.onNote(n.id) })
                }
            }
            PixelTextButton("+ Ghi chú", onClick = actions.onNewNote, modifier = Modifier.align(Alignment.BottomEnd).padding(m.unit * 2))
        }
    }
}

@Composable
private fun TagManageDialog(vm: NotesViewModel, onDismiss: () -> Unit) {
    val tags by vm.tags.collectAsState()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    var editing by remember { mutableStateOf<NoteTagEntity?>(null) }
    var name by remember { mutableStateOf("") }
    PixelDialog(onDismiss) {
        PixelText("Tag", style = type.title, color = Pal.Ink)
        if (tags.isEmpty()) PixelText("Chưa có tag nào.", style = type.body, color = Pal.InkSoft)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(m.unit)) {
            for (t in tags) {
                if (editing?.id == t.id) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit)) {
                        PixelTextField(name, { name = it.take(24) }, Modifier.weight(1f))
                        PixelTextButton("Lưu", onClick = { vm.renameTag(t, name); editing = null })
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit)) {
                        PixelText("#${t.name}", style = type.body, color = Pal.Ink, modifier = Modifier.weight(1f), maxLines = 1)
                        PixelTextButton("Sửa", onClick = { editing = t; name = t.name }, look = ButtonLook.Light)
                        PixelTextButton("Xoá", onClick = { vm.deleteTag(t) }, look = ButtonLook.Light)
                    }
                }
            }
        }
        PixelText("Đổi tên trùng tag khác thì hai tag gộp làm một. Xoá tag không xoá ghi chú.", style = type.caption, color = Pal.InkSoft)
        PixelTextButton("Xong", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
    }
}

// ------------------------------------------------------------------ archive & bin

class NoteBinViewModel(private val c: AppContainer, val archive: Boolean) : ViewModel() {
    val list: StateFlow<List<NoteFull>?> = (if (archive) c.notes.archived else c.notes.bin)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun restore(n: NoteFull) = viewModelScope.launch {
        if (archive) c.notes.setArchived(n.id, false) else c.notes.restore(n.id)
    }

    fun deleteForever(n: NoteFull) = viewModelScope.launch { c.notes.deleteForever(n.id) }
    fun emptyBin() = viewModelScope.launch { c.notes.emptyBin(list.value.orEmpty().map { it.id }) }
}

@Composable
fun NoteBinScreen(vm: NoteBinViewModel, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val list by vm.list.collectAsState()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    var confirm by remember { mutableStateOf<NoteFull?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    PixelScreen(
        if (vm.archive) "Lưu trữ" else "Thùng rác",
        onBack = onBack,
        actions = { if (!vm.archive && !list.isNullOrEmpty()) PixelTextButton("Dọn hết", onClick = { confirmAll = true }, look = ButtonLook.Light) },
    ) {
        if (list?.isEmpty() == true) {
            PixelText(
                if (vm.archive) "Chưa cất ghi chú nào." else "Thùng rác trống. Đồ xoá nằm đây 30 ngày rồi mới mất hẳn.",
                style = type.body, color = Pal.Paper,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(m.unit * 3), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = m.unit * 4)) {
            items(list.orEmpty(), key = { it.id }) { n ->
                Column(verticalArrangement = Arrangement.spacedBy(m.unit)) {
                    if (n.diary) DiaryCard(n, hidden = false, Modifier.fillMaxWidth())
                    else NoteCard(n, Modifier.fillMaxWidth().let { if (vm.archive) it.clickable { onOpen(n.id) } else it })
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit)) {
                        if (!vm.archive) {
                            val left = 30 - ((System.currentTimeMillis() - (n.note.deletedAt ?: 0)) / 86_400_000L)
                            PixelText("còn ${left.coerceAtLeast(0)} ngày", style = type.caption, color = Pal.Paper, modifier = Modifier.weight(1f))
                        } else {
                            PixelText("sửa ${updated(n)}", style = type.caption, color = Pal.Paper, modifier = Modifier.weight(1f))
                        }
                        PixelTextButton(if (vm.archive) "Bỏ lưu trữ" else "Khôi phục", onClick = { vm.restore(n) }, look = ButtonLook.Light)
                        if (!vm.archive) PixelTextButton("Xoá hẳn", onClick = { confirm = n })
                    }
                }
            }
        }
    }
    confirm?.let { n ->
        PixelDialog(onDismiss = { confirm = null }) {
            PixelText("Xoá hẳn? Không lấy lại được đâu.", style = type.bodyBold, color = Pal.Ink)
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                PixelTextButton("Thôi", onClick = { confirm = null }, look = ButtonLook.Light, modifier = Modifier.weight(1f))
                PixelTextButton("Xoá hẳn", onClick = { vm.deleteForever(n); confirm = null }, modifier = Modifier.weight(1f))
            }
        }
    }
    if (confirmAll) {
        PixelDialog(onDismiss = { confirmAll = false }) {
            PixelText("Dọn sạch thùng rác (${list?.size ?: 0} mục, kèm ảnh)?", style = type.bodyBold, color = Pal.Ink)
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                PixelTextButton("Thôi", onClick = { confirmAll = false }, look = ButtonLook.Light, modifier = Modifier.weight(1f))
                PixelTextButton("Dọn hết", onClick = { vm.emptyBin(); confirmAll = false }, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun updated(n: NoteFull) = shortDate(Instant.ofEpochMilli(n.note.updatedAt).atZone(ZoneId.systemDefault()).toLocalDate())
