package kr.contest.pleosapp.core

import kotlin.math.abs
import kotlin.math.max

/**
 * '딱 맞춰 가요'와 '좀 더 달려요'의 상태 전환과 도착 시각 맞추기. 안드로이드에 의존하지 않고, 시간은 밖에서 넣어 준다.
 *
 * 홈 → 설정(목적지·시각·분위기) → 코스 후보 → 주행(내비 남은 시간으로 계속 맞춤) → 도착
 *
 * - 늦지 않는 게 먼저: 늦을 것 같으면 경유지를 바로 뺀다.
 * - 일찍 도착할 것 같으면 '조금 더 돌기'를 권한다 (자동으로 넣지는 않는다).
 * - '쉬었다 가요'가 진행 중이면 시간 맞추기를 멈췄다가, 다시 출발하면 이어서 맞춘다.
 *
 * 내비 경로 요청·음성·실내 분위기처럼 밖에서 할 일은 [DriveEffect]로 쌓아 두고 앱이 [drainEffects]로 가져간다.
 */
class DriveEngine(
    private val points: List<CoursePoint>,
    private val restPlaces: List<RestArea>,
    val config: DriveConfig = DriveConfig(),
) {
    var state = DriveState()
        private set

    private val effects = ArrayList<DriveEffect>()
    private var askedDirectAt: Long? = null
    private var lateHandled = false
    private var routeChangedAt = Long.MIN_VALUE / 2
    private var moodApplied = false
    private var planFrom: LatLng? = null
    private var planDirectMs: Long? = null
    /** 쉬다 가는 코스에서 쉴 곳 가까이까지 왔는지 */
    private var restStopReached = false

    fun drainEffects(): List<DriveEffect> {
        if (effects.isEmpty()) return emptyList()
        val out = effects.toList()
        effects.clear()
        return out
    }

    // ---- 차량·내비 입력 ----

    fun onTick(now: Long) {
        state = state.copy(now = now)
        when (state.step) {
            DriveStep.SETUP -> {
                val asked = askedDirectAt
                if (state.directMs == null && asked != null && now - asked >= config.directTimeWaitMs) useEstimatedDirect()
                if (state.planPending && state.directMs != null) makeOptions(now)
            }
            DriveStep.ACTIVE -> track(now)
            else -> Unit
        }
    }

    fun onLocation(now: Long, position: LatLng) {
        state = state.copy(now = now, position = position)
        if (state.step == DriveStep.ACTIVE) track(now)
    }

    /** 내비의 지금 목적지와 남은 시간 (경유지 포함). 목적지가 없으면 null */
    fun onNaviDestination(now: Long, place: Place?, remainingMs: Long?) {
        val remain = remainingMs?.takeIf { it > 0 }
        state = state.copy(now = now, naviDestination = place, naviRemainingMs = if (place == null) null else remain)
        if (state.step != DriveStep.SETUP) return
        val dest = state.destination
        // 고른 목적지가 내비의 지금 목적지면 내비 남은 시간을 바로 가는 시간으로 쓴다.
        if (dest != null && remain != null && dest.sameSpot(place) && (state.directMs == null || state.directEstimated)) {
            state = state.copy(directMs = remain, directEstimated = false)
        }
    }

    /** 내비에 물은 '바로 가면 걸리는 시간' 답 */
    fun onDirectTime(now: Long, destination: Place, ms: Long) {
        if (state.step != DriveStep.SETUP || ms <= 0) return
        if (!destination.sameSpot(state.destination)) return
        if (state.directMs != null && !state.directEstimated) return
        state = state.copy(now = now, directMs = ms, directEstimated = false)
        if (state.planPending) makeOptions(now)
    }

    fun onPlaces(places: List<Place>) {
        state = state.copy(places = places)
    }

    fun onWaypointArrived(now: Long) {
        if (state.step != DriveStep.ACTIVE || !state.viaActive) return
        if (state.chosen?.kind == CourseKind.REST_STOP) {
            // 쉬다 가는 곳: 주차해서 휴식을 시작할 때까지 쉬는 시간을 계속 셈에 넣는다.
            restStopReached = true
            state = state.copy(now = now, message = "쉬다 갈 곳에 왔어요. P단으로 주차하면 도착 시각에 맞춰 깨워 드릴게요.")
            return
        }
        state = state.copy(now = now, viaCleared = true, message = "경유지를 지났어요. 이제 목적지로 가요.")
    }

    /** 쉬다 가는 코스에서 지금 쉴 곳에 와 있으면 그곳. 아니면 null (앱이 주차를 보고 휴식을 시작할 때 쓴다) */
    fun restStopHere(position: LatLng?): RestArea? {
        if (state.step != DriveStep.ACTIVE || !state.viaActive || state.paused) return null
        val option = state.chosen ?: return null
        if (option.kind != CourseKind.REST_STOP) return null
        val area = option.restArea ?: return null
        val at = position ?: return null
        return area.takeIf { Geo.distanceMeters(at, it.position) <= REST_STOP_NEAR_METERS }
    }

    fun onDestinationArrived(now: Long) {
        if (state.step == DriveStep.ACTIVE) arrive(now)
    }

    /** '쉬었다 가요'가 진행 중인지 (쉼터로 가는 중이거나 쉬는 중). 진행 중에는 시간 맞추기를 멈춘다. */
    fun onRestActive(now: Long, active: Boolean) {
        if (state.paused == active) return
        state = state.copy(now = now, paused = active)
        if (state.step != DriveStep.ACTIVE) return
        if (active) {
            // 쉼터가 경로의 경유지를 대신하므로, 고른 경유지는 지난 것으로 본다.
            state = state.copy(viaCleared = true, extendOffered = false, message = "쉬었다 가는 동안 시간 맞추기를 잠시 멈춰요.")
        } else {
            lateHandled = false
            routeChangedAt = now
            state = state.copy(message = "다시 출발했어요. 도착 시각을 이어서 맞출게요.")
            // 쉬는 동안 실내 환경을 휴식용으로 바꿨다가 되돌렸으므로 분위기를 다시 켠다.
            if (moodApplied) effects += DriveEffect.CabinMood(state.mood)
            track(now)
        }
    }

    // ---- 화면 조작 ----

    fun openMode(now: Long, mode: DriveMode) {
        lateHandled = false
        askedDirectAt = null
        val dest = defaultDestination(mode)
        state = state.copy(
            now = now,
            step = DriveStep.SETUP,
            mode = mode,
            destination = dest,
            targetAt = null,
            extraMinutes = DEFAULT_EXTRA_MINUTES,
            directMs = null,
            directEstimated = false,
            planPending = false,
            options = emptyList(),
            chosen = null,
            viaCleared = false,
            arrivalEstimate = null,
            status = FitStatus.UNKNOWN,
            diffMs = null,
            extendOffered = false,
            message = null,
            arrivedAt = null,
        )
        if (dest != null) prepareDirect(now, dest)
        if (mode == DriveMode.ON_TIME) state = state.copy(targetAt = defaultTarget(now))
    }

    fun setDestination(now: Long, place: Place) {
        if (state.step != DriveStep.SETUP) return
        state = state.copy(now = now, destination = place, directMs = null, directEstimated = false)
        prepareDirect(now, place)
    }

    /** 도착 목표 시각을 분 단위로 옮긴다. 지금부터 5분 뒤보다 이르게는 못 한다. */
    fun shiftTarget(now: Long, minutes: Int) {
        if (state.step != DriveStep.SETUP || state.mode != DriveMode.ON_TIME) return
        val current = state.targetAt ?: defaultTarget(now)
        val earliest = roundUp(now + 5 * MINUTE_MS, FIVE_MINUTES)
        state = state.copy(targetAt = max(earliest, current + minutes * MINUTE_MS))
    }

    fun setExtraMinutes(minutes: Int) {
        if (state.step == DriveStep.SETUP) state = state.copy(extraMinutes = minutes.coerceIn(5, 180))
    }

    fun setMood(mood: Mood) {
        state = state.copy(mood = mood)
    }

    fun setStyle(style: RoadStyle) {
        state = state.copy(style = style)
    }

    /** '코스 찾기'. 바로 가는 시간을 아직 모르면 답을 기다렸다가 (없으면 추정해서) 후보를 낸다. */
    fun plan(now: Long) {
        if (state.step != DriveStep.SETUP) return
        val dest = state.destination ?: return
        if (state.directMs == null) {
            state = state.copy(planPending = true)
            if (askedDirectAt == null) prepareDirect(now, dest)
            return
        }
        makeOptions(now)
    }

    fun choose(now: Long, option: CourseOption) {
        if (state.step != DriveStep.CHOOSE) return
        val dest = state.destination ?: return
        lateHandled = false
        restStopReached = false
        routeChangedAt = now
        state = state.copy(
            now = now,
            step = DriveStep.ACTIVE,
            chosen = option,
            options = emptyList(),
            viaCleared = option.via.isEmpty(),
            status = FitStatus.UNKNOWN,
            diffMs = null,
            arrivalEstimate = null,
            extendOffered = false,
            arrivedAt = null,
            message = startMessage(option),
        )
        effects += DriveEffect.Route(dest, option.via)
        if (state.mode == DriveMode.LONGER && !moodApplied) {
            moodApplied = true
            effects += DriveEffect.CabinMood(state.mood)
        }
        effects += DriveEffect.Speak(startSpeech(option))
        track(now)
    }

    /** '바로 가기': 후보 화면에서는 경유지 없이 출발, 주행 중에는 남은 경유지를 뺀다. */
    fun goDirect(now: Long) {
        when (state.step) {
            DriveStep.CHOOSE -> {
                val dest = state.destination ?: return
                val target = state.targetAt
                val direct = state.directMs ?: 0L
                val slack = if (target != null) TimeFitPlanner.slackMs(now, target, direct, config) else 0L
                if (state.chosen == null) {
                    choose(now, TimeFitPlanner.direct(slack))
                } else {
                    // '조금 더 돌기'에서 마음을 바꿈: 지금 경로 그대로 간다.
                    state = state.copy(step = DriveStep.ACTIVE, options = emptyList(), destination = dest)
                }
            }
            DriveStep.ACTIVE -> {
                val dest = state.destination ?: return
                if (!state.viaActive) return
                routeChangedAt = now
                effects += DriveEffect.Route(dest, emptyList())
                effects += DriveEffect.Speak("경유지를 빼고 목적지로 바로 갈게요.")
                state = state.copy(viaCleared = true, extendOffered = false, message = "경유지를 빼고 목적지로 바로 가요.")
            }
            else -> Unit
        }
    }

    /** 일찍 도착할 것 같을 때 '조금 더 돌기': 지금 위치에서 남은 여유로 코스를 다시 고른다. */
    fun extend(now: Long) {
        if (state.step != DriveStep.ACTIVE) return
        val dest = state.destination ?: return
        val target = state.targetAt ?: return
        val from = state.position ?: return
        val remain = remainingMs() ?: return
        val slack = target - now - remain - config.marginMs
        val options = TimeFitPlanner.options(from, dest, remain, slack, points, stopsFor(state.mode), style(), config)
            .filter { it.kind != CourseKind.DIRECT }
        if (options.isEmpty()) {
            state = state.copy(extendOffered = false, message = "더 돌 만한 코스가 없어요. 그대로 갈게요.")
            return
        }
        planFrom = from
        planDirectMs = remain
        state = state.copy(now = now, step = DriveStep.CHOOSE, options = options, extendOffered = false)
    }

    fun back(now: Long) {
        when (state.step) {
            DriveStep.CHOOSE -> state = if (state.chosen != null) {
                state.copy(step = DriveStep.ACTIVE, options = emptyList())
            } else {
                state.copy(step = DriveStep.SETUP, options = emptyList(), planPending = false)
            }
            DriveStep.SETUP -> reset()
            DriveStep.ACTIVE, DriveStep.ARRIVED -> finish(now)
            DriveStep.HOME -> Unit
        }
    }

    /** 드라이브를 끝내고 처음 화면으로. 바꿔 둔 분위기는 되돌린다. */
    fun finish(now: Long) {
        restoreMood()
        reset()
        state = state.copy(now = now)
    }

    /**
     * 쉬다 가는 코스에서 쉼터에 주차했을 때 깨울 시각.
     * 도착 목표 − 남은 길 − 여유 − 깬 뒤 몸을 깨우는 시간. 쉬다 가는 코스가 아니면 null
     */
    fun restStopAlarmAt(now: Long, wakeUpMs: Long): Long? {
        if (state.step != DriveStep.ACTIVE) return null
        val option = state.chosen ?: return null
        if (option.kind != CourseKind.REST_STOP) return null
        val dest = state.destination ?: return null
        val target = state.targetAt ?: return null
        val from = state.position ?: option.via.firstOrNull()?.position ?: return null
        val speed = TimeFitPlanner.effectiveSpeedMps(planFrom ?: from, dest.position, planDirectMs, config)
        val driveMs = (Geo.distanceMeters(from, dest.position) * config.detourFactor / speed * 1000.0).toLong()
        return max(target - driveMs - config.marginMs - wakeUpMs, now + MIN_REST_MS)
    }

    // ---- 내부 ----

    private fun defaultDestination(mode: DriveMode): Place? {
        val current = state.naviDestination?.copy(kind = PlaceKind.CURRENT)
        return when (mode) {
            DriveMode.ON_TIME -> current ?: state.places.firstOrNull()
            DriveMode.LONGER -> current ?: state.places.firstOrNull { it.kind == PlaceKind.HOME } ?: state.places.firstOrNull()
        }
    }

    /** 바로 가는 시간: 내비의 지금 목적지면 남은 시간을 쓰고, 아니면 내비에 묻는다. */
    private fun prepareDirect(now: Long, dest: Place) {
        val remain = state.naviRemainingMs
        if (remain != null && dest.sameSpot(state.naviDestination)) {
            askedDirectAt = null
            state = state.copy(directMs = remain, directEstimated = false)
        } else {
            askedDirectAt = now
            effects += DriveEffect.AskDirectTime(dest)
        }
    }

    private fun useEstimatedDirect() {
        val estimate = estimateDirect() ?: return
        askedDirectAt = null
        state = state.copy(directMs = estimate, directEstimated = true)
    }

    private fun estimateDirect(): Long? {
        val from = state.position ?: return null
        val dest = state.destination ?: return null
        return TimeFitPlanner.estimateDirectMs(from, dest.position, config)
    }

    private fun defaultTarget(now: Long): Long {
        val direct = state.directMs ?: estimateDirect() ?: DEFAULT_DIRECT_MS
        return roundUp(now + direct + DEFAULT_SPARE_MS, FIVE_MINUTES)
    }

    private fun style(): RoadStyle = if (state.mode == DriveMode.LONGER) RoadStyle.SCENIC else state.style

    /** 좀 더 달려요는 달리는 시간이 목적이라 쉬다 가는 코스를 내지 않는다. */
    private fun stopsFor(mode: DriveMode?): List<RestArea> = if (mode == DriveMode.LONGER) emptyList() else restPlaces

    private fun makeOptions(now: Long) {
        val dest = state.destination ?: return
        val direct = state.directMs ?: return
        val target = when (state.mode) {
            DriveMode.LONGER -> now + direct + state.extraMinutes * MINUTE_MS
            else -> state.targetAt ?: defaultTarget(now)
        }
        val slack = when (state.mode) {
            DriveMode.LONGER -> state.extraMinutes * MINUTE_MS
            else -> TimeFitPlanner.slackMs(now, target, direct, config)
        }
        val from = state.position
        planFrom = from
        planDirectMs = direct
        val options = if (from == null) {
            listOf(TimeFitPlanner.direct(slack))
        } else {
            TimeFitPlanner.options(from, dest, direct, slack, points, stopsFor(state.mode), style(), config)
        }
        val note = when {
            from == null -> "현재 위치를 몰라 바로 가는 코스만 보여요."
            state.directEstimated -> "내비 예상 시간을 받지 못해 거리로 추정했어요."
            else -> null
        }
        state = state.copy(
            now = now,
            step = DriveStep.CHOOSE,
            targetAt = target,
            options = options,
            planPending = false,
            message = note,
        )
    }

    private fun track(now: Long) {
        val dest = state.destination ?: return
        val target = state.targetAt ?: return
        val position = state.position
        if (position != null && Geo.distanceMeters(position, dest.position) <= config.arriveMeters) {
            arrive(now)
            return
        }
        val naviRemain = state.naviRemainingMs?.takeIf { dest.sameSpot(state.naviDestination) }
        if (naviRemain != null && naviRemain <= ARRIVE_REMAIN_MS && !state.viaActive) {
            arrive(now)
            return
        }
        checkRestStopPassed(position)
        val settled = now - routeChangedAt >= ROUTE_SETTLE_MS
        // 경로를 막 바꿨으면 내비가 새 경로를 계산할 때까지 계획한 값으로 보여 준다.
        val remain = if (settled) remainingMs() else plannedRemainingMs(now) ?: remainingMs()
        if (remain == null) return
        val eta = now + remain
        val diff = eta - target
        val status = when {
            diff > config.lateToleranceMs -> FitStatus.LATE
            diff < -config.earlyToleranceMs -> FitStatus.EARLY
            else -> FitStatus.ON_TIME
        }
        state = state.copy(arrivalEstimate = eta, diffMs = diff, status = status)
        if (state.paused || !settled) return
        when {
            status == FitStatus.LATE && state.viaActive && !lateHandled -> {
                lateHandled = true
                routeChangedAt = now
                effects += DriveEffect.Route(dest, emptyList())
                effects += DriveEffect.Speak("길이 막혀서 경유지를 빼고 바로 갈게요. 늦지 않는 게 먼저예요.")
                state = state.copy(
                    viaCleared = true,
                    extendOffered = false,
                    message = "${DriveText.minutes(diff)}분 늦을 것 같아 경유지를 빼고 바로 가요.",
                )
            }
            status == FitStatus.EARLY && !state.viaActive && !state.extendOffered && remain >= config.extendMinRemainMs -> {
                state = state.copy(
                    extendOffered = true,
                    message = "${DriveText.minutes(diff)}분 일찍 도착할 것 같아요. '조금 더 돌기'로 시간을 맞출 수 있어요.",
                )
            }
            status != FitStatus.EARLY && state.extendOffered -> state = state.copy(extendOffered = false)
        }
    }

    /** 쉬다 가는 곳까지 왔다가 쉬지 않고 떠나면 쉬는 시간을 셈에서 뺀다. */
    private fun checkRestStopPassed(position: LatLng?) {
        val area = state.chosen?.restArea ?: return
        if (!state.viaActive || state.paused || position == null) return
        val d = Geo.distanceMeters(position, area.position)
        if (d <= REST_STOP_NEAR_METERS) {
            restStopReached = true
        } else if (restStopReached && d > REST_STOP_LEFT_METERS) {
            restStopReached = false
            state = state.copy(viaCleared = true, message = "쉬지 않고 지나왔어요. 도착 시각을 다시 맞춰 볼게요.")
        }
    }

    /** 내비 남은 시간 (목적지가 같을 때). 없으면 남은 경로를 직선거리로 추정한다. 쉬다 가는 코스는 쉬는 시간을 더한다. */
    private fun remainingMs(): Long? {
        val dest = state.destination ?: return null
        val stay = if (state.viaActive) state.chosen?.stayMs ?: 0L else 0L
        val navi = state.naviRemainingMs
        if (navi != null && dest.sameSpot(state.naviDestination)) return navi + stay
        val position = state.position ?: return null
        val via = if (state.viaActive) state.chosen?.via.orEmpty() else emptyList()
        var meters = 0.0
        var from = position
        for (p in via) {
            meters += Geo.distanceMeters(from, p.position)
            from = p.position
        }
        meters += Geo.distanceMeters(from, dest.position)
        val speed = TimeFitPlanner.effectiveSpeedMps(planFrom ?: position, dest.position, planDirectMs, config)
        return (meters * config.detourFactor / speed * 1000.0).toLong() + stay
    }

    /** 코스를 고를 때 계획한 남은 시간 (경로를 막 바꿔 내비 값이 아직 옛 경로일 때) */
    private fun plannedRemainingMs(now: Long): Long? {
        val option = state.chosen ?: return null
        val direct = planDirectMs ?: return null
        val elapsed = now - routeChangedAt
        val added = if (state.viaActive) option.addedMs else 0L
        return (direct + added - elapsed).coerceAtLeast(0L)
    }

    private fun arrive(now: Long) {
        val target = state.targetAt
        val diff = target?.let { now - it }
        val message = when {
            diff == null -> "도착했어요."
            abs(diff) <= MINUTE_MS -> "딱 맞춰 도착했어요."
            diff < 0 -> "${DriveText.minutes(diff)}분 일찍 도착했어요."
            else -> "${DriveText.minutes(diff)}분 늦게 도착했어요."
        }
        state = state.copy(
            now = now,
            step = DriveStep.ARRIVED,
            arrivedAt = now,
            diffMs = diff,
            status = when {
                diff == null -> FitStatus.UNKNOWN
                diff > config.lateToleranceMs -> FitStatus.LATE
                diff < -config.earlyToleranceMs -> FitStatus.EARLY
                else -> FitStatus.ON_TIME
            },
            extendOffered = false,
            message = message,
        )
        restoreMood()
        effects += DriveEffect.Speak(message)
    }

    private fun restoreMood() {
        if (moodApplied) {
            moodApplied = false
            effects += DriveEffect.CabinMood(null)
        }
    }

    private fun reset() {
        askedDirectAt = null
        lateHandled = false
        restStopReached = false
        planFrom = null
        planDirectMs = null
        state = DriveState(
            now = state.now,
            position = state.position,
            naviDestination = state.naviDestination,
            naviRemainingMs = state.naviRemainingMs,
            places = state.places,
            mood = state.mood,
            style = state.style,
        )
    }

    private fun startMessage(option: CourseOption): String = when (option.kind) {
        CourseKind.DIRECT -> "목적지로 바로 가요."
        CourseKind.SCENIC -> "${DriveText.withObject(option.via.first().name)} 거쳐 가요."
        CourseKind.DETOUR -> "주변으로 돌아서 가요."
        CourseKind.REST_STOP -> "${RestMateText.spokenName(option.via.first().name)}에 들러 쉬다 가요."
    }

    private fun startSpeech(option: CourseOption): String {
        val target = state.targetAt
        val at = if (target != null) "${DriveText.spokenClock(target)}에 맞춰 도착하도록 안내할게요." else "안내를 시작할게요."
        return when (option.kind) {
            CourseKind.DIRECT -> "바로 가도 시간에 맞아요. $at"
            CourseKind.SCENIC -> "${DriveText.withObject(option.via.first().name)} 거쳐 가요. $at"
            CourseKind.DETOUR -> "주변으로 돌아서 가요. $at"
            CourseKind.REST_STOP -> "${RestMateText.spokenName(option.via.first().name)}에 들러 쉬다 가요. $at"
        }
    }

    companion object {
        const val DEFAULT_EXTRA_MINUTES = 30
        private const val FIVE_MINUTES = 5 * MINUTE_MS
        private const val DEFAULT_DIRECT_MS = 20 * MINUTE_MS
        private const val DEFAULT_SPARE_MS = 20 * MINUTE_MS
        private const val ROUTE_SETTLE_MS = 20 * SECOND_MS
        private const val ARRIVE_REMAIN_MS = 30 * SECOND_MS
        private const val MIN_REST_MS = 5 * MINUTE_MS
        private const val REST_STOP_NEAR_METERS = 400.0
        private const val REST_STOP_LEFT_METERS = 1_200.0

        fun roundUp(ms: Long, step: Long): Long = ((ms + step - 1) / step) * step
    }
}
