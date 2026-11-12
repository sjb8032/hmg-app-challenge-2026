package kr.contest.pleosapp.core

/**
 * 드라이브메이트의 시간 모드. 세 번째 모드인 '쉬었다 가요'는 [RestMateEngine]이 맡고,
 * 화면 아래 버튼으로 어느 화면에서나 누를 수 있다.
 */
enum class DriveMode(val title: String, val subtitle: String) {
    /** 약속·픽업·체크인 시각에 정각 도착 */
    ON_TIME("딱 맞춰 가요", "약속·픽업 시각에 정각 도착"),

    /** 바로 가기 아쉬울 때 경치 좋은 길로 조금 더 달리다 도착 */
    LONGER("좀 더 달려요", "경치 좋은 길로 조금 더 달리다 도착"),
}

enum class PlaceKind(val label: String) {
    CURRENT("지금 목적지"),
    HOME("집"),
    OFFICE("회사"),
    BOOKMARK("즐겨찾기"),
    RECENT("최근 목적지"),
    WAYPOINT("경유지"),
}

/** 내비에 넘길 수 있는 장소. POI 값을 알면 같이 넘긴다. */
data class Place(
    val name: String,
    val position: LatLng,
    val kind: PlaceKind = PlaceKind.BOOKMARK,
    val poiId: String = "",
    val poiSubId: String = "",
    val address: String = "",
) {
    /** 이름이 달라도 가까우면 같은 곳으로 본다 (내비가 좌표를 조금 다르게 줄 때가 있음). */
    fun sameSpot(other: Place?): Boolean =
        other != null && Geo.distanceMeters(position, other.position) <= SAME_SPOT_METERS

    companion object {
        const val SAME_SPOT_METERS = 150.0
    }
}

enum class Mood(val label: String) {
    DATE("데이트"),
    SOLO("혼자 쉬기"),
}

enum class RoadStyle(val label: String) {
    LESS_TRAFFIC("덜 막히는 길"),
    SCENIC("경치 좋은 길"),
}

enum class CourseTag(val label: String) {
    RIVER("강변"),
    NIGHT_VIEW("야경"),
    PARK("공원"),
    MOUNTAIN("산길"),
    LAKE("호숫가"),
}

/** 경유지로 넣을 만한 경치 좋은 지점 (팀이 고른 목록) */
data class CoursePoint(
    val id: String,
    val name: String,
    val position: LatLng,
    val tags: Set<CourseTag>,
)

enum class CourseKind {
    /** 경유지 없이 바로 */
    DIRECT,

    /** 경치 좋은 지점을 거쳐 감 */
    SCENIC,

    /** 고른 지점이 없을 때 주변으로 돌아감 */
    DETOUR,

    /** 휴게소·주차장에 들러 쉬다 감 */
    REST_STOP,
}

data class CourseOption(
    val id: String,
    val kind: CourseKind,
    val title: String,
    /** 이 코스를 권하는 이유 (화면에 한 줄) */
    val reason: String,
    val via: List<Place>,
    /** 바로 가는 것보다 더 걸리는 시간 (추정, 쉬는 시간 포함) */
    val addedMs: Long,
    /** 쉬다 가는 코스면 쉬는 시간 */
    val stayMs: Long = 0L,
    /** 쉬다 가는 코스의 쉼터 */
    val restArea: RestArea? = null,
)

enum class DriveStep {
    /** 모드 고르기 */
    HOME,

    /** 목적지·시각·분위기 고르기 */
    SETUP,

    /** 코스 후보 고르기 */
    CHOOSE,

    /** 달리면서 도착 시각 맞추기 */
    ACTIVE,

    /** 도착 */
    ARRIVED,
}

enum class FitStatus { UNKNOWN, ON_TIME, EARLY, LATE }

