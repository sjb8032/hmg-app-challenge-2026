package kr.contest.pleosapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualClockTest {
    private var real = 1_000L
    private val clock = VirtualClock(start = 50_000L, scale = 12.0) { real }

    @Test
    fun runsScaledByRealTime() {
        real += 5 * SECOND_MS
        assertEquals(50_000L + MINUTE_MS, clock.now())
    }

    @Test
    fun holdStopsTheClockAndResumesWithoutJump() {
        real += 5 * SECOND_MS
        clock.hold(true)
        assertTrue(clock.held)
        real += 30 * SECOND_MS
        assertEquals(50_000L + MINUTE_MS, clock.now())
        clock.hold(false)
        assertFalse(clock.held)
        assertEquals(50_000L + MINUTE_MS, clock.now())
        real += 5 * SECOND_MS
        assertEquals(50_000L + 2 * MINUTE_MS, clock.now())
    }

    @Test
    fun holdingTwiceKeepsTheSameTime() {
        clock.hold(true)
        real += 10 * SECOND_MS
        clock.hold(true)
        real += 10 * SECOND_MS
        clock.hold(false)
        assertEquals(50_000L, clock.now())
    }

    @Test
    fun shiftMovesTimeEvenWhileHeld() {
        clock.hold(true)
        clock.shift(10 * MINUTE_MS)
        assertEquals(50_000L + 10 * MINUTE_MS, clock.now())
        clock.hold(false)
        real += 5 * SECOND_MS
        assertEquals(50_000L + 11 * MINUTE_MS, clock.now())
    }
}
