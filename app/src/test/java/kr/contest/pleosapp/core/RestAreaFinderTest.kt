package kr.contest.pleosapp.core

import kr.contest.pleosapp.core.TestAreas.area
import kr.contest.pleosapp.core.TestAreas.northCity
import kr.contest.pleosapp.core.TestAreas.origin
import kr.contest.pleosapp.core.TestAreas.southCity
import kr.contest.pleosapp.core.TestAreas.southKm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestAreaFinderTest {
    private val ahead10 = area("ahead10", southKm(10.0))
    private val ahead30 = area("ahead30", southKm(30.0))
    private val behind = area("behind", southKm(-10.0))
    private val sameDirection = area("same", southKm(12.0, lon = 127.0005), directionTarget = southCity)
    private val oppositeDirection = area("opposite", southKm(11.0, lon = 126.9995), directionTarget = northCity)
    private val sideways = area("sideways", LatLng(37.0, 127.3))

    private val finder = RestAreaFinder(listOf(ahead30, behind, oppositeDirection, ahead10, sameDirection, sideways))

    @Test
    fun picksAreasAheadInArrivalOrder() {
        val result = finder.findAhead(origin, headingDeg = 180.0, speedKmh = 100.0)
        assertEquals(listOf("ahead10", "same", "ahead30"), result.candidates.map { it.area.id })
        assertEquals("ahead10", result.best?.area?.id)
    }

    @Test
    fun skipsOppositeCarriagewayByDirectionLabel() {
        val ids = finder.findAhead(origin, headingDeg = 180.0).candidates.map { it.area.id }
        assertTrue("opposite" !in ids)
        assertTrue("same" in ids)
    }

    @Test
    fun skipsAreasBehindAndSideways() {
        val ids = finder.findAhead(origin, headingDeg = 180.0).candidates.map { it.area.id }
        assertTrue("behind" !in ids)
        assertTrue("sideways" !in ids)
    }

    @Test
    fun etaUsesDetourFactorAndSpeed() {
        val best = finder.findAhead(origin, headingDeg = 180.0, speedKmh = 100.0).best!!
        // 직선 10km × 1.25 = 12.5km, 100km/h → 7.5분
        assertEquals(12.5, best.roadMeters / 1000.0, 0.1)
        assertEquals(7.5, best.etaMinutes, 0.1)
    }

    @Test
    fun slowSpeedFallsBackToDefaultSpeed() {
        val best = finder.findAhead(origin, headingDeg = 180.0, speedKmh = 10.0).best!!
        // 정체 중에는 80km/h로 가정: 12.5km → 약 9.4분
        assertEquals(9.375, best.etaMinutes, 0.1)
    }

    @Test
    fun gapIsTimeBetweenFirstAndSecond() {
        val gap = RestAreaFinder(listOf(ahead10, ahead30))
            .findAhead(origin, headingDeg = 180.0, speedKmh = 100.0)
            .gapAfterFirstMinutes!!
        // 20km × 1.25 = 25km, 100km/h → 15분
        assertEquals(15.0, gap, 0.2)
    }

    @Test
    fun usesDestinationWhenHeadingIsUnknown() {
        val ids = finder.findAhead(origin, headingDeg = null, destination = southCity).candidates.map { it.area.id }
        assertEquals("ahead10", ids.first())
        assertTrue("behind" !in ids)
    }

    @Test
    fun picksRightHandSideOfTwinRestAreas() {
        // 같은 이름의 양방향 휴게소. 남쪽으로 갈 때 오른쪽은 서쪽이다.
        val west = RestArea("west", "쌍둥이휴게소", RestAreaType.SERVICE_AREA, southKm(10.0, lon = 126.9983))
        val east = RestArea("east", "쌍둥이휴게소", RestAreaType.SERVICE_AREA, southKm(10.0, lon = 127.0017))
        val twins = RestAreaFinder(listOf(east, west))

        val southbound = twins.findAhead(origin, headingDeg = 180.0)
        assertEquals(listOf("west"), southbound.candidates.map { it.area.id })

        val northbound = twins.findAhead(southKm(20.0), headingDeg = 0.0)
        assertEquals(listOf("east"), northbound.candidates.map { it.area.id })
    }

    @Test
    fun expresswayOnlyPrefersAreasNextToTheMotorway() {
        val localStop = area("local", southKm(5.0))
        val motorwayStop = area("motorway", southKm(8.0)).copy(onExpressway = true)
        val mixed = RestAreaFinder(listOf(localStop, motorwayStop))

        assertEquals("local", mixed.findAhead(origin, 180.0).best?.area?.id)
        assertEquals("motorway", mixed.findAhead(origin, 180.0, expresswayOnly = true).best?.area?.id)
        // 고속도로 쉼터가 없으면 전체에서 고른다.
        assertEquals("local", RestAreaFinder(listOf(localStop)).findAhead(origin, 180.0, expresswayOnly = true).best?.area?.id)
    }

    @Test
    fun destinationPointRoundTrips() {
        val p = Geo.destinationPoint(origin, 135.0, 10_000.0)
        assertEquals(10_000.0, Geo.distanceMeters(origin, p), 1.0)
        assertEquals(135.0, Geo.bearingDegrees(origin, p), 0.1)
    }

    @Test
    fun noCandidatesGivesNullBest() {
        val result = RestAreaFinder(listOf(behind)).findAhead(origin, headingDeg = 180.0)
        assertNull(result.best)
        assertNull(result.gapAfterFirstMinutes)
    }
}
