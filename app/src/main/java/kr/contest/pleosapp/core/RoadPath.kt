package kr.contest.pleosapp.core

/**
 * 도로 모양을 따라 그린 길(점 목록)을 다루는 도우미. 안드로이드에 의존하지 않는다.
 *
 * 시연용 모의 차는 경유지 사이를 곧게 움직인다(시간 계산을 단순하게 두려고). 화면에는 같은 진행 비율만큼
 * 도로 모양을 따라간 자리에 차를 그린다: 곧은 구간을 40% 왔으면 도로 길의 40% 지점.
 */
object RoadPath {
    /** 길이 (m) */
    fun length(path: List<LatLng>): Double {
        var total = 0.0
        for (i in 1 until path.size) total += Geo.distanceMeters(path[i - 1], path[i])
        return total
    }

    /** 처음부터 [fraction](0~1) 지점까지 */
    fun head(path: List<LatLng>, fraction: Double): List<LatLng> {
        if (path.size < 2) return path
        val f = fraction.coerceIn(0.0, 1.0)
        val target = length(path) * f
        val out = ArrayList<LatLng>()
        out += path[0]
        var walked = 0.0
        for (i in 1 until path.size) {
            val d = Geo.distanceMeters(path[i - 1], path[i])
            if (walked + d >= target) {
                out += lerp(path[i - 1], path[i], if (d > 0.0) (target - walked) / d else 0.0)
                return out
            }
            walked += d
            out += path[i]
        }
        return out
    }

    /** [fraction](0~1) 지점부터 끝까지 */
    fun tail(path: List<LatLng>, fraction: Double): List<LatLng> {
        if (path.size < 2) return path
        val f = fraction.coerceIn(0.0, 1.0)
        val target = length(path) * f
        var walked = 0.0
        for (i in 1 until path.size) {
            val d = Geo.distanceMeters(path[i - 1], path[i])
            if (walked + d >= target) {
                val start = lerp(path[i - 1], path[i], if (d > 0.0) (target - walked) / d else 0.0)
                return listOf(start) + path.subList(i, path.size)
            }
            walked += d
        }
        return listOf(path.last())
    }

    /** [fraction](0~1) 지점 */
    fun pointAt(path: List<LatLng>, fraction: Double): LatLng? = head(path, fraction).lastOrNull()

    /**
     * [fraction] 지점에서 앞으로 [aheadMeters] 간 곳을 바라보는 방향 (도로가 굽어도 화면이 흔들리지 않게 조금 앞을 본다).
     * 길이 너무 짧으면 null.
     */
    fun bearingAt(path: List<LatLng>, fraction: Double, aheadMeters: Double = 300.0): Double? {
        val total = length(path)
        if (total < 1.0) return null
        val from = pointAt(path, fraction) ?: return null
        val to = pointAt(path, (fraction + aheadMeters / total).coerceAtMost(1.0)) ?: return null
        if (Geo.distanceMeters(from, to) < 1.0) {
            val back = pointAt(path, (fraction - aheadMeters / total).coerceAtLeast(0.0)) ?: return null
            if (Geo.distanceMeters(back, from) < 1.0) return null
            return Geo.bearingDegrees(back, from)
        }
        return Geo.bearingDegrees(from, to)
    }

    /** [minMeters]보다 가까운 점을 솎는다 (처음과 끝은 남긴다) */
    fun thin(path: List<LatLng>, minMeters: Double): List<LatLng> {
        if (path.size <= 2) return path
        val out = ArrayList<LatLng>()
        out += path.first()
        for (i in 1 until path.lastIndex) {
            if (Geo.distanceMeters(out.last(), path[i]) >= minMeters) out += path[i]
        }
        out += path.last()
        return out
    }

    /** 이어 붙이기 (앞 길의 끝과 뒤 길의 처음이 같으면 한 번만) */
    fun join(parts: List<List<LatLng>>): List<LatLng> {
        val out = ArrayList<LatLng>()
        for (part in parts) {
            for (p in part) {
                if (out.isEmpty() || Geo.distanceMeters(out.last(), p) > 0.5) out += p
            }
        }
        return out
    }

    private fun lerp(a: LatLng, b: LatLng, t: Double): LatLng =
        LatLng(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
}
