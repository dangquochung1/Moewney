package app.meowney.core.game

import app.meowney.feature.room.Mood

/** First version of the cat's mood: worried about money, happy when Sen keeps the book. */
object MoodEngine {
    fun mood(leftToday: Long, hasJarLoans: Boolean, loggedToday: Boolean): Mood = when {
        leftToday < 0 || hasJarLoans -> Mood.Worried
        loggedToday -> Mood.Happy
        else -> Mood.Calm
    }
}
