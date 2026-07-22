package com.buddiestime.watchparty

data class QuietHours(val startHour: Int, val endHour: Int) {
    fun isQuietAt(hour: Int): Boolean =
        if (startHour <= endHour) hour in startHour until endHour
        else hour >= startHour || hour < endHour
}
