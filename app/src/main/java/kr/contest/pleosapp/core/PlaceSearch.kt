package kr.contest.pleosapp.core

import kr.contest.pleosapp.core.MiniJson.list
import kr.contest.pleosapp.core.MiniJson.num
import kr.contest.pleosapp.core.MiniJson.obj
import kr.contest.pleosapp.core.MiniJson.str
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

/** 장소를 어디서 찾았는지 (검색 화면 아래 출처 표시) */
enum class PlaceSource(val label: String) {
    APP("앱에 있는 곳"),
    OSM("OpenStreetMap"),
    KAKAO("카카오"),
    MAP("지도에서 고른 곳"),
}

/** 장소 검색 결과 한 곳 */
data class PlaceHit(
    val name: String,
    /** 짧은 주소 (모르면 빈 문자열) */
    val address: String,
    val position: LatLng,
    val source: PlaceSource,
    /** 종류 (역·공원·음식점 등). 모르면 null */
    val category: String? = null,
) {
    /** 내비·시간 모드에 넘길 장소 */
    fun toPlace(kind: PlaceKind = PlaceKind.SEARCH): Place = Place(name, position, kind, address = address)

    companion object {
        fun of(place: Place, source: PlaceSource = PlaceSource.APP): PlaceHit =
            PlaceHit(place.name, place.address, place.position, source, place.kind.label.takeIf { it != place.name })
    }
}

/**
 * 장소 검색: 앱에 있는 곳(즐겨찾기·경치 지점·쉼터 등)에서 찾기, 검색 서비스 주소 만들기와 응답 읽기, 결과 합치기.
 * 안드로이드·네트워크에 의존하지 않는다 (실제로 묻는 것은 [kr.contest.pleosapp.data.PlaceSearchClient]).
 *
 * 검색 서비스는 둘 중 하나다.
 * - 카카오 로컬 (키가 있을 때): 국내 상호·건물 검색이 내비처럼 잘 된다.
 * - OpenStreetMap Nominatim (키 없음): 역·공원·랜드마크·주소. 이용 정책에 따라 입력할 때마다 묻지 않고 '검색'을 누를 때만,
 *   1초에 한 번까지 묻는다.
 */
object PlaceSearch {
    const val MAX_RESULTS = 12

    /** 이름이 같고 이만큼 가까우면 같은 곳 (역 출입구·건물 윤곽처럼 한 곳이 여러 번 나올 때) */
    private const val SAME_NAME_METERS = 300.0

    /** 이름이 달라도 이만큼 가까우면 같은 곳 */
    private const val SAME_SPOT_METERS = 25.0

    /** 검색 서비스가 지금 차 둘레를 먼저 찾게 하는 범위 (위도·경도 ±) */
    private const val NEAR_SPAN_DEG = 0.45

    private const val NOMINATIM = "https://nominatim.openstreetmap.org"
    private const val KAKAO = "https://dapi.kakao.com/v2/local"

