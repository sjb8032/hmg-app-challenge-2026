package kr.contest.pleosapp.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** OSM 타일 번호 */
internal data class TileKey(val z: Int, val x: Int, val y: Int)

/**
 * 미니맵 바탕 지도 타일 (OpenStreetMap 표준 타일). 키가 필요 없고 지명이 한글로 나온다.
 *
 * OSM 타일 이용 정책을 따른다: 앱 이름을 밝힌 User-Agent, 지도 위 출처 표기, 받은 타일은 기기에 7일 저장해 다시 받지 않기,
 * 미니맵 한 장에 쓰는 만큼만 받기 (미리 내려받기 없음). 인터넷이 안 되면 받지 못한 자리는 기존 도식 지도 바탕이 그대로 보인다.
 */
internal object MapTiles {
    enum class Mode {
        /** 받지 않는다 (도식 지도만) */
        OFF,

        /** 백그라운드에서 받아 오면 다시 그린다 (앱) */
        ASYNC,

        /** 그리기 전에 그 자리에서 받는다 (화면 캡처 시험) */
        BLOCKING,
    }

    @Volatile
    var mode: Mode = Mode.ASYNC

    const val ATTRIBUTION = "© OpenStreetMap contributors"
    private const val TILE_URL = "https://tile.openstreetmap.org/%d/%d/%d.png"
    private const val USER_AGENT =
        "DriveMate/0.1 (Hyundai Motor Group in-car app contest; +https://github.com/sjb8032/hmg-app-challenge-2026)"

    /** 메모리에 두는 타일 수 (RGB 565라 한 장 128KB) */
    private const val MEMORY_TILES = 72
    private const val DISK_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private const val RETRY_MS = 60_000L

    private var dir: File? = null

    // 아래 셋은 메인 스레드에서만 고친다 (BLOCKING은 그리는 스레드에서)
    private val loaded = mutableStateMapOf<TileKey, ImageBitmap>()
    private val order = ArrayDeque<TileKey>()
    private val pending = HashSet<TileKey>()
    private val failedAt = HashMap<TileKey, Long>()

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val executor: ExecutorService by lazy {
        Executors.newFixedThreadPool(2) { r -> Thread(r, "map-tiles").apply { isDaemon = true } }
    }

    fun init(context: Context) {
        dir = File(context.cacheDir, "map-tiles").apply { mkdirs() }
    }

    /** 받아 둔 타일 (없으면 null). Compose 안에서 읽으면 타일이 도착할 때 다시 그린다 */
    fun get(key: TileKey): ImageBitmap? = loaded[key]

    /** 미니맵에 필요한 타일을 받는다. 이미 있거나 받는 중이거나 방금 실패한 타일은 건너뛴다 */
    fun request(keys: List<TileKey>) {
        when (mode) {
            Mode.OFF -> Unit
            Mode.BLOCKING -> for (key in keys) {
                if (key !in loaded) runCatching { load(key) }.getOrNull()?.let { put(key, it) }
            }
            Mode.ASYNC -> for (key in keys) {
                if (key in loaded || key in pending || recentlyFailed(key)) continue
                pending += key
                executor.execute {
                    val bitmap = runCatching { load(key) }.getOrNull()
                    main.post {
                        pending -= key
                        if (bitmap != null) {
                            failedAt -= key
                            put(key, bitmap)
                        } else {
                            failedAt[key] = SystemClock.elapsedRealtime()
                        }
                    }
                }
            }
        }
    }

    private fun recentlyFailed(key: TileKey): Boolean {
        val at = failedAt[key] ?: return false
        return SystemClock.elapsedRealtime() - at < RETRY_MS
    }

    private fun put(key: TileKey, bitmap: ImageBitmap) {
        loaded[key] = bitmap
        order.remove(key)
        order.addLast(key)
        while (order.size > MEMORY_TILES) loaded.remove(order.removeFirst())
    }

    /** 기기에 저장한 타일이 7일 안이면 그것을, 아니면 받아서 저장한다 */
    private fun load(key: TileKey): ImageBitmap? {
        val file = dir?.let { File(it, "${key.z}_${key.x}_${key.y}.png") }
        if (file != null && file.exists() && System.currentTimeMillis() - file.lastModified() < DISK_MAX_AGE_MS) {
            decode(file.readBytes())?.let { return it }
        }
        val bytes = download(key) ?: return null
        val image = decode(bytes) ?: return null
        if (file != null) runCatching { file.writeBytes(bytes) }
        return image
    }

    private fun download(key: TileKey): ByteArray? {
        val url = URL(String.format(Locale.ROOT, TILE_URL, key.z, key.x, key.y))
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = 6_000
        conn.readTimeout = 8_000
        return try {
            if (conn.responseCode == HttpURLConnection.HTTP_OK) conn.inputStream.use { it.readBytes() } else null
        } finally {
            conn.disconnect()
        }
    }

    private fun decode(bytes: ByteArray): ImageBitmap? {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }

    /**
     * 타일 색 바꾸기.
     * - 낮: 채도를 낮추고 조금 밝게 (위에 그리는 경로·번호가 먼저 보이게)
     * - 밤: 색을 뒤집고 색상을 반 바퀴 돌려 어두운 지도로 (물은 파랗게 남고 글자는 밝게), 남색 쪽으로 살짝
     */
    val dayFilter: ColorFilter by lazy {
        val s = 0.55f
        val lr = 0.213f
        val lg = 0.715f
        val lb = 0.072f
        val k = 0.9f
        val lift = 24f
        ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    k * (lr + (1 - lr) * s), k * (lg - lg * s), k * (lb - lb * s), 0f, lift,
                    k * (lr - lr * s), k * (lg + (1 - lg) * s), k * (lb - lb * s), 0f, lift,
                    k * (lr - lr * s), k * (lg - lg * s), k * (lb + (1 - lb) * s), 0f, lift,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }

    val nightFilter: ColorFilter by lazy {
        // 색상 180° 회전 행렬 (밝기 보존)
        val h = arrayOf(
            floatArrayOf(-0.574f, 1.430f, 0.144f),
            floatArrayOf(0.426f, 0.430f, 0.144f),
            floatArrayOf(0.426f, 1.430f, -0.856f),
        )
        val k = 0.82f
        // 남색 쪽으로 (R, G, B 더하기)
        val tint = floatArrayOf(-4f, 10f, 30f)
        // out = k·H·(255 - c) + tint = -k·H·c + k·255 + tint (H의 각 행 합이 1)
        val v = FloatArray(20)
        for (row in 0 until 3) {
            for (col in 0 until 3) v[row * 5 + col] = -k * h[row][col]
            v[row * 5 + 4] = k * 255f + tint[row]
        }
        v[18] = 1f
        ColorFilter.colorMatrix(ColorMatrix(v))
    }
}
