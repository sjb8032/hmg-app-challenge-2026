package kr.contest.pleosapp.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {
    @Test
    fun distanceBetweenSeoulAndBusanIsAbout325km() {
        val seoul = LatLng(37.5665, 126.9780)
        val busan = LatLng(35.1796, 129.0756)
        assertEquals(325.0, Geo.distanceMeters(seoul, busan) / 1000.0, 5.0)
    }

    @Test
    fun bearingPointsToCompassDirections() {
        val origin = LatLng(37.0, 127.0)
        assertEquals(0.0, Geo.bearingDegrees(origin, LatLng(37.1, 127.0)), 0.5)
        assertEquals(180.0, Geo.bearingDegrees(origin, LatLng(36.9, 127.0)), 0.5)
        assertEquals(90.0, Geo.bearingDegrees(origin, LatLng(37.0, 127.1)), 0.5)
    }

    @Test
    fun angleDiffWrapsAround() {
        assertEquals(20.0, Geo.angleDiff(350.0, 10.0), 1e-9)
        assertEquals(180.0, Geo.angleDiff(0.0, 180.0), 1e-9)
        assertEquals(90.0, Geo.angleDiff(45.0, 315.0), 1e-9)
    }
}
