package app.meowney.feature.settings

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.meowney.AppContainer
import app.meowney.core.backup.BackupManager
import app.meowney.core.backup.CsvExporter
import app.meowney.core.data.Settings
import app.meowney.core.notify.EveningReminder
import app.meowney.core.ui.ButtonLook
import app.meowney.container
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelChip
import app.meowney.core.ui.PixelDialog
import app.meowney.core.ui.PixelPanel
import app.meowney.core.ui.PixelScreen
import app.meowney.core.ui.PixelText
import app.meowney.core.ui.PixelTextButton
import app.meowney.core.ui.PixelTextField
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class SettingsViewModel(private val c: AppContainer, private val context: Context) : ViewModel() {
    val settings = c.settings.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())
    private val backup = BackupManager(context, c)

    fun update(f: (Settings) -> Settings) = viewModelScope.launch {
        c.settings.update(f)
        val s = c.settings.now()
        c.sfx.enabled = s.soundOn
        c.music.enabled = s.musicOn
        EveningReminder.schedule(context, s.reminderMinute, s.reminderOn)
        app.meowney.core.notify.CalendarReminder.schedule(context)
    }

    suspend fun export(uri: Uri): String = runCatching {
        backup.export(uri)
        c.settings.markBackup(LocalDate.now().toEpochDay())
        "Sao lưu xong. Cất file kỹ nha Sen."
    }.getOrElse { "Sao lưu lỗi: ${it.message}" }

    suspend fun restore(uri: Uri): String = runCatching {
        backup.restore(uri)
        "Khôi phục xong, đang mở lại app..."
    }.getOrElse { it.message ?: "Khôi phục lỗi." }

    suspend fun csv(uri: Uri): String = runCatching {
        CsvExporter.write(context, uri, c.money.exportRows())
        "Xuất CSV xong."
    }.getOrElse { "Xuất CSV lỗi: ${it.message}" }
}

