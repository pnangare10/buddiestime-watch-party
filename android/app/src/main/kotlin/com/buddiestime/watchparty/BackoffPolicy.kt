package com.buddiestime.watchparty

class BackoffPolicy(private val baseMs: Long = 1000, private val maxMs: Long = 15000) {
    fun delayFor(attempt: Int): Long {
        val n = if (attempt < 1) 1 else attempt
        var d = baseMs
        repeat(n - 1) { d = (d * 2).coerceAtMost(maxMs) }
        return d.coerceAtMost(maxMs)
    }
}
