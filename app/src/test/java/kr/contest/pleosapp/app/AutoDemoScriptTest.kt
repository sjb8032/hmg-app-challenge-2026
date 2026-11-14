package kr.contest.pleosapp.app

import kr.contest.pleosapp.core.DriveMode
import kr.contest.pleosapp.core.DriveStep
import kr.contest.pleosapp.core.FitStatus
import kr.contest.pleosapp.core.Gear
import kr.contest.pleosapp.core.Phase
import kr.contest.pleosapp.core.PickupNoticeKind
import kr.contest.pleosapp.core.SECOND_MS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone
import kotlin.math.abs

/**
 * 자동 시연 대본을 실제 엔진(모의 내비·가상 시계·두 엔진)으로 끝까지 돌려 본다.
 * 모든 장면이 시간 초과 없이 조건을 채우고 넘어가야 한다 (영상 녹화 중 멈추거나 엉뚱한 화면이 나오지 않게).
 */
class AutoDemoScriptTest {
    private var savedZone: TimeZone? = null

    @Before
    fun zone() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(DemoRig.ZONE)
    }

    @After
    fun restoreZone() {
        savedZone?.let(TimeZone::setDefault)
    }

    /** 대본을 끝까지 돌린다. 장면마다 넘어가기 직전의 화면 상태를 [onLeave]로 본다 */
    private fun play(chapter: AutoChapter?, onLeave: (AutoStatus, AutoView) -> Unit = { _, _ -> }): Pair<DemoRig, AutoDemoRunner> {
        val rig = DemoRig()
        val runner = AutoDemoRunner(rig)
        runner.start(chapter, rig.real)
        var guard = 0
        while (runner.running && guard < 20_000) {
            rig.advance(200)
            val before = runner.status
            runner.tick(rig.real)
            if (before != null && runner.status?.scene != before.scene) onLeave(before, rig.view())
            guard++
        }
        assertFalse("대본이 끝나지 않음", runner.running)
        return rig to runner
    }

    @Test
    fun wholeDemoRunsWithoutTimeouts() {
        val left = LinkedHashMap<String, AutoView>()
        val (rig, runner) = play(null) { status, view -> left[status.title + "#" + status.scene] = view }
        println(runner.transcript.joinToString("\n"))
        println("자동 시연 길이: ${rig.real / 1000}초")
        assertEquals("시간이 다 되어 넘어간 장면: ${runner.timedOut}", emptyList<String>(), runner.timedOut)
        // 시연 카드에 쓴 길이(약 5분)와 실제로 도는 길이가 10초 넘게 어긋나지 않게
        val seconds = rig.real / 1000
        assertTrue("실제 ${seconds}초, 카드에는 ${AutoDemoScript.LENGTH_SECONDS}초", abs(seconds - AutoDemoScript.LENGTH_SECONDS) <= 10)
        assertNull(runner.status)
        // 다 돌고 나면 고속도로에서 다시 출발해 강릉 숙소로 가는 중
        assertEquals(Phase.DRIVING, rig.view().core.phase)
        assertTrue(rig.view().driving)
        assertEquals("강릉 숙소", rig.lastRoute?.first?.name)
        assertTrue(rig.lastRoute?.second.orEmpty().isEmpty())
        assertEquals(AutoDemoScript.scenes(null).size, left.size)
    }

    @Test
    fun onTimeStoryReroutesAndArrivesOnTime() {
        val seen = ArrayList<AutoView>()
        val (rig, runner) = play(AutoChapter.ON_TIME) { _, view -> seen += view }
        assertEquals(emptyList<String>(), runner.timedOut)
        val d = rig.view().drive
        assertEquals(DriveStep.ARRIVED, d.step)
        assertEquals(DriveMode.ON_TIME, d.mode)
        val arrived = d.arrivedAt!!
        assertTrue("목표보다 늦게 도착", arrived <= d.targetAt!!)
        // 막힌 뒤 늦음을 지켜보다가 경유지를 바꿨다
        assertTrue(runner.transcript.any { it.startsWith("늦지 않는 길로 바꿨어요") && "바꿨어요" in it })
        assertTrue(rig.said.any { it.startsWith("늦지 않게") })
        // 해설에 7시 10분 목표가 나온다
        assertTrue(runner.transcript.any { "7시 10분" in it })
    }

    @Test
    fun pickupStorySendsArrivalNotices() {
        val (rig, runner) = play(AutoChapter.PICKUP)
        assertEquals(emptyList<String>(), runner.timedOut)
        val d = rig.view().drive
        assertEquals(DriveStep.ARRIVED, d.step)
        assertEquals(DriveMode.ON_TIME, d.mode)
        assertEquals("아이 학원", d.destination?.name)
        assertEquals("아이", d.pickupTo)
        // 도착 3분 전 → 자리 없음 → 다시 와서 P단 (시연: 실제로 보내지 않고 남기기만)
        assertEquals(
            listOf(PickupNoticeKind.APPROACH, PickupNoticeKind.CIRCLING, PickupNoticeKind.PARKED),
            rig.sent.map { it.kind },
        )
        assertEquals(rig.sent.toList(), d.pickupNotices)
        assertTrue(rig.sent.last().text, "아이 학원 바로 앞에 차를 세웠어요" in rig.sent.last().text)
        assertEquals(Gear.PARK, rig.view().core.gear)
        assertTrue(rig.said.any { it.startsWith("한 바퀴 돌고") })
        assertTrue(rig.said.any { it == "아이에게 보낼 도착 문자를 띄웠어요." })
        // 학원 앞에 세운 것은 도착이라 '주차하셨네요'를 묻지 않는다
        assertNull(rig.view().core.prompt)
        assertTrue(runner.transcript.any { it.startsWith("도착 3분 전") && "아이 학원 앞에 도착해요" in it })
        // 기획서 시나리오 2처럼 밤 9시 50분 픽업 (바로 가면 10분 남짓 일찍)
        assertEquals("pickup", rig.view().tripId)
        assertTrue(runner.transcript.joinToString("\n"), runner.transcript.any { "픽업은 9시 50분" in it && "분 일찍 닿아" in it })
    }

    @Test
    fun parkingRightAfterTheArrivalScreenDoesNotAskToRest() {
        // 발표 중 도착 화면이 뜨자마자(모의 차가 아직 학원 점까지 굴러가는 중) 'P단 주차'를 눌러도 '주차하셨네요'를 묻지 않는다
        val rig = DemoRig()
        rig.selectDemoTrip("pickup")
        rig.openMode(DriveMode.ON_TIME)
        rig.setPickup(true)
        rig.planCourses()
        rig.chooseCourse(rig.view().drive.options.first())
        rig.demoDrive()
        var guard = 0
        while (rig.drive.state.step != DriveStep.ARRIVED && guard++ < 20_000) rig.advance(200)
        assertTrue("아직 굴러가는 중", rig.view().driving)
        rig.advance(600)
        rig.demoPark()
        assertEquals(PickupNoticeKind.PARKED, rig.sent.last().kind)
        rig.advance(3_000)
        assertNull(rig.engine.state.prompt)
    }

    @Test
    fun longerStoryAddsFifteenMinutes() {
        val (rig, runner) = play(AutoChapter.LONGER)
        assertEquals(emptyList<String>(), runner.timedOut)
        val d = rig.view().drive
        assertEquals(DriveStep.ACTIVE, d.step)
        assertEquals(DriveMode.LONGER, d.mode)
        assertEquals(45, d.extraMinutes)
        assertNotNull(d.chosen)
    }

    /**
     * '+15분 더'는 출발하고 곧 누른다: 시연 길에서는 출발 뒤 실제 8초쯤까지만 15분짜리 코스가 맞는다.
     * 출발 해설이 길어지거나 기기에서 장면이 조금 늦게 넘어가도 코스가 나오게, 5초 안에 누르는지 본다.
     */
    @Test
    fun addTimeIsPressedSoonAfterDeparting() {
        val rig = DemoRig()
        val runner = AutoDemoRunner(rig)
        runner.start(AutoChapter.LONGER, rig.real)
        var departedAt: Long? = null
        var addedAt: Long? = null
        var seen = 0
        var guard = 0
        while (runner.running && addedAt == null && guard++ < 10_000) {
            rig.advance(100)
            runner.tick(rig.real)
            if (runner.transcript.size > seen) {
                seen = runner.transcript.size
                val title = runner.transcript.last().substringBefore(" — ")
                if (title == "출발") departedAt = rig.real
                if (title == "15분 더 달리고 싶을 때") addedAt = rig.real
            }
        }
        val gap = addedAt!! - departedAt!!
        assertTrue("출발하고 ${gap}ms 뒤에 눌렀어요", gap <= 5_000)
    }

    @Test
    fun restStoryNapsAndDepartsAgain() {
        val phases = ArrayList<Phase>()
        val (rig, runner) = play(AutoChapter.REST) { _, view -> phases += view.core.phase }
        assertEquals(emptyList<String>(), runner.timedOut)
        assertTrue(Phase.TO_REST_AREA in phases)
        assertTrue(Phase.RESTING in phases)
        assertEquals(Phase.DRIVING, rig.view().core.phase)
        assertFalse(rig.alarmOn)
        assertTrue(rig.said.any { it.startsWith("휴식 시간이 끝났어요") })
        assertTrue(rig.notes.any { it == "다시 안내: 강릉 숙소" })
    }

    /**
     * '휴식 건너뛰기'는 가상 시계를 알람 시각까지 돌린다. 쉰 시작 시각을 앞당겨 적지 않아서
     * 화면의 지금 시각·알람 시각·쉰 시간이 서로 맞고, 해설에서 말한 알람 시각에 알람이 울린다.
     */
    @Test
    fun skippingTheRestMovesTheClockToTheAlarm() {
        val rig = DemoRig()
        val runner = AutoDemoRunner(rig)
        runner.start(AutoChapter.REST, rig.real)
        var guard = 0
        while (rig.view().core.phase != Phase.RESTING && guard++ < 5_000) {
            rig.advance(200)
            runner.tick(rig.real)
        }
        runner.stop()
        val rest = rig.view().core.rest
        assertNotNull(rest)
        assertTrue(rest!!.alarmPending)
        rig.demoSkipRest()
        val after = rig.view().core.rest!!
        assertEquals(rest.startedAt, after.startedAt)
        assertTrue("지금 ${rig.now()}, 알람 ${rest.alarmAt}", rig.now() >= rest.alarmAt && rig.now() - rest.alarmAt <= SECOND_MS)
        assertTrue(after.alarmRinging)
        assertFalse(after.alarmPending)
        // 다시 눌러도 더 돌리지 않는다
        val now = rig.now()
        rig.demoSkipRest()
        assertEquals(now, rig.now())
    }

    /** 해설은 다 읽을 수 있을 만큼 띄운다: 공백 뺀 글자 수를 초당 7.5자로 읽는 시간보다 빨리 넘기지 않는다 */
    @Test
    fun everyNarrationStaysLongEnoughToRead() {
        val rig = DemoRig()
        val runner = AutoDemoRunner(rig)
        runner.start(null, rig.real)
        val tooFast = ArrayList<String>()
        var shownAt = rig.real
        var shownText: String? = null
        var seen = 0
        var guard = 0
        while (runner.running && guard++ < 20_000) {
            rig.advance(100)
            val before = runner.status?.scene
            runner.tick(rig.real)
            val text = shownText
            if (text != null && runner.status?.scene != before) {
                val need = (text.count { !it.isWhitespace() } * 1_000 / AutoDemoScript.READ_CHARS_PER_SECOND).toLong()
                if (rig.real - shownAt < need - 200) tooFast += "${text.take(20)}… ${rig.real - shownAt}ms < ${need}ms"
                shownText = null
            }
            if (runner.transcript.size > seen) {
                seen = runner.transcript.size
                shownAt = rig.real
                shownText = runner.transcript.last().substringAfter(" — ")
            }
        }
        assertEquals(emptyList<String>(), tooFast)
    }

    @Test
    fun lengthLabelRoundsToTenSeconds() {
        assertEquals("약 5분", AutoDemoScript.lengthLabel)
        assertEquals(1_000L * 10, AutoDemoScript.readMsFor("가".repeat(75), atLeast = 3_000L))
        assertEquals(7_000L, AutoDemoScript.readMsFor("짧은 해설이에요.", atLeast = 7_000L))
    }

    @Test
    fun cueComesBeforeThePress() {
        val rig = DemoRig()
        val runner = AutoDemoRunner(rig)
        runner.start(AutoChapter.ON_TIME, rig.real)
        // 첫 장면(시내 고르기)은 짚기가 없어 바로 누른다
        assertEquals("city", rig.view().tripId)
        // 두 번째 장면(온타임 모드 켜기)까지 간다
        while (runner.status?.cue == null) {
            rig.advance(200)
            runner.tick(rig.real)
        }
        assertEquals(AutoCue.MODE_ON_TIME, runner.status?.cue)
        assertEquals(DriveStep.HOME, rig.view().drive.step)
        // 짚는 동안은 누르지 않는다
        rig.advance(1_000)
        runner.tick(rig.real)
        assertEquals(DriveStep.HOME, rig.view().drive.step)
        rig.advance(800)
        runner.tick(rig.real)
        assertNull(runner.status?.cue)
        assertEquals(DriveStep.SETUP, rig.view().drive.step)
        assertEquals(FitStatus.UNKNOWN, rig.view().drive.status)
    }

    @Test
    fun skipAndStop() {
        val rig = DemoRig()
        val runner = AutoDemoRunner(rig)
        runner.start(null, rig.real)
        assertEquals(1, runner.status?.scene)
        runner.skip(rig.real)
        assertEquals(2, runner.status?.scene)
        runner.stop()
        assertFalse(runner.running)
        assertNull(runner.status)
        runner.tick(rig.real)
        assertNull(runner.status)
    }
}
