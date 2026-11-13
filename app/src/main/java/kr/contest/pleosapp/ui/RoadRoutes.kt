package kr.contest.pleosapp.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import kr.contest.pleosapp.app.UiState
import kr.contest.pleosapp.core.Geo
import kr.contest.pleosapp.core.LatLng
import kr.contest.pleosapp.core.RoadPath
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/** 도로 길을 받을 구간 (양 끝을 약 10m 단위로 반올림해 같은 구간을 다시 받지 않는다) */
internal data class LegKey(val fromLat: Int, val fromLon: Int, val toLat: Int, val toLon: Int) {
    companion object {
        fun of(from: LatLng, to: LatLng) = LegKey(
            (from.lat * 1e4).roundToInt(),
            (from.lon * 1e4).roundToInt(),
            (to.lat * 1e4).roundToInt(),
            (to.lon * 1e4).roundToInt(),
        )
    }
}

/**
 * 미니맵에 그릴 도로 길 (두 곳 사이를 실제 도로로 잇는 모양). OSRM 길찾기 서버에서 받는다 (키 없음).
 *
 * 내비 SDK로는 경로 모양을 받을 수 없어, 화면에 그리는 데만 쓴다 (도착 시간 계산은 내비 남은 시간 그대로).
 * OSRM 이용 정책을 따른다: 앱 이름을 밝힌 User-Agent, 1초에 한 번 넘게 묻지 않기, 받은 길은 기기에 저장해 다시 묻지 않기.
 * 받기 전이나 인터넷이 안 되면 그리는 쪽이 곧은 선으로 대신한다.
 */
internal object RoadRoutes {
    @Volatile
    var mode: MapTiles.Mode = MapTiles.Mode.ASYNC

    const val ATTRIBUTION = "경로 © OSRM"
    private const val ROUTE_URL =
        "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&steps=false&alternatives=false"
    private const val USER_AGENT =
        "DriveMate/0.1 (Hyundai Motor Group in-car app contest; +https://github.com/sjb8032/hmg-app-challenge-2026)"

    /** 이보다 짧은 구간은 곧게 그린다 (묻지 않는다) */
    private const val MIN_LEG_METERS = 200.0
    private const val MIN_GAP_MS = 1_100L
    private const val RETRY_MS = 60_000L
    private const val MEMORY_LEGS = 48

    private var dir: File? = null

    // 아래는 메인 스레드에서만 고친다 (BLOCKING은 그리는 스레드에서)
    private val loaded = mutableStateMapOf<LegKey, List<LatLng>>()
    private val order = ArrayDeque<LegKey>()
    private val pending = HashSet<LegKey>()
    private val failedAt = HashMap<LegKey, Long>()

