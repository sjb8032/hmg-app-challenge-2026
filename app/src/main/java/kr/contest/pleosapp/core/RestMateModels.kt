package kr.contest.pleosapp.core

const val SECOND_MS = 1_000L
const val MINUTE_MS = 60 * SECOND_MS
const val HOUR_MS = 60 * MINUTE_MS

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

enum class Phase {
    /** 평소 주행 */
    DRIVING,
    /** 쉼터로 이동 중 */
    TO_REST_AREA,
    /** 쉼터에서 휴식 중 */
    RESTING,
}

enum class CheckInAnswer { OK, MORE_DROWSY, QUIET }

/** 차량 경고등에서 읽은 사건. 휴식 전후 주행 비교와 쉼터 안내에 쓴다. */
enum class VehicleEvent {
    /** 운전자 주의 경고 (차가 졸음·부주의를 감지해 휴식을 권함) */
    DRIVER_ATTENTION,
    /** 차로 유지 보조·비상 차로 유지 개입 */
    LANE_ASSIST,
    /** 전방 충돌 경고 */
    COLLISION_WARNING,
}

/** 차 실내 환경 전환 단계 */
enum class CabinMode {
    /** 쉬기 좋게: 무드등 은은하게, 원하는 온도, 약한 바람 */
    REST,
    /** 깨우기: 무드등 점점 밝게, 시원한 바깥 바람 */
    WAKE,
    /** 출발: 휴식 전 설정으로 되돌림 */
    RESTORE,
}

/** 롱웨이 모드의 차 안 분위기 (무드등 밝기·온도) */
data class MoodPreset(
    val label: String,
    val temperatureC: Float,
    /** 무드등 밝기 (0..1) */
    val brightness: Double,
    /** 바람 세기 (0..1) */
    val fan: Double,
) {
    companion object {
        fun of(mood: Mood): MoodPreset = when (mood) {
            Mood.DATE -> MoodPreset("데이트", temperatureC = 22f, brightness = 0.35, fan = 0.3)
            Mood.SOLO -> MoodPreset("혼자 쉬기", temperatureC = 23f, brightness = 0.2, fan = 0.25)
        }
    }
}

/** 사용자가 고르는 휴식 환경 */
data class RestEnvironment(
    /** 휴식을 시작하면 차 실내 환경을 바꿀지 */
    val enabled: Boolean = true,
    val restTemperatureC: Float = 23f,
    /** 무드등을 은은하게 켤지 */
    val dimLight: Boolean = true,
    /** 쉬는 동안 바깥 공기를 들일지 (내기 순환을 끔) */
    val freshAir: Boolean = true,
)

sealed interface Prompt {
    /** 연속 주행이 길 때 휴식을 한 번 묻는다. */
    data class LongDrive(val drivenMinutes: Int, val next: RestAreaCandidate?) : Prompt

    /** 앞 쉼터를 지나면 다음 쉼터까지 오래 걸릴 때. gapMinutes가 null이면 검색 범위 안에 다음 쉼터가 없음 */
    data class RestGapAhead(val next: RestAreaCandidate, val gapMinutes: Int?) : Prompt

    /** 안내 없이 P단 주차했을 때 휴식인지 묻는다. */
    data object ParkedHere : Prompt

    /** 쉼터로 이동 중 상태 확인 */
    data object CheckIn : Prompt
}

/** 엔진이 앱에 요청하는 동작 (내비 안내, 음성, 알람) */
sealed interface Effect {
    data class StartGuidance(val area: RestArea) : Effect
    data object CancelGuidance : Effect
    data class Speak(val text: String) : Effect
    data class Alarm(val on: Boolean) : Effect
    data class Cabin(val mode: CabinMode) : Effect
}

data class RestSession(
    val startedAt: Long,
    val plannedMinutes: Int,
    val alarmAt: Long,
    val alarmRinging: Boolean = false,
    /** 알람을 끈 뒤에는 연장하기 전까지 다시 울리지 않는다. */
    val alarmDone: Boolean = false,
    val area: RestArea? = null,
    /** 휴식 시작 시점에 목적지까지 남은 경로 시간 (ms). 모르면 null */
    val remainingRouteMs: Long? = null,
    /** remainingRouteMs가 내비 값이 아니라 직선거리로 추정한 값인지 */
    val remainingRouteEstimated: Boolean = false,
    /** 휴식 중 P단을 한 번이라도 확인했는지. 확인한 뒤에만 D·R단을 출발로 본다 */
    val sawPark: Boolean = false,
    /** 휴식 중 한 번이라도 멈췄는지. 멈춘 뒤에만 속도로 출발을 판단한다 ('주차했어요'를 조금 일찍 눌러도 휴식이 바로 끝나지 않게) */
    val sawStill: Boolean = false,
    /** 깨우기를 시작한 시각 (알람이 울렸거나 '일어났어요'를 누름). null이면 아직 쉬는 중 */
    val wakeStartedAt: Long? = null,
)

