package kr.contest.pleosapp.app

import kr.contest.pleosapp.core.DemoNavi
import kr.contest.pleosapp.core.DemoTrips
import kr.contest.pleosapp.core.Geo
import kr.contest.pleosapp.core.LatLng
import kr.contest.pleosapp.core.MINUTE_MS
import kr.contest.pleosapp.core.Place
import kr.contest.pleosapp.core.PlaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 모의 차가 달린 구간을 미니맵용으로 제대로 기록하는지 (경유지 도착·도중 경로 변경·목적지 도착) */
class LegTrackerTest {
    private val trip = DemoTrips.CITY
    private val naksan = Place("낙산공원", LatLng(37.5806, 127.0076), PlaceKind.WAYPOINT)
    private val inwang = Place("인왕산 스카이웨이", LatLng(37.5796, 126.9582), PlaceKind.WAYPOINT)

    private fun DemoNavi.track(t: LegTracker) = t.update(legStart, target?.position, position)

    @Test
    fun recordsLegsAcrossWaypointRerouteAndArrival() {
        val navi = DemoNavi(trip)
        val legs = LegTracker()
        navi.track(legs)
        // 처음: 출발지 → 내비 목적지
        assertEquals(trip.start, legs.current!!.from)
        assertEquals(0f, legs.current!!.progress, 1e-4f)

        // 코스를 골라 낙산공원 경유: 출발하기 전이라 처음 구간은 기록하지 않는다
        navi.routeTo(trip.destination, listOf(naksan))
        navi.track(legs)
        assertTrue(legs.done.isEmpty())
        assertEquals(naksan.position, legs.current!!.to)

        // 4분 달림: 진행 비율이 곧은 선을 따라 는다
        navi.moving = true
        navi.advance(4 * MINUTE_MS)
        navi.track(legs)
        val p = legs.current!!.progress
        assertTrue("progress=$p", p in 0.05f..0.95f)
        val expected = Geo.distanceMeters(trip.start, navi.position) / Geo.distanceMeters(trip.start, naksan.position)
        assertEquals(expected.toFloat(), p, 0.01f)

        // 도중에 인왕산 쪽으로 바꿈: 달리던 구간은 그때까지 온 만큼 남는다
        navi.routeTo(trip.destination, listOf(inwang))
        navi.track(legs)
        assertEquals(1, legs.done.size)
        assertEquals(p, legs.done[0].progress, 1e-4f)
        assertEquals(inwang.position, legs.current!!.to)
        assertTrue(Geo.distanceMeters(legs.current!!.from, navi.position) < 1.0)

        // 경유지까지 달림: 그 구간은 끝까지(1), 다음 구간은 경유지에서 목적지로
        repeat(200) {
            if (navi.via.isNotEmpty()) navi.advance(10_000L)
        }
        navi.track(legs)
        assertEquals(2, legs.done.size)
        assertEquals(1f, legs.done[1].progress, 1e-4f)
        assertEquals(inwang.position, legs.current!!.from)
        assertEquals(trip.destination.position, legs.current!!.to)

        // 목적지 도착: 지금 구간이 없어지고 마지막 구간은 끝까지
        repeat(200) { if (navi.destination != null) navi.advance(10_000L) }
        navi.track(legs)
        assertNull(legs.current)
        assertEquals(3, legs.done.size)
        assertEquals(1f, legs.done.last().progress, 1e-4f)
        assertNotNull(legs.done.last())
    }

    @Test
    fun keepsOnlyRecentLegs() {
        val legs = LegTracker(max = 3)
        var from = LatLng(37.5, 127.0)
        repeat(6) { i ->
            val to = Geo.destinationPoint(from, 90.0, 1_000.0)
            legs.update(from, to, Geo.destinationPoint(from, 90.0, 500.0))
            from = to
            legs.update(from, Geo.destinationPoint(from, 0.0, 1_000.0), from)
        }
        assertEquals(3, legs.done.size)
    }
}
