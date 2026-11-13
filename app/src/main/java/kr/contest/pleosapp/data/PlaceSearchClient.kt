package kr.contest.pleosapp.data

import android.os.Handler
import android.os.Looper
import kr.contest.pleosapp.core.LatLng
import kr.contest.pleosapp.core.PlaceHit
import kr.contest.pleosapp.core.PlaceSearch
import kr.contest.pleosapp.core.PlaceSource
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** 검색 서비스 답: 찾은 곳, 묻지 못했는지(인터넷 안 됨·서버 오류) */
data class OnlinePlaces(val hits: List<PlaceHit>, val failed: Boolean)

/**
 * 장소 검색 서비스에 묻는다. 백그라운드에서 한 줄로 묻고 답은 메인 스레드로 돌려준다.
 *
 * - [kakaoKey]가 있으면 카카오 로컬 (키워드 검색·좌표로 주소 찾기). 키는 local.properties에만 두고 저장소에 올리지 않는다.
 * - 없으면 OpenStreetMap Nominatim. 이용 정책을 따른다: 앱을 밝힌 User-Agent, 1초에 한 번까지,
 *   입력할 때마다 묻지 않기('검색'을 누를 때만), 같은 검색은 다시 묻지 않기(최근 32개를 기억).
 */
class PlaceSearchClient(kakaoKey: String?) {
    private val kakaoKey: String? = kakaoKey?.takeIf { it.isNotBlank() }

    /** 검색 화면 아래 출처 표시 */
    val serviceName: String
        get() = if (kakaoKey != null) "카카오 로컬" else "OpenStreetMap Nominatim (© OpenStreetMap contributors)"

    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "place-search").apply { isDaemon = true } }

    private val cache = object : LinkedHashMap<String, List<PlaceHit>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<PlaceHit>>?): Boolean = size > CACHE_SIZE
    }

    /** 마지막으로 서버에 물은 시각 (묻는 스레드에서만) */
    private var lastAskAt = 0L

    /** 이름·주소로 찾는다. 같은 검색(차가 약 5km 안에 있을 때)은 기억해 둔 답을 바로 준다 */
    fun search(query: String, near: LatLng?, onResult: (OnlinePlaces) -> Unit) {
        val key = query.trim() + (near?.let { "@" + (it.lat * 20).roundToInt() + "," + (it.lon * 20).roundToInt() }.orEmpty())
        val cached = synchronized(cache) { cache[key] }
        if (cached != null) {
            onResult(OnlinePlaces(cached, failed = false))
            return
        }
        executor.execute {
            val hits = runCatching { fetchSearch(query.trim(), near) }.getOrNull()
            if (hits != null) synchronized(cache) { cache[key] = hits }
            main.post { onResult(OnlinePlaces(hits.orEmpty(), failed = hits == null)) }
        }
    }

    /** 지도에서 고른 곳의 이름·주소 (찾지 못하면 null). 자리는 고른 곳 그대로 둔다 */
    fun reverse(at: LatLng, onResult: (PlaceHit?) -> Unit) {
        executor.execute {
            val hit = runCatching { fetchReverse(at) }.getOrNull()
            main.post { onResult(hit?.copy(position = at, source = PlaceSource.MAP)) }
        }
    }

    private fun fetchSearch(query: String, near: LatLng?): List<PlaceHit>? {
        val key = kakaoKey
        return if (key != null) {
            get(PlaceSearch.kakaoKeywordUrl(query, near), key)?.let(PlaceSearch::parseKakaoKeyword)
        } else {
            get(PlaceSearch.nominatimSearchUrl(query, near), null)?.let(PlaceSearch::parseNominatim)
        }
    }

    private fun fetchReverse(at: LatLng): PlaceHit? {
        val key = kakaoKey
        return if (key != null) {
            get(PlaceSearch.kakaoReverseUrl(at), key)?.let { PlaceSearch.parseKakaoReverse(it, at) }
        } else {
            get(PlaceSearch.nominatimReverseUrl(at), null)?.let(PlaceSearch::parseNominatimReverse)
        }
    }

    /** 본문 (실패하면 null). 1초에 한 번 넘게 묻지 않는다 */
    private fun get(url: String, kakaoKey: String?): String? {
        val wait = lastAskAt + MIN_GAP_MS - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastAskAt = System.currentTimeMillis()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept-Language", "ko")
        if (kakaoKey != null) conn.setRequestProperty("Authorization", "KakaoAK $kakaoKey")
        conn.connectTimeout = 6_000
        conn.readTimeout = 8_000
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) null else conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val MIN_GAP_MS = 1_100L
        private const val CACHE_SIZE = 32
        private const val USER_AGENT =
            "DriveMate/0.1 (Hyundai Motor Group in-car app contest; +https://github.com/sjb8032/hmg-app-challenge-2026)"
    }
}
