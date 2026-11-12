package kr.contest.pleosapp.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 남는 시간을 계산하고 경유지 후보를 고른다. 안드로이드에 의존하지 않는다.
 *
 * 경로 모양을 모르므로 거리는 직선거리 × 우회 계수로 잡고, 속도는 바로 가는 시간(내비 값)에서 얻는다.
 * 후보를 고른 뒤에는 내비가 알려 주는 실제 남은 시간으로 [DriveEngine]이 계속 고친다.
 *
 * 남는 시간 = 목표 시각 − 지금 − 바로 가는 시간 − 여유
 */
object TimeFitPlanner {
    private const val MIN_FILL = 0.35
    private const val SCENIC_BONUS = 0.15
    /** 고른 분위기(야경·강변·다리·공원)에 맞는 지점 가산 */
    private const val PREFER_BONUS = 0.5
    /** '도심 피하기'에서 도심 지점 감산 */
    private const val CITY_PENALTY = 0.5
    private const val NEAR_METERS = 500.0
    private const val DISTINCT_METERS = 2_000.0
    private const val MAX_SCENIC = 2
    private const val MAX_OPTIONS = 3
    private const val DETOUR_FILL = 0.85
    private const val MAX_DETOUR_OFFSET_METERS = 15_000.0
    private const val MIN_SPEED_MPS = 12.0 / 3.6
    /** 고속도로는 도심 기준 우회 계수보다 곧아서 환산 속도가 100km/h를 넘을 수 있다. */
    private const val MAX_SPEED_MPS = 130.0 / 3.6
    private val SCENIC_TAGS = setOf(CourseTag.RIVER, CourseTag.NIGHT_VIEW, CourseTag.LAKE, CourseTag.SEA, CourseTag.BRIDGE)

    /** 이보다 오래 가는 길이면 휴게소에서만 쉬다 가고, 가는 길 축 위의 지점만 본다 */
    private const val LONG_TRIP_MS = 45 * MINUTE_MS

    /** 먼 길: 길의 30%를 지난 곳부터 목적지 조금 너머까지, 옆으로는 max(10km, 길이의 12%) 안 */
    private const val AXIS_MIN_PROGRESS = 0.3
    private const val AXIS_MAX_PROGRESS = 1.1
    private const val AXIS_MIN_LATERAL_METERS = 10_000.0
    private const val AXIS_LATERAL_RATIO = 0.12

    /** 온타임 모드 짧은 길: 목적지에서 max(출발지까지 거리, 5km) 안의 지점만 (반대쪽으로 멀리 돌지 않게) */
    private const val ON_TIME_MIN_RADIUS_METERS = 5_000.0

    /** 근처에서 기다리기: 목적지에서 2km 안 (없으면 3km 안) */
    private const val WAIT_NEAR_METERS = 2_000.0
    private const val WAIT_NEAR_MAX_METERS = 3_000.0

    /** '도심 피하기'에서 피하는 곳: 서울시청·강남역 3km 안 */
    private const val CITY_CENTER_METERS = 3_000.0
    private val CITY_CENTERS = listOf(LatLng(37.5663, 126.9779), LatLng(37.4979, 127.0276))

    fun slackMs(now: Long, targetAt: Long, directMs: Long, config: DriveConfig): Long =
        targetAt - now - directMs - config.marginMs

    /** 내비 값이 없을 때 바로 가는 시간을 직선거리로 추정한다. */
    fun estimateDirectMs(from: LatLng, to: LatLng, config: DriveConfig): Long =
        travelMs(Geo.distanceMeters(from, to) * config.detourFactor, config.fallbackSpeedKmh / 3.6)

    /** 바로 가는 시간과 거리로 이 길의 평균 속도(m/s)를 잡는다. */
    fun effectiveSpeedMps(from: LatLng, to: LatLng, directMs: Long?, config: DriveConfig): Double {
        val fallback = config.fallbackSpeedKmh / 3.6
        if (directMs == null || directMs < MINUTE_MS) return fallback
        val road = Geo.distanceMeters(from, to) * config.detourFactor
        if (road < NEAR_METERS) return fallback
        return (road / (directMs / 1000.0)).coerceIn(MIN_SPEED_MPS, MAX_SPEED_MPS)
    }

    /** 경유지를 거치면 바로 가는 것보다 더 걸리는 시간 */
    fun addedMs(from: LatLng, to: LatLng, via: LatLng, speedMps: Double, config: DriveConfig): Long {
        val direct = Geo.distanceMeters(from, to)
        val path = Geo.distanceMeters(from, via) + Geo.distanceMeters(via, to)
        return travelMs((path - direct).coerceAtLeast(0.0) * config.detourFactor, speedMps)
    }

