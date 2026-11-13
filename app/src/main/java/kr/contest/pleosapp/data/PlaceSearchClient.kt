package kr.contest.pleosapp.data

import android.os.Handler
import android.os.Looper
import kr.contest.pleosapp.core.LatLng
import kr.contest.pleosapp.core.PlaceHit
import kr.contest.pleosapp.core.PlaceSearch
import kr.contest.pleosapp.core.PlaceSource
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** 검색 서비스에 묻지 못한 까닭 */
enum class SearchFailure {
    /** 인터넷에 닿지 않음 */
    OFFLINE,

    /** 서버가 오류로 답했거나 알 수 없는 답 (한도 초과, 키 문제 등) */
    SERVICE,
}

/** 검색 서비스 답: 찾은 곳, 묻지 못했으면 그 까닭 */
data class OnlinePlaces(val hits: List<PlaceHit>, val failure: SearchFailure? = null)

/**
 * 장소 검색 서비스에 묻는다. 백그라운드에서 한 줄로 묻고 답은 메인 스레드로 돌려준다.
 *
 * - [kakaoKey]가 있으면 카카오 로컬 (키워드 검색·좌표로 주소 찾기). 키는 local.properties에만 두고 저장소에 올리지 않는다.
 *   카카오가 키를 거절하면(401·403: 키가 틀렸거나 카카오맵 사용 설정이 꺼짐) 그 뒤로는 OpenStreetMap으로 묻는다.
 * - 없으면 OpenStreetMap Nominatim. 이용 정책을 따른다: 앱을 밝힌 User-Agent, 1초에 한 번까지,
 *   입력할 때마다 묻지 않기('검색'을 누를 때만), 같은 검색은 다시 묻지 않기(최근 32개를 기억).
 */
class PlaceSearchClient(kakaoKey: String?) {
    private val kakaoKey: String? = kakaoKey?.takeIf { it.isNotBlank() }

    /** 카카오가 키를 거절했는지 (묻는 스레드에서 쓰고 화면에서 읽는다) */
    @Volatile
    private var kakaoRejected = false

    private val useKakao: Boolean get() = kakaoKey != null && !kakaoRejected

