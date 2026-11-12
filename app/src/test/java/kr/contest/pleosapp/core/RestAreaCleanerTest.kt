package kr.contest.pleosapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestAreaCleanerTest {
    /** 충주 근처 (큰 도시 밖) */
    private val rural = LatLng(36.90, 127.90)

    /** 서울 용산 (서울 한가운데) */
    private val downtown = LatLng(37.5300, 126.9700)

    private fun area(name: String, type: RestAreaType, at: LatLng, motorway: Boolean = false) =
        RestArea(id = name, name = name, type = type, position = at, onExpressway = motorway)

    @Test
    fun dropsPlacesThatAreNotForRestingInTheCar() {
        assertFalse(RestAreaCleaner.keep(area("서대문퀵서비스", RestAreaType.SERVICE_AREA, rural)))
        assertFalse(RestAreaCleaner.keep(area("청량리역 택시승강장", RestAreaType.SERVICE_AREA, rural)))
        assertFalse(RestAreaCleaner.keep(area("산동주유소", RestAreaType.SERVICE_AREA, rural)))
        assertFalse(RestAreaCleaner.keep(area("비내 자전거 쉼터", RestAreaType.REST_STOP, rural)))
        // 쉴 곳을 뜻하는 말이 없는 고속도로 밖 항목 (정비소·경로당 등)
        assertFalse(RestAreaCleaner.keep(area("성지카서비스", RestAreaType.SERVICE_AREA, rural)))
        assertFalse(RestAreaCleaner.keep(area("퇴산리 노모당", RestAreaType.REST_STOP, rural)))
    }

    @Test
    fun dropsNamesThatCannotBeSpokenAsAPlace() {
        assertFalse(RestAreaCleaner.keep(area("정자", RestAreaType.REST_STOP, rural)))
        assertFalse(RestAreaCleaner.keep(area("휴게소", RestAreaType.SERVICE_AREA, rural)))
        assertFalse(RestAreaCleaner.keep(area("팔각정자", RestAreaType.REST_STOP, rural)))
    }

    @Test
    fun dropsMisplacedOffMotorwayAreasDowntown() {
        // 충주 앙성휴게소가 서울 한가운데에 찍혀 있던 경우
        assertFalse(RestAreaCleaner.keep(area("앙성휴게소", RestAreaType.SERVICE_AREA, downtown)))
        assertTrue(RestAreaCleaner.keep(area("앙성휴게소", RestAreaType.SERVICE_AREA, rural)))
    }

    @Test
    fun keepsRealRestAreasAndCityPlaces() {
        assertTrue(RestAreaCleaner.keep(area("횡성휴게소", RestAreaType.SERVICE_AREA, rural, motorway = true)))
        assertTrue(RestAreaCleaner.keep(area("대포졸음쉼터", RestAreaType.DROWSY_SHELTER, rural)))
        assertTrue(RestAreaCleaner.keep(area("서울만남의광장 휴게소 (부산방향)", RestAreaType.SERVICE_AREA, LatLng(37.4600, 127.0400), motorway = true)))
        assertTrue(RestAreaCleaner.keep(area("세종로 공영주차장", RestAreaType.PARKING, downtown)))
        assertTrue(RestAreaCleaner.keep(area("반포한강공원 주차장", RestAreaType.PARK, downtown)))
        assertEquals(1, RestAreaCleaner.clean(listOf(area("정자", RestAreaType.REST_STOP, rural), area("대포졸음쉼터", RestAreaType.DROWSY_SHELTER, rural))).size)
    }

    private fun directed(id: String, name: String, label: String?, at: LatLng) =
        RestArea(id, name, RestAreaType.SERVICE_AREA, at, directionLabel = label, onExpressway = true)

    @Test
    fun mergesTheSamePlaceListedTwice() {
        val a = directed("a", "용인휴게소 강릉방향", "강릉", LatLng(37.24553, 127.24235))
        val b = directed("b", "용인 휴게소 (강릉 방향)", "강릉", LatLng(37.24600, 127.24246))
        val other = directed("c", "용인 휴게소 (인천 방향)", "인천", LatLng(37.24797, 127.23789))
        val bare = directed("d", "평창", null, LatLng(37.61104, 128.46456))
        val pc = directed("e", "평창 휴게소 (인천 방향)", "인천", LatLng(37.61041, 128.46363))
        val kept = RestAreaCleaner.dedupe(listOf(a, b, other, bare, pc)).map { it.id }
        // 같은 방향 두 이름은 하나로 (괄호로 적은 이름을 남김), 반대 방향은 그대로, 이름만 있는 '평창'은 옆 휴게소와 합친다.
        assertEquals(listOf("b", "c", "e"), kept)
        assertEquals("용인", RestAreaCleaner.baseKey(a))
        assertEquals("용인", RestAreaCleaner.baseKey(b))
    }

    @Test
    fun keepsBothSidesOfAShelter() {
        val east = RestArea("x", "대신졸음쉼터 원주방향", RestAreaType.DROWSY_SHELTER, LatLng(37.38401, 127.60293), directionLabel = "원주", onExpressway = true)
        val west = RestArea("y", "대신졸음쉼터 광주방향", RestAreaType.DROWSY_SHELTER, LatLng(37.38463, 127.60389), directionLabel = "광주", onExpressway = true)
        assertEquals(2, RestAreaCleaner.dedupe(listOf(east, west)).size)
    }
}
