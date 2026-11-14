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

/**
 * 어떻게 쉬는지. 어떻게 쉬게 됐는지로 기본값을 정하고, 휴식 화면에서 바꿀 수 있다.
 * - 졸음 경고·레스트 모드 버튼으로 왔으면 [NAP]
 * - 온타임 모드의 '쉬다 가기'·'근처에서 기다리기' 코스로 왔으면 [WAIT]
 * - 롱웨이 모드 중 안내 없이 세웠으면 묻는다 ([STAY] 또는 [NAP])
 */
enum class RestKind(val label: String, val detail: String) {
    /** 눈 붙이기: 알람으로 깨우고, 깬 뒤 몸을 깨우는 시간을 둔다 */
    NAP("눈 붙이기", "알람으로 깨워 드려요"),

    /** 그냥 기다리기: 출발할 시각만 알려 준다 (깨우기·센 바람 없음) */
    WAIT("그냥 기다리기", "출발할 시각에 알려 드려요"),

    /** 잠깐 머물기 (롱웨이 모드): 분위기를 그대로 두고, 출발할 때쯤 묻는다 */
    STAY("잠깐 머물기", "분위기 그대로, 출발할 때 알려 드려요"),
}

/**
 * 지금 달리는 드라이브의 도착 시각. 쉬는 동안 알람·출발 시각을 여기에 맞춘다.
 *
 * @param strict 온타임 모드처럼 늦으면 안 되는 시각인지 (롱웨이 모드는 느슨한 목표)
 * @param remainingMs 지금 위치에서 목적지까지 바로 가는 시간 (내비 값, 없으면 추정). 모르면 null
 * @param quiet 롱웨이 모드 데이트처럼 말하지 않는 드라이브인지 (출발 알림을 화면으로만)
 */
data class TripDeadline(
    val targetAt: Long,
    val marginMs: Long,
    val strict: Boolean,
    val remainingMs: Long?,
    val quiet: Boolean = false,
)

/** 낮잠·깨우기 시간 계산 */
object RestTiming {
    /** 이보다 짧게 쉬면 깨우는 시간을 두지 않는다 (깊이 잠들 시간이 없음) */
    const val NO_WAKE_UNDER_MS = 5 * MINUTE_MS

    /** 도착 시각에 맞추려고 줄이더라도 낮잠은 이만큼은 있어야 쉰 보람이 있다 */
    const val MIN_FITTED_NAP_MS = 10 * MINUTE_MS

    /** 깬 뒤 몸을 깨우는 시간: 쉰 시간의 절반, 최대 [maxWakeMs]. 5분 미만으로 쉬었으면 0 */
    fun wakeMsFor(restedMs: Long, maxWakeMs: Long): Long =
        if (restedMs < NO_WAKE_UNDER_MS) 0L else minOf(maxWakeMs, restedMs / 2)

    /** [availableMs] 안에 깨우는 시간까지 마칠 수 있는 가장 긴 낮잠. 너무 짧으면 0 */
    fun longestNapMs(availableMs: Long, maxWakeMs: Long): Long = when {
        // 낮잠이 maxWake의 2배 이상이면 깨우는 시간은 maxWake: n + maxWake ≤ available
        availableMs >= 3 * maxWakeMs -> availableMs - maxWakeMs
        // 그보다 짧으면 깨우는 시간은 n/2: 1.5n ≤ available
        availableMs * 2 / 3 >= NO_WAKE_UNDER_MS -> availableMs * 2 / 3
        else -> 0L
    }
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

    /** 안내 없이 P단 주차했을 때 쉬어 갈지, 어떻게 쉴지 묻는다. */
    data object ParkedHere : Prompt

    /** 쉼터로 이동 중 상태 확인 */
    data object CheckIn : Prompt
}

/** 엔진이 앱에 요청하는 동작 (내비 안내, 음성, 알람) */
sealed interface Effect {
    data class StartGuidance(val area: RestArea) : Effect
    data object CancelGuidance : Effect
    data class Speak(val text: String) : Effect
    /** 점점 커지는 알람 (끌 때까지 울림) */
    data class Alarm(val on: Boolean) : Effect
    /** 짧은 알림음 한 번 (출발할 시각) */
    data object Chime : Effect

    /** @param departing 출발해서 되돌리는지 (아니면 쉬는 방식을 바꿔 휴식 환경만 끈 것) */
    data class Cabin(val mode: CabinMode, val departing: Boolean = true) : Effect
}

