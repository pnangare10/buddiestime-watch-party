package com.buddiestime.watchparty

/** Uppercases the first character; leaves the rest (and blank/already-capitalized strings) untouched. */
fun String.capitalizeFirst(): String =
    replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
