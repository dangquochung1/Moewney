package app.meowney.core.format

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VndTest {
    @Test fun groupsThousands() {
        assertThat(formatVnd(0)).isEqualTo("0đ")
        assertThat(formatVnd(999)).isEqualTo("999đ")
        assertThat(formatVnd(1_000)).isEqualTo("1.000đ")
        assertThat(formatVnd(152_000)).isEqualTo("152.000đ")
        assertThat(formatVnd(15_000_000)).isEqualTo("15.000.000đ")
        assertThat(formatVnd(-45_000)).isEqualTo("-45.000đ")
    }

    @Test fun shortForm() {
        assertThat(formatVndShort(25_000)).isEqualTo("25k")
        assertThat(formatVndShort(3_200_000)).isEqualTo("3,2tr")
        assertThat(formatVndShort(5_000_000)).isEqualTo("5tr")
        assertThat(formatVndShort(-1_250_000)).isEqualTo("-1,3tr")
        assertThat(formatVndShort(500)).isEqualTo("500đ")
    }
}
