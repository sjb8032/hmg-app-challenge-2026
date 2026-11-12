package kr.contest.pleosapp.core

import kr.contest.pleosapp.core.TestAreas.origin
import kr.contest.pleosapp.core.TestAreas.southKm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeFitPlannerTest {
    private val config = DriveConfig()
    private val dest = Place("목적지", southKm(10.0))
    private val direct = 20 * MINUTE_MS

    /** 출발지-목적지 가운데에서 동쪽으로 km만큼 비켜난 지점 */
    private fun sideKm(km: Double): LatLng = Geo.destinationPoint(southKm(5.0), 90.0, km * 1000.0)

    private fun point(id: String, position: LatLng, vararg tags: CourseTag) =
        CoursePoint(id, "${id}지점", position, tags.toSet())

    @Test
    fun slackIsTargetMinusNowMinusDirectMinusMargin() {
        assertEquals(17 * MINUTE_MS, TimeFitPlanner.slackMs(0L, 40 * MINUTE_MS, direct, config))
    }

    @Test
    fun speedComesFromDirectTime() {
        // 10km × 1.35 = 13.5km를 20분에 → 시속 40.5km
        val v = TimeFitPlanner.effectiveSpeedMps(origin, dest.position, direct, config)
        assertEquals(40.5, v * 3.6, 0.5)
    }

    @Test
    fun picksPointThatFillsSlackAndSkipsTooShortOrTooLong() {
        val points = listOf(
            point("p3", sideKm(3.0)),
            point("p6", sideKm(6.0)),
            point("p10", sideKm(10.0)),
        )
        val options = TimeFitPlanner.options(origin, dest, direct, 17 * MINUTE_MS, points, emptyList(), RoadStyle.LESS_TRAFFIC, config)
        assertEquals(listOf("scenic-p6"), options.map { it.id })
        val added = options.first().addedMs
        assertTrue("약 11분 더 걸려야 함: $added", added in 9 * MINUTE_MS..13 * MINUTE_MS)
    }

    @Test
    fun scenicStylePrefersRiverOverSlightlyBetterFill() {
        val plain = point("plain", sideKm(6.5))
        val river = Geo.destinationPoint(southKm(5.0), 270.0, 6.0 * 1000.0)
        val options = TimeFitPlanner.options(
            origin, dest, direct, 17 * MINUTE_MS,
            listOf(plain, point("river", river, CourseTag.RIVER)), emptyList(), RoadStyle.SCENIC, config,
        )
        assertEquals("scenic-river", options.first().id)
        assertEquals(2, options.size)
    }

    @Test
    fun shortSlackGoesDirect() {
        val options = TimeFitPlanner.options(
            origin, dest, direct, 3 * MINUTE_MS, listOf(point("p6", sideKm(6.0))), emptyList(), RoadStyle.SCENIC, config,
        )
        assertEquals(listOf(CourseKind.DIRECT), options.map { it.kind })
    }

    @Test
    fun longSlackAddsRestStopAheadWithStayTime() {
        val ahead = RestArea("ahead", "앞쪽휴게소", RestAreaType.SERVICE_AREA, Geo.destinationPoint(southKm(6.0), 90.0, 300.0))
        val behind = RestArea("behind", "뒤쪽휴게소", RestAreaType.SERVICE_AREA, LatLng(37.03, 127.0))
        val slack = 40 * MINUTE_MS
        val options = TimeFitPlanner.options(origin, dest, direct, slack, emptyList(), listOf(behind, ahead), RoadStyle.SCENIC, config)
        val rest = options.single { it.kind == CourseKind.REST_STOP }
        assertEquals("rest-ahead", rest.id)
        assertEquals(slack, rest.addedMs)
        assertTrue(rest.stayMs in 35 * MINUTE_MS..slack)
    }

    @Test
    fun noRestStopWhenSlackIsShort() {
        val ahead = RestArea("ahead", "앞쪽휴게소", RestAreaType.SERVICE_AREA, southKm(6.0))
        val options = TimeFitPlanner.options(origin, dest, direct, 15 * MINUTE_MS, emptyList(), listOf(ahead), RoadStyle.SCENIC, config)
        assertTrue(options.none { it.kind == CourseKind.REST_STOP })
    }

    @Test
    fun detourFillsMostOfSlackWhenNoPointsNearby() {
        val slack = 15 * MINUTE_MS
        val options = TimeFitPlanner.options(origin, dest, direct, slack, emptyList(), emptyList(), RoadStyle.SCENIC, config)
        val detour = options.single()
        assertEquals(CourseKind.DETOUR, detour.kind)
        val fill = detour.addedMs.toDouble() / slack
        assertEquals(0.85, fill, 0.05)
    }

    @Test
    fun negativeSlackSaysItMayBeLate() {
        val only = TimeFitPlanner.options(origin, dest, direct, -5 * MINUTE_MS, emptyList(), emptyList(), RoadStyle.SCENIC, config).single()
        assertEquals(CourseKind.DIRECT, only.kind)
        assertTrue(only.reason.contains("5분 늦을"))
        assertNull(only.restArea)
    }

    @Test
    fun objectParticleFollowsFinalConsonant() {
        assertEquals("팔각정을", DriveText.withObject("팔각정"))
        assertEquals("북악스카이웨이를", DriveText.withObject("북악스카이웨이"))
    }
}
