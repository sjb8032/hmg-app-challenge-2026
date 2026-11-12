package kr.contest.pleosapp.core

import kotlin.math.abs
import kotlin.math.max

/**
 * '온타임 모드'와 '롱웨이 모드'의 상태 전환과 도착 시각 맞추기. 안드로이드에 의존하지 않고, 시간은 밖에서 넣어 준다.
 *
 * 홈 → 설정(목적지·시각·분위기) → 코스 후보 → 주행(내비 남은 시간으로 계속 맞춤) → 도착
 *
 * - 늦지 않는 게 먼저: 늦음이 1분 30초 넘게 이어지면 지금 위치에서 더 짧은 코스로 바꾸고, 그래도 늦으면 경유지를 뺀다.
 *   롱웨이 모드는 마감이 느슨해 max(10분, 더 달리기로 한 시간의 1/3)까지는 그대로 두고, 넘으면 짧은 코스로 바꾼다.
 * - 일찍 도착할 것 같으면 '조금 더 돌기'를 권한다. 누르면 미리 골라 둔 코스로 바로 바꾼다.
 *   쉬고 난 뒤, 경로를 바꾼 지 10분 안, 더 돌 코스가 없을 때는 권하지 않는다.
 * - '레스트 모드'로 쉼터에 가는 동안은 시간 맞추기를 멈추고 고른 경유지는 남겨 둔다 (안내를 취소하면 그대로 이어 감).
 *   실제로 쉬었으면 경유지를 지난 것으로 보고, 다시 출발하면 지금 위치에서 이어서 맞춘다.
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
    private var routeChangedAt = Long.MIN_VALUE / 2
    /** 경로를 바꿀 때 예상한 남은 시간. 내비가 새 경로를 계산하기 전까지 이 값으로 보여 준다 */
    private var routePlanMs: Long? = null
    /** 경유지를 바꾸거나 뺀 시각 (그 뒤 한동안 '조금 더 돌기'를 권하지 않는다) */
    private var viaChangedAt = Long.MIN_VALUE / 2
    private var moodApplied = false
    private var planFrom: LatLng? = null
    private var planDirectMs: Long? = null
    /** 쉬다 가는 코스에서 쉴 곳 가까이까지 왔는지 */
    private var restStopReached = false
    /** 목표 시각을 직접 옮겼는지. 안 옮겼으면 끝낼 곳이 바뀔 때 기본 목표(바로 가는 시간 + 20분)를 다시 잡는다 */
    private var targetTouched = false
    private var styleTouched = false
    private var flavorTouched = false
    /** 늦음이 처음 보인 시각 */
    private var lateSince: Long? = null
    /** 늦어서 한 일: 0 없음, 1 짧은 코스로 바꿈, 2 경유지를 뺌 */
    private var lateSteps = 0
    /** 경유지 없이도 늦을 때는 한 번만 말한다 */
    private var lateSpoken = false
    /** 이번 드라이브에서 쉬었는지 (졸려서 쉰 뒤에는 더 돌기를 권하지 않는다) */
    private var restedThisDrive = false
    private var restPhase = Phase.DRIVING
    private var extendCheckedAt = Long.MIN_VALUE / 2

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
            DriveStep.ACTIVE -> {
                expireMessage(now)
                track(now)
            }
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
            refreshDefaultTarget(now)
        }
    }

    /** 내비에 물은 '바로 가면 걸리는 시간' 답 */
    fun onDirectTime(now: Long, destination: Place, ms: Long) {
        if (state.step != DriveStep.SETUP || ms <= 0) return
        if (!destination.sameSpot(state.destination)) return
        if (state.directMs != null && !state.directEstimated) return
        state = state.copy(now = now, directMs = ms, directEstimated = false)
        refreshDefaultTarget(now)
        if (state.planPending) makeOptions(now)
    }

    fun onPlaces(places: List<Place>) {
        state = state.copy(places = places)
    }

    fun onWaypointArrived(now: Long) {
        // 쉼터로 안내하는 동안 닿은 경유지는 쉼터다 (고른 경유지가 아님).
        if (state.step != DriveStep.ACTIVE || !state.viaActive || state.paused) return
        val option = state.chosen
        if (option?.kind == CourseKind.REST_STOP) {
            // 쉬다 가는 곳: 주차해서 쉬기 시작할 때까지 경유지로 남겨 둔다.
            restStopReached = true
            setMessage(
                now,
                if (option.restKind == RestKind.NAP) {
                    "쉬다 갈 곳에 왔어요. P단으로 주차하면 도착 시각에 맞춰 깨워 드릴게요."
                } else {
                    "기다릴 곳에 왔어요. P단으로 세우면 출발할 시각을 알려 드릴게요."
                },
            )
            return
        }
        state = state.copy(viaCleared = true)
        viaChangedAt = now
        setMessage(now, "경유지를 지났어요. 이제 목적지로 가요.")
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

    /** 쉬다 가는 코스에서 어떻게 쉬는지 (기본: 그냥 기다리기) */
    val plannedRestKind: RestKind get() = state.chosen?.restKind ?: RestKind.WAIT

    fun onDestinationArrived(now: Long) {
        if (state.step == DriveStep.ACTIVE) arrive(now)
    }

    /**
     * '레스트 모드' 단계가 바뀔 때.
     * - 쉼터로 가는 중: 시간 맞추기를 멈춘다. 고른 경유지는 남겨 둔다 (안내를 취소하면 그 경로로 돌아감).
     * - 쉬는 중: 쉰 곳이 경유지를 대신하므로 경유지를 지난 것으로 본다.
     * - 다시 주행: 쉬었으면 지금 위치에서 이어서 맞추고, 안내만 취소했으면 원래 코스로 이어 간다.
     */
    fun onRestPhase(now: Long, phase: Phase) {
        val previous = restPhase
        if (previous == phase) return
        restPhase = phase
        state = state.copy(now = now, paused = phase != Phase.DRIVING)
        if (state.step != DriveStep.ACTIVE) return
        when (phase) {
            Phase.TO_REST_AREA -> {
                state = state.copy(extendOffered = false, extendOption = null)
                setMessage(now, "쉬었다 가는 동안 시간 맞추기를 잠시 멈춰요.")
            }
            Phase.RESTING -> {
                restedThisDrive = true
                state = state.copy(viaCleared = true, extendOffered = false, extendOption = null)
            }
            Phase.DRIVING -> {
                lateSince = null
                lateSteps = 0
                lateSpoken = false
                routeChangedAt = now
                viaChangedAt = now
                if (previous == Phase.RESTING) {
                    // 쉬고 다시 출발: 계획을 지금 위치 기준으로 다시 잡는다 (옛 계획으로 '많이 늦어요'가 뜨지 않게).
                    planFrom = state.position
                    planDirectMs = directFromHereMs()
                    routePlanMs = planDirectMs
                    // 롱웨이 모드는 쉰 곳에서 코스가 끝난 것으로 보고, 바로 가는 시각을 새 예정으로 삼는다.
                    val direct = planDirectMs
                    if (state.mode == DriveMode.LONGER && direct != null) state = state.copy(targetAt = now + direct)
                    setMessage(now, "다시 출발했어요. 도착 시각을 이어서 맞출게요.")
                } else {
                    routePlanMs = null
                    setMessage(now, "쉼터 안내를 취소했어요. 원래 코스로 이어서 가요.")
                }
                track(now)
            }
        }
    }

    /** 달리는 중이면 도착 시각과 지금 위치에서 바로 가는 시간. 레스트 모드가 쉬는 동안 알람을 여기에 맞춘다 */
    fun deadline(): TripDeadline? {
        if (state.step != DriveStep.ACTIVE) return null
        val target = state.targetAt ?: return null
        val strict = state.mode == DriveMode.ON_TIME
        return TripDeadline(target, marginFor(), strict, directFromHereMs())
    }

    /** 내비 경로에 남아 있어야 할 경유지 (쉼터 안내를 취소하면 이 경로로 되돌린다) */
    fun activeVia(): List<Place> =
        if (state.step == DriveStep.ACTIVE && state.viaActive) state.chosen?.via.orEmpty() else emptyList()

    /** 롱웨이 모드로 켜 둔 분위기. 쉬고 나서 실내 환경을 되돌린 뒤 다시 켤 때 쓴다 */
    val moodInEffect: Mood? get() = if (moodApplied) state.mood else null

    // ---- 화면 조작 ----

    fun openMode(now: Long, mode: DriveMode) {
        resetTrip()
        targetTouched = false
        styleTouched = false
        flavorTouched = false
        val dest = defaultDestination(mode)
        state = state.copy(
            now = now,
            step = DriveStep.SETUP,
            mode = mode,
            destination = dest,
            targetAt = null,
            extraMinutes = DEFAULT_EXTRA_MINUTES,
            onTimeStyle = defaultStyle(dest),
            flavor = if (mode == DriveMode.LONGER) CourseFlavor.auto(DriveText.hourOfDay(now), state.mood) else null,
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
            extendOption = null,
            message = null,
            messageAt = null,
            arrivedAt = null,
        )
        if (dest != null) prepareDirect(now, dest)
        if (mode == DriveMode.ON_TIME) state = state.copy(targetAt = defaultTarget(now))
    }

    fun setDestination(now: Long, place: Place) {
        if (state.step != DriveStep.SETUP) return
        state = state.copy(now = now, destination = place, directMs = null, directEstimated = false, message = null)
        if (!styleTouched) state = state.copy(onTimeStyle = defaultStyle(place))
        prepareDirect(now, place)
        refreshDefaultTarget(now)
    }

    /** 도착 목표 시각을 분 단위로 옮긴다. 지금부터 5분 뒤보다 이르게는 못 한다. */
    fun shiftTarget(now: Long, minutes: Int) {
        if (state.step != DriveStep.SETUP || state.mode != DriveMode.ON_TIME) return
        val current = state.targetAt ?: defaultTarget(now)
        setTarget(now, current + minutes * MINUTE_MS)
    }

    /** 도착 목표 시각을 고른다 (시각 칩). 지금부터 5분 뒤보다 이르게는 못 한다. */
    fun setTarget(now: Long, at: Long) {
        if (state.step != DriveStep.SETUP || state.mode != DriveMode.ON_TIME) return
        val earliest = roundUp(now + 5 * MINUTE_MS, FIVE_MINUTES)
        targetTouched = true
        state = state.copy(targetAt = max(earliest, at), message = null)
    }

    /** 시각 칩 4개: 바로 가면 도착하는 시각 + 10분부터 (가까우면 10분, 멀면 30분 간격) */
    fun targetChoices(now: Long): List<Long> = targetChoices(now, state.directMs ?: estimateDirect() ?: DEFAULT_DIRECT_MS)

    fun setExtraMinutes(minutes: Int) {
        if (state.step == DriveStep.SETUP) state = state.copy(extraMinutes = minutes.coerceIn(5, 180))
    }

    fun setMood(mood: Mood) {
        state = state.copy(mood = mood)
        if (state.mode == DriveMode.LONGER && !flavorTouched && state.step == DriveStep.SETUP) {
            state = state.copy(flavor = CourseFlavor.auto(DriveText.hourOfDay(state.now), mood))
        }
    }

    fun setOnTimeStyle(style: OnTimeStyle) {
        styleTouched = true
        state = state.copy(onTimeStyle = style)
    }

    /** 롱웨이 모드 코스 후보 화면의 칩 (야경·강변·다리·공원·도심 피하기). 고르면 후보를 다시 낸다 */
    fun setFlavor(now: Long, flavor: CourseFlavor) {
        flavorTouched = true
        state = state.copy(flavor = flavor)
        if (state.step == DriveStep.CHOOSE && state.chosen == null) makeOptions(now)
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
        val direct = planDirectMs ?: state.directMs ?: 0L
        // 롱웨이 모드: 고른 코스가 걸리는 만큼이 새 도착 목표 (마감이 아니라 예정)
        val target = if (state.mode == DriveMode.LONGER) now + direct + option.addedMs else state.targetAt
        lateSince = null
        lateSteps = 0
        lateSpoken = false
        restStopReached = false
        routeChangedAt = now
        routePlanMs = direct + option.addedMs
        state = state.copy(
            now = now,
            step = DriveStep.ACTIVE,
            chosen = option,
            options = emptyList(),
            viaCleared = option.via.isEmpty(),
            targetAt = target,
            status = FitStatus.UNKNOWN,
            diffMs = null,
            arrivalEstimate = null,
            extendOffered = false,
            extendOption = null,
            arrivedAt = null,
            // 화면 위 제목이 고른 코스를 보여 주므로 같은 말을 또 띄우지 않는다.
            message = null,
            messageAt = null,
        )
        effects += DriveEffect.Route(dest, option.via)
        if (state.mode == DriveMode.LONGER && !moodApplied) {
            moodApplied = true
            effects += DriveEffect.CabinMood(state.mood)
        }
        speak(Say.START, startSpeech(option, now, direct))
        track(now)
    }

    /**
     * '바로 가기': 후보 화면에서는 경유지 없이 출발, 주행 중에는 남은 경유지를 뺀다.
     * 롱웨이 모드에서는 '바로 집으로': 경유지를 빼고 그때 도착 시각을 새 예정으로 삼는다.
     */
    fun goDirect(now: Long) {
        when (state.step) {
            DriveStep.CHOOSE -> {
                if (state.chosen != null) {
                    state = state.copy(step = DriveStep.ACTIVE, options = emptyList())
                    return
                }
                val target = state.targetAt
                val direct = state.directMs ?: 0L
                val slack = if (state.mode == DriveMode.ON_TIME && target != null) {
                    TimeFitPlanner.slackMs(now, target, direct, config)
                } else {
                    0L
                }
                choose(now, directOption(now, slack))
            }
            DriveStep.ACTIVE -> {
                val dest = state.destination ?: return
                if (!state.viaActive || state.paused) return
                val direct = directFromHereMs()
                setRoute(now, dest, emptyList(), direct)
                state = state.copy(viaCleared = true, extendOffered = false, extendOption = null)
                if (state.mode == DriveMode.LONGER) {
                    if (direct != null) state = state.copy(targetAt = now + direct)
                    setMessage(now, "${DriveText.toward(dest.name)} 바로 가요.")
                    speak(Say.VIA, "${DriveText.toward(dest.name)} 바로 갈게요.")
                } else {
                    setMessage(now, "경유지를 빼고 목적지로 바로 가요.")
                    speak(Say.VIA, "경유지를 빼고 목적지로 바로 갈게요.")
                }
            }
            else -> Unit
        }
    }

    /** '조금 더 돌기': 지금 위치에서 남는 시간을 채울 코스로 바로 바꾼다 (마음이 바뀌면 '바로 가기'). */
    fun extend(now: Long) {
        if (state.step != DriveStep.ACTIVE || state.paused) return
        val found = coursesFromHere(now)
        if (found == null) {
            state = state.copy(extendOffered = false, extendOption = null)
            setMessage(now, "더 돌 만한 코스가 없어요. 그대로 갈게요.")
            return
        }
        // 버튼에 보여 준 코스가 아직 맞으면 그 코스로 (보여 준 것과 다른 곳으로 가지 않게)
        val offered = state.extendOption
        val option = found.first.firstOrNull { offered != null && sameVia(it, offered) } ?: found.first.first()
        applyCourse(now, option, found.second)
        lateSteps = 0
        setMessage(now, "${DriveText.toward(option.title)} 바꿨어요. 마음이 바뀌면 '바로 가기'를 눌러 주세요.")
        speak(Say.VIA, "${DriveText.toward(option.title)} 바꿀게요.")
        track(now)
    }

    /**
     * 달리는 중 시간 더하기.
     * - 온타임 모드 [+5분][+10분]: 약속이 늦춰졌을 때. 목표를 미루고, 늘어난 시간을 채울 코스로 바로 바꾼다 (지금 코스보다 늦게 끝나는 코스가 있을 때).
     * - 롱웨이 모드 [+15분 더]: 더 달리고 싶을 때. 늘어난 시간으로 코스를 다시 고르고, 그 코스가 걸리는 만큼을 새 예정으로 삼는다
     *   (근처 지점으로 다 못 채우면 실제로 늘어난 만큼만 알려 준다).
     */
    fun addTime(now: Long, minutes: Int) {
        if (state.step != DriveStep.ACTIVE || state.paused) return
        val before = state.targetAt ?: return
        val target = before + minutes * MINUTE_MS
        val current = state.chosen.takeIf { state.viaActive }
        val currentEta = state.arrivalEstimate ?: before
        val longer = state.mode == DriveMode.LONGER
        lateSince = null
        lateSpoken = false
        state = state.copy(targetAt = target, extendOffered = false, extendOption = null)
        // 늘어난 시간을 채울 코스: 지금 코스가 아닌 것 중 가장 길게(목표 안에서) 달리는 코스.
        // 더한 시간의 1/3도 못 채우면 코스를 바꾸지 않는다 (바꿔도 거의 같음).
        val refill = coursesFromHere(now, exclude = current)?.let { (options, direct) ->
            options.filter { now + direct + it.addedMs > currentEta + minutes * MINUTE_MS / 3 }
                .maxByOrNull { it.addedMs }
                ?.let { it to direct }
        }
        if (refill == null) {
            if (longer) {
                state = state.copy(targetAt = before)
                setMessage(now, "지금 코스가 이 근처에서 가장 길어요.")
            } else {
                setMessage(now, "도착 목표를 늦췄어요 (${DriveText.clock(target)}).")
                speak(Say.VIA, "도착 목표를 ${DriveText.toward(DriveText.spokenClock(target))} 늦췄어요.")
            }
            track(now)
            return
        }
        val (option, direct) = refill
        applyCourse(now, option, direct)
        lateSteps = 0
        val title = option.title
        if (longer) {
            // 롱웨이 모드는 고른 코스가 걸리는 만큼을 새 예정으로 (근처 지점으로 다 못 채우면 실제로 늘어난 만큼만 알린다)
            val planned = now + direct + option.addedMs
            val gained = planned - currentEta
            state = state.copy(targetAt = planned, extraMinutes = state.extraMinutes + minutes)
            setMessage(
                now,
                if (gained >= minutes * MINUTE_MS * 7 / 10) {
                    "${minutes}분 더 달려요. ${DriveText.toward(title)} 바꿨어요."
                } else {
                    "이 근처는 약 ${DriveText.minutes(gained)}분 더 달리는 코스가 가장 길어요. ${DriveText.toward(title)} 바꿨어요."
                },
            )
            speak(Say.VIA, "${DriveText.toward(title)} 바꿀게요.")
        } else {
            setMessage(now, "도착 목표를 늦추고(${DriveText.clock(target)}) ${DriveText.toward(title)} 바꿨어요.")
            speak(Say.VIA, "${DriveText.spokenClock(target)}에 맞춰 ${DriveText.toward(title)} 바꿀게요.")
        }
        track(now)
    }

    fun back(now: Long) {
        when (state.step) {
            DriveStep.CHOOSE -> state = if (state.chosen != null) {
                state.copy(step = DriveStep.ACTIVE, options = emptyList())
            } else {
                state.copy(step = DriveStep.SETUP, options = emptyList(), planPending = false, message = null)
            }
            DriveStep.SETUP -> reset()
            DriveStep.ACTIVE, DriveStep.ARRIVED -> finish(now)
            DriveStep.HOME -> Unit
        }
    }

    /** 드라이브를 끝내고 처음 화면으로. 바꿔 둔 분위기는 되돌리고, 내비에 남은 경유지는 뺀다. */
    fun finish(now: Long) {
        val dest = state.destination
        if (state.step == DriveStep.ACTIVE && state.viaActive && !state.paused && dest != null) {
            effects += DriveEffect.Route(dest, emptyList())
        }
        restoreMood()
        reset()
        state = state.copy(now = now)
    }

    /** 지금 설정을 자주 쓰는 설정으로 저장할 수 있으면 그 내용 (설정·코스 고르기 화면에서) */
    fun saveable(): SavedTrip? = state.toSavedTrip()

    /** 저장한 설정을 한 번에: 모드를 열고, 끝낼 곳·시각을 맞춰 바로 코스를 찾는다. 오늘 그 시각이 지났으면 시각만 다시 고르게 한다 */
    fun applySaved(now: Long, trip: SavedTrip) {
        openMode(now, trip.mode)
        state = state.copy(destination = trip.place, directMs = null, directEstimated = false)
        prepareDirect(now, trip.place)
        trip.mood?.let { state = state.copy(mood = it) }
        trip.extraMinutes?.let { state = state.copy(extraMinutes = it.coerceIn(5, 180)) }
        state = state.copy(onTimeStyle = trip.onTimeStyle ?: defaultStyle(trip.place))
        styleTouched = trip.onTimeStyle != null
        if (trip.mode == DriveMode.LONGER) {
            flavorTouched = trip.flavor != null
            state = state.copy(flavor = trip.flavor ?: CourseFlavor.auto(DriveText.hourOfDay(now), state.mood))
        }
        if (trip.mode == DriveMode.ON_TIME) {
            val minute = trip.minuteOfDay
            if (minute == null) {
                refreshDefaultTarget(now)
            } else {
                val at = DriveText.todayAt(now, minute)
                if (at <= now) {
                    state = state.copy(targetAt = defaultTarget(now))
                    setMessage(now, "오늘은 이미 지난 시각이에요 (${DriveText.clockOfDay(minute)}). 도착 시각을 골라 주세요.")
                    return
                }
                targetTouched = true
                state = state.copy(targetAt = at)
            }
        }
        plan(now)
    }

    // ---- 내부 ----

    private enum class Say {
        /** 출발할 때 */
        START,

        /** 경유지를 바꾸거나 뺄 때 */
        VIA,

        /** 경유지 없이도 늦을 때 */
        LATE,

        /** 도착 */
        ARRIVE,
    }

    /**
     * 음성 안내 규칙 (안전 안내는 레스트 모드가 따로 늘 말한다).
     * - 온타임 모드: 출발·경로 변경·늦음(처음 한 번)·도착을 말한다. 일찍 도착할 것 같다는 말과 '조금 더 돌기' 권유는 화면으로만.
     * - 롱웨이 모드 데이트: 말하지 않는다 (화면으로만).
     * - 롱웨이 모드 혼자: 경로가 바뀔 때만 시각 없이 짧게.
     */
    private fun speak(kind: Say, text: String) {
        val allowed = when (state.mode) {
            DriveMode.ON_TIME -> true
            DriveMode.LONGER -> state.mood == Mood.SOLO && kind != Say.LATE
            null -> false
        }
        if (allowed) effects += DriveEffect.Speak(text)
    }

    private fun setMessage(now: Long, text: String?) {
        state = state.copy(message = text, messageAt = if (text != null) now else null)
    }

    private fun expireMessage(now: Long) {
        val at = state.messageAt ?: return
        if (state.message != null && now - at >= config.messageTtlMs) state = state.copy(message = null, messageAt = null)
    }

    private fun marginFor(): Long = if (state.mode == DriveMode.ON_TIME) config.marginMs else 0L

    private fun defaultStyle(place: Place?): OnTimeStyle =
        if (place?.kind == PlaceKind.BOOKMARK) OnTimeStyle.WAIT_NEAR else OnTimeStyle.LOOP

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
        refreshDefaultTarget(state.now)
    }

    /** 온타임 모드 설정 중, 목표 시각을 직접 옮기지 않았으면 바로 가는 시간에 맞춰 기본 목표를 다시 잡는다. */
    private fun refreshDefaultTarget(now: Long) {
        if (state.step != DriveStep.SETUP || state.mode != DriveMode.ON_TIME || targetTouched) return
        state = state.copy(targetAt = defaultTarget(now))
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

    /** 롱웨이 모드는 달리는 시간이 목적이라 쉬다 가는 코스를 내지 않는다. */
    private fun stopsFor(mode: DriveMode?): List<RestArea> = if (mode == DriveMode.LONGER) emptyList() else restPlaces

    /** 온타임 모드는 경치·우회로 오래 돌지 않는다 (남는 시간이 길면 쉬다 가기). 롱웨이 모드는 고른 만큼 */
    private fun maxAdded(): Long = if (state.mode == DriveMode.LONGER) Long.MAX_VALUE else config.maxOnTimeCourseMs

    private fun wish(): CourseWish = when (state.mode) {
        DriveMode.LONGER -> {
            val flavor = state.flavor
            CourseWish(prefer = flavor?.tags.orEmpty(), avoidCity = flavor == CourseFlavor.AVOID_CITY)
        }
        else -> CourseWish(onTime = true)
    }

    /** 코스 후보: 온타임 '근처에서 기다리기'면 기다릴 곳부터, 없으면 돌면서 맞추는 코스 */
    private fun planOptions(
        from: LatLng,
        dest: Place,
        direct: Long,
        slack: Long,
        notes: MutableList<String>? = null,
    ): List<CourseOption> {
        if (state.mode == DriveMode.ON_TIME && state.onTimeStyle == OnTimeStyle.WAIT_NEAR) {
            val wait = TimeFitPlanner.waitNearOptions(from, dest, direct, slack, restPlaces, config)
            if (wait.isNotEmpty()) return wait
            if (slack >= config.minSlackMs) notes?.add("목적지 근처에 기다릴 곳이 없어 돌면서 맞추는 코스를 보여 드려요.")
        }
        return TimeFitPlanner.options(from, dest, direct, slack, points, stopsFor(state.mode), config, maxAdded(), wish())
    }

    private fun directOption(now: Long, slack: Long): CourseOption {
        val direct = state.directMs ?: 0L
        if (state.mode == DriveMode.LONGER) {
            return TimeFitPlanner.direct(0L).copy(reason = "더 돌지 않고 ${DriveText.clock(now + direct)}에 도착해요")
        }
        return TimeFitPlanner.direct(slack, now + direct, config.marginMs)
    }

    private fun makeOptions(now: Long) {
        val dest = state.destination ?: return
        val direct = state.directMs ?: return
        val longer = state.mode == DriveMode.LONGER
        val target = if (longer) now + direct + state.extraMinutes * MINUTE_MS else state.targetAt ?: defaultTarget(now)
        val slack = if (longer) state.extraMinutes * MINUTE_MS else TimeFitPlanner.slackMs(now, target, direct, config)
        val from = state.position
        planFrom = from
        planDirectMs = direct
        val notes = ArrayList<String>()
        val options = if (from == null) {
            notes += "현재 위치를 몰라 바로 가는 코스만 보여요."
            listOf(directOption(now, slack))
        } else {
            planOptions(from, dest, direct, slack, notes).map { if (it.kind == CourseKind.DIRECT) directOption(now, slack) else it }
        }
        if (state.directEstimated) notes += "내비 예상 시간을 받지 못해 거리로 추정했어요."
        if (longer && from != null) {
            val best = options.filter { it.kind != CourseKind.DIRECT }.maxOfOrNull { it.addedMs } ?: 0L
            if (best < slack * 7 / 10) {
                notes += if (best >= MINUTE_MS) "이 근처는 최대 ${DriveText.minutes(best)}분 코스예요." else "이 근처에는 더 달릴 코스가 없어요."
            }
            val flavor = state.flavor
            if (flavor != null && best >= MINUTE_MS && options.none { o -> o.tags.any { it in flavor.tags } }) {
                notes += "${flavor.label} 코스가 근처에 없어 다른 곳을 보여 드려요."
            }
        }
        state = state.copy(
            now = now,
            step = DriveStep.CHOOSE,
            targetAt = target,
            options = options,
            planPending = false,
            message = notes.joinToString(" ").ifEmpty { null },
            messageAt = now,
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
        val navi = naviRemainMs()
        if (navi != null && navi <= ARRIVE_REMAIN_MS && !state.viaActive) {
            arrive(now)
            return
        }
        checkRestStopPassed(now, position)
        val settled = now - routeChangedAt >= ROUTE_SETTLE_MS
        // 경로를 막 바꿨으면 내비가 새 경로를 계산할 때까지 계획한 값으로 보여 준다.
        val remain = (if (settled) null else plannedRemainingMs(now)) ?: remainingMs(now) ?: return
        val eta = now + remain
        val diff = eta - target
        val status = statusFor(diff)
        state = state.copy(arrivalEstimate = eta, diffMs = diff, status = status)
        if (state.paused || !settled) return
        if (status == FitStatus.LATE) onLate(now, diff) else lateSince = null
        when {
            status == FitStatus.EARLY -> maybeOfferExtend(now, diff, remain)
            state.extendOffered -> state = state.copy(extendOffered = false, extendOption = null)
        }
    }

    private fun statusFor(diff: Long): FitStatus = when {
        diff > lateToleranceMs() -> FitStatus.LATE
        diff < -config.earlyToleranceMs -> FitStatus.EARLY
        else -> FitStatus.ON_TIME
    }

    /** 늦었다고 보는 폭: 온타임 2분, 롱웨이 max(10분, 더 달리기로 한 시간의 1/3) */
    private fun lateToleranceMs(): Long =
        if (state.mode == DriveMode.LONGER) {
            max(config.longerLateToleranceMs, state.extraMinutes * MINUTE_MS / 3)
        } else {
            config.lateToleranceMs
        }

    /**
     * 늦음이 [DriveConfig.lateObserveMs] 넘게 이어지면:
     * 경유지가 있으면 지금 위치에서 더 짧은 코스로 바꾸고(목적지 근처에서 기다리기 포함), 없거나 그래도 늦으면 경유지를 뺀다.
     * 롱웨이 모드는 짧은 코스가 없을 때만 뺀다. 경유지 없이도 늦으면 온타임 모드에서만 한 번 알린다.
     */
    private fun onLate(now: Long, diff: Long) {
        val since = lateSince ?: now.also { lateSince = it }
        if (now - since < config.lateObserveMs) return
        val dest = state.destination ?: return
        if (state.viaActive) {
            lateSince = null
            val tryShorter = state.mode == DriveMode.LONGER || lateSteps == 0
            val shorter = if (tryShorter) courseFromHere(now, exclude = state.chosen) else null
            if (shorter != null) {
                applyCourse(now, shorter.first, shorter.second)
                lateSteps = 1
                val title = shorter.first.title
                setMessage(now, "길이 막혀 ${DriveText.toward(title)} 바꿨어요.")
                speak(Say.VIA, "길이 막혀 ${DriveText.toward(title)} 바꿀게요.")
            } else {
                setRoute(now, dest, emptyList(), directFromHereMs())
                lateSteps = 2
                state = state.copy(viaCleared = true, extendOffered = false, extendOption = null)
                setMessage(now, "길이 막혀 경유지를 빼고 바로 가요.")
                speak(Say.VIA, "길이 막혀 경유지를 빼고 바로 갈게요.")
            }
            return
        }
        if (state.mode == DriveMode.ON_TIME && !lateSpoken) {
            lateSpoken = true
            val text = "길이 막혀 약 ${DriveText.minutes(diff)}분 늦을 것 같아요."
            setMessage(now, text)
            speak(Say.LATE, text)
        }
    }

    /**
     * 일찍 도착할 것 같으면, 더 돌 코스가 실제로 있을 때만 권한다 (쉰 뒤·경로를 바꾼 직후에는 권하지 않음).
     * 권하는 동안에는 1분마다 코스를 다시 골라 버튼에 보여 준 코스가 지금 위치에서도 맞게 한다.
     */
    private fun maybeOfferExtend(now: Long, diff: Long, remain: Long) {
        if (state.viaActive || restedThisDrive) return
        if (remain < config.extendMinRemainMs || now - viaChangedAt < config.extendQuietMs) {
            if (state.extendOffered) state = state.copy(extendOffered = false, extendOption = null)
            return
        }
        if (now - extendCheckedAt < EXTEND_RECHECK_MS) return
        extendCheckedAt = now
        val option = courseFromHere(now)?.first
        if (option == null) {
            if (state.extendOffered) state = state.copy(extendOffered = false, extendOption = null)
            return
        }
        val first = !state.extendOffered
        state = state.copy(extendOffered = true, extendOption = option)
        // 화면으로만 알린다 (음성 없음).
        if (first) {
            setMessage(now, "${DriveText.minutes(diff)}분 일찍 도착할 것 같아요. '조금 더 돌기'를 누르면 ${DriveText.toward(option.title)} 시간을 맞춰요.")
        }
    }

    /**
     * 지금 위치에서 남는 시간으로 코스를 하나 고른다 (조금 더 돌기·늦을 때 짧은 코스·시간 더하기).
     * 바로 가는 시간(정체 반영)도 함께 돌려준다. 고를 코스가 없으면 null
     */
    private fun courseFromHere(now: Long, exclude: CourseOption? = null): Pair<CourseOption, Long>? {
        val (options, direct) = coursesFromHere(now, exclude) ?: return null
        return options.first() to direct
    }

    /** [courseFromHere]의 후보 전부 (좋은 순). 고를 코스가 없으면 null */
    private fun coursesFromHere(now: Long, exclude: CourseOption? = null): Pair<List<CourseOption>, Long>? {
        val from = state.position ?: return null
        val dest = state.destination ?: return null
        val target = state.targetAt ?: return null
        val direct = directFromHereMs() ?: return null
        val slack = target - now - direct - marginFor()
        if (slack < config.minSlackMs) return null
        val options = planOptions(from, dest, direct, slack)
            .filter { it.kind != CourseKind.DIRECT && !sameVia(it, exclude) }
        if (options.isEmpty()) return null
        return options to direct
    }

    private fun applyCourse(now: Long, option: CourseOption, directMs: Long) {
        val dest = state.destination ?: return
        planFrom = state.position
        planDirectMs = directMs
        restStopReached = false
        viaChangedAt = now
        setRoute(now, dest, option.via, directMs + option.addedMs)
        state = state.copy(chosen = option, viaCleared = option.via.isEmpty(), extendOffered = false, extendOption = null)
    }

    private fun setRoute(now: Long, dest: Place, via: List<Place>, plannedRemainMs: Long?) {
        routeChangedAt = now
        routePlanMs = plannedRemainMs
        lateSince = null
        if (via.isEmpty()) viaChangedAt = now
        effects += DriveEffect.Route(dest, via)
    }

    private fun sameVia(a: CourseOption, b: CourseOption?): Boolean {
        if (b == null) return false
        val x = a.via.firstOrNull() ?: return b.via.isEmpty()
        val y = b.via.firstOrNull() ?: return false
        return x.sameSpot(y)
    }

    /** 쉬다 가는 곳까지 왔다가 쉬지 않고 떠나면 쉬는 시간을 셈에서 뺀다. */
    private fun checkRestStopPassed(now: Long, position: LatLng?) {
        val area = state.chosen?.restArea ?: return
        if (!state.viaActive || state.paused || position == null) return
        val d = Geo.distanceMeters(position, area.position)
        if (d <= REST_STOP_NEAR_METERS) {
            restStopReached = true
        } else if (restStopReached && d > REST_STOP_LEFT_METERS) {
            restStopReached = false
            viaChangedAt = now
            state = state.copy(viaCleared = true)
            setMessage(now, "쉬지 않고 지나왔어요. 도착 시각을 다시 맞춰 볼게요.")
        }
    }

    /** 내비가 알려 주는 우리 목적지까지 남은 시간 (내비 목적지가 같을 때만) */
    private fun naviRemainMs(): Long? {
        val dest = state.destination ?: return null
        return state.naviRemainingMs?.takeIf { dest.sameSpot(state.naviDestination) }
    }

    /** 내비 경로에 들어 있는 시간 모드의 경유지 (쉼터로 안내하는 동안은 빼고 본다) */
    private fun routeVia(): List<Place> = if (state.viaActive && !state.paused) state.chosen?.via.orEmpty() else emptyList()

    private fun planSpeed(position: LatLng): Double {
        val dest = state.destination?.position ?: return config.fallbackSpeedKmh / 3.6
        return TimeFitPlanner.effectiveSpeedMps(planFrom ?: position, dest, planDirectMs, config)
    }

    /**
     * 목적지까지 남은 시간. 내비 값(경유지 포함)이 있으면 그것을, 없으면 남은 경로를 직선거리로 추정한다.
     * 쉬다 가는 곳에 들르는 코스는 그곳에서 시간을 맞춰 출발하므로 '목표 − 여유'보다 일찍 닿지 않는다.
     */
    private fun remainingMs(now: Long): Long? {
        val driving = naviRemainMs() ?: run {
            val dest = state.destination ?: return null
            val position = state.position ?: return null
            TimeFitPlanner.pathMs(position, routeVia(), dest.position, planSpeed(position), config)
        }
        val option = state.chosen
        if (state.viaActive && !state.paused && option?.kind == CourseKind.REST_STOP) {
            val target = state.targetAt ?: return driving + option.stayMs
            return max(driving, target - marginFor() - now)
        }
        return driving
    }

    /**
     * 지금 위치에서 경유지 없이 목적지까지 가는 시간.
     * 경유지가 있는 경로면 내비 남은 시간 ÷ 모델 추정(경유지 포함)으로 정체 비율(1~3배)을 구해 반영한다.
     */
    private fun directFromHereMs(): Long? {
        val dest = state.destination ?: return null
        val navi = naviRemainMs()
        val position = state.position
        // 이미 닿은(400m 안) 경유지는 남은 경로에서 뺀다 (쉬다 가는 곳에 세워 둔 동안 등).
        val via = routeVia().filter { position == null || Geo.distanceMeters(it.position, position) > REST_STOP_NEAR_METERS }
        if (navi != null && via.isEmpty()) return navi
        if (position == null) return navi ?: planDirectMs
        val speed = planSpeed(position)
        val model = TimeFitPlanner.pathMs(position, emptyList(), dest.position, speed, config)
        if (navi == null) return model
        val withVia = TimeFitPlanner.pathMs(position, via, dest.position, speed, config).coerceAtLeast(1L)
        val jam = (navi.toDouble() / withVia).coerceIn(1.0, 3.0)
        return (model * jam).toLong()
    }

    /** 경로를 막 바꿨을 때 계획한 남은 시간 (내비 값이 아직 옛 경로일 때) */
    private fun plannedRemainingMs(now: Long): Long? {
        val plan = routePlanMs ?: return null
        return (plan - (now - routeChangedAt)).coerceAtLeast(0L)
    }

    private fun arrive(now: Long) {
        val target = state.targetAt
        val diff = target?.let { now - it }
        // 여유(3분)를 두고 맞추므로 목표보다 조금 일찍 닿는 것까지 '딱 맞춰'로 본다.
        val message = when {
            state.mode == DriveMode.LONGER -> "도착했어요."
            diff == null -> "도착했어요."
            abs(diff) <= MINUTE_MS -> "딱 맞춰 도착했어요."
            diff < 0 && -diff <= config.marginMs + MINUTE_MS -> "딱 맞춰 도착했어요. ${DriveText.minutes(diff)}분 여유가 있어요."
            diff < 0 -> "${DriveText.minutes(diff)}분 일찍 도착했어요."
            else -> "${DriveText.minutes(diff)}분 늦게 도착했어요."
        }
        state = state.copy(
            now = now,
            step = DriveStep.ARRIVED,
            arrivedAt = now,
            diffMs = diff,
            status = if (diff == null) FitStatus.UNKNOWN else statusFor(diff),
            extendOffered = false,
            extendOption = null,
            message = message,
            messageAt = now,
        )
        restoreMood()
        speak(Say.ARRIVE, message)
    }

    private fun restoreMood() {
        if (moodApplied) {
            moodApplied = false
            effects += DriveEffect.CabinMood(null)
        }
    }

    /** 드라이브 하나에 쓰는 값을 처음으로 */
    private fun resetTrip() {
        askedDirectAt = null
        lateSince = null
        lateSteps = 0
        lateSpoken = false
        restedThisDrive = false
        restStopReached = false
        routePlanMs = null
        planFrom = null
        planDirectMs = null
        extendCheckedAt = Long.MIN_VALUE / 2
        viaChangedAt = Long.MIN_VALUE / 2
    }

    private fun reset() {
        resetTrip()
        state = DriveState(
            now = state.now,
            position = state.position,
            naviDestination = state.naviDestination,
            naviRemainingMs = state.naviRemainingMs,
            places = state.places,
            mood = state.mood,
            paused = state.paused,
        )
    }

    private fun startSpeech(option: CourseOption, now: Long, direct: Long): String {
        val via = option.via.firstOrNull()?.name.orEmpty()
        if (state.mode == DriveMode.LONGER) {
            // 롱웨이 모드는 시각 없이 짧게 (혼자일 때만 말한다)
            return when (option.kind) {
                CourseKind.DIRECT -> "${DriveText.toward(state.destination?.name ?: "목적지")} 바로 갈게요."
                CourseKind.SCENIC -> "${DriveText.withObject(via)} 거쳐 가요."
                CourseKind.DETOUR -> "주변으로 돌아서 가요."
                CourseKind.REST_STOP -> "${RestMateText.spokenName(via)}에 들렀다 가요."
            }
        }
        val target = state.targetAt
        val at = if (target != null) "${DriveText.spokenClock(target)}에 맞춰 도착하도록 안내할게요." else "안내를 시작할게요."
        return when (option.kind) {
            CourseKind.DIRECT -> {
                val early = if (target != null) target - now - direct else 0L
                if (early >= config.marginMs + 2 * MINUTE_MS) {
                    "바로 가면 ${DriveText.spokenClock(now + direct)}, ${DriveText.minutes(early)}분 일찍 도착해요."
                } else {
                    "바로 가도 시간에 맞아요. $at"
                }
            }
            CourseKind.SCENIC -> "${DriveText.withObject(via)} 거쳐 가요. $at"
            CourseKind.DETOUR -> "주변으로 돌아서 가요. $at"
            CourseKind.REST_STOP -> if (option.restKind == RestKind.NAP) {
                "${RestMateText.spokenName(via)}에 들러 쉬다 가요. $at"
            } else {
                "${RestMateText.spokenName(via)}에서 기다렸다 가요. $at"
            }
        }
    }

    companion object {
        const val DEFAULT_EXTRA_MINUTES = 30
        private const val FIVE_MINUTES = 5 * MINUTE_MS
        private const val DEFAULT_DIRECT_MS = 20 * MINUTE_MS
        private const val DEFAULT_SPARE_MS = 20 * MINUTE_MS
        private const val ROUTE_SETTLE_MS = 20 * SECOND_MS
        private const val ARRIVE_REMAIN_MS = 30 * SECOND_MS
        private const val REST_STOP_NEAR_METERS = 400.0
        private const val REST_STOP_LEFT_METERS = 1_200.0
        private const val EXTEND_RECHECK_MS = MINUTE_MS

        fun roundUp(ms: Long, step: Long): Long = ((ms + step - 1) / step) * step

        /** 시각 칩 4개: (지금 + 바로 가는 시간 + 10분)을 올림한 시각부터. 30분 안 길이면 10분, 그보다 멀면 30분 간격 */
        fun targetChoices(now: Long, directMs: Long): List<Long> {
            val step = if (directMs < 30 * MINUTE_MS) 10 * MINUTE_MS else 30 * MINUTE_MS
            val base = roundUp(now + directMs + 10 * MINUTE_MS, step)
            return List(4) { base + it * step }
        }
    }
}
