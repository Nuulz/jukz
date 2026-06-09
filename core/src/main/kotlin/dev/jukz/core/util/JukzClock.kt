package dev.jukz.core.util

/**
 * Injectable time source. Production uses [SystemClock]; tests use [FakeClock] so
 * settle/heartbeat/TTL windows can be driven deterministically without real sleeps.
 */
interface JukzClock {
    fun nowMillis(): Long
}

object SystemClock : JukzClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** Test clock; time only moves when the test advances it. */
class FakeClock(private var now: Long = 0L) : JukzClock {
    override fun nowMillis(): Long = now
    fun advance(millis: Long) {
        now += millis
    }
    fun set(millis: Long) {
        now = millis
    }
}