    /** 마지막으로 서버에 물은 시각 (묻는 스레드에서만) */
    private var lastAskAt = 0L

    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** 한 줄로 차례차례 묻는다 (1초에 한 번) */
    private val executor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "road-routes").apply { isDaemon = true } }
    }

    fun init(context: Context) {
        dir = File(context.cacheDir, "road-routes").apply { mkdirs() }
    }

    /** 받아 둔 도로 길 (처음과 끝은 [from]·[to] 그대로). 없으면 null. Compose 안에서 읽으면 길이 도착할 때 다시 그린다 */
    fun get(from: LatLng, to: LatLng): List<LatLng>? = loaded[LegKey.of(from, to)]

    /** 도로 길을 받아 둔다. 이미 있거나 받는 중이거나 방금 실패했거나 짧은 구간은 건너뛴다 */
    fun request(legs: List<Pair<LatLng, LatLng>>) {
        if (mode == MapTiles.Mode.OFF) return
        for ((from, to) in legs) {
            val key = LegKey.of(from, to)
            if (key in loaded || key in pending || recentlyFailed(key)) continue
            if (Geo.distanceMeters(from, to) < MIN_LEG_METERS) continue
            if (mode == MapTiles.Mode.BLOCKING) {
                runCatching { load(from, to) }.getOrNull()?.let { put(key, it) }
                continue
            }
            pending += key
            executor.execute {
                val path = runCatching { load(from, to) }.getOrNull()
                main.post {
                    pending -= key
                    if (path != null) {
                        failedAt -= key
                        put(key, path)
                    } else {
                        failedAt[key] = SystemClock.elapsedRealtime()
                    }
                }
            }
        }
    }

    /**
     * 미니맵이 그리기 전에 부른다: 앱에서는 백그라운드로 받고, 화면 캡처 시험에서는 그 자리에서 받는다.
     * 앞 구간의 도로 길이 있어야 정해지는 구간이 있어 (도중에 경로가 바뀐 곳에서 이어 그리기) 캡처 때는 몇 번 되풀이한다.
     */
    @Composable
    fun Load(ui: UiState) {
        if (mode == MapTiles.Mode.BLOCKING) {
            remember(ui) { repeat(3) { request(roadLegsFor(ui)) } }
        } else {
            val legs = roadLegsFor(ui)
            val keys = legs.map { LegKey.of(it.first, it.second) }
            LaunchedEffect(keys) { request(legs) }
        }
    }

    private fun recentlyFailed(key: LegKey): Boolean {
        val at = failedAt[key] ?: return false
        return SystemClock.elapsedRealtime() - at < RETRY_MS
    }

    private fun put(key: LegKey, path: List<LatLng>) {
        loaded[key] = path
        order.remove(key)
        order.addLast(key)
        while (order.size > MEMORY_LEGS) loaded.remove(order.removeFirst())
    }

    /** 기기에 저장한 길이 있으면 그것을, 없으면 서버에 묻고 저장한다 */
    private fun load(from: LatLng, to: LatLng): List<LatLng>? {
        val key = LegKey.of(from, to)
        val file = dir?.let { File(it, "${key.fromLat}_${key.fromLon}_${key.toLat}_${key.toLon}.txt") }
        if (file != null && file.exists()) {
            decodeFile(file.readText())?.let { return finish(from, to, it) }
        }
        val points = fetch(from, to) ?: return null
        if (file != null) runCatching { file.writeText(points.joinToString(";") { "${it.lat},${it.lon}" }) }
        return finish(from, to, points)
    }

    /** 처음과 끝을 실제 지점으로 잇고, 너무 촘촘한 점은 솎는다 (긴 길도 1,500점 안쪽) */
    private fun finish(from: LatLng, to: LatLng, road: List<LatLng>): List<LatLng> {
        val path = RoadPath.join(listOf(listOf(from), road, listOf(to)))
        val minGap = max(12.0, RoadPath.length(path) / 1_500.0)
        return RoadPath.thin(path, minGap)
    }

    private fun fetch(from: LatLng, to: LatLng): List<LatLng>? {
        // 1초에 한 번 넘게 묻지 않는다
        val wait = lastAskAt + MIN_GAP_MS - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastAskAt = System.currentTimeMillis()
        val url = URL(String.format(Locale.ROOT, ROUTE_URL, from.lon, from.lat, to.lon, to.lat))
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = 6_000
        conn.readTimeout = 10_000
        val body = try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
        return parseGeometry(body)
    }

    private fun decodeFile(text: String): List<LatLng>? = text.split(';').mapNotNull { part ->
        val (lat, lon) = part.split(',').takeIf { it.size == 2 } ?: return@mapNotNull null
        LatLng(lat.toDoubleOrNull() ?: return@mapNotNull null, lon.toDoubleOrNull() ?: return@mapNotNull null)
    }.takeIf { it.size >= 2 }

    private val pair = Regex("""\[\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*]""")

    /** OSRM 응답에서 경로 모양(GeoJSON 좌표 [경도, 위도] 목록)만 꺼낸다 */
    internal fun parseGeometry(json: String): List<LatLng>? {
        if (!json.contains("\"code\":\"Ok\"")) return null
        val at = json.indexOf("\"coordinates\"")
        if (at < 0) return null
        val start = json.indexOf('[', at)
        if (start < 0) return null
        var depth = 0
        var end = -1
        for (i in start until json.length) {
            when (json[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
        if (end < 0) return null
        val points = pair.findAll(json.substring(start + 1, end)).map {
            LatLng(it.groupValues[2].toDouble(), it.groupValues[1].toDouble())
        }.toList()
        return points.takeIf { it.size >= 2 }
    }
}
