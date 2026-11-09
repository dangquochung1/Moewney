package app.meowney.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.meowney.core.db.MeowneyDb
import app.meowney.core.db.NoteBlockEntity
import app.meowney.core.db.NoteEntity
import app.meowney.core.notes.MoodEngine
import app.meowney.core.notes.NoteImages
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoteRepositoryTest {
    private lateinit var db: MeowneyDb
    private lateinit var money: MoneyRepository
    private lateinit var images: NoteImages
    private lateinit var notes: NoteRepository
    private var now = 1_000_000_000_000L
    private val today = LocalDate.of(2026, 11, 10)

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, MeowneyDb::class.java).allowMainThreadQueries().build()
        money = MoneyRepository(db)
        images = NoteImages(ctx)
        images.dir.listFiles()?.forEach { it.delete() }
        notes = NoteRepository(db, images) { now }
    }

    @After fun tearDown() = db.close()

    private fun text(t: String) = NoteBlockEntity(noteId = 0, type = "TEXT", text = t)
    private fun check(t: String, done: Boolean = false) = NoteBlockEntity(noteId = 0, type = "CHECK", text = t, checked = done)
    private fun note(title: String) = NoteEntity(title = title, createdAt = 0, updatedAt = 0)
    private suspend fun coins() = money.coins.first()

    @Test fun searchIgnoresAccentsAndFindsTagsAndChecklists() = runBlocking<Unit> {
        notes.save(note("Đi Đà Lạt"), listOf(text("Nhớ mang áo"), check("Sạc dự phòng")), listOf("#du lịch"))
        notes.save(note("Chợ"), listOf(check("Trứng")), emptyList())
        assertThat(notes.search("da lat").first().map { it.note.title }).containsExactly("Đi Đà Lạt")
        assertThat(notes.search("SAC DU").first()).hasSize(1)
        assertThat(notes.search("du lich").first()).hasSize(1)
        assertThat(notes.search("trung").first().single().note.title).isEqualTo("Chợ")
        assertThat(notes.search("   ").first()).isEmpty()
    }

    @Test fun tagsFilterRenameAndMerge() = runBlocking<Unit> {
        val a = notes.save(note("A"), listOf(text("x")), listOf("Quà", "Gia đình")).id
        notes.save(note("B"), listOf(text("y")), listOf("quà")) // same tag, other case
        val tags = notes.tags.first()
        assertThat(tags.map { it.name }).containsExactly("Gia đình", "Quà")
        val qua = tags.single { it.name == "Quà" }
        assertThat(notes.cards(qua.id).first()).hasSize(2)
        val gd = tags.single { it.name == "Gia đình" }
        notes.renameTag(gd, "Quà") // merge
        assertThat(notes.tags.first().map { it.name }).containsExactly("Quà")
        assertThat(notes.full(a)!!.tags.map { it.name }).containsExactly("Quà")
        notes.renameTag(notes.tags.first().single(), "Tặng")
        assertThat(notes.search("tang").first()).hasSize(2)
    }

    @Test fun pinnedFirstAndChecklistToggle() = runBlocking<Unit> {
        val a = notes.save(note("Cũ"), listOf(text("x")), emptyList()).id
        now += 1000
        notes.save(note("Mới"), listOf(text("y")), emptyList())
        assertThat(notes.cards().first().map { it.note.title }).containsExactly("Mới", "Cũ").inOrder()
        notes.setPinned(a, true)
        assertThat(notes.cards().first().first().note.title).isEqualTo("Cũ")
        assertThat(notes.pinned.first().map { it.id }).containsExactly(a)
        val b = notes.save(note("Chợ"), listOf(check("Trứng"), check("Sữa")), emptyList()).id
        val egg = notes.full(b)!!.checks.first()
        notes.toggleCheck(egg.id)
        assertThat(notes.full(b)!!.checks.map { it.checked }).containsExactly(true, false).inOrder()
    }

    @Test fun diaryOnlyTodayCoinsOnceAndNeedsContent() = runBlocking<Unit> {
        assertThat(notes.diaryFor(today.minusDays(1), today)).isNull()
        val page = notes.diaryFor(today, today, prompt = "Hôm nay vui không?")!!
        assertThat(page.note.prompt).isEqualTo("Hôm nay vui không?")
        val before = coins()
        // mood but nothing written: no coins
        assertThat(notes.save(page.note.copy(mood = 4), emptyList(), emptyList(), today).reward).isNull()
        val r = notes.save(page.note.copy(mood = 4), listOf(text("Ăn bún chả")), emptyList(), today).reward!!
        assertThat(r.coins).isEqualTo(MoodEngine.DIARY_COINS)
        assertThat(r.streak).isEqualTo(1)
        assertThat(notes.save(page.note.copy(mood = 5), listOf(text("Ăn bún chả ngon")), emptyList(), today).reward).isNull()
        assertThat(coins()).isEqualTo(before + 3)
        // the same page is returned, not a second one
        assertThat(notes.diaryFor(today, today)!!.id).isEqualTo(page.id)
        // the next day the page can still be edited but pays nothing
        val tomorrow = today.plusDays(1)
        assertThat(notes.diaryFor(today, tomorrow)!!.mood!!.level).isEqualTo(5)
        assertThat(notes.save(page.note.copy(mood = 3), listOf(text("sửa")), emptyList(), tomorrow).reward).isNull()
    }

    @Test fun streakBonusAtSevenDays() = runBlocking<Unit> {
        var last: DiaryReward? = null
        for (i in 6 downTo 0) {
            val d = today.minusDays(i.toLong())
            val p = notes.diaryFor(d, d)!!
            last = notes.save(p.note.copy(mood = 3), listOf(text("ngày $i")), emptyList(), d).reward
        }
        assertThat(last!!.streak).isEqualTo(7)
        assertThat(last.bonus).isEqualTo(10)
        assertThat(coins()).isEqualTo(7 * 3 + 10)
        assertThat(notes.writtenDays.first()).hasSize(7)
    }

    @Test fun binRestoreAndPurgeAfter30DaysWithPhotos() = runBlocking<Unit> {
        images.file("aaaa-1.png").writeText("x")
        images.file("orphan-2.png").writeText("x")
        val id = notes.save(note("Ảnh"), listOf(NoteBlockEntity(noteId = 0, type = "IMAGE", image = "aaaa-1.png")), emptyList()).id
        notes.trash(id)
        assertThat(notes.cards().first()).isEmpty()
        assertThat(notes.bin.first().map { it.id }).containsExactly(id)
        notes.restore(id)
        assertThat(notes.cards().first()).hasSize(1)
        notes.trash(id)
        now += 29 * NoteRepository.DAY_MS
        notes.purge()
        assertThat(notes.full(id)).isNotNull()
        assertThat(images.file("aaaa-1.png").exists()).isTrue()
        assertThat(images.file("orphan-2.png").exists()).isFalse()
        now += 2 * NoteRepository.DAY_MS
        notes.purge()
        assertThat(notes.full(id)).isNull()
        assertThat(images.file("aaaa-1.png").exists()).isFalse()
    }

    @Test fun removingAPhotoBlockDeletesItsFile() = runBlocking<Unit> {
        images.file("bbbb-1.jpg").writeText("x")
        val n = notes.save(note("Ảnh"), listOf(NoteBlockEntity(noteId = 0, type = "IMAGE", image = "bbbb-1.jpg")), emptyList())
        notes.save(notes.full(n.id)!!.note, listOf(text("không ảnh nữa")), emptyList())
        assertThat(images.file("bbbb-1.jpg").exists()).isFalse()
    }

    @Test fun moodMonthStats() = runBlocking<Unit> {
        val month = YearMonth.from(today)
        for ((i, m) in listOf(5, 4, 1, 2, 3).withIndex()) {
            val d = month.atDay(i + 1)
            val p = notes.diaryFor(d, d)!!
            notes.save(p.note.copy(mood = m), listOf(text("x")), emptyList(), d)
        }
        val s = notes.moodMonth(month)
        assertThat(s.counts).containsExactly(1, 1, 1, 1, 1).inOrder()
        assertThat(s.summary).isEqualTo(app.meowney.core.notes.MoodSummary(written = 5, good = 2, ok = 1, bad = 2))
        assertThat(notes.moods.first()).hasSize(5)
    }
}
