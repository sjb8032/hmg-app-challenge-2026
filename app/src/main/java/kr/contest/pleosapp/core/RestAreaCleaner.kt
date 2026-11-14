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
 * - 같은 곳이 이름만 조금 다르게 두 번 들어 있으면 ("용인휴게소 강릉방향"과 "용인 휴게소 (강릉 방향)") 하나만 남긴다.
 * - 운영 회사 이름이나 지명만 적힌 고속도로 항목("연합진흥고성", "하남")은 300m 안에 제대로 된 이름의
 *   같은 곳("공룡나라휴게소", "하남드림휴게소")이 있으면 뺀다 (안내 음성이 회사 이름을 읽지 않게).
 */
object RestAreaCleaner {
    /** 이 거리 안에 같은 종류·같은 이름(방향 표시 뺀)이 또 있으면 같은 곳으로 본다 */
    private const val SAME_PLACE_METERS = 250.0
    private val TYPE_TAILS = listOf("졸음쉼터", "휴게소", "쉼터")

    private val REST_WORDS = listOf("휴게", "쉼터", "졸음", "만남의", "광장")

    private val NOISE_WORDS = listOf(
        "퀵", "택시", "승강장", "매표소", "방문자센터", "주유소", "도시가스", "오토큐",
        "서비스센터", "서비스 센터", "카서비스", "경비실", "휴게실", "자전거", "안전지대",
        // 휴게소 운영 회사 이름이 붙은 항목 ("파라다이스코리아원주상부산방향휴게소": 바로 옆에 '원주휴게소'가 따로 있음)
        "코리아",
    )

    /** 이름이 이상한 항목을 뺄 때, 이 거리 안에 제대로 된 이름의 같은 종류가 있어야 한다 */
    private const val ODD_TWIN_METERS = 300.0

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

    fun clean(areas: List<RestArea>): List<RestArea> = dropOddTwins(dedupe(areas.filter(::keep).map(::withUpDown)))

    /** 서울 시청. 고속도로는 서울 쪽이 상행이다 */
    private val SEOUL = LatLng(37.5667, 126.9783)

    /**
     * 이름에 '상행'·'하행'만 적고 방향 지명이 없는 쉼터에 방향을 붙인다.
     * 상행은 서울로 가는 차, 하행은 서울에서 멀어지는 차가 들르는 쪽으로 본다 (하행은 서울의 반대쪽 점을 향하게).
     * 방향이 없으면 마주 보는 두 쉼터가 같은 곳으로 합쳐져, 반대편 쉼터로 안내할 수 있다 (가평 휴게소 상행·하행).
     */
    fun withUpDown(area: RestArea): RestArea {
        if (area.directionTarget != null || area.directionLabel != null) return area
        val up = "상행" in area.name
        val down = "하행" in area.name
        if (up == down) return area
        val p = area.position
        return if (up) {
            area.copy(directionLabel = "서울", directionTarget = SEOUL)
        } else {
            area.copy(directionLabel = "하행", directionTarget = LatLng(2 * p.lat - SEOUL.lat, 2 * p.lon - SEOUL.lon))
        }
    }

    /**
     * 이름에 휴게·쉼터 같은 말이 없는 항목(회사 이름·지명만)은 300m 안에 제대로 된 이름의 같은 종류가 있으면 뺀다.
     * 그런 이웃이 없으면 (고속도로 본선 옆이라 남긴 곳) 그대로 둔다.
     */
    fun dropOddTwins(areas: List<RestArea>): List<RestArea> {
        val proper = areas.filter { a -> REST_WORDS.any { it in a.name } }
        return areas.filter { a ->
            REST_WORDS.any { it in a.name } ||
                proper.none { p -> p.type == a.type && Geo.distanceMeters(p.position, a.position) <= ODD_TWIN_METERS }
        }
    }

    /**
     * 같은 곳이 두 번 들어 있으면 하나만 남긴다: 같은 종류, 250m 안, 방향 표시를 뺀 이름이 같고, 같은 쪽(진행 방향)일 때.
     * 괄호로 방향을 적은 이름("○○ 휴게소 (강릉 방향)")을 먼저 남긴다. 순서는 그대로 둔다.
     */
    fun dedupe(areas: List<RestArea>): List<RestArea> {
        val dropped = HashSet<String>()
        val groups = areas.groupBy { it.type to baseKey(it) }
        for (group in groups.values) {
            if (group.size < 2) continue
            val kept = ArrayList<RestArea>()
            for (a in group.sortedBy { rank(it) }) {
                val twin = kept.any { k -> sameWay(k, a) && Geo.distanceMeters(k.position, a.position) <= SAME_PLACE_METERS }
                if (twin) dropped += a.id else kept += a
            }
        }
        return if (dropped.isEmpty()) areas else areas.filterNot { it.id in dropped }
    }

    /**
     * 두 쉼터가 같은 쪽(같은 진행 방향) 것인지. 방향을 모르는 쪽이 있으면 같다고 본다.
     * 방향 지명이 달라도 가리키는 쪽이 비슷하면 같은 쪽이다 ('오산 휴게소 (상행)'과 '오산휴게소(과천방향)').
     */
    private fun sameWay(a: RestArea, b: RestArea): Boolean {
        if (a.directionLabel == null || b.directionLabel == null || a.directionLabel == b.directionLabel) return true
        val ta = a.directionTarget ?: return false
        val tb = b.directionTarget ?: return false
        return Geo.angleDiff(Geo.bearingDegrees(a.position, ta), Geo.bearingDegrees(b.position, tb)) <= SAME_WAY_DEGREES
    }

    /** 방향 지명이 가리키는 쪽이 이 각도 안이면 같은 쪽 */
    private const val SAME_WAY_DEGREES = 60.0

    /** 이름에서 괄호·방향 표시·띄어쓰기·끝의 '휴게소/쉼터'를 뺀 것 ("용인 휴게소 (강릉 방향)" → "용인") */
    fun baseKey(area: RestArea): String {
        val words = RestMateText.spokenName(area.name).split(" ").filter { it.isNotBlank() }
        val core = if (words.size > 1 && words.last().endsWith("방향")) words.dropLast(1) else words
        var key = core.joinToString("")
        for (tail in TYPE_TAILS) {
            if (key.length > tail.length && key.endsWith(tail)) {
                key = key.removeSuffix(tail)
                break
            }
        }
        return key
    }

    /** 남길 순서: 괄호로 방향을 적은 이름 → 방향을 아는 것 → 나머지 */
    private fun rank(area: RestArea): Int = when {
        area.name.contains("(") -> 0
        area.directionLabel != null -> 1
        else -> 2
    }

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
