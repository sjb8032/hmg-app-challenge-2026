package kr.contest.pleosapp.core

/** PT 시연용 출발 상황 */
data class DemoTrip(
    val id: String,
    val title: String,
    val start: LatLng,
    /** 출발할 때 내비에 이미 걸려 있는 목적지 */
    val destination: Place,
    /** 내비 즐겨찾기·최근 목적지 표본 */
    val places: List<Place>,
    /** 평균 속도 (km/h). 남은 시간과 화면 속도에 쓴다 */
    val speedKmh: Double,
    /** 도로가 직선보다 얼마나 돌아가는지 */
    val detourFactor: Double,
    val expressway: Boolean,
    /** 가상 시계 배속 (1초에 몇 초가 흐르는지) */
    val timeScale: Double,
    /** 가상 시계 시작 시각 (그날 자정부터 ms). null이면 지금 시각 */
    val startTimeOfDayMs: Long? = null,
    /** 픽업 알림을 받을 표본 연락처 (실제로는 연결한 휴대폰의 연락처에서 고른다) */
    val pickupContact: String = "아이",
)

sealed interface DemoNaviEvent {
    data class WaypointArrived(val place: Place) : DemoNaviEvent
    data class DestinationArrived(val place: Place) : DemoNaviEvent
}

/**
 * 시연용 모의 내비: 경로를 직선 구간으로 잇고, 가상 시계에 맞춰 차를 옮긴다. 안드로이드에 의존하지 않는다.
 *
 * 남은 시간 = 남은 직선거리 × 우회 계수 ÷ 평균 속도 (+ 정체로 늦어지는 시간).
 * 차는 직선 위를 (평균 속도 ÷ 우회 계수)로 움직여, 남은 시간이 실제 흐른 시간만큼 줄어든다.
 */
class DemoNavi(val trip: DemoTrip) {
    var position: LatLng = trip.start
        private set
    var headingDeg: Double = Geo.bearingDegrees(trip.start, trip.destination.position)
        private set
    var destination: Place? = trip.destination
        private set
    var via: List<Place> = emptyList()
        private set

    /**
     * 지금 곧게 달리는 구간의 시작점: 경로를 받은 곳, 또는 방금 지난 경유지.
     * 화면에서는 이 구간을 진행한 비율만큼 도로 모양을 따라간 자리에 차를 그린다.
     */
    var legStart: LatLng = trip.start
        private set

    /** 지금 향하는 곳 (다음 경유지, 없으면 목적지) */
    val target: Place? get() = nextTarget()

    /** D단으로 달리는 중인지 (앱이 정한다) */
    var moving = false

    /** 남은 정체 시간: 이 시간 동안은 [JAM_FACTOR]배 속도로 간다 */
    private var jamLeftMs = 0L

    val jammed: Boolean get() = jamLeftMs > 0

    /** 화면에 보여 줄 속도 */
    val speedKmh: Double
        get() = when {
            !moving -> 0.0
            jamLeftMs > 0 -> trip.speedKmh * JAM_FACTOR
            else -> trip.speedKmh
        }

    fun routeTo(dest: Place, via: List<Place>) {
        destination = dest
        this.via = via.take(2)
        legStart = position
        nextTarget()?.let { headingDeg = Geo.bearingDegrees(position, it.position) }
    }

    fun cancel() {
        destination = null
        via = emptyList()
        legStart = position
    }

    /** 목적지까지 남은 시간 (경유지 포함). 목적지가 없으면 null */
    fun remainingMs(): Long? {
        val dest = destination ?: return null
        var meters = 0.0
        var from = position
        for (p in via + dest) {
            meters += Geo.distanceMeters(from, p.position)
            from = p.position
        }
        return travelMs(meters) + jamExtraMs()
    }

    /** 지금 위치에서 그곳까지 바로 가면 걸리는 시간 */
    fun tripTimeMs(place: Place): Long = travelMs(Geo.distanceMeters(position, place.position))

    /** 앞에 정체가 생겨 extraMs만큼 늦어진다. */
    fun jam(extraMs: Long) {
        jamLeftMs += (extraMs / (1.0 - JAM_FACTOR)).toLong()
    }

