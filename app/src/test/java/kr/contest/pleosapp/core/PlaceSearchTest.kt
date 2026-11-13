package kr.contest.pleosapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 장소 검색: 응답 읽기(OpenStreetMap Nominatim·카카오 로컬), 앱에 있는 곳 찾기, 결과 합치기 */
class PlaceSearchTest {
    private val gwanghwamun = LatLng(37.5759, 126.9768)
    private val nonhyeon = LatLng(37.5110, 127.0214)

    private val nominatim = """
        [
          {"place_id":1,"licence":"Data © OpenStreetMap contributors","osm_type":"way","osm_id":2,
           "lat":"37.5759880","lon":"126.9768370","category":"historic","type":"city_gate","place_rank":30,
           "importance":0.55,"addresstype":"historic","name":"광화문",
           "display_name":"광화문, 사직로, 세종로, 종로구, 서울특별시, 03045, 대한민국",
           "address":{"historic":"광화문","road":"사직로","house_number":"161","quarter":"세종로","borough":"종로구",
                      "city":"서울특별시","postcode":"03045","country":"대한민국","country_code":"kr"}},
          {"place_id":3,"lat":"37.5714","lon":"126.9765","category":"railway","type":"station",
           "name":"광화문역","display_name":"광화문역, 세종대로, 종로구, 서울특별시, 대한민국",
           "address":{"railway":"광화문역","road":"세종대로","borough":"종로구","city":"서울특별시","country":"대한민국"}},
          {"place_id":4,"lat":"37.57145","lon":"126.97652","category":"railway","type":"subway_entrance",
           "name":"광화문역","display_name":"광화문역, 세종대로, 종로구, 서울특별시, 대한민국"},
          {"place_id":5,"lat":"37.5","lon":"127.0","name":"",
           "display_name":"\"따옴표\" 거리 \\ 이름, 강남구, 서울특별시, 06000, 대한민국"},
          {"place_id":6,"lat":"bad","lon":"127.0","name":"좌표 없음"}
        ]
    """.trimIndent()

    @Test
    fun readsNominatimResults() {
        val hits = PlaceSearch.parseNominatim(nominatim)!!
        assertEquals(4, hits.size)
        val gate = hits[0]
        assertEquals("광화문", gate.name)
        assertEquals("서울특별시 종로구 사직로 161", gate.address)
        assertEquals(37.5759880, gate.position.lat, 1e-9)
        assertEquals(126.9768370, gate.position.lon, 1e-9)
        assertEquals(PlaceSource.OSM, gate.source)
        assertEquals("명소", gate.category)
        assertEquals("역", hits[1].category)
        // 주소 칸이 없으면 display_name을 큰 곳부터 (우편번호·나라 빼고)
        assertEquals("서울특별시 종로구 세종대로", hits[2].address)
        // 이름이 비면 display_name 첫 칸 (따옴표·역슬래시 그대로)
        assertEquals("\"따옴표\" 거리 \\ 이름", hits[3].name)
        assertEquals("서울특별시 강남구", hits[3].address)
    }

    @Test
    fun readsNominatimReverseAndErrors() {
        val hit = PlaceSearch.parseNominatimReverse(
            """{"lat":"37.5665","lon":"126.9780","name":"","display_name":"110, 세종대로, 중구, 서울특별시, 대한민국",
               "address":{"road":"세종대로","house_number":"110","borough":"중구","city":"서울특별시"}}""",
        )!!
        assertEquals("세종대로 110", hit.name)
        assertEquals("서울특별시 중구", hit.address)
        assertNull(PlaceSearch.parseNominatimReverse("""{"error":"Unable to geocode"}"""))
        // 오류 화면·잘린 응답은 '찾은 곳 없음'([])과 구별한다 (기억해 두지 않고 '검색 서비스가 답하지 않음'으로)
        assertNull(PlaceSearch.parseNominatim("<html>Bandwidth limit exceeded</html>"))
        assertNull(PlaceSearch.parseNominatim("""{"error":"Too many requests"}"""))
        assertNull(PlaceSearch.parseNominatim("""[{"lat":"37.5","""))
        assertTrue(PlaceSearch.parseNominatim("[]")!!.isEmpty())
    }