private fun isXiaomi() = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit, onXiaomi: () -> Unit, onCredits: () -> Unit, onUiKit: () -> Unit) {
    val s by vm.settings.collectAsState()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var confirmRestore by remember { mutableStateOf<Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch { message = vm.export(uri) }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmRestore = uri
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch { message = vm.csv(uri) }
    }

    PixelScreen("Cài đặt", onBack = onBack) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(m.unit * 3)) {
            message?.let { PixelText(it, style = type.bodyBold, color = Pal.Gold) }

            Section("Mèo") {
                PixelTextField(s.catName, { n -> vm.update { it.copy(catName = n.take(16)) } }, Modifier.fillMaxWidth())
            }
            Section("Dữ liệu") {
                PixelText("Dữ liệu chỉ nằm trên máy này. Sao lưu định kỳ rồi cất file lên Drive/Zalo.", style = type.caption, color = Pal.InkSoft)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit * 2), verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                    PixelTextButton("Sao lưu", onClick = { exportLauncher.launch(BackupManager.fileName("meowney", "zip")) })
                    PixelTextButton("Khôi phục", onClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, look = ButtonLook.Light)
                    PixelTextButton("Xuất CSV", onClick = { csvLauncher.launch(BackupManager.fileName("meowney", "csv")) }, look = ButtonLook.Light)
                }
            }
            Section("Nhắc ghi sổ") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                    PixelChip(if (s.reminderOn) "Đang bật" else "Đang tắt", selected = s.reminderOn) { vm.update { it.copy(reminderOn = !it.reminderOn) } }
                    PixelTextButton("-", onClick = { vm.update { it.copy(reminderMinute = (it.reminderMinute - 15).mod(24 * 60)) } }, look = ButtonLook.Light)
                    PixelText("%02d:%02d".format(s.reminderMinute / 60, s.reminderMinute % 60), style = type.title, color = Pal.Ink)
                    PixelTextButton("+", onClick = { vm.update { it.copy(reminderMinute = (it.reminderMinute + 15).mod(24 * 60)) } }, look = ButtonLook.Light)
                }
                PixelText("Chỉ nhắc khi hôm nay chưa ghi khoản nào.", style = type.caption, color = Pal.InkSoft)
            }
            Section("Nhắc lịch") {
                PixelChip(if (s.calendarRemindOn) "Đang bật" else "Đang tắt", selected = s.calendarRemindOn) {
                    vm.update { it.copy(calendarRemindOn = !it.calendarRemindOn) }
                }
                PixelText("Sự kiện có giờ nhắc đúng phút; cả ngày và việc trong thông báo 8:00.", style = type.caption, color = Pal.InkSoft)
            }
            Section("Sổ tay") {
                NotesPrivacy(s.notesLock, s.diaryHidePreview, vm)
            }
            Section("Ngày lương") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                    PixelChip("Cuối tháng", selected = s.payday == 0) { vm.update { it.copy(payday = 0) } }
                    PixelTextButton("-", onClick = { vm.update { it.copy(payday = if (it.payday <= 2) 0 else it.payday - 1) } }, look = ButtonLook.Light)
                    PixelText(if (s.payday == 0) "--" else "ngày ${s.payday}", style = type.bodyBold, color = Pal.Ink)
                    PixelTextButton("+", onClick = { vm.update { it.copy(payday = if (it.payday == 0) 2 else (it.payday + 1).coerceAtMost(28)) } }, look = ButtonLook.Light)
                }
            }
            Section("Âm thanh") {
                Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                    PixelChip(if (s.soundOn) "Hiệu ứng: bật" else "Hiệu ứng: tắt", selected = s.soundOn) { vm.update { it.copy(soundOn = !it.soundOn) } }
                    PixelChip(if (s.musicOn) "Nhạc nền: bật" else "Nhạc nền: tắt", selected = s.musicOn) { vm.update { it.copy(musicOn = !it.musicOn) } }
                }
                PixelText("Nhạc chỉ phát ở màn chính, đổi bài theo mùa.", style = type.caption, color = Pal.InkSoft)
            }
            Section("Khác") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit * 2), verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                    PixelTextButton(if (isXiaomi()) "Hướng dẫn Xiaomi" else "Giữ nhắc nhở chạy", onClick = onXiaomi, look = ButtonLook.Light)
                    PixelTextButton("Ghi công", onClick = onCredits, look = ButtonLook.Light)
                    PixelTextButton("Xưởng UI", onClick = onUiKit, look = ButtonLook.Light)
                }
            }
        }
    }

    confirmRestore?.let { uri ->
        PixelDialog(onDismiss = { confirmRestore = null }) {
            PixelText("Khôi phục?", style = type.title)
            PixelText("Toàn bộ dữ liệu hiện tại sẽ bị thay bằng bản sao lưu. Không hoàn tác được.", style = type.body)
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                PixelTextButton("Khôi phục", onClick = {
                    confirmRestore = null
                    scope.launch { message = vm.restore(uri) }
                })
                PixelTextButton("Huỷ", onClick = { confirmRestore = null }, look = ButtonLook.Light)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val m = LocalPixelMetrics.current
    PixelPanel(Modifier.fillMaxWidth(), sprite = "ui/wood/nine_path_panel_3") {
        Column(verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            PixelText(title, style = LocalPixelType.current.title, color = Pal.Ink)
            content()
        }
    }
}

// ------------------------------------------------------------------ Xiaomi / background guide

private fun open(context: Context, vararg intents: Intent): Boolean {
    for (i in intents) {
        try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    return false
}

private fun appDetails(context: Context) =
    Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

@Composable
fun XiaomiGuideScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    PixelScreen(if (isXiaomi()) "Hướng dẫn Xiaomi" else "Giữ nhắc nhở chạy", onBack = onBack) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(m.unit * 3)) {
            PixelText(
                "Máy Xiaomi/Redmi/POCO hay tắt app chạy nền nên lời nhắc 21h có thể không kêu. Bật 2 mục dưới đây là được.",
                style = type.body, color = Pal.Ink,
            )
            PixelPanel(Modifier.fillMaxWidth(), sprite = "ui/wood/nine_path_panel_3") {
                Column(verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                    PixelText("1. Tự khởi chạy", style = type.title)
                    PixelText("Bật công tắc cạnh Meowney trong danh sách.", style = type.body)
                    PixelTextButton("Mở Tự khởi chạy", onClick = {
                        open(
                            context,
                            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                            appDetails(context),
                        )
                    })
                }
            }
            PixelPanel(Modifier.fillMaxWidth(), sprite = "ui/wood/nine_path_panel_3") {
                Column(verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                    PixelText("2. Pin: Không giới hạn", style = type.title)
                    PixelText("Chọn 'Không giới hạn' (No restrictions) cho Meowney.", style = type.body)
                    PixelTextButton("Mở cài đặt pin", onClick = {
                        open(
                            context,
                            Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                            appDetails(context),
                        )
                    })
                }
            }
            PixelText("Không mở được thì vào Cài đặt > Ứng dụng > Meowney rồi bật thủ công.", style = type.caption, color = Pal.InkSoft)
        }
    }
}

