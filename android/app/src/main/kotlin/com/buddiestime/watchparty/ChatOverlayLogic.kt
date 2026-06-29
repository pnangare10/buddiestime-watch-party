package com.buddiestime.watchparty

/** First whitespace-delimited token of a display name, or "Guest" when blank. */
fun firstNameOf(name: String): String =
    name.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() } ?: "Guest"

/** Drops oldest entries in place so history.size <= max. No-op if already within cap. */
fun trimHistory(history: MutableList<ChatMessage>, max: Int) {
    while (history.size > max) history.removeAt(0)
}

/**
 * Tracks locally-rendered outgoing messages so the server's echo of the user's own
 * message can be de-duplicated (we render optimistically, then swallow the echo).
 */
class OutgoingEchoTracker(private val windowMs: Long = 10_000L) {
    private data class Pending(val text: String, val at: Long)
    private val pending = mutableListOf<Pending>()

    fun registerLocal(text: String, now: Long) {
        pending.add(Pending(text.trim(), now))
    }

    /** Returns true (once) if an outgoing message with this text was registered within the window. */
    fun consumeEcho(text: String, now: Long): Boolean {
        pending.removeAll { now - it.at > windowMs }
        val idx = pending.indexOfFirst { it.text == text.trim() }
        return if (idx >= 0) { pending.removeAt(idx); true } else false
    }
}
