package kr.contest.pleosapp.sdk

import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kr.contest.pleosapp.core.Geo
import kr.contest.pleosapp.core.LatLng
import kr.contest.pleosapp.core.NaviTime
import kr.contest.pleosapp.core.Place
import kr.contest.pleosapp.core.PlaceKind

/**
 * Pleos NaviHelper SDK 연결.
 * - 현재 위치·진행 방향을 1초마다 조회하고, 위치 변화로 속도를 추정한다 (Vehicle SDK에 차속 API가 없음).
 * - 목적지·남은 시간과 다음 회전 안내까지 거리를 5초마다 조회한다.
 * - 즐겨찾기(집·회사)와 최근 목적지를 읽고, 다른 목적지까지 바로 가는 시간을 경로 조회(requestTrip)로 묻는다.
 * - 경로 요청: 목적지 + 경유지(최대 2곳). 경유지가 없으면 목적지만 요청한다.
 */
class NaviSignals(context: Context, private val sink: Sink) {
    interface Sink {
        fun onNaviConnection(connected: Boolean, message: String?)
        fun onNaviLocation(position: LatLng, headingDeg: Double?, speedKmh: Double?)
        /** 목적지가 없으면 null. remainSeconds는 목적지까지 남은 시간(초, 경유지 포함) */
        fun onNaviDestination(destination: Place?, remainSeconds: Int?)
        fun onNaviNextManeuver(meters: Double?)
        fun onNaviSpeedLimit(kmh: Int?)
        fun onNaviWaypointArrived(name: String?)
        fun onNaviArrived(name: String?)
        /** 즐겨찾기(집·회사 먼저)와 최근 목적지 */
        fun onNaviPlaces(places: List<Place>)
        /** [requestTripTime]의 답: 지금 위치에서 그곳까지 바로 가면 걸리는 시간 */
        fun onNaviTripTime(destination: Place, durationMs: Long)
        fun onNaviMessage(message: String)
    }

