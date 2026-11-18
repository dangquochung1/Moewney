package app.meowney.core.notify

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NudgeEngineTest {
    private val nag = "Sen ơi, hôm nay chưa ghi sổ."

    @Test fun eachToneHasItsOwnTitle() {
        val titles = CatTone.entries.map { NudgeEngine.title(it, "Mít") }
        assertThat(titles.toSet()).hasSize(CatTone.entries.size)
        assertThat(titles.all { it.startsWith("Mít ") }).isTrue()
        assertThat(NudgeEngine.title(CatTone.ANGRY, "Bông")).isEqualTo("Bông đang tức giận")
    }

    @Test fun overdueWorkMakesTheCatAngryOnceADay() {
        val i = EveningInputs(undone = listOf("Gửi mail"), overdue = listOf("Mua sách"), logged = true)
        val angry = NudgeEngine.evening(i, nag)!!
        assertThat(angry.tone).isEqualTo(CatTone.ANGRY)
        assertThat(angry.text).contains("Còn 2 việc chưa làm")
        assertThat(angry.text).contains("\"Mua sách\" đã quá hạn")
        // already angry today: the same facts come as a plain reminder
        assertThat(NudgeEngine.evening(i.copy(angryToday = true), nag)!!.tone).isEqualTo(CatTone.REMIND)
    }

    @Test fun threeOpenTodosAreEnoughToBeAngry() {
        val two = EveningInputs(undone = listOf("A", "B"), logged = true)
        assertThat(NudgeEngine.evening(two, nag)!!.tone).isEqualTo(CatTone.REMIND)
        val three = two.copy(undone = listOf("A", "B", "C"))
        assertThat(NudgeEngine.evening(three, nag)!!.tone).isEqualTo(CatTone.ANGRY)
    }

    @Test fun remindersListWhatIsLeft() {
        val n = NudgeEngine.evening(EveningInputs(undone = listOf("Giặt đồ"), habitsOpen = listOf("Đọc sách", "Uống nước")), nag)!!
        assertThat(n.tone).isEqualTo(CatTone.REMIND)
        assertThat(n.text).contains("Còn 1 việc chưa làm: \"Giặt đồ\".")
        assertThat(n.text).contains("Còn 2 thói quen chưa xong")
        assertThat(n.text).endsWith(nag) // nothing logged today
        // logged already: no nagging about the book
        assertThat(NudgeEngine.evening(EveningInputs(undone = listOf("Giặt đồ"), logged = true), nag)!!.text).doesNotContain(nag)
    }

    @Test fun aFinishedDayMakesTheCatHappyAndAnEmptyOneSaysNothing() {
        val happy = NudgeEngine.evening(EveningInputs(todosDone = 5, habitsDone = 3, logged = true), nag)!!
        assertThat(happy.tone).isEqualTo(CatTone.HAPPY)
        assertThat(happy.text).contains("5 việc và 3 thói quen")
        assertThat(NudgeEngine.evening(EveningInputs(logged = true), nag)).isNull()
        // nothing planned and nothing logged: only the reminder to write the book
        assertThat(NudgeEngine.evening(EveningInputs(), nag)!!.text).isEqualTo(nag)
    }

    @Test fun longListsAreCut() {
        assertThat(NudgeEngine.names(listOf("A", "B", "C", "D", "E"), "việc")).isEqualTo("\"A\", \"B\", \"C\" và 2 việc nữa")
        assertThat(NudgeEngine.names(listOf("A"), "việc")).isEqualTo("\"A\"")
    }

    @Test fun morningSplitsAgendaWorriesAndAbsence() {
        val all = NudgeEngine.morning(MorningInputs(agenda = listOf("Nộp bài (14:00)", "Mua sách"), due = listOf("Tiền điện 350k"), negativeJars = listOf("Hưởng thụ"), daysAway = 2))
        assertThat(all.map { it.tone }).containsExactly(CatTone.MORNING, CatTone.WORRIED, CatTone.SAD).inOrder()
        assertThat(all[0].text).startsWith("Hôm nay có 2 việc")
        assertThat(all[1].text).contains("Tiền điện 350k đến hạn hôm nay.")
        assertThat(all[1].text).contains("Lọ Hưởng thụ đang âm.")
        assertThat(all[2].text).startsWith("Hai hôm rồi")
        assertThat(NudgeEngine.morning(MorningInputs(daysAway = 1))).isEmpty()
        assertThat(NudgeEngine.morning(MorningInputs(daysAway = 5)).single().text).startsWith("5 hôm rồi")
    }
}
