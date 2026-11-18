package app.meowney.feature.room

import app.meowney.core.data.GameState
import app.meowney.core.game.Catalog
import app.meowney.core.game.Season
import app.meowney.core.game.SeasonEngine
import app.meowney.core.game.Slot

/** What the cat has with it: the bed, bowl and toy Sen picked, a hat, and the season. */
data class RoomLook(
    val bed: String = "prop/catbed",
    val bowl: String = "prop/pet_food_red",
    val toy: String = "prop/yarnball",
    /** hat/<name> worn now, or null. */
    val hat: String? = null,
    val season: Season? = null,
)

/** The look from what is equipped; in Noel the cat gets a Santa hat unless Sen picked one. */
fun GameState.look(season: Season?): RoomLook {
    fun item(slot: Slot) = equipped(slot)?.let { Catalog[it] }
    val hat = item(Slot.HAT)?.sprite?.split("/")?.getOrNull(1) ?: SeasonEngine.freeHat(season)
    return RoomLook(
        bed = item(Slot.BED)?.sprite ?: "prop/catbed",
        bowl = item(Slot.BOWL)?.sprite ?: "prop/pet_food_red",
        toy = item(Slot.TOY)?.sprite ?: "prop/yarnball",
        hat = hat,
        season = season,
    )
}
