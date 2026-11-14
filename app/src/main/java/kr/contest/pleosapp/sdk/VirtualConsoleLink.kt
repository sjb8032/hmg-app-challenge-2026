package kr.contest.pleosapp.sdk

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kr.contest.pleosapp.core.ConsoleSync
import kr.contest.pleosapp.core.Gear
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Virtual Console 연동 상태 (시연 컨트롤에 보여 준다) */
enum class ConsoleLinkState { OFF, CONNECTING, ON, UNREACHABLE }

data class ConsoleStatus(
    val state: ConsoleLinkState = ConsoleLinkState.OFF,
    /** 콘솔에 마지막으로 맞춘 기어·속도 */
    val gear: Gear? = null,
    val speedKmh: Int? = null,
    /** 콘솔의 3D 주행 화면(브라우저)이 열려 있는지 */
    val browserActive: Boolean = false,
)

/**
 * 시연 모드에서 Pleos Connect 에뮬레이터의 Virtual Console(에뮬레이터 안 127.0.0.1:8088)에 모의 차량의 기어·속도를 보낸다.
 * 계기판의 기어·속도와 콘솔의 3D 주행 화면이 앱의 모의 주행과 같이 움직인다. 무엇을 보낼지는 [ConsoleSync]가 정한다.
 *
 * 실제 차·내비 신호를 쓸 때(시연 모드가 아닐 때)는 보내지 않는다. 콘솔이 없으면(실차·일반 기기) 조용히 '연결 안 됨'으로 둔다.
 * 네트워크는 작업 스레드 하나에서만 쓰고, 상태는 메인 스레드로 알린다.
 */
class VirtualConsoleLink(
    private val onStatus: (ConsoleStatus) -> Unit,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "virtual-console").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    // ---- 메인 스레드 ----
    private var enabled = false
    private var wanted: ConsoleSync.Target? = null
    private var lastAttemptAt = 0L

    // ---- 작업 스레드 ----
    private var sent: ConsoleSync.Target? = null
    private var browserActive = false
    private var browserCheckedAt = 0L

    @Volatile private var lastOk = true

    /** 시연을 시작할 때 부른다. 다음 [update]부터 콘솔을 맞춘다. */
    fun start() {
        enabled = true
        wanted = null
        lastOk = true
        worker.execute { sent = null; browserCheckedAt = 0L }
        onStatus(ConsoleStatus(ConsoleLinkState.CONNECTING))
    }

    /** 모의 차량 상태를 콘솔에 맞춘다. 바뀐 것이 있을 때(또는 연결이 안 돼 다시 시도할 때)만 보낸다. */
    fun update(gear: Gear, speedKmh: Double) {
        if (!enabled) return
        if (gear != Gear.PARK && gear != Gear.DRIVE) return
        val target = ConsoleSync.Target(gear, speedKmh.roundToInt().coerceAtLeast(0))
        val now = SystemClock.elapsedRealtime()
        val retry = !lastOk && now - lastAttemptAt >= RETRY_MS
        if (target == wanted && !retry) return
        if (!lastOk && !retry) {
            // 연결이 안 되는 동안에는 바라는 상태만 기억해 두고, 다시 시도할 때 보낸다.
            wanted = target
            return
        }
        wanted = target
        lastAttemptAt = now
        worker.execute { push(target, report = true) }
    }

    /** 시연을 끝낼 때: 콘솔을 P단·정지로 돌려 두고 멈춘다. */
    fun stop() {
        if (!enabled) return
        enabled = false
        wanted = null
        worker.execute {
            if (sent != null) push(ConsoleSync.Target(Gear.PARK, 0), report = false)
            sent = null
        }
        onStatus(ConsoleStatus(ConsoleLinkState.OFF))
    }

    /** 앱을 닫을 때 */
    fun release() {
        stop()
        worker.shutdown()
    }

    // ---- 작업 스레드 ----

    private fun push(target: ConsoleSync.Target, report: Boolean) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - browserCheckedAt >= BROWSER_CHECK_MS) {
                browserActive = readBrowserActive()
                browserCheckedAt = now
            }
            val writes = ConsoleSync.plan(sent, target, browserActive)
            for (write in writes) send(write)
            // 콘솔 화면이 속도를 계산하는 중이라 보내지 않은 변화는 보낸 것으로 치지 않는다.
            if (writes.isNotEmpty() || sent == null) sent = target
            lastOk = true
            val shown = sent ?: target
            if (report) post(ConsoleStatus(ConsoleLinkState.ON, shown.gear, shown.speedKmh, browserActive))
        } catch (e: Exception) {
            lastOk = false
            sent = null
            Log.i(TAG, "Virtual Console에 연결하지 못했어요: ${e.message}")
            if (report) post(ConsoleStatus(ConsoleLinkState.UNREACHABLE))
        }
    }

    private fun post(status: ConsoleStatus) {
        main.post { if (enabled) onStatus(status) }
    }

    private fun send(write: ConsoleSync.Write) {
        val path = when (write) {
            is ConsoleSync.Write.Controls ->
                "/vehicle/controls?speed=${write.speedKmh}" +
                    "&accelerator=${String.format(Locale.ROOT, "%.3f", write.acceleratorPct)}" +
                    "&brake=${String.format(Locale.ROOT, "%.3f", write.brakePct)}"
            is ConsoleSync.Write.SetGear -> "/vehicle/gear?gear=${gearName(write.gear)}"
        }
        val conn = open(path)
        try {
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(0)
            conn.outputStream.close()
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code ($path)")
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /** 콘솔 화면(브라우저)이 열려 있는지: /vehicle/state의 browser.active */
    private fun readBrowserActive(): Boolean {
        val conn = open("/vehicle/state")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code (/vehicle/state)")
            val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return JSONObject(text).optJSONObject("browser")?.optBoolean("active", false) ?: false
        } finally {
            conn.disconnect()
        }
    }

    private fun open(path: String): HttpURLConnection =
        (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
        }

    private fun gearName(gear: Gear): String = when (gear) {
        Gear.PARK -> "Park"
        Gear.REVERSE -> "Reverse"
        Gear.NEUTRAL -> "Neutral"
        Gear.DRIVE, Gear.UNKNOWN -> "Drive"
    }

    companion object {
        /** 에뮬레이터 안에서 본 Virtual Console 주소 (PC에서는 adb forward tcp:8088 tcp:8088 뒤 같은 주소) */
        const val DEFAULT_BASE_URL = "http://127.0.0.1:8088"
        private const val TAG = "DriveMate"
        private const val CONNECT_TIMEOUT_MS = 700
        private const val READ_TIMEOUT_MS = 2_000
        /** 연결이 안 되면 이만큼 쉬었다 다시 시도한다 */
        private const val RETRY_MS = 8_000L
        /** 콘솔 화면이 열려 있는지 다시 볼 간격 */
        private const val BROWSER_CHECK_MS = 4_000L
    }
}
