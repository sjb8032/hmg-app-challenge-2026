package kr.contest.pleosapp.core

import kr.contest.pleosapp.core.TestAreas.origin
import kr.contest.pleosapp.core.TestAreas.southKm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 온타임 모드 픽업 알림: 도착 3분 전·세운 곳·자리 없음 (시연: 문자를 실제로 보내지 않고 남기기만 한다) */
class DrivePickupTest {
    private val t0 = 1_700_000_000_000L
    private val dest = Place("아이 학원", southKm(10.0), PlaceKind.BOOKMARK)
    private val direct = 20 * MINUTE_MS

    /** 픽업 알림을 켜고 바로 가는 길로 출발한 엔진 */
    private fun active(pickup: String? = "아이"): DriveEngine =
        DriveEngine(emptyList(), emptyList()).apply {
            onLocation(t0, origin)
            onNaviDestination(t0, dest, direct)
            openMode(t0, DriveMode.ON_TIME)
            setPickup(pickup)
            plan(t0)
            goDirect(t0)
            drainEffects()
        }

    private fun DriveEngine.naviAt(now: Long, remainingMs: Long) {
        onNaviDestination(now, dest, remainingMs)
        onTick(now)
    }

    /** 목적지에서 북쪽으로 [meters]만큼 떨어진 곳 */
    private fun nearDest(meters: Double) = Geo.destinationPoint(dest.position, 0.0, meters)

    private fun DriveEngine.sent() = drainEffects().filterIsInstance<DriveEffect.SendPickup>().map { it.notice }

    @Test
    fun approachIsSentOnceWhenThreeMinutesLeft() {
        val e = active()
        assertEquals(DriveStep.ACTIVE, e.state.step)
        assertEquals("아이", e.state.pickupTo)
        e.naviAt(t0 + 10 * MINUTE_MS, 10 * MINUTE_MS)
        assertTrue(e.sent().isEmpty())

        val now = t0 + 17 * MINUTE_MS
        e.onLocation(now, southKm(8.5))
        e.naviAt(now, 2 * MINUTE_MS + 50 * SECOND_MS)
        val notice = e.sent().single()
        assertEquals(PickupNoticeKind.APPROACH, notice.kind)
        assertEquals("아이", notice.to)
        assertTrue(notice.text, notice.text.startsWith(PickupText.PREFIX))
        assertTrue(notice.text, "아이 학원 앞에 도착해요" in notice.text)
        assertTrue(notice.text, DriveText.clock(now + 2 * MINUTE_MS + 50 * SECOND_MS) in notice.text)
        // 지금 차 위치를 지도 링크로 넣는다
        assertEquals(southKm(8.5), notice.position)
        assertTrue(notice.text, notice.link!! in notice.text)
        assertEquals(listOf(notice), e.state.pickupNotices)
        assertTrue(e.drainEffects().isEmpty())

        e.naviAt(now + MINUTE_MS, 2 * MINUTE_MS)
        assertTrue(e.sent().isEmpty())
        assertEquals(1, e.state.pickupNotices.size)
    }

    @Test
    fun nothingIsSentWhenPickupIsOff() {
        val e = active(pickup = null)
        e.naviAt(t0 + 18 * MINUTE_MS, 2 * MINUTE_MS)
        e.onLocation(t0 + 19 * MINUTE_MS, dest.position)
        e.onTick(t0 + 19 * MINUTE_MS)
        assertFalse(e.onParked(t0 + 20 * MINUTE_MS))
        e.circleForSpot(t0 + 20 * MINUTE_MS)
        assertTrue(e.sent().isEmpty())
        assertTrue(e.state.pickupNotices.isEmpty())
        assertFalse(e.state.circling)
    }

