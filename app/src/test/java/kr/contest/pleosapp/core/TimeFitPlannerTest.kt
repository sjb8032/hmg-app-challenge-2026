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
        val options = TimeFitPlanner.options(origin, dest, direct, 17 * MINUTE_MS, points, emptyList(), config)
        assertEquals(listOf("scenic-p6"), options.map { it.id })
        val added = options.first().addedMs
        assertTrue("약 11분 더 걸려야 함: $added", added in 9 * MINUTE_MS..13 * MINUTE_MS)
    }

    @Test
    fun scenicPointsBeatSlightlyBetterFill() {
        val plain = point("plain", sideKm(6.5))
        val river = Geo.destinationPoint(southKm(5.0), 270.0, 6.0 * 1000.0)
        val options = TimeFitPlanner.options(
            origin, dest, direct, 17 * MINUTE_MS,
            listOf(plain, point("river", river, CourseTag.RIVER)), emptyList(), config,
        )
        assertEquals("scenic-river", options.first().id)
        assertEquals(2, options.size)
        assertEquals(setOf(CourseTag.RIVER), options.first().tags)
    }

    @Test
    fun chosenFlavorWinsOverBetterFill() {
        val river = point("river", sideKm(6.5), CourseTag.RIVER)
        val bridge = point("bridge", Geo.destinationPoint(southKm(5.0), 270.0, 4_500.0), CourseTag.BRIDGE)
        val plain = TimeFitPlanner.options(origin, dest, direct, 17 * MINUTE_MS, listOf(river, bridge), emptyList(), config)
        assertEquals("scenic-river", plain.first().id)
        val wish = CourseWish(prefer = CourseFlavor.BRIDGE.tags)
        val preferred = TimeFitPlanner.options(origin, dest, direct, 17 * MINUTE_MS, listOf(river, bridge), emptyList(), config, wish = wish)
        assertEquals("scenic-bridge", preferred.first().id)
    }

    @Test
    fun avoidCityPushesDowntownPointsBack() {
        // 강남역 근처 지점과 한강 쪽 지점 (둘 다 야경): 그냥 고르면 강남역 쪽, 도심 피하기면 한강 쪽이 앞
        val from = LatLng(37.5110, 127.0214)
        val to = Place("광화문", LatLng(37.5717, 126.9768))
        val downtown = CoursePoint("downtown", "도심 지점", LatLng(37.4990, 127.0280), setOf(CourseTag.NIGHT_VIEW))
        val riverside = CoursePoint("riverside", "강변 지점", LatLng(37.5300, 126.9600), setOf(CourseTag.NIGHT_VIEW))
        val points = listOf(downtown, riverside)
        val plain = TimeFitPlanner.options(from, to, 20 * MINUTE_MS, 15 * MINUTE_MS, points, emptyList(), config)
        assertEquals("scenic-downtown", plain.first().id)
        val avoid = TimeFitPlanner.options(from, to, 20 * MINUTE_MS, 15 * MINUTE_MS, points, emptyList(), config, wish = CourseWish(avoidCity = true))
        assertEquals("scenic-riverside", avoid.first().id)
    }

    @Test
    fun avoidCityAlsoAvoidsPointsReachedThroughCityHall() {
        // 논현 → 잠실: 북악 팔각정은 시청에서 4km 넘게 떨어져 있지만 가는 길이 종로를 지나므로 뒤로 미룬다.
        val from = LatLng(37.5110, 127.0214)
        val home = Place("집", LatLng(37.5145, 127.1059))
        val bugak = CoursePoint("bugak", "팔각정", LatLng(37.6023, 126.9810), setOf(CourseTag.NIGHT_VIEW, CourseTag.MOUNTAIN))
        val river = CoursePoint("nodeul", "노들섬", LatLng(37.5176, 126.9594), setOf(CourseTag.RIVER, CourseTag.NIGHT_VIEW, CourseTag.BRIDGE))
        val wish = CourseWish(prefer = CourseFlavor.AVOID_CITY.tags, avoidCity = true)
        val options = TimeFitPlanner.options(from, home, 25 * MINUTE_MS, 90 * MINUTE_MS, listOf(bugak, river), emptyList(), config, wish = wish)
        assertEquals("scenic-nodeul", options.first().id)
        // 도심 피하기가 아니면 더 오래 달리는 팔각정이 앞이다.
        val plain = TimeFitPlanner.options(from, home, 25 * MINUTE_MS, 90 * MINUTE_MS, listOf(bugak, river), emptyList(), config, wish = CourseWish(prefer = CourseFlavor.AVOID_CITY.tags))
        assertEquals("scenic-bugak", plain.first().id)
    }

    @Test
    fun shortSlackGoesDirect() {
        val options = TimeFitPlanner.options(
            origin, dest, direct, 3 * MINUTE_MS, listOf(point("p6", sideKm(6.0))), emptyList(), config,
        )
        assertEquals(listOf(CourseKind.DIRECT), options.map { it.kind })
    }

    @Test
    fun longSlackAddsRestStopAheadWithStayTime() {
        val ahead = RestArea("ahead", "앞쪽휴게소", RestAreaType.SERVICE_AREA, Geo.destinationPoint(southKm(6.0), 90.0, 300.0))
        val behind = RestArea("behind", "뒤쪽휴게소", RestAreaType.SERVICE_AREA, LatLng(37.03, 127.0))
        val slack = 40 * MINUTE_MS
        val options = TimeFitPlanner.options(origin, dest, direct, slack, emptyList(), listOf(behind, ahead), config)
        val rest = options.single { it.kind == CourseKind.REST_STOP }
        assertEquals("rest-ahead", rest.id)
        assertEquals(slack, rest.addedMs)
        assertTrue(rest.stayMs in 35 * MINUTE_MS..slack)
    }

    @Test
    fun longTripRestsAtServiceAreaAroundTheMiddleNotCityParkNearStart() {
        val far = Place("강릉", southKm(150.0))
        val park = RestArea("park", "출발지공원", RestAreaType.PARK, southKm(3.0))
        val early = TestAreas.area("early", southKm(30.0))
        val middle = TestAreas.area("middle", southKm(90.0))
        val options = TimeFitPlanner.options(
            origin, far, 160 * MINUTE_MS, 40 * MINUTE_MS, emptyList(), listOf(park, early, middle), config,
        )
        assertEquals("rest-middle", options.first { it.kind == CourseKind.REST_STOP }.id)
        assertTrue(options.none { it.id == "rest-park" })
    }

    @Test
    fun longTripSecondRestStopSitsElsewhereOnTheRoute() {
        // 먼 길은 모두 휴게소라 종류 대신 길 위 위치가 다른 곳을 둘째로 낸다 (60% 근처와 그보다 앞쪽).
        val far = Place("강릉", southKm(150.0))
        val middle = TestAreas.area("middle", southKm(90.0))
        val nextDoor = TestAreas.area("nextdoor", southKm(93.0))
        val later = TestAreas.area("later", southKm(120.0))
        val options = TimeFitPlanner.options(
            origin, far, 160 * MINUTE_MS, 80 * MINUTE_MS, emptyList(), listOf(middle, nextDoor, later), config,
            maxAddedMs = 35 * MINUTE_MS,
        )
        val rests = options.filter { it.kind == CourseKind.REST_STOP }.map { it.id }
        assertEquals(listOf("rest-middle", "rest-later"), rests)
        // 기다리는 시간이 한 시간을 넘으면 시간과 분으로 적는다.
        assertTrue(options.first().reason, options.first().reason.contains("1시간 20분"))
    }

    @Test
    fun onTimeLongTripSkipsPointsBeyondTheDestination() {
        // 목적지를 지나쳤다 되돌아오는 바닷가(길의 105%)는 온타임 모드에서 빼고, 롱웨이 모드에서는 본다.
        val far = Place("강릉 숙소", southKm(150.0))
        val beyond = point("beyond", Geo.destinationPoint(southKm(157.0), 90.0, 6_000.0), CourseTag.SEA)
        val onTime = TimeFitPlanner.options(
            origin, far, 160 * MINUTE_MS, 20 * MINUTE_MS, listOf(beyond), emptyList(), config, wish = CourseWish(onTime = true),
        )
        assertTrue(onTime.none { it.id == "scenic-beyond" })
        val longer = TimeFitPlanner.options(origin, far, 160 * MINUTE_MS, 20 * MINUTE_MS, listOf(beyond), emptyList(), config)
        assertTrue(longer.any { it.id == "scenic-beyond" })
    }

    @Test
    fun restStopSkipsOppositeDirectionTwin() {
        val far = Place("남쪽 도시", southKm(150.0))
        val wrongWay = TestAreas.area("wrong", southKm(90.0), directionTarget = TestAreas.northCity)
        val rightWay = TestAreas.area("right", southKm(95.0), directionTarget = TestAreas.southCity)
        val options = TimeFitPlanner.options(
            origin, far, 160 * MINUTE_MS, 40 * MINUTE_MS, emptyList(), listOf(wrongWay, rightWay), config,
        )
        assertEquals("rest-right", options.single { it.kind == CourseKind.REST_STOP }.id)
    }

    @Test
    fun shortTripRestsNearDestination() {
        val nearStart = RestArea("a", "출발 근처 주차장", RestAreaType.PARKING, Geo.destinationPoint(southKm(2.5), 90.0, 300.0))
        val nearDest = RestArea("b", "도착 근처 공원", RestAreaType.PARK, Geo.destinationPoint(southKm(7.5), 90.0, 300.0))
        val options = TimeFitPlanner.options(
            origin, dest, direct, 40 * MINUTE_MS, emptyList(), listOf(nearStart, nearDest), config,
        )
        val rest = options.first { it.kind == CourseKind.REST_STOP }
        assertEquals("rest-b", rest.id)
        assertTrue(rest.reason.startsWith("공원 주차장"))
        assertEquals(RestKind.WAIT, rest.restKind)
        // 종류가 다른 곳(공영 주차장)을 두 번째로 낸다.
        assertEquals(listOf("rest-b", "rest-a"), options.filter { it.kind == CourseKind.REST_STOP }.map { it.id })
    }

    @Test
    fun bigSlackOnTimeRestsInsteadOfLongDetours() {
        val ahead = RestArea("ahead", "가는길주차장", RestAreaType.PARKING, Geo.destinationPoint(southKm(7.0), 90.0, 300.0))
        val far = point("far", sideKm(12.0))
        val options = TimeFitPlanner.options(
            origin, dest, direct, 58 * MINUTE_MS, listOf(far), listOf(ahead), config, maxAddedMs = 35 * MINUTE_MS,
        )
        // 오래 돌기보다 쉬다 가기를 앞에 두되, 경치 코스도 하나는 같이 보여 준다.
        assertEquals(listOf("rest-ahead", "scenic-far"), options.map { it.id })
        assertTrue(options.first().title, options.first().title.endsWith("에서 기다리기"))
    }

    @Test
    fun longerModeShowsShorterPointsRatherThanNothing() {
        // 60분 더 달리고 싶은데 근처 지점은 15분짜리뿐이면, 아무것도 없다고 하지 않고 그 지점을 보여 준다.
        val near = point("near", sideKm(6.0))
        val options = TimeFitPlanner.options(origin, dest, direct, 60 * MINUTE_MS, listOf(near), emptyList(), config)
        assertEquals("scenic-near", options.first().id)
    }

    @Test
    fun waitNearNeedsAFewMinutesOfWaiting() {
        // 2.4km 떨어진 주차장까지 돌아가는 데 남는 시간을 거의 다 쓰면 기다리는 코스로 내지 않는다.
        val lot = RestArea("lot", "먼주차장", RestAreaType.PARKING, Geo.destinationPoint(dest.position, 90.0, 2_400.0))
        val detour = TimeFitPlanner.addedMs(origin, dest.position, lot.position, TimeFitPlanner.effectiveSpeedMps(origin, dest.position, direct, config), config)
        assertTrue(TimeFitPlanner.waitNearOptions(origin, dest, direct, detour + 2 * MINUTE_MS, listOf(lot), config).isEmpty())
        assertEquals(1, TimeFitPlanner.waitNearOptions(origin, dest, direct, detour + 6 * MINUTE_MS, listOf(lot), config).size)
        assertTrue(TimeFitPlanner.hasWaitPlaceNear(dest, listOf(lot)))
    }

    @Test
    fun bigSlackWithoutRestPlaceCapsScenicTime() {
        val near = point("near", sideKm(8.0))
        val far = point("far", sideKm(14.0))
        val options = TimeFitPlanner.options(
            origin, dest, direct, 58 * MINUTE_MS, listOf(near, far), emptyList(), config, maxAddedMs = 35 * MINUTE_MS,
        )
        assertTrue(options.isNotEmpty())
        assertTrue(options.all { it.addedMs <= 35 * MINUTE_MS })
        assertEquals("scenic-near", options.first().id)
    }

    @Test
    fun longTripKeepsOnlyPointsAlongTheWay() {
        // 셋 다 남는 시간(12분)을 잘 채우지만, 길의 20% 지점과 옆으로 25km 벗어난 지점은 뺀다 (150km 길: 옆으로 18km까지)
        val far = Place("먼 도시", southKm(150.0))
        val early = point("early", Geo.destinationPoint(southKm(30.0), 90.0, 17_000.0))
        val sideways = point("sideways", Geo.destinationPoint(southKm(100.0), 90.0, 25_000.0))
        val along = point("along", Geo.destinationPoint(southKm(110.0), 90.0, 17_000.0))
        val options = TimeFitPlanner.options(
            origin, far, 160 * MINUTE_MS, 12 * MINUTE_MS, listOf(early, sideways, along), emptyList(), config,
        )
        val ids = options.map { it.id }
        assertTrue(ids.toString(), "scenic-early" !in ids && "scenic-sideways" !in ids)
        assertTrue(ids.toString(), "scenic-along" in ids)
    }

    @Test
    fun onTimeShortTripStaysOnTheDestinationSide() {
        // 목적지 반대쪽(출발지 북쪽) 지점은 온타임 모드에서 보지 않는다 (목적지에서 14km, 길이는 10km).
        val behind = point("behind", Geo.destinationPoint(Geo.destinationPoint(origin, 0.0, 4_000.0), 90.0, 3_000.0))
        val options = TimeFitPlanner.options(
            origin, dest, direct, 30 * MINUTE_MS, listOf(behind), emptyList(), config, maxAddedMs = 35 * MINUTE_MS,
            wish = CourseWish(onTime = true),
        )
        assertTrue(options.none { it.id == "scenic-behind" })
        val loose = TimeFitPlanner.options(origin, dest, direct, 30 * MINUTE_MS, listOf(behind), emptyList(), config)
        assertTrue(loose.any { it.id == "scenic-behind" })
    }

    @Test
    fun waitNearPicksDifferentKindsCloseToDestination() {
        val park = RestArea("p", "목적지공원", RestAreaType.PARK, Geo.destinationPoint(dest.position, 90.0, 800.0))
        val lot = RestArea("l", "목적지주차장", RestAreaType.PARKING, Geo.destinationPoint(dest.position, 270.0, 1_500.0))
        val lot2 = RestArea("l2", "가까운주차장", RestAreaType.PARKING, Geo.destinationPoint(dest.position, 0.0, 600.0))
        val far = RestArea("f", "먼주차장", RestAreaType.PARKING, Geo.destinationPoint(dest.position, 90.0, 6_000.0))
        val options = TimeFitPlanner.waitNearOptions(origin, dest, direct, 30 * MINUTE_MS, listOf(far, lot, park, lot2), config)
        assertEquals(listOf("wait-l2", "wait-p"), options.map { it.id })
        assertTrue(options.all { it.kind == CourseKind.REST_STOP && it.restKind == RestKind.WAIT })
        assertTrue(options.first().stayMs in 20 * MINUTE_MS..30 * MINUTE_MS)
        assertTrue(options.first().reason, options.first().reason.contains("기다려요"))
    }

    @Test
    fun waitNearLooksUpTo3kmAndOtherwiseGivesNothing() {
        val near3 = RestArea("n3", "조금 먼 주차장", RestAreaType.PARKING, Geo.destinationPoint(dest.position, 90.0, 2_600.0))
        assertEquals(listOf("wait-n3"), TimeFitPlanner.waitNearOptions(origin, dest, direct, 30 * MINUTE_MS, listOf(near3), config).map { it.id })
        val far = RestArea("f", "먼 주차장", RestAreaType.PARKING, Geo.destinationPoint(dest.position, 90.0, 5_000.0))
        assertTrue(TimeFitPlanner.waitNearOptions(origin, dest, direct, 30 * MINUTE_MS, listOf(far), config).isEmpty())
    }

    @Test
    fun directReasonSaysHowEarly() {
        val at = 1_700_000_000_000L
        assertTrue(TimeFitPlanner.direct(12 * MINUTE_MS, at, 3 * MINUTE_MS).reason.endsWith("15분 일찍"))
        assertTrue(TimeFitPlanner.direct(0L, at, MINUTE_MS).reason.endsWith("1분 일찍"))
        assertTrue(TimeFitPlanner.direct(-MINUTE_MS, at, MINUTE_MS).reason.endsWith("정각"))
        // 여유(3분)를 못 지키는 것과 늦는 것은 다르다: 늦음은 목표 시각 기준으로 센다.
        assertEquals("바로 가도 약 2분 늦어요", TimeFitPlanner.direct(-5 * MINUTE_MS, at, 3 * MINUTE_MS).reason)
    }

    @Test
    fun longTripIgnoresScenicPointsBehind() {
        val far = Place("먼 도시", southKm(150.0))
        val behind = point("behind", LatLng(37.15, 127.0))
        val ahead = point("ahead", Geo.destinationPoint(southKm(120.0), 90.0, 8_000.0))
        val options = TimeFitPlanner.options(
            origin, far, 160 * MINUTE_MS, 20 * MINUTE_MS, listOf(behind, ahead), emptyList(), config,
        )
        assertTrue(options.none { it.id == "scenic-behind" })
    }

    @Test
    fun fitLabelTreatsSmallSpareAsOnTime() {
        assertEquals("정각", DriveText.fit(FitStatus.ON_TIME, -30 * SECOND_MS))
        assertEquals("3분 일찍", DriveText.fit(FitStatus.ON_TIME, -3 * MINUTE_MS))
        // 목표를 30초 넘게 지나면 늦는다고 보여 준다 (예전에는 2분까지 '정각').
        assertEquals("약 1분 늦어요", DriveText.fit(FitStatus.ON_TIME, 40 * SECOND_MS))
        assertEquals("8분 일찍", DriveText.fit(FitStatus.EARLY, -8 * MINUTE_MS))
        assertEquals("약 4분 늦어요", DriveText.fit(FitStatus.LATE, 4 * MINUTE_MS))
        // 롱웨이 모드에는 '늦어요'가 없다.
        assertEquals("약 6분 더", DriveText.fitLonger(FitStatus.ON_TIME, 6 * MINUTE_MS))
        assertEquals("예정대로", DriveText.fitLonger(FitStatus.ON_TIME, MINUTE_MS))
        assertEquals("약 12분 더", DriveText.fitLonger(FitStatus.LATE, 12 * MINUTE_MS))
    }

    @Test
    fun noRestStopWhenSlackIsShort() {
        val ahead = RestArea("ahead", "앞쪽휴게소", RestAreaType.SERVICE_AREA, southKm(6.0))
        val options = TimeFitPlanner.options(origin, dest, direct, 15 * MINUTE_MS, emptyList(), listOf(ahead), config)
        assertTrue(options.none { it.kind == CourseKind.REST_STOP })
    }

    @Test
    fun detourFillsMostOfSlackWhenNoPointsNearby() {
        val slack = 15 * MINUTE_MS
        val options = TimeFitPlanner.options(origin, dest, direct, slack, emptyList(), emptyList(), config)
        val detour = options.single()
        assertEquals(CourseKind.DETOUR, detour.kind)
        val fill = detour.addedMs.toDouble() / slack
        assertEquals(0.85, fill, 0.05)
    }

    @Test
    fun negativeSlackSaysItMayBeLate() {
        val only = TimeFitPlanner.options(origin, dest, direct, -5 * MINUTE_MS, emptyList(), emptyList(), config).single()
        assertEquals(CourseKind.DIRECT, only.kind)
        assertTrue(only.reason, only.reason.contains("5분 늦어요"))
        assertNull(only.restArea)
    }

    @Test
    fun objectParticleFollowsFinalConsonant() {
        assertEquals("팔각정을", DriveText.withObject("팔각정"))
        assertEquals("북악스카이웨이를", DriveText.withObject("북악스카이웨이"))
    }

    @Test
    fun directionParticleFollowsFinalConsonant() {
        assertEquals("휴게소로", DriveText.toward("휴게소"))
        assertEquals("공영 주차장으로", DriveText.toward("공영 주차장"))
        assertEquals("서울로", DriveText.toward("서울"))
    }

    @Test
    fun particlesLookPastTrailingParentheses() {
        assertEquals("용인 휴게소 (강릉 방향)를", DriveText.withObject("용인 휴게소 (강릉 방향)"))
        assertEquals("구정휴게소(동해방향)로", DriveText.toward("구정휴게소(동해방향)"))
        assertEquals("강변공원을", DriveText.withObject("강변공원"))
    }

    @Test
    fun clockRoundsToTheNearestMinuteLikeMinutes() {
        // 7:02:40 도착은 '7:03' (분 차이를 반올림하는 것과 같은 기준). 지금 시각 표시는 시계처럼 버린다.
        val base = DriveText.todayAt(1_700_000_000_000L, 19 * 60)
        assertEquals("오후 7:03", DriveText.clock(base + 2 * MINUTE_MS + 40 * SECOND_MS))
        assertEquals("오후 7:02", DriveText.clock(base + 2 * MINUTE_MS + 20 * SECOND_MS))
        assertEquals("오후 7:02", DriveText.clockNow(base + 2 * MINUTE_MS + 40 * SECOND_MS))
        assertEquals("7시 3분", DriveText.spokenClock(base + 2 * MINUTE_MS + 40 * SECOND_MS))
    }
}
