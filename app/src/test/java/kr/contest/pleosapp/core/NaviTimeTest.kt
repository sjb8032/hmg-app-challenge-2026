package kr.contest.pleosapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NaviTimeTest {
    private val now = 1_700_000_000_000L

    @Test
    fun secondsWhenSpeedIsPlausible() {
        // 10km를 1200초(20분): 시속 30km
        assertEquals(20 * MINUTE_MS, NaviTime.tripDurationMs(1_200, 10_000, now))
    }

    @Test
    fun minutesWhenSecondsWouldBeTooFast() {
        // 10km를 20초는 말이 안 되니 20분으로 본다.
        assertEquals(20 * MINUTE_MS, NaviTime.tripDurationMs(20, 10_000, now))
    }

    @Test
    fun epochSecondsBecomeDurationFromNow() {
        val arriveAt = (now + 25 * MINUTE_MS) / 1000
        assertEquals(25 * MINUTE_MS, NaviTime.tripDurationMs(arriveAt.toInt(), 12_000, now))
    }

    @Test
    fun nonsenseIsIgnored() {
        assertNull(NaviTime.tripDurationMs(0, 10_000, now))
        assertNull(NaviTime.tripDurationMs(-5, 10_000, now))
        // 100km를 1분이든 1초든 말이 안 된다.
        assertNull(NaviTime.tripDurationMs(1, 100_000, now))
    }
}
