package com.buddiestime.watchparty

import android.app.Activity
import android.util.Log
import androidx.annotation.ColorRes
import androidx.annotation.StyleRes

private const val TAG = "HWP-THEME"

/**
 * Daily-rotating accent for the whole app. The base Theme.Fluffles defaults to
 * blush; every Activity calls [apply] before setContentView so colorPrimary &
 * friends resolve to today's accent. The pick is date-based (days since epoch,
 * device-local ms clock), so both phones on the same day see the same accent.
 */
object FlufflesTheme {

    data class Accent(
        val label: String,
        @StyleRes val overlay: Int,
        @ColorRes val primary: Int,
        @ColorRes val deep: Int,
        @ColorRes val container: Int,
    )

    private val accents = listOf(
        Accent("blush", R.style.ThemeOverlay_Fluffles_Accent_Blush,
            R.color.accent_blush, R.color.accent_blush_deep, R.color.accent_blush_container),
        Accent("lavender", R.style.ThemeOverlay_Fluffles_Accent_Lavender,
            R.color.accent_lavender, R.color.accent_lavender_deep, R.color.accent_lavender_container),
        Accent("peach", R.style.ThemeOverlay_Fluffles_Accent_Peach,
            R.color.accent_peach, R.color.accent_peach_deep, R.color.accent_peach_container),
    )

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    fun todaysAccent(nowMs: Long = System.currentTimeMillis()): Accent {
        val day = nowMs / DAY_MS
        val idx = (day % accents.size).toInt()
        val accent = accents[idx]
        Log.d(TAG, "todaysAccent: epochDay=$day idx=$idx accent=${accent.label}")
        return accent
    }

    /** Must run before setContentView in every Activity. */
    fun apply(activity: Activity, roomTheme: ThemeState? = null): Accent {
        val accent = if (roomTheme?.mode == "manual" && roomTheme.value != null) {
            accents.firstOrNull { it.label == roomTheme.value } ?: todaysAccent()
        } else todaysAccent()
        activity.theme.applyStyle(accent.overlay, true)
        Log.d(TAG, "apply: ${activity.javaClass.simpleName} ← accent=${accent.label} (mode=${roomTheme?.mode ?: "auto/no-room"})")
        return accent
    }
}