    @Test
    fun readsKakaoKeywordAndReverse() {
        val json = """
            {"documents":[
              {"address_name":"서울 종로구 세종로 1-68","category_group_code":"","category_group_name":"",
               "category_name":"여행 > 관광,명소 > 문화유적 > 궁","distance":"230","id":"8","phone":"",
               "place_name":"광화문","place_url":"http://place.map.kakao.com/8","road_address_name":"서울 종로구 사직로 161",
               "x":"126.976861","y":"37.575987"},
              {"address_name":"서울 종로구 세종로 81-3","category_group_code":"SW8","category_group_name":"지하철역",
               "category_name":"교통,수송 > 지하철,전철 > 수도권5호선","distance":"550","place_name":"광화문역 5호선",
               "road_address_name":"","x":"126.976545","y":"37.571591"}
            ],"meta":{"is_end":true,"pageable_count":2,"total_count":2}}
        """.trimIndent()
        val hits = PlaceSearch.parseKakaoKeyword(json)!!
        assertEquals(listOf("광화문", "광화문역 5호선"), hits.map { it.name })
        assertEquals("서울 종로구 사직로 161", hits[0].address)
        assertEquals("궁", hits[0].category)
        // 도로명 주소가 없으면 지번 주소
        assertEquals("서울 종로구 세종로 81-3", hits[1].address)
        assertEquals("지하철역", hits[1].category)
        assertEquals(37.571591, hits[1].position.lat, 1e-9)
        assertEquals(PlaceSource.KAKAO, hits[1].source)

        val at = LatLng(37.5665, 126.978)
        val reverse = PlaceSearch.parseKakaoReverse(
            """{"documents":[{"road_address":{"address_name":"서울 중구 세종대로 110","building_name":"서울특별시청"},
               "address":{"address_name":"서울 중구 태평로1가 31"}}],"meta":{"total_count":1}}""",
            at,
        )!!
        assertEquals("서울특별시청", reverse.name)
        assertEquals("서울 중구 세종대로 110", reverse.address)
        assertEquals(at, reverse.position)
        assertEquals(PlaceSource.MAP, reverse.source)
        assertNull(PlaceSearch.parseKakaoReverse("""{"documents":[],"meta":{"total_count":0}}""", at))
        // 키가 틀렸을 때의 오류 응답은 결과 없음과 다르다
        assertNull(PlaceSearch.parseKakaoKeyword("""{"errorType":"AccessDeniedError","message":"cannot find app"}"""))
        assertTrue(PlaceSearch.parseKakaoKeyword("""{"documents":[],"meta":{"total_count":0}}""")!!.isEmpty())
    }

    @Test
    fun localSearchMatchesNamesAndPutsBetterMatchesFirst() {
        val candidates = listOf(
            PlaceHit("반포한강공원 주차장", "", LatLng(37.5108, 126.9958), PlaceSource.APP),
            PlaceHit("한강공원 쉼터", "", LatLng(37.60, 127.10), PlaceSource.APP),
            PlaceHit("여의도한강공원", "", LatLng(37.5284, 126.9326), PlaceSource.APP),
            PlaceHit("서울숲", "서울 성동구", LatLng(37.5444, 127.0374), PlaceSource.APP),
            PlaceHit("집", "", LatLng(37.5145, 127.1059), PlaceSource.APP),
        )
        // 띄어쓰기를 무시하고, 검색어로 시작하는 이름이 먼저, 그다음 가까운 순
        val park = PlaceSearch.local("한강 공원", candidates, nonhyeon)
        assertEquals(listOf("한강공원 쉼터", "반포한강공원 주차장", "여의도한강공원"), park.map { it.name })
        assertEquals(listOf("서울숲"), PlaceSearch.local("성동구", candidates, null).map { it.name })
        assertTrue(PlaceSearch.local("  ", candidates, null).isEmpty())
        assertEquals(1, PlaceSearch.local("한강", candidates, nonhyeon, limit = 1).size)
    }

    @Test
    fun mergeKeepsAppPlacesFirstAndDropsDuplicates() {
        val local = listOf(PlaceHit("광화문 약속 장소", "", gwanghwamun, PlaceSource.APP))
        val online = PlaceSearch.parseNominatim(nominatim)!!
        val merged = PlaceSearch.merge(local, online)
        // 앱에 있는 곳과 같은 자리(25m 안)이고 이름이 겹치는 '광화문'은 빼고, 같은 이름의 역 출입구도 하나로
        assertEquals(listOf("광화문 약속 장소", "광화문역", "\"따옴표\" 거리 \\ 이름"), merged.map { it.name })
        assertEquals(2, PlaceSearch.merge(local, online, limit = 2).size)
    }

