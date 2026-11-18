package app.meowney.core.game

import app.meowney.feature.room.Mood

/** First version of the cat's mood: worried about money, happy when Sen keeps the book. */
object MoodEngine {
    fun mood(hasNegativeJar: Boolean, hasJarLoans: Boolean, loggedToday: Boolean): Mood = when {
        hasNegativeJar || hasJarLoans -> Mood.Worried
        loggedToday -> Mood.Happy
        else -> Mood.Calm
    }
}