    private val helper = NaviHelper(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private var connected = false

    private var lastFixAt = 0L
    private var lastFix: LatLng? = null
    private var speedKmh: Double? = null
    private var pollCount = 0

    private var bookmarks: List<Place> = emptyList()
    private var recents: List<Place> = emptyList()
    private var pendingTrip: Place? = null

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
                        requestPlaces()
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

    /** 즐겨찾기와 최근 목적지를 다시 읽는다. 답은 [Sink.onNaviPlaces]로 온다. */
    fun requestPlaces() {
        if (!connected) return
        safely("bookmarks") { helper.getBookmarkInfo() }
        safely("recent destinations") { helper.getRecentDestinationInfo() }
    }

    /** 그곳까지 바로 가면 걸리는 시간을 묻는다. 답은 [Sink.onNaviTripTime]으로 온다 (여러 번 물으면 마지막 것만). */
    fun requestTripTime(place: Place): Boolean {
        if (!connected) return false
        pendingTrip = place
        return try {
            helper.requestTrip(TripRequest(place.position.lat, place.position.lon, place.poiId, place.name, place.poiSubId))
            true
        } catch (e: Exception) {
            pendingTrip = null
            Log.w(TAG, "trip failed: $e")
            false
        }
    }

    /**
     * 목적지로 안내를 요청한다. 경유지가 있으면 경유지를 거쳐 가는 경로로 요청한다 (최대 2곳).
     * 반환값: 내비에 요청을 보냈는지
     */
    fun routeTo(destination: Place, via: List<Place>): Boolean {
        if (!connected) return false
        return try {
            val dest = routeInfo(destination)
            if (via.isEmpty()) {
                helper.requestRoute(dest)
            } else {
                val waypoints = via.take(2).mapIndexed { i, p ->
                    waypointInfo(p, if (i == 0) WaypointIndex.FIRST else WaypointIndex.SECOND)
                }
                helper.requestRouteWithWaypoints(RouteWithWaypointsRequest(dest, waypoints))
            }
            true
        } catch (e: Exception) {
            sink.onNaviMessage("내비 경로 요청 실패: ${e.message}")
            false
        }
    }

    fun cancelRoute() {
        if (!connected) return
        safely("cancel") { helper.cancelRoute() }
    }

    // POI ID가 없는 좌표 안내는 POI 관련 값을 빈 문자열로 넘긴다 (에뮬레이터에서 동작 확인 필요)
    private fun routeInfo(p: Place) = RouteInfo(
        latitude = p.position.lat,
        longitude = p.position.lon,
        poiName = p.name,
        poiId = p.poiId,
        address = p.address,
        poiSubId = p.poiSubId,
    )

    private fun waypointInfo(p: Place, index: WaypointIndex) = RequestWaypointInfo(
        latitude = p.position.lat,
        longitude = p.position.lon,
        poiName = p.name,
        waypointIndex = index,
        poiId = p.poiId,
        address = p.address,
        poiSubId = p.poiSubId,
    )

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
            "onRouteEnded", "onRouteCancelled" -> sink.onNaviDestination(null, null)
            "onWayPointArrived" -> sink.onNaviWaypointArrived((first as? WaypointArrivedInfo)?.name)
            "onDestinationArrived" -> sink.onNaviArrived((first as? DestinationArrivedInfo)?.name)
            "onBookmarkInfo" -> {
                bookmarks = (first as? List<*>).orEmpty()
                    .mapNotNull { item -> (item as? BookmarkInfo)?.let { bookmarkPlace(it) } }
                    .sortedBy { kindOrder(it.kind) }
                publishPlaces()
            }
            "onRecentDestinationInfo" -> {
                recents = (first as? List<*>).orEmpty()
                    .mapNotNull { item -> (item as? RecentDestinationInfo)?.let { recentPlace(it) } }
                publishPlaces()
            }
            "onTripInfo" -> onTrip(first as? TripInfo)
            "onError" -> sink.onNaviMessage("내비 오류: $first")
        }
    }

    private fun publishPlaces() {
        val out = ArrayList<Place>(bookmarks)
        for (r in recents) {
            if (out.count { it.kind == PlaceKind.RECENT } >= MAX_RECENTS) break
            if (out.none { it.sameSpot(r) }) out += r
        }
        sink.onNaviPlaces(out)
    }

    private fun bookmarkPlace(b: BookmarkInfo): Place? {
        if (b.latitude == 0.0 && b.longitude == 0.0) return null
        val kind = when (b.category) {
            BookmarkType.HOME -> PlaceKind.HOME
            BookmarkType.OFFICE -> PlaceKind.OFFICE
            BookmarkType.EV_CHARGING_STATION -> return null
            else -> PlaceKind.BOOKMARK
        }
        val fallback = when (kind) {
            PlaceKind.HOME -> "집"
            PlaceKind.OFFICE -> "회사"
            else -> "즐겨찾기"
        }
        return Place(
            name = b.name.orEmpty().ifBlank { fallback },
            position = LatLng(b.latitude, b.longitude),
            kind = kind,
            poiId = b.poiId.orEmpty(),
            poiSubId = b.poiSubId.orEmpty(),
            address = b.address.orEmpty(),
        )
    }

    private fun recentPlace(r: RecentDestinationInfo): Place? {
        if (r.latitude == 0.0 && r.longitude == 0.0) return null
        return Place(
            name = r.name.orEmpty().ifBlank { r.address.orEmpty().ifBlank { "최근 목적지" } },
            position = LatLng(r.latitude, r.longitude),
            kind = PlaceKind.RECENT,
            poiId = r.poiId.orEmpty(),
            poiSubId = r.poiSubId.orEmpty(),
            address = r.address.orEmpty(),
        )
    }

    private fun kindOrder(kind: PlaceKind): Int = when (kind) {
        PlaceKind.HOME -> 0
        PlaceKind.OFFICE -> 1
        else -> 2
    }

    private fun onTrip(info: TripInfo?) {
        val place = pendingTrip ?: return
        pendingTrip = null
        if (info == null) return
        val ms = NaviTime.tripDurationMs(info.eta, info.distance, System.currentTimeMillis())
        if (ms == null) {
            Log.w(TAG, "trip eta not understood: eta=${info.eta} distance=${info.distance}")
            return
        }
        sink.onNaviTripTime(place, ms)
    }

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
            sink.onNaviDestination(null, null)
            return
        }
        val place = Place(
            name = info.name.orEmpty().ifBlank { "목적지" },
            position = LatLng(info.latitude, info.longitude),
            kind = PlaceKind.CURRENT,
            poiId = info.poiId.orEmpty(),
            poiSubId = info.poiSubId.orEmpty(),
            address = info.address.orEmpty(),
        )
        sink.onNaviDestination(place, info.remainTime.takeIf { it > 0 })
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "$what failed: $e")
        }
    }

    companion object {
        private const val TAG = "DriveMateNavi"
        private const val POLL_INTERVAL_MS = 1_000L
        private const val MAX_RECENTS = 5
    }
}
