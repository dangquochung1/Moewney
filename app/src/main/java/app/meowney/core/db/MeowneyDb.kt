package app.meowney.core.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import app.meowney.core.money.JarCode

@Database(
    entities = [
        WalletEntity::class, JarEntity::class, CategoryEntity::class, TxnEntity::class,
        JarEntryEntity::class, CoinEntryEntity::class, QuickTemplateEntity::class,
        GoalEntity::class, GoalEntryEntity::class, DebtEntity::class, RecurringEntity::class,
        DayStatEntity::class, MonthReportEntity::class,
        HabitEntity::class, HabitLogEntity::class, FocusSessionEntity::class,
        CalLabelEntity::class, EventEntity::class, TodoEntity::class, TodoStepEntity::class,
        NoteEntity::class, NoteBlockEntity::class, NoteTagEntity::class, NoteTagRef::class,
    ],
    version = 5,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5)],
)
abstract class MeowneyDb : RoomDatabase() {
    abstract fun money(): MoneyDao
    abstract fun plans(): PlanDao
    abstract fun reports(): ReportDao
    abstract fun habits(): HabitDao
    abstract fun calendar(): CalendarDao
    abstract fun notes(): NoteDao

    companion object {
        const val FILE = "meowney.db"
        const val SCHEMA_VERSION = 5

        fun open(context: Context): MeowneyDb =
            Room.databaseBuilder(context.applicationContext, MeowneyDb::class.java, FILE).build()
    }
}

/** What a fresh install starts with. */
object DefaultData {
    data class JarSeed(val code: JarCode, val name: String, val percent: Int)

    val jars = listOf(
        JarSeed(JarCode.NEC, "Thiết yếu", 55),
        JarSeed(JarCode.FFA, "Tự do tài chính", 10),
        JarSeed(JarCode.LTSS, "Tiết kiệm dài hạn", 10),
        JarSeed(JarCode.EDU, "Giáo dục", 10),
        JarSeed(JarCode.PLAY, "Hưởng thụ", 10),
        JarSeed(JarCode.GIVE, "Cho đi", 5),
        JarSeed(JarCode.UNALLOC, "Chưa phân bổ", 0),
    )

    /** name, raven16 cell, jar it is paid from */
    data class CategorySeed(val name: String, val icon: Int, val jar: JarCode?)

    val expenseCategories = listOf(
        CategorySeed("Ăn uống", Icons.FOOD, JarCode.NEC),
        CategorySeed("Cà phê", Icons.DRINK, JarCode.PLAY),
        CategorySeed("Đi lại", Icons.TRAVEL, JarCode.NEC),
        CategorySeed("Nhà & hoá đơn", Icons.HOME, JarCode.NEC),
        CategorySeed("Mua sắm", Icons.SHOP, JarCode.PLAY),
        CategorySeed("Giải trí", Icons.FUN, JarCode.PLAY),
        CategorySeed("Sức khoẻ", Icons.HEALTH, JarCode.NEC),
        CategorySeed("Học tập", Icons.STUDY, JarCode.EDU),
        CategorySeed("Quà & hiếu hỷ", Icons.GIFT, JarCode.GIVE),
        CategorySeed("Từ thiện", Icons.CHARITY, JarCode.GIVE),
        CategorySeed("Đầu tư", Icons.INVEST, JarCode.FFA),
        CategorySeed("Khác", Icons.OTHER, JarCode.NEC),
    )

    val incomeCategories = listOf(
        CategorySeed("Lương", Icons.SALARY, null),
        CategorySeed("Thưởng", Icons.BONUS, null),
        CategorySeed("Làm thêm", Icons.SIDE_JOB, null),
        CategorySeed("Được cho", Icons.GIFT_IN, null),
        CategorySeed("Bán đồ", Icons.SELL, null),
        CategorySeed("Khác", Icons.OTHER, null),
    )
}

/** Cells of assets/icons/raven16.png picked for the categories (16 per row). */
object Icons {
    const val FOOD = 466     // bowl of food
    const val DRINK = 528    // cup
    const val TRAVEL = 890   // running figure
    const val HOME = 15      // roof
    const val SHOP = 157     // money bag
    const val FUN = 946      // music notes
    const val HEALTH = 279   // red potion
    const val STUDY = 100    // blue book
    const val GIFT = 5       // chest
    const val CHARITY = 674  // heart
    const val INVEST = 159   // $
    const val OTHER = 12     // ?
    const val SALARY = 170   // gold coin
    const val BONUS = 27     // star
    const val SIDE_JOB = 126 // hammer
    const val GIFT_IN = 660  // two hearts
    const val SELL = 24      // cart
}