data class DriveState(
    val now: Long = 0L,
    val step: DriveStep = DriveStep.HOME,
    val mode: DriveMode? = null,
    val position: LatLng? = null,
    /** 내비의 지금 목적지와 남은 시간 (경유지 포함) */
    val naviDestination: Place? = null,
    val naviRemainingMs: Long? = null,
    /** 고를 수 있는 장소 (지금 목적지, 내비 즐겨찾기·최근 목적지) */
    val places: List<Place> = emptyList(),

    // ---- 고른 것 ----
    val destination: Place? = null,
    /** 딱 맞춰 가요: 도착 목표 시각 / 좀 더 달려요: 코스를 고를 때 정해지는 도착 시각 */
    val targetAt: Long? = null,
    val extraMinutes: Int = 30,
    val mood: Mood = Mood.SOLO,
    val style: RoadStyle = RoadStyle.SCENIC,

    // ---- 계획 ----
    /** 바로 가면 걸리는 시간 */
    val directMs: Long? = null,
    /** directMs가 내비 값이 아니라 직선거리로 추정한 값인지 */
    val directEstimated: Boolean = false,
    /** '코스 찾기'를 눌렀는데 바로 가는 시간을 기다리는 중 */
    val planPending: Boolean = false,
    val options: List<CourseOption> = emptyList(),
    val chosen: CourseOption? = null,
    /** 경유지를 지났거나 뺐는지 */
    val viaCleared: Boolean = false,

    // ---- 진행 ----
    val arrivalEstimate: Long? = null,
    val status: FitStatus = FitStatus.UNKNOWN,
    /** 도착 예상 - 목표 (ms). 양수면 늦음 */
    val diffMs: Long? = null,
    /** 일찍 도착할 것 같아 '조금 더 돌기'를 권하는 중 */
    val extendOffered: Boolean = false,
    val message: String? = null,
    /** 쉬었다 가요가 진행 중이라 시간 맞추기를 잠시 멈춤 */
    val paused: Boolean = false,
    val arrivedAt: Long? = null,
) {
    /** 아직 지나지 않은 경유지가 있는지 */
    val viaActive: Boolean get() = !viaCleared && chosen?.via?.isNotEmpty() == true
}

/** 드라이브 엔진이 앱에 요청하는 동작 */
sealed interface DriveEffect {
    /** 바로 가는 시간을 알아봐 달라 (내비 예상 시간 조회) */
    data class AskDirectTime(val destination: Place) : DriveEffect

    /** 목적지와 경유지로 경로를 다시 요청 (경유지가 비면 목적지로 바로) */
    data class Route(val destination: Place, val via: List<Place>) : DriveEffect

    data class Speak(val text: String) : DriveEffect

    /** 차 안 분위기. null이면 원래 설정으로 되돌림 */
    data class CabinMood(val mood: Mood?) : DriveEffect
}

data class DriveConfig(
    /** 도착 시각 앞에 남겨 두는 여유 */
    val marginMs: Long = 3 * MINUTE_MS,
    /** 남는 시간이 이보다 적으면 바로 간다 */
    val minSlackMs: Long = 4 * MINUTE_MS,
    /** 이만큼 늦을 것 같으면 경유지를 뺀다 */
    val lateToleranceMs: Long = 2 * MINUTE_MS,
    /** 이만큼 일찍 도착할 것 같으면 더 돌기를 권한다 */
    val earlyToleranceMs: Long = 6 * MINUTE_MS,
    /** 더 돌기를 권하려면 남은 시간이 이 정도는 있어야 한다 */
    val extendMinRemainMs: Long = 8 * MINUTE_MS,
    /** 남는 시간이 이만큼 넘으면 휴게소·주차장에 들러 쉬다 가는 코스도 낸다 */
    val restStopMinSlackMs: Long = 25 * MINUTE_MS,
    /** 쉬다 가는 코스로 들를 곳까지 돌아가는 시간 상한 */
    val restStopMaxDetourMs: Long = 12 * MINUTE_MS,
    /** 목적지에서 이만큼 가까우면 도착으로 본다 */
    val arriveMeters: Double = 150.0,
    /** 직선거리 → 도로 거리 (도심 기준) */
    val detourFactor: Double = 1.35,
    /** 내비 값이 없을 때 쓰는 평균 속도 (도심) */
    val fallbackSpeedKmh: Double = 30.0,
    /** 바로 가는 시간을 내비에 물은 뒤 이만큼 답이 없으면 직선거리로 추정한다 */
    val directTimeWaitMs: Long = 4 * SECOND_MS,
)
