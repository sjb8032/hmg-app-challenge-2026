package kr.contest.pleosapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadPathTest {
    // ㄱ자 길: 동쪽으로 1km, 북쪽으로 1km
    private val a = LatLng(37.5, 127.0)
    private val b = Geo.destinationPoint(a, 90.0, 1_000.0)
    private val c = Geo.destinationPoint(b, 0.0, 1_000.0)
    private val path = listOf(a, b, c)

    @Test
    fun lengthAddsSegments() {
        assertEquals(2_000.0, RoadPath.length(path), 1.0)
    }

    @Test
    fun headAndTailSplitAtTheSameSpot() {
        val head = RoadPath.head(path, 0.75)
        val tail = RoadPath.tail(path, 0.75)
        assertEquals(1_500.0, RoadPath.length(head), 1.0)
        assertEquals(500.0, RoadPath.length(tail), 1.0)
        assertTrue(Geo.distanceMeters(head.last(), tail.first()) < 0.5)
        // 굽은 곳(b)을 지난 지점이라 앞쪽은 b를 거친다
        assertTrue(head.any { Geo.distanceMeters(it, b) < 0.5 })
    }

    @Test
    fun pointAtFollowsTheRoadNotTheStraightLine() {
        val half = RoadPath.pointAt(path, 0.5)!!
        // 길의 절반은 굽은 곳(b) — a와 c를 곧게 이은 선의 가운데가 아니다
        assertTrue(Geo.distanceMeters(half, b) < 1.0)
    }

    @Test
    fun bearingLooksAheadAlongTheRoad() {
        assertEquals(90.0, RoadPath.bearingAt(path, 0.1, aheadMeters = 100.0)!!, 1.0)
        val north = RoadPath.bearingAt(path, 0.8, aheadMeters = 100.0)!!
        assertTrue(north < 1.0 || north > 359.0)
        // 끝에서는 뒤에서 앞으로의 방향
        assertNotNull(RoadPath.bearingAt(path, 1.0, aheadMeters = 100.0))
    }

    @Test
    fun thinKeepsEndsAndDropsClosePoints() {
        val dense = (0..100).map { Geo.destinationPoint(a, 90.0, it * 5.0) }
        val thin = RoadPath.thin(dense, 50.0)
        assertEquals(dense.first(), thin.first())
        assertEquals(dense.last(), thin.last())
        assertTrue(thin.size in 10..12)
    }

    @Test
    fun joinDropsTheSharedPoint() {
        val joined = RoadPath.join(listOf(listOf(a, b), listOf(b, c)))
        assertEquals(3, joined.size)
    }
}
