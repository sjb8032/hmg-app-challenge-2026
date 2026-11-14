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

    @Test
    fun dropsCompanyOrBareNamesNextToAProperlyNamedRestArea() {
        val company = RestArea("a", "연합진흥고성", RestAreaType.SERVICE_AREA, LatLng(35.04797, 128.26693), onExpressway = true)
        val proper = RestArea("b", "공룡나라휴게소", RestAreaType.SERVICE_AREA, LatLng(35.04900, 128.26900), onExpressway = true)
        val bare = RestArea("c", "하남", RestAreaType.SERVICE_AREA, LatLng(37.53108, 127.20675), onExpressway = true)
        val dream = RestArea("d", "하남드림휴게소", RestAreaType.SERVICE_AREA, LatLng(37.53140, 127.20710), onExpressway = true)
        // 제대로 된 이웃이 없는 고속도로 항목은 남긴다 (어디인지 모르는 것보다 낫다).
        val alone = RestArea("e", "연합진흥문경", RestAreaType.SERVICE_AREA, LatLng(36.60000, 128.10000), onExpressway = true)
        val operator = RestArea("f", "파라다이스코리아원주상부산방향휴게소", RestAreaType.SERVICE_AREA, LatLng(37.43364, 127.92949), onExpressway = true)
        val kept = RestAreaCleaner.clean(listOf(company, proper, bare, dream, alone, operator)).map { it.id }
        assertEquals(listOf("b", "d", "e"), kept)
    }

    @Test
    fun keepsBothSidesOfAnUpDownPairAndGuidesToTheSideOfTravel() {
        // 서울양양고속도로 가평 휴게소: 이름에 상행·하행만 있고 방향 지명이 없다.
        // 예전에는 둘이 같은 곳으로 합쳐져 상행만 남았고, 양양 쪽으로 가는 차를 건너편(상행) 휴게소로 안내했다.
        val up = area("가평 휴게소 (상행)", RestAreaType.SERVICE_AREA, LatLng(37.70107, 127.54415), motorway = true)
        val down = area("가평 휴게소 (하행)", RestAreaType.SERVICE_AREA, LatLng(37.70200, 127.54590), motorway = true)
        val kept = RestAreaCleaner.clean(listOf(up, down))
        assertEquals(listOf("가평 휴게소 (상행)", "가평 휴게소 (하행)"), kept.map { it.name })

        val finder = RestAreaFinder(kept)
        // 남양주에서 동쪽(강릉·양양)으로 가는 차는 하행, 서울로 돌아오는 차는 상행
        val eastbound = finder.findAhead(LatLng(37.62, 127.40), headingDeg = 60.0, speedKmh = 95.0, expresswayOnly = true)
        assertEquals("가평 휴게소 (하행)", eastbound.best?.area?.name)
        val westbound = finder.findAhead(LatLng(37.78, 127.70), headingDeg = 240.0, speedKmh = 95.0, expresswayOnly = true)
        assertEquals("가평 휴게소 (상행)", westbound.best?.area?.name)

        // 경부고속도로 오산 휴게소: 부산 쪽(남쪽)으로 가면 하행
        val osanUp = area("오산 휴게소 (상행)", RestAreaType.SERVICE_AREA, LatLng(37.17812, 127.03755), motorway = true)
        val osanDown = area("오산 휴게소 (하행)", RestAreaType.SERVICE_AREA, LatLng(37.17654, 127.03742), motorway = true)
        val south = RestAreaFinder(RestAreaCleaner.clean(listOf(osanUp, osanDown)))
            .findAhead(LatLng(37.30, 127.05), headingDeg = 180.0, speedKmh = 95.0, expresswayOnly = true)
        assertEquals("오산 휴게소 (하행)", south.best?.area?.name)
        // 방향 지명이 이미 있으면 그대로 둔다
        val named = area("홍천 휴게소 (양양 방향)", RestAreaType.SERVICE_AREA, LatLng(37.75775, 128.00431), motorway = true)
            .copy(directionLabel = "양양", directionTarget = LatLng(38.0806, 128.6241))
        assertEquals(named, RestAreaCleaner.withUpDown(named))
    }
}
