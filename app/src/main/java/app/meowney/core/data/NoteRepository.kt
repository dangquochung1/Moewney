package app.meowney.core.data

import androidx.room.withTransaction
import app.meowney.core.db.CoinEntryEntity
import app.meowney.core.db.MeowneyDb
import app.meowney.core.db.NoteBlockEntity
import app.meowney.core.db.NoteEntity
import app.meowney.core.db.NoteTagEntity
import app.meowney.core.db.NoteTagRef
import app.meowney.core.notes.BlockType
import app.meowney.core.notes.Mood
import app.meowney.core.notes.MoodEngine
import app.meowney.core.notes.MoodMonth
import app.meowney.core.notes.MoodSummary
import app.meowney.core.notes.NoteImages
import app.meowney.core.notes.NoteKind
import app.meowney.core.notes.NoteText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.YearMonth

/** A note or diary page with its blocks and tags. */
data class NoteFull(val note: NoteEntity, val blocks: List<NoteBlockEntity>, val tags: List<NoteTagEntity>) {
    val id get() = note.id
    val diary get() = note.kind == NoteKind.DIARY.name
    val mood get() = Mood.of(note.mood)
    val day: LocalDate? get() = note.day?.let(LocalDate::ofEpochDay)
    val checks get() = blocks.filter { it.type == BlockType.CHECK.name }
    val firstImage get() = blocks.firstOrNull { it.image != null }?.image
    val hasContent get() = blocks.any { it.image != null || it.text.isNotBlank() }
    val isEmpty get() = note.title.isBlank() && !hasContent
}

/** What saving a diary page earned: coins for writing today, the streak and its bonus. */
data class DiaryReward(val coins: Int, val streak: Int, val bonus: Int)

data class NoteSaved(val id: Long, val reward: DiaryReward?)