data class RestSession(
    val startedAt: Long,
    val plannedMinutes: Int,
    /** 눈 붙이기: 깨우는 알람 / 기다리기·머물기: 출발을 알려 주는 시각 */
    val alarmAt: Long,
    val alarmRinging: Boolean = false,
    /** 알람을 끈 뒤에는 연장하기 전까지 다시 울리지 않는다. */
    val alarmDone: Boolean = false,
    val area: RestArea? = null,
    val kind: RestKind = RestKind.NAP,
    /** 맞춰야 할 도착 시각 (온타임 모드·롱웨이 모드로 달리는 중일 때). 없으면 null */
    val targetAt: Long? = null,
    /** [targetAt]이 늦으면 안 되는 시각인지 (온타임 모드) */
    val strictTarget: Boolean = false,
    /** 이때 출발하면 도착 시각에 맞는다 (여유 포함). 도착 시각이 없으면 null */
    val departAt: Long? = null,
    /** 알람을 도착 시각에 맞춰 잡았는지 ('○시 도착에 맞춰 깨워 드려요'는 이때만) */
    val fitsTarget: Boolean = false,
    /** 이대로 쉬면 도착이 늦어지는 시간. 늦지 않으면 null */
    val lateByMs: Long? = null,
    /** 쉬는 동안 남은 길이 바뀌어 알람을 옮겼으면 옮기기 전 시각 (화면에 '알람 9:30 → 9:33') */
    val previousAlarmAt: Long? = null,
    /** 쉴 시간을 직접 골랐는지. 고른 뒤에는 알람을 저절로 옮기지 않는다 */
    val userSetMinutes: Boolean = false,
    /** 기다리기·머물기에서 출발 시각을 알려 줬는지 */
    val remindedAt: Long? = null,
    /** 깬 뒤 몸을 깨우는 시간 (깨우기를 시작할 때 쉰 시간으로 정한다) */
    val wakeMs: Long = 0L,
    /** 졸려서 쉬는지 (레스트 모드 안내·졸음 경고로 왔음). 기다리기로 바꿔도 조금은 쉬고 출발하게 한다 */
    val fatigue: Boolean = false,
    /** 쉬기 전 30분 안에 차량의 운전자 주의 경고가 있었는지. 낮잠을 도착 시각에 맞춰 줄이지 않는다 */
    val warned: Boolean = false,
    /**
     * 출발하라고 알릴 시각: 도착 시각에 맞는 출발 시각. 졸려서 쉬는 중이면 조금은 쉰 뒤, 눈 붙이기면 몸을 깨운 뒤.
     * 도착 시각이 없으면 null
     */
    val callAt: Long? = null,
    /** 출발할 시각이라고 알린 때 (짧은 알림음 + 음성) */
    val departCalledAt: Long? = null,
    /**
     * 눈 붙이기를 도착 시각에 맞춰 줄이기로 골랐는지. 고르지 않으면 정한 시간만큼 재우고 늦는 것만 한 번 알린다
     * (줄일지는 운전자가 정한다).
     */
    val fitNap: Boolean = false,
    /** 이대로 자면 늦을 때, 도착 시각에 맞추려면 몇 분까지 줄일 수 있는지 (화면 버튼). 줄여도 소용없으면 null */
    val fitNapMinutes: Int? = null,
    /** [fitNapMinutes]로 줄여도 남는 늦음 (최소 10분은 자므로). 줄이면 늦지 않으면 null */
    val fitNapLateMs: Long? = null,
    /**
     * 기다리는 동안(눈 붙이기는 깬 뒤) 남은 길이 바뀌어 [callAt]이 1분 넘게 옮겨졌으면 옮기기 전 시각
     * (화면에 '출발 시각 2:03 → 1:57'). 시각을 다시 알리면 지운다.
     */
    val callMovedFrom: Long? = null,
    /** '지금 출발해도 늦어요'를 알린 때 */
    val lateWarnedAt: Long? = null,
    /** 멈춘 자리. 여기서 150m 넘게 움직이면 출발로 본다 */
    val stopPosition: LatLng? = null,
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
) {
    /** 알람(기다리기·머물기는 출발 알림)이 아직 안 울렸는지. 시연의 '휴식 건너뛰기'는 이때만 쓴다 */
    val alarmPending: Boolean get() = !alarmRinging && !alarmDone && wakeStartedAt == null && remindedAt == null
}

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
    /**
     * 깨울 때 차가 무드등을 밝히고 바깥 바람을 넣을 수 있는지. 휴식 환경을 껐거나, 차가 연결되지 않았거나,
     * 이 차에서 공조를 바꿀 수 없으면 false: 깨우는 말과 깬 뒤 할 일에서 창문을 열어 달라고 한다.
     */
    val cabinWakes: Boolean = true,
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
    /** 기본 낮잠 시간 (고속도로). 30분 미만 낮잠이 깊은 잠을 피해 깬 뒤 멍함(수면 관성)이 덜하다 */
    val defaultRestMinutes: Int = 20,
    /** 시내(일반 도로) 기본 낮잠 시간. 갈 길이 짧아 조금 짧게 쉰다 */
    val cityRestMinutes: Int = 15,
    /** 깨우기 시간의 최대값: 깬 뒤 바로 출발하지 않고 몸을 깨우는 시간 (쉰 시간의 절반, 최대 이만큼) */
    val wakeUpMs: Long = 10 * MINUTE_MS,
    /** 기다리기: 출발 시각 이만큼 전에 알려 준다 */
    val departNoticeMs: Long = 2 * MINUTE_MS,
    /** 잠깐 머물기(롱웨이 모드): 세운 뒤 적어도 이만큼은 출발을 묻지 않는다 */
    val minStayMs: Long = 10 * MINUTE_MS,
    /** 졸려서 쉬는 중(레스트 모드 안내로 옴)이면 기다리기로 바꿔도 이만큼은 출발을 재촉하지 않는다 */
    val fatigueMinRestMs: Long = 10 * MINUTE_MS,
    /** 쉬기 전 이 시간 안에 운전자 주의 경고가 있었으면 경고 뒤 휴식으로 본다 */
    val warnedWindowMs: Long = 30 * MINUTE_MS,
    /** 목적지까지 이보다 적게 남았으면 휴식 공백 안내·2시간 알림 대신 '도착해서 쉬어 가세요' */
    val nearDestinationMs: Long = 30 * MINUTE_MS,
    /** 쉬는 동안 남은 길을 다시 보고 알람·출발 시각을 고치는 간격 */
    val refitIntervalMs: Long = MINUTE_MS,
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