    /** dtMs만큼 차를 옮긴다. 경유지·목적지에 닿으면 사건을 돌려주고, 목적지에서는 멈춘다. */
    fun advance(dtMs: Long): List<DemoNaviEvent> {
        if (!moving || dtMs <= 0) return emptyList()
        val events = ArrayList<DemoNaviEvent>()
        val jamPart = minOf(dtMs, jamLeftMs)
        jamLeftMs -= jamPart
        val straightMps = trip.speedKmh / 3.6 / trip.detourFactor
        var meters = straightMps * ((dtMs - jamPart) + jamPart * JAM_FACTOR) / 1000.0
        while (meters > 0.0) {
            val target = nextTarget()
            if (target == null) {
                position = Geo.destinationPoint(position, headingDeg, meters)
                break
            }
            val d = Geo.distanceMeters(position, target.position)
            if (d > meters) {
                headingDeg = Geo.bearingDegrees(position, target.position)
                position = Geo.destinationPoint(position, headingDeg, meters)
                break
            }
            meters -= d
            position = target.position
            legStart = position
            if (via.isNotEmpty()) {
                via = via.drop(1)
                events += DemoNaviEvent.WaypointArrived(target)
                // 경유지(쉼터)에서 세울지는 앱이 정한다. 사건이 나면 이번 걸음은 여기서 멈춘다.
                break
            } else {
                destination = null
                moving = false
                jamLeftMs = 0L
                events += DemoNaviEvent.DestinationArrived(target)
                break
            }
        }
        return events
    }

    private fun nextTarget(): Place? = via.firstOrNull() ?: destination

    private fun travelMs(meters: Double): Long =
        (meters * trip.detourFactor / (trip.speedKmh / 3.6) * 1000.0).toLong()

    private fun jamExtraMs(): Long = (jamLeftMs * (1.0 - JAM_FACTOR)).toLong()

    companion object {
        /** 정체 중 속도 비율 */
        const val JAM_FACTOR = 0.25
    }
}

object DemoTrips {
    private val HOME = Place("집", LatLng(37.5145, 127.1059), PlaceKind.HOME)
    private val OFFICE = Place("회사", LatLng(37.5219, 126.9245), PlaceKind.OFFICE)
    private val ACADEMY = Place("아이 학원", LatLng(37.4946, 127.0576), PlaceKind.BOOKMARK)
    private val PLACES = listOf(HOME, OFFICE, ACADEMY)

    /**
     * 시내: 논현역에서 광화문 약속 장소로 (약 20분 거리). 퇴근길 저녁 약속 이야기라 가상 시계를 오후 6시 30분에 시작한다
     * (언제 시연해도 같은 시각·같은 코스 후보가 나오게).
     */
    val CITY = DemoTrip(
        id = "city",
        title = "시내 · 논현역 → 광화문",
        start = LatLng(37.5110, 127.0214),
        destination = Place("광화문 약속 장소", LatLng(37.5717, 126.9768), PlaceKind.CURRENT),
        places = PLACES,
        speedKmh = 32.0,
        detourFactor = 1.35,
        expressway = false,
        timeScale = 12.0,
        startTimeOfDayMs = 18 * HOUR_MS + 30 * MINUTE_MS,
    )

    /** 고속도로: 영동고속도로로 강릉 숙소까지 (체크인 15시 이야기, 가상 시계 오전 11시 40분 시작) */
    val HIGHWAY = DemoTrip(
        id = "highway",
        title = "고속도로 · 용인 → 강릉",
        start = LatLng(37.2400, 127.1500),
        destination = Place("강릉 숙소", LatLng(37.7700, 128.9000), PlaceKind.CURRENT),
        places = PLACES,
        speedKmh = 95.0,
        detourFactor = 1.2,
        expressway = true,
        timeScale = 30.0,
        startTimeOfDayMs = 11 * HOUR_MS + 40 * MINUTE_MS,
    )

    val ALL = listOf(CITY, HIGHWAY)
}