class NoteRepository(
    private val db: MeowneyDb,
    private val images: NoteImages? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.notes()
    private val money = db.money()

    val tags: Flow<List<NoteTagEntity>> = dao.tags()

    private fun join(notes: Flow<List<NoteEntity>>): Flow<List<NoteFull>> =
        combine(notes, dao.blocks(), dao.refs(), dao.tags()) { ns, blocks, refs, tags ->
            val blocksOf = blocks.groupBy { it.noteId }
            val tagById = tags.associateBy { it.id }
            val tagsOf = refs.groupBy({ it.noteId }, { tagById[it.tagId] })
            ns.map { n -> NoteFull(n, blocksOf[n.id].orEmpty(), tagsOf[n.id].orEmpty().filterNotNull().sortedBy { it.name }) }
        }

    /** The notes grid: pinned first, then the latest edited; optionally only one tag. */
    fun cards(tagId: Long? = null): Flow<List<NoteFull>> =
        join(dao.notes()).map { all -> if (tagId == null) all else all.filter { n -> n.tags.any { it.id == tagId } } }

    val archived: Flow<List<NoteFull>> = join(dao.archived())
    val bin: Flow<List<NoteFull>> = join(dao.bin())
    val pinned: Flow<List<NoteFull>> = join(dao.pinned())
    val diaries: Flow<List<NoteFull>> = join(dao.diaries())

    /** Day → mood of every diary page that has one. */
    val moods: Flow<Map<Long, Int>> = dao.diaries().map { ps -> ps.filter { it.mood != null }.associate { it.day!! to it.mood!! } }

    /** Days the diary was written on the day itself (the ones that paid coins). */
    val writtenDays: Flow<Set<Long>> = dao.writtenDays().map { it.toSet() }

    fun search(query: String): Flow<List<NoteFull>> {
        val q = NoteText.fold(query)
        return if (q.isBlank()) flowOf(emptyList()) else join(dao.search(q))
    }

    fun fullFlow(id: Long): Flow<NoteFull?> = join(dao.noteFlow(id).map { listOfNotNull(it) }).map { it.firstOrNull() }

    suspend fun full(id: Long): NoteFull? {
        val n = dao.note(id) ?: return null
        return NoteFull(n, dao.blocksOf(id), dao.tagsOf(id))
    }

    /**
     * The diary page of [day]. Today's page is created if missing (or taken back out of the
     * bin); a past day without a page stays empty: no writing after the day is over.
     */
    suspend fun diaryFor(day: LocalDate, today: LocalDate = LocalDate.now(), prompt: String = ""): NoteFull? {
        val e = day.toEpochDay()
        val found = dao.diaryOn(e)
        if (found != null) {
            if (found.deletedAt != null) {
                if (day != today) return null
                dao.updateNote(found.copy(deletedAt = null))
            }
            return full(found.id)
        }
        if (day != today) return null
        val now = clock()
        val id = dao.insertNote(NoteEntity(kind = NoteKind.DIARY.name, day = e, prompt = prompt, createdAt = now, updatedAt = now))
        return full(id)
    }

    /**
     * Saves a note with all its blocks and tags (blocks are replaced). A diary page of [today]
     * with a mood and some content pays [MoodEngine.DIARY_COINS] once, plus streak bonuses.
     */
    suspend fun save(
        note: NoteEntity,
        blocks: List<NoteBlockEntity>,
        tagNames: List<String>,
        today: LocalDate = LocalDate.now(),
    ): NoteSaved {
        val oldImages = if (note.id == 0L) emptySet() else dao.blocksOf(note.id).mapNotNull { it.image }.toSet()
        val saved = db.withTransaction {
            val names = tagNames.map(NoteText::cleanTag).filter { it.isNotBlank() }.distinctBy { it.lowercase() }
            val existing = dao.tagsNow()
            val tags = names.map { name -> tagNamed(existing, name) ?: NoteTagEntity(dao.insertTag(NoteTagEntity(name = name)), name) }
            val n = note.copy(
                updatedAt = clock(),
                search = NoteText.searchText(note.title, blocks.map { it.text }, tags.map { it.name }),
            )
            val id = if (n.id == 0L) dao.insertNote(n.copy(createdAt = n.updatedAt)) else n.id.also { dao.updateNote(n) }
            dao.deleteBlocksOf(id)
            dao.insertBlocks(blocks.mapIndexed { i, b -> b.copy(id = 0, noteId = id, sort = i) })
            dao.deleteRefsOf(id)
            dao.insertRefs(tags.map { NoteTagRef(id, it.id) })
            val reward = if (n.kind == NoteKind.DIARY.name && n.day == today.toEpochDay()) reward(n, blocks) else null
            NoteSaved(id, reward)
        }
        val gone = oldImages - blocks.mapNotNull { it.image }.toSet()
        gone.forEach { images?.delete(it) }
        return saved
    }

    private suspend fun reward(n: NoteEntity, blocks: List<NoteBlockEntity>): DiaryReward? {
        val day = n.day!!
        val written = n.mood != null && blocks.any { it.image != null || it.text.isNotBlank() }
        if (!written || dao.coinCount(R_DIARY, day) > 0) return null
        val streak = MoodEngine.streak(dao.writtenDaysNow().toSet() + day, day)
        val bonus = MoodEngine.STREAK_BONUS[streak] ?: 0
        money.insertCoins(
            listOfNotNull(
                CoinEntryEntity(delta = MoodEngine.DIARY_COINS, reason = R_DIARY, refId = day, day = day),
                if (bonus > 0) CoinEntryEntity(delta = bonus, reason = R_DIARY_STREAK, refId = day, day = day) else null,
            ),
        )
        return DiaryReward(MoodEngine.DIARY_COINS, streak, bonus)
    }

    /** Ticks a checklist line without moving the note in the grid. */
    suspend fun toggleCheck(blockId: Long) {
        val b = dao.block(blockId) ?: return
        dao.updateBlock(b.copy(checked = !b.checked))
    }

    suspend fun setPinned(id: Long, pinned: Boolean) = update(id) { it.copy(pinned = pinned) }
    suspend fun setArchived(id: Long, archived: Boolean) = update(id) { it.copy(archived = archived, pinned = if (archived) false else it.pinned) }
    suspend fun trash(id: Long) = update(id) { it.copy(deletedAt = clock()) }
    suspend fun restore(id: Long) = update(id) { it.copy(deletedAt = null) }

    private suspend fun update(id: Long, f: (NoteEntity) -> NoteEntity) {
        dao.note(id)?.let { dao.updateNote(f(it)) }
    }

    suspend fun deleteForever(id: Long) {
        val files = dao.blocksOf(id).mapNotNull { it.image }
        dao.deleteNote(id)
        files.forEach { images?.delete(it) }
    }

    suspend fun emptyBin(ids: List<Long>) = ids.forEach { deleteForever(it) }

    /** Bin older than 30 days goes for good; photo files nothing points to are removed. */
    suspend fun purge() {
        dao.expired(clock() - BIN_DAYS * DAY_MS).forEach { deleteForever(it.id) }
        images?.sweep(dao.allImages().toSet())
    }

    // ------------------------------------------------------------------ tags

    suspend fun renameTag(tag: NoteTagEntity, name: String) {
        val clean = NoteText.cleanTag(name)
        if (clean.isBlank()) return
        val other = tagNamed(dao.tagsNow(), clean)
        db.withTransaction {
            if (other != null && other.id != tag.id) {
                // merge into the existing tag
                val notes = dao.refsNow().filter { it.tagId == tag.id }.map { NoteTagRef(it.noteId, other.id) }
                dao.insertRefs(notes)
                dao.deleteTag(tag.id)
            } else {
                dao.updateTag(tag.copy(name = clean))
            }
        }
        reindex()
    }

    /** Tags match without caring about case (SQLite only folds ASCII). */
    private fun tagNamed(all: List<NoteTagEntity>, name: String) = all.firstOrNull { it.name.lowercase() == name.lowercase() }

    suspend fun deleteTag(id: Long) {
        dao.deleteTag(id)
        reindex()
    }

    private suspend fun reindex() {
        for (n in dao.allNotes()) {
            val s = NoteText.searchText(n.title, dao.blocksOf(n.id).map { it.text }, dao.tagsOf(n.id).map { it.name })
            if (s != n.search) dao.updateNote(n.copy(search = s))
        }
    }

    // ------------------------------------------------------------------ moods

    suspend fun moodMonth(month: YearMonth): MoodMonth {
        val from = month.atDay(1).toEpochDay()
        val to = month.atEndOfMonth().toEpochDay()
        val moods = dao.diariesNow(from, to).filter { it.mood != null }.associate { it.day!! to it.mood!! }
        val spent = dao.spentByDay(from, to).associate { it.day to it.total }
        val habits = dao.habitRatios(from, to).associate { it.day to (it.done to it.total) }
        return MoodEngine.month(moods, from, to, spent, habits)
    }

    suspend fun moodSummary(month: YearMonth): MoodSummary? = moodMonth(month).summary.takeIf { it.written > 0 }

    companion object {
        const val R_DIARY = "diary"
        const val R_DIARY_STREAK = "diary_streak"
        const val BIN_DAYS = 30L
        const val DAY_MS = 86_400_000L
    }
}