    @Test
    fun pickupIsOnlyForOnTimeSetup() {
        val e = DriveEngine(emptyList(), emptyList())
        e.onLocation(t0, origin)
        e.onNaviDestination(t0, dest, direct)
        e.openMode(t0, DriveMode.LONGER)
        e.setPickup("아이")
        assertNull(e.state.pickupTo)
        e.openMode(t0, DriveMode.ON_TIME)
        e.setPickup("아이")
        assertEquals("아이", e.state.pickupTo)
        e.setPickup(null)
        assertNull(e.state.pickupTo)
        // 모드를 다시 열면 꺼진 채로 시작한다
        e.setPickup("아이")
        e.openMode(t0, DriveMode.ON_TIME)
        assertNull(e.state.pickupTo)
    }

    @Test
    fun parkedSpotIsSentOncePerSpot() {
        val e = active()
        val at = t0 + 19 * MINUTE_MS
        e.onLocation(at, nearDest(60.0))
        e.onTick(at)
        assertEquals(DriveStep.ARRIVED, e.state.step)
        e.drainEffects()

        // 이미 도착했어도 픽업할 곳에 세운 것이라 true (앱이 레스트 모드에 알려 '주차하셨네요'를 묻지 않게)
        assertTrue(e.onParked(at + 10 * SECOND_MS))
        val parked = e.sent().single()
        assertEquals(PickupNoticeKind.PARKED, parked.kind)
        assertTrue(parked.text, "약 60m 떨어진 곳" in parked.text)
        assertEquals(nearDest(60.0), parked.position)

        // 같은 자리에서 다시 P단이면 또 보내지 않는다
        e.onParked(at + 30 * SECOND_MS)
        assertTrue(e.sent().isEmpty())
        // 조금(50m) 옮겨 다시 세우면 새 자리를 보낸다
        e.onLocation(at + MINUTE_MS, nearDest(10.0))
        e.onParked(at + MINUTE_MS)
        val moved = e.sent().single()
        assertTrue(moved.text, "아이 학원 바로 앞에 차를 세웠어요" in moved.text)
        assertEquals(2, e.state.pickupNotices.count { it.kind == PickupNoticeKind.PARKED })
    }

    @Test
    fun parkingJustBeforeThePlaceCountsAsArrival() {
        val e = active()
        val at = t0 + 19 * MINUTE_MS
        // 도착으로 보는 150m 밖, 내비도 1분 남았다고 하는 곳에 세움
        e.onLocation(at, nearDest(250.0))
        e.naviAt(at, MINUTE_MS)
        assertEquals(DriveStep.ACTIVE, e.state.step)
        assertTrue(e.onParked(at))
        assertEquals(DriveStep.ARRIVED, e.state.step)
        val kinds = e.sent().map { it.kind }
        assertTrue(PickupNoticeKind.PARKED in kinds)
        assertTrue(e.state.pickupNotices.last().text, "약 250m" in e.state.pickupNotices.last().text)
    }

    @Test
    fun parkingFarFromThePlaceSendsNothing() {
        val e = active()
        e.onLocation(t0 + 5 * MINUTE_MS, southKm(3.0))
        assertFalse(e.onParked(t0 + 5 * MINUTE_MS))
        assertEquals(DriveStep.ACTIVE, e.state.step)
        assertTrue(e.sent().isEmpty())
    }

