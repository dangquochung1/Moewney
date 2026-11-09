package app.meowney.core.notes

import java.text.Normalizer

enum class BlockType { TEXT, CHECK, IMAGE }

enum class NoteKind { NOTE, DIARY }

/** The five moods of a diary page, worst first. [emote] is a sprite under ui/emote. */
enum class Mood(val level: Int, val label: String, val emote: String) {
    AWFUL(1, "Rất tệ", "ui/emote/emote15"),
    BAD(2, "Tệ", "ui/emote/emote13"),
    OK(3, "Bình thường", "ui/emote/emote5"),
    GOOD(4, "Vui", "ui/emote/emote11"),
    GREAT(5, "Rất vui", "ui/emote/emote6");

    val good get() = level >= 4
    val bad get() = level <= 2

    companion object {
        fun of(level: Int?): Mood? = entries.firstOrNull { it.level == level }
    }
}

object NoteText {
    private val marks = "\\p{Mn}+".toRegex()
    private val spaces = "\\s+".toRegex()

    /** Lower case without Vietnamese accents: "Đà Lạt" → "da lat". */
    fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "")
            .replace('đ', 'd').replace('Đ', 'D')
            .lowercase()
            .replace(spaces, " ")
            .trim()

    /** What a search looks through: title, every block's text and the tag names. */
    fun searchText(title: String, texts: List<String>, tags: List<String>): String =
        fold((listOf(title) + texts + tags).filter { it.isNotBlank() }.joinToString(" "))

    /** Tag names are kept short, without '#', spaces collapsed. */
    fun cleanTag(s: String): String = s.trim().removePrefix("#").trim().replace(spaces, " ").take(24)
}
