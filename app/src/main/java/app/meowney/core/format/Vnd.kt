package app.meowney.core.format

/** 152000 -> "152.000đ", -45000 -> "-45.000đ". Amounts are whole đồng. */
fun formatVnd(amount: Long, suffix: String = "đ"): String {
    val digits = kotlin.math.abs(amount).toString()
    val grouped = buildString {
        digits.forEachIndexed { i, c ->
            if (i > 0 && (digits.length - i) % 3 == 0) append('.')
            append(c)
        }
    }
    return (if (amount < 0) "-" else "") + grouped + suffix
}

/** Short form for tight places: 152000 -> "152k", 3200000 -> "3,2tr". */
fun formatVndShort(amount: Long): String {
    val a = kotlin.math.abs(amount)
    val sign = if (amount < 0) "-" else ""
    return when {
        a >= 1_000_000 -> {
            val tenths = (a + 50_000) / 100_000
            sign + (tenths / 10) + (if (tenths % 10 != 0L) ",${tenths % 10}" else "") + "tr"
        }
        a >= 1_000 -> sign + (a / 1000) + "k"
        else -> sign + a + "đ"
    }
}