data class RecoveryCheck(
    val restStartedAt: Long,
    val departedAt: Long,
    /** null이면 아직 비교 중 */
    val result: StabilityComparison? = null,
    /** 결과가 불안정일 때 권하는 다음 쉼터 */
    val nextRest: RestAreaCandidate? = null,
)

data class RestMateState(
    val now: Long = 0L,
    val phase: Phase = Phase.DRIVING,
    val continuousDrivingMs: Long = 0L,
    val gear: Gear = Gear.UNKNOWN,
    val speedKmh: Double? = null,
    val position: LatLng? = null,
    val headingDeg: Double? = null,
    /** 고속도로 주행 중인지 (내비 제한속도로 판단). 모르면 null */
    val onExpressway: Boolean? = null,
    val destination: LatLng? = null,
    /** 내비가 알려 준 목적지까지 남은 시간 (ms) */
    val remainingRouteMs: Long? = null,
    val target: RestAreaCandidate? = null,
    val targetRemainingMeters: Double? = null,
    val alternatives: List<RestAreaCandidate> = emptyList(),
    /** 마지막 쉼터 검색이 실패했는지와 그 이유 */
    val searchFailure: String? = null,
    /** 주행 화면에 보여 줄 앞쪽 가장 가까운 쉼터 */
    val nextAhead: RestAreaCandidate? = null,
    val prompt: Prompt? = null,
    val quiet: Boolean = false,
    val rest: RestSession? = null,
    val recovery: RecoveryCheck? = null,
)

data class RestMateConfig(
    /** 이만큼 연속 주행하면 휴식을 묻는다 (한국도로공사 권고: 2시간) */
    val longDriveMs: Long = 2 * HOUR_MS,
    val longDriveSnoozeMs: Long = 30 * MINUTE_MS,
    /** 휴식 공백 안내는 이만큼 운전한 뒤부터 */
    val gapAdviceMinDriveMs: Long = 90 * MINUTE_MS,
    val gapAdviceMaxEtaMinutes: Double = 10.0,
    val gapAdviceMinGapMinutes: Double = 30.0,
    val aheadSearchIntervalMs: Long = 30 * SECOND_MS,
    /** P단·정지가 이만큼 이어지면 주차로 본다. 신호 대기와 구분하기 위함 (시험값) */
    val parkConfirmMs: Long = 10 * SECOND_MS,
    /** 이만큼 쉬면 연속 주행 시간을 0으로 돌린다 */
    val restResetMs: Long = 15 * MINUTE_MS,
    /** 쉼터로 이동 중 상태 확인 간격 (시험값 2~3분) */
    val checkInIntervalMs: Long = 150 * SECOND_MS,
    /** 쉼터가 이만큼 가까우면 진입 안내에 집중하도록 조용히 한다 */
    val nearArrivalMeters: Double = 1_500.0,
    /** 휴식 전 비교 구간 */
    val beforeWindowMs: Long = 20 * MINUTE_MS,
    /** 재출발 후 비교 구간 */
    val afterWindowMs: Long = 10 * MINUTE_MS,
    /** 기본 휴식 시간. 30분 미만 낮잠이 깊은 잠을 피해 깬 뒤 멍함(수면 관성)이 덜하다 */
    val defaultRestMinutes: Int = 20,
    /** 깨우기 시간: 깬 뒤 바로 출발하지 않고 몸을 깨우는 시간. 도착 예상에도 더한다 (시험값, 팀 결정 필요) */
    val wakeUpMs: Long = 10 * MINUTE_MS,
    /** 다시 출발한 뒤 휴식 전후 주행을 비교할지 (드라이브메이트 기획에서 빠짐, 기본 꺼짐) */
    val compareAfterRest: Boolean = false,
    val stability: StabilityConfig = StabilityConfig(),
) {
    companion object {
        /**
         * PT 시연용: 가상 시계가 timeScale배로 흐르므로 대기 시간은 그대로 두고(실제로는 짧아짐),
         * 상태 확인·쉼터 재검색만 실제 시간으로 너무 잦지 않게 늘린다. 화면에 '시연 모드'로 표시한다.
         */
        fun forDemo(timeScale: Double) = RestMateConfig(
            checkInIntervalMs = (20 * SECOND_MS * timeScale).toLong(),
            aheadSearchIntervalMs = (5 * SECOND_MS * timeScale).toLong(),
        )
    }
}
