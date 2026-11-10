package app.meowney.feature.room

import app.meowney.core.data.CloseSummary
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CatLinesTest {
    @Test fun daysAwayDoNotRepeatTheSameNote() {
        val note = "Khong tra sua: chuyển 30k rồi."
        val s = CloseSummary(
            kept = List(40) { "Khong tra sua" },
            missed = listOf("Doc sach", "Doc sach"),
            notes = List(40) { note } + List(5) { "Khong tra sua: lọ không đủ nên chưa chuyển 30k." },
            coins = 12,
        )
        val line = CatLines.habitSummary(s)
        assertThat(line).startsWith("Mấy hôm qua Sen giữ được Khong tra sua.")
        assertThat(line).contains("(x40)")
        assertThat(line).contains("(x5)")
        assertThat(line.split("chuyển 30k rồi").size - 1).isEqualTo(1)
        assertThat(line).contains("Lỡ Doc sach rồi")
        assertThat(line.length).isLessThan(250)
    }

    @Test fun oneDayStaysSimple() {
        val line = CatLines.habitSummary(CloseSummary(kept = listOf("Uong nuoc"), coins = 4))
        assertThat(line).isEqualTo("Hôm qua Sen giữ được Uong nuoc. +4 xu")
    }
}
