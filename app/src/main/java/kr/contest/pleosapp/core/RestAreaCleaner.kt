package kr.contest.pleosapp.core

/**
 * 내장 쉼터 데이터(OpenStreetMap)에서 차를 세워 쉴 수 없는 항목을 거른다.
 *
 * OSM의 'services'·'rest_area' 태그에는 퀵서비스·택시 승강장·주유소·정비소·경로당처럼 쉴 곳이 아닌 것도 섞여 있고,
 * 좌표가 엉뚱한 곳(예: 충주 앙성휴게소가 서울 용산에 찍힘)에 있는 것도 있다.
 * - 고속도로 본선 옆이 아닌 항목은 이름에 휴게·쉼터·졸음·만남의광장 같은 말이 있어야 남긴다.
 * - 이름이 쉴 곳이 아님을 드러내면 뺀다 (퀵·택시·주유소·자전거 등).
 * - 이름만 있는 항목(정자·쉼터·휴게소)은 어디인지 말로 안내할 수 없어 뺀다.
 * - 큰 도시 한가운데의 고속도로 밖 휴게소·쉼터는 대부분 잘못 찍힌 좌표라 뺀다 (시내는 공원·공영 주차장으로 안내).
 */
object RestAreaCleaner {
    private val REST_WORDS = listOf("휴게", "쉼터", "졸음", "만남의", "광장")

    private val NOISE_WORDS = listOf(
        "퀵", "택시", "승강장", "매표소", "방문자센터", "주유소", "도시가스", "오토큐",
        "서비스센터", "서비스 센터", "카서비스", "경비실", "휴게실", "자전거", "안전지대",
    )

    private val BARE_NAMES = setOf("휴게소", "쉼터", "정자", "팔각정", "팔각정자", "간이휴게소")

    /** 큰 도시 한가운데 (반경 안의 고속도로 밖 휴게소·쉼터는 뺀다) */
    data class Zone(val name: String, val center: LatLng, val radiusMeters: Double)

    val URBAN_ZONES: List<Zone> = listOf(
        Zone("서울", LatLng(37.5665, 126.9780), 15_000.0),
        Zone("부산", LatLng(35.1796, 129.0756), 12_000.0),
        Zone("인천", LatLng(37.4563, 126.7052), 10_000.0),
        Zone("대구", LatLng(35.8714, 128.6014), 10_000.0),
        Zone("대전", LatLng(36.3504, 127.3845), 9_000.0),
        Zone("광주", LatLng(35.1595, 126.8526), 9_000.0),
        Zone("울산", LatLng(35.5384, 129.3114), 8_000.0),
        Zone("수원", LatLng(37.2636, 127.0286), 7_000.0),
        Zone("성남", LatLng(37.4200, 127.1265), 6_000.0),
        Zone("고양", LatLng(37.6584, 126.8320), 6_000.0),
        Zone("부천", LatLng(37.5034, 126.7660), 5_000.0),
        Zone("창원", LatLng(35.2280, 128.6811), 7_000.0),
        Zone("청주", LatLng(36.6424, 127.4890), 7_000.0),
        Zone("전주", LatLng(35.8242, 127.1480), 7_000.0),
    )

    fun clean(areas: List<RestArea>): List<RestArea> = areas.filter(::keep)

    fun keep(area: RestArea): Boolean {
        // 시내 쉴 곳(공원·공영 주차장)은 팀이 고른 목록이라 그대로 둔다.
        if (area.type == RestAreaType.PARK || area.type == RestAreaType.PARKING) return true
        val name = area.name
        if (NOISE_WORDS.any { it in name }) return false
        if (RestMateText.spokenName(name).replace(" ", "") in BARE_NAMES) return false
        if (area.onExpressway) return true
        if (REST_WORDS.none { it in name }) return false
        val looseType = area.type == RestAreaType.SERVICE_AREA || area.type == RestAreaType.REST_STOP
        return !(looseType && inUrbanZone(area.position))
    }

    fun inUrbanZone(position: LatLng): Boolean =
        URBAN_ZONES.any { Geo.distanceMeters(position, it.center) <= it.radiusMeters }
}
