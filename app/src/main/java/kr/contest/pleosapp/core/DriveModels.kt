package kr.contest.pleosapp.core

/**
 * 드라이브메이트의 시간 모드. 세 번째 모드인 '레스트 모드'는 [RestMateEngine]이 맡고,
 * 화면 아래 버튼으로 어느 화면에서나 누를 수 있다.
 */
enum class DriveMode(val title: String, val subtitle: String) {
    /** 약속·픽업·체크인 시각에 정각 도착 */
    ON_TIME("온타임 모드", "약속·픽업 시각에 정각 도착"),

    /** 바로 가기 아쉬울 때 경치 좋은 길로 조금 더 달리다 도착 */
    LONGER("롱웨이 모드", "경치 좋은 길로 조금 더 달리다 도착"),
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

/** 온타임 모드에서 남는 시간을 어떻게 쓸지 */
enum class OnTimeStyle(val label: String, val detail: String) {
    /** 경치 좋은 지점을 거쳐 돌면서 시간을 맞춘다 */
    LOOP("돌면서 맞추기", "경치 좋은 곳을 거쳐 가요"),

    /** 목적지 가까운 공원·공영 주차장에 세워 기다렸다가 간다 (학원 픽업처럼 앞에 세울 수 없을 때) */
    WAIT_NEAR("근처에서 기다리기", "목적지 가까이 세워 두고 기다려요"),
}

enum class CourseTag(val label: String) {
    RIVER("강변"),
    NIGHT_VIEW("야경"),
    PARK("공원"),
    MOUNTAIN("산길"),
    LAKE("호숫가"),
    SEA("바닷가"),
    BRIDGE("다리"),
}

/** 롱웨이 모드에서 어떤 길을 달리고 싶은지 (코스 후보 화면 위 칩) */
enum class CourseFlavor(val label: String, val tags: Set<CourseTag>) {
    NIGHT_VIEW("야경", setOf(CourseTag.NIGHT_VIEW)),
    RIVER("강변", setOf(CourseTag.RIVER)),
    BRIDGE("다리", setOf(CourseTag.BRIDGE)),
    PARK("공원", setOf(CourseTag.PARK, CourseTag.LAKE)),

    /** 차 많은 도심(시청·강남역 근처)을 피하고 강변·산길·호숫가·바닷가로 */
    AVOID_CITY("도심 피하기", setOf(CourseTag.RIVER, CourseTag.MOUNTAIN, CourseTag.LAKE, CourseTag.SEA)),
    ;

    companion object {
        /** 시각과 분위기로 기본값을 정한다: 밤이면 야경, 낮 데이트면 강변, 낮에 혼자면 도심 피하기 */
        fun auto(hourOfDay: Int, mood: Mood): CourseFlavor = when {
            hourOfDay >= 18 || hourOfDay < 6 -> NIGHT_VIEW
            mood == Mood.DATE -> RIVER
            else -> AVOID_CITY
        }
    }
}

/** 코스 후보를 고를 때 바라는 것 */
data class CourseWish(
    /** 이 태그가 붙은 지점을 앞에 둔다 (롱웨이 모드의 야경·강변·다리·공원) */
    val prefer: Set<CourseTag> = emptySet(),
    /** 도심(시청·강남역 3km 안) 지점을 뒤로 미룬다 */
    val avoidCity: Boolean = false,
    /** 온타임 모드: 짧은 길(45분 미만)이면 목적지 쪽 반경 안의 지점만 본다 (반대쪽으로 멀리 돌지 않게) */
    val onTime: Boolean = false,
)

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
    /** 경치 지점의 특징 (고른 분위기와 맞는지 볼 때 쓴다) */
    val tags: Set<CourseTag> = emptySet(),
    /** 쉬다 가는 코스에서 어떻게 쉬는지 (기본: 그냥 기다리기) */
    val restKind: RestKind? = null,
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
    /** 온타임 모드: 도착 목표 시각 / 롱웨이 모드: 코스를 고를 때 정해지는 도착 시각 */
    val targetAt: Long? = null,
    val extraMinutes: Int = 30,
    val mood: Mood = Mood.SOLO,
    /** 온타임 모드: 돌면서 맞추기 / 근처에서 기다리기 */
    val onTimeStyle: OnTimeStyle = OnTimeStyle.LOOP,
    /** 롱웨이 모드: 야경·강변·다리·공원·도심 피하기 */
    val flavor: CourseFlavor? = null,

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
    /** '조금 더 돌기'를 누르면 거쳐 갈 코스 (권할 때 미리 골라 둔다) */
    val extendOption: CourseOption? = null,
    val message: String? = null,
    /** [message]를 띄운 시각. 달리는 중에는 조금 지나면 지운다 */
    val messageAt: Long? = null,
    /** 레스트 모드가 진행 중이라 시간 맞추기를 잠시 멈춤 */
    val paused: Boolean = false,
    val arrivedAt: Long? = null,
) {
    /** 아직 지나지 않은 경유지가 있는지 */
    val viaActive: Boolean get() = !viaCleared && chosen?.via?.isNotEmpty() == true
}

