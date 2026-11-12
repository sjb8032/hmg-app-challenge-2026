package kr.contest.pleosapp.data

import kr.contest.pleosapp.core.CoursePoint
import kr.contest.pleosapp.core.CourseTag
import kr.contest.pleosapp.core.CourseTag.BRIDGE
import kr.contest.pleosapp.core.CourseTag.LAKE
import kr.contest.pleosapp.core.CourseTag.MOUNTAIN
import kr.contest.pleosapp.core.CourseTag.NIGHT_VIEW
import kr.contest.pleosapp.core.CourseTag.PARK
import kr.contest.pleosapp.core.CourseTag.RIVER
import kr.contest.pleosapp.core.CourseTag.SEA
import kr.contest.pleosapp.core.LatLng
import kr.contest.pleosapp.core.RestArea
import kr.contest.pleosapp.core.RestAreaType

/**
 * 경유지로 넣을 만한 경치 좋은 지점 (팀이 고른 목록, 시연용 표본).
 *
 * 좌표는 지도에서 읽은 대략 위치다. 실제 서비스에서는 팀이 직접 달려 보고 차가 들어갈 수 있는 지점으로 고친다.
 */
object CourseCatalog {
    val POINTS: List<CoursePoint> = listOf(
        // 서울: 야경·산길
        point("bugak", "북악스카이웨이 팔각정", 37.6023, 126.9810, NIGHT_VIEW, MOUNTAIN),
        point("inwang", "인왕산 스카이웨이", 37.5880, 126.9625, NIGHT_VIEW, MOUNTAIN),
        point("naksan", "낙산공원", 37.5806, 127.0076, NIGHT_VIEW, PARK),
        point("sowol", "남산 소월로", 37.5535, 126.9800, NIGHT_VIEW, MOUNTAIN),
        point("eungbong", "응봉산", 37.5506, 127.0322, NIGHT_VIEW),
        // 서울: 한강
        point("banpo", "반포한강공원", 37.5103, 126.9959, RIVER, NIGHT_VIEW, PARK, BRIDGE),
        point("jamsugyo", "잠수교", 37.5155, 126.9945, RIVER, BRIDGE),
        point("nodeul", "노들섬", 37.5176, 126.9594, RIVER, NIGHT_VIEW, BRIDGE),
        point("yeouido", "여의도한강공원", 37.5284, 126.9326, RIVER, PARK),
        point("ttukseom", "뚝섬한강공원", 37.5296, 127.0695, RIVER, PARK),
        point("jamsil", "잠실한강공원", 37.5179, 127.0860, RIVER),
        point("mangwon", "망원한강공원", 37.5547, 126.8950, RIVER, PARK),
        point("seonyudo", "선유도공원", 37.5433, 126.8995, RIVER, PARK, BRIDGE),
        // 서울: 공원·호수
        point("seoulforest", "서울숲", 37.5444, 127.0374, PARK),
        point("olympic", "올림픽공원", 37.5207, 127.1214, PARK),
        point("seokchon", "석촌호수", 37.5087, 127.1003, LAKE, PARK),
        point("haneul", "하늘공원", 37.5683, 126.8853, PARK),
        point("yangjae", "양재 시민의숲", 37.4695, 127.0365, PARK),
        // 강릉: 바다·호수
        point("gyeongpo-lake", "경포호", 37.7955, 128.8968, LAKE),
        point("gyeongpo-beach", "경포해변", 37.8055, 128.9083, SEA),
        point("anmok", "안목해변", 37.7722, 128.9474, SEA),
        point("jeongdongjin", "정동진", 37.6905, 129.0336, SEA),
        point("daegwallyeong", "대관령 옛길", 37.6880, 128.7600, MOUNTAIN),
    )

    private fun point(id: String, name: String, lat: Double, lon: Double, vararg tags: CourseTag) =
        CoursePoint(id, name, LatLng(lat, lon), tags.toSet())
}

/**
 * 시내에서 잠깐 세워 쉴 수 있는 곳 (시연용 표본: 서울 공원 주차장·공영 주차장).
 *
 * 실제 서비스에서는 공공데이터포털 '전국주차장정보표준데이터'와 공원 정보로 바꾼다. 좌표는 대략 위치다.
 */
object CityRestPlaces {
    const val ATTRIBUTION = "시내 쉼터는 시연용 표본 (공공데이터로 교체 예정)"

    val SAMPLES: List<RestArea> = listOf(
        park("banpo-p", "반포한강공원 주차장", 37.5108, 126.9958),
        park("jamwon-p", "잠원한강공원 주차장", 37.5208, 127.0115),
        park("ichon-p", "이촌한강공원 주차장", 37.5175, 126.9705),
        park("yeouido-p", "여의도한강공원 주차장", 37.5270, 126.9330),
        park("ttukseom-p", "뚝섬한강공원 주차장", 37.5298, 127.0668),
        park("jamsil-p", "잠실한강공원 주차장", 37.5185, 127.0838),
        park("mangwon-p", "망원한강공원 주차장", 37.5552, 126.8972),
        park("seoulforest-p", "서울숲 주차장", 37.5436, 127.0418),
        park("olympic-p", "올림픽공원 주차장", 37.5190, 127.1170),
        park("yangjae-p", "양재 시민의숲 주차장", 37.4702, 127.0368),
        parking("sejongno-p", "세종로 공영주차장", 37.5727, 126.9771),
        parking("jongmyo-p", "종묘 공영주차장", 37.5718, 126.9937),
        parking("tancheon-p", "탄천 공영주차장", 37.5145, 127.0695),
    )

    private fun park(id: String, name: String, lat: Double, lon: Double) =
        RestArea(id = "city-$id", name = name, type = RestAreaType.PARK, position = LatLng(lat, lon))

    private fun parking(id: String, name: String, lat: Double, lon: Double) =
        RestArea(id = "city-$id", name = name, type = RestAreaType.PARKING, position = LatLng(lat, lon))
}
