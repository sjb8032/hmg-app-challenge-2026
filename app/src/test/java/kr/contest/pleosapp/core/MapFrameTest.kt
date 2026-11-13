package kr.contest.pleosapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapFrameTest {
    private val nonhyeon = LatLng(37.5110, 127.0214)
    private val gwanghwamun = LatLng(37.5717, 126.9768)

    @Test
    fun noPointsMeansNoFrame() {
        assertNull(MapFrame.fit(emptyList(), aspect = 1.5f))
    }

    @Test
    fun allPointsFallInsideWithPadding() {
        val frame = MapFrame.fit(listOf(nonhyeon, gwanghwamun), aspect = 1.6f, padding = 0.1f)!!
        for (p in listOf(nonhyeon, gwanghwamun)) {
            val m = frame.project(p)
            assertTrue("x=${m.x}", m.x in 0.05f..0.95f)
            assertTrue("y=${m.y}", m.y in 0.05f..0.95f)
        }
    }

    @Test
    fun northIsUpAndEastIsRight() {
        val frame = MapFrame.fit(listOf(nonhyeon, gwanghwamun), aspect = 1f)!!
        val south = frame.project(nonhyeon)
        val north = frame.project(gwanghwamun)
        // 광화문이 논현역보다 북서쪽
        assertTrue(north.y < south.y)
        assertTrue(north.x < south.x)
    }

    @Test
    fun keepsAspectRatioSoDistancesLookTheSameBothWays() {
        val frame = MapFrame.fit(listOf(nonhyeon, gwanghwamun), aspect = 2f)!!
        assertEquals(2.0, frame.widthMeters / frame.heightMeters, 1e-9)
    }

    @Test
    fun singlePointGetsMinimumSpanAndSitsInTheMiddle() {
        val frame = MapFrame.fit(listOf(nonhyeon), aspect = 1.5f, padding = 0f, minSpanMeters = 1_500.0)!!
        assertEquals(1_500.0, frame.widthMeters, 1e-6)
        val m = frame.project(nonhyeon)
        assertEquals(0.5f, m.x, 1e-4f)
        assertEquals(0.5f, m.y, 1e-4f)
    }
}