    @Test
    fun mergeKeepsDifferentPlacesThatShareANameOrASpot() {
        val gangnam = LatLng(37.4979, 127.0276)
        // 지점 이름이 안 붙는 체인점: 250m 떨어진 두 '스타벅스'는 다른 곳
        val a = PlaceHit("스타벅스", "서울특별시 강남구 강남대로 390", gangnam, PlaceSource.OSM, "음식점")
        val b = PlaceHit("스타벅스", "서울특별시 서초구 서초대로77길 54", Geo.destinationPoint(gangnam, 200.0, 250.0), PlaceSource.OSM, "음식점")
        assertEquals(2, PlaceSearch.merge(emptyList(), listOf(a, b)).size)
        // 건물 윤곽과 점처럼 같은 이름이 바로 옆(80m 안)이면 하나
        val a2 = a.copy(position = Geo.destinationPoint(gangnam, 90.0, 40.0))
        assertEquals(1, PlaceSearch.merge(emptyList(), listOf(a, a2)).size)
        // 역·공항은 출입구가 넓게 흩어져 있어 300m 안의 같은 이름은 하나
        val station = PlaceHit("서울역", "", LatLng(37.5547, 126.9707), PlaceSource.OSM, "역")
        val exit = station.copy(position = Geo.destinationPoint(station.position, 0.0, 250.0), category = "철도")
        assertEquals(1, PlaceSearch.merge(emptyList(), listOf(station, exit)).size)
        // 이름이 전혀 다르면 붙어 있어도 다른 곳 (앱의 대략 좌표가 실제 다른 곳을 가리지 않게)
        val app = PlaceHit("낙산공원", "", LatLng(37.5806, 127.0076), PlaceSource.APP, "경치 지점")
        val cafe = PlaceHit("이화동 카페", "", Geo.destinationPoint(app.position, 45.0, 15.0), PlaceSource.OSM, "음식점")
        assertEquals(2, PlaceSearch.merge(listOf(app), listOf(cafe)).size)
    }

    @Test
    fun hitFromTheNaviKeepsItsPlace() {
        // 내비 즐겨찾기를 검색 창에서 고르면 POI 번호·종류를 그대로 넘긴다 (실제 내비 경로 요청, 즐겨찾기의 기본 기다리기)
        val academy = Place("아이 학원", LatLng(37.4946, 127.0576), PlaceKind.BOOKMARK, poiId = "123", poiSubId = "0", address = "대치동")
        val hit = PlaceHit.of(academy)
        assertEquals(academy, hit.toPlace())
        assertEquals(academy.copy(kind = PlaceKind.CURRENT), hit.toPlace(PlaceKind.CURRENT))
        assertEquals("즐겨찾기", hit.category)
        // 검색 서비스에서 찾은 곳은 '검색한 곳'
        val found = PlaceHit("롯데월드타워", "서울특별시 송파구 올림픽로 300", LatLng(37.5126, 127.1025), PlaceSource.OSM)
        assertEquals(Place("롯데월드타워", found.position, PlaceKind.SEARCH, address = found.address), found.toPlace())
        assertEquals(PlaceKind.CURRENT, found.toPlace(PlaceKind.CURRENT).kind)
    }

    @Test
    fun urlsAskInKoreanNearTheCar() {
        val url = PlaceSearch.nominatimSearchUrl("서울역 2번 출구", nonhyeon)
        assertTrue(url, url.startsWith("https://nominatim.openstreetmap.org/search?"))
        assertTrue(url, "accept-language=ko" in url && "countrycodes=kr" in url && "format=jsonv2" in url)
        assertTrue(url, url.endsWith("&q=%EC%84%9C%EC%9A%B8%EC%97%AD+2%EB%B2%88+%EC%B6%9C%EA%B5%AC"))
        assertTrue(url, "&viewbox=126.571400,37.961000,127.471400,37.061000" in url)
        assertTrue(!PlaceSearch.nominatimSearchUrl("a", null).contains("viewbox"))
        assertEquals(
            "https://nominatim.openstreetmap.org/reverse?format=jsonv2&addressdetails=1&accept-language=ko&zoom=18&lat=37.511000&lon=127.021400",
            PlaceSearch.nominatimReverseUrl(nonhyeon),
        )
        assertEquals(
            "https://dapi.kakao.com/v2/local/search/keyword.json?size=15&x=127.021400&y=37.511000&query=%EA%B0%95%EB%82%A8%EC%97%AD",
            PlaceSearch.kakaoKeywordUrl("강남역", nonhyeon),
        )
    }

    @Test
    fun distanceLabels() {
        assertEquals("10m", PlaceSearch.distanceLabel(3.0))
        assertEquals("950m", PlaceSearch.distanceLabel(948.0))
        assertEquals("1.2km", PlaceSearch.distanceLabel(1_234.0))
        assertEquals("15km", PlaceSearch.distanceLabel(15_400.0))
    }

    @Test
    fun miniJsonReadsEscapesAndNumbers() {
        val v = MiniJson.parse("""{"a":[1,-2.5e2,true,false,null],"b":"A한\n","c":{}}""") as Map<*, *>
        assertEquals(listOf(1.0, -250.0, true, false, null), v["a"])
        assertEquals("A한\n", v["b"])
        assertEquals(emptyMap<String, Any?>(), v["c"])
        assertNull(MiniJson.parse("{\"a\":1"))
        assertNull(MiniJson.parse("[1,2] x"))
    }
}
