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

    /** 온타임 모드를 열고 코스 후보까지 낸다. */
    private fun DriveEngine.planOnTime(now: Long = t0, shiftMinutes: Int = 0): DriveEngine {
        openMode(now, DriveMode.ON_TIME)
        if (shiftMinutes != 0) shiftTarget(now, shiftMinutes)
        plan(now)
        return this
    }

    /** 내비가 알려 주는 남은 시간을 바꾸고 시간을 흘린다. */
    private fun DriveEngine.naviAt(now: Long, remainingMs: Long) {
        onNaviDestination(now, dest, remainingMs)
        onTick(now)
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
        assertEquals(OnTimeStyle.LOOP, e.state.onTimeStyle)
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
        // 제목이 코스를 보여 주므로 같은 말을 또 띄우지 않는다.
        assertNull(e.state.message)
        val route = e.drainEffects().filterIsInstance<DriveEffect.Route>().single()
        assertEquals(dest, route.destination)
        assertEquals(listOf("강변공원"), route.via.map { it.name })
    }

    @Test
    fun runningLateWaitsThenShortensCourseThenDropsIt() {
        val p35 = CoursePoint("p35", "가까운공원", Geo.destinationPoint(southKm(5.0), 90.0, 3_500.0), setOf(CourseTag.PARK))
        val e = engine(points = listOf(p6, p35))
        e.openMode(t0, DriveMode.ON_TIME)
        e.setTarget(t0, t0 + 45 * MINUTE_MS)
        e.plan(t0)
        e.choose(t0 + 1_000, e.state.options.first { it.id == "scenic-p6" })
        e.drainEffects()
        val target = e.state.targetAt!!

        var now = t0 + 60 * SECOND_MS
        e.naviAt(now, target - now + 5 * MINUTE_MS)
        assertEquals(FitStatus.LATE, e.state.status)
        // 잠깐 막힌 것일 수 있어 바로 바꾸지 않는다.
        assertTrue(e.state.viaActive)
        assertTrue(e.drainEffects().none { it is DriveEffect.Route })

        now += 95 * SECOND_MS
        e.naviAt(now, target - now + 5 * MINUTE_MS)
        val shortened = e.drainEffects()
        assertEquals(listOf("가까운공원"), shortened.filterIsInstance<DriveEffect.Route>().single().via.map { it.name })
        assertEquals("scenic-p35", e.state.chosen?.id)
        assertEquals(1, shortened.count { it is DriveEffect.Speak })

        // 그래도 늦으면 경유지를 뺀다.
        now += 30 * SECOND_MS
        e.naviAt(now, target - now + 5 * MINUTE_MS)
        now += 95 * SECOND_MS
        e.naviAt(now, target - now + 5 * MINUTE_MS)
        assertFalse(e.state.viaActive)
        assertTrue(e.drainEffects().filterIsInstance<DriveEffect.Route>().single().via.isEmpty())
    }

    @Test
    fun lateWithoutWaypointIsSpokenOnce() {
        val e = engine().planOnTime()
        e.goDirect(t0 + 1_000)
        e.drainEffects()
        val target = e.state.targetAt!!
        var now = t0 + MINUTE_MS
        e.naviAt(now, target - now + 6 * MINUTE_MS)
        now += 95 * SECOND_MS
        e.naviAt(now, target - now + 6 * MINUTE_MS)
        assertEquals(1, e.drainEffects().count { it is DriveEffect.Speak })
        now += 5 * MINUTE_MS
        e.naviAt(now, target - now + 6 * MINUTE_MS)
        assertTrue(e.drainEffects().none { it is DriveEffect.Speak })
    }

    @Test
    fun noWaypointChangeRightAfterChoosing() {
        val e = engine().planOnTime()
        e.choose(t0 + 1_000, e.state.options.first { it.kind == CourseKind.SCENIC })
        e.drainEffects()
        // 내비가 아직 옛 경로 값을 주는 동안에는 늦다고 보지 않는다.
        e.naviAt(t0 + 5_000, 60 * MINUTE_MS)
        assertTrue(e.state.viaActive)
        assertTrue(e.drainEffects().none { it is DriveEffect.Route })
    }

    @Test
    fun arrivingEarlyOffersOneTapExtendOnScreenOnly() {
        val e = engine().planOnTime()
        e.goDirect(t0 + 1_000)
        assertEquals(DriveStep.ACTIVE, e.state.step)
        e.drainEffects()
        val target = e.state.targetAt!!

        // 바로 가면 20분 걸리는데 목표까지 40분 넘게 남음
        val now = t0 + 60 * SECOND_MS
        e.naviAt(now, direct)
        assertTrue(target - now > direct + 15 * MINUTE_MS)
        assertEquals(FitStatus.EARLY, e.state.status)
        assertTrue(e.state.extendOffered)
        assertEquals("scenic-p6", e.state.extendOption?.id)
        assertTrue("일찍 도착 권유는 화면으로만", e.drainEffects().none { it is DriveEffect.Speak })

        e.extend(now + 1_000)
        assertEquals(DriveStep.ACTIVE, e.state.step)
        assertEquals("scenic-p6", e.state.chosen?.id)
        assertTrue(e.state.viaActive)
        assertEquals(listOf("강변공원"), e.drainEffects().filterIsInstance<DriveEffect.Route>().single().via.map { it.name })

        // 마음이 바뀌면 '바로 가기'로 되돌린다.
        e.goDirect(now + 2_000)
        assertFalse(e.state.viaActive)
    }

    @Test
    fun noExtendOfferAfterRest() {
        val e = engine().planOnTime()
        e.goDirect(t0 + 1_000)
        val target = e.state.targetAt!!
        e.onRestPhase(t0 + 2_000, Phase.TO_REST_AREA)
        e.onRestPhase(t0 + 3_000, Phase.RESTING)
        e.onRestPhase(t0 + 4_000, Phase.DRIVING)
        val now = t0 + 20 * MINUTE_MS
        e.naviAt(now, target - now - 15 * MINUTE_MS)
        assertEquals(FitStatus.EARLY, e.state.status)
        assertFalse(e.state.extendOffered)
    }

    @Test
    fun noExtendOfferRightAfterPassingWaypoint() {
        val e = engine().planOnTime()
        e.choose(t0 + 1_000, e.state.options.first { it.kind == CourseKind.SCENIC })
        val target = e.state.targetAt!!
        e.onWaypointArrived(t0 + 2 * MINUTE_MS)
        val now = t0 + 5 * MINUTE_MS
        e.naviAt(now, target - now - 15 * MINUTE_MS)
        assertEquals(FitStatus.EARLY, e.state.status)
        assertFalse(e.state.extendOffered)
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
    fun targetChipsStepByDistance() {
        val near = DriveEngine.targetChoices(t0, 12 * MINUTE_MS)
        assertEquals(4, near.size)
        assertEquals(10 * MINUTE_MS, near[1] - near[0])
        assertEquals(0L, near[0] % (10 * MINUTE_MS))
        assertTrue(near[0] >= t0 + 22 * MINUTE_MS)
        val far = DriveEngine.targetChoices(t0, 160 * MINUTE_MS)
        assertEquals(30 * MINUTE_MS, far[1] - far[0])

        val e = engine()
        e.openMode(t0, DriveMode.ON_TIME)
        val chip = e.targetChoices(t0)[2]
        e.setTarget(t0, chip)
        assertEquals(chip, e.state.targetAt)
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
    fun goingDirectWhenEarlySaysHowEarly() {
        val e = engine().planOnTime(shiftMinutes = 10)
        e.drainEffects()
        e.goDirect(t0 + 1_000)
        val speech = e.drainEffects().filterIsInstance<DriveEffect.Speak>().single().text
        assertTrue(speech, speech.startsWith("바로 가면") && speech.contains("일찍 도착해요"))
    }

    @Test
    fun longerModeTargetFollowsChosenCourseAndDateStaysQuiet() {
        val e = engine()
        e.openMode(t0, DriveMode.LONGER)
        e.setExtraMinutes(15)
        e.setMood(Mood.DATE)
        e.plan(t0)
        assertEquals(t0 + direct + 15 * MINUTE_MS, e.state.targetAt)
        assertTrue(e.state.options.none { it.kind == CourseKind.REST_STOP })

        val option = e.state.options.first()
        e.choose(t0 + 1_000, option)
        // 롱웨이 모드의 도착 목표는 고른 코스가 걸리는 만큼
        assertEquals(t0 + 1_000 + direct + option.addedMs, e.state.targetAt)
        val effects = e.drainEffects()
        assertTrue(effects.contains(DriveEffect.CabinMood(Mood.DATE)))
        assertTrue("데이트 분위기에서는 말하지 않는다", effects.none { it is DriveEffect.Speak })
        e.onLocation(t0 + 40 * MINUTE_MS, dest.position)
        val arrived = e.drainEffects()
        assertTrue(arrived.contains(DriveEffect.CabinMood(null)))
        assertTrue(arrived.none { it is DriveEffect.Speak })
    }

    @Test
    fun longerModeToleratesDelayAndDropsOnlyWithoutAlternative() {
        val e = engine()
        e.openMode(t0, DriveMode.LONGER)
        e.setExtraMinutes(30)
        e.plan(t0)
        e.choose(t0 + 1_000, e.state.options.first { it.id == "scenic-p6" })
        e.drainEffects()
        val target = e.state.targetAt!!

        // 8분 늦어지는 건 그대로 둔다 ('늦어요'가 아니라 '약 8분 더').
        var now = t0 + MINUTE_MS
        e.naviAt(now, target - now + 8 * MINUTE_MS)
        assertEquals(FitStatus.ON_TIME, e.state.status)
        now += 2 * MINUTE_MS
        e.naviAt(now, target - now + 8 * MINUTE_MS)
        assertTrue(e.state.viaActive)

        // 10분을 넘게 늦어지면, 짧은 코스가 없으니 경유지를 뺀다. 혼자일 때는 시각 없이 짧게 말한다.
        e.naviAt(now, target - now + 13 * MINUTE_MS)
        assertEquals(FitStatus.LATE, e.state.status)
        now += 95 * SECOND_MS
        e.naviAt(now, target - now + 13 * MINUTE_MS)
        assertFalse(e.state.viaActive)
        val speech = e.drainEffects().filterIsInstance<DriveEffect.Speak>().single().text
        assertFalse(speech, speech.contains("시 "))
    }

    @Test
    fun restGuidanceKeepsWaypointUntilActuallyResting() {
        val e = engine().planOnTime()
        e.choose(t0 + 1_000, e.state.options.first { it.kind == CourseKind.SCENIC })
        e.onRestPhase(t0 + 2_000, Phase.TO_REST_AREA)
        assertTrue(e.state.paused)
        // 쉼터로 안내만 받는 동안은 경유지를 남겨 둔다 (취소하면 그 경로로 돌아감).
        assertTrue(e.state.viaActive)
        assertEquals(listOf("강변공원"), e.activeVia().map { it.name })

        e.onRestPhase(t0 + 3_000, Phase.DRIVING)
        assertFalse(e.state.paused)
        assertTrue(e.state.viaActive)
        assertEquals("쉼터 안내를 취소했어요. 원래 코스로 이어서 가요.", e.state.message)

        // 다시 안내받아 실제로 쉬면 쉰 곳이 경유지를 대신한다.
        e.onRestPhase(t0 + 4_000, Phase.TO_REST_AREA)
        e.onRestPhase(t0 + 5_000, Phase.RESTING)
        assertFalse(e.state.viaActive)
        val now = t0 + 30 * MINUTE_MS
        e.drainEffects()
        e.naviAt(now, 90 * MINUTE_MS)
        assertTrue(e.drainEffects().none { it is DriveEffect.Route })

        // 다시 출발: 지금 위치에서 이어서 맞춘다 (옛 계획으로 크게 늦다고 하지 않는다).
        e.onNaviDestination(now, dest, 10 * MINUTE_MS)
        e.onRestPhase(now + 1_000, Phase.DRIVING)
        assertFalse(e.state.paused)
        assertEquals("다시 출발했어요. 도착 시각을 이어서 맞출게요.", e.state.message)
        assertTrue(e.state.status.toString(), e.state.status != FitStatus.LATE)
    }

    @Test
    fun deadlineGivesTargetAndRemainingFromRestStop() {
        val e = engine(points = emptyList()).planOnTime(shiftMinutes = 20)
        val option = e.state.options.first { it.kind == CourseKind.REST_STOP }
        e.choose(t0 + 1_000, option)
        assertEquals(RestKind.WAIT, e.plannedRestKind)
        val at = t0 + 10 * MINUTE_MS
        e.onLocation(at, stop.position)
        e.onNaviDestination(at, dest, 6 * MINUTE_MS)
        val deadline = e.deadline()!!
        assertEquals(e.state.targetAt, deadline.targetAt)
        assertTrue(deadline.strict)
        assertEquals(3 * MINUTE_MS, deadline.marginMs)
        assertEquals(6 * MINUTE_MS, deadline.remainingMs)
    }

    @Test
    fun waitingCourseAbsorbsSmallDelays() {
        val e = engine(points = emptyList()).planOnTime(shiftMinutes = 20)
        e.choose(t0 + 1_000, e.state.options.first { it.kind == CourseKind.REST_STOP })
        val now = t0 + 5 * MINUTE_MS
        // 길이 10분 막혀도 기다리는 시간이 줄 뿐 늦지 않는다.
        e.naviAt(now, direct + 10 * MINUTE_MS)
        assertEquals(FitStatus.ON_TIME, e.state.status)
    }

    @Test
    fun restStopKeepsStayUntilParkedAndClearsIfPassed() {
        val e = engine(points = emptyList()).planOnTime(shiftMinutes = 20)
        val option = e.state.options.first { it.kind == CourseKind.REST_STOP }
        e.choose(t0 + 1_000, option)
        val at = t0 + 10 * MINUTE_MS
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
    fun bookmarkDestinationDefaultsToWaitingNearby() {
        val lot = RestArea("lot", "학원앞주차장", RestAreaType.PARKING, Geo.destinationPoint(dest.position, 0.0, 800.0))
        val academy = Place("학원", dest.position, PlaceKind.BOOKMARK)
        val e = engine(stops = listOf(lot))
        e.onPlaces(listOf(academy))
        e.openMode(t0, DriveMode.ON_TIME)
        e.setDestination(t0, academy)
        assertEquals(OnTimeStyle.WAIT_NEAR, e.state.onTimeStyle)
        e.setTarget(t0, t0 + 50 * MINUTE_MS)
        e.plan(t0)
        val option = e.state.options.single()
        assertEquals("wait-lot", option.id)
        assertEquals(RestKind.WAIT, option.restKind)

        // 근처에 기다릴 곳이 없으면 돌면서 맞추는 코스로 바꾸고 알려 준다.
        val bare = engine(stops = emptyList())
        bare.openMode(t0, DriveMode.ON_TIME)
        bare.setOnTimeStyle(OnTimeStyle.WAIT_NEAR)
        bare.setTarget(t0, t0 + 45 * MINUTE_MS)
        bare.plan(t0)
        assertTrue(bare.state.options.any { it.kind == CourseKind.SCENIC })
        assertTrue(bare.state.message.orEmpty().contains("기다릴 곳이 없어"))
    }

    @Test
    fun onTimeChipAddsTimeAndFillsIt() {
        val e = engine().planOnTime()
        e.goDirect(t0 + 1_000)
        e.drainEffects()
        val before = e.state.targetAt!!
        e.addTime(t0 + 2 * MINUTE_MS, 10)
        assertEquals(before + 10 * MINUTE_MS, e.state.targetAt)
        assertTrue(e.state.viaActive)
        val effects = e.drainEffects()
        assertTrue(effects.filterIsInstance<DriveEffect.Route>().single().via.isNotEmpty())
        assertEquals(1, effects.count { it is DriveEffect.Speak })
    }

    @Test
    fun longerChipsAddTimeAndGoStraightHome() {
        val far = CoursePoint("far", "먼공원", Geo.destinationPoint(southKm(5.0), 90.0, 9_000.0), emptySet())
        val e = engine(points = listOf(p6, far))
        e.openMode(t0, DriveMode.LONGER)
        e.setExtraMinutes(15)
        e.plan(t0)
        e.choose(t0 + 1_000, e.state.options.first { it.id == "scenic-p6" })
        val before = e.state.targetAt!!
        // +15분 더: 더 긴 코스로 바꾸고, 그 코스가 걸리는 만큼을 새 예정으로
        e.addTime(t0 + MINUTE_MS, 15)
        assertEquals("scenic-far", e.state.chosen?.id)
        assertEquals(30, e.state.extraMinutes)
        assertTrue(e.state.targetAt!! > before + 5 * MINUTE_MS)
        // 더 긴 코스가 없으면 그대로 두고 알려 준다.
        val target = e.state.targetAt
        e.addTime(t0 + 2 * MINUTE_MS, 15)
        assertEquals("scenic-far", e.state.chosen?.id)
        assertEquals(target, e.state.targetAt)

        // '바로 집으로': 경유지를 빼고 그때 도착 시각을 새 예정으로
        val now = t0 + 3 * MINUTE_MS
        e.onNaviDestination(now, dest, 12 * MINUTE_MS)
        e.goDirect(now)
        assertFalse(e.state.viaActive)
        assertTrue(e.state.targetAt!! in now + 10 * MINUTE_MS..now + 40 * MINUTE_MS)
    }

    @Test
    fun finishWithWaypointRoutesStraightToDestination() {
        val e = engine().planOnTime()
        e.choose(t0 + 1_000, e.state.options.first { it.kind == CourseKind.SCENIC })
        e.drainEffects()
        e.finish(t0 + 2_000)
        val route = e.drainEffects().filterIsInstance<DriveEffect.Route>().single()
        assertEquals(dest, route.destination)
        assertTrue(route.via.isEmpty())
        assertEquals(DriveStep.HOME, e.state.step)
    }

    @Test
    fun savedTripReplansInOneTapAndSkipsPastTimes() {
        val e = engine()
        e.openMode(t0, DriveMode.ON_TIME)
        e.setOnTimeStyle(OnTimeStyle.WAIT_NEAR)
        e.setTarget(t0, t0 + 50 * MINUTE_MS)
        e.plan(t0)
        val saved = e.saveable()!!
        assertEquals(DriveMode.ON_TIME, saved.mode)
        assertEquals(PlaceKind.RECENT, saved.place.kind)
        assertEquals(DriveText.minuteOfDay(t0 + 50 * MINUTE_MS), saved.minuteOfDay)
        assertEquals(OnTimeStyle.WAIT_NEAR, saved.onTimeStyle)
        e.finish(t0)

        e.applySaved(t0 + 1_000, saved)
        assertEquals(DriveStep.CHOOSE, e.state.step)
        assertEquals(DriveText.todayAt(t0, saved.minuteOfDay!!), e.state.targetAt)
        assertEquals(OnTimeStyle.WAIT_NEAR, e.state.onTimeStyle)

        e.finish(t0 + 2_000)
        e.applySaved(t0 + 3_000, saved.copy(minuteOfDay = DriveText.minuteOfDay(t0 - 30 * MINUTE_MS)))
        assertEquals(DriveStep.SETUP, e.state.step)
        assertTrue(e.state.message.orEmpty().contains("지난 시각"))
    }

    @Test
    fun longerFlavorChipReplans() {
        val bridge = CoursePoint("br", "다리", Geo.destinationPoint(southKm(5.0), 270.0, 5_000.0), setOf(CourseTag.BRIDGE))
        val e = engine(points = listOf(p6, bridge))
        e.openMode(t0, DriveMode.LONGER)
        e.setExtraMinutes(15)
        e.plan(t0)
        e.setFlavor(t0 + 1_000, CourseFlavor.RIVER)
        assertEquals("scenic-p6", e.state.options.first().id)
        e.setFlavor(t0 + 2_000, CourseFlavor.BRIDGE)
        assertEquals(CourseFlavor.BRIDGE, e.state.flavor)
        assertEquals("scenic-br", e.state.options.first().id)
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
