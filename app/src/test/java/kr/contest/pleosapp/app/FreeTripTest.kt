package kr.contest.pleosapp.app

import kr.contest.pleosapp.core.DriveMode
import kr.contest.pleosapp.core.DriveStep
import kr.contest.pleosapp.core.Geo
import kr.contest.pleosapp.core.LatLng
import kr.contest.pleosapp.core.MINUTE_MS
import kr.contest.pleosapp.core.Phase
import kr.contest.pleosapp.core.Place
import kr.contest.pleosapp.core.PlaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 직접 해 보기: 고른 곳에서 출발해 검색한 목적지로 (컨트롤러와 같은 흐름의 DemoRig로) */
class FreeTripTest {
    private val cityHall = LatLng(37.5663, 126.9779)

    @Test
    fun waitsForADestinationBeforeDriving() {
        val rig = DemoRig()
        rig.startFree(cityHall, "서울시청")
        rig.demoDrive()
        rig.advance(10_000)
        val navi = rig.navi!!
        // 목적지 없이 달리면 모의 차가 한쪽으로 끝없이 가므로 출발하지 않는다
        assertFalse(navi.moving)
        assertEquals(cityHall, navi.position)
        assertTrue(rig.notes.contains(FREE_TRIP_NEEDS_DESTINATION))
        rig.setNaviDestination(Place("광화문", LatLng(37.5759, 126.9768), PlaceKind.CURRENT))
        rig.demoDrive()
        rig.advance(10_000)
        assertTrue(navi.moving)
    }

    @Test
    fun restNearTheEndOfAHighwayTripKeepsTheEta() {
        val rig = DemoRig()
        val daejeon = Place("대전역", LatLng(36.3326, 127.4343), PlaceKind.CURRENT)
        rig.startFree(cityHall, "서울시청")
        rig.setNaviDestination(daejeon)
        val navi = rig.navi!!
        rig.demoDrive()
        // 시청 둘레를 벗어나면 고속도로로 보고, 레스트 모드는 휴게소·졸음쉼터를 찾는다
        while (Geo.distanceMeters(navi.position, cityHall) < 15_000.0) rig.advance(1_000)
        assertTrue(navi.onHighway)
        assertEquals(true, rig.engine.state.onExpressway)
        while (Geo.distanceMeters(navi.position, daejeon.position) > 30_000.0) rig.advance(1_000)
        val before = navi.remainingMs()!!
        rig.pressDrowsy()
        rig.advance(400)
        assertEquals(Phase.TO_REST_AREA, rig.engine.state.phase)
        val after = navi.remainingMs()!!
        println("레스트 모드 전후 남은 시간: ${before / MINUTE_MS}분 → ${after / MINUTE_MS}분, 경유지 ${navi.via.map { it.name }}")
        // 쉼터를 경유지로 넣어도 같은 속도로 달린다: 남은 시간은 들르는 만큼만 는다 (예전에는 22분 → 95분)
        assertTrue("${before / MINUTE_MS} -> ${after / MINUTE_MS}", after - before < 15 * MINUTE_MS)
        assertEquals(true, rig.engine.state.onExpressway)
    }

    @Test
    fun longerCourseRunsAsPlanned() {
        val rig = DemoRig()
        val seoulStation = LatLng(37.5547, 126.9707)
        // 곧은 거리 약 22km: 양 끝 시내 길과 가운데 고속도로를 섞은 속도
        val hanam = Place("스타필드 하남", LatLng(37.5452, 127.2239), PlaceKind.CURRENT)
        rig.startFree(seoulStation, "서울역")
        rig.setNaviDestination(hanam)
        rig.openMode(DriveMode.LONGER)
        rig.setExtraMinutes(30)
        val direct = rig.drive.state.directMs!!
        rig.planCourses()
        rig.advance(1_000)
        val pick = rig.drive.state.options.first { it.via.isNotEmpty() }
        rig.chooseCourse(pick)
        rig.advance(1_000)
        assertEquals(DriveStep.ACTIVE, rig.drive.state.step)
        val planned = direct + pick.addedMs - pick.stayMs
        val naviRemain = rig.navi!!.remainingMs()!!
        println("롱웨이 +30분: 바로 ${direct / MINUTE_MS}분, 계획 ${planned / MINUTE_MS}분, 내비 ${naviRemain / MINUTE_MS}분 (${pick.via.map { it.name }})")
        // 고른 코스를 달리는 시간이 계획과 맞는다 (예전에는 경유지로 길이 40km를 넘으면 고속도로 속도가 되어 계획 102분, 내비 30분)
        assertEquals(planned.toDouble(), naviRemain.toDouble(), 1.5 * MINUTE_MS)
        assertTrue(naviRemain > direct)
    }
}
