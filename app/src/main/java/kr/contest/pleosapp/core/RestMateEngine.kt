package kr.contest.pleosapp.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** 목적지 도착 안내 앞뒤로 이 시간 안에 세운 차는 도착해서 세운 것으로 본다 */
private const val ARRIVAL_PARK_WINDOW_MS = 5 * MINUTE_MS

/**
 * 레스트메이트의 상태 전환을 맡는다. 안드로이드에 의존하지 않고, 시간은 밖에서 넣어 준다.
 *
 * 주행 → (졸려요·주의 경고·연속 주행 2시간·휴식 공백) → 쉼터 이동 → (P단 주차 확정) → 휴식
 * → (출발) → 주행하면서 휴식 전후 주행 비교
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
    private var longDriveSnoozeUntil = 0L
    private val gapAdvisedIds = HashSet<String>()
    private var lastAheadSearch = -1L
    private var lastCheckIn = 0L
    private var nearArrivalAnnounced = false
    private var closestToTarget = Double.MAX_VALUE
    private var lastPosition: LatLng? = null
    private var lastPrune = 0L
    private var tripArrivedAt = Long.MIN_VALUE / 2

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

    /** 목적지에 도착했다 (내비 도착 안내). 도착해서 세운 차에는 '여기서 쉬어 갈까요?'를 묻지 않는다. */
    fun onTripArrived(now: Long) {
        tripArrivedAt = now
        if (state.prompt == Prompt.ParkedHere) state = state.copy(prompt = null)
    }

    /** 시연용으로 고속도로 주행 여부를 직접 정한다. */
    fun setOnExpressway(on: Boolean?) {
        state = state.copy(onExpressway = on)
    }

    fun onRemainingRoute(ms: Long?) {
        state = state.copy(remainingRouteMs = ms)
    }

    fun onSteeringAngle(now: Long, degrees: Double) = stability.onSteeringAngle(now, degrees)

    fun onBrake(now: Long, pressed: Boolean) = stability.onBrake(now, pressed)

    fun onTurnSignal(now: Long, on: Boolean) = stability.onTurnSignal(now, on)

    /** 차량의 운전자 주의 경고. 묻지 않고 바로 쉼터 안내로 넘어간다. */
    fun onAttentionWarning(now: Long) {
        advance(now)
        stability.onWarning(now)
        when (state.phase) {
            Phase.DRIVING -> {
                effects += Effect.Speak("운전자 주의 경고가 있었어요. 가장 빨리 닿는 쉼터를 안내할게요.")
                guideToBest(now, announce = false)
            }
            Phase.TO_REST_AREA -> findFasterStop(now)
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

    /** '졸려요' 버튼. 묻지 않고 바로 쉼터 안내로 넘어간다. */
    fun pressDrowsy(now: Long) {
        advance(now)
        when (state.phase) {
            Phase.DRIVING -> guideToBest(now, announce = true)
            Phase.TO_REST_AREA -> findFasterStop(now)
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
            CheckInAnswer.MORE_DROWSY -> findFasterStop(now)
            CheckInAnswer.QUIET -> state = state.copy(quiet = true)
        }
    }

    fun acceptPrompt(now: Long) {
        advance(now)
        val prompt = state.prompt ?: return
        state = state.copy(prompt = null)
        when (prompt) {
            is Prompt.LongDrive -> guideToBest(now, announce = true)
            is Prompt.RestGapAhead -> beginGuidance(now, prompt.next, emptyList(), announce = true)
            Prompt.ParkedHere -> startRest(now)
            Prompt.CheckIn -> lastCheckIn = now
        }
    }

    fun dismissPrompt(now: Long) {
        advance(now)
        val prompt = state.prompt ?: return
        state = state.copy(prompt = null)
        when (prompt) {
            is Prompt.LongDrive -> longDriveSnoozeUntil = now + config.longDriveSnoozeMs
            is Prompt.RestGapAhead -> Unit
            Prompt.ParkedHere -> Unit
            Prompt.CheckIn -> lastCheckIn = now
        }
    }

    /** 휴식 시작. P단 주차가 확정되면 자동으로 불리고, '주차했어요' 버튼으로도 부른다. */
    fun startRest(now: Long) {
        advance(now)
        if (state.phase == Phase.RESTING) return
        val planned = config.defaultRestMinutes
        val navRemaining = state.remainingRouteMs
        val remaining = navRemaining ?: estimateRemainingRouteMs()
        state = state.copy(
            phase = Phase.RESTING,
            prompt = null,
            quiet = false,
            recovery = null,
            rest = RestSession(
                startedAt = now,
                plannedMinutes = planned,
                alarmAt = now + planned * MINUTE_MS,
                area = state.target?.area,
                remainingRouteMs = remaining,
                remainingRouteEstimated = navRemaining == null && remaining != null,
            ),
        )
        effects += Effect.Cabin(CabinMode.REST)
        effects += Effect.Speak("휴식을 시작할게요. ${planned}분 뒤에 알람으로 알려 드릴게요.")
    }

    /**
     * 딱 맞춰 가요의 '쉬다 가는 코스'에서 쉼터에 주차했을 때: 도착 시각에 맞춰 깨우도록 알람 시각을 정해 쉰다.
     */
    fun startRestUntil(now: Long, alarmAt: Long, area: RestArea?) {
        advance(now)
        if (state.phase == Phase.RESTING) return
        val at = max(alarmAt, now + MINUTE_MS)
        val minutes = ((at - now + MINUTE_MS - 1) / MINUTE_MS).toInt()
        state = state.copy(
            phase = Phase.RESTING,
            prompt = null,
            quiet = false,
            recovery = null,
            target = null,
            targetRemainingMeters = null,
            alternatives = emptyList(),
            rest = RestSession(
                startedAt = now,
                plannedMinutes = minutes,
                alarmAt = at,
                area = area,
                remainingRouteMs = state.remainingRouteMs,
            ),
        )
        effects += Effect.Cabin(CabinMode.REST)
        effects += Effect.Speak("도착 시각에 맞춰 쉬다 가요. ${minutes}분 뒤에 깨워 드릴게요.")
    }

    fun setRestMinutes(now: Long, minutes: Int) {
        advance(now)
        val rest = state.rest ?: return
        if (rest.alarmRinging) effects += Effect.Alarm(false)
        if (rest.wakeStartedAt != null) effects += Effect.Cabin(CabinMode.REST)
        state = state.copy(
            rest = rest.copy(
                plannedMinutes = minutes,
                alarmAt = rest.startedAt + minutes * MINUTE_MS,
                alarmRinging = false,
                alarmDone = false,
                wakeStartedAt = null,
            ),
        )
        evaluate(now)
    }

    fun extendRest(now: Long, minutes: Int) {
        advance(now)
        val rest = state.rest ?: return
        if (rest.alarmRinging) effects += Effect.Alarm(false)
        if (rest.wakeStartedAt != null) effects += Effect.Cabin(CabinMode.REST)
        val alarmAt = max(rest.alarmAt, now) + minutes * MINUTE_MS
        state = state.copy(
            rest = rest.copy(
                plannedMinutes = ((alarmAt - rest.startedAt) / MINUTE_MS).toInt(),
                alarmAt = alarmAt,
                alarmRinging = false,
                alarmDone = false,
                wakeStartedAt = null,
            ),
        )
    }

    fun stopAlarm(now: Long) {
        advance(now)
        val rest = state.rest ?: return
        if (rest.alarmRinging) effects += Effect.Alarm(false)
        state = state.copy(rest = rest.copy(alarmRinging = false, alarmDone = true))
    }

    /** 알람 전에 깼을 때 '일어났어요'. 남은 알람은 끄고 깨우기를 시작한다. */
    fun wakeUp(now: Long) {
        advance(now)
        val rest = state.rest ?: return
        if (rest.wakeStartedAt != null) return
        if (rest.alarmRinging) effects += Effect.Alarm(false)
        state = state.copy(rest = rest.copy(alarmRinging = false, alarmDone = true, wakeStartedAt = now))
        startWakeUp(byAlarm = false)
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

    /** 시연용: 알람 시각을 지금으로 당긴다. */
    fun skipToAlarm(now: Long) {
        advance(now)
        val rest = state.rest ?: return
        state = state.copy(rest = rest.copy(alarmAt = now, alarmDone = false))
        evaluate(now)
    }

    /** 시연용: 연속 주행 시간을 더한다. */
    fun addDrivingTime(now: Long, ms: Long) {
        advance(now)
        state = state.copy(continuousDrivingMs = state.continuousDrivingMs + ms)
        longDriveSnoozeUntil = 0L
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
                if (!parkedPromptShown && state.prompt == null && !atDestination) {
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
            if (result != null) maybeAdviseGap(result)
        }
        if (state.prompt == null && isDriving() &&
            state.continuousDrivingMs >= config.longDriveMs && now >= longDriveSnoozeUntil
        ) {
            val hours = state.continuousDrivingMs / HOUR_MS
            state = state.copy(
                prompt = Prompt.LongDrive((state.continuousDrivingMs / MINUTE_MS).toInt(), state.nextAhead),
            )
            longDriveSnoozeUntil = now + config.longDriveSnoozeMs
            effects += Effect.Speak("${hours}시간 넘게 운전하셨어요. 다음 쉼터에 들를까요?")
        }
    }

    /** 앞 쉼터를 지나치면 한참 쉴 곳이 없을 때, 그 앞에서 미리 묻는다. */
    private fun maybeAdviseGap(result: RestAreaSearchResult) {
        if (state.prompt != null || !isDriving()) return
        if (state.continuousDrivingMs < config.gapAdviceMinDriveMs) return
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
                    effects += Effect.Speak("곧 진입 안내가 나와요. 잠시 조용히 할게요.")
                }
                return
            }
        }
        if (!state.quiet && state.prompt == null && now - lastCheckIn >= config.checkInIntervalMs) {
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
        if ((parkedNow && !rest.sawPark) || (stillNow && !rest.sawStill)) {
            rest = rest.copy(sawPark = rest.sawPark || parkedNow, sawStill = rest.sawStill || stillNow)
            state = state.copy(rest = rest)
        }
        // P단을 확인한 뒤 D단으로 바꾸면 출발 (주차 자리를 고치는 R단은 출발로 보지 않는다).
        // 기어 정보가 없으면 한 번 멈춘 뒤 다시 달리는지로 판단한다.
        val shifted = rest.sawPark && state.gear == Gear.DRIVE
        val rolling = rest.sawStill && (state.speedKmh ?: 0.0) >= 10.0 && !parkedNow
        if (shifted || rolling) {
            endRest(now)
            return
        }
        if (!rest.alarmRinging && !rest.alarmDone && now >= rest.alarmAt) {
            state = state.copy(rest = rest.copy(alarmRinging = true, wakeStartedAt = rest.wakeStartedAt ?: now))
            effects += Effect.Alarm(true)
            startWakeUp(byAlarm = true)
        }
    }

    /** 깨우기: 차 환경을 바꾸고, 깬 뒤 바로 출발하지 않도록 안내한다 (수면 관성). */
    private fun startWakeUp(byAlarm: Boolean) {
        effects += Effect.Cabin(CabinMode.WAKE)
        val opening = if (byAlarm) "휴식 시간이 끝났어요." else "잘 쉬셨어요."
        effects += Effect.Speak(
            "$opening 무드등을 밝히고 시원한 바람을 넣을게요. " +
                "깬 직후에는 멍할 수 있으니 ${RestMateText.duration(config.wakeUpMs)} 정도 몸을 깨운 뒤 출발해 주세요.",
        )
    }

    private fun endRest(now: Long) {
        val rest = state.rest ?: return
        if (rest.alarmRinging) effects += Effect.Alarm(false)
        val restedMs = now - rest.startedAt
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
        effects += Effect.Cabin(CabinMode.RESTORE)
        val wokeAt = rest.wakeStartedAt
        val groggy = wokeAt == null || now - wokeAt < config.wakeUpMs
        val caution = if (groggy) " 깬 지 얼마 안 됐으니 처음 몇 분은 천천히 가 주세요." else ""
        val tail = if (config.compareAfterRest) {
            " 처음 ${RestMateText.duration(config.afterWindowMs)} 동안 휴식 전과 주행을 비교해 볼게요."
        } else {
            " 원래 가던 길로 다시 안내할게요."
        }
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

    private fun guideToBest(now: Long, announce: Boolean) {
        if (state.position == null) {
            state = state.copy(searchFailure = "현재 위치를 확인하지 못했어요. 내비게이션 연결을 확인해 주세요.")
            effects += Effect.Speak("현재 위치를 확인하지 못했어요. 가까운 휴게소에서 쉬어 주세요.")
            return
        }
        val result = search()
        val best = result?.best
        if (result == null || best == null) {
            state = state.copy(searchFailure = "앞쪽에서 쉼터를 찾지 못했어요.")
            effects += Effect.Speak("앞쪽에서 쉼터를 찾지 못했어요. 가까운 휴게소에서 쉬어 주세요.")
            return
        }
        beginGuidance(now, best, result.candidates.drop(1), announce)
    }

    /** '더 졸려요'나 반복 경고: 지금 안내하는 곳보다 빨리 닿는 곳이 있으면 바꾼다. */
    private fun findFasterStop(now: Long) {
        val current = state.target
        if (current == null) {
            guideToBest(now, announce = true)
            return
        }
        val result = search() ?: return
        val currentEta = result.candidates.firstOrNull { it.area.id == current.area.id }?.etaMinutes
            ?: Double.MAX_VALUE
        val faster = result.candidates.firstOrNull { it.area.id != current.area.id && it.etaMinutes + 1.0 < currentEta }
        if (faster != null) {
            effects += Effect.Speak("더 빨리 정차할 수 있는 곳을 찾을게요.")
            beginGuidance(now, faster, result.candidates.filter { it.area.id != faster.area.id }, announce = true)
        } else {
            val minutes = if (currentEta < Double.MAX_VALUE) RestMateText.etaMinutes(currentEta) else null
            val tail = if (minutes != null) " 약 ${minutes}분 남았어요." else ""
            effects += Effect.Speak("지금 안내하는 곳이 가장 빨리 닿는 쉼터예요.$tail")
        }
    }

    private fun beginGuidance(
        now: Long,
        candidate: RestAreaCandidate,
        alternatives: List<RestAreaCandidate>,
        announce: Boolean,
    ) {
        state = state.copy(
            phase = Phase.TO_REST_AREA,
            target = candidate,
            targetRemainingMeters = state.position?.let { Geo.distanceMeters(it, candidate.area.position) },
            alternatives = alternatives.filter { it.area.id != candidate.area.id }.take(3),
            searchFailure = null,
            prompt = null,
            quiet = false,
        )
        lastCheckIn = now
        nearArrivalAnnounced = false
        closestToTarget = Double.MAX_VALUE
        effects += Effect.StartGuidance(candidate.area)
        if (announce) {
            effects += Effect.Speak(
                "${RestMateText.spokenName(candidate.area.name)}까지 약 ${RestMateText.etaMinutes(candidate.etaMinutes)}분, " +
                    "안내를 시작했어요. 도착하면 휴식 모드도 준비할게요.",
            )
        }
    }

    private fun search(): RestAreaSearchResult? {
        val position = state.position ?: return null
        return finder.findAhead(
            position = position,
            headingDeg = state.headingDeg,
            destination = state.destination,
            speedKmh = state.speedKmh,
            expresswayOnly = state.onExpressway == true,
        )
    }

    private fun estimateRemainingRouteMs(): Long? {
        val position = state.position ?: return null
        val destination = state.destination ?: return null
        val km = Geo.distanceMeters(position, destination) / 1_000.0 * 1.25
        return (km / 90.0 * HOUR_MS).toLong()
    }
}