    @Test
    fun noSpotCirclesAboutThreeMinutesAndComesBack() {
        val e = active()
        val at = t0 + 20 * MINUTE_MS
        // 남쪽으로 달려와 도착 (달리는 방향이 남쪽)
        e.onLocation(at - 20 * SECOND_MS, nearDest(140.0))
        e.onLocation(at, dest.position)
        e.onTick(at)
        assertEquals(DriveStep.ARRIVED, e.state.step)
        e.drainEffects()

        e.circleForSpot(at + 5 * SECOND_MS)
        val s = e.state
        assertEquals(DriveStep.ACTIVE, s.step)
        assertTrue(s.circling)
        assertTrue(s.viaActive)
        assertEquals("circle", s.chosen?.id)
        val effects = e.drainEffects()
        val route = effects.filterIsInstance<DriveEffect.Route>().single()
        assertEquals(dest.name, route.destination.name)
        val loop = route.via.single()
        assertEquals(DriveEngine.CIRCLE_VIA_NAME, loop.name)
        val radius = Geo.distanceMeters(dest.position, loop.position)
        assertTrue("한 바퀴 반경 $radius", radius in 250.0..900.0)
        // 남쪽(180°)으로 달려왔으니 오른쪽 앞(240°) 쪽으로 돈다
        val bearing = Geo.bearingDegrees(dest.position, loop.position)
        assertTrue("도는 쪽 $bearing", bearing in 225.0..255.0)
        val back = s.arrivalEstimate!! - (at + 5 * SECOND_MS)
        assertTrue("다시 오는 데 ${back / SECOND_MS}초", back in 2 * MINUTE_MS..4 * MINUTE_MS)
        val circling = effects.filterIsInstance<DriveEffect.SendPickup>().single().notice
        assertEquals(PickupNoticeKind.CIRCLING, circling.kind)
        assertTrue(circling.text, "한 바퀴 돌고" in circling.text && DriveText.clock(s.arrivalEstimate!!) in circling.text)
        assertTrue(effects.filterIsInstance<DriveEffect.Speak>().single().text.startsWith("한 바퀴 돌고"))

        // 도는 동안 늦어져도 (내비 10분) 길을 줄이거나 빼지 않는다
        val loopPos = Geo.destinationPoint(dest.position, 240.0, 300.0)
        for (k in 1..8) {
            val now = at + 30 * SECOND_MS * k
            e.onLocation(now, loopPos)
            e.naviAt(now, 10 * MINUTE_MS)
        }
        assertTrue(e.drainEffects().none { it is DriveEffect.Route })
        assertTrue(e.state.viaActive)
        assertNull(e.state.lateSince)
        assertFalse(e.state.extendOffered)

        // 도는 길을 지나 다시 학원 앞으로
        val after = at + 5 * MINUTE_MS
        e.onWaypointArrived(after)
        assertFalse(e.state.viaActive)
        assertTrue(e.state.message.orEmpty(), e.state.message.orEmpty().startsWith("한 바퀴 돌았어요"))
        e.onLocation(after + MINUTE_MS, nearDest(20.0))
        e.onTick(after + MINUTE_MS)
        assertEquals(DriveStep.ARRIVED, e.state.step)
        assertEquals("다시 도착했어요.", e.state.message)
        assertFalse(e.state.circling)
        // 다시 와서 세우면 세운 곳을 보낸다 ('곧 도착'은 처음 도착할 때 한 번만: 마지막 구간이 짧아 도착하면서 띄웠다)
        e.onParked(after + MINUTE_MS + 10 * SECOND_MS)
        assertEquals(
            listOf(PickupNoticeKind.APPROACH, PickupNoticeKind.CIRCLING, PickupNoticeKind.PARKED),
            e.state.pickupNotices.map { it.kind },
        )
    }

    @Test
    fun waitingInTheQueueWhileCirclingIsNotArrival() {
        val e = active()
        val at = t0 + 20 * MINUTE_MS
        e.onLocation(at, dest.position)
        e.onTick(at)
        e.circleForSpot(at + 5 * SECOND_MS)
        e.drainEffects()
        // 학원 앞 줄에 2분 넘게 서 있어도 (30m 앞) 도는 길을 지나기 전에는 다시 도착이 아니다
        var now = at + 5 * SECOND_MS
        repeat(14) {
            now += 10 * SECOND_MS
            e.onLocation(now, nearDest(30.0))
            e.naviAt(now, 3 * MINUTE_MS)
        }
        assertEquals(DriveStep.ACTIVE, e.state.step)
        assertTrue(e.state.circling)
        assertTrue(e.state.viaActive)
        // 그 자리에 세우면 (빈자리가 났음) 도착이고 도는 길은 내비에서 뺀다
        assertTrue(e.onParked(now + 10 * SECOND_MS))
        assertEquals(DriveStep.ARRIVED, e.state.step)
        assertFalse(e.state.viaActive)
        val effects = e.drainEffects()
        val route = effects.filterIsInstance<DriveEffect.Route>().single()
        assertTrue(route.via.isEmpty())
        assertEquals(PickupNoticeKind.PARKED, effects.filterIsInstance<DriveEffect.SendPickup>().single().notice.kind)
    }