/** 지금 설정(설정·코스 고르기 화면)을 자주 쓰는 설정으로 저장할 수 있으면 그 내용 */
fun DriveState.toSavedTrip(): SavedTrip? {
    if (step != DriveStep.SETUP && step != DriveStep.CHOOSE) return null
    val m = mode ?: return null
    val dest = destination ?: return null
    // '지금 목적지'는 다음에 쓸 때 지금이 아니므로 최근 목적지로 둔다.
    val place = if (dest.kind == PlaceKind.CURRENT) dest.copy(kind = PlaceKind.RECENT) else dest
    return when (m) {
        DriveMode.ON_TIME -> SavedTrip(
            mode = m,
            place = place,
            minuteOfDay = targetAt?.let { DriveText.minuteOfDay(it) },
            onTimeStyle = onTimeStyle,
        )
        DriveMode.LONGER -> SavedTrip(
            mode = m,
            place = place,
            extraMinutes = extraMinutes,
            mood = mood,
            flavor = flavor,
        )
    }
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
    /** 온타임 모드에서 경치·우회 코스로 더 쓰는 시간 상한 (넘으면 쉬다 가기를 권함) */
    val maxOnTimeCourseMs: Long = 35 * MINUTE_MS,
    /** 목적지에서 이만큼 가까우면 도착으로 본다 */
    val arriveMeters: Double = 150.0,
    /** 직선거리 → 도로 거리 (도심 기준) */
    val detourFactor: Double = 1.35,
    /** 내비 값이 없을 때 쓰는 평균 속도 (도심) */
    val fallbackSpeedKmh: Double = 30.0,
    /** 바로 가는 시간을 내비에 물은 뒤 이만큼 답이 없으면 직선거리로 추정한다 */
    val directTimeWaitMs: Long = 4 * SECOND_MS,
    /** 늦음이 이만큼 이어져야 경유지를 바꾼다 (잠깐 막힌 것에 흔들리지 않게) */
    val lateObserveMs: Long = 90 * SECOND_MS,
    /** 롱웨이 모드는 이만큼(또는 더 달리기로 한 시간의 1/3) 넘게 늦어질 때만 코스를 줄인다 */
    val longerLateToleranceMs: Long = 10 * MINUTE_MS,
    /** 경로를 바꾼 뒤 이만큼은 '조금 더 돌기'를 권하지 않는다 */
    val extendQuietMs: Long = 10 * MINUTE_MS,
    /** 달리는 중 안내 문구를 화면에 두는 시간 */
    val messageTtlMs: Long = 2 * MINUTE_MS,
)

/**
 * 자주 쓰는 설정 (학원 픽업 4:50, 집 +30분 같은). 처음 화면 칩으로 한 번에 다시 쓴다.
 * 휴대폰 없이 차 안에서만 저장한다.
 */
