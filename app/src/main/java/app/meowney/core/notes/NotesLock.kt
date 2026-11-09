package app.meowney.core.notes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the notebook is open. Unlocking lasts while Sen stays in the app; leaving it for more
 * than [GRACE_MS] locks it again. Trips the app makes itself (photo picker, camera) do not count.
 */
class NotesLock(private val clock: () -> Long = System::currentTimeMillis) {
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked
    private var leftAt: Long? = null
    private var ownTrip = false

    fun unlock() {
        _unlocked.value = true
    }

    fun lock() {
        _unlocked.value = false
    }

    /** About to open another app on purpose (gallery, camera): the time away does not lock. */
    fun ownTrip() {
        ownTrip = true
    }

    fun onAppHidden() {
        leftAt = clock()
    }

    fun onAppShown() {
        val left = leftAt
        leftAt = null
        if (ownTrip) {
            ownTrip = false
            return
        }
        if (left != null && clock() - left > GRACE_MS) _unlocked.value = false
    }

    companion object {
        const val GRACE_MS = 60_000L
    }
}
