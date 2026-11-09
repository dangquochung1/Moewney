package app.meowney.feature.notes

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.meowney.container
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelScreen
import app.meowney.core.ui.PixelText
import app.meowney.core.ui.PixelTextButton
import app.meowney.feature.money.SpriteView

private const val AUTH = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

/** The phone has a fingerprint or a screen lock the notebook can ask for. */
fun canLockNotes(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS ||
        context.getSystemService(KeyguardManager::class.java).isDeviceSecure

private tailrec fun Context.fragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.fragmentActivity()
    else -> null
}

/** Asks for the fingerprint or the phone's PIN / pattern; [onOk] when it matches. */
fun askToUnlock(context: Context, onOk: () -> Unit, onFail: (String) -> Unit) {
    val activity = context.fragmentActivity() ?: return onFail("Không mở được khoá.")
    val prompt = BiometricPrompt(
        activity, ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_CANCELED) onFail(errString.toString())
            }
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Mở sổ tay")
        .setSubtitle("Dùng vân tay hoặc mã khoá máy")
        .setAllowedAuthenticators(AUTH)
        .build()
    runCatching { prompt.authenticate(info) }.onFailure { onFail("Máy chưa cài khoá màn hình.") }
}

/**
 * Shows [content] only when the notebook lock is off or open. Otherwise a sleeping cat and an
 * unlock button (the prompt also pops up by itself once).
 */
@Composable
fun NotesGate(onBack: () -> Unit, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val settings by c.settings.flow.collectAsState(initial = null)
    val unlocked by c.notesLock.unlocked.collectAsState()
    val s = settings ?: return
    if (!s.notesLock || unlocked) {
        content()
        return
    }
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    var error by remember { mutableStateOf<String?>(null) }
    val ask = { askToUnlock(ctx, onOk = { c.notesLock.unlock() }, onFail = { error = it }) }
    LaunchedEffect(Unit) { ask() }
    PixelScreen("Sổ tay", onBack = onBack) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(m.unit * 4, Alignment.CenterVertically),
        ) {
            SpriteView("cat/${s.catColor}/sleep1_r", scale = m.ui + 1)
            PixelText("Sổ tay đang khoá. Trẫm canh giùm Sen rồi.", style = type.body, color = Pal.Cream, align = TextAlign.Center)
            error?.let { PixelText(it, style = type.caption, color = Pal.Red, align = TextAlign.Center) }
            PixelTextButton("Mở khoá", onClick = ask, modifier = Modifier.fillMaxWidth())
        }
    }
}