// ------------------------------------------------------------------ credits

private val CREDITS = listOf(
    "Font Pixeloid" to "GGBotNet - SIL Open Font License 1.1 (bản sửa thêm chữ Việt: Meowney Pixel)",
    "Ninja Adventure (UI gỗ, nhiều sprite)" to "Pixel-boy & AAA - CC0",
    "Cozy Cat Pixel Art Pack (ổ, bát, len, thùng)" to "FOTOPIXY - dùng tự do, cấm bán lại asset",
    "Sunnyside World" to "Daniel Diggle",
    "Raven Fantasy Icons" to "clockworkraven",
    "Mèo 6 màu, nội thất nhà, tháp mèo, nhà thú cưng, đồ chơi" to "pack miễn phí - chưa rõ tác giả",
    "Âm thanh hiệu ứng" to "từ kho asset của game cũ",
)

@Composable
fun CreditsScreen(onBack: () -> Unit) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    PixelScreen("Ghi công", onBack = onBack) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            PixelText("Meowney dùng asset của các tác giả sau. Cảm ơn rất nhiều!", style = type.body, color = Pal.Ink)
            for ((what, who) in CREDITS) {
                PixelPanel(Modifier.fillMaxWidth(), sprite = "ui/wood/nine_path_panel_3") {
                    Column {
                        PixelText(what, style = type.bodyBold)
                        PixelText(who, style = type.caption, color = Pal.InkSoft)
                    }
                }
            }
        }
    }
}

@Composable
private fun NotesPrivacy(lockOn: Boolean, hidePreview: Boolean, vm: SettingsViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val type = LocalPixelType.current
    val m = LocalPixelMetrics.current
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
        PixelChip(if (lockOn) "Khoá vân tay: bật" else "Khoá vân tay: tắt", selected = lockOn) {
            error = null
            if (!lockOn && !app.meowney.feature.notes.canLockNotes(ctx)) {
                error = "Máy chưa cài vân tay hay mã khoá màn hình."
            } else {
                // turning it on or off both need the owner's finger or PIN
                app.meowney.feature.notes.askToUnlock(ctx, onOk = {
                    ctx.container.notesLock.unlock()
                    vm.update { it.copy(notesLock = !lockOn) }
                }, onFail = { error = it })
            }
        }
        PixelText("Mở Sổ tay phải dùng vân tay hoặc mã khoá máy. Rời app quá 1 phút thì khoá lại.", style = type.caption, color = Pal.InkSoft)
        error?.let { PixelText(it, style = type.caption, color = Pal.Red) }
        PixelChip(if (hidePreview) "Che xem trước nhật ký: bật" else "Che xem trước nhật ký: tắt", selected = hidePreview) {
            vm.update { it.copy(diaryHidePreview = !hidePreview) }
        }
        PixelText("Lịch nhật ký và kết quả tìm chỉ hiện ngày và tâm trạng, chạm vào mới đọc.", style = type.caption, color = Pal.InkSoft)
    }
}
