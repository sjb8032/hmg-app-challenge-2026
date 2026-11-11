package kr.contest.pleosapp.core

data class RestAreaCandidate(
    val area: RestArea,
    /** 현재 위치에서 직선거리 (m) */
    val straightMeters: Double,
    /** 도로 우회를 감안한 추정 거리 (m) */
    val roadMeters: Double,
    /** 추정 도착 시간 (분) */
    val etaMinutes: Double,
) {
    /** 검색 사이에 화면을 갱신할 때, 지금 위치·속도로 거리와 도착 시간을 다시 계산한다. */
    fun updatedFor(position: LatLng?, speedKmh: Double?): RestAreaCandidate {
        if (position == null || straightMeters <= 0.0) return this
        val detour = roadMeters / straightMeters
        val d = Geo.distanceMeters(position, area.position)
        val road = d * detour
        return copy(straightMeters = d, roadMeters = road, etaMinutes = RestAreaFinder.etaMinutes(road, speedKmh))
    }
}

data class RestAreaSearchResult(
    /** 도착이 빠른 순서 */
    val candidates: List<RestAreaCandidate>,
    /** 가장 가까운 쉼터를 지나친 뒤 그다음 쉼터까지 추가로 걸리는 추정 시간 (분). 후보가 2개 미만이면 null */
    val gapAfterFirstMinutes: Double?,
) {
    val best: RestAreaCandidate? get() = candidates.firstOrNull()
}

/**
 * 진행 방향 앞쪽에서 빨리 닿는 쉼터를 고른다.
 *
 * 경로 모양을 모르므로 직선거리에 우회 계수를 곱해 추정한다.
 * - 진행 방향 기준 앞쪽 부채꼴 안에 있는 쉼터만 본다.
 * - 이름에 "○○방향"이 붙은 쉼터는 그 지명 쪽이 진행 방향과 맞을 때만 고른다 (반대편 차로 제외).
 * - 이름이 같은 쉼터가 가까이 두 개 있으면 (양방향 휴게소) 진행 방향 오른쪽 것을 고른다 (우측 통행).
 * - 목적지를 알면 목적지에서 더 멀어지는 쉼터는 뺀다.
 */
class RestAreaFinder(
    private val areas: List<RestArea>,
    private val maxSearchMeters: Double = 80_000.0,
    private val minAheadMeters: Double = 300.0,
    private val forwardConeDeg: Double = 60.0,
    private val nearConeDeg: Double = 80.0,
    private val nearMeters: Double = 3_000.0,
    private val detourFactor: Double = DEFAULT_DETOUR_FACTOR,
    private val fallbackSpeedKmh: Double = DEFAULT_FALLBACK_SPEED_KMH,
) {
    val size: Int get() = areas.size

    companion object {
        const val DEFAULT_DETOUR_FACTOR = 1.25
        const val DEFAULT_FALLBACK_SPEED_KMH = 80.0

        /** 이보다 느리면 정체·정차 중으로 보고 기본 속도로 도착 시간을 잡는다. */
        const val MIN_TRUSTED_SPEED_KMH = 40.0

        fun etaMinutes(
            roadMeters: Double,
            speedKmh: Double?,
            fallbackSpeedKmh: Double = DEFAULT_FALLBACK_SPEED_KMH,
        ): Double {
            val speed = speedKmh?.takeIf { it >= MIN_TRUSTED_SPEED_KMH } ?: fallbackSpeedKmh
            return roadMeters / 1000.0 / speed * 60.0
        }
    }

    /**
     * @param expresswayOnly 고속도로 주행 중이면 true. 본선 옆 쉼터만 고르고, 없으면 전체에서 고른다.
     */
    fun findAhead(
        position: LatLng,
        headingDeg: Double?,
        destination: LatLng? = null,
        speedKmh: Double? = null,
        limit: Int = 5,
        expresswayOnly: Boolean = false,
    ): RestAreaSearchResult {
        if (expresswayOnly) {
            val onRoad = search(areas.filter { it.onExpressway }, position, headingDeg, destination, speedKmh, limit)
            if (onRoad.best != null) return onRoad
        }
        return search(areas, position, headingDeg, destination, speedKmh, limit)
    }

    private fun search(
        pool: List<RestArea>,
        position: LatLng,
        headingDeg: Double?,
        destination: LatLng?,
        speedKmh: Double?,
        limit: Int,
    ): RestAreaSearchResult {
        val travelHeading = headingDeg ?: destination?.let { Geo.bearingDegrees(position, it) }
        val distToDest = destination?.let { Geo.distanceMeters(position, it) }
        val sorted = pool.mapNotNull { area ->
            val d = Geo.distanceMeters(position, area.position)
            if (d < minAheadMeters || d > maxSearchMeters) return@mapNotNull null
            if (travelHeading != null) {
                val cone = if (d < nearMeters) nearConeDeg else forwardConeDeg
                val bearing = Geo.bearingDegrees(position, area.position)
                if (Geo.angleDiff(bearing, travelHeading) > cone) return@mapNotNull null
                if (!matchesDirection(area, travelHeading)) return@mapNotNull null
            }
            if (destination != null && distToDest != null) {
                if (Geo.distanceMeters(area.position, destination) > distToDest + 1_000.0) return@mapNotNull null
            }
            val road = d * detourFactor
            RestAreaCandidate(area, d, road, etaMinutes(road, speedKmh, fallbackSpeedKmh))
        }.sortedBy { it.etaMinutes }

        val picked = if (travelHeading != null) pickTravelSide(sorted, position, travelHeading) else sorted
        val gap = if (picked.size >= 2) picked[1].etaMinutes - picked[0].etaMinutes else null
        return RestAreaSearchResult(picked.take(limit), gap)
    }

    /** 이름이 같은 쉼터가 1.5km 안에 여럿이면 진행 방향 오른쪽에 있는 하나만 남긴다. */
    private fun pickTravelSide(
        sorted: List<RestAreaCandidate>,
        position: LatLng,
        heading: Double,
    ): List<RestAreaCandidate> {
        val kept = ArrayList<RestAreaCandidate>()
        for (c in sorted) {
            val twinIndex = kept.indexOfFirst {
                baseName(it.area.name) == baseName(c.area.name) &&
                    Geo.distanceMeters(it.area.position, c.area.position) < 1_500.0
            }
            if (twinIndex < 0) {
                kept.add(c)
                continue
            }
            val twin = kept[twinIndex]
            val twinSide = Geo.lateralOffsetMeters(position, heading, twin.area.position)
            val side = Geo.lateralOffsetMeters(position, heading, c.area.position)
            if (side > twinSide) kept[twinIndex] = c
        }
        return kept.sortedBy { it.etaMinutes }
    }

    private fun baseName(name: String): String =
        RestMateText.spokenName(name).replace(" ", "")

    private fun matchesDirection(area: RestArea, travelHeading: Double): Boolean {
        val target = area.directionTarget ?: return true
        // 방향 지명 바로 근처에서는 방위각이 흔들리므로 판단하지 않는다.
        if (Geo.distanceMeters(area.position, target) < 5_000.0) return true
        val toward = Geo.bearingDegrees(area.position, target)
        return Geo.angleDiff(toward, travelHeading) <= 90.0
    }
}