    /** 검색 화면 아래 출처 표시 */
    val serviceName: String
        get() = when {
            useKakao -> "카카오 로컬"
            kakaoKey != null -> "$OSM_NAME · 카카오 키가 거절되어 대신 씀"
            else -> OSM_NAME
        }

    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "place-search").apply { isDaemon = true } }

    private val cache = object : LinkedHashMap<String, List<PlaceHit>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<PlaceHit>>?): Boolean = size > CACHE_SIZE
    }

    /** 마지막으로 서버에 물은 때 (System.nanoTime, 묻는 스레드에서만). 기기 시계를 바꿔도 흔들리지 않게 */
    private var lastAskAtNs = 0L

    /** 서버의 답 */
    private sealed interface Reply {
        data class Ok(val body: String) : Reply

        data class Http(val code: Int) : Reply

        data object Offline : Reply
    }

    /**
     * 이름·주소로 찾는다. Nominatim은 같은 검색(차가 약 5km 안에 있을 때)을 기억해 둔 답으로 바로 준다(정책이 권하는 대로).
     * 카카오 로컬은 받은 결과를 따로 저장해 쓰지 못하게 되어 있어 기억하지 않고 매번 묻는다.
     */
    fun search(query: String, near: LatLng?, onResult: (OnlinePlaces) -> Unit) {
        val q = query.trim()
        val key = q + (near?.let { "@" + (it.lat * 20).roundToInt() + "," + (it.lon * 20).roundToInt() }.orEmpty())
        val cached = if (useKakao) null else synchronized(cache) { cache[key] }
        if (cached != null) {
            onResult(OnlinePlaces(cached))
            return
        }
        executor.execute {
            val result = fetchSearch(q, near, key)
            main.post { onResult(result) }
        }
    }

    /** 지도에서 고른 곳의 이름·주소 (찾지 못하면 null). 자리는 고른 곳 그대로 둔다 */
    fun reverse(at: LatLng, onResult: (PlaceHit?) -> Unit) {
        executor.execute {
            val hit = fetchReverse(at)
            main.post { onResult(hit?.copy(position = at, source = PlaceSource.MAP)) }
        }
    }

    private fun fetchSearch(query: String, near: LatLng?, cacheKey: String): OnlinePlaces {
        val key = kakaoKey
        if (key != null && !kakaoRejected) {
            when (val r = get(PlaceSearch.kakaoKeywordUrl(query, near), key)) {
                is Reply.Ok -> return PlaceSearch.parseKakaoKeyword(r.body)?.let { OnlinePlaces(it) }
                    ?: OnlinePlaces(emptyList(), SearchFailure.SERVICE)
                is Reply.Http -> {
                    if (r.code !in KEY_REJECTED) return OnlinePlaces(emptyList(), SearchFailure.SERVICE)
                    kakaoRejected = true
                }
                Reply.Offline -> return OnlinePlaces(emptyList(), SearchFailure.OFFLINE)
            }
        }
        return when (val r = get(PlaceSearch.nominatimSearchUrl(query, near), null)) {
            is Reply.Ok -> {
                val hits = PlaceSearch.parseNominatim(r.body) ?: return OnlinePlaces(emptyList(), SearchFailure.SERVICE)
                synchronized(cache) { cache[cacheKey] = hits }
                OnlinePlaces(hits)
            }
            is Reply.Http -> OnlinePlaces(emptyList(), SearchFailure.SERVICE)
            Reply.Offline -> OnlinePlaces(emptyList(), SearchFailure.OFFLINE)
        }
    }

    private fun fetchReverse(at: LatLng): PlaceHit? {
        val key = kakaoKey
        if (key != null && !kakaoRejected) {
            when (val r = get(PlaceSearch.kakaoReverseUrl(at), key)) {
                is Reply.Ok -> return PlaceSearch.parseKakaoReverse(r.body, at)
                is Reply.Http -> {
                    if (r.code !in KEY_REJECTED) return null
                    kakaoRejected = true
                }
                Reply.Offline -> return null
            }
        }
        val r = get(PlaceSearch.nominatimReverseUrl(at), null)
        return (r as? Reply.Ok)?.let { PlaceSearch.parseNominatimReverse(it.body) }
    }

    /** 서버에 묻는다. 1초에 한 번 넘게 묻지 않는다 */
    private fun get(url: String, kakaoKey: String?): Reply {
        val waitMs = ((lastAskAtNs + MIN_GAP_MS * NS_PER_MS - System.nanoTime()) / NS_PER_MS).coerceIn(0L, MIN_GAP_MS)
        if (lastAskAtNs != 0L && waitMs > 0) Thread.sleep(waitMs)
        lastAskAtNs = System.nanoTime()
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept-Language", "ko")
                if (kakaoKey != null) conn.setRequestProperty("Authorization", "KakaoAK $kakaoKey")
                conn.connectTimeout = 6_000
                conn.readTimeout = 8_000
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    Reply.Http(code)
                } else {
                    Reply.Ok(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            Reply.Offline
        } catch (e: SecurityException) {
            Reply.Offline
        }
    }

    companion object {
        private const val MIN_GAP_MS = 1_100L
        private const val NS_PER_MS = 1_000_000L
        private const val CACHE_SIZE = 32
        private val KEY_REJECTED = setOf(401, 403)
        private const val OSM_NAME = "OpenStreetMap Nominatim (© OpenStreetMap contributors)"
        private const val USER_AGENT =
            "DriveMate/0.1 (Hyundai Motor Group in-car app contest; +https://github.com/sjb8032/hmg-app-challenge-2026)"
    }
}
