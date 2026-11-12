package kr.contest.pleosapp.core

import kr.contest.pleosapp.core.TestAreas.origin
import kr.contest.pleosapp.core.TestAreas.southKm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveEngineTest {
    private val t0 = 1_700_000_000_000L
    private val dest = Place("약속 장소", southKm(10.0), PlaceKind.CURRENT)
    private val direct = 20 * MINUTE_MS
    private val p6 = CoursePoint("p6", "강변공원", Geo.destinationPoint(southKm(5.0), 90.0, 6_000.0), setOf(CourseTag.RIVER))
    private val stop = RestArea("stop", "가는길휴게소", RestAreaType.SERVICE_AREA, Geo.destinationPoint(southKm(6.0), 90.0, 300.0))

    private fun engine(points: List<CoursePoint> = listOf(p6), stops: List<RestArea> = listOf(stop)) =
        DriveEngine(points, stops).apply {
            onLocation(t0, origin)
            onNaviDestination(t0, dest, direct)
        }

    /** 딱 맞춰 가요를 열고 코스 후보까지 낸다. */
    private fun DriveEngine.planOnTime(now: Long = t0, shiftMinutes: Int = 0): DriveEngine {
        openMode(now, DriveMode.ON_TIME)
        if (shiftMinutes != 0) shiftTarget(now, shiftMinutes)
        plan(now)
        return this
    }

    @Test
    fun onTimeUsesNaviRemainingAsDirectAndRoundsTarget() {
        val e = engine()
        e.openMode(t0, DriveMode.ON_TIME)
        assertEquals(DriveStep.SETUP, e.state.step)
        assertEquals(direct, e.state.directMs)
        assertFalse(e.state.directEstimated)
        val target = e.state.targetAt!!
        assertEquals(0L, target % (5 * MINUTE_MS))
        assertTrue(target >= t0 + direct + 20 * MINUTE_MS)
        assertTrue(e.drainEffects().none { it is DriveEffect.AskDirectTime })
    }

    @Test
    fun planOffersScenicAndChooseRoutesViaWaypoint() {
        val e = engine().planOnTime()
        assertEquals(DriveStep.CHOOSE, e.state.step)
        val scenic = e.state.options.first { it.kind == CourseKind.SCENIC }
        assertEquals("scenic-p6", scenic.id)

        e.drainEffects()
        e.choose(t0 + 1_000, scenic)
        assertEquals(DriveStep.ACTIVE, e.state.step)
        assertTrue(e.state.viaActive)
        val route = e.drainEffects().filterIsInstance<DriveEffect.Route>().single()
        assertEquals(dest, route.destination)
        assertEquals(listOf("강변공원"), route.via.map { it.name })
    }

    @Test
    fun runningLateDropsWaypointOnce() {
        val e = engine().planOnTime()
        e.choose(t0 + 1_000, e.state.options.first { it.kind == CourseKind.SCENIC })
        e.drainEffects()
        val target = e.state.targetAt!!

        val now = t0 + 60 * SECOND_MS
        e.onNaviDestination(now, dest, target - now + 5 * MINUTE_MS)
        e.onTick(now)
        assertEquals(FitStatus.LATE, e.state.status)
        assertFalse(e.state.viaActive)
        val route = e.drainEffects().filterIsInstance<DriveEffect.Route>().single()
        assertTrue(route.via.isEmpty())

        e.onTick(now + 30 * SECOND_MS)
        assertTrue(e.drainEffects().none { it is DriveEffect.Route })
    }

    @Test
    fun noWaypointChangeRightAfterChoosing() {
        val e = engine().planOnTime()
        e.choose(t0 + 1_000, e.state.options.first { it.kind == CourseKind.SCENIC })
        e.drainEffects()
        // 내비가 아직 옛 경로 값을 주는 동안에는 늦다고 보지 않는다.
        e.onNaviDestination(t0 + 5_000, dest, 60 * MINUTE_MS)
        e.onTick(t0 + 5_000)
        assertTrue(e.state.viaActive)
        assertTrue(e.drainEffects().none { it is DriveEffect.Route })
    }

    @Test
    fun arrivingEarlyOffersExtendWhichReplans() {
        val e = engine().planOnTime()
        e.goDirect(t0 + 1_000)
        assertEquals(DriveStep.ACTIVE, e.state.step)
        val target = e.state.targetAt!!

        val now = t0 + 60 * SECOND_MS
        e.onNaviDestination(now, dest, target - now - 15 * MINUTE_MS)
        e.onTick(now)
        assertEquals(FitStatus.EARLY, e.state.status)
        assertTrue(e.state.extendOffered)

        e.extend(now + 1_000)
        assertEquals(DriveStep.CHOOSE, e.state.step)
        assertTrue(e.state.options.isNotEmpty())
        e.back(now + 2_000)
        assertEquals(DriveStep.ACTIVE, e.state.step)
    }

    @Test
    fun asksDirectTimeForOtherDestinationAndEstimatesWithoutAnswer() {
        val home = Place("집", southKm(4.0), PlaceKind.HOME)
        val e = engine()
        e.onPlaces(listOf(home))
        e.openMode(t0, DriveMode.ON_TIME)
        e.drainEffects()
        e.setDestination(t0 + 1_000, home)
        assertTrue(e.drainEffects().any { it is DriveEffect.AskDirectTime && it.destination == home })
        assertNull(e.state.directMs)

        e.plan(t0 + 2_000)
        assertTrue(e.state.planPending)
        e.onTick(t0 + 6_000)
        assertNotNull(e.state.directMs)
        assertTrue(e.state.directEstimated)
        assertEquals(DriveStep.CHOOSE, e.state.step)
    }

    @Test
    fun defaultTargetFollowsNewDestinationUntilShifted() {
        val near = Place("학원", southKm(3.0), PlaceKind.BOOKMARK)
        val e = engine()
        e.openMode(t0, DriveMode.ON_TIME)
        val first = e.state.targetAt!!
        e.setDestination(t0 + 1_000, near)
        e.onDirectTime(t0 + 2_000, near, 6 * MINUTE_MS)
        val second = e.state.targetAt!!
        assertTrue("가까운 곳은 기본 목표도 당겨짐: ${(first - second) / MINUTE_MS}분", second < first)

        e.shiftTarget(t0 + 3_000, 30)
        val shifted = e.state.targetAt!!
        e.setDestination(t0 + 4_000, dest)
        assertEquals(shifted, e.state.targetAt)
    }

    @Test
    fun directTimeAnswerIsUsed() {
        val home = Place("집", southKm(4.0), PlaceKind.HOME)
        val e = engine()
        e.openMode(t0, DriveMode.ON_TIME)
        e.setDestination(t0 + 1_000, home)
        e.onDirectTime(t0 + 2_000, home, 9 * MINUTE_MS)
        assertEquals(9 * MINUTE_MS, e.state.directMs)
        assertFalse(e.state.directEstimated)
    }

    @Test
    fun arrivalNearDestinationReportsOnTime() {
        val e = engine().planOnTime()
        e.goDirect(t0 + 1_000)
        e.drainEffects()
        val target = e.state.targetAt!!
        e.onLocation(target - 20 * SECOND_MS, dest.position)
        assertEquals(DriveStep.ARRIVED, e.state.step)
        assertEquals("딱 맞춰 도착했어요.", e.state.message)
    }

    @Test
    fun longerModeSetsTargetFromExtraTimeAndRestoresMoodOnArrival() {
        val e = engine()
        e.openMode(t0, DriveMode.LONGER)
        e.setExtraMinutes(15)
        e.setMood(Mood.DATE)
        e.plan(t0)
        assertEquals(t0 + direct + 15 * MINUTE_MS, e.state.targetAt)
        assertTrue(e.state.options.none { it.kind == CourseKind.REST_STOP })

        e.choose(t0 + 1_000, e.state.options.first())
        assertTrue(e.drainEffects().contains(DriveEffect.CabinMood(Mood.DATE)))
        e.onLocation(t0 + 40 * MINUTE_MS, dest.position)
        assertTrue(e.drainEffects().contains(DriveEffect.CabinMood(null)))
    }

    @Test
    fun restPausesTimeFittingAndClearsWaypoint() {
        val e = engine().planOnTime()
        e.choose(t0 + 1_000, e.state.options.first { it.kind == CourseKind.SCENIC })
        e.onRestActive(t0 + 2_000, true)
        assertTrue(e.state.paused)
        assertFalse(e.state.viaActive)

        val now = t0 + 5 * MINUTE_MS
        e.drainEffects()
        e.onNaviDestination(now, dest, 90 * MINUTE_MS)
        e.onTick(now)
        assertTrue(e.drainEffects().none { it is DriveEffect.Route })

        e.onRestActive(now + 1_000, false)
        assertFalse(e.state.paused)
    }

    @Test
    fun restStopWakesInTimeForTarget() {
        val e = engine(points = emptyList()).planOnTime(shiftMinutes = 20)
        val option = e.state.options.first { it.kind == CourseKind.REST_STOP }
        e.choose(t0 + 1_000, option)
        val parkedAt = t0 + 10 * MINUTE_MS
        e.onLocation(parkedAt, stop.position)
        val alarmAt = e.restStopAlarmAt(parkedAt, 10 * MINUTE_MS)!!
        val target = e.state.targetAt!!
        // 남은 4km 남짓을 가는 시간 + 여유 3분 + 깨는 시간 10분을 빼고 깨운다.
        assertTrue(alarmAt < target - 13 * MINUTE_MS)
        assertTrue(alarmAt > target - 25 * MINUTE_MS)
    }

    @Test
    fun restStopKeepsStayUntilParkedAndClearsIfPassed() {
        val e = engine(points = emptyList()).planOnTime(shiftMinutes = 20)
        val option = e.state.options.first { it.kind == CourseKind.REST_STOP }
        e.choose(t0 + 1_000, option)
        val at = t0 + 10 * MINUTE_MS
        // 내비 남은 시간은 운전 시간만: 쉬는 시간을 더하면 목표 시각에 딱 맞는 상황
        e.onNaviDestination(at, dest, e.state.targetAt!! - at - option.stayMs)
        e.onLocation(at, stop.position)
        e.onWaypointArrived(at)
        assertTrue(e.state.viaActive)
        assertEquals(stop, e.restStopHere(stop.position))

        // 쉬지 않고 떠나면 쉬는 시간을 셈에서 뺀다.
        e.onLocation(at + MINUTE_MS, Geo.destinationPoint(stop.position, 180.0, 2_000.0))
        assertFalse(e.state.viaActive)
        assertNull(e.restStopHere(stop.position))
    }

    @Test
    fun finishReturnsHomeAndKeepsPlaces() {
        val home = Place("집", southKm(4.0), PlaceKind.HOME)
        val e = engine()
        e.onPlaces(listOf(home))
        e.planOnTime()
        e.finish(t0 + 1_000)
        assertEquals(DriveStep.HOME, e.state.step)
        assertEquals(listOf(home), e.state.places)
        assertNull(e.state.chosen)
    }
}