    /** 경유지를 차례로 거쳐 목적지까지 가는 시간 (직선거리 × 우회 계수) */
    fun pathMs(from: LatLng, via: List<Place>, to: LatLng, speedMps: Double, config: DriveConfig): Long {
        var meters = 0.0
        var at = from
        for (p in via) {
            meters += Geo.distanceMeters(at, p.position)
            at = p.position
        }
        meters += Geo.distanceMeters(at, to)
        return travelMs(meters * config.detourFactor, speedMps)
    }

    /**
     * 코스 후보를 2~3개 낸다: 경치 좋은 지점 최대 2곳, 남는 시간이 길면 쉬다 가는 곳 최대 2곳(종류가 다른 곳).
     * 고른 지점이 근처에 없으면 주변으로 돌아가는 코스를 낸다.
     *
     * - 먼 길(45분 이상)은 가는 길 축 위의 지점만 본다 (지나온 쪽이나 옆으로 멀리 벗어난 곳 제외).
     * - 온타임 모드의 짧은 길은 목적지 쪽 반경 안의 지점만 본다.
     * - 남는 시간이 [maxAddedMs]보다 길면: 쉬다 가는 곳이 있으면 그것만 내고(오래 돌기보다 쉬기),
     *   없으면 [maxAddedMs]만큼만 채우는 경치 코스를 낸다.
     *
     * @param restPlaces 쉬다 가는 코스로 들를 수 있는 곳. 비우면 쉬다 가는 코스를 내지 않는다.
     * @param maxAddedMs 경치·우회 코스로 더 쓸 수 있는 최대 시간 (온타임 모드에서 한 시간씩 돌지 않게)
     */
    fun options(
        from: LatLng,
        destination: Place,
        directMs: Long,
        slackMs: Long,
        points: List<CoursePoint>,
        restPlaces: List<RestArea>,
        config: DriveConfig,
        maxAddedMs: Long = Long.MAX_VALUE,
        wish: CourseWish = CourseWish(),
    ): List<CourseOption> {
        if (slackMs < config.minSlackMs) return listOf(direct(slackMs))
        val to = destination.position
        val speed = effectiveSpeedMps(from, to, directMs, config)
        val rest = if (slackMs >= config.restStopMinSlackMs) {
            restStopOptions(from, to, directMs, slackMs, speed, restPlaces, config)
        } else {
            emptyList()
        }
        if (slackMs > maxAddedMs && rest.isNotEmpty()) return rest
        val driveSlack = minOf(slackMs, maxAddedMs)
        val candidates = candidatePoints(from, to, directMs, points, wish)
        val scenic = scenicOptions(from, to, driveSlack, speed, candidates, wish, config)
        val detour = if (scenic.isEmpty()) detourOption(from, to, driveSlack, speed, config) else null
        return (scenic + rest + listOfNotNull(detour)).take(MAX_OPTIONS).ifEmpty { listOf(direct(slackMs)) }
    }

    /**
     * 온타임 모드 '근처에서 기다리기': 목적지 2km 안(없으면 3km 안)의 공원·공영 주차장 등에 세워 기다렸다 간다.
     * 종류가 다른 곳을 먼저 골라 최대 2곳. 기다릴 곳이 없으면 빈 목록.
     */
    fun waitNearOptions(
        from: LatLng,
        destination: Place,
        directMs: Long,
        slackMs: Long,
        restPlaces: List<RestArea>,
        config: DriveConfig,
    ): List<CourseOption> {
        if (slackMs < config.minSlackMs) return emptyList()
        val to = destination.position
        val speed = effectiveSpeedMps(from, to, directMs, config)
        fun within(limit: Double) = restPlaces.filter {
            Geo.distanceMeters(it.position, to) <= limit && Geo.distanceMeters(from, it.position) >= NEAR_METERS
        }
        val pool = within(WAIT_NEAR_METERS).ifEmpty { within(WAIT_NEAR_MAX_METERS) }
        val usable = pool
            .map { it to addedMs(from, to, it.position, speed, config) }
            .filter { (_, detour) -> detour < slackMs }
            .sortedWith(
                compareBy<Pair<RestArea, Long>>({ if (RestAreaFinder.isCityPlace(it.first)) 0 else 1 })
                    .thenBy { Geo.distanceMeters(it.first.position, to) },
            )
        val first = usable.firstOrNull() ?: return emptyList()
        val second = usable.drop(1).firstOrNull { it.first.type != first.first.type } ?: usable.getOrNull(1)
        return listOfNotNull(first, second).map { (area, detour) ->
            val stay = slackMs - detour
            CourseOption(
                id = "wait-${area.id}",
                kind = CourseKind.REST_STOP,
                title = "${RestMateText.spokenName(area.name)}에서 기다리기",
                reason = "${area.type.label} · 목적지까지 ${RestMateText.distance(Geo.distanceMeters(area.position, to))} · " +
                    "약 ${DriveText.minutes(stay)}분 기다려요",
                via = listOf(Place(area.name, area.position, PlaceKind.WAYPOINT)),
                addedMs = slackMs,
                stayMs = stay,
                restArea = area,
                restKind = RestKind.WAIT,
            )
        }
    }

