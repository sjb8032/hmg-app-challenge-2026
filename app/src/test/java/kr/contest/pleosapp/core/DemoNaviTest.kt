package kr.contest.pleosapp.core

import kr.contest.pleosapp.core.TestAreas.origin
import kr.contest.pleosapp.core.TestAreas.southKm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoNaviTest {
    private val dest = Place("목적지", southKm(10.0), PlaceKind.CURRENT)
    private val trip = DemoTrip(
        id = "t",
        title = "테스트",
        start = origin,
        destination = dest,
        places = emptyList(),
        speedKmh = 30.0,
        detourFactor = 1.5,
        expressway = false,
        timeScale = 12.0,
    )

    @Test
    fun remainingShrinksByElapsedTime() {
        val navi = DemoNavi(trip)
        val before = navi.remainingMs()!!
        // 10km × 1.5 = 15km를 시속 30km로: 30분
        assertEquals(30 * MINUTE_MS.toDouble(), before.toDouble(), 2_000.0)
        navi.moving = true
        navi.advance(5 * MINUTE_MS)
        assertEquals((before - 5 * MINUTE_MS).toDouble(), navi.remainingMs()!!.toDouble(), 2_000.0)
    }

    @Test
    fun jamDelaysArrivalByRequestedTime() {
        val navi = DemoNavi(trip)
        navi.moving = true
        val before = navi.remainingMs()!!
        navi.jam(6 * MINUTE_MS)
        assertEquals((before + 6 * MINUTE_MS).toDouble(), navi.remainingMs()!!.toDouble(), 2_000.0)
        navi.advance(3 * MINUTE_MS)
        assertTrue(navi.jammed)
        assertEquals((before + 3 * MINUTE_MS).toDouble(), navi.remainingMs()!!.toDouble(), 2_000.0)
    }

    @Test
    fun passesWaypointThenStopsAtDestination() {
        val navi = DemoNavi(trip)
        val side = Place("경유지", Geo.destinationPoint(southKm(5.0), 90.0, 2_000.0), PlaceKind.WAYPOINT)
        navi.routeTo(dest, listOf(side))
        navi.moving = true
        val events = ArrayList<DemoNaviEvent>()
        repeat(200) { events += navi.advance(30 * SECOND_MS) }
        assertEquals(DemoNaviEvent.WaypointArrived(side), events.first())
        assertEquals(DemoNaviEvent.DestinationArrived(dest), events.last())
        assertFalse(navi.moving)
        assertNull(navi.remainingMs())
        assertTrue(Geo.distanceMeters(navi.position, dest.position) < 1.0)
    }
}
