package app.meowney.core.money

/** Keys of the pixel keypad. */
sealed interface AmountKey {
    data class Digit(val d: Int) : AmountKey
    data object Triple0 : AmountKey
    data object Thousand : AmountKey   // "k"
    data object Million : AmountKey    // "tr"
    data object Back : AmountKey
    data object Clear : AmountKey
}

/**
 * Amount typed on the keypad, in whole đồng. "25" then "k" = 25.000; "2" then "tr" =
 * 2.000.000. Never more than [MAX] (12 digits).
 */
@JvmInline
value class AmountInput(val value: Long = 0) {
    fun press(key: AmountKey): AmountInput = when (key) {
        is AmountKey.Digit -> safe(value * 10 + key.d)
        AmountKey.Triple0 -> safe(value * 1000)
        AmountKey.Thousand -> safe(value * 1000)
        AmountKey.Million -> safe(value * 1_000_000)
        AmountKey.Back -> AmountInput(value / 10)
        AmountKey.Clear -> AmountInput(0)
    }

    private fun safe(next: Long) = if (next in 0..MAX) AmountInput(next) else this

    companion object {
        const val MAX = 999_999_999_999L
    }
}
