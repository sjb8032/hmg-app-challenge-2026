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
    /** 롱웨이 모드에서 MIN_FILL로 고를 지점이 없을 때 (더 달릴 시간을 늘렸더니 후보가 사라지지 않게) */
    private const val LOOSE_MIN_FILL = 0.15
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

    /**
     * 먼 길은 남는 시간이 이만큼만 돼도 휴게소에서 쉬다 가는 코스를 낸다
     * (두 시간 운전에 10분 쉬기는 안전에도 좋고, 목적지 앞에서 15분 기다리는 것보다 낫다)
     */
    private const val LONG_TRIP_REST_MIN_SLACK_MS = 10 * MINUTE_MS

    /** 먼 길: 길의 30%를 지난 곳부터 목적지 조금 너머까지(온타임 모드는 목적지까지), 옆으로는 max(10km, 길이의 12%) 안 */
    private const val AXIS_MIN_PROGRESS = 0.3
    private const val AXIS_MAX_PROGRESS = 1.1
    private const val ON_TIME_AXIS_MAX_PROGRESS = 1.0
    private const val AXIS_MIN_LATERAL_METERS = 10_000.0
    private const val AXIS_LATERAL_RATIO = 0.12

    /** 온타임 모드 짧은 길: 목적지에서 max(출발지까지 거리, 5km) 안의 지점만 (반대쪽으로 멀리 돌지 않게) */
    private const val ON_TIME_MIN_RADIUS_METERS = 5_000.0

    /** 근처에서 기다리기: 목적지에서 2km 안 (없으면 3km 안) */
    private const val WAIT_NEAR_METERS = 2_000.0
    private const val WAIT_NEAR_MAX_METERS = 3_000.0

    /** 기다리는 코스는 이만큼은 기다릴 수 있어야 낸다 (1분 기다리려고 돌아가지 않게) */
    private const val MIN_WAIT_STAY_MS = 5 * MINUTE_MS

    /** 먼 길의 두 번째 쉬다 가는 곳은 첫 곳과 길 위 위치가 이만큼 달라야 한다 */
    private const val DISTINCT_PROGRESS = 0.15

    /** '도심 피하기'에서 피하는 곳: 서울시청 3.5km·강남역 3km 안, 그리고 시청 2.5km 안을 지나는 길 */
    private val CITY_HALL = LatLng(37.5663, 126.9779)
    private val GANGNAM = LatLng(37.4979, 127.0276)
    private const val CITY_HALL_METERS = 3_500.0
    private const val GANGNAM_METERS = 3_000.0
    private const val CITY_HALL_PASS_METERS = 2_500.0

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
     * - 남는 시간이 [maxAddedMs]보다 길면: 쉬다 가는 곳이 있으면 그것을 앞에 두고 경치 코스는 하나만 곁들이며
     *   (오래 돌기보다 쉬기), 없으면 [maxAddedMs]만큼만 채우는 경치 코스를 낸다.
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
        val restMinSlack = if (directMs >= LONG_TRIP_MS) LONG_TRIP_REST_MIN_SLACK_MS else config.restStopMinSlackMs
        val rest = if (slackMs >= restMinSlack) {
            restStopOptions(from, to, directMs, slackMs, speed, restPlaces, config)
        } else {
            emptyList()
        }
        val driveSlack = minOf(slackMs, maxAddedMs)
        val candidates = candidatePoints(from, to, directMs, points, wish)
        var scenic = scenicOptions(from, to, driveSlack, speed, candidates, wish, config, MIN_FILL)
        // 롱웨이 모드: 더 달릴 시간을 늘렸다고 후보가 사라지지 않게 덜 채우는 지점도 본다.
        if (scenic.isEmpty() && !wish.onTime) scenic = scenicOptions(from, to, driveSlack, speed, candidates, wish, config, LOOSE_MIN_FILL)
        // 남는 시간이 길면 쉬다 가는 곳을 앞에 두고, 경치 코스도 하나는 같이 보여 준다.
        if (slackMs > maxAddedMs && rest.isNotEmpty()) return (rest + scenic.take(1)).take(MAX_OPTIONS)
        val detour = if (scenic.isEmpty()) detourOption(from, to, driveSlack, speed, config) else null
        return (scenic + rest + listOfNotNull(detour)).take(MAX_OPTIONS).ifEmpty { listOf(direct(slackMs)) }
    }

    /** 목적지 근처(3km 안)에 기다릴 곳이 있는지 ('근처에서 기다리기' 후보가 없을 때 이유를 알리려고) */
    fun hasWaitPlaceNear(destination: Place, restPlaces: List<RestArea>): Boolean =
        restPlaces.any { Geo.distanceMeters(it.position, destination.position) <= WAIT_NEAR_MAX_METERS }

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
            .filter { (_, detour) -> slackMs - detour >= MIN_WAIT_STAY_MS }
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
                    "약 ${DriveText.duration(stay)} 기다려요",
                via = listOf(Place(area.name, area.position, PlaceKind.WAYPOINT)),
                addedMs = slackMs,
                stayMs = stay,
                restArea = area,
                restKind = RestKind.WAIT,
            )
        }
    }

    /**
     * 바로 가는 코스. 이유는 목표 시각 기준으로 적는다 (여유를 못 지키는 것과 늦는 것은 다르다).
     * @param slackMs 남는 시간 (여유를 뺀 값)
     * @param arriveAt 바로 가면 도착하는 시각. 주면 "바로 가면 오후 6:45 도착 · 15분 일찍"처럼 적는다
     */
    fun direct(slackMs: Long, arriveAt: Long? = null, marginMs: Long = 0L): CourseOption {
        // 목표보다 얼마나 일찍 닿는지 (음수면 늦음)
        val early = slackMs + marginMs
        // 도착 시각은 카드에 따로 나오므로 여기서는 목표와의 차이만 (계획한 여유 + 2분 안이면 '여유')
        val reason = when {
            early < -30 * SECOND_MS -> "바로 가도 약 ${DriveText.duration(early)} 늦어요"
            arriveAt == null -> "바로 가도 시간에 맞아요"
            early <= 30 * SECOND_MS -> "바로 가면 정각"
            early <= marginMs + 2 * MINUTE_MS -> "바로 가면 ${DriveText.duration(early)} 여유"
            else -> "바로 가면 ${DriveText.duration(early)} 일찍"
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
        // 온타임 모드는 목적지를 지나쳤다 되돌아오는 지점을 뺀다 (체크인 시각을 맞추려고 숙소를 지나가지 않게).
        val maxProgress = if (wish.onTime) ON_TIME_AXIS_MAX_PROGRESS else AXIS_MAX_PROGRESS
        return when {
            directMs >= LONG_TRIP_MS -> points.filter { onAxis(from, to, total, it.position, maxProgress) }
            wish.onTime -> {
                val radius = max(total, ON_TIME_MIN_RADIUS_METERS)
                points.filter { Geo.distanceMeters(it.position, to) <= radius }
            }
            else -> points
        }
    }

    private fun onAxis(from: LatLng, to: LatLng, total: Double, p: LatLng, maxProgress: Double): Boolean {
        if (total < 1.0) return false
        val (along, across) = Geo.alongAcross(from, to, p)
        val progress = along / total
        return progress in AXIS_MIN_PROGRESS..maxProgress &&
            abs(across) <= max(AXIS_MIN_LATERAL_METERS, total * AXIS_LATERAL_RATIO)
    }

    /** 코스의 경유지가 모두 도심을 피하는지 ('도심 피하기'로 달리는 중 시간을 더할 때) */
    fun avoidsCity(from: LatLng, option: CourseOption, to: LatLng): Boolean =
        option.via.none { throughCity(from, it.position, to) }

    /**
     * 도심(시청·강남역 가까이)이거나, 가는 길이 시청 가까이를 지나는지 ('도심 피하기').
     * 출발지나 목적지가 도심이면 그쪽 구간은 어차피 도심을 지나므로 따지지 않는다.
     */
    private fun throughCity(from: LatLng, p: LatLng, to: LatLng): Boolean {
        if (Geo.distanceMeters(CITY_HALL, p) <= CITY_HALL_METERS || Geo.distanceMeters(GANGNAM, p) <= GANGNAM_METERS) return true
        val fromDowntown = Geo.distanceMeters(CITY_HALL, from) <= CITY_HALL_METERS
        val toDowntown = Geo.distanceMeters(CITY_HALL, to) <= CITY_HALL_METERS
        return (!fromDowntown && segmentDistance(from, p, CITY_HALL) <= CITY_HALL_PASS_METERS) ||
            (!toDowntown && segmentDistance(p, to, CITY_HALL) <= CITY_HALL_PASS_METERS)
    }

    /** 점과 a→b 선분 사이 거리 (m, 근사) */
    private fun segmentDistance(a: LatLng, b: LatLng, point: LatLng): Double {
        val length = Geo.distanceMeters(a, b)
        if (length < 1.0) return Geo.distanceMeters(a, point)
        val (along, across) = Geo.alongAcross(a, b, point)
        return when {
            along <= 0.0 -> Geo.distanceMeters(a, point)
            along >= length -> Geo.distanceMeters(b, point)
            else -> abs(across)
        }
    }

    private fun scenicOptions(
        from: LatLng,
        to: LatLng,
        slackMs: Long,
        speed: Double,
        points: List<CoursePoint>,
        wish: CourseWish,
        config: DriveConfig,
        minFill: Double,
    ): List<CourseOption> {
        data class Scored(val point: CoursePoint, val added: Long, val score: Double)

        val scored = points.mapNotNull { p ->
            if (Geo.distanceMeters(from, p.position) < NEAR_METERS || Geo.distanceMeters(to, p.position) < NEAR_METERS) {
                return@mapNotNull null
            }
            val added = addedMs(from, to, p.position, speed, config)
            if (added > slackMs || added < slackMs * minFill) return@mapNotNull null
            var bonus = if (p.tags.any { it in SCENIC_TAGS }) SCENIC_BONUS else 0.0
            if (p.tags.any { it in wish.prefer }) bonus += PREFER_BONUS
            if (wish.avoidCity && throughCity(from, p.position, to)) bonus -= CITY_PENALTY
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
     * 가는 길에 들를 수 있는 쉼터에서 남는 시간만큼 쉬다 간다. 최대 2곳 (시내는 종류가 다른 곳, 먼 길은 길 위 위치가 다른 곳).
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
        data class Scored(val area: RestArea, val detour: Long, val score: Double, val progress: Double)

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
                Scored(area, detour, detour + abs(progress - idealProgress) * progressWeightMs, progress)
            }
            .sortedBy { it.score }
            .toList()
        val first = ranked.firstOrNull() ?: return emptyList()
        val second = ranked.drop(1).firstOrNull {
            val apart = Geo.distanceMeters(it.area.position, first.area.position) >= DISTINCT_METERS
            // 먼 길은 모두 휴게소라 종류 대신 길 위 위치가 다른 곳을 둘째로 (예: 길의 40%·75%)
            apart && if (longTrip) abs(it.progress - first.progress) >= DISTINCT_PROGRESS else it.area.type != first.area.type
        }
        return listOfNotNull(first, second).map { s ->
            val stay = slackMs - s.detour
            val city = RestAreaFinder.isCityPlace(s.area)
            CourseOption(
                id = "rest-${s.area.id}",
                kind = CourseKind.REST_STOP,
                // 시내 공원·주차장은 '근처에서 기다리기'와 같은 이름으로 (같은 곳을 두 이름으로 부르지 않게)
                title = "${RestMateText.spokenName(s.area.name)}에서 ${if (city) "기다리기" else "쉬다 가기"}",
                reason = "${s.area.type.label} · 약 ${DriveText.duration(stay)} ${if (city) "기다렸다" else "쉬고"} 시간 맞춰 출발",
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
            reason = "근처에 들를 만한 지점이 없어 약 ${DriveText.minutes(added)}분 돌아가요",
            via = listOf(Place("돌아가는 지점", via, PlaceKind.WAYPOINT)),
            addedMs = added,
        )
    }

    /**
     * '자리 없음': [center]에서 한 바퀴 돌아 [loopMs] 안팎에 다시 오도록 거쳐 갈 지점.
     * 진행 방향에서 오른쪽으로 60° 튼 쪽(우회전으로 블록을 도는 쪽)에, 왕복 도로 거리가 [loopMs]에 맞는 거리(250~900m)로 잡는다.
     * 실제 길은 내비가 이 지점 가까운 도로로 잇는다.
     */
    fun circlePoint(center: LatLng, headingDeg: Double, loopMs: Long, speedMps: Double, config: DriveConfig): LatLng {
        val road = loopMs / 1000.0 * speedMps
        val radius = (road / 2.0 / config.detourFactor).coerceIn(CIRCLE_MIN_METERS, CIRCLE_MAX_METERS)
        return Geo.destinationPoint(center, ((headingDeg + CIRCLE_TURN_DEG) % 360.0 + 360.0) % 360.0, radius)
    }

    private const val CIRCLE_MIN_METERS = 250.0
    private const val CIRCLE_MAX_METERS = 900.0
    private const val CIRCLE_TURN_DEG = 60.0

    private fun travelMs(meters: Double, speedMps: Double): Long = (meters / speedMps * 1000.0).toLong()
}
