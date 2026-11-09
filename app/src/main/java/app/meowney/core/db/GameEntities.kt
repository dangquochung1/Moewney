package app.meowney.core.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Something bought in the shop (or won). Treats are used up ([qty]); everything else is kept. */
@Entity(tableName = "owned_item")
data class OwnedItemEntity(
    @PrimaryKey val itemId: String,
    val qty: Int = 1,
    val acquiredDay: Long,
)

/** A cat of the collection (one per colour 1..6); the one on show is settings.catColor. */
@Entity(tableName = "pet")
data class PetEntity(
    @PrimaryKey val color: Int,
    val name: String,
    /** "Tim thân thiết", grows with treats. */
    val affection: Int = 0,
    val adoptedDay: Long,
)

/** What sits in each decoration slot (only slots changed from the default are stored). */
@Entity(tableName = "equip")
data class EquipEntity(
    @PrimaryKey val slot: String,
    val itemId: String,
)

/** A real-life treat Sen set a coin price on, e.g. "Trà sữa" for 300 coins / 35.000đ. */
@Entity(tableName = "real_reward")
data class RealRewardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val coins: Int,
    /** Expected money in đồng (0 = not about money). */
    val amount: Long = 0,
    val categoryId: Long? = null,
    val sort: Int = 0,
    val archived: Boolean = false,
)

/** A redeemed reward waiting to be enjoyed; "Dùng" opens a prefilled expense. */
@Entity(tableName = "voucher", indices = [Index("usedDay")])
data class VoucherEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rewardId: Long?,
    val name: String,
    val coins: Int,
    val amount: Long,
    val categoryId: Long?,
    val boughtDay: Long,
    val usedDay: Long? = null,
)

/** A reached achievement tier (1 đồng, 2 bạc, 3 vàng). */
@Entity(tableName = "achievement", primaryKeys = ["id", "tier"])
data class AchievementEntity(val id: String, val tier: Int, val day: Long)

/** The monthly challenge: three suggestions, the one picked, when it was done and claimed. */
@Entity(tableName = "challenge")
data class ChallengeEntity(
    /** yyyymm */
    @PrimaryKey val month: Int,
    /** JSON list of ChallengeSpec. */
    val options: String,
    val picked: Int? = null,
    val doneDay: Long? = null,
    val claimed: Boolean = false,
)

/** Small game events to count: SPIN, LIXI, FEED, QUEST, BOOST. [data] is free text (prize, quest id...). */
@Entity(tableName = "game_log", indices = [Index("day"), Index("kind")])
data class GameLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: Long,
    val kind: String,
    val data: String = "",
    val at: Long = 0,
)

@Dao
interface GameDao {
    @Query("SELECT * FROM owned_item")
    fun owned(): Flow<List<OwnedItemEntity>>

    @Query("SELECT * FROM owned_item")
    suspend fun ownedNow(): List<OwnedItemEntity>

    @Query("SELECT * FROM owned_item WHERE itemId = :id")
    suspend fun ownedItem(id: String): OwnedItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putOwned(o: OwnedItemEntity)

    @Query("DELETE FROM owned_item WHERE itemId = :id")
    suspend fun deleteOwned(id: String)

    @Query("SELECT * FROM pet ORDER BY adoptedDay, color")
    fun pets(): Flow<List<PetEntity>>

    @Query("SELECT * FROM pet ORDER BY adoptedDay, color")
    suspend fun petsNow(): List<PetEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPet(p: PetEntity)

    @Update suspend fun updatePet(p: PetEntity)

    @Query("SELECT * FROM equip")
    fun equip(): Flow<List<EquipEntity>>

    @Query("SELECT * FROM equip")
    suspend fun equipNow(): List<EquipEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putEquip(e: EquipEntity)

    @Query("DELETE FROM equip WHERE slot = :slot")
    suspend fun clearEquip(slot: String)

    // ------------------------------------------------------------------ real rewards

    @Query("SELECT * FROM real_reward WHERE archived = 0 ORDER BY sort, id")
    fun rewards(): Flow<List<RealRewardEntity>>

    @Query("SELECT * FROM real_reward WHERE id = :id")
    suspend fun reward(id: Long): RealRewardEntity?

    @Insert suspend fun insertReward(r: RealRewardEntity): Long
    @Update suspend fun updateReward(r: RealRewardEntity)

    @Query("SELECT * FROM voucher ORDER BY usedDay IS NOT NULL, boughtDay DESC, id DESC")
    fun vouchers(): Flow<List<VoucherEntity>>

    @Query("SELECT * FROM voucher WHERE id = :id")
    suspend fun voucher(id: Long): VoucherEntity?

    @Insert suspend fun insertVoucher(v: VoucherEntity): Long
    @Update suspend fun updateVoucher(v: VoucherEntity)

    // ------------------------------------------------------------------ achievements & challenges

    @Query("SELECT * FROM achievement")
    fun achievements(): Flow<List<AchievementEntity>>

