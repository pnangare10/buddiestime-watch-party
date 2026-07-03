package com.buddiestime.watchparty

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentRoomsStoreTest {
    @Test fun encode_then_decode_roundtrips() {
        val list = listOf(
            RecentRoom("abc", "hotstar", "hotstar.com/x", 100),
            RecentRoom("def", "netflix", "netflix.com/watch/1", 200),
        )
        val decoded = RecentRoomsCodec.decode(RecentRoomsCodec.encode(list))
        assertEquals(list, decoded)
    }
    @Test fun decode_tolerates_garbage() {
        assertEquals(emptyList<RecentRoom>(), RecentRoomsCodec.decode(""))
        assertEquals(emptyList<RecentRoom>(), RecentRoomsCodec.decode("not json"))
    }
    @Test fun merge_dedupes_newest_wins_and_sorts_desc() {
        val existing = listOf(RecentRoom("abc", "hotstar", "old", 100))
        val merged = mergeRecent(existing, RecentRoom("abc", "hotstar", "new", 300))
        assertEquals(1, merged.size)
        assertEquals("new", merged[0].videoUrl)
        assertEquals(300, merged[0].lastJoined)
    }
    @Test fun merge_caps_length() {
        val many = (1..25).map { RecentRoom("r$it", "hotstar", "u$it", it.toLong()) }
        val merged = many.fold(emptyList<RecentRoom>()) { acc, r -> mergeRecent(acc, r, max = 20) }
        assertEquals(20, merged.size)
        assertEquals("r25", merged[0].roomId) // newest first
    }
}