    @Test
    fun briefStopBeforeAPendingWaypointIsNotPickupArrival() {
        val beyond = Place("학원 너머 공원", southKm(13.0), PlaceKind.WAYPOINT)
        val e = DriveEngine(emptyList(), emptyList()).apply {
            onLocation(t0, origin)
            onNaviDestination(t0, dest, direct)
            openMode(t0, DriveMode.ON_TIME)
            setTarget(t0, t0 + 50 * MINUTE_MS)
            setPickup("아이")
            plan(t0)
            choose(t0, CourseOption("scenic-x", CourseKind.SCENIC, "학원 너머 공원 거쳐 가기", "", listOf(beyond), 25 * MINUTE_MS))
            drainEffects()
        }
        val now = t0 + 18 * MINUTE_MS
        e.onLocation(now, nearDest(200.0))
        e.naviAt(now, 28 * MINUTE_MS)
        // 학원 200m 앞에서 잠깐 P단 (경유지가 아직 남음): 도착도, 세운 곳 문자도 아니다
        assertFalse(e.onParked(now + 10 * SECOND_MS))
        assertEquals(DriveStep.ACTIVE, e.state.step)
        assertTrue(e.state.viaActive)
        assertTrue(e.sent().isEmpty())
    }

    @Test
    fun shortLastLegSendsApproachOnArrival() {
        // 기다릴 곳(학원 옆 110m 공영주차장)에서 기다리다 떠나면 바로 도착이라 3분 전 문자를 띄울 틈이 없다
        val lot = RestArea("lot", "학원 옆 공영주차장", RestAreaType.PARKING, Geo.destinationPoint(dest.position, 90.0, 110.0))
        val e = DriveEngine(emptyList(), listOf(lot)).apply {
            onLocation(t0, origin)
            onNaviDestination(t0, dest, direct)
            openMode(t0, DriveMode.ON_TIME)
            setOnTimeStyle(OnTimeStyle.WAIT_NEAR)
            setTarget(t0, t0 + 50 * MINUTE_MS)
            setPickup("아이")
            plan(t0)
        }
        e.choose(t0, e.state.options.first { it.kind == CourseKind.REST_STOP })
        var now = t0 + 21 * MINUTE_MS
        e.onLocation(now, lot.position)
        e.onWaypointArrived(now)
        e.onTick(now)
        assertFalse("기다릴 곳에 세운 것은 픽업할 곳이 아니다", e.onParked(now))
        e.onRestPhase(now, Phase.RESTING, RestSession(startedAt = now, plannedMinutes = 25, alarmAt = now + 25 * MINUTE_MS, area = lot, kind = RestKind.WAIT))
        now += 25 * MINUTE_MS
        e.onRestPhase(now, Phase.DRIVING)
        assertEquals(DriveStep.ARRIVED, e.state.step)
        assertEquals(listOf(PickupNoticeKind.APPROACH), e.state.pickupNotices.map { it.kind })
        assertTrue(e.state.pickupNotices.single().text, "아이 학원 앞에 도착해요" in e.state.pickupNotices.single().text)
    }

    @Test
    fun longerCourseAfterApproachSendsANewApproach() {
        // 학원 동쪽 4km 공원 (늘어난 시간을 채울 경치 지점)
        val park = CoursePoint("park", "학원 옆 공원", Geo.destinationPoint(dest.position, 90.0, 4_000.0), setOf(CourseTag.PARK))
        val e = DriveEngine(listOf(park), emptyList()).apply {
            onLocation(t0, origin)
            onNaviDestination(t0, dest, direct)
            openMode(t0, DriveMode.ON_TIME)
            setPickup("아이")
            plan(t0)
            goDirect(t0)
            drainEffects()
        }
        val now = t0 + 17 * MINUTE_MS
        e.onLocation(now, southKm(8.5))
        e.naviAt(now, 2 * MINUTE_MS + 50 * SECOND_MS)
        assertEquals(PickupNoticeKind.APPROACH, e.sent().single().kind)
        // 약속이 늦춰져 +10분: 더 도는 코스로 바꾸면 도착 시각이 달라지므로 마지막 구간에서 다시 띄운다
        e.addTime(now + 10 * SECOND_MS, 10)
        assertTrue("경치 코스로 다시 채움", e.state.viaActive)
        e.drainEffects()
        val later = now + 15 * MINUTE_MS
        e.onWaypointArrived(later)
        e.onLocation(later, southKm(9.0))
        e.naviAt(later, 2 * MINUTE_MS)
        assertEquals(PickupNoticeKind.APPROACH, e.sent().single().kind)
        assertEquals(2, e.state.pickupNotices.count { it.kind == PickupNoticeKind.APPROACH })
    }

