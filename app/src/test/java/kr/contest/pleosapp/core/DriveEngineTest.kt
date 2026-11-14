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
        assertTrue(target >= t0 + direct + 10 * MINUTE_MS)
        // 기본 목표는 시각 칩 가운데 하나라 칩에 ◀ 표시가 붙는다.
        assertTrue(target in e.targetChoices(t0))
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
        // 잠깐 막힌 것일 수 있어 바로 바꾸지 않는다. 화면에는 언제부터 지켜보는지 보여 준다.
        assertTrue(e.state.viaActive)
        assertEquals(now, e.state.lateSince)
        assertTrue(e.drainEffects().none { it is DriveEffect.Route })

        now += 95 * SECOND_MS
        e.naviAt(now, target - now + 5 * MINUTE_MS)
        assertNull(e.state.lateSince)
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

    /** 남는 시간이 없으면 설정 화면의 '바로 가기'로 후보 화면을 거치지 않고 바로 출발한다 */
    @Test
    fun goDirectFromSetupWhenThereIsNoSpareTime() {
        val e = engine()
        e.openMode(t0, DriveMode.ON_TIME)
        // 바로 가도 늦는 목표 (지금부터 10분 뒤, 바로 가면 20분)
        e.setTarget(t0, t0 + 10 * MINUTE_MS)
        e.drainEffects()
        e.goDirect(t0 + 1_000)
        assertEquals(DriveStep.ACTIVE, e.state.step)
        assertEquals(CourseKind.DIRECT, e.state.chosen?.kind)
        assertFalse(e.state.viaActive)
        assertTrue(e.state.options.isEmpty())
        assertTrue(e.drainEffects().filterIsInstance<DriveEffect.Route>().all { it.via.isEmpty() })
        // 바로 가는 시간을 모르면 누를 수 없다 (설정 화면 그대로)
        val unknown = DriveEngine(listOf(p6), listOf(stop)).apply { onLocation(t0, origin) }
        unknown.openMode(t0, DriveMode.ON_TIME)
        unknown.setDestination(t0, dest)
        if (unknown.state.directMs == null) {
            unknown.goDirect(t0 + 1_000)
            assertEquals(DriveStep.SETUP, unknown.state.step)
        }
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
        val e = engine().planOnTime(shiftMinutes = 10)
        e.goDirect(t0 + 1_000)
        assertEquals(DriveStep.ACTIVE, e.state.step)
        e.drainEffects()
        val target = e.state.targetAt!!

        // 바로 가기를 막 골랐으면 3분은 권하지 않는다.
        e.naviAt(t0 + 60 * SECOND_MS, direct)
        assertFalse(e.state.extendOffered)

        // 바로 가면 20분 걸리는데 목표까지 40분 넘게 남음
        val now = t0 + 4 * MINUTE_MS
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
    fun stillEarlyAfterPassingWaypointOffersAFartherPointRightAway() {
        // 목적지 쪽으로 서쪽에 있는 지점: 출발할 때는 너무 짧아(남는 시간의 35% 미만) 후보가 아니다.
        val west = CoursePoint("pw", "서쪽공원", Geo.destinationPoint(southKm(8.0), 270.0, 4_000.0), setOf(CourseTag.PARK))
        val e = engine(points = listOf(p6, west)).planOnTime(shiftMinutes = 10)
        assertEquals(listOf("scenic-p6"), e.state.options.filter { it.kind == CourseKind.SCENIC }.map { it.id })
        e.choose(t0 + 1_000, e.state.options.first { it.id == "scenic-p6" })
        val passedAt = t0 + 12 * MINUTE_MS
        e.onLocation(passedAt, p6.position)
        e.onWaypointArrived(passedAt)
        e.naviAt(passedAt + MINUTE_MS, 14 * MINUTE_MS)
        assertEquals(FitStatus.EARLY, e.state.status)
        // 경유지를 지난 것은 경로를 바꾼 게 아니라서 바로 권한다.
        assertTrue(e.state.extendOffered)
        assertEquals("scenic-pw", e.state.extendOption?.id)
        assertTrue(e.state.message.orEmpty(), e.state.message.orEmpty().contains("서쪽공원을 거쳐 가면"))
    }

    @Test
    fun extendIsNotOfferedBeforeTheChosenWaypoint() {
        val far = CoursePoint("far", "먼공원", Geo.destinationPoint(southKm(7.0), 90.0, 8_000.0), setOf(CourseTag.PARK))
        val e = engine(points = listOf(p6, far)).planOnTime(shiftMinutes = 15)
        e.choose(t0 + 1_000, e.state.options.first { it.id == "scenic-p6" })
        val target = e.state.targetAt!!
        // 강변공원으로 가는 중에는 15분 일찍 닿을 것 같아도 권하지 않는다: 바꾸면 고른 경유지를 건너뛴다.
        // (경유지를 지난 뒤에 남는 시간이 있으면 그때 권한다)
        val now = t0 + 5 * MINUTE_MS
        e.naviAt(now, target - now - 15 * MINUTE_MS)
        assertTrue(e.state.viaActive)
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
        val mid = DriveEngine.targetChoices(t0, 45 * MINUTE_MS)
        assertEquals(30 * MINUTE_MS, mid[1] - mid[0])
        // 먼 길은 1시간 간격: 10시 30분에 2시간 15분 거리면 1시·2시·3시·4시 (3시 체크인을 칩으로)
        val far = DriveEngine.targetChoices(t0, 160 * MINUTE_MS)
        assertEquals(HOUR_MS, far[1] - far[0])
        assertEquals(0L, far[0] % HOUR_MS)

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
        // 경유지가 남아 있으면 목적지 옆을 지나는 것과 구분해 1분 머물러야 도착으로 본다.
        e.onLocation(t0 + 40 * MINUTE_MS, dest.position)
        assertEquals(DriveStep.ACTIVE, e.state.step)
        e.onLocation(t0 + 41 * MINUTE_MS, dest.position)
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

    /** 온타임 모드로 강변공원 코스를 골라 출발 (목표 45분 뒤) */
    private fun scenicTrip(): DriveEngine {
        val e = engine()
        e.openMode(t0, DriveMode.ON_TIME)
        e.setTarget(t0, t0 + 45 * MINUTE_MS)
        e.plan(t0)
        e.choose(t0 + 1_000, e.state.options.first { it.id == "scenic-p6" })
        return e
    }

    private fun session(at: Long, kind: RestKind = RestKind.WAIT, fatigue: Boolean = false, area: RestArea? = null) =
        RestSession(startedAt = at, plannedMinutes = 10, alarmAt = at + 10 * MINUTE_MS, kind = kind, fatigue = fatigue, area = area)

    @Test
    fun restGuidanceKeepsWaypointAndResumeKeepsItWhileItStillFits() {
        val e = scenicTrip()
        e.onRestPhase(t0 + 2_000, Phase.TO_REST_AREA)
        assertTrue(e.state.paused)
        // 쉼터로 안내만 받는 동안은 경유지를 남겨 둔다 (취소하면 그 경로로 돌아감).
        assertTrue(e.state.viaActive)
        assertEquals(listOf("강변공원"), e.activeVia().map { it.name })

        e.onRestPhase(t0 + 3_000, Phase.DRIVING)
        assertFalse(e.state.paused)
        assertTrue(e.state.viaActive)
        assertEquals("쉼터 안내를 취소했어요. 원래 코스로 이어서 가요.", e.state.message)

        // 다른 곳에서 잠깐 쉬어도 경유지는 남겨 두고, 다시 출발할 때 아직 맞으면 그대로 간다.
        e.onRestPhase(t0 + 4_000, Phase.TO_REST_AREA)
        e.onRestPhase(t0 + 5_000, Phase.RESTING, session(t0 + 5_000))
        assertTrue(e.state.viaActive)
        val now = t0 + 8 * MINUTE_MS
        e.drainEffects()
        e.naviAt(now, 90 * MINUTE_MS)
        assertTrue(e.drainEffects().none { it is DriveEffect.Route })

        e.onNaviDestination(now, dest, direct)
        e.onRestPhase(now + 1_000, Phase.DRIVING)
        assertFalse(e.state.paused)
        assertTrue(e.state.viaActive)
        assertEquals("다시 출발했어요. 강변공원을 거쳐 가요.", e.state.message)
        assertTrue(e.state.status.toString(), e.state.status != FitStatus.LATE)
        assertEquals(listOf("강변공원"), e.activeVia().map { it.name })
    }

    @Test
    fun afterDrowsyRestScenicWaypointIsDroppedAndSaidSo() {
        val e = scenicTrip()
        e.onRestPhase(t0 + 2_000, Phase.TO_REST_AREA)
        e.onRestPhase(t0 + 3_000, Phase.RESTING, session(t0 + 3_000, RestKind.NAP, fatigue = true))
        val now = t0 + 6 * MINUTE_MS
        e.onNaviDestination(now, dest, direct)
        e.drainEffects()
        e.onRestPhase(now, Phase.DRIVING)
        assertFalse(e.state.viaActive)
        assertTrue(e.state.message.orEmpty(), e.state.message.orEmpty().contains("경유지는 빼고"))
        val speech = e.drainEffects().filterIsInstance<DriveEffect.Speak>().single().text
        assertTrue(speech, speech.contains("바로 가요"))
        // 졸려서 쉰 뒤에는 경치 길로 더 돌자고 하지 않는다.
        val later = now + 5 * MINUTE_MS
        e.naviAt(later, 8 * MINUTE_MS)
        assertEquals(FitStatus.EARLY, e.state.status)
        assertFalse(e.state.extendOffered)
    }

    @Test
    fun cancellingRestGuidanceShowsTheOriginalCoursePlanNotTheDetourToTheShelter() {
        val e = scenicTrip()
        val shelter = RestArea("west", "서쪽쉼터", RestAreaType.DROWSY_SHELTER, Geo.destinationPoint(southKm(3.0), 270.0, 8_000.0))
        val at = t0 + 2 * MINUTE_MS
        e.onRestPhase(at, Phase.TO_REST_AREA, restTarget = shelter)
        // 쉼터를 거치는 내비 경로라 남은 시간이 크게 늘었다.
        e.onNaviDestination(at, dest, 50 * MINUTE_MS)
        val deadline = e.deadline()!!
        assertTrue("바로 가는 시간은 쉼터를 빼고: ${deadline.remainingMs!! / MINUTE_MS}분", deadline.remainingMs!! < 40 * MINUTE_MS)
        e.onRestPhase(at + 1_000, Phase.DRIVING)
        val eta = e.state.arrivalEstimate!!
        assertTrue("취소 직후 도착 예상: ${(eta - at) / MINUTE_MS}분 뒤", eta < at + 50 * MINUTE_MS)
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
    fun restingAtThePlannedStopFinishesTheWaypoint() {
        val e = engine(points = emptyList()).planOnTime(shiftMinutes = 20)
        e.choose(t0 + 1_000, e.state.options.first { it.kind == CourseKind.REST_STOP })
        val at = t0 + 10 * MINUTE_MS
        e.onLocation(at, stop.position)
        e.onRestPhase(at, Phase.RESTING, session(at, area = stop))
        assertFalse(e.state.viaActive)
        e.onNaviDestination(at + 20 * MINUTE_MS, dest, 6 * MINUTE_MS)
        e.drainEffects()
        e.onRestPhase(at + 20 * MINUTE_MS, Phase.DRIVING)
        assertEquals("다시 출발했어요. 약속 장소로 가요.", e.state.message)
        // 계획대로 쉬고 출발하면 경로 이야기는 따로 하지 않는다 (레스트 모드가 '다시 출발하시네요'라고 함).
        assertTrue(e.drainEffects().none { it is DriveEffect.Speak })
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
    fun waitingStopIsDroppedWhenThereIsNoTimeLeftToWait() {
        val e = engine(points = emptyList()).planOnTime(shiftMinutes = 20)
        val option = e.state.options.first { it.kind == CourseKind.REST_STOP }
        e.choose(t0 + 1_000, option)
        e.drainEffects()
        val target = e.state.targetAt!!
        // 길이 막혀 쉼터를 거쳐 가면 기다릴 시간이 1분밖에 안 남는다.
        var now = t0 + 2 * MINUTE_MS
        e.naviAt(now, target - 3 * MINUTE_MS - now - MINUTE_MS)
        assertTrue(e.state.viaActive)
        now += 61 * SECOND_MS
        e.naviAt(now, target - 3 * MINUTE_MS - now - MINUTE_MS)
        assertFalse(e.state.viaActive)
        val effects = e.drainEffects()
        assertTrue(effects.filterIsInstance<DriveEffect.Route>().single().via.isEmpty())
        assertTrue(effects.filterIsInstance<DriveEffect.Speak>().single().text.contains("기다릴 시간이 없어졌어요"))
    }

    @Test
    fun waitingStopNextToTheDestinationIsNotMistakenForArrival() {
        // 목적지에서 110m 떨어진 공영주차장에서 기다리는 코스: 주차장에 닿아도 도착이 아니다.
        val lot = RestArea("lot", "옆주차장", RestAreaType.PARKING, Geo.destinationPoint(dest.position, 0.0, 110.0))
        val e = engine(points = emptyList(), stops = listOf(lot))
        e.openMode(t0, DriveMode.ON_TIME)
        e.setOnTimeStyle(OnTimeStyle.WAIT_NEAR)
        e.setTarget(t0, t0 + 50 * MINUTE_MS)
        e.plan(t0)
        e.choose(t0 + 1_000, e.state.options.single())
        e.onLocation(t0 + 20 * MINUTE_MS, lot.position)
        assertEquals(DriveStep.ACTIVE, e.state.step)
        assertEquals(lot, e.restStopHere(lot.position))
    }

    @Test
    fun waitingCourseShowsWhenToArriveAndLeaveTheStop() {
        val e = engine(points = emptyList()).planOnTime(shiftMinutes = 20)
        e.choose(t0 + 1_000, e.state.options.first { it.kind == CourseKind.REST_STOP })
        val now = t0 + 2 * MINUTE_MS
        e.naviAt(now, direct)
        val arrive = e.state.stopArriveAt!!
        val leave = e.state.stopDepartAt!!
        assertTrue(arrive in now..leave)
        // 쉼터에서 떠나 목적지까지 가면 '목표 − 여유'에 닿는다.
        assertTrue(leave < e.state.targetAt!! - 3 * MINUTE_MS)
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
    fun addingTimeWhileWaitingOnlyMovesTheTarget() {
        val e = engine(points = emptyList()).planOnTime(shiftMinutes = 20)
        e.choose(t0 + 1_000, e.state.options.first { it.kind == CourseKind.REST_STOP })
        val before = e.state.targetAt!!
        e.onRestPhase(t0 + 10 * MINUTE_MS, Phase.RESTING, session(t0 + 10 * MINUTE_MS))
        e.drainEffects()
        e.addTime(t0 + 11 * MINUTE_MS, 10)
        assertEquals(before + 10 * MINUTE_MS, e.state.targetAt)
        assertEquals(before + 10 * MINUTE_MS, e.deadline()!!.targetAt)
        val effects = e.drainEffects()
        assertTrue(effects.none { it is DriveEffect.Route })
        // 말은 레스트 모드가 새 출발 시각과 함께 한 번에 한다.
        assertTrue(effects.none { it is DriveEffect.Speak })
    }

    @Test
    fun addingTimeKeepsTheSameKindOfCourse() {
        // 경치 코스로 가는 중 +10분: 쉬다 가는 곳이 더 길게 채워도 경치 코스로만 바꾼다.
        val far = CoursePoint("far", "먼공원", Geo.destinationPoint(southKm(5.0), 90.0, 8_000.0), setOf(CourseTag.PARK))
        val e = engine(points = listOf(p6, far)).planOnTime(shiftMinutes = 10)
        e.choose(t0 + 1_000, e.state.options.first { it.id == "scenic-p6" })
        e.naviAt(t0 + MINUTE_MS, direct + 11 * MINUTE_MS)
        e.addTime(t0 + 2 * MINUTE_MS, 10)
        assertEquals(CourseKind.SCENIC, e.state.chosen?.kind)
    }

    @Test
    fun lateByMoreThanHalfAMinuteIsLate() {
        val e = engine().planOnTime()
        e.goDirect(t0 + 1_000)
        val target = e.state.targetAt!!
        val now = t0 + MINUTE_MS
        e.naviAt(now, target - now + MINUTE_MS)
        assertEquals(FitStatus.LATE, e.state.status)
        e.naviAt(now + 1_000, target - now - 1_000 + 20 * SECOND_MS)
        assertEquals(FitStatus.ON_TIME, e.state.status)
    }

    @Test
    fun startingLateSaysSoOnceAndDoesNotBlameTraffic() {
        val e = engine()
        e.openMode(t0, DriveMode.ON_TIME)
        e.setTarget(t0, t0 + 10 * MINUTE_MS)
        e.plan(t0)
        e.drainEffects()
        e.goDirect(t0 + 1_000)
        val start = e.drainEffects().filterIsInstance<DriveEffect.Speak>().single().text
        assertTrue(start, start.startsWith("바로 가도 약") && start.contains("늦어요"))
        var now = t0 + MINUTE_MS
        val target = e.state.targetAt!!
        e.naviAt(now, target - now + 10 * MINUTE_MS)
        now += 95 * SECOND_MS
        e.naviAt(now, target - now + 10 * MINUTE_MS)
        assertTrue(e.drainEffects().none { it is DriveEffect.Speak })
        assertTrue(e.state.message.orEmpty().contains("길이 막혀").not())
    }

    @Test
    fun earlyCourseSaysWhenItWillReallyArrive() {
        val e = engine().planOnTime(shiftMinutes = 10)
        e.drainEffects()
        e.choose(t0 + 1_000, e.state.options.first { it.id == "scenic-p6" })
        val speech = e.drainEffects().filterIsInstance<DriveEffect.Speak>().single().text
        assertTrue(speech, speech.startsWith("강변공원을 거쳐 가요.") && speech.contains("일찍 도착해요"))
    }

    @Test
    fun savedTimeJustAfterMidnightMeansTomorrow() {
        val e = engine()
        val lateNight = DriveText.todayAt(t0, 23 * 60 + 40)
        val trip = SavedTrip(DriveMode.ON_TIME, dest, minuteOfDay = 10, onTimeStyle = OnTimeStyle.LOOP)
        e.applySaved(lateNight, trip)
        assertEquals(DriveStep.CHOOSE, e.state.step)
        assertEquals(lateNight + 30 * MINUTE_MS, e.state.targetAt)
    }

    @Test
    fun choicesAreRefreshedWhileWaitingOnTheChooseScreen() {
        val e = engine().planOnTime()
        val first = e.state.options.first { it.kind == CourseKind.SCENIC }
        // 10분 동안 고르지 않으면 남는 시간이 줄어 강변공원 코스가 맞지 않게 된다.
        for (m in 1..10) e.naviAt(t0 + m * MINUTE_MS, direct)
        assertEquals(DriveStep.CHOOSE, e.state.step)
        assertTrue(e.state.options.none { it.id == first.id })
    }

    @Test
    fun longerGoStraightHomeTakesTheNaviTimeAsTheNewPlan() {
        val e = engine()
        e.openMode(t0, DriveMode.LONGER)
        e.setExtraMinutes(15)
        e.plan(t0)
        e.choose(t0 + 1_000, e.state.options.first { it.id == "scenic-p6" })
        val now = t0 + 3 * MINUTE_MS
        e.onNaviDestination(now, dest, 30 * MINUTE_MS)
        e.goDirect(now)
        // 내비가 새 경로를 계산하면 그 도착 시각이 새 예정이다 ('약 N분 더'가 바로 뜨지 않게).
        e.naviAt(now + 30 * SECOND_MS, 17 * MINUTE_MS)
        assertEquals(now + 30 * SECOND_MS + 17 * MINUTE_MS, e.state.targetAt)
        assertEquals(0L, e.state.diffMs)
    }

    @Test
    fun longerSoloSpeaksOnlyWhenTheRouteChanges() {
        val e = engine()
        e.openMode(t0, DriveMode.LONGER)
        e.setExtraMinutes(15)
        e.plan(t0)
        e.drainEffects()
        e.choose(t0 + 1_000, e.state.options.first { it.id == "scenic-p6" })
        assertTrue(e.drainEffects().none { it is DriveEffect.Speak })
        e.onLocation(t0 + 40 * MINUTE_MS, dest.position)
        e.onLocation(t0 + 41 * MINUTE_MS, dest.position)
        assertEquals(DriveStep.ARRIVED, e.state.step)
        assertTrue(e.drainEffects().none { it is DriveEffect.Speak })
    }

    @Test
    fun nightViewStartsEarlierInWinter() {
        assertEquals(CourseFlavor.NIGHT_VIEW, CourseFlavor.auto(17, Mood.DATE, month = 11))
        assertEquals(CourseFlavor.RIVER, CourseFlavor.auto(17, Mood.DATE, month = 6))
        assertEquals(CourseFlavor.RIVER, CourseFlavor.auto(18, Mood.DATE, month = 7))
        assertEquals(CourseFlavor.NIGHT_VIEW, CourseFlavor.auto(19, Mood.SOLO, month = 7))
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
        // 조금 전(1시간 안)에 지난 시각이면 늦게 나온 날이다: 그 시각을 그대로 두고 바로 가는 길을 보여 준다.
        val justPassed = DriveText.minuteOfDay(t0 - 10 * MINUTE_MS)
        e.applySaved(t0 + 3_000, saved.copy(minuteOfDay = justPassed))
        assertEquals(DriveStep.CHOOSE, e.state.step)
        assertEquals(DriveText.todayAt(t0, justPassed), e.state.targetAt)
        assertEquals(listOf(CourseKind.DIRECT), e.state.options.map { it.kind })
        assertTrue(e.state.message.orEmpty(), e.state.message.orEmpty().contains("이미 지났어요"))

        e.finish(t0 + 4_000)
        // 한참 전에 지났으면 오늘은 지났다고 하고 시각만 다시 고르게 한다 (다음 날로 보는 것은 12시간 안일 때만).
        e.applySaved(t0 + 5_000, saved.copy(minuteOfDay = DriveText.minuteOfDay(t0 - 2 * HOUR_MS)))
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

    @Test
    fun addingTimeWithoutALongerCourseSaysHowEarly() {
        // 경치 코스로 가는 중 +10분인데 더 돌 경치 코스가 없다: 목표만 미루고, 그만큼 일찍 닿는 것을 숨기지 않는다.
        val e = engine().planOnTime()
        e.choose(t0 + 1_000, e.state.options.first { it.id == "scenic-p6" })
        e.drainEffects()
        e.addTime(t0 + MINUTE_MS, 10)
        assertEquals("scenic-p6", e.state.chosen?.id)
        assertTrue(e.state.message.orEmpty(), e.state.message.orEmpty().contains("일찍 도착해요"))
        val said = e.drainEffects().filterIsInstance<DriveEffect.Speak>().single().text
        assertTrue(said, said.startsWith("도착 목표를") && said.contains("일찍 도착해요"))
    }

    @Test
    fun lateSpeechOnlyWhenMoreThanTwoMinutesLate() {
        val e = engine().planOnTime()
        e.goDirect(t0 + 1_000)
        e.drainEffects()
        val target = e.state.targetAt!!
        // 1분 늦을 것 같은 상태가 이어져도 화면 칩만 (내비 예상은 1분쯤 흔들린다)
        var now = t0 + MINUTE_MS
        e.naviAt(now, target - now + MINUTE_MS)
        now += 2 * MINUTE_MS
        e.naviAt(now, target - now + MINUTE_MS)
        assertEquals(FitStatus.LATE, e.state.status)
        assertTrue(e.drainEffects().none { it is DriveEffect.Speak })
        // 3분 늦을 것 같으면 한 번 말한다.
        now += MINUTE_MS
        e.naviAt(now, target - now + 3 * MINUTE_MS)
        val said = e.drainEffects().filterIsInstance<DriveEffect.Speak>().single().text
        assertTrue(said, said.contains("늦을 것 같아요"))
    }

    @Test
    fun longerRestCountsTheRemainingWaypoint() {
        // 롱웨이 모드는 쉬고 나서 남은 경유지를 거쳐 가므로, 쉬는 동안의 남은 시간에도 경유지를 넣는다
        // (쉬는 화면의 '지금 출발하면 ○ 도착'이 출발 뒤 도착 예상과 맞게).
        val e = engine()
        e.openMode(t0, DriveMode.LONGER)
        e.setExtraMinutes(30)
        e.plan(t0)
        e.choose(t0 + 1_000, e.state.options.first { it.id == "scenic-p6" })
        val now = t0 + 3 * MINUTE_MS
        val rest = RestSession(startedAt = now, plannedMinutes = 10, alarmAt = now + 10 * MINUTE_MS, kind = RestKind.STAY)
        e.onRestPhase(now, Phase.RESTING, rest)
        e.onNaviDestination(now, dest, 15 * MINUTE_MS)
        e.onTick(now + 1_000)
        val remaining = e.deadline()!!.remainingMs!!
        assertTrue(remaining.toString(), remaining > 15 * MINUTE_MS + 5 * MINUTE_MS)
    }

    @Test
    fun longWaitSaysWhenLeavingLaterStillFits() {
        val e = engine()
        e.openMode(t0, DriveMode.ON_TIME)
        e.setTarget(t0, t0 + 2 * 60 * MINUTE_MS)
        e.plan(t0)
        // 2시간 뒤 도착 목표, 바로 가면 20분, 여유 3분 → 97분 뒤에 떠나도 맞는다 (분은 내려서).
        val latest = DriveText.clock(t0 + 97 * MINUTE_MS - 30 * SECOND_MS)
        val message = e.state.message.orEmpty()
        assertTrue(message, message.contains("지금 길이면 ${latest}에 출발해도 맞아요."))
    }

    @Test
    fun shortWaitDoesNotSayLeaveLater() {
        val e = engine().planOnTime()
        assertFalse(e.state.message.orEmpty().contains("출발해도 맞아요"))
    }
}