    /** 비교용 글자: 소문자, 띄어쓰기·기호 빼기 */
    fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /**
     * 앱에 있는 곳 가운데 이름(또는 주소)에 검색어가 들어간 곳.
     * 이름이 검색어와 같거나 검색어로 시작하면 먼저, 그다음 가까운 순.
     */
    fun local(query: String, candidates: List<PlaceHit>, near: LatLng?, limit: Int = 5): List<PlaceHit> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        fun rank(hit: PlaceHit): Int {
            val name = normalize(hit.name)
            return when {
                name == q -> 0
                name.startsWith(q) -> 1
                name.contains(q) -> 2
                normalize(hit.address).contains(q) -> 3
                else -> -1
            }
        }
        return candidates
            .map { it to rank(it) }
            .filter { it.second >= 0 }
            .sortedWith(compareBy<Pair<PlaceHit, Int>> { it.second }.thenBy { near?.let { n -> Geo.distanceMeters(n, it.first.position) } ?: 0.0 })
            .map { it.first }
            .let { dedupe(it) }
            .take(limit)
    }

    /** 앱에 있는 곳과 검색 서비스 결과를 합친다. 같은 곳은 하나로 두고, 앱에 있는 곳이 먼저 */
    fun merge(local: List<PlaceHit>, online: List<PlaceHit>, limit: Int = MAX_RESULTS): List<PlaceHit> =
        dedupe(local + online).take(limit)

    private fun dedupe(hits: List<PlaceHit>): List<PlaceHit> {
        val out = ArrayList<PlaceHit>()
        for (h in hits) {
            val dup = out.any { o ->
                val d = Geo.distanceMeters(o.position, h.position)
                d <= SAME_SPOT_METERS || (d <= SAME_NAME_METERS && normalize(o.name) == normalize(h.name))
            }
            if (!dup) out += h
        }
        return out
    }

    /** 거리 표시: 950m, 1.2km, 15km */
    fun distanceLabel(meters: Double): String = when {
        meters < 1_000.0 -> "${((meters / 10.0).roundToInt() * 10).coerceAtLeast(10)}m"
        meters < 10_000.0 -> String.format(Locale.ROOT, "%.1fkm", meters / 1_000.0)
        else -> "${(meters / 1_000.0).roundToInt()}km"
    }

    private fun enc(text: String): String = URLEncoder.encode(text, "UTF-8")

    private fun fmt(v: Double): String = String.format(Locale.ROOT, "%.6f", v)

    // ---------------------------------------------------------------- OpenStreetMap Nominatim

    /** 검색: 우리나라 안에서, 한국어 이름으로. [near]가 있으면 그 둘레를 먼저 (밖도 찾는다) */
    fun nominatimSearchUrl(query: String, near: LatLng?, limit: Int = 10): String {
        val box = near?.let {
            "&viewbox=${fmt(it.lon - NEAR_SPAN_DEG)},${fmt(it.lat + NEAR_SPAN_DEG)},${fmt(it.lon + NEAR_SPAN_DEG)},${fmt(it.lat - NEAR_SPAN_DEG)}"
        }.orEmpty()
        return "$NOMINATIM/search?format=jsonv2&addressdetails=1&accept-language=ko&countrycodes=kr&limit=$limit$box&q=${enc(query)}"
    }

    /** 지도에서 고른 곳의 이름·주소 */
    fun nominatimReverseUrl(at: LatLng): String =
        "$NOMINATIM/reverse?format=jsonv2&addressdetails=1&accept-language=ko&zoom=18&lat=${fmt(at.lat)}&lon=${fmt(at.lon)}"

    fun parseNominatim(json: String): List<PlaceHit> {
        val items = MiniJson.parse(json) as? List<*> ?: return emptyList()
        return items.mapNotNull { (it as? Map<*, *>)?.let(::nominatimHit) }
    }

    fun parseNominatimReverse(json: String): PlaceHit? {
        val item = MiniJson.parse(json) as? Map<*, *> ?: return null
        if (item.containsKey("error")) return null
        return nominatimHit(item)
    }

    private fun nominatimHit(item: Map<*, *>): PlaceHit? {
        val lat = item.num("lat") ?: return null
        val lon = item.num("lon") ?: return null
        val address = item.obj("address")
        val display = item.str("display_name").orEmpty()
        val parts = display.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val road = address?.let { a -> listOfNotNull(a.str("road"), a.str("house_number")).joinToString(" ") }.orEmpty()
        val name = item.str("name")?.trim().orEmpty()
            .ifEmpty { road }
            .ifEmpty { parts.firstOrNull().orEmpty() }
        if (name.isEmpty()) return null
        return PlaceHit(
            name = name,
            address = nominatimAddress(address, parts, name),
            position = LatLng(lat, lon),
            source = PlaceSource.OSM,
            category = osmCategory(item.str("category"), item.str("type")),
        )
    }

    /** 큰 곳부터 짧게: '서울특별시 종로구 사직로 161' (주소 칸이 없으면 display_name을 뒤집어서) */
    private fun nominatimAddress(address: Map<*, *>?, displayParts: List<String>, name: String): String {
        if (address != null) {
            val street = listOfNotNull(address.str("road"), address.str("house_number")).joinToString(" ")
            val area = street.ifEmpty {
                listOf("quarter", "suburb", "neighbourhood", "village", "town").firstNotNullOfOrNull { address.str(it) }.orEmpty()
            }
            val parts = listOf(
                address.str("province"),
                address.str("city"),
                address.str("county"),
                address.str("borough") ?: address.str("city_district"),
                area,
            ).filterNotNull().map { it.trim() }.filter { it.isNotEmpty() && it != name }
            val out = ArrayList<String>()
            for (p in parts) if (out.lastOrNull() != p) out += p
            if (out.isNotEmpty()) return out.joinToString(" ")
        }
        return displayParts
            .drop(1)
            .filterNot { p -> p == "대한민국" || p.all { it.isDigit() || it == '-' } }
            .reversed()
            .joinToString(" ")
    }

    private fun osmCategory(category: String?, type: String?): String? = when (category) {
        "railway" -> if (type == "station" || type == "halt" || type == "subway_entrance") "역" else "철도"
        "aeroway" -> "공항"
        "leisure" -> if (type == "park") "공원" else "여가"
        "tourism" -> "관광지"
        "historic" -> "명소"
        "amenity" -> when (type) {
            "parking" -> "주차장"
            "school", "university", "college" -> "학교"
            "hospital" -> "병원"
            "restaurant", "cafe", "fast_food" -> "음식점"
            else -> "편의시설"
        }
        "shop" -> "가게"
        "office" -> "회사"
        "building" -> "건물"
        "highway" -> if (type == "services" || type == "rest_area") "휴게소" else "도로"
        "place", "boundary" -> "지역"
        else -> null
    }

    // ---------------------------------------------------------------- 카카오 로컬 (키가 있을 때)

    /** 키워드 검색. [near]가 있으면 거리도 받는다 */
    fun kakaoKeywordUrl(query: String, near: LatLng?): String {
        val at = near?.let { "&x=${fmt(it.lon)}&y=${fmt(it.lat)}" }.orEmpty()
        return "$KAKAO/search/keyword.json?size=15$at&query=${enc(query)}"
    }

    /** 지도에서 고른 곳의 주소 */
    fun kakaoReverseUrl(at: LatLng): String = "$KAKAO/geo/coord2address.json?x=${fmt(at.lon)}&y=${fmt(at.lat)}"

    fun parseKakaoKeyword(json: String): List<PlaceHit> {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return emptyList()
        val docs = root.list("documents") ?: return emptyList()
        return docs.mapNotNull { d ->
            val doc = d as? Map<*, *> ?: return@mapNotNull null
            val lon = doc.num("x") ?: return@mapNotNull null
            val lat = doc.num("y") ?: return@mapNotNull null
            val name = doc.str("place_name")?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            PlaceHit(
                name = name,
                address = doc.str("road_address_name").orEmpty().ifBlank { doc.str("address_name").orEmpty() },
                position = LatLng(lat, lon),
                source = PlaceSource.KAKAO,
                category = doc.str("category_group_name").orEmpty()
                    .ifBlank { doc.str("category_name").orEmpty().substringAfterLast(" > ") }
                    .ifBlank { null },
            )
        }
    }

    fun parseKakaoReverse(json: String, at: LatLng): PlaceHit? {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return null
        val doc = root.list("documents")?.firstOrNull() as? Map<*, *> ?: return null
        val road = doc.obj("road_address")
        val lot = doc.obj("address")
        val roadName = road?.str("address_name").orEmpty()
        val lotName = lot?.str("address_name").orEmpty()
        val building = road?.str("building_name").orEmpty()
        val name = building.ifBlank { roadName }.ifBlank { lotName }
        if (name.isBlank()) return null
        return PlaceHit(name, roadName.ifBlank { lotName }, at, PlaceSource.MAP)
    }
}