data class SavedTrip(
    val mode: DriveMode,
    val place: Place,
    /** 온타임 모드: 도착 목표 (자정부터 분) */
    val minuteOfDay: Int? = null,
    /** 롱웨이 모드: 더 달릴 시간 */
    val extraMinutes: Int? = null,
    val mood: Mood? = null,
    val onTimeStyle: OnTimeStyle? = null,
    val flavor: CourseFlavor? = null,
) {
    /** 칩 아랫줄: "온타임 · 오후 4:50 · 근처에서 기다리기", "롱웨이 · +30분 · 데이트" */
    val detail: String
        get() = listOfNotNull(
            if (mode == DriveMode.ON_TIME) "온타임" else "롱웨이",
            minuteOfDay?.let { DriveText.clockOfDay(it) },
            extraMinutes?.let { "+${it}분" },
            onTimeStyle?.takeIf { it == OnTimeStyle.WAIT_NEAR }?.label,
            mood?.takeIf { mode == DriveMode.LONGER }?.label,
        ).joinToString(" · ")

    /** 같은 곳·같은 시각이면 같은 설정으로 본다 (다시 저장하면 바꿔 쓴다) */
    fun sameAs(other: SavedTrip): Boolean =
        mode == other.mode && place.sameSpot(other.place) && minuteOfDay == other.minuteOfDay && extraMinutes == other.extraMinutes

    /** 한 줄 글자로 (탭으로 나눔). 장소 이름의 탭·줄바꿈은 빈칸으로 바꾼다 */
    fun encode(): String = listOf(
        VERSION,
        mode.name,
        clean(place.name),
        place.position.lat.toString(),
        place.position.lon.toString(),
        place.kind.name,
        clean(place.poiId),
        clean(place.poiSubId),
        clean(place.address),
        minuteOfDay?.toString().orEmpty(),
        extraMinutes?.toString().orEmpty(),
        mood?.name.orEmpty(),
        onTimeStyle?.name.orEmpty(),
        flavor?.name.orEmpty(),
    ).joinToString("\t")

    companion object {
        const val MAX = 4
        private const val VERSION = "v1"

        private fun clean(text: String) = text.replace('\t', ' ').replace('\n', ' ')

        fun decode(line: String): SavedTrip? {
            val f = line.split('\t')
            if (f.size < 14 || f[0] != VERSION) return null
            val mode = DriveMode.entries.firstOrNull { it.name == f[1] } ?: return null
            val lat = f[3].toDoubleOrNull() ?: return null
            val lon = f[4].toDoubleOrNull() ?: return null
            if (f[2].isBlank()) return null
            val place = Place(
                name = f[2],
                position = LatLng(lat, lon),
                kind = PlaceKind.entries.firstOrNull { it.name == f[5] } ?: PlaceKind.BOOKMARK,
                poiId = f[6],
                poiSubId = f[7],
                address = f[8],
            )
            return SavedTrip(
                mode = mode,
                place = place,
                minuteOfDay = f[9].toIntOrNull()?.takeIf { it in 0 until 24 * 60 },
                extraMinutes = f[10].toIntOrNull(),
                mood = Mood.entries.firstOrNull { it.name == f[11] },
                onTimeStyle = OnTimeStyle.entries.firstOrNull { it.name == f[12] },
                flavor = CourseFlavor.entries.firstOrNull { it.name == f[13] },
            )
        }

        fun encodeAll(trips: List<SavedTrip>): String = trips.joinToString("\n") { it.encode() }

        fun decodeAll(text: String?): List<SavedTrip> =
            text.orEmpty().lines().filter { it.isNotBlank() }.mapNotNull(::decode).take(MAX)

        /** 새 설정을 맨 앞에 넣는다. 같은 설정은 바꿔 쓰고, [MAX]개를 넘으면 오래된 것부터 뺀다 */
        fun added(trips: List<SavedTrip>, trip: SavedTrip): List<SavedTrip> =
            (listOf(trip) + trips.filterNot { it.sameAs(trip) }).take(MAX)
    }
}
