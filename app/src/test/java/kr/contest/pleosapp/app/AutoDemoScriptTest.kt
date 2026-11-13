package kr.contest.pleosapp.app

import kr.contest.pleosapp.core.DriveMode
import kr.contest.pleosapp.core.DriveStep
import kr.contest.pleosapp.core.FitStatus
import kr.contest.pleosapp.core.Gear
import kr.contest.pleosapp.core.Phase
import kr.contest.pleosapp.core.PickupNoticeKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

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
        assertTrue(rig.said.any { it == "아이에게 곧 도착한다고 알렸어요." })
        // 학원 앞에 세운 것은 도착이라 '주차하셨네요'를 묻지 않는다
        assertNull(rig.view().core.prompt)
        assertTrue(runner.transcript.any { it.startsWith("도착 3분 전") && "아이 학원 앞에 도착해요" in it })
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
