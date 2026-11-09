package app.meowney.core.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * A page of the notebook: a free note (kind NOTE) or the diary page of one day (kind DIARY,
 * one per [day]). The body is a list of [NoteBlockEntity]. [search] is the accent-free text of
 * title, blocks and tags (core/notes/NoteText.kt), rebuilt on every save.
 */
@Entity(tableName = "note", indices = [Index("kind"), Index(value = ["kind", "day"], unique = true)])
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** NOTE / DIARY */
    val kind: String = "NOTE",
    val title: String = "",
    /** Diary: LocalDate.toEpochDay(). Null for notes. */
    val day: Long? = null,
    /** Diary: 1 (rất tệ) .. 5 (rất vui). */
    val mood: Int? = null,
    /** Diary: the cat's question shown on the page. */
    val prompt: String = "",
    /** Paper colour, index into NOTE_PAPERS. */
    val color: Int = 0,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    /** Set when moved to the bin; purged 30 days later. */
    val deletedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val search: String = "",
)

@Entity(
    tableName = "note_block",
    foreignKeys = [ForeignKey(NoteEntity::class, ["id"], ["noteId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("noteId")],
)
data class NoteBlockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val sort: Int = 0,
    /** TEXT / CHECK / IMAGE */
    val type: String = "TEXT",
    val text: String = "",
    val checked: Boolean = false,
    /** IMAGE: file name inside filesDir/notes. */
    val image: String? = null,
)

@Entity(tableName = "note_tag", indices = [Index(value = ["name"], unique = true)])
data class NoteTagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Entity(
    tableName = "note_tag_ref",
    primaryKeys = ["noteId", "tagId"],
    foreignKeys = [
        ForeignKey(NoteEntity::class, ["id"], ["noteId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(NoteTagEntity::class, ["id"], ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tagId")],
)
data class NoteTagRef(val noteId: Long, val tagId: Long)

data class HabitDayRatio(val day: Long, val done: Int, val total: Int)

@Dao
interface NoteDao {
    @Query("SELECT * FROM note WHERE kind = 'NOTE' AND deletedAt IS NULL AND archived = 0 ORDER BY pinned DESC, updatedAt DESC")
    fun notes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM note WHERE kind = 'NOTE' AND deletedAt IS NULL AND archived = 1 ORDER BY updatedAt DESC")
    fun archived(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM note WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun bin(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM note WHERE kind = 'NOTE' AND pinned = 1 AND deletedAt IS NULL AND archived = 0 ORDER BY updatedAt DESC")
    fun pinned(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM note WHERE kind = 'DIARY' AND deletedAt IS NULL ORDER BY day")
    fun diaries(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM note WHERE kind = 'DIARY' AND deletedAt IS NULL AND day BETWEEN :from AND :to ORDER BY day")
    suspend fun diariesNow(from: Long, to: Long): List<NoteEntity>

    @Query("SELECT * FROM note WHERE deletedAt IS NULL AND search LIKE '%' || :q || '%' ORDER BY updatedAt DESC")
    fun search(q: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM note WHERE id = :id")
    suspend fun note(id: Long): NoteEntity?

    @Query("SELECT * FROM note WHERE id = :id")
    fun noteFlow(id: Long): Flow<NoteEntity?>

    @Query("SELECT * FROM note WHERE kind = 'DIARY' AND day = :day")
    suspend fun diaryOn(day: Long): NoteEntity?

    @Query("SELECT * FROM note WHERE deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun expired(before: Long): List<NoteEntity>

    @Insert suspend fun insertNote(n: NoteEntity): Long
    @Update suspend fun updateNote(n: NoteEntity)

    @Query("DELETE FROM note WHERE id = :id")
    suspend fun deleteNote(id: Long)

    // ------------------------------------------------------------------ blocks

    @Query("SELECT * FROM note_block ORDER BY noteId, sort, id")
    fun blocks(): Flow<List<NoteBlockEntity>>

    @Query("SELECT * FROM note_block WHERE noteId = :noteId ORDER BY sort, id")
    suspend fun blocksOf(noteId: Long): List<NoteBlockEntity>

    @Query("SELECT * FROM note_block WHERE noteId = :noteId ORDER BY sort, id")
    fun blocksFlow(noteId: Long): Flow<List<NoteBlockEntity>>

    @Query("SELECT * FROM note_block WHERE id = :id")
    suspend fun block(id: Long): NoteBlockEntity?

    @Query("SELECT image FROM note_block WHERE image IS NOT NULL")
    suspend fun allImages(): List<String>

    @Insert suspend fun insertBlocks(b: List<NoteBlockEntity>)
    @Update suspend fun updateBlock(b: NoteBlockEntity)

    @Query("DELETE FROM note_block WHERE noteId = :noteId")
    suspend fun deleteBlocksOf(noteId: Long)

    // ------------------------------------------------------------------ tags

    @Query("SELECT * FROM note_tag ORDER BY name")
    fun tags(): Flow<List<NoteTagEntity>>

    @Query("SELECT * FROM note_tag")
    suspend fun tagsNow(): List<NoteTagEntity>

    @Insert suspend fun insertTag(t: NoteTagEntity): Long
    @Update suspend fun updateTag(t: NoteTagEntity)

    @Query("DELETE FROM note_tag WHERE id = :id")
    suspend fun deleteTag(id: Long)

    @Query("SELECT * FROM note_tag_ref")
    fun refs(): Flow<List<NoteTagRef>>

    @Query("SELECT * FROM note_tag_ref")
    suspend fun refsNow(): List<NoteTagRef>

    @Query("SELECT * FROM note")
    suspend fun allNotes(): List<NoteEntity>

    @Query("SELECT t.* FROM note_tag t JOIN note_tag_ref r ON r.tagId = t.id WHERE r.noteId = :noteId ORDER BY t.name")
    suspend fun tagsOf(noteId: Long): List<NoteTagEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertRefs(r: List<NoteTagRef>)

    @Query("DELETE FROM note_tag_ref WHERE noteId = :noteId")
    suspend fun deleteRefsOf(noteId: Long)

    // ------------------------------------------------------------------ diary coins & stats

    @Query("SELECT day FROM coin_entry WHERE reason = 'diary'")
    fun writtenDays(): Flow<List<Long>>

    @Query("SELECT day FROM coin_entry WHERE reason = 'diary'")
    suspend fun writtenDaysNow(): List<Long>

    @Query("SELECT COUNT(*) FROM coin_entry WHERE reason = :reason AND refId = :refId")
    suspend fun coinCount(reason: String, refId: Long): Int

    @Query(
        "SELECT day, SUM(CASE WHEN status IN ('DONE', 'KEPT') THEN 1 ELSE 0 END) AS done, " +
            "SUM(CASE WHEN status IN ('DONE', 'KEPT', 'FAILED', 'MISSED') THEN 1 ELSE 0 END) AS total " +
            "FROM habit_log WHERE day BETWEEN :from AND :to GROUP BY day",
    )
    suspend fun habitRatios(from: Long, to: Long): List<HabitDayRatio>

    @Query("SELECT day, SUM(amount) AS total FROM txn WHERE type = 'EXPENSE' AND day BETWEEN :from AND :to GROUP BY day")
    suspend fun spentByDay(from: Long, to: Long): List<DaySum>
}
