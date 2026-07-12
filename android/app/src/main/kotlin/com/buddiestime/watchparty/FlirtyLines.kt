package com.buddiestime.watchparty

import android.util.Log
import java.util.Calendar
import kotlin.random.Random

private const val TAG = "HWP-FLIRT"

/**
 * The love-note lines shown on the splash overlay. Picker prefers a
 * time-of-day line ~40% of the time when one applies, otherwise draws
 * from the general pool. Every pick is logged.
 */
object FlirtyLines {

    private val her = Personalization.HER_NAME
    private val him = Personalization.HIS_NAME

    private val general = listOf(
        "Hey $her 💗 I made this just for you",
        "Movie night with my favorite person?",
        "Two tickets, front row — reserved for us, always",
        "You bring the smile, I'll bring the movies 🎬",
        "Our own little cinema. No one else allowed",
        "Built with 100% love by $him",
        "You + me + something to watch = perfect",
        "Warning: watching with $her may cause extreme happiness",
        "Every rewatch is better next to you",
        "Popcorn's ready. Missing you already 🍿",
        "The best seat in the house is next to you",
        "Some stories are ours alone 💞",
        "Press play, $her — I'm right here",
        "Distance is just an intermission",
    )

    private val morning = listOf(          // 5–11
        "Good morning, beautiful ☀️ movie later?",
        "Morning, $her 🌸 today's plan: you, me, one great show",
    )
    private val afternoon = listOf(        // 12–16
        "Afternoon break? I've got us a screen 🎬",
        "Thinking of you… and maybe one episode 👀",
    )
    private val evening = listOf(          // 17–21
        "Golden hour — and you're still the highlight ✨",
        "Perfect evening for us and a movie",
    )
    private val lateNight = listOf(        // 22–4
        "It's late… one more episode? 🌙",
        "Can't sleep? Me neither. Movie? 🌙",
    )

    fun pick(
        hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
        random: Random = Random.Default,
    ): String {
        val timePool = when (hour) {
            in 5..11 -> morning
            in 12..16 -> afternoon
            in 17..21 -> evening
            else -> lateNight
        }
        val useTimeLine = random.nextInt(100) < 40
        val pool = if (useTimeLine) timePool else general
        val line = pool[random.nextInt(pool.size)]
        Log.d(TAG, "pick: hour=$hour useTimeLine=$useTimeLine poolSize=${pool.size} line=\"$line\"")
        return line
    }
}
