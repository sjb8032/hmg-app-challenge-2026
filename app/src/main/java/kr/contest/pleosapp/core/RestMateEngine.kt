package kr.contest.pleosapp.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** 목적지 도착 안내 앞뒤로 이 시간 안에 세운 차는 도착해서 세운 것으로 본다 */
private const val ARRIVAL_PARK_WINDOW_MS = 5 * MINUTE_MS

/** 안내하던 쉼터에서 이만큼 안에서 세웠으면 그 쉼터에서 쉬는 것으로 본다 (멀면 '지금 있는 곳') */
private const val AT_AREA_METERS = 400.0

/** 쉬는 중 이 속도를 넘거나, 멈춘 자리에서 이만큼 움직이면 출발로 본다 (주차장 안에서 자리만 옮기는 것과 구분) */
private const val DEPART_SPEED_KMH = 15.0
private const val DEPART_MOVE_METERS = 150.0

/** 속도를 모를 때는 D단이 이만큼 이어지면 출발로 본다 */
private const val DEPART_HOLD_MS = 60 * SECOND_MS

/**
 * 기다리는 곳이 목적지에서 이만큼 안이면 (목적지 바로 옆 주차장), 천천히 조금만 움직여도 목적지 쪽으로 가면 출발로 본다.
 * 막힌 길로 기어 나가 [DEPART_MOVE_METERS] 전에 목적지에 닿는 것까지 놓치지 않게.
 */
private const val NEAR_DEST_METERS = 300.0
private const val NEAR_DEST_MOVE_METERS = 30.0

/** 이 속도를 넘으면 움직이는 중으로 본다 (출발하는 중에는 늦음 알람을 울리지 않는다) */
private const val MOVING_KMH = 3.0

/** 늦음은 30초 넘게 늦을 때부터 센다 (달리는 화면의 '늦어요'와 같은 기준) */
private const val LATE_MS = 30 * SECOND_MS

/** 안내를 시작한 뒤 이 시간 안에는 '곧 도착해요'를 말하지 않는다 (안내 시작 말과 겹치지 않게) */
private const val NEAR_SPEECH_GAP_MS = MINUTE_MS

/** 출발 알림 뒤 이만큼 지나도 그대로면 알람을 울린다 (늦기 시작할 때) */
private const val ESCALATE_AFTER_CALL_MS = 2 * MINUTE_MS

/** 늦음 알람을 끄고도(또는 저절로 멈춘 뒤에도) 그대로 있으면 이 간격으로 짧게 다시 알린다 */
private const val LATE_REPEAT_MS = 5 * MINUTE_MS

/** 늦음 알람은 이만큼 울리면 저절로 멈춘다 (빈 차에서 계속 울리지 않게) */
private const val LATE_ALARM_RING_MS = 2 * MINUTE_MS

/** 출발 알림 뒤 이만큼 지나면 늦음을 더 알리지 않는다 */
private const val LATE_REPEAT_UNTIL_MS = 20 * MINUTE_MS

/** 깨우는 알람이 이만큼 넘게 울린 뒤에 껐으면 그동안 자고 있었던 것으로 보고 깨우는 시간을 다시 센다 */
private const val SLEPT_THROUGH_ALARM_MS = 3 * MINUTE_MS

/**
 * 레스트메이트의 상태 전환을 맡는다. 안드로이드에 의존하지 않고, 시간은 밖에서 넣어 준다.
 *
 * 주행 → (졸려요·주의 경고·연속 주행 2시간·휴식 공백) → 쉼터 이동 → (P단 주차 확정) → 휴식
 * → (출발) → 주행하면서 휴식 전후 주행 비교
 *
 * 온타임 모드로 달리는 중이면 출발 알림을 단계로 한다: 출발 2분 전 음성 → 출발 시각에 알림음과 음성 →
 * 그래도 그대로면 늦기 시작할 때 알람. 졸려서 쉬는 중이면 기다리기로 바꿔도 조금은 쉬고 출발하게 한다.
 *
 * 내비 안내·음성·알람처럼 밖에서 해야 할 일은 [Effect]로 쌓아 두고, 앱이 [drainEffects]로 가져가 처리한다.
 */
