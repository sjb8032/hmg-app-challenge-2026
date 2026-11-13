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
    fun freeTripTimeFollowsTheWholeDistance() {
        // 직접 해 보기: 목적지 없이 고른 곳에서 시작한다. 걸리는 시간은 양 끝 10km를 시내(32km/h, 1.35배),
        // 그 사이를 고속도로(95km/h, 1.2배)로 본 합이다.
        val trip = DemoTrips.free(origin, "고른 곳")
        assertEquals(DemoTrips.FREE_ID, trip.id)
        assertNull(trip.startTimeOfDayMs)
        val navi = DemoNavi(trip)
        assertNull(navi.destination)
        assertNull(navi.remainingMs())
        assertFalse(navi.onHighway)

        // 20km 안은 모두 시내: 10km × 1.35 ÷ 32km/h ≈ 25.3분
        val near = Place("가까운 곳", southKm(10.0), PlaceKind.SEARCH)
        assertEquals(25.3 * MINUTE_MS, navi.tripTimeMs(near).toDouble(), 0.2 * MINUTE_MS)
        navi.routeTo(near, emptyList())
        assertFalse(navi.onHighway)
        assertEquals(25.3 * MINUTE_MS, navi.remainingMs()!!.toDouble(), 0.2 * MINUTE_MS)
        navi.moving = true
        assertEquals(32.0, navi.speedKmh, 1e-9)

        // 100km: 시내 20km(50.6분) + 고속도로 80km(60.6분) ≈ 111.3분
        val far = Place("먼 곳", southKm(100.0), PlaceKind.SEARCH)
        assertEquals(111.3 * MINUTE_MS, navi.tripTimeMs(far).toDouble(), 0.3 * MINUTE_MS)
        navi.routeTo(far, emptyList())
        val total = navi.remainingMs()!!
        assertEquals(111.3 * MINUTE_MS, total.toDouble(), 0.3 * MINUTE_MS)
        assertEquals(navi.tripTimeMs(far), total)

        // 출발한 곳 둘레 10km는 시내, 그 뒤는 고속도로, 목적지 10km 앞부터 다시 시내 (레스트 모드가 찾는 쉼터 종류)
        assertFalse(navi.onHighway)
        var elapsed = 0L
        while (Geo.distanceMeters(origin, navi.position) < 11_000.0) {
            navi.advance(30 * SECOND_MS)
            elapsed += 30 * SECOND_MS
        }
        assertTrue(navi.onHighway)
        // 남은 시간은 흐른 시간만큼 준다 (가는 내내 같은 평균 속도)
        assertEquals((total - elapsed).toDouble(), navi.remainingMs()!!.toDouble(), 2.0 * SECOND_MS)
        while (Geo.distanceMeters(navi.position, far.position) > 9_000.0) navi.advance(30 * SECOND_MS)
        assertFalse(navi.onHighway)

        // 출발지를 옮기면 경로는 지운다
        navi.moveTo(southKm(50.0))
        assertNull(navi.destination)
        assertFalse(navi.moving)
        assertFalse(navi.onHighway)
        assertEquals(southKm(50.0), navi.position)
    }

    @Test
    fun freeTripTimeGrowsWithDistance() {
        // 어느 거리에서도 더 먼 곳이 더 빨리 닿지 않는다 (예전에는 40km에서 세 배 빨라졌다)
        val navi = DemoNavi(DemoTrips.free(origin, "고른 곳"))
        var last = 0L
        for (km in 1..300) {
            val t = navi.tripTimeMs(Place("${km}km", southKm(km.toDouble()), PlaceKind.SEARCH))
            assertTrue("${km}km: $t <= $last", t > last)
            last = t
        }
    }

    @Test
    fun freeTripKeepsItsSpeedWhenRoutedAgainToTheSameDestination() {
        // 같은 목적지로 경로를 다시 받으면(쉼터 경유, 쉬고 나서, 코스 바꾸기) 속도를 바꾸지 않는다: 남은 시간이 갑자기 뛰지 않게
        val navi = DemoNavi(DemoTrips.free(origin, "고른 곳"))
        val far = Place("먼 곳", southKm(100.0), PlaceKind.SEARCH)
        navi.routeTo(far, emptyList())
        navi.moving = true
        while (Geo.distanceMeters(navi.position, far.position) > 30_000.0) navi.advance(30 * SECOND_MS)
        val before = navi.remainingMs()!!
        val stop = Place("쉼터", Geo.destinationPoint(navi.position, 180.0, 1_000.0), PlaceKind.WAYPOINT)
        navi.routeTo(far, listOf(stop))
        assertEquals(before.toDouble(), navi.remainingMs()!!.toDouble(), 0.5 * MINUTE_MS)
        assertEquals(navi.tripTimeMs(far).toDouble(), before.toDouble(), 2.0 * SECOND_MS)
        navi.routeTo(far, emptyList())
        assertEquals(before.toDouble(), navi.remainingMs()!!.toDouble(), 2.0 * SECOND_MS)
    }

    @Test
    fun freeTripCourseThroughAViaMatchesThePlan() {
        // 코스를 고를 때는 (바로 가는 시간 ÷ 바로 가는 거리) × 경유 거리로 잡는다. 경유지를 넣어도 같은 속도로 달려야 계획과 맞는다
        val navi = DemoNavi(DemoTrips.free(origin, "고른 곳"))
        val dest = Place("35km", southKm(35.0), PlaceKind.SEARCH)
        val direct = navi.tripTimeMs(dest)
        val via = Place("경치", Geo.destinationPoint(southKm(17.5), 90.0, 10_000.0), PlaceKind.WAYPOINT)
        navi.routeTo(dest, listOf(via))
        val path = Geo.distanceMeters(origin, via.position) + Geo.distanceMeters(via.position, dest.position)
        val planned = direct * path / Geo.distanceMeters(origin, dest.position)
        assertEquals(planned, navi.remainingMs()!!.toDouble(), 2.0 * SECOND_MS)
        assertTrue(navi.remainingMs()!! > direct)
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
