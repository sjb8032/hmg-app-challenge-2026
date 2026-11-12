package kr.contest.pleosapp.core

import kr.contest.pleosapp.core.ConsoleSync.Target
import kr.contest.pleosapp.core.ConsoleSync.Write
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleSyncTest {
    private val standing = Target(Gear.DRIVE, 0)
    private val cruising = Target(Gear.DRIVE, 32)
    private val jammed = Target(Gear.DRIVE, 8)
    private val parked = Target(Gear.PARK, 0)

    @Test
    fun acceleratorHoldsTheSpeedInTheConsoleModel() {
        val pct = ConsoleSync.acceleratorFor(32)
        // 가속(19 × 페달) = 저항(0.012 × 속도)
        assertEquals(0.012 * 32, 19.0 * pct / 100.0, 1e-9)
        assertEquals(0.0, ConsoleSync.acceleratorFor(0), 0.0)
    }

    @Test
    fun firstSyncSetsSpeedThenGear() {
        val writes = ConsoleSync.plan(null, standing, browserActive = true)
        assertEquals(listOf(Write.Controls(0, 0.0), Write.SetGear(Gear.DRIVE)), writes)
    }

    @Test
    fun departingSendsCruiseSpeedWithHoldingPedal() {
        val writes = ConsoleSync.plan(standing, cruising, browserActive = true)
        assertEquals(listOf(Write.Controls(32, ConsoleSync.acceleratorFor(32))), writes)
    }

    @Test
    fun speedChangeWhileDrivingIsSentOnlyWithoutConsoleScreen() {
        assertTrue(ConsoleSync.plan(cruising, jammed, browserActive = true).isEmpty())
        assertEquals(
            listOf(Write.Controls(8, ConsoleSync.acceleratorFor(8))),
            ConsoleSync.plan(cruising, jammed, browserActive = false),
        )
    }

    @Test
    fun parkingPutsParkFirstThenStops() {
        assertEquals(
            listOf(Write.SetGear(Gear.PARK), Write.Controls(0, 0.0)),
            ConsoleSync.plan(cruising, parked, browserActive = true),
        )
    }

    @Test
    fun leavingAParkedSpotSetsSpeedBeforeDrive() {
        assertEquals(
            listOf(Write.Controls(32, ConsoleSync.acceleratorFor(32)), Write.SetGear(Gear.DRIVE)),
            ConsoleSync.plan(parked, cruising, browserActive = true),
        )
    }

    @Test
    fun sameStateSendsNothing() {
        assertTrue(ConsoleSync.plan(cruising, cruising, browserActive = false).isEmpty())
    }
}