class RestMateEngine(
    private val finder: RestAreaFinder,
    initialConfig: RestMateConfig = RestMateConfig(),
) {
    private val stability = StabilityMonitor(initialConfig.stability)

    var config: RestMateConfig = initialConfig
        set(value) {
            field = value
            stability.config = value.stability
        }

    var state = RestMateState()
        private set

    private val effects = ArrayList<Effect>()

    private var lastTick: Long? = null
    private var parkedSince: Long? = null
    private var parkedPromptShown = false

    /**
     * 앱을 켠 뒤 한 번이라도 달렸는지 (D·R·N단). 처음부터 P단으로 서 있으면(집 주차장에서 앱을 켬)
     * '주차하셨네요. 여기서 쉬어 갈까요?'를 묻지 않는다. 쉬어 가자는 말은 달리다 세웠을 때만 맞다.
     */
    private var droveSinceStart = false
    private var longDriveSnoozeUntil = 0L
    /** 휴식 공백 안내·2시간 알림을 넘긴 뒤 한동안 다시 묻지 않는다 */
    private var adviceSnoozeUntil = 0L
    private val gapAdvisedIds = HashSet<String>()
    private var lastAheadSearch = -1L
    private var lastCheckIn = 0L
    private var nearArrivalAnnounced = false
    private var guidanceStartedAt = Long.MIN_VALUE / 2
    private var closestToTarget = Double.MAX_VALUE
    private var lastPosition: LatLng? = null
    private var lastPrune = 0L
    private var tripArrivedAt = Long.MIN_VALUE / 2
    private var lastWarningAt = Long.MIN_VALUE / 2
    /** 달리는 드라이브의 도착 시각 (앱이 드라이브 엔진에서 받아 넣어 준다) */
    private var deadline: TripDeadline? = null
    /** 지금 휴식에서 원래 쉬려던 시간 (도착 시각에 맞추려고 줄여도 기억해 둔다) */
    private var wantedMs = 0L
    private var lastRefitAt = Long.MIN_VALUE / 2
    /** 쉬는 중 D단으로 바꾼 시각 (속도를 모를 때 출발 판단에 쓴다) */
    private var driveSince: Long? = null

    private enum class Hurry {
        /** '더 빨리 쉴래요' 버튼 */
        BUTTON,

        /** 상태 확인에서 '더 졸려요' */
        MORE_DROWSY,

        /** 안내 중에 다시 온 운전자 주의 경고 */
        WARNING,
    }

    fun drainEffects(): List<Effect> {
        if (effects.isEmpty()) return emptyList()
        val out = effects.toList()
        effects.clear()
        return out
    }

    // ---- 차량·내비 입력 ----

    fun onTick(now: Long) {
        advance(now)
        evaluate(now)
    }

    fun onGear(now: Long, gear: Gear) {
        advance(now)
        state = state.copy(gear = gear)
        if (gear == Gear.DRIVE || gear == Gear.REVERSE || gear == Gear.NEUTRAL) droveSinceStart = true
        evaluate(now)
    }

    fun onSpeed(now: Long, kmh: Double?) {
        advance(now)
        state = state.copy(speedKmh = kmh)
        stability.onSpeed(now, kmh)
        evaluate(now)
    }

    fun onLocation(now: Long, position: LatLng, headingDeg: Double?) {
        advance(now)
        var heading = headingDeg
        val prev = lastPosition
        if (prev == null) {
            lastPosition = position
        } else if (Geo.distanceMeters(prev, position) >= 30.0) {
            if (heading == null) heading = Geo.bearingDegrees(prev, position)
            lastPosition = position
        }
        state = state.copy(position = position, headingDeg = heading ?: state.headingDeg)
        evaluate(now)
    }

    fun onDestination(destination: LatLng?) {
        state = state.copy(destination = destination)
    }

    /** 내비가 알려 주는 현재 도로 제한속도. 90km/h 이상이면 고속도로로 본다. */
    fun onSpeedLimit(kmh: Int?) {
        state = state.copy(onExpressway = kmh?.let { it >= 90 })
    }

    /**
     * 목적지에 도착했다 (내비 도착 안내). 도착해서 세운 차에는 '여기서 쉬어 갈까요?'를 묻지 않고, 쉬어 가자는 카드도 지운다.
     * 목적지 바로 옆에서 기다리다 옮겨 와 닿았으면 쉬기도 조용히 끝낸다 (도착은 시간 모드가 알린다).
     */
    fun onTripArrived(now: Long) {
        tripArrivedAt = now
        // 도착한 뒤 한동안은 연속 주행 알림을 다시 내지 않는다 (숙소 주차장에서 '다음 쉼터에 들를까요?'가 되지 않게).
        longDriveSnoozeUntil = max(longDriveSnoozeUntil, now + config.longDriveSnoozeMs)
        val rest = state.rest
        if (state.phase == Phase.RESTING && rest != null) {
            val from = rest.stopPosition
            val at = state.position
            if (from != null && at != null && Geo.distanceMeters(from, at) >= NEAR_DEST_MOVE_METERS) {
                endRest(now, arrived = true)
            }
            return
        }
        when (state.prompt) {
            Prompt.ParkedHere, is Prompt.LongDrive, is Prompt.RestGapAhead -> state = state.copy(prompt = null)
            else -> Unit
        }
    }

    /** 시연용으로 고속도로 주행 여부를 직접 정한다. */
    fun setOnExpressway(on: Boolean?) {
        state = state.copy(onExpressway = on)
    }

    fun onRemainingRoute(ms: Long?) {
        state = state.copy(remainingRouteMs = ms)
    }

    /**
     * 온타임 모드·롱웨이 모드로 달리는 중이면 도착 시각. 쉬는 동안 알람·출발 시각을 여기에 맞춘다.
     * 쉬는 중에 도착 목표가 바뀌면 (+5분·+10분) 바로 다시 맞추고 출발 시각을 알려 준다.
     */
    fun setDeadline(value: TripDeadline?) {
        deadline = value
        val rest = state.rest ?: return
        if (value == null || rest.targetAt == null) return
        when {
            value.targetAt != rest.targetAt -> retarget(state.now)
            // 쉬는 중에 남은 길이 1분 넘게 바뀌면 (막힘·풀림) 1분을 기다리지 않고 바로 출발 시각을 다시 맞춘다.
            value.remainingMs != null && rest.remainingRouteMs != null &&
                abs(value.remainingMs - rest.remainingRouteMs) >= MINUTE_MS -> {
                lastRefitAt = state.now
                refit(state.now)
            }
        }
    }

    fun onSteeringAngle(now: Long, degrees: Double) = stability.onSteeringAngle(now, degrees)

    fun onBrake(now: Long, pressed: Boolean) = stability.onBrake(now, pressed)

    fun onTurnSignal(now: Long, on: Boolean) = stability.onTurnSignal(now, on)

    /** 차량의 운전자 주의 경고. 묻지 않고 바로 쉼터 안내로 넘어간다 (목적지가 더 가까우면 도착해서 쉬라고 한다). */
    fun onAttentionWarning(now: Long) {
        advance(now)
        stability.onWarning(now)
        lastWarningAt = now
        when (state.phase) {
            Phase.DRIVING -> guideToBest(now, announce = true, opening = "운전자 주의 경고가 있었어요. ")
            Phase.TO_REST_AREA -> findFasterStop(now, Hurry.WARNING)
            Phase.RESTING -> Unit
        }
    }

    /** 차량 경고등에서 읽은 사건. 주의 경고는 쉼터 안내로 이어지고, 나머지는 휴식 전후 비교에만 쓴다. */
    fun onVehicleEvent(now: Long, event: VehicleEvent) {
        when (event) {
            VehicleEvent.DRIVER_ATTENTION -> onAttentionWarning(now)
            VehicleEvent.LANE_ASSIST -> stability.onLaneEvent(now)
            VehicleEvent.COLLISION_WARNING -> stability.onCollisionWarning(now)
        }
    }

    // ---- 운전자 조작 ----

    /**
     * '레스트 모드'(졸려요) 버튼. 묻지 않고 바로 쉼터 안내로 넘어간다.
     * 이미 P단으로 세워 두었으면 쉼터로 보내지 않고 그 자리에서 바로 눈 붙이기를 시작한다
     * (예전에는 쉼터 안내를 요청한 직후 '주차 확인'으로 그 자리 휴식이 시작돼, 쉬는 동안 내비가 쉼터로 안내하고 있었다).
     */
    fun pressDrowsy(now: Long) {
        advance(now)
        when (state.phase) {
            Phase.DRIVING -> if (state.gear == Gear.PARK) startRest(now, RestKind.NAP, area = null) else guideToBest(now, announce = true)
            Phase.TO_REST_AREA -> findFasterStop(now, Hurry.BUTTON)
            Phase.RESTING -> Unit
        }
    }

    fun selectRestArea(now: Long, candidate: RestAreaCandidate) {
        advance(now)
        val others = (state.alternatives + listOfNotNull(state.target)).filter { it.area.id != candidate.area.id }
        beginGuidance(now, candidate, others, announce = true)
    }

    fun cancelGuidance(now: Long) {
        advance(now)
        if (state.phase != Phase.TO_REST_AREA) return
        state = state.copy(
            phase = Phase.DRIVING,
            target = null,
            targetRemainingMeters = null,
            alternatives = emptyList(),
            prompt = null,
        )
        effects += Effect.CancelGuidance
    }

    fun answerCheckIn(now: Long, answer: CheckInAnswer) {
        advance(now)
        if (state.prompt == Prompt.CheckIn) state = state.copy(prompt = null)
        lastCheckIn = now
        when (answer) {
            CheckInAnswer.OK -> Unit
            CheckInAnswer.MORE_DROWSY -> findFasterStop(now, Hurry.MORE_DROWSY)
            CheckInAnswer.QUIET -> state = state.copy(quiet = true)
        }
    }

    fun acceptPrompt(now: Long) {
        advance(now)
        val prompt = state.prompt ?: return
        state = state.copy(prompt = null)
        when (prompt) {
            is Prompt.LongDrive -> guideToBest(now, announce = true)
            is Prompt.RestGapAhead ->
                beginGuidance(now, prompt.next.updatedFor(state.position, state.speedKmh), emptyList(), announce = true)
            Prompt.ParkedHere -> startRest(now)
            Prompt.CheckIn -> lastCheckIn = now
        }
    }

    /** '주차하셨네요' 물음에 어떻게 쉴지 골라 답한다 (그냥 기다리기·잠깐 머물기·눈 붙이기). */
    fun restHere(now: Long, kind: RestKind) {
        advance(now)
        if (state.prompt == Prompt.ParkedHere) state = state.copy(prompt = null)
        startRest(now, kind, area = null)
    }

    fun dismissPrompt(now: Long) {
        advance(now)
        val prompt = state.prompt ?: return
        state = state.copy(prompt = null)
        when (prompt) {
            is Prompt.LongDrive, is Prompt.RestGapAhead -> {
                // 넘긴 뒤 다른 쉼터로 바로 또 묻지 않는다.
                longDriveSnoozeUntil = now + config.longDriveSnoozeMs
                adviceSnoozeUntil = now + config.longDriveSnoozeMs
            }
            Prompt.ParkedHere -> Unit
            Prompt.CheckIn -> lastCheckIn = now
        }
    }

    /**
     * 휴식 시작. P단 주차가 확정되면 자동으로 불리고, '주차했어요' 버튼으로도 부른다.
     * 온타임 모드로 달리는 중이면 알람·출발 시각을 도착 시각에 맞추고, 맞출 수 없으면 얼마나 늦어지는지 먼저 알린다.
     *
     * @param kind 졸음·레스트 모드 버튼으로 왔으면 눈 붙이기, 쉬다 가는 코스면 그냥 기다리기
     * @param area 쉬는 곳. 기본값은 안내하던 쉼터 (그 쉼터에서 멀리 세웠으면 '지금 있는 곳')
     */
    fun startRest(now: Long, kind: RestKind = RestKind.NAP, area: RestArea? = guidedAreaHere()) {
        advance(now)
        if (state.phase == Phase.RESTING) return
        val fromGuidance = state.phase == Phase.TO_REST_AREA
        val warned = now - lastWarningAt <= config.warnedWindowMs
        val navRemaining = state.remainingRouteMs
        val remaining = deadline?.remainingMs ?: navRemaining ?: estimateRemainingRouteMs()
        wantedMs = defaultMinutes() * MINUTE_MS
        lastRefitAt = now
        driveSince = null
        val d = deadline
        val still = state.gear == Gear.PARK || (state.speedKmh ?: 0.0) < 5.0
        val base = RestSession(
            startedAt = now,
            plannedMinutes = defaultMinutes(),
            alarmAt = now + wantedMs,
            area = area,
            kind = kind,
            targetAt = d?.targetAt,
            strictTarget = d?.strict == true,
            remainingRouteMs = remaining,
            remainingRouteEstimated = d?.remainingMs == null && navRemaining == null && remaining != null,
            fatigue = fromGuidance || warned,
            warned = warned,
            stopPosition = state.position.takeIf { still },
        )
        var rest = fitted(now, base, remaining)
        val speech = startSpeech(now, rest)
        rest = markStartNotices(now, rest)
        state = state.copy(
            phase = Phase.RESTING,
            prompt = null,
            quiet = false,
            recovery = null,
            target = null,
            targetRemainingMeters = null,
            alternatives = emptyList(),
            rest = rest,
        )
        if (kind == RestKind.NAP) effects += Effect.Cabin(CabinMode.REST)
        speech?.let { effects += Effect.Speak(it) }
    }

    /** 휴식 중에 어떻게 쉴지 바꾼다. 눈 붙이기로 바꾸면 휴식 환경을 켜고, 그만두면 휴식 환경만 끈다. */
    fun setRestKind(now: Long, kind: RestKind) {
        advance(now)
        val rest = state.rest ?: return
        if (rest.kind == kind) return
        if (rest.alarmRinging) effects += Effect.Alarm(false)
        when {
            kind == RestKind.NAP -> effects += Effect.Cabin(CabinMode.REST)
            rest.kind == RestKind.NAP -> effects += Effect.Cabin(CabinMode.RESTORE, departing = false)
        }
        var reset = rest.copy(
            kind = kind,
            alarmRinging = false,
            alarmDone = false,
            wakeStartedAt = null,
            wakeMs = 0L,
            remindedAt = null,
            departCalledAt = null,
            lateWarnedAt = null,
            previousAlarmAt = null,
            callMovedFrom = null,
            userSetMinutes = false,
            fitNap = false,
        )
        if (kind == RestKind.NAP) {
            // 기다리다가 눈을 붙이면 낮잠은 지금부터 잰다.
            wantedMs = defaultMinutes() * MINUTE_MS
            reset = reset.copy(startedAt = now, plannedMinutes = defaultMinutes(), alarmAt = now + wantedMs)
        }
        lastRefitAt = now
        var next = fitted(now, reset, reset.remainingRouteMs)
        val speech = startSpeech(now, next)
        next = markStartNotices(now, next)
        state = state.copy(rest = next)
        speech?.let { effects += Effect.Speak(it) }
        evaluate(now)
    }

    /** 쉴 시간을 직접 고른다. 고른 뒤에는 알람을 저절로 옮기지 않고, 도착이 늦어지면 알려 준다. */
    fun setRestMinutes(now: Long, minutes: Int) {
        advance(now)
        val rest = state.rest ?: return
        reopen(rest)
        wantedMs = minutes * MINUTE_MS
        val chosen = reopened(rest).copy(
            plannedMinutes = minutes,
            alarmAt = rest.startedAt + minutes * MINUTE_MS,
            userSetMinutes = true,
            fitNap = false,
            previousAlarmAt = null,
        )
        val next = fitted(now, chosen, chosen.remainingRouteMs)
        state = state.copy(rest = next)
        effects += Effect.Speak(alarmChangeSpeech(next))
        evaluate(now)
    }

    fun extendRest(now: Long, minutes: Int) {
        advance(now)
        val rest = state.rest ?: return
        reopen(rest)
        val alarmAt = max(rest.alarmAt, now) + minutes * MINUTE_MS
        wantedMs = alarmAt - rest.startedAt
        val chosen = reopened(rest).copy(
            plannedMinutes = ((alarmAt - rest.startedAt) / MINUTE_MS).toInt(),
            alarmAt = alarmAt,
            userSetMinutes = true,
            fitNap = false,
            previousAlarmAt = null,
        )
        val next = fitted(now, chosen, chosen.remainingRouteMs)
        state = state.copy(rest = next)
        effects += Effect.Speak(alarmChangeSpeech(next))
    }

    /** 쉴 시간을 바꿨을 때: 언제 깨우는지(알리는지)와 도착이 늦어지는지 */
    private fun alarmChangeSpeech(rest: RestSession): String {
        val at = DriveText.spokenClock(rest.alarmAt)
        val head = if (rest.kind == RestKind.NAP) "${at}에 깨워 드릴게요." else "${at}에 알려 드릴게요."
        val late = rest.lateByMs ?: return head
        return "$head 도착은 약 ${DriveText.duration(late)} 늦어져요."
    }

    /** 알람을 다시 잡기 전에: 울리는 알람을 끄고, 깨우는 중이었으면 휴식 환경으로 돌린다. */
    private fun reopen(rest: RestSession) {
        if (rest.alarmRinging) effects += Effect.Alarm(false)
        if (rest.wakeStartedAt != null && rest.kind == RestKind.NAP) effects += Effect.Cabin(CabinMode.REST)
    }

    private fun reopened(rest: RestSession) = rest.copy(
        alarmRinging = false,
        alarmDone = false,
        wakeStartedAt = null,
        wakeMs = 0L,
        remindedAt = null,
        departCalledAt = null,
        lateWarnedAt = null,
        callMovedFrom = null,
    )

    /**
     * '알람 끄기'. 울리는 알람만 끈다 (울리기 전에는 아무것도 하지 않음).
     * 깨우는 알람을 한참 못 듣고 잤으면 몸을 깨우는 시간은 알람을 끈 지금부터 센다 (깊이 잔 사람일수록 더 멍하다).
     */
    fun stopAlarm(now: Long) {
        advance(now)
        val rest = state.rest ?: return
        if (!rest.alarmRinging) return
        effects += Effect.Alarm(false)
        val stopped = rest.copy(alarmRinging = false, alarmDone = true)
        val wokeAt = rest.wakeStartedAt
        val sleptThrough = rest.kind == RestKind.NAP && rest.lateWarnedAt == null && wokeAt != null &&
            now - wokeAt >= SLEPT_THROUGH_ALARM_MS
        if (!sleptThrough) {
            state = state.copy(rest = stopped)
            return
        }
        val wake = RestTiming.wakeMsFor(now - rest.startedAt, config.wakeUpMs)
        val restarted = withCall(stopped.copy(wakeStartedAt = now, wakeMs = wake))
        state = state.copy(rest = restarted)
        if (wake <= 0L) return
        val late = restarted.callAt?.let { call -> readyLateMs(restarted, max(call, now)) }
        // 졸려서 쉰 휴식은 늦는 것을 쉬기 시작할 때 알렸으므로 다시 말하지 않는다.
        val tail = if (restarted.strictTarget && !drowsyRest(restarted) && late != null && late > LATE_MS) {
            " 그러면 도착은 약 ${DriveText.duration(late)} 늦어져요."
        } else {
            ""
        }
        effects += Effect.Speak("막 깨셨으니 ${RestMateText.duration(wake)} 정도 몸을 깨운 뒤 출발해 주세요.$tail")
    }

    /** [at]에 출발하면 목표 시각보다 얼마나 늦는지 (여유까지 쓴 뒤 기준). 도착 시각이 없으면 null */
    private fun readyLateMs(rest: RestSession, at: Long): Long? {
        val depart = rest.departAt ?: return null
        return at - depart - (deadline?.marginMs ?: 0L)
    }

    /** 알람 전에 깼을 때 '일어났어요'. 남은 알람은 끄고 깨우기를 시작한다. */
    fun wakeUp(now: Long) {
        advance(now)
        val rest = state.rest ?: return
        if (rest.wakeStartedAt != null || rest.kind != RestKind.NAP) return
        if (rest.alarmRinging) effects += Effect.Alarm(false)
        val wake = RestTiming.wakeMsFor(now - rest.startedAt, config.wakeUpMs)
        val woke = withCall(rest.copy(alarmRinging = false, alarmDone = true, wakeStartedAt = now, wakeMs = wake))
        state = state.copy(rest = woke)
        startWakeUp(now, byAlarm = false, woke)
    }

    /** 기어 정보를 못 받을 때 쓰는 '출발' 버튼. */
    fun departNow(now: Long) {
        advance(now)
        if (state.phase == Phase.RESTING) endRest(now)
    }

    fun dismissRecovery(now: Long) {
        advance(now)
        state = state.copy(recovery = null)
    }

    /** 비교 결과가 불안정할 때 '한 번 더 쉬기'. */
    fun acceptRecoveryRest(now: Long) {
        advance(now)
        val next = state.recovery?.nextRest
        state = state.copy(recovery = null)
        if (next != null) beginGuidance(now, next, emptyList(), announce = true) else guideToBest(now, announce = true)
    }

    // ---- 시연용 ----
    // '휴식 건너뛰기'는 앱이 가상 시계를 알람 시각까지 돌려서 한다 (쉰 시간·알람 시각이 시계와 맞게). [RestSession.alarmPending]

    /** 시연용: 연속 주행 시간을 더한다. */
    fun addDrivingTime(now: Long, ms: Long) {
        advance(now)
        state = state.copy(continuousDrivingMs = state.continuousDrivingMs + ms)
        longDriveSnoozeUntil = 0L
        adviceSnoozeUntil = 0L
        evaluate(now)
    }

    // ---- 내부 ----

    private fun advance(now: Long) {
        val last = lastTick
        if (last != null && now <= last) return
        if (last != null && state.phase != Phase.RESTING && isDriving()) {
            val dt = minOf(now - last, 5 * MINUTE_MS)
            state = state.copy(continuousDrivingMs = state.continuousDrivingMs + dt)
        }
        lastTick = now
        state = state.copy(now = now)
    }

    /** 운전 중인지. 신호 대기·정체도 운전 시간에 넣는다 (P단이 아니면 운전 중). */
    private fun isDriving(): Boolean = when (state.gear) {
        Gear.DRIVE, Gear.REVERSE, Gear.NEUTRAL -> true
        Gear.PARK -> false
        Gear.UNKNOWN -> (state.speedKmh ?: 0.0) >= 5.0
    }

    /** 실제로 움직이고 있는지 (쉼터를 지나쳤는지 판단할 때 쓴다). */
    private fun isMoving(): Boolean {
        val speed = state.speedKmh
        return if (speed != null) speed >= 5.0 else state.gear == Gear.DRIVE
    }

    private fun evaluate(now: Long) {
        updateParking(now)
        when (state.phase) {
            Phase.DRIVING -> evaluateDriving(now)
            Phase.TO_REST_AREA -> evaluateToRestArea(now)
            Phase.RESTING -> evaluateResting(now)
        }
        evaluateRecovery(now)
        if (now - lastPrune > 10 * MINUTE_MS) {
            lastPrune = now
            stability.prune(now - 3 * HOUR_MS)
        }
    }

    private fun updateParking(now: Long) {
        val parked = state.gear == Gear.PARK
        if (!parked) {
            parkedSince = null
            parkedPromptShown = false
            if (state.prompt == Prompt.ParkedHere) state = state.copy(prompt = null)
            return
        }
        val since = parkedSince ?: now.also { parkedSince = it }
        val parkedFor = now - since
        if (parkedFor < config.parkConfirmMs) return
        when (state.phase) {
            Phase.TO_REST_AREA -> startRest(now)
            Phase.DRIVING -> {
                if (parkedFor >= config.restResetMs) state = state.copy(continuousDrivingMs = 0L)
                val atDestination = abs(since - tripArrivedAt) <= ARRIVAL_PARK_WINDOW_MS
                if (!parkedPromptShown && state.prompt == null && !atDestination && droveSinceStart) {
                    parkedPromptShown = true
                    state = state.copy(prompt = Prompt.ParkedHere)
                }
            }
            Phase.RESTING -> Unit
        }
    }

    private fun evaluateDriving(now: Long) {
        if (state.position != null && (lastAheadSearch < 0 || now - lastAheadSearch >= config.aheadSearchIntervalMs)) {
            lastAheadSearch = now
            val result = search()
            state = state.copy(nextAhead = result?.best)
            if (result != null) {
                refreshPromptCandidate(result)
                maybeAdviseGap(now, result)
            }
        }
        if (state.prompt == null && isDriving() && state.continuousDrivingMs >= config.longDriveMs &&
            now >= longDriveSnoozeUntil && now >= adviceSnoozeUntil
        ) {
            val hours = state.continuousDrivingMs / HOUR_MS
            longDriveSnoozeUntil = now + config.longDriveSnoozeMs
            if (nearDestination()) {
                // 곧 도착하면 쉼터에 들르자고 하지 않는다 (목적지 너머 쉼터로 보내지 않게). 데이트 중이면 말하지 않는다.
                if (deadline?.quiet != true) {
                    effects += Effect.Speak("${hours}시간 넘게 운전하셨어요. 곧 도착하니 도착하면 꼭 쉬어 가세요.")
                }
            } else {
                state = state.copy(
                    prompt = Prompt.LongDrive((state.continuousDrivingMs / MINUTE_MS).toInt(), state.nextAhead),
                )
                effects += Effect.Speak("${hours}시간 넘게 운전하셨어요. 다음 쉼터에 들를까요?")
            }
        }
    }

    /**
     * 떠 있는 알림 카드의 쉼터를 새 검색 결과로 고친다. 지나친 쉼터면 카드를 지운다.
     * 그사이 목적지가 가까워졌으면 (30분 안) 쉼터에 들르자는 카드를 지운다 (목적지 너머 쉼터로 보내지 않게).
     */
    private fun refreshPromptCandidate(result: RestAreaSearchResult) {
        val p0 = state.prompt
        if ((p0 is Prompt.LongDrive || p0 is Prompt.RestGapAhead) && nearDestination()) {
            state = state.copy(prompt = null)
            return
        }
        when (val p = state.prompt) {
            is Prompt.RestGapAhead -> {
                val fresh = result.candidates.firstOrNull { it.area.id == p.next.area.id }
                state = state.copy(prompt = fresh?.let { p.copy(next = it) })
            }
            is Prompt.LongDrive -> state = state.copy(prompt = p.copy(next = result.best))
            else -> Unit
        }
    }

    /** 목적지까지 얼마 안 남았는지 (휴식 공백 안내·2시간 알림 대신 '도착해서 쉬어 가세요') */
    private fun nearDestination(): Boolean {
        val remaining = routeRemainingMs() ?: return false
        return remaining <= config.nearDestinationMs
    }

    /** 목적지까지 남은 시간: 내비 값(경유지 포함), 없으면 드라이브의 바로 가는 시간 */
    private fun routeRemainingMs(): Long? = state.remainingRouteMs ?: deadline?.remainingMs

    /** 앞 쉼터를 지나치면 한참 쉴 곳이 없을 때, 그 앞에서 미리 묻는다. */
    private fun maybeAdviseGap(now: Long, result: RestAreaSearchResult) {
        if (state.prompt != null || !isDriving() || now < adviceSnoozeUntil) return
        if (state.continuousDrivingMs < config.gapAdviceMinDriveMs || nearDestination()) return
        val best = result.best ?: return
        if (best.etaMinutes > config.gapAdviceMaxEtaMinutes || best.area.id in gapAdvisedIds) return
        val gap = result.gapAfterFirstMinutes
        if (gap != null && gap < config.gapAdviceMinGapMinutes) return
        gapAdvisedIds += best.area.id
        val gapMinutes = gap?.roundToInt()
        state = state.copy(prompt = Prompt.RestGapAhead(best, gapMinutes))
        val tail = if (gapMinutes != null) "다음 휴식 장소까지 ${gapMinutes}분이에요" else "한동안 쉴 곳이 없어요"
        effects += Effect.Speak("앞 쉼터를 지나면 $tail. 이번에 쉬어 갈까요?")
    }

    private fun evaluateToRestArea(now: Long) {
        val target = state.target ?: return
        val remaining = state.position?.let { Geo.distanceMeters(it, target.area.position) }
        state = state.copy(targetRemainingMeters = remaining)
        if (remaining != null) {
            if (remaining < closestToTarget) closestToTarget = remaining
            if (closestToTarget < 700.0 && remaining > closestToTarget + 1_000.0 && isMoving()) {
                handlePassedTarget(now, target)
                return
            }
            if (remaining <= config.nearArrivalMeters) {
                if (!nearArrivalAnnounced) {
                    nearArrivalAnnounced = true
                    if (state.prompt == Prompt.CheckIn) state = state.copy(prompt = null)
                    // 안내를 막 시작했으면 시작할 때 한 말로 충분하다.
                    if (now - guidanceStartedAt > NEAR_SPEECH_GAP_MS) effects += Effect.Speak("곧 도착해요. 내비 안내에 집중해 주세요.")
                }
                return
            }
        }
        // 도착 직전에는 상태를 묻지 않는다 (묻자마자 '곧 도착'으로 카드가 사라지지 않게).
        val farEnough = remaining == null || remaining > 2 * config.nearArrivalMeters
        if (!state.quiet && state.prompt == null && farEnough && now - lastCheckIn >= config.checkInIntervalMs) {
            lastCheckIn = now
            state = state.copy(prompt = Prompt.CheckIn)
            effects += Effect.Speak("지금은 어떠세요? 더 졸리면 '더 졸려요'를 눌러 주세요.")
        }
    }

    private fun handlePassedTarget(now: Long, passed: RestAreaCandidate) {
        effects += Effect.Speak("쉼터를 지나쳤어요. 다음 쉼터를 안내할게요.")
        val result = search()
        val next = result?.candidates?.firstOrNull { it.area.id != passed.area.id }
        if (next != null) {
            beginGuidance(now, next, result.candidates.filter { it.area.id != passed.area.id }, announce = true)
        } else {
            state = state.copy(
                phase = Phase.DRIVING,
                target = null,
                targetRemainingMeters = null,
                alternatives = emptyList(),
                searchFailure = "앞쪽에서 다음 쉼터를 찾지 못했어요.",
            )
            effects += Effect.CancelGuidance
        }
    }

    private fun evaluateResting(now: Long) {
        var rest = state.rest ?: return
        val parkedNow = state.gear == Gear.PARK
        val stillNow = parkedNow || (state.speedKmh ?: 0.0) < 5.0
        val position = state.position
        if ((parkedNow && !rest.sawPark) || (stillNow && !rest.sawStill) || (stillNow && rest.stopPosition == null && position != null)) {
            rest = rest.copy(
                sawPark = rest.sawPark || parkedNow,
                sawStill = rest.sawStill || stillNow,
                stopPosition = rest.stopPosition ?: position.takeIf { stillNow },
            )
            state = state.copy(rest = rest)
        }
        if (departed(now, rest)) {
            endRest(now)
            return
        }
        if (now - lastRefitAt >= config.refitIntervalMs) {
            lastRefitAt = now
            refit(now)
            rest = state.rest ?: return
        }
        when (rest.kind) {
            RestKind.NAP -> if (!rest.alarmRinging && !rest.alarmDone && rest.wakeStartedAt == null && now >= rest.alarmAt) {
                val wake = RestTiming.wakeMsFor(now - rest.startedAt, config.wakeUpMs)
                val ringing = withCall(rest.copy(alarmRinging = true, wakeStartedAt = now, wakeMs = wake))
                state = state.copy(rest = ringing)
                effects += Effect.Alarm(true)
                startWakeUp(now, byAlarm = true, ringing)
                return
            }
            RestKind.WAIT, RestKind.STAY -> if (!rest.alarmDone && now >= rest.alarmAt) remind(now, rest)
        }
        departureCalls(now)
    }

    /**
     * 쉬다가 출발했는지. P단이거나 한 번도 멈춘 적이 없으면 아니다.
     * 시속 15km를 넘거나 멈춘 자리에서 150m 넘게 움직이면 출발로 본다 (주차장 안에서 자리만 옮기는 것과 구분).
     * 속도를 모르면 D단이 1분 이어질 때 출발로 본다.
     */
    private fun departed(now: Long, rest: RestSession): Boolean {
        val gear = state.gear
        if (gear == Gear.PARK) {
            driveSince = null
            return false
        }
        driveSince = if (gear == Gear.DRIVE) driveSince ?: now else null
        if (!rest.sawPark && !rest.sawStill) return false
        val speed = state.speedKmh
        if (speed != null && speed >= DEPART_SPEED_KMH) return true
        if (movedTowardDestination(rest)) return true
        val from = rest.stopPosition
        val at = state.position
        if (from != null && at != null && Geo.distanceMeters(from, at) >= DEPART_MOVE_METERS) return true
        val since = driveSince
        return speed == null && since != null && now - since >= DEPART_HOLD_MS
    }

    /**
     * 목적지 바로 옆(300m 안)에서 기다리다가 목적지 쪽으로 30m 넘게 움직였는지.
     * 주차장 안에서 자리만 옮기는 것과 달리 목적지에 가까워진다.
     */
    private fun movedTowardDestination(rest: RestSession): Boolean {
        val from = rest.stopPosition ?: return false
        val at = state.position ?: return false
        val dest = state.destination ?: return false
        val before = Geo.distanceMeters(from, dest)
        if (before > NEAR_DEST_METERS) return false
        if (Geo.distanceMeters(from, at) < NEAR_DEST_MOVE_METERS) return false
        return Geo.distanceMeters(at, dest) <= before - NEAR_DEST_MOVE_METERS / 2
    }

    /** 쉬는 차가 움직이는 중인지 (D단에서 천천히라도). 출발하는 중에는 늦음 알람·알림을 하지 않는다 */
    private fun movingOff(rest: RestSession): Boolean {
        if (state.gear == Gear.PARK) return false
        val speed = state.speedKmh
        if (speed != null && speed >= MOVING_KMH) return true
        val from = rest.stopPosition ?: return false
        val at = state.position ?: return false
        return Geo.distanceMeters(from, at) >= NEAR_DEST_MOVE_METERS
    }

    /** 깨우기: 차 환경을 바꾸고, 깬 뒤 바로 출발하지 않도록 안내한다 (수면 관성). 기다릴 시간이 남으면 출발 시각도 알린다. */
    private fun startWakeUp(now: Long, byAlarm: Boolean, rest: RestSession) {
        effects += Effect.Cabin(CabinMode.WAKE)
        val opening = if (byAlarm) "휴식 시간이 끝났어요." else "잘 쉬셨어요."
        val wakeMs = rest.wakeMs
        val call = rest.callAt
        val tail = when {
            rest.strictTarget && call != null && call - (now + wakeMs) >= 3 * MINUTE_MS ->
                "출발은 ${DriveText.spokenClock(call)}에 하면 돼요. 그때 다시 알려 드릴게요."
            wakeMs > 0 -> "깬 직후에는 멍할 수 있으니 ${RestMateText.duration(wakeMs)} 정도 몸을 깨운 뒤 출발해 주세요."
            else -> "준비되면 천천히 출발해 주세요."
        }
        effects += Effect.Speak("$opening 무드등을 밝히고 시원한 바람을 넣을게요. $tail")
    }

    /** 기다리기·머물기: 출발할 때가 다가왔다고 알린다 (기다리기는 출발 2분 전, 머물기는 출발할 때쯤). */
    private fun remind(now: Long, rest: RestSession) {
        state = state.copy(rest = rest.copy(alarmDone = true, remindedAt = now, callMovedFrom = null))
        val text = when (rest.kind) {
            // 롱웨이 모드 데이트는 분위기를 깨지 않게 화면으로만 알린다.
            RestKind.STAY -> if (deadline?.quiet == true) null else "슬슬 출발할까요?"
            else -> {
                val call = rest.callAt
                when {
                    call == null || rest.targetAt == null -> "${rest.plannedMinutes}분 지났어요. 준비되면 출발해 주세요."
                    call - now >= MINUTE_MS -> departNotice(rest, call)
                    // 출발 시각이 코앞이면 출발 알림이 곧바로 말한다.
                    else -> null
                }
            }
        }
        text?.let { effects += Effect.Speak(it) }
    }

    /**
     * 출발 2분 전 음성. 그때 떠나도 늦으면 맞는다고 하지 않고 언제 닿는지 말한다.
     * 남은 길이 바뀌어 처음 알린 출발 시각이 옮겨졌으면 그것부터 말한다.
     */
    private fun departNotice(rest: RestSession, call: Long): String {
        val target = rest.targetAt ?: return "곧 출발할 시간이에요. 출발 준비해 주세요."
        val route = rest.remainingRouteMs
        val late = readyLateMs(rest, call)
        val arrival = if (route != null && late != null && late > LATE_MS) {
            "${DriveText.spokenClock(call + route)}쯤 도착해요"
        } else {
            "${DriveText.spokenClock(target)} 도착에 맞아요"
        }
        val at = DriveText.spokenClock(call)
        val moved = rest.callMovedFrom
        return if (moved != null && moved != call) {
            val how = if (moved > call) "당겼어요" else "늦췄어요"
            "남은 길이 바뀌어 출발 시각을 ${DriveText.toward(at)} $how. 그때 출발하면 $arrival. 출발 준비해 주세요."
        } else {
            "${at}에 출발하면 $arrival. 출발 준비해 주세요."
        }
    }

    /** 졸려서 쉬는 중인지 (레스트 모드 안내로 왔거나 주의 경고 뒤). 출발을 재촉하지 않는다 */
    private fun drowsyRest(rest: RestSession): Boolean = rest.fatigue || rest.warned

    /**
     * 온타임 모드의 출발 알림 (기다리기, 깬 뒤의 눈 붙이기).
     * - 눈 붙이기: 깬 뒤 기다릴 시간이 있으면 출발 2분 전에 음성 (기다리기는 [remind]가 알람 시각에)
     * - 출발할 시각: 짧은 알림음과 음성
     * - 그래도 그대로 있어 늦기 시작하면: 알람과 '지금 출발해도 약 N분 늦어요' (다시 잠들었을 때를 위해)
     */
    private fun departureCalls(now: Long) {
        val rest = state.rest ?: return
        if (!rest.strictTarget || rest.kind == RestKind.STAY) return
        val depart = rest.departAt ?: return
        val target = rest.targetAt ?: return
        val call = rest.callAt ?: return
        if (rest.kind == RestKind.NAP && (rest.wakeStartedAt == null || rest.alarmRinging)) return
        // 이미 움직여 나가는 중이면 (막힌 길로 천천히) 출발을 재촉하지 않는다.
        if (movingOff(rest)) return
        val margin = deadline?.marginMs ?: 0L
        if (rest.kind == RestKind.NAP && rest.remindedAt == null && now < call) {
            val wakeEnd = (rest.wakeStartedAt ?: now) + rest.wakeMs
            if (call - wakeEnd >= 3 * MINUTE_MS && now >= call - config.departNoticeMs) {
                state = state.copy(rest = rest.copy(remindedAt = now, callMovedFrom = null))
                effects += Effect.Speak(departNotice(rest, call))
            }
            return
        }
        val lateNow = now - depart - margin
        val called = rest.departCalledAt
        if (called == null) {
            if (now < call) return
            state = state.copy(rest = rest.copy(departCalledAt = now))
            effects += Effect.Chime
            val moved = rest.callMovedFrom
            // 처음 알린 시각보다 당겨졌으면 왜 지금인지 먼저 말한다.
            val head = if (moved != null && moved > call) "남은 길이 바뀌어 출발 시각이 당겨졌어요." else "출발할 시간이에요."
            effects += Effect.Speak(
                when {
                    // 졸려서 쉬었는데 늦으면 늦는 것은 쉬기 시작할 때 알렸다: 재촉하지 않는다.
                    drowsyRest(rest) && lateNow > LATE_MS -> "충분히 쉬셨으면 천천히 출발해 주세요."
                    lateNow > LATE_MS -> "$head 지금 출발해도 약 ${DriveText.duration(lateNow)} 늦어요."
                    else -> "$head 지금 출발하면 ${DriveText.spokenClock(target)} 도착에 맞아요."
                },
            )
            return
        }
        // 졸려서 쉬는 중이면 늦어도 알람으로 깨우거나 다시 알리지 않는다 (안전 때문에 늦는 것을 받아들였다).
        if (drowsyRest(rest)) return
        val warnedAt = rest.lateWarnedAt
        if (warnedAt == null) {
            if (lateNow < MINUTE_MS || now - called < ESCALATE_AFTER_CALL_MS) return
            // 다시 잠들었을 때를 위해 알람을 울린다.
            state = state.copy(rest = rest.copy(lateWarnedAt = now, alarmRinging = true))
            effects += Effect.Alarm(true)
            effects += Effect.Speak("지금 출발해도 약 ${DriveText.duration(lateNow)} 늦어요.")
            return
        }
        if (rest.alarmRinging) {
            // 늦음 알람은 오래 울리지 않는다 (차를 비웠을 수도 있다). 이후로는 짧게 다시 알린다.
            if (now - warnedAt >= LATE_ALARM_RING_MS) {
                state = state.copy(rest = rest.copy(alarmRinging = false))
                effects += Effect.Alarm(false)
            }
            return
        }
        // 출발 알림 뒤 한참(20분) 지나도 그대로면 일부러 머무는 것으로 보고 더 알리지 않는다 (빈 차에서 계속 울리지 않게).
        if (now - warnedAt < LATE_REPEAT_MS || now - called > LATE_REPEAT_UNTIL_MS) return
        state = state.copy(rest = rest.copy(lateWarnedAt = now))
        effects += Effect.Chime
        effects += Effect.Speak("지금 출발해도 약 ${DriveText.duration(lateNow)} 늦어요.")
    }

    /**
     * 알람(기다리기·머물기는 출발 알림) 시각과 출발 시각을 정한다. 도착 시각이 없으면 [rest]의 알람을 그대로 쓴다.
     * - 눈 붙이기 (온타임): 깨우는 시간까지 마치고 제때 출발하도록 줄이되, 10분보다 짧게는 줄이지 않는다 (안전이 먼저).
     *   운전자 주의 경고 뒤의 휴식이나 쉴 시간을 직접 고른 경우에는 줄이지 않고 늦어지는 만큼만 알린다.
     * - 그냥 기다리기: 출발 시각 [RestMateConfig.departNoticeMs] 전에 알린다. 졸려서 쉬는 중이면 조금은 쉰 뒤로.
     * - 잠깐 머물기: 출발 시각에 묻는다 (세운 뒤 적어도 [RestMateConfig.minStayMs]).
     * 이미 울렸거나 알린 알람은 옮기지 않는다.
     */
    private fun fitted(now: Long, rest: RestSession, remaining: Long?): RestSession {
        val d = deadline
        if (d == null || remaining == null) {
            return rest.copy(targetAt = null, strictTarget = false, departAt = null, callAt = null, fitsTarget = false, lateByMs = null)
        }
        val departAt = d.targetAt - remaining - d.marginMs
        val base = rest.copy(targetAt = d.targetAt, strictTarget = d.strict, departAt = departAt, remainingRouteMs = remaining)
        val open = !base.alarmRinging && !base.alarmDone && base.wakeStartedAt == null && base.remindedAt == null
        var alarmAt = base.alarmAt
        // 도착 시각에 맞추면 몇 분 잘 수 있는지 (최소 10분). 줄일지는 운전자가 고른다 ([fitNapToArrival]).
        val fitNapMs = if (base.kind == RestKind.NAP && d.strict && !base.warned) {
            val longest = RestTiming.longestNapMs(departAt - base.startedAt, config.wakeUpMs)
            max(minOf(wantedMs, longest), minOf(wantedMs, RestTiming.MIN_FITTED_NAP_MS))
        } else {
            null
        }
        if (open && !base.userSetMinutes) {
            when (base.kind) {
                RestKind.NAP -> if (fitNapMs != null && base.fitNap) alarmAt = base.startedAt + fitNapMs
                RestKind.WAIT -> {
                    // 출발 알림처럼 분 단위로 내려 맞춘 출발 시각의 2분 전
                    val call = max(departAt, base.startedAt + minRestMs(base)) / MINUTE_MS * MINUTE_MS
                    alarmAt = max(call - config.departNoticeMs, now)
                }
                RestKind.STAY -> alarmAt = max(max(departAt, base.startedAt + minRestMs(base)), now)
            }
        }
        val next = withCall(base.copy(alarmAt = alarmAt))
        val ready = readyAt(next) ?: departAt
        // 여유(3분)까지 쓰면 목표 시각에는 닿는다: 늦음은 목표 시각 기준으로 센다.
        val late = max(ready, now) - departAt - d.marginMs
        val minutes = (((alarmAt - base.startedAt) + MINUTE_MS / 2) / MINUTE_MS).toInt().coerceAtLeast(1)
        // 늦을 것 같고 줄이면 도움이 될 때만 '시간에 맞춰 N분만 쉬기'를 내놓는다
        // (이미 그만큼 잤으면 내놓지 않는다: 누르자마자 깨우게 되므로).
        val fitMinutes = fitNapMs?.let { ((it + MINUTE_MS / 2) / MINUTE_MS).toInt() }
        val offerFit = open && !base.fitNap && late > LATE_MS && fitNapMs != null &&
            fitNapMs < alarmAt - base.startedAt - MINUTE_MS / 2 && base.startedAt + fitNapMs > now + MINUTE_MS
        // 줄여도(최소 10분) 남는 늦음: 버튼과 음성이 '늦지 않아요'라고 잘못 말하지 않게
        val fitLate = if (offerFit && fitNapMs != null) {
            val shortened = withCall(base.copy(alarmAt = base.startedAt + fitNapMs))
            (max(readyAt(shortened) ?: departAt, now) - departAt - d.marginMs).takeIf { it > LATE_MS }
        } else {
            null
        }
        return next.copy(
            plannedMinutes = if (base.userSetMinutes || !open) base.plannedMinutes else minutes,
            fitsTarget = late <= LATE_MS,
            lateByMs = late.takeIf { d.strict && it > LATE_MS },
            fitNapMinutes = fitMinutes.takeIf { offerFit },
            fitNapLateMs = fitLate,
        )
    }

    /**
     * '시간에 맞춰 N분만 쉬기': 눈 붙이기를 도착 시각에 맞춰 줄인다 (최소 10분).
     * 그 뒤로 남은 길이 바뀌면 알람도 따라 옮긴다.
     */
    fun fitNapToArrival(now: Long) {
        advance(now)
        val rest = state.rest ?: return
        if (rest.kind != RestKind.NAP || rest.fitNapMinutes == null) return
        val chosen = rest.copy(fitNap = true, userSetMinutes = false, previousAlarmAt = null)
        val next = fitted(now, chosen, chosen.remainingRouteMs)
        state = state.copy(rest = next)
        // 몇 분 뒤에 깨우는지는 지금부터 센다 (쉬는 도중에 줄여도 맞게).
        val minutes = ((next.alarmAt - now + MINUTE_MS / 2) / MINUTE_MS).coerceAtLeast(1)
        val target = next.targetAt
        val late = next.lateByMs
        effects += Effect.Speak(
            when {
                late != null -> "${minutes}분 뒤에 깨워 드릴게요. 그래도 도착은 약 ${DriveText.duration(late)} 늦어져요."
                target != null -> "${DriveText.spokenClock(target)} 도착에 맞춰 ${minutes}분 뒤에 깨워 드릴게요."
                else -> "${minutes}분 뒤에 깨워 드릴게요."
            },
        )
        evaluate(now)
    }

    /**
     * 출발하라고 알릴 시각을 다시 계산한다 (도착 시각이 있을 때만).
     * 분 단위로 내려 맞춘다: 화면 시계가 '4:40'일 때 '4:40 출발'과 출발 알림이 함께 맞게 (조금 일찍은 괜찮다).
     */
    private fun withCall(rest: RestSession): RestSession =
        rest.copy(callAt = readyAt(rest)?.let { it / MINUTE_MS * MINUTE_MS })

    /** 출발해도 되는 가장 이른 시각: 출발 시각, 졸려서 쉬는 중이면 조금 쉰 뒤, 눈 붙이기면 깨우는 시간이 끝난 뒤 */
    private fun readyAt(rest: RestSession): Long? {
        val depart = rest.departAt ?: return null
        var at = max(depart, rest.startedAt + minRestMs(rest))
        if (rest.kind == RestKind.NAP) {
            val wakeStart = rest.wakeStartedAt
            val wakeEnd = if (wakeStart != null) {
                wakeStart + rest.wakeMs
            } else {
                rest.alarmAt + RestTiming.wakeMsFor(rest.alarmAt - rest.startedAt, config.wakeUpMs)
            }
            at = max(at, wakeEnd)
        }
        return at
    }

    /** 출발을 재촉하기 전에 쉬어야 하는 시간 (눈 붙이기는 알람이 정하므로 0) */
    private fun minRestMs(rest: RestSession): Long = when {
        rest.kind == RestKind.NAP -> 0L
        rest.warned -> defaultMinutes() * MINUTE_MS
        rest.fatigue -> max(config.fatigueMinRestMs, if (rest.kind == RestKind.STAY) config.minStayMs else 0L)
        rest.kind == RestKind.STAY -> config.minStayMs
        else -> 0L
    }

    /** 지금 곧바로 출발해야 하면 방금 한 말로 출발 알림을 대신한다 (같은 말을 두 번 하지 않게). */
    private fun markStartNotices(now: Long, rest: RestSession): RestSession {
        if (!rest.strictTarget || rest.kind == RestKind.STAY) return rest
        if (rest.kind == RestKind.NAP && rest.wakeStartedAt == null) return rest
        val call = rest.callAt ?: return rest
        if (call > now + MINUTE_MS) return rest
        return rest.copy(
            alarmDone = rest.alarmDone || rest.kind == RestKind.WAIT,
            remindedAt = now,
            departCalledAt = now,
        )
    }

    /** 쉬는 동안 남은 길(정체)이 바뀌면 알람·출발 시각을 다시 맞춘다. 1분 넘게 바뀌면 화면에 옮긴 것을 보여 준다. */
    private fun refit(now: Long) {
        val rest = state.rest ?: return
        val d = deadline
        if (d == null) {
            // 도착 시각이 없으면 도착 예상에 쓰는 남은 길만 고친다 (목적지가 쉼터 자신일 때는 그대로).
            val route = state.remainingRouteMs
            val dest = state.destination
            val at = state.position
            if (route != null && dest != null && at != null && Geo.distanceMeters(dest, at) > 1_000.0) {
                state = state.copy(rest = rest.copy(remainingRouteMs = route))
            }
            return
        }
        val remaining = d.remainingMs ?: state.remainingRouteMs ?: return
        val next = fitted(now, rest, remaining)
        val moved = abs(next.alarmAt - rest.alarmAt) >= MINUTE_MS
        val updated = when {
            next.alarmAt == rest.alarmAt -> next
            moved -> next.copy(previousAlarmAt = rest.alarmAt)
            else -> withCall(next.copy(alarmAt = rest.alarmAt, plannedMinutes = rest.plannedMinutes))
        }
        state = state.copy(rest = withCallMove(rest, updated))
    }

    /**
     * 출발 시각을 기다리는 중(눈 붙이기는 깬 뒤)에 출발 시각이 옮겨졌으면 처음 시각을 남긴다 (화면에 '2:03 → 1:57').
     * 출발할 시각을 이미 알렸으면 남기지 않는다.
     */
    private fun withCallMove(before: RestSession, after: RestSession): RestSession {
        val old = before.callAt
        val new = after.callAt
        val waitingToGo = after.kind != RestKind.STAY && (after.kind != RestKind.NAP || after.wakeStartedAt != null)
        if (!waitingToGo || after.departCalledAt != null || old == null || new == null || old == new) return after
        val from = before.callMovedFrom ?: old
        return after.copy(callMovedFrom = from.takeIf { abs(new - it) >= MINUTE_MS })
    }

    /** 쉬는 중에 도착 목표가 바뀌었다 (+5분·+10분): 출발 알림을 다시 잡고 언제 출발하면 되는지 알린다. */
    private fun retarget(now: Long) {
        val rest = state.rest ?: return
        val d = deadline ?: return
        val nap = rest.kind == RestKind.NAP
        if (rest.alarmRinging && (!nap || rest.lateWarnedAt != null)) effects += Effect.Alarm(false)
        val reset = rest.copy(
            alarmRinging = rest.alarmRinging && nap && rest.lateWarnedAt == null,
            alarmDone = if (nap) rest.alarmDone else false,
            remindedAt = null,
            departCalledAt = null,
            lateWarnedAt = null,
            previousAlarmAt = null,
            callMovedFrom = null,
        )
        lastRefitAt = now
        val remaining = d.remainingMs ?: state.remainingRouteMs ?: rest.remainingRouteMs ?: return
        val next = fitted(now, reset, remaining)
        state = state.copy(rest = markStartNotices(now, next))
        if (!d.strict) return
        val call = next.callAt ?: return
        val old = rest.targetAt
        val head = "도착 목표를 ${DriveText.toward(DriveText.spokenClock(d.targetAt))} ${if (old == null || d.targetAt > old) "늦췄어요" else "바꿨어요"}."
        val tail = when {
            nap && next.wakeStartedAt == null ->
                if (abs(next.alarmAt - rest.alarmAt) >= MINUTE_MS) " 알람을 ${DriveText.toward(DriveText.spokenClock(next.alarmAt))} 옮겼어요." else ""
            next.lateByMs != null -> " 지금 출발해도 약 ${DriveText.duration(next.lateByMs)} 늦어요."
            call > now + MINUTE_MS -> " ${DriveText.spokenClock(call)}에 출발하면 돼요."
            else -> " 지금 출발하면 맞아요."
        }
        effects += Effect.Speak(head + tail)
    }

    private fun startSpeech(now: Long, rest: RestSession): String? {
        val target = rest.targetAt
        val minutes = ((rest.alarmAt - rest.startedAt + MINUTE_MS / 2) / MINUTE_MS).coerceAtLeast(1)
        val call = rest.callAt
        return when (rest.kind) {
            RestKind.NAP -> {
                val late = rest.lateByMs
                val wakeEnd = rest.alarmAt + RestTiming.wakeMsFor(rest.alarmAt - rest.startedAt, config.wakeUpMs)
                when {
                    target != null && rest.strictTarget && late != null -> {
                        val why = if (rest.warned) "주의 경고가 있었으니 충분히 쉬어 가요." else "그래도 안전이 먼저예요."
                        // 늦는 것은 한 번 알리고, 줄일지는 운전자가 화면에서 고른다.
                        val fit = rest.fitNapMinutes?.let { m ->
                            val still = rest.fitNapLateMs
                            if (still == null) " 늦지 않으려면 화면에서 ${m}분으로 줄일 수 있어요."
                            else " 화면에서 ${m}분으로 줄이면 늦는 시간이 약 ${DriveText.duration(still)}으로 줄어요."
                        }.orEmpty()
                        "${minutes}분 쉬면 도착이 약 ${DriveText.duration(late)} 늦어져요. $why ${minutes}분 뒤에 깨워 드릴게요.$fit"
                    }
                    target != null && rest.strictTarget && call != null && call - wakeEnd >= 3 * MINUTE_MS ->
                        "${minutes}분 뒤에 깨워 드릴게요. 출발은 ${DriveText.spokenClock(call)}에 하면 돼요."
                    target != null && rest.strictTarget ->
                        "${DriveText.spokenClock(target)} 도착에 맞춰 ${minutes}분 뒤에 깨워 드릴게요."
                    else -> "휴식을 시작할게요. ${minutes}분 뒤에 알람으로 깨워 드릴게요."
                }
            }
            RestKind.WAIT -> {
                val depart = rest.departAt
                val late = rest.lateByMs
                when {
                    target == null || depart == null || call == null -> "${minutes}분 뒤에 알려 드릴게요."
                    // 졸려서 쉬는 중: 출발 시각이 됐어도 조금은 쉬고 가게 한다.
                    call > max(depart, now) + MINUTE_MS -> {
                        val tail = if (late != null) " 도착은 약 ${DriveText.duration(late)} 늦어져요." else ""
                        "졸릴 때는 조금이라도 쉬어 가요. ${DriveText.spokenClock(call)}까지 쉬고 출발해 주세요.$tail"
                    }
                    late != null -> "지금 출발해도 약 ${DriveText.duration(late)} 늦어요."
                    call <= now + MINUTE_MS -> "지금 출발하면 ${DriveText.spokenClock(target)} 도착에 맞아요."
                    else -> "${DriveText.spokenClock(call)}에 출발하면 ${DriveText.spokenClock(target)} 도착에 맞아요. 출발할 때 알려 드릴게요."
                }
            }
            // 롱웨이 모드: 분위기를 깨지 않게 화면으로만 알린다.
            RestKind.STAY -> null
        }
    }

    /**
     * 쉬기를 끝낸다 (출발).
     * @param arrived 기다리던 곳 바로 옆 목적지에 닿아서 끝났는지. 도착은 시간 모드가 알리므로 말하지 않는다.
     */
    private fun endRest(now: Long, arrived: Boolean = false) {
        val rest = state.rest ?: return
        if (rest.alarmRinging) effects += Effect.Alarm(false)
        wantedMs = 0L
        driveSince = null
        val restedMs = now - rest.startedAt
        // 목적지가 코앞인지 (기다리던 곳 바로 옆): '다시 출발하시네요'를 하지 않는다.
        val destNear = state.destination?.let { dest -> state.position?.let { Geo.distanceMeters(it, dest) <= NEAR_DEST_METERS } } == true
        state = state.copy(
            phase = Phase.DRIVING,
            rest = null,
            target = null,
            targetRemainingMeters = null,
            alternatives = emptyList(),
            prompt = null,
            quiet = false,
            continuousDrivingMs = if (restedMs >= config.restResetMs) 0L else state.continuousDrivingMs,
            recovery = if (config.compareAfterRest) RecoveryCheck(restStartedAt = rest.startedAt, departedAt = now) else null,
        )
        longDriveSnoozeUntil = 0L
        // '출발' 버튼으로 끝냈는데 아직 P단이면, 방금 쉰 자리에서 다시 '여기서 쉬어 갈까요?'를 묻지 않는다.
        parkedPromptShown = true
        // 휴식 환경은 눈 붙이기에서만 바꾼다.
        if (rest.kind == RestKind.NAP) effects += Effect.Cabin(CabinMode.RESTORE)
        if (rest.kind == RestKind.STAY || arrived) return
        val wokeAt = rest.wakeStartedAt
        // 깨우는 알람이 울리는 채로 출발했거나, 깨우는 시간이 아직 안 끝났으면 막 깬 것이다.
        val groggy = rest.kind == RestKind.NAP && restedMs >= RestTiming.NO_WAKE_UNDER_MS &&
            (wokeAt == null || now - wokeAt < rest.wakeMs || (rest.alarmRinging && rest.lateWarnedAt == null))
        // 졸려서 쉬었는데 조금만 쉬고 떠나면 다시 졸릴 때를 일러 둔다.
        val enough = if (rest.warned) defaultMinutes() * MINUTE_MS else config.fatigueMinRestMs
        val shortRest = (rest.fatigue || rest.warned) && restedMs < enough
        val caution = when {
            groggy -> " 깬 지 얼마 안 됐으니 처음 몇 분은 천천히 가 주세요."
            shortRest -> " 많이 쉬지 못했어요. 다시 졸리면 바로 레스트 모드를 눌러 주세요."
            else -> ""
        }
        val d = deadline
        // 시간 모드로 달리는 중이면 어느 길로 가는지는 시간 모드가 알린다.
        val tail = when {
            config.compareAfterRest -> " 처음 ${RestMateText.duration(config.afterWindowMs)} 동안 휴식 전과 주행을 비교해 볼게요."
            d != null || destNear -> ""
            else -> " 원래 가던 길로 다시 안내할게요."
        }
        if ((d?.quiet == true || destNear) && caution.isEmpty() && tail.isEmpty()) return
        effects += Effect.Speak("다시 출발하시네요.$caution$tail")
    }

    private fun evaluateRecovery(now: Long) {
        val rec = state.recovery ?: return
        val afterEnd = rec.departedAt + config.afterWindowMs
        if (rec.result != null || now < afterEnd) return
        val comparison = stability.compare(
            beforeFrom = rec.restStartedAt - config.beforeWindowMs,
            beforeTo = rec.restStartedAt,
            afterFrom = rec.departedAt,
            afterTo = afterEnd,
        )
        val next = if (comparison.verdict == StabilityVerdict.LESS_STABLE) search()?.best else null
        state = state.copy(recovery = rec.copy(result = comparison, nextRest = next))
        effects += Effect.Speak(RestMateText.recoverySpeech(comparison.verdict, next))
    }

    private fun guideToBest(now: Long, announce: Boolean, opening: String = "") {
        if (state.position == null) {
            state = state.copy(searchFailure = "현재 위치를 확인하지 못했어요. 내비게이션 연결을 확인해 주세요.")
            effects += Effect.Speak("${opening}현재 위치를 확인하지 못했어요. 가까운 휴게소에서 쉬어 주세요.")
            return
        }
        val result = search()
        val best = result?.best
        if (destinationFirst(best, opening)) return
        if (result == null || best == null) {
            state = state.copy(searchFailure = "앞쪽에서 쉼터를 찾지 못했어요.")
            effects += Effect.Speak("${opening}앞쪽에서 쉼터를 찾지 못했어요. 가까운 휴게소에서 쉬어 주세요.")
            return
        }
        beginGuidance(now, best, result.candidates.drop(1), announce, opening)
    }

    /**
     * 목적지가 가장 가까운 쉼터만큼 가깝거나 더 가까우면 쉼터로 보내지 않고 목적지에서 쉬라고 한다
     * (목적지를 지나 쉼터에 갔다가 되돌아오지 않게).
     */
    private fun destinationFirst(best: RestAreaCandidate?, opening: String): Boolean {
        val remaining = routeRemainingMs() ?: return false
        val bestMs = best?.let { (it.etaMinutes * MINUTE_MS).toLong() }
        val first = if (bestMs == null) remaining <= 10 * MINUTE_MS else remaining <= bestMs + MINUTE_MS
        if (!first) return false
        state = state.copy(searchFailure = null)
        effects += Effect.Speak("${opening}목적지까지 약 ${DriveText.minutes(remaining)}분이에요. 도착하면 꼭 쉬어 가세요.")
        return true
    }

    /**
     * '더 빨리 쉴래요'·'더 졸려요'·반복 경고: 지금 안내하는 곳보다 빨리 닿는 곳이 있으면 바꾼다.
     * 지금 쉼터가 코앞이면(1.5km·3분 안) 바꾸지 않는다. 시내에서는 쉼터에 닿기 전에 안전한 곳에 먼저 세우라고 한다.
     */
    private fun findFasterStop(now: Long, hurry: Hurry) {
        val current = state.target
        if (current == null) {
            guideToBest(now, announce = true)
            return
        }
        val live = current.updatedFor(state.position, state.speedKmh)
        val currentEta = live.etaMinutes
        val near = (state.targetRemainingMeters ?: live.straightMeters) <= config.nearArrivalMeters || currentEta <= 3.0
        val result = if (near) null else search()
        val faster = result?.candidates?.firstOrNull { it.area.id != current.area.id && it.etaMinutes + 1.0 < currentEta }
        if (result != null && faster != null) {
            beginGuidance(now, faster, result.candidates.filter { it.area.id != faster.area.id }, announce = false)
            effects += Effect.Speak(
                "더 빨리 닿는 ${DriveText.toward(RestMateText.spokenName(faster.area.name))} 바꿀게요. " +
                    "약 ${RestMateText.etaMinutes(faster.etaMinutes)}분이에요.",
            )
            return
        }
        val base = if (near) {
            "지금 안내하는 곳이 가장 가까워요. 곧 도착해요."
        } else {
            "지금 안내하는 곳이 가장 빨리 닿는 쉼터예요. 약 ${RestMateText.etaMinutes(currentEta)}분 남았어요."
        }
        val advice = when {
            near -> ""
            state.onExpressway == false -> " 많이 졸리면 가까운 안전한 곳에 먼저 세우고 '주차했어요'를 눌러 주세요."
            hurry != Hurry.BUTTON -> " 창문을 열어 바람을 쐬면서 쉼터까지만 조심해서 가 주세요."
            else -> ""
        }
        effects += Effect.Speak(base + advice)
    }

    private fun beginGuidance(
        now: Long,
        candidate: RestAreaCandidate,
        alternatives: List<RestAreaCandidate>,
        announce: Boolean,
        opening: String = "",
    ) {
        val position = state.position
        val others = alternatives
            .filter { it.area.id != candidate.area.id }
            .sortedBy { it.updatedFor(position, state.speedKmh).etaMinutes }
            .take(3)
        state = state.copy(
            phase = Phase.TO_REST_AREA,
            target = candidate,
            targetRemainingMeters = position?.let { Geo.distanceMeters(it, candidate.area.position) },
            alternatives = others,
            searchFailure = null,
            prompt = null,
            quiet = false,
        )
        lastCheckIn = now
        guidanceStartedAt = now
        nearArrivalAnnounced = false
        closestToTarget = Double.MAX_VALUE
        effects += Effect.StartGuidance(candidate.area)
        if (announce) {
            effects += Effect.Speak(
                "$opening${RestMateText.spokenName(candidate.area.name)}까지 약 ${RestMateText.etaMinutes(candidate.etaMinutes)}분, " +
                    "안내를 시작했어요. 도착하면 휴식 모드도 준비할게요.",
            )
        }
    }

    /** 안내하던 쉼터 가까이(400m 안)에서 세웠으면 그 쉼터, 아니면 null ('지금 있는 곳') */
    private fun guidedAreaHere(): RestArea? {
        val area = state.target?.area ?: return null
        val at = state.position ?: return area
        return area.takeIf { Geo.distanceMeters(at, it.position) <= AT_AREA_METERS }
    }

    private fun search(): RestAreaSearchResult? {
        val position = state.position ?: return null
        return finder.findAhead(
            position = position,
            headingDeg = state.headingDeg,
            destination = state.destination,
            speedKmh = state.speedKmh,
            expresswayOnly = state.onExpressway == true,
            surface = state.onExpressway == false,
        )
    }

    /** 처음 잡는 쉴 시간: 시내(일반 도로)는 조금 짧게 */
    private fun defaultMinutes(): Int =
        if (state.onExpressway == false) config.cityRestMinutes else config.defaultRestMinutes

    private fun estimateRemainingRouteMs(): Long? {
        val position = state.position ?: return null
        val destination = state.destination ?: return null
        val km = Geo.distanceMeters(position, destination) / 1_000.0 * 1.25
        val kmh = if (state.onExpressway == false) RestAreaFinder.SURFACE_SPEED_KMH else 90.0
        return (km / kmh * HOUR_MS).toLong()
    }
}
