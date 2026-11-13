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

    @Test
    fun freeTripPicksCityOrHighwaySpeedByRouteLength() {
        // 직접 해 보기: 목적지 없이 고른 곳에서 시작해, 경로 길이에 따라 시내(32km/h)·고속도로(95km/h)로 달린다.
        val trip = DemoTrips.free(origin, "고른 곳")
        assertEquals(DemoTrips.FREE_ID, trip.id)
        assertNull(trip.startTimeOfDayMs)
        val navi = DemoNavi(trip)
        assertNull(navi.destination)
        assertNull(navi.remainingMs())

        val near = Place("가까운 곳", southKm(10.0), PlaceKind.SEARCH)
        // 바로 가는 시간도 같은 기준: 10km × 1.35 ÷ 32km/h ≈ 25분
        assertEquals(25.3 * MINUTE_MS, navi.tripTimeMs(near).toDouble(), 0.2 * MINUTE_MS)
        navi.routeTo(near, emptyList())
        assertFalse(navi.onHighway)
        assertEquals(25.3 * MINUTE_MS, navi.remainingMs()!!.toDouble(), 0.2 * MINUTE_MS)

        val far = Place("먼 곳", southKm(100.0), PlaceKind.SEARCH)
        // 100km × 1.2 ÷ 95km/h ≈ 75.8분
        assertEquals(75.8 * MINUTE_MS, navi.tripTimeMs(far).toDouble(), 0.3 * MINUTE_MS)
        navi.routeTo(far, emptyList())
        assertTrue(navi.onHighway)
        navi.moving = true
        assertEquals(95.0, navi.speedKmh, 1e-9)

        // 출발지를 옮기면 경로는 지운다
        navi.moveTo(southKm(50.0))
        assertNull(navi.destination)
        assertFalse(navi.moving)
        assertEquals(southKm(50.0), navi.position)
    }

    @Test
    fun fixedTripsKeepTheirSpeed() {
        val navi = DemoNavi(trip)
        navi.routeTo(Place("먼 곳", southKm(100.0), PlaceKind.SEARCH), emptyList())
        navi.moving = true
        assertEquals(30.0, navi.speedKmh, 1e-9)
        assertFalse(navi.onHighway)
    }
}
