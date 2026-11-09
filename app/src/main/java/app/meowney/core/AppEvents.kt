package app.meowney.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Something the cat should react to the next time the home screen is shown. */
sealed interface CatEvent {
    data class Fed(val label: String, val amount: Long, val coins: Int) : CatEvent
    data class Poured(val amount: Long, val coins: Int, val notes: List<String> = emptyList()) : CatEvent
    data class Coins(val coins: Int, val text: String) : CatEvent
    data class Say(val text: String) : CatEvent
    /** Sen picked a diary mood (1..5): the cat cheers or lies down beside them. */
    data class Mood(val level: Int, val text: String) : CatEvent
    /** Sen gave a treat from the wardrobe: the cat eats. */
    data class Treat(val name: String) : CatEvent
}

/**
 * Holds the latest cat event until the home screen consumes it. A StateFlow (not a plain
 * event stream) because home is not composed while the entry screen is open.
 */
class AppEvents {
    private val _pending = MutableStateFlow<CatEvent?>(null)
    val pending: StateFlow<CatEvent?> = _pending

    fun post(event: CatEvent) {
        _pending.value = event
    }

    fun consume() {
        _pending.value = null
    }
}