    @Test
    fun addingTimeWhileCirclingOnlyMovesTheTarget() {
        val e = active()
        val at = t0 + 20 * MINUTE_MS
        e.onLocation(at, dest.position)
        e.onTick(at)
        e.circleForSpot(at)
        e.drainEffects()
        val target = e.state.targetAt!!
        e.addTime(at + 10 * SECOND_MS, 10)
        assertEquals(target + 10 * MINUTE_MS, e.state.targetAt)
        assertTrue(e.state.circling)
        assertEquals("circle", e.state.chosen?.id)
        assertTrue(e.drainEffects().none { it is DriveEffect.Route })
    }

    @Test
    fun noSpotNeedsToBeNearThePlace() {
        val e = active()
        e.onLocation(t0 + 5 * MINUTE_MS, southKm(3.0))
        e.circleForSpot(t0 + 5 * MINUTE_MS)
        assertFalse(e.state.circling)
        assertTrue(e.sent().isEmpty())
    }

    @Test
    fun savedTripKeepsPickupAndReadsOldLines() {
        val trip = SavedTrip(DriveMode.ON_TIME, dest, minuteOfDay = 16 * 60 + 50, onTimeStyle = OnTimeStyle.WAIT_NEAR, pickupTo = "아이")
        assertEquals(trip, SavedTrip.decode(trip.encode()))
        assertTrue(trip.detail, trip.detail.endsWith("픽업 알림"))
        // 예전(14칸) 저장값도 읽는다
        val old = trip.copy(pickupTo = null).encode().split('\t').take(14).joinToString("\t")
        assertEquals(trip.copy(pickupTo = null), SavedTrip.decode(old))
        // 픽업 알림만 다르면 같은 설정으로 보고 바꿔 쓴다
        assertTrue(trip.sameAs(trip.copy(pickupTo = null)))
        assertEquals(listOf(trip), SavedTrip.added(listOf(trip.copy(pickupTo = null)), trip))

        val e = DriveEngine(emptyList(), emptyList())
        e.onLocation(t0, origin)
        e.onNaviDestination(t0, dest, direct)
        e.applySaved(t0, trip)
        assertEquals("아이", e.state.pickupTo)
        assertEquals("아이", e.state.toSavedTrip()?.pickupTo)
    }

    @Test
    fun pickupTextHasLinkAndShortBody() {
        val p = LatLng(37.4946, 127.0576)
        assertEquals("https://map.kakao.com/link/map/DriveMate,37.49460,127.05760", PickupText.mapLink(p))
        val text = PickupText.approach("아이 학원", "오후 4:47", p)
        assertEquals("오후 4:47쯤 아이 학원 앞에 도착해요. 아이 학원 앞에서 만나요.", PickupText.body(text))
        assertTrue(PickupText.parked("아이 학원", 12.0, p).contains("아이 학원 바로 앞에 차를 세웠어요."))
        assertEquals("아이 학원에서 약 130m 떨어진 곳에 차를 세웠어요.", PickupText.body(PickupText.parked("아이 학원", 126.0, p)))
        assertEquals(
            "아이 학원 앞에 세울 곳이 없어 한 바퀴 돌고 오후 4:53쯤 다시 올게요. 만날 곳은 그대로예요.",
            PickupText.body(PickupText.circling("아이 학원", "오후 4:53")),
        )
    }
}
