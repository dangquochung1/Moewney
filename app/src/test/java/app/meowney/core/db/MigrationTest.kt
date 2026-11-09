package app.meowney.core.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = instrumentation,
        file = instrumentation.targetContext.getDatabasePath("mig.db"),
        driver = AndroidSQLiteDriver(),
        databaseClass = MeowneyDb::class,
    )

    @Test fun v1DataSurvivesMigrationTo2() {
        helper.createDatabase(1).apply {
            execSQL("INSERT INTO wallet (id, name, kind, isDefault, archived, sort) VALUES (1, 'Tiền mặt', 'CASH', 1, 0, 0)")
            execSQL("INSERT INTO jar (id, code, name, percent, isSavings, sort) VALUES (1, 'NEC', 'Thiết yếu', 55, 0, 0)")
            execSQL("INSERT INTO txn (id, type, amount, walletId, note, occurredAt, day, createdAt, source) VALUES (1, 'OPENING', 2000000, 1, '', 0, 20401, 0, 'ONBOARDING')")
            execSQL("INSERT INTO jar_entry (jarId, amount, kind, txnId, day) VALUES (1, 2000000, 'OPENING', 1, 20401)")
            close()
        }
        val db = helper.runMigrationsAndValidate(2)
        db.prepare("SELECT amount, debtId FROM txn WHERE id = 1").use {
            assertThat(it.step()).isTrue()
            assertThat(it.getLong(0)).isEqualTo(2_000_000)
            assertThat(it.isNull(1)).isTrue()
        }
        db.prepare("SELECT SUM(amount) FROM jar_entry").use {
            it.step()
            assertThat(it.getLong(0)).isEqualTo(2_000_000)
        }
        db.prepare("SELECT COUNT(*) FROM goal").use {
            it.step()
            assertThat(it.getLong(0)).isEqualTo(0)
        }
        db.close()
    }

    @Test fun v2DataSurvivesMigrationTo3() {
        helper.createDatabase(2).apply {
            execSQL("INSERT INTO wallet (id, name, kind, isDefault, archived, sort) VALUES (1, 'Tiền mặt', 'CASH', 1, 0, 0)")
            execSQL("INSERT INTO jar (id, code, name, percent, isSavings, sort) VALUES (1, 'LTSS', 'Tiết kiệm dài hạn', 10, 1, 0)")
            execSQL("INSERT INTO goal (id, name, jarId, target, autoPercent, createdDay, archived) VALUES (1, 'Laptop', 1, 20000000, 50, 20401, 0)")
            execSQL("INSERT INTO goal_entry (goalId, amount, kind, day) VALUES (1, 500000, 'MANUAL', 20401)")
            close()
        }
        val db = helper.runMigrationsAndValidate(3)
        db.prepare("SELECT SUM(amount) FROM goal_entry").use {
            it.step()
            assertThat(it.getLong(0)).isEqualTo(500_000)
        }
        db.execSQL("INSERT INTO habit (id, name, type, icon, target, unit, schedule, weekdays, timesPerWeek, partOfDay, difficulty, moneyAmount, strength, streak, bestStreak, tickets, closedThrough, createdDay, archived, sort) VALUES (1, 'Đọc sách', 'TIMER', 100, 45, 'phút', 'DAILY', 127, 0, 'EVENING', 2, 0, 0, 0, 0, 0, 20400, 20401, 0, 0)")
        db.prepare("SELECT COUNT(*) FROM habit_log").use {
            it.step()
            assertThat(it.getLong(0)).isEqualTo(0)
        }
        db.close()
    }
}