    @Query("SELECT * FROM achievement")
    suspend fun achievementsNow(): List<AchievementEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAchievement(a: AchievementEntity): Long

    @Query("SELECT * FROM challenge WHERE month = :month")
    fun challengeFlow(month: Int): Flow<ChallengeEntity?>

    @Query("SELECT * FROM challenge WHERE month = :month")
    suspend fun challenge(month: Int): ChallengeEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChallenge(c: ChallengeEntity)

    @Update suspend fun updateChallenge(c: ChallengeEntity)

    @Query("SELECT COUNT(*) FROM challenge WHERE claimed = 1")
    suspend fun challengesDone(): Int

    // ------------------------------------------------------------------ log

    @Insert suspend fun log(e: GameLogEntity): Long

    @Query("SELECT COUNT(*) FROM game_log WHERE kind = :kind AND day = :day")
    suspend fun countOn(kind: String, day: Long): Int

    @Query("SELECT COUNT(*) FROM game_log WHERE kind = :kind AND day = :day")
    fun countOnFlow(kind: String, day: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM game_log WHERE kind = :kind")
    suspend fun countAll(kind: String): Int

    @Query("SELECT COUNT(*) FROM game_log WHERE kind = :kind AND data = :data")
    suspend fun countData(kind: String, data: String): Int

    @Query("SELECT MAX(at) FROM game_log WHERE kind = :kind")
    fun lastAt(kind: String): Flow<Long?>

    // ------------------------------------------------------------------ numbers for achievements & challenges

    @Query("SELECT COALESCE(SUM(delta), 0) FROM coin_entry")
    suspend fun balance(): Long

    @Query("SELECT COALESCE(SUM(delta), 0) FROM coin_entry WHERE delta > 0")
    suspend fun coinsEarned(): Long

    @Query("SELECT COUNT(*) FROM coin_entry WHERE reason = :reason")
    suspend fun coinCount(reason: String): Int

    @Query("SELECT DISTINCT day FROM coin_entry WHERE reason = :reason AND day BETWEEN :from AND :to")
    suspend fun coinDays(reason: String, from: Long, to: Long): List<Long>

    @Query("SELECT COUNT(DISTINCT day) FROM txn WHERE source IN ('MANUAL', 'TEMPLATE') AND type IN ('EXPENSE', 'INCOME')")
    suspend fun loggedDays(): Int

    @Query("SELECT DISTINCT day FROM txn WHERE source IN ('MANUAL', 'TEMPLATE') AND type IN ('EXPENSE', 'INCOME') AND day BETWEEN :from AND :to")
    suspend fun loggedDaysBetween(from: Long, to: Long): List<Long>

    @Query("SELECT COUNT(*) FROM txn WHERE source IN ('MANUAL', 'TEMPLATE') AND type IN ('EXPENSE', 'INCOME')")
    suspend fun loggedTxns(): Int

    @Query("SELECT COUNT(*) FROM goal WHERE achievedDay IS NOT NULL")
    suspend fun goalsReached(): Int

    @Query("SELECT COUNT(*) FROM month_report")
    suspend fun reportsKept(): Int

    @Query("SELECT COALESCE(MAX(bestStreak), 0) FROM habit")
    suspend fun bestHabitStreak(): Int

    @Query("SELECT COUNT(*) FROM habit_log WHERE status IN ('DONE', 'KEPT')")
    suspend fun habitDone(): Int

    @Query("SELECT COUNT(*) FROM habit_log WHERE habitId = :habitId AND status IN ('DONE', 'KEPT') AND day BETWEEN :from AND :to")
    suspend fun habitDoneBetween(habitId: Long, from: Long, to: Long): Int

    @Query("SELECT COALESCE(SUM(focusMinutes), 0) FROM focus_session WHERE completed = 1")
    suspend fun focusMinutes(): Int

    @Query("SELECT COALESCE(SUM(focusMinutes), 0) FROM focus_session WHERE completed = 1 AND day BETWEEN :from AND :to")
    suspend fun focusMinutesBetween(from: Long, to: Long): Int

    @Query("SELECT COUNT(*) FROM todo WHERE doneAt IS NOT NULL")
    suspend fun todosDone(): Int

    @Query("SELECT COUNT(*) FROM todo WHERE doneDay BETWEEN :from AND :to")
    suspend fun todosDoneBetween(from: Long, to: Long): Int

    @Query("SELECT COUNT(*) FROM event")
    suspend fun events(): Int

    @Query("SELECT COUNT(*) FROM note WHERE kind = 'NOTE' AND deletedAt IS NULL")
    suspend fun notes(): Int

    @Query("SELECT COUNT(*) FROM note_block WHERE image IS NOT NULL")
    suspend fun photos(): Int

    @Query("SELECT COUNT(*) FROM voucher WHERE usedDay IS NOT NULL")
    suspend fun vouchersUsed(): Int
}