    /**
     * 바로 가는 코스.
     * @param arriveAt 바로 가면 도착하는 시각. 주면 일찍 닿는 만큼을 이유에 적는다 ("바로 가면 오후 6:45 도착, 15분 일찍")
     */
    fun direct(slackMs: Long, arriveAt: Long? = null, marginMs: Long = 0L): CourseOption {
        val early = slackMs + marginMs
        val reason = when {
            slackMs < 0 -> "바로 가도 약 ${DriveText.minutes(slackMs)}분 늦을 수 있어요"
            arriveAt == null || early < 2 * MINUTE_MS -> "바로 가도 시간에 맞아요"
            else -> "바로 가면 ${DriveText.clock(arriveAt)} 도착, ${DriveText.minutes(early)}분 일찍"
        }
        return CourseOption(
            id = "direct",
            kind = CourseKind.DIRECT,
            title = "바로 가기",
            reason = reason,
            via = emptyList(),
            addedMs = 0L,
        )
    }

    private fun candidatePoints(
        from: LatLng,
        to: LatLng,
        directMs: Long,
        points: List<CoursePoint>,
        wish: CourseWish,
    ): List<CoursePoint> {
        val total = Geo.distanceMeters(from, to)
        return when {
            directMs >= LONG_TRIP_MS -> points.filter { onAxis(from, to, total, it.position) }
            wish.onTime -> {
                val radius = max(total, ON_TIME_MIN_RADIUS_METERS)
                points.filter { Geo.distanceMeters(it.position, to) <= radius }
            }
            else -> points
        }
    }

    private fun onAxis(from: LatLng, to: LatLng, total: Double, p: LatLng): Boolean {
        if (total < 1.0) return false
        val (along, across) = Geo.alongAcross(from, to, p)
        val progress = along / total
        return progress in AXIS_MIN_PROGRESS..AXIS_MAX_PROGRESS &&
            abs(across) <= max(AXIS_MIN_LATERAL_METERS, total * AXIS_LATERAL_RATIO)
    }

    private fun nearCityCenter(p: LatLng): Boolean = CITY_CENTERS.any { Geo.distanceMeters(it, p) <= CITY_CENTER_METERS }

    private fun scenicOptions(
        from: LatLng,
        to: LatLng,
        slackMs: Long,
        speed: Double,
        points: List<CoursePoint>,
        wish: CourseWish,
        config: DriveConfig,
    ): List<CourseOption> {
        data class Scored(val point: CoursePoint, val added: Long, val score: Double)

        val scored = points.mapNotNull { p ->
            if (Geo.distanceMeters(from, p.position) < NEAR_METERS || Geo.distanceMeters(to, p.position) < NEAR_METERS) {
                return@mapNotNull null
            }
            val added = addedMs(from, to, p.position, speed, config)
            if (added > slackMs || added < slackMs * MIN_FILL) return@mapNotNull null
            var bonus = if (p.tags.any { it in SCENIC_TAGS }) SCENIC_BONUS else 0.0
            if (p.tags.any { it in wish.prefer }) bonus += PREFER_BONUS
            if (wish.avoidCity && nearCityCenter(p.position)) bonus -= CITY_PENALTY
            Scored(p, added, added.toDouble() / slackMs + bonus)
        }.sortedByDescending { it.score }

        val picked = ArrayList<Scored>()
        for (s in scored) {
            if (picked.any { Geo.distanceMeters(it.point.position, s.point.position) < DISTINCT_METERS }) continue
            picked += s
            if (picked.size == MAX_SCENIC) break
        }
        return picked.map { s ->
            CourseOption(
                id = "scenic-${s.point.id}",
                kind = CourseKind.SCENIC,
                title = "${s.point.name} 거쳐 가기",
                reason = "${s.point.tags.joinToString("·") { it.label }} · 약 ${DriveText.minutes(s.added)}분 더",
                via = listOf(Place(s.point.name, s.point.position, PlaceKind.WAYPOINT)),
                addedMs = s.added,
                tags = s.point.tags,
            )
        }
    }

