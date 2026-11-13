package kr.contest.pleosapp.app

import kr.contest.pleosapp.core.Geo
import kr.contest.pleosapp.core.LatLng

/**
 * 시연용 모의 차가 곧게 달리는 구간을 따라가며 기록한다 (미니맵이 도로 모양을 따라 그리게). 안드로이드에 의존하지 않는다.
 *
 * 경로가 바뀌거나 경유지를 지나면 달리던 구간을 지나온 구간으로 넘긴다:
 * 경유지·목적지에 닿았으면 끝까지(1), 도중에 경로가 바뀌었으면 그때까지 온 만큼.
 */
class LegTracker(private val max: Int = 16) {
    var current: DriveLeg? = null
        private set

    private val finished = ArrayList<DriveLeg>()

    /** 지나온 구간 (오래된 것부터) */
    val done: List<DriveLeg> get() = finished

    /**
     * @param from 지금 구간의 시작점 (경로를 받은 곳·방금 지난 경유지)
     * @param to 지금 향하는 곳. 없으면 (목적지에 닿았거나 경로가 없으면) null
     */
    fun update(from: LatLng, to: LatLng?, position: LatLng) {
        val cur = current
        if (cur != null && (to == null || !same(cur.from, from) || !same(cur.to, to))) {
            val reached = same(cur.to, position) || same(cur.to, from)
            val leg = if (reached) cur.copy(progress = 1f) else cur
            if (leg.progress > 0.001f) finished += leg
            while (finished.size > max) finished.removeAt(0)
            current = null
        }
        if (to == null) return
        val total = Geo.distanceMeters(from, to)
        val left = Geo.distanceMeters(position, to)
        val progress = if (total < 1.0) 1f else (1.0 - left / total).toFloat().coerceIn(0f, 1f)
        current = DriveLeg(from, to, progress)
    }

    fun clear() {
        current = null
        finished.clear()
    }

    private fun same(a: LatLng, b: LatLng): Boolean = Geo.distanceMeters(a, b) < 1.0
}
