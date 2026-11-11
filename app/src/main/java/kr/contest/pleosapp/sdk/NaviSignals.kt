package kr.contest.pleosapp.sdk

import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kr.contest.pleosapp.core.Geo
import kr.contest.pleosapp.core.LatLng
import kr.contest.pleosapp.core.RestArea

/**
 * Pleos NaviHelper SDK 연결.
 * - 현재 위치·진행 방향을 1초마다 조회하고, 위치 변화로 속도를 추정한다 (Vehicle SDK에 차속 API가 없음).
 * - 목적지·남은 시간과 다음 회전 안내까지 거리를 주기적으로 조회한다.
 * - 쉼터 안내: 경로가 있으면 경유지로 추가하고, 없으면 쉼터를 목적지로 안내를 요청한다.
 */
class NaviSignals(context: Context, private val sink: Sink) {
    interface Sink {
        fun onNaviConnection(connected: Boolean, message: String?)
        fun onNaviLocation(position: LatLng, headingDeg: Double?, speedKmh: Double?)
        /** 목적지가 없으면 모두 null. remainSeconds는 목적지까지 남은 시간(초) */
        fun onNaviDestination(destination: LatLng?, name: String?, remainSeconds: Int?)
        fun onNaviNextManeuver(meters: Double?)
        fun onNaviSpeedLimit(kmh: Int?)
        fun onNaviWaypointArrived(name: String?)
        fun onNaviMessage(message: String)
    }

    private val helper = NaviHelper(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private var connected = false
    private var routeActive = false
    private var waypointAdded = false

    private var lastFixAt = 0L
    private var lastFix: LatLng? = null
    private var speedKmh: Double? = null
    private var pollCount = 0

    private val listener: NaviHelperEventListener = listenerProxy { method, args ->
        main.post { handle(method, args) }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (connected) {
                safely("location") { helper.getCurrentLocationInfo() }
                if (pollCount % 5 == 0) {
                    safely("destination") { helper.getDestinationInfo() }
                    safely("tbt") { helper.getTBTInfo() }
                }
                pollCount++
            }
            main.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun start() {
        try {
            helper.initialize(
                {
                    main.post {
                        connected = true
                        safely("listener") { helper.addListener(listener) }
                        sink.onNaviConnection(true, null)
                        main.removeCallbacks(poll)
                        main.post(poll)
                    }
                },
                { e -> main.post { sink.onNaviConnection(false, e?.message ?: "연결 실패") } },
            )
        } catch (e: Exception) {
            sink.onNaviConnection(false, e.message ?: e.javaClass.simpleName)
        }
    }

    fun stop() {
        main.removeCallbacks(poll)
        if (connected) {
            safely("remove listener") { helper.removeListener(listener) }
            safely("release") { helper.release() }
        }
        connected = false
    }

    /** 쉼터로 안내한다. 반환값: 내비에 요청을 보냈는지 */
    fun guideTo(area: RestArea): Boolean {
        if (!connected) return false
        return try {
            if (routeActive) {
                if (waypointAdded) helper.removeWaypoint(WaypointIndex.FIRST)
                helper.addWaypoint(
                    RequestWaypointInfo(
                        latitude = area.position.lat,
                        longitude = area.position.lon,
                        poiName = area.name,
                        waypointIndex = WaypointIndex.FIRST,
                    ),
                )
                waypointAdded = true
            } else {
                helper.requestRoute(
                    RouteInfo(
                        latitude = area.position.lat,
                        longitude = area.position.lon,
                        poiName = area.name,
                    ),
                )
                waypointAdded = false
            }
            true
        } catch (e: Exception) {
            sink.onNaviMessage("내비 안내 요청 실패: ${e.message}")
            false
        }
    }

    fun cancelGuidance() {
        if (!connected) return
        safely("cancel") {
            if (waypointAdded) helper.removeWaypoint(WaypointIndex.FIRST) else helper.cancelRoute()
        }
        waypointAdded = false
    }

    private fun handle(method: String, args: Array<out Any?>) {
        val first = args.firstOrNull()
        when (method) {
            "onCurrentLocationInfo" -> (first as? CurrentLocationInfo)?.let { onLocation(it) }
            "onDestinationInfo" -> onDestination(first as? DestinationInfo)
            "onTBTInfo" -> {
                val next = (first as? List<*>)?.firstOrNull() as? TBTInfo
                sink.onNaviNextManeuver(next?.distance)
            }
            "onCurrentRoadSpeedLimit" -> sink.onNaviSpeedLimit((first as? Number)?.toInt()?.takeIf { it > 0 })
            "onNavigationStatus" -> {
                val status = first as? NavigationStatus
                if (status != null) routeActive = status == NavigationStatus.ROUTE_DRIVE
            }
            "onRouteStarted", "onRoutingCompleted" -> routeActive = true
            "onRouteEnded", "onRouteCancelled" -> {
                routeActive = false
                waypointAdded = false
                sink.onNaviDestination(null, null, null)
            }
            "onWayPointArrived" -> {
                waypointAdded = false
                sink.onNaviWaypointArrived(args.firstOrNull()?.let { describeArrival(it) })
            }
            "onError" -> sink.onNaviMessage("내비 오류: $first")
        }
    }

    private fun describeArrival(info: Any): String? =
        (info as? ai.pleos.playground.navi.data.WaypointArrivedInfo)?.name

    private fun onLocation(info: CurrentLocationInfo) {
        val position = LatLng(info.latitude, info.longitude)
        if (position.lat == 0.0 && position.lon == 0.0) return
        val now = SystemClock.elapsedRealtime()
        val prev = lastFix
        if (prev == null) {
            lastFix = position
            lastFixAt = now
        } else {
            val dt = (now - lastFixAt) / 1000.0
            if (dt >= 0.8) {
                val v = Geo.distanceMeters(prev, position) / dt * 3.6
                val smoothed = speedKmh?.let { 0.5 * v + 0.5 * it } ?: v
                speedKmh = smoothed
                lastFix = position
                lastFixAt = now
            }
        }
        val heading = info.heading.takeIf { !it.isNaN() }
        sink.onNaviLocation(position, heading, speedKmh)
    }

    private fun onDestination(info: DestinationInfo?) {
        if (info == null || (info.latitude == 0.0 && info.longitude == 0.0)) {
            routeActive = false
            sink.onNaviDestination(null, null, null)
            return
        }
        routeActive = true
        sink.onNaviDestination(LatLng(info.latitude, info.longitude), info.name, info.remainTime)
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "$what failed: $e")
        }
    }

    companion object {
        private const val TAG = "RestMateNavi"
        private const val POLL_INTERVAL_MS = 1_000L
    }
}