    /**
     * 가는 길에 들를 수 있는 쉼터에서 남는 시간만큼 쉬다 간다. 종류가 다른 곳으로 최대 2곳.
     * - 먼 길(고속도로)은 휴게소만, 길의 60% 즈음을 좋게 본다.
     * - 가까운 길(시내)은 공원·공영 주차장도 보고, 목적지 가까이(70% 즈음)를 좋게 본다. 깬 뒤 남은 길이 짧아 시간이 잘 맞는다.
     * - 돌아가는 시간이 짧을수록 좋다.
     */
    private fun restStopOptions(
        from: LatLng,
        to: LatLng,
        directMs: Long,
        slackMs: Long,
        speed: Double,
        restPlaces: List<RestArea>,
        config: DriveConfig,
    ): List<CourseOption> {
        val total = Geo.distanceMeters(from, to)
        val longTrip = directMs >= LONG_TRIP_MS
        val idealProgress = if (longTrip) 0.6 else 0.7
        val progressWeightMs = if (longTrip) 20 * MINUTE_MS else 5 * MINUTE_MS
        val pool = if (longTrip) {
            restPlaces.filter { it.type == RestAreaType.SERVICE_AREA }.ifEmpty {
                restPlaces.filter { it.type == RestAreaType.DROWSY_SHELTER || it.type == RestAreaType.REST_STOP }
            }
        } else {
            restPlaces
        }
        data class Scored(val area: RestArea, val detour: Long, val score: Double)

        val ranked = pool.asSequence()
            .filter { Geo.distanceMeters(from, it.position) >= NEAR_METERS }
            // 목적지 쪽에 있는 곳만 (지나온 쪽은 빼기)
            .filter { Geo.distanceMeters(it.position, to) < total }
            // "○○방향" 쉼터는 가는 방향과 맞는 쪽만 (반대편 차로 제외)
            .filter { RestAreaFinder.matchesDirection(it, Geo.bearingDegrees(it.position, to)) }
            .map { it to addedMs(from, to, it.position, speed, config) }
            .filter { (_, detour) -> detour <= config.restStopMaxDetourMs && detour < slackMs }
            .map { (area, detour) ->
                val a = Geo.distanceMeters(from, area.position)
                val progress = a / (a + Geo.distanceMeters(area.position, to))
                Scored(area, detour, detour + abs(progress - idealProgress) * progressWeightMs)
            }
            .sortedBy { it.score }
            .toList()
        val first = ranked.firstOrNull() ?: return emptyList()
        val second = ranked.drop(1).firstOrNull {
            it.area.type != first.area.type && Geo.distanceMeters(it.area.position, first.area.position) >= DISTINCT_METERS
        }
        return listOfNotNull(first, second).map { s ->
            val stay = slackMs - s.detour
            CourseOption(
                id = "rest-${s.area.id}",
                kind = CourseKind.REST_STOP,
                title = "${RestMateText.spokenName(s.area.name)}에서 쉬다 가기",
                reason = "${s.area.type.label} · 약 ${DriveText.minutes(stay)}분 쉬고 시간 맞춰 출발",
                via = listOf(Place(s.area.name, s.area.position, PlaceKind.WAYPOINT)),
                addedMs = slackMs,
                stayMs = stay,
                restArea = s.area,
                restKind = RestKind.WAIT,
            )
        }
    }

    /**
     * 고른 지점이 없을 때: 출발지와 목적지 가운데에서 오른쪽으로 비켜난 점을 거친다.
     * 옆으로 h만큼 비켜나면 길이가 2·√((d/2)² + h²) − d 만큼 늘어나는 것을 거꾸로 푼다.
     */
    private fun detourOption(
        from: LatLng,
        to: LatLng,
        slackMs: Long,
        speed: Double,
        config: DriveConfig,
    ): CourseOption? {
        val d = Geo.distanceMeters(from, to)
        val wantedExtra = slackMs * DETOUR_FILL / 1000.0 * speed / config.detourFactor
        val half = d / 2
        val leg = (wantedExtra + d) / 2
        if (leg <= half) return null
        val offset = sqrt(leg * leg - half * half)
        if (offset > MAX_DETOUR_OFFSET_METERS) return null
        val bearing = if (d < 1.0) 0.0 else Geo.bearingDegrees(from, to)
        val mid = Geo.destinationPoint(from, bearing, half)
        val via = Geo.destinationPoint(mid, (bearing + 90.0) % 360.0, offset)
        val added = addedMs(from, to, via, speed, config)
        return CourseOption(
            id = "detour",
            kind = CourseKind.DETOUR,
            title = "주변으로 돌아서 가기",
            reason = "근처에 고른 경치 지점이 없어 약 ${DriveText.minutes(added)}분 돌아가요",
            via = listOf(Place("돌아가는 지점", via, PlaceKind.WAYPOINT)),
            addedMs = added,
        )
    }

    private fun travelMs(meters: Double, speedMps: Double): Long = (meters / speedMps * 1000.0).toLong()
}
