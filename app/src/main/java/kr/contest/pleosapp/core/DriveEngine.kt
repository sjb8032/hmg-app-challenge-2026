package kr.contest.pleosapp.core

import kotlin.math.abs
import kotlin.math.max

/**
 * '온타임 모드'와 '롱웨이 모드'의 상태 전환과 도착 시각 맞추기. 안드로이드에 의존하지 않고, 시간은 밖에서 넣어 준다.
 *
 * 홈 → 설정(목적지·시각·분위기) → 코스 후보 → 주행(내비 남은 시간으로 계속 맞춤) → 도착
 *
 * - 늦지 않는 게 먼저: 목표보다 30초 넘게 늦을 것 같은 상태가 1분 30초 이어지면 지금 위치에서 더 짧은 코스로 바꾸고,
 *   그래도 늦으면 경유지를 뺀다. 기다리는 코스는 기다릴 시간이 3분 밑으로 줄면 들르지 않고 바로 간다.
 *   롱웨이 모드는 마감이 느슨해 max(10분, 더 달리기로 한 시간의 1/3)까지는 그대로 두고, 넘으면 짧은 코스로 바꾼다.
 * - 일찍 도착할 것 같으면 '조금 더 돌기'를 권한다 (경치 경유지로 가는 중에도 더 먼 지점으로). 누르면 미리 골라 둔 코스로 바꾼다.
 *   코스를 고른 뒤 3분, 경로를 바꾼 뒤 10분 안에는 권하지 않고, 졸려서 쉰 뒤에는 쉬다 가는 곳만 권한다.
 * - '레스트 모드'로 쉼터에 가는 동안은 시간 맞추기를 멈추고 고른 경유지는 남겨 둔다 (안내를 취소하면 그대로 이어 감).
 *   계획한 곳에서 쉬었으면 경유지를 지난 것으로 보고, 다른 곳에서 쉬었으면 다시 출발할 때 경유지가 아직 맞는지 보고 남기거나 뺀다.
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
    /** 코스 후보 화면에서 바로 가는 시간을 다시 물었는지 (자리를 옮겼을 때) */
    private var refreshAsked = false
    private var routeChangedAt = Long.MIN_VALUE / 2
    /** 경로를 바꿀 때 예상한 남은 운전 시간. 내비가 새 경로를 계산하기 전까지 이 값으로 보여 준다 */
    private var routePlanMs: Long? = null
    /** 경유지를 바꾸거나 뺀 시각 (그 뒤 한동안 '조금 더 돌기'를 권하지 않는다) */
    private var viaChangedAt = Long.MIN_VALUE / 2
    /** 이때까지는 '조금 더 돌기'를 권하지 않는다 (코스를 막 골랐을 때) */
    private var extendQuietUntil = Long.MIN_VALUE / 2
    private var moodApplied = false
    private var planFrom: LatLng? = null
    private var planDirectMs: Long? = null
    /** 코스 후보를 낸 시각 (후보 화면에 오래 머물면 다시 낸다) */
    private var planMadeAt = Long.MIN_VALUE / 2
    /** 쉬다 가는 코스에서 쉴 곳 가까이까지 왔는지 */
    private var restStopReached = false
    /** 목표 시각을 직접 옮겼는지. 안 옮겼으면 끝낼 곳이 바뀔 때 기본 목표를 다시 잡는다 */
    private var targetTouched = false
    private var styleTouched = false
    private var flavorTouched = false
    /** 늦음이 처음 보인 시각 */
    private var lateSince: Long? = null
    /** 늦어서 한 일: 0 없음, 1 짧은 코스로 바꿈, 2 경유지를 뺌 */
    private var lateSteps = 0
    /** 경유지 없이도 늦을 때는 한 번만 말한다 (출발할 때·쉬는 동안 이미 말했으면 다시 말하지 않는다) */
    private var lateSpoken = false
    /** 이번 드라이브에서 졸려서 쉬었는지 (그 뒤에는 경치 길로 더 돌기를 권하지 않는다) */
    private var restedThisDrive = false
    private var restPhase = Phase.DRIVING
    /** 지금 휴식이 졸려서 쉬는 것인지 */
    private var restFatigue = false
    /** 지금 휴식이 코스에서 계획한 쉬다 가는 곳에서인지 */
    private var restAtStop = false
    /** 레스트 모드가 내비 경로에 넣은 쉼터 (그 경로의 남은 시간에서 쉼터를 빼고 볼 때 쓴다) */
    private var restVia: LatLng? = null
    /** 쉬기 시작해 경로를 목적지로 바꾼 뒤 내비가 새 값을 줄 때까지 */
    private var restViaUntil = Long.MAX_VALUE
    private var extendCheckedAt = Long.MIN_VALUE / 2
    /** 화면에 띄운 '조금 더 돌기' 권유 문구 (권유를 거두면 같이 지운다) */
    private var offerMessage: String? = null
    /** 롱웨이 모드: 경로를 바꾼 뒤 내비가 새 값을 주면 그 도착 시각을 새 예정으로 삼는다 */
    private var retargetOnSettle = false
    /** 기다리는 코스에서 기다릴 시간이 모자라기 시작한 시각 */
    private var waitShortSince: Long? = null
    /** 목적지 가까이(150m 안)에 들어온 시각 */
    private var nearDestinationSince: Long? = null
    /** 픽업 알림: 도착 3분 전 문자를 보냈는지 (드라이브마다 한 번) */
    private var approachSent = false
    /** 픽업 알림: 마지막으로 '세운 곳'을 보낸 위치 (같은 자리에서 두 번 보내지 않게) */
    private var parkedNoticeAt: LatLng? = null
    /** 차가 달리는 방향 (위치가 15m 넘게 바뀔 때마다 잡는다). '자리 없음'에서 한 바퀴 돌 쪽을 정할 때 쓴다 */
    private var travelBearing: Double? = null
    private var bearingFrom: LatLng? = null

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
            DriveStep.CHOOSE -> {
                val asked = askedDirectAt
                if (refreshAsked && asked != null && now - asked >= config.directTimeWaitMs) {
                    // 다시 물은 바로 가는 시간에 답이 없으면 거리로 추정해 후보를 다시 낸다.
                    refreshAsked = false
                    askedDirectAt = null
                    estimateDirect()?.let { state = state.copy(directMs = it, directEstimated = true) }
                    makeOptions(now, refresh = true)
                } else {
                    maybeRefreshChoices(now)
                }
            }
            DriveStep.ACTIVE -> {
                expireMessage(now)
                track(now)
            }
            else -> Unit
        }
    }

    fun onLocation(now: Long, position: LatLng) {
        val from = bearingFrom
        if (from == null) {
            bearingFrom = position
        } else if (Geo.distanceMeters(from, position) >= BEARING_STEP_METERS) {
            travelBearing = Geo.bearingDegrees(from, position)
            bearingFrom = position
        }
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
        if (ms <= 0 || !destination.sameSpot(state.destination)) return
        when (state.step) {
            DriveStep.SETUP -> {
                if (state.directMs != null && !state.directEstimated) return
                state = state.copy(now = now, directMs = ms, directEstimated = false)
                refreshDefaultTarget(now)
                if (state.planPending) makeOptions(now)
            }
            DriveStep.CHOOSE -> if (refreshAsked) {
                refreshAsked = false
                askedDirectAt = null
                state = state.copy(now = now, directMs = ms, directEstimated = false)
                makeOptions(now, refresh = true)
            }
            else -> Unit
        }
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
        if (state.circling) {
            // '자리 없음'으로 도는 길의 경유지를 지났다: 이제 픽업할 곳으로 다시 간다.
            state = state.copy(viaCleared = true)
            setMessage(now, "한 바퀴 돌았어요. ${DriveText.toward(state.destination?.name ?: "목적지")} 다시 가요.")
            return
        }
        // 경유지를 지나는 것은 고른 코스대로 가는 것이라, 일찍 닿을 것 같으면 바로 '조금 더 돌기'를 권할 수 있다.
        state = state.copy(viaCleared = true)
        extendCheckedAt = Long.MIN_VALUE / 2
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
     * P단으로 세웠을 때. 픽업 알림이 켜진 온타임 모드에서 픽업할 곳 가까이(300m 안)면 세운 곳을 보낸다.
     * 아직 도착으로 보지 않았으면 여기서 도착으로 본다 (목적지 조금 앞 빈 곳에 세운 것, '자리 없음'으로 돌다 빈자리를 찾은 것).
     * 쉬다 가는 코스의 기다릴 곳, 쉬는 중, 고른 경유지가 아직 남았을 때(학원 앞을 지나 경유지로 가다 잠깐 세움)는 아니다.
     * 같은 자리(30m 안)에서 다시 세우면 또 보내지 않는다.
     *
     * @return 픽업할 곳에 세운 것이면 true. 앱은 레스트 모드에도 도착을 알려 '주차하셨네요'를 묻지 않게 한다
     *   (시간 모드가 목적지 150m 안에서 먼저 도착으로 봐서 내비의 도착 안내가 아직 없을 때도).
     */
    fun onParked(now: Long): Boolean {
        if (state.mode != DriveMode.ON_TIME) return false
        val to = state.pickupTo ?: return false
        val dest = state.destination ?: return false
        val at = state.position ?: return false
        val meters = Geo.distanceMeters(at, dest.position)
        if (meters > config.pickupParkMeters) return false
        when (state.step) {
            DriveStep.ACTIVE -> {
                if (state.paused || restStopHere(at) != null) return false
                if (state.viaActive && !state.circling) return false
                arrive(now)
            }
            DriveStep.ARRIVED -> Unit
            else -> return false
        }
        val last = parkedNoticeAt
        if (last == null || Geo.distanceMeters(last, at) >= PARKED_RESEND_METERS) {
            parkedNoticeAt = at
            send(now, PickupNoticeKind.PARKED, PickupText.parked(dest.name, meters, at), at)
            speak(Say.NOTICE, "${to}에게 보낼 세운 곳 문자를 띄웠어요.")
        }
        return true
    }

    /**
     * '자리 없음' (픽업 알림이 켜진 온타임 모드): 도착했는데 세울 곳이 없으면 주변을 한 바퀴(약 3분) 돌아 다시 온다.
     * 한 바퀴 도는 지점을 경유지로 넣고, 픽업할 사람에게 몇 시쯤 다시 오는지 알린다.
     * 목표 시각은 그대로 두어 늦어지면 늦는 만큼 보여 주되, 도는 동안은 늦어도 길을 줄이지 않는다 (세울 곳이 없어 도는 것이라).
     */
    fun circleForSpot(now: Long) {
        if (state.mode != DriveMode.ON_TIME) return
        val to = state.pickupTo ?: return
        val dest = state.destination ?: return
        val here = state.position ?: dest.position
        val near = Geo.distanceMeters(here, dest.position) <= config.pickupParkMeters
        val ok = state.step == DriveStep.ARRIVED ||
            (state.step == DriveStep.ACTIVE && near && !state.paused && !state.circling)
        if (!ok) return
        val speed = config.fallbackSpeedKmh / 3.6
        val heading = travelBearing ?: Geo.bearingDegrees(planFrom ?: here, dest.position)
        val point = TimeFitPlanner.circlePoint(dest.position, heading, config.circleMs, speed, config)
        val via = Place(CIRCLE_VIA_NAME, point, PlaceKind.WAYPOINT)
        val loopMs = TimeFitPlanner.pathMs(here, listOf(via), dest.position, speed, config)
        val backAt = now + loopMs
        val option = CourseOption(
            id = "circle",
            kind = CourseKind.DETOUR,
            title = "한 바퀴 돌고 다시 오기",
            reason = "세울 곳이 없어 주변을 약 ${DriveText.minutes(loopMs)}분 돌아요",
            via = listOf(via),
            addedMs = loopMs,
        )
        lateSince = null
        lateSteps = 0
        approachSent = true
        parkedNoticeAt = null
        nearDestinationSince = null
        planFrom = here
        planDirectMs = null
        offerMessage = null
        state = state.copy(
            now = now,
            step = DriveStep.ACTIVE,
            chosen = option,
            viaCleared = false,
            circling = true,
            arrivedAt = null,
            arrivalEstimate = backAt,
            status = FitStatus.UNKNOWN,
            diffMs = null,
            lateSince = null,
            extendOffered = false,
            extendOption = null,
        )
        setRoute(now, dest, listOf(via), loopMs)
        val back = DriveText.clock(backAt)
        setMessage(now, "세울 곳이 없어 한 바퀴 돌고 ${back}쯤 다시 와요.")
        speak(Say.VIA, "한 바퀴 돌고 ${DriveText.spokenClock(backAt)}쯤 다시 올게요. ${to}에게 보낼 문자도 띄웠어요.")
        send(now, PickupNoticeKind.CIRCLING, PickupText.circling(dest.name, back), null)
        track(now)
    }

    /**
     * '레스트 모드' 단계가 바뀔 때. 앱이 틱마다 부른다.
     * - 쉼터로 가는 중: 시간 맞추기를 멈춘다. 고른 경유지는 남겨 둔다 (안내를 취소하면 그 경로로 돌아감).
     * - 쉬는 중: 코스에서 계획한 곳이면 경유지를 지난 것으로 본다.
     * - 다시 주행: 쉬었으면 지금 위치에서 이어서 맞춘다 (남은 경유지는 아직 맞으면 남긴다). 안내만 취소했으면 원래 코스로 이어 간다.
     *
     * @param rest 쉬는 중이면 그 휴식 (졸려서 쉬는지, 어디서 쉬는지)
     * @param restTarget 쉼터로 안내하는 중이면 그 쉼터 (내비 경로에 들어가 있다)
     */
    fun onRestPhase(now: Long, phase: Phase, rest: RestSession? = null, restTarget: RestArea? = null) {
        if (phase == Phase.TO_REST_AREA && restTarget != null) {
            restVia = restTarget.position
            restViaUntil = Long.MAX_VALUE
        }
        val previous = restPhase
        if (previous == phase) return
        // 내비 경로가 바뀌기 전 값으로 바로 가는 시간과 정체 비율을 먼저 구해 둔다.
        val directNow = directFromHereMs()
        val jam = state.position?.let { naviJam(it, planSpeed(it)) } ?: 1.0
        restPhase = phase
        state = state.copy(now = now, paused = phase != Phase.DRIVING)
        if (state.step != DriveStep.ACTIVE) {
            if (phase == Phase.DRIVING) {
                restVia = null
                // 코스 후보 화면에서 쉬고 왔으면 후보를 새로 낸다.
                if (state.step == DriveStep.CHOOSE) planMadeAt = Long.MIN_VALUE / 2
            }
            return
        }
        when (phase) {
            Phase.TO_REST_AREA -> {
                withdrawOffer(now)
                setMessage(
                    now,
                    if (state.mode == DriveMode.LONGER) "쉬고 나면 고른 코스를 이어서 가요." else "쉬었다 가는 동안 시간 맞추기를 잠시 멈춰요.",
                )
            }
            Phase.RESTING -> {
                restViaUntil = now + ROUTE_SETTLE_MS
                restFatigue = rest?.fatigue == true
                if (restFatigue) restedThisDrive = true
                restAtStop = isPlannedStop(rest?.area)
                if (restAtStop) state = state.copy(viaCleared = true)
                withdrawOffer(now)
            }
            Phase.DRIVING -> {
                lateSince = null
                lateSteps = 0
                waitShortSince = null
                if (previous == Phase.RESTING) resumeAfterRest(now, directNow, jam) else cancelRestGuidance(now, directNow, jam)
                restVia = null
                restAtStop = false
                track(now)
            }
        }
    }

    /** 달리는 중이면 도착 시각과 지금 위치에서 바로 가는 시간. 레스트 모드가 쉬는 동안 알람을 여기에 맞춘다 */
    fun deadline(): TripDeadline? {
        if (state.step != DriveStep.ACTIVE) return null
        val target = state.targetAt ?: return null
        val strict = state.mode == DriveMode.ON_TIME
        val quiet = state.mode == DriveMode.LONGER && state.mood == Mood.DATE
        return TripDeadline(target, marginFor(), strict, remainingForRest(), quiet)
    }

    /**
     * 레스트 모드가 출발 시각·도착 예상에 쓰는 남은 시간: 지금 위치에서 바로 가는 시간.
     * 롱웨이 모드는 쉬고 나서 남은 경유지를 늘 거쳐 가므로, 쉬는 동안에는 그 길로 본다
     * (쉬는 화면의 '지금 출발하면 ○ 도착'이 출발한 뒤의 도착 예상과 맞게).
     */
    private fun remainingForRest(): Long? {
        val direct = directFromHereMs()
        if (state.mode != DriveMode.LONGER || !state.paused || restAtStop) return direct
        val jam = state.position?.let { naviJam(it, planSpeed(it)) } ?: 1.0
        return courseFromHereMs(direct, jam)
    }

    /** 내비 경로에 남아 있어야 할 경유지 (쉼터 안내를 취소하거나 쉬고 출발하면 이 경로로 안내한다) */
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
            flavor = if (mode == DriveMode.LONGER) autoFlavor(now, state.mood) else null,
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
            stopArriveAt = null,
            stopDepartAt = null,
            message = null,
            messageAt = null,
            arrivedAt = null,
            pickupTo = null,
            pickupNotices = emptyList(),
            circling = false,
        )
        if (dest != null) prepareDirect(now, dest)
        if (mode == DriveMode.ON_TIME) state = state.copy(targetAt = defaultTarget(now))
    }

    /** 온타임 모드: 픽업 알림을 받을 사람 (null이면 끈다). 설정·코스 고르기 화면에서 */
    fun setPickup(to: String?) {
        if (state.mode != DriveMode.ON_TIME) return
        if (state.step != DriveStep.SETUP && state.step != DriveStep.CHOOSE) return
        state = state.copy(pickupTo = to?.takeIf { it.isNotBlank() })
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

    /** 시각 칩 4개 (바로 가는 시간에 따라 10분·30분·1시간 간격) */
    fun targetChoices(now: Long): List<Long> = targetChoices(now, state.directMs ?: estimateDirect() ?: DEFAULT_DIRECT_MS)

    fun setExtraMinutes(minutes: Int) {
        if (state.step == DriveStep.SETUP) state = state.copy(extraMinutes = minutes.coerceIn(5, 180))
    }

    fun setMood(mood: Mood) {
        state = state.copy(mood = mood)
        if (state.mode == DriveMode.LONGER && !flavorTouched && state.step == DriveStep.SETUP) {
            state = state.copy(flavor = autoFlavor(state.now, mood))
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
        restStopReached = false
        waitShortSince = null
        retargetOnSettle = false
        routeChangedAt = now
        // 계획한 남은 운전 시간 (기다리는 시간은 [withStay]가 따로 더한다)
        routePlanMs = direct + option.addedMs - option.stayMs
        extendQuietUntil = now + config.extendAfterChooseQuietMs
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
        // 처음부터 늦는 코스면 출발할 때 말하므로, 1분 뒤 '늦을 것 같아요'를 또 하지 않는다.
        lateSpoken = state.mode == DriveMode.ON_TIME && target != null && now + direct + option.addedMs - target > config.lateToleranceMs
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
     * 롱웨이 모드에서는 '바로 집으로': 경유지를 빼고, 내비가 새 경로를 계산하면 그 도착 시각을 새 예정으로 삼는다.
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
                state = state.copy(viaCleared = true)
                withdrawOffer(now)
                if (state.mode == DriveMode.LONGER) {
                    if (direct != null) state = state.copy(targetAt = now + direct)
                    retargetOnSettle = true
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

    /** '조금 더 돌기'(기다리는 코스면 '기다렸다 가기'): 남는 시간을 채울 코스로 바로 바꾼다 (마음이 바뀌면 '바로 가기'). */
    fun extend(now: Long) {
        if (state.step != DriveStep.ACTIVE || state.paused || state.circling) return
        val found = extendCandidates(now)
        if (found == null) {
            withdrawOffer(now)
            setMessage(now, "더 돌 만한 코스가 없어요. 그대로 갈게요.")
            return
        }
        // 버튼에 보여 준 코스가 아직 맞으면 그 코스로 (보여 준 것과 다른 곳으로 가지 않게)
        val offered = state.extendOption
        val option = found.first.firstOrNull { offered != null && sameVia(it, offered) } ?: found.first.first()
        applyCourse(now, option, found.second)
        lateSteps = 0
        val route = routePhrase(option)
        setMessage(now, "${route}로 바꿨어요. 마음이 바뀌면 '바로 가기'를 눌러 주세요.")
        speak(Say.VIA, "${route}로 바꿀게요.")
        track(now)
    }

    /**
     * 달리는 중 시간 더하기.
     * - 온타임 모드 [+5분][+10분]: 약속이 늦춰졌을 때. 목표를 미루고, 늘어난 시간을 같은 종류의 코스(경치는 경치, 기다리기는 기다리기)로 채운다.
     *   기다리는 중이거나 기다리는 코스로 가는 중이면 기다리는 시간만 늘린다.
     * - 롱웨이 모드 [+15분 더]: 더 달리고 싶을 때. 늘어난 시간으로 코스를 다시 고르고, 그 코스가 걸리는 만큼을 새 예정으로 삼는다
     *   (근처 지점으로 다 못 채우면 실제로 늘어난 만큼만 알려 준다).
     */
    fun addTime(now: Long, minutes: Int) {
        if (state.step != DriveStep.ACTIVE) return
        val before = state.targetAt ?: return
        val longer = state.mode == DriveMode.LONGER
        val target = before + minutes * MINUTE_MS
        if (state.paused) {
            // 쉬는(기다리는) 중: 도착 목표만 미룬다. 레스트 모드가 출발 시각을 다시 맞춰 함께 말한다.
            if (longer) return
            state = state.copy(targetAt = target)
            setMessage(now, "도착 목표를 늦췄어요 (${DriveText.clock(target)}).")
            return
        }
        if (state.circling) {
            // '자리 없음'으로 도는 중: 도는 길은 그대로 두고 목표만 미룬다.
            state = state.copy(targetAt = target)
            setMessage(now, "도착 목표를 늦췄어요 (${DriveText.clock(target)}).")
            track(now)
            return
        }
        val current = state.chosen.takeIf { state.viaActive }
        val currentEta = state.arrivalEstimate ?: before
        lateSince = null
        lateSpoken = false
        state = state.copy(targetAt = target)
        withdrawOffer(now)
        if (!longer && current?.kind == CourseKind.REST_STOP) {
            // 기다리는 코스: 기다리는 시간이 늘어날 뿐 코스는 그대로
            setMessage(now, "도착 목표를 늦췄어요 (${DriveText.clock(target)}). 기다리는 시간이 늘어요.")
            speak(Say.VIA, "도착 목표를 ${DriveText.toward(DriveText.spokenClock(target))} 늦췄어요.")
            track(now)
            return
        }
        // 늘어난 시간을 채울 코스: 지금 코스가 아닌 같은 종류의 코스 중 가장 길게(목표 안에서) 달리는 코스.
        // 더한 시간의 1/3도 못 채우면 코스를 바꾸지 않는다 (바꿔도 거의 같음). '도심 피하기'면 도심을 지나는 코스는 빼고 본다.
        val family = courseFamily()
        val avoidCity = wish().avoidCity
        val from = state.position
        val destAt = state.destination?.position
        val refill = coursesFromHere(now, exclude = current)?.let { (options, direct) ->
            options.filter { it.kind == family && now + direct + it.addedMs > currentEta + minutes * MINUTE_MS / 3 }
                .filter { !avoidCity || from == null || destAt == null || TimeFitPlanner.avoidsCity(from, it, destAt) }
                .maxByOrNull { it.addedMs }
                ?.let { it to direct }
        }
        if (refill == null) {
            if (longer) {
                state = state.copy(targetAt = before)
                setMessage(now, "${minutes}분 안에 더 달릴 코스가 없어요. 지금 코스로 가요.")
            } else {
                // 같은 종류로 더 돌 코스가 없으면 목표만 미룬다. 그만큼 일찍 닿는 것을 숨기지 않는다.
                val early = -DriveText.gapMs(currentEta, target)
                val tail = if (early > marginFor() + 2 * MINUTE_MS) " 지금 코스로는 약 ${DriveText.duration(early)} 일찍 도착해요." else ""
                setMessage(now, "도착 목표를 늦췄어요 (${DriveText.clock(target)}).$tail")
                speak(Say.VIA, "도착 목표를 ${DriveText.toward(DriveText.spokenClock(target))} 늦췄어요.$tail")
            }
            track(now)
            return
        }
        val (option, direct) = refill
        applyCourse(now, option, direct)
        lateSteps = 0
        // 픽업 알림: 코스가 길어져 도착 시각이 바뀌므로 마지막 구간에서 '곧 도착' 문자를 다시 띄운다.
        approachSent = false
        val route = routePhrase(option)
        if (longer) {
            // 롱웨이 모드는 고른 코스가 걸리는 만큼을 새 예정으로 (근처 지점으로 다 못 채우면 실제로 늘어난 만큼만 알린다)
            val planned = now + direct + option.addedMs
            val gained = planned - currentEta
            state = state.copy(targetAt = planned, extraMinutes = state.extraMinutes + minutes)
            setMessage(
                now,
                if (gained >= minutes * MINUTE_MS * 7 / 10) {
                    "${minutes}분 더 달려요. ${route}로 바꿨어요."
                } else {
                    // 더한 시간 안에 맞는 코스 중 가장 긴 것 (더 길게 달릴 코스는 시간을 더 더하면 나올 수 있다)
                    "약 ${DriveText.duration(gained)} 더 달려요 (${minutes}분 안에 맞는 코스). ${route}로 바꿨어요."
                },
            )
            speak(Say.VIA, "${route}로 바꿀게요.")
        } else {
            setMessage(now, "도착 목표를 늦추고(${DriveText.clock(target)}) ${route}로 바꿨어요.")
            speak(Say.VIA, "${DriveText.spokenClock(target)}에 맞춰 ${route}로 바꿀게요.")
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

    /**
     * 저장한 설정을 한 번에: 모드를 열고, 끝낼 곳·시각을 맞춰 바로 코스를 찾는다.
     * 저장한 시각이 지났으면 12시간 안이면 다음 날로 보고 (자정 넘는 픽업), 아니면 시각만 다시 고르게 한다.
     */
    fun applySaved(now: Long, trip: SavedTrip) {
        openMode(now, trip.mode)
        state = state.copy(
            destination = trip.place,
            directMs = null,
            directEstimated = false,
            pickupTo = trip.pickupTo.takeIf { trip.mode == DriveMode.ON_TIME },
        )
        prepareDirect(now, trip.place)
        trip.mood?.let { state = state.copy(mood = it) }
        trip.extraMinutes?.let { state = state.copy(extraMinutes = it.coerceIn(5, 180)) }
        state = state.copy(onTimeStyle = trip.onTimeStyle ?: defaultStyle(trip.place))
        styleTouched = trip.onTimeStyle != null
        if (trip.mode == DriveMode.LONGER) {
            flavorTouched = trip.flavor != null
            state = state.copy(flavor = trip.flavor ?: autoFlavor(now, state.mood))
        }
        if (trip.mode == DriveMode.ON_TIME) {
            val minute = trip.minuteOfDay
            if (minute == null) {
                refreshDefaultTarget(now)
            } else {
                var at = DriveText.todayAt(now, minute)
                if (at <= now && at + DAY_MS - now <= HALF_DAY_MS) at += DAY_MS
                // 조금 전에 지났으면 (늦게 나온 날) 그 시각을 그대로 두고 바로 가는 길을 보여 준다.
                if (at <= now && now - at > SAVED_JUST_PASSED_MS) {
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

        /** 픽업 알림을 보냈을 때 (누구에게 무엇을 알렸는지 짧게) */
        NOTICE,
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
            DriveMode.LONGER -> state.mood == Mood.SOLO && kind == Say.VIA
            null -> false
        }
        if (allowed) effects += DriveEffect.Speak(text)
    }

    private fun setMessage(now: Long, text: String?) {
        state = state.copy(message = text, messageAt = if (text != null) now else null)
        offerMessage = null
    }

    private fun expireMessage(now: Long) {
        val at = state.messageAt ?: return
        if (state.message != null && now - at >= config.messageTtlMs) {
            state = state.copy(message = null, messageAt = null)
            offerMessage = null
        }
    }

    private fun marginFor(): Long = if (state.mode == DriveMode.ON_TIME) config.marginMs else 0L

    private fun autoFlavor(now: Long, mood: Mood): CourseFlavor =
        CourseFlavor.auto(DriveText.hourOfDay(now), mood, DriveText.monthOf(now))

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

    /** 기본 목표: 시각 칩 가운데 (바로 가면 도착하는 시각 + 20분)에 가장 가까운 것 (칩에 ◀ 표시가 늘 붙게) */
    private fun defaultTarget(now: Long): Long {
        val direct = state.directMs ?: estimateDirect() ?: DEFAULT_DIRECT_MS
        val wanted = now + direct + DEFAULT_SPARE_MS
        return targetChoices(now, direct).minByOrNull { abs(it - wanted) } ?: roundUp(wanted, FIVE_MINUTES)
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
            if (slack >= config.minSlackMs) {
                val near = TimeFitPlanner.hasWaitPlaceNear(dest, restPlaces)
                notes?.add(if (near) "기다릴 시간이 짧아 다른 코스를 보여 드려요." else "목적지 근처에 기다릴 곳이 없어 다른 코스를 보여 드려요.")
                if (near) {
                    // 기다릴 곳은 있는데 시간이 짧으면 바로 가기를 먼저 보여 준다.
                    val others = TimeFitPlanner.options(from, dest, direct, slack, points, stopsFor(state.mode), config, maxAdded(), wish())
                    return (listOf(TimeFitPlanner.direct(slack)) + others.filter { it.kind != CourseKind.DIRECT }).take(3)
                }
            }
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

    /** @param refresh 후보 화면에 머무는 동안 저절로 다시 내는지 (남아 있는 코스의 자리를 지킨다) */
    private fun makeOptions(now: Long, refresh: Boolean = false) {
        val dest = state.destination ?: return
        val direct = state.directMs ?: return
        val longer = state.mode == DriveMode.LONGER
        val target = if (longer) now + direct + state.extraMinutes * MINUTE_MS else state.targetAt ?: defaultTarget(now)
        val slack = if (longer) state.extraMinutes * MINUTE_MS else TimeFitPlanner.slackMs(now, target, direct, config)
        val from = state.position
        planFrom = from
        planDirectMs = direct
        planMadeAt = now
        val notes = ArrayList<String>()
        val fresh = if (from == null) {
            notes += "현재 위치를 몰라 바로 가는 코스만 보여요."
            listOf(directOption(now, slack))
        } else {
            planOptions(from, dest, direct, slack, notes).map { if (it.kind == CourseKind.DIRECT) directOption(now, slack) else it }
        }
        // 후보 화면에 머무는 동안 다시 낸 후보: 남아 있는 코스는 자리를 그대로 두고, 바뀌었으면 알린다
        // (누르려던 자리의 코스가 말없이 바뀌지 않게).
        val previous = if (refresh && state.step == DriveStep.CHOOSE) state.options else emptyList()
        val options = if (previous.isEmpty()) {
            fresh
        } else {
            // 빠진 코스 자리에만 새 코스를 넣는다 (남은 코스는 번호가 그대로).
            val newcomers = fresh.filter { o -> previous.none { it.id == o.id } }.toMutableList()
            val merged = previous.mapNotNull { old ->
                fresh.firstOrNull { it.id == old.id } ?: newcomers.removeFirstOrNull()
            } + newcomers
            if (merged.map { it.id } != previous.map { it.id }) notes += "시간이 지나 후보를 다시 찾았어요."
            merged
        }
        if (!longer && target <= now) notes += "목표 시각(${DriveText.clock(target)})이 이미 지났어요. 바로 가는 길을 보여 드려요."
        if (state.directEstimated) notes += "내비 예상 시간을 받지 못해 거리로 추정했어요."
        if (!longer && state.onTimeStyle == OnTimeStyle.LOOP && slack > config.maxOnTimeCourseMs &&
            options.any { it.kind == CourseKind.REST_STOP }
        ) {
            notes += "남는 시간이 길어 쉬었다 가는 곳을 먼저 보여 드려요."
        }
        if (!longer && slack >= config.lateStartNoteMs) {
            // 바로 가는 길 기준으로 가장 늦게 떠나도 되는 시각. 분은 내려서 보여 준다 (그 분에 떠나도 늦지 않게).
            notes += "지금 길이면 ${DriveText.clock(now + slack - 30 * SECOND_MS)}에 출발해도 맞아요."
        }
        if (longer && from != null) longerNote(options, slack)?.let { notes += it }
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

    /** 롱웨이 모드 후보가 바란 시간을 다 못 채울 때 알림 (고른 길 칩 기준) */
    private fun longerNote(options: List<CourseOption>, slack: Long): String? {
        val drives = options.filter { it.kind != CourseKind.DIRECT }
        val best = drives.maxOfOrNull { it.addedMs } ?: 0L
        if (best < MINUTE_MS) return "이 근처에는 더 달릴 코스가 없어요."
        val flavor = state.flavor
        if (flavor != null) {
            val matching = drives.filter { o -> o.tags.any { it in flavor.tags } }
            if (matching.isEmpty()) return "${flavor.label} 코스가 근처에 없어 다른 곳을 보여 드려요."
            val bestMatch = matching.maxOf { it.addedMs }
            if (bestMatch < slack * 7 / 10) return "${flavor.label} 코스는 최대 ${DriveText.minutes(bestMatch)}분이에요."
            return null
        }
        return if (best < slack * 7 / 10) "이 근처는 최대 ${DriveText.minutes(best)}분 코스예요." else null
    }

    /** 코스 후보 화면에 오래 머물면 1분마다 후보를 다시 낸다 (시간이 지나 맞지 않게 된 코스를 고르지 않게). */
    private fun maybeRefreshChoices(now: Long) {
        if (state.chosen != null || state.paused || refreshAsked) return
        if (now - planMadeAt < CHOOSE_REFRESH_MS) return
        val dest = state.destination ?: return
        val remain = state.naviRemainingMs
        val from = planFrom
        val position = state.position
        val moved = from != null && position != null && Geo.distanceMeters(from, position) > CHOOSE_MOVED_METERS
        when {
            remain != null && dest.sameSpot(state.naviDestination) -> state = state.copy(directMs = remain, directEstimated = false)
            moved -> {
                // 자리를 옮겼으면 바로 가는 시간을 다시 묻고, 답이 오면 후보를 낸다.
                planMadeAt = now
                askedDirectAt = now
                refreshAsked = true
                effects += DriveEffect.AskDirectTime(dest)
                return
            }
        }
        makeOptions(now, refresh = true)
    }

    private fun track(now: Long) {
        val dest = state.destination ?: return
        val position = state.position
        // 쉬는(기다리는) 중이거나 목적지 바로 옆의 기다릴 곳에 와 있으면 도착이 아니다 (거기서 세워 기다린다).
        val holding = state.paused || restStopHere(position) != null
        val near = position != null && Geo.distanceMeters(position, dest.position) <= config.arriveMeters
        if (!near) nearDestinationSince = null
        if (!holding && near) {
            // 경유지로 가는 길에 목적지 옆을 지나는 것은 도착이 아니다: 경유지가 남아 있으면 1분 머물러야 도착으로 본다.
            // '자리 없음'으로 도는 중에는 도는 길(경유지)을 지나야 다시 도착으로 본다 (학원 앞 줄에 1분 넘게 서 있어도).
            val since = nearDestinationSince ?: now.also { nearDestinationSince = it }
            val lingered = !state.circling && now - since >= PASSING_BY_MS
            if (!state.viaActive || lingered) {
                arrive(now)
                return
            }
        }
        val navi = naviRemainMs()
        if (!holding && navi != null && navi <= ARRIVE_REMAIN_MS && !state.viaActive) {
            arrive(now)
            return
        }
        if (state.targetAt == null) return
        checkRestStopPassed(now, position)
        val settled = now - routeChangedAt >= ROUTE_SETTLE_MS
        // 경로를 막 바꿨으면 내비가 새 경로를 계산할 때까지 계획한 값으로 보여 준다.
        val driving = (if (settled) null else plannedRemainingMs(now)) ?: drivingRemainingMs() ?: return
        val remain = withStay(now, driving)
        if (settled && retargetOnSettle && !state.paused) {
            retargetOnSettle = false
            if (state.mode == DriveMode.LONGER) state = state.copy(targetAt = now + remain)
        }
        val target = state.targetAt ?: return
        val eta = now + remain
        val diff = eta - target
        val status = statusFor(diff)
        state = state.copy(arrivalEstimate = eta, diffMs = diff, status = status)
        updateStopTimes(now, driving)
        maybeSendApproach(now, driving, eta)
        if (state.paused || !settled) {
            if (state.lateSince != null) state = state.copy(lateSince = null)
            return
        }
        if (checkWaitStillWorth(now, driving)) return
        if (state.circling) {
            // '자리 없음'으로 한 바퀴 도는 중에는 늦어도 길을 줄이거나 더 돌기를 권하지 않는다.
            lateSince = null
            if (state.lateSince != null) state = state.copy(lateSince = null)
            withdrawOffer(now)
            return
        }
        if (status == FitStatus.LATE) onLate(now, diff) else lateSince = null
        if (state.lateSince != lateSince) state = state.copy(lateSince = lateSince)
        when {
            status == FitStatus.EARLY -> maybeOfferExtend(now, diff, remain)
            state.extendOffered -> withdrawOffer(now)
        }
    }

    /**
     * 픽업 알림: 마지막 구간(남은 경유지 없음)에서 도착까지 3분 남으면 도착 예정 시각·만날 곳·지금 차 위치를 보낸다.
     * 드라이브마다 한 번이고, 쉬는 중이나 '자리 없음'으로 도는 중에는 보내지 않는다 (도는 동안은 그때 따로 알린다).
     */
    private fun maybeSendApproach(now: Long, driving: Long, eta: Long) {
        val to = state.pickupTo ?: return
        if (approachSent || state.circling || state.paused || state.viaActive) return
        if (driving > config.pickupNoticeMs) return
        val dest = state.destination ?: return
        approachSent = true
        send(now, PickupNoticeKind.APPROACH, PickupText.approach(dest.name, DriveText.clock(eta), state.position), state.position)
        speak(Say.NOTICE, "${to}에게 보낼 도착 문자를 띄웠어요.")
    }

    /** 픽업 알림 한 통을 남기고 앱에 보내 달라고 한다 (시연: 앱은 화면과 안내 기록에만 남긴다). */
    private fun send(now: Long, kind: PickupNoticeKind, text: String, position: LatLng?) {
        val to = state.pickupTo ?: return
        val notice = PickupNotice(now, kind, to, text, position)
        state = state.copy(pickupNotices = state.pickupNotices + notice)
        effects += DriveEffect.SendPickup(notice)
    }

    private fun statusFor(diff: Long): FitStatus = when {
        diff > lateToleranceMs() -> FitStatus.LATE
        diff < -config.earlyToleranceMs -> FitStatus.EARLY
        else -> FitStatus.ON_TIME
    }

    /** 늦었다고 보는 폭: 온타임 30초, 롱웨이 max(10분, 더 달리기로 한 시간의 1/3) */
    private fun lateToleranceMs(): Long =
        if (state.mode == DriveMode.LONGER) {
            max(config.longerLateToleranceMs, state.extraMinutes * MINUTE_MS / 3)
        } else {
            config.lateToleranceMs
        }

    /**
     * 늦음이 [DriveConfig.lateObserveMs] 넘게 이어지면:
     * 경유지가 있으면 지금 위치에서 더 짧은 코스로 바꾸고, 없거나 그래도 늦으면 경유지를 뺀다.
     * 롱웨이 모드는 짧은 코스가 없을 때만 뺀다. 경유지 없이도 늦으면 온타임 모드에서만 한 번 알린다.
     * 왜 늦는지는 알 수 없으므로 이유를 붙이지 않는다.
     */
    private fun onLate(now: Long, diff: Long) {
        val since = lateSince ?: now.also { lateSince = it }
        if (now - since < config.lateObserveMs) return
        val dest = state.destination ?: return
        val longer = state.mode == DriveMode.LONGER
        if (state.viaActive) {
            lateSince = null
            val tryShorter = longer || lateSteps == 0
            val shorter = if (tryShorter) courseFromHere(now, exclude = state.chosen) else null
            if (shorter != null) {
                applyCourse(now, shorter.first, shorter.second)
                lateSteps = 1
                val route = routePhrase(shorter.first)
                setMessage(now, if (longer) "예정보다 늦어져 ${route}로 바꿨어요." else "늦지 않게 ${route}로 바꿨어요.")
                speak(Say.VIA, if (longer) "${route}로 바꿀게요." else "늦지 않게 ${route}로 바꿀게요.")
            } else {
                setRoute(now, dest, emptyList(), directFromHereMs())
                lateSteps = 2
                state = state.copy(viaCleared = true)
                withdrawOffer(now)
                setMessage(now, if (longer) "예정보다 늦어져 경유지를 빼고 바로 가요." else "늦지 않게 경유지를 빼고 바로 가요.")
                speak(Say.VIA, if (longer) "경유지를 빼고 바로 갈게요." else "늦지 않게 경유지를 빼고 바로 갈게요.")
            }
            return
        }
        // 음성은 2분 넘게 늦을 때만 (내비 예상은 1분쯤 흔들려서 '늦어요' 뒤에 '딱 맞춰'가 되기 쉽다). 화면 칩은 그대로.
        if (state.mode == DriveMode.ON_TIME && !lateSpoken && diff > LATE_SPEECH_MS) {
            lateSpoken = true
            // 화면 칩이 늦는 분을 계속 보여 주므로 문구에는 숫자를 넣지 않는다 (시간이 지나 서로 달라지지 않게).
            setMessage(now, "도착이 늦어질 것 같아요. 서두르지 말고 안전하게 가세요.")
            speak(Say.LATE, "약 ${DriveText.duration(diff)} 늦을 것 같아요.")
        }
    }

    /**
     * 기다리는 코스에서 길이 막혀 기다릴 시간이 3분 밑으로 줄면 들르지 않고 목적지로 바로 간다
     * (주차장은 들어갔다 나오는 데도 시간이 걸린다). 1분 지켜본 뒤 바꾼다. 바꿨으면 true
     */
    private fun checkWaitStillWorth(now: Long, driving: Long): Boolean {
        val option = state.chosen
        val target = state.targetAt
        if (state.mode != DriveMode.ON_TIME || !state.viaActive || option?.kind != CourseKind.REST_STOP || restStopReached || target == null) {
            waitShortSince = null
            return false
        }
        val stay = target - marginFor() - now - driving
        if (stay >= config.minWaitStayMs) {
            waitShortSince = null
            return false
        }
        val since = waitShortSince ?: now.also { waitShortSince = it }
        if (now - since < WAIT_SHORT_OBSERVE_MS) return false
        waitShortSince = null
        val dest = state.destination ?: return false
        setRoute(now, dest, emptyList(), directFromHereMs())
        state = state.copy(viaCleared = true)
        withdrawOffer(now)
        setMessage(now, "기다릴 시간이 거의 없어 ${DriveText.toward(dest.name)} 바로 가요.")
        speak(Say.VIA, "기다릴 시간이 없어졌어요. ${DriveText.toward(dest.name)} 바로 갈게요.")
        return true
    }

    /** 기다리는 코스: 그곳에 닿는 시각과 떠날 시각 (화면에 '4:30 도착 → 4:40 출발') */
    private fun updateStopTimes(now: Long, driving: Long) {
        val option = state.chosen
        val area = option?.restArea
        val dest = state.destination
        val position = state.position
        val target = state.targetAt
        if (!state.viaActive || state.paused || option?.kind != CourseKind.REST_STOP || area == null ||
            dest == null || position == null || target == null
        ) {
            if (state.stopArriveAt != null || state.stopDepartAt != null) state = state.copy(stopArriveAt = null, stopDepartAt = null)
            return
        }
        val speed = planSpeed(position)
        val toStop = if (restStopReached) 0L else TimeFitPlanner.pathMs(position, emptyList(), area.position, speed, config)
        val fromStop = TimeFitPlanner.pathMs(area.position, emptyList(), dest.position, speed, config)
        val scale = if (toStop + fromStop > 0) driving.toDouble() / (toStop + fromStop) else 1.0
        val arriveAt = now + (toStop * scale).toLong()
        // 떠날 시각은 레스트 모드의 출발 알림처럼 분 단위로 내려 맞춘다.
        val departAt = max(arriveAt, (target - marginFor() - (fromStop * scale).toLong()) / MINUTE_MS * MINUTE_MS)
        state = state.copy(stopArriveAt = arriveAt, stopDepartAt = departAt)
    }

    /**
     * 일찍 도착할 것 같으면, 더 돌 코스(기다리는 코스 포함)가 실제로 있을 때만 화면으로 권한다.
     * 고른 경유지로 가는 중에는 권하지 않고, 지난 뒤에 권한다 ([extendCandidates]).
     * 권하는 동안에는 1분마다 다시 보되, 보여 준 코스가 아직 맞으면 그대로 둔다 (버튼이 말없이 바뀌지 않게).
     * 새로 권하는 것은 목적지까지 [DriveConfig.extendMinRemainMs] 넘게 남았을 때만, 이미 권한 것은 3분 남을 때까지 둔다.
     */
    private fun maybeOfferExtend(now: Long, diff: Long, remain: Long) {
        val quiet = now < extendQuietUntil || now - viaChangedAt < config.extendQuietMs
        val offered0 = state.extendOption
        val tooLate = if (offered0 == null) remain < config.extendMinRemainMs else remain < EXTEND_KEEP_MIN_REMAIN_MS
        if (tooLate || quiet) {
            withdrawOffer(now)
            return
        }
        if (now - extendCheckedAt < EXTEND_RECHECK_MS) return
        extendCheckedAt = now
        val found = extendCandidates(now)
        if (found == null) {
            withdrawOffer(now)
            return
        }
        val offered = state.extendOption
        val keep = offered?.let { o -> found.first.firstOrNull { sameVia(it, o) } }
        if (offered != null && keep == null) {
            // 권한 코스가 맞지 않게 됐으면 다른 곳으로 말없이 바꾸지 않고 거둔다. 잠시 뒤 다시 본다.
            withdrawOffer(now)
            extendQuietUntil = now + EXTEND_SWITCH_QUIET_MS
            return
        }
        val option = keep ?: found.first.first()
        state = state.copy(extendOffered = true, extendOption = option)
        if (keep == null) {
            // 화면으로만 알린다 (음성 없음). 다 못 채우면 '시간이 맞아요' 대신 얼마나 일찍 닿는지 쓴다.
            val via = option.via.firstOrNull()?.name?.let { RestMateText.spokenName(it) }.orEmpty()
            val how = if (option.kind == CourseKind.REST_STOP) "${via}에서 기다렸다 가면" else "${DriveText.withObject(via)} 거쳐 가면"
            val target = state.targetAt
            val early = if (target != null) -DriveText.gapMs(now + found.second + option.addedMs, target) else 0L
            val result = if (early > marginFor() + 2 * MINUTE_MS) "약 ${DriveText.duration(early)} 일찍 닿아요." else "시간이 맞아요."
            setMessage(now, "${DriveText.duration(diff)} 일찍 도착해요. $how $result")
            offerMessage = state.message
        }
    }

    /** 권유를 거둔다. 권유 문구가 아직 떠 있으면 같이 지운다 (없는 버튼을 찾지 않게). */
    private fun withdrawOffer(now: Long) {
        if (!state.extendOffered && state.extendOption == null) return
        val showing = offerMessage != null && state.message == offerMessage
        state = state.copy(extendOffered = false, extendOption = null)
        if (showing) setMessage(now, null)
        offerMessage = null
    }

    /**
     * '조금 더 돌기'로 바꿀 코스들 (좋은 순)과 바로 가는 시간. 지금보다 3분 넘게 늦게 끝나는 코스만.
     * 고른 경유지(경치·기다리는 곳)로 가는 중이면 권하지 않는다: 바꾸면 고른 경유지를 건너뛰게 된다.
     * 경유지를 지난 뒤에 남는 시간이 있으면 그때 권한다.
     * 졸려서 쉰 뒤에는 쉬다 가는(기다리는) 코스만 권한다.
     */
    private fun extendCandidates(now: Long): Pair<List<CourseOption>, Long>? {
        val current = state.chosen.takeIf { state.viaActive }
        if (current != null && current.via.isNotEmpty()) return null
        val eta = state.arrivalEstimate ?: return null
        val found = coursesFromHere(now, exclude = current)
        val direct = found?.second ?: directFromHereMs() ?: return null
        fun gains(o: CourseOption) = now + direct + o.addedMs >= eta + EXTEND_MIN_GAIN_MS
        var pool = found?.first.orEmpty().filter(::gains)
        if (restedThisDrive) pool = pool.filter { it.kind == CourseKind.REST_STOP }
        if (pool.isEmpty() && state.mode == DriveMode.ON_TIME) {
            // 더 돌 경치 코스가 없으면 목적지 근처에서 기다렸다 가기 (온타임 모드는 정각 도착이 먼저)
            val from = state.position
            val dest = state.destination
            val target = state.targetAt
            if (from != null && dest != null && target != null) {
                val slack = target - now - direct - marginFor()
                pool = TimeFitPlanner.waitNearOptions(from, dest, direct, slack, restPlaces, config)
                    .filter { !sameVia(it, current) && gains(it) }
            }
        }
        if (pool.isEmpty()) return null
        return pool to direct
    }

    /** 시간 더하기로 채울 코스 종류: 지금 코스와 같은 종류 (경유지가 없으면 온타임 '근처에서 기다리기'는 기다리기, 아니면 경치) */
    private fun courseFamily(): CourseKind {
        val current = state.chosen.takeIf { state.viaActive }
        return when {
            current != null -> if (current.kind == CourseKind.REST_STOP) CourseKind.REST_STOP else CourseKind.SCENIC
            state.mode == DriveMode.ON_TIME && state.onTimeStyle == OnTimeStyle.WAIT_NEAR -> CourseKind.REST_STOP
            else -> CourseKind.SCENIC
        }
    }

    /**
     * 지금 위치에서 남는 시간으로 코스를 하나 고른다 (늦을 때 짧은 코스).
     * 바로 가는 시간(정체 반영)도 함께 돌려준다. 고를 코스가 없으면 null
     */
    private fun courseFromHere(now: Long, exclude: CourseOption? = null): Pair<CourseOption, Long>? {
        val (options, direct) = coursesFromHere(now, exclude) ?: return null
        return options.first() to direct
    }

    /**
     * [courseFromHere]의 후보 전부 (좋은 순). 고를 코스가 없으면 null.
     * 달리는 중에는 계산으로 만든 '돌아가는 지점'은 쓰지 않는다 (골목·단지 안일 수 있음).
     */
    private fun coursesFromHere(now: Long, exclude: CourseOption? = null): Pair<List<CourseOption>, Long>? {
        val from = state.position ?: return null
        val dest = state.destination ?: return null
        val target = state.targetAt ?: return null
        val direct = directFromHereMs() ?: return null
        val slack = target - now - direct - marginFor()
        if (slack < config.minSlackMs) return null
        val options = planOptions(from, dest, direct, slack)
            .filter { it.kind != CourseKind.DIRECT && it.kind != CourseKind.DETOUR && !sameVia(it, exclude) }
        if (options.isEmpty()) return null
        return options to direct
    }

    private fun applyCourse(now: Long, option: CourseOption, directMs: Long) {
        val dest = state.destination ?: return
        planFrom = state.position
        planDirectMs = directMs
        restStopReached = false
        waitShortSince = null
        viaChangedAt = now
        setRoute(now, dest, option.via, directMs + option.addedMs - option.stayMs)
        state = state.copy(chosen = option, viaCleared = option.via.isEmpty())
        withdrawOffer(now)
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

    /** 코스를 말로: "낙산공원을 거쳐 가는 길", "탄천 공영주차장에서 기다렸다 가는 길" ('…로 바꿀게요' 앞에 쓴다) */
    private fun routePhrase(option: CourseOption): String {
        val via = option.via.firstOrNull()?.name?.let { RestMateText.spokenName(it) }
        return when {
            via == null || option.kind == CourseKind.DIRECT -> "바로 가는 길"
            option.kind == CourseKind.DETOUR -> "주변으로 돌아가는 길"
            option.kind == CourseKind.REST_STOP -> "${via}에서 ${restVerb(option)} 가는 길"
            else -> "${DriveText.withObject(via)} 거쳐 가는 길"
        }
    }

    /** 쉬다 가는 곳에서 하는 일: 시내 공원·주차장은 기다렸다, 휴게소는 쉬었다 */
    private fun restVerb(option: CourseOption): String {
        val area = option.restArea
        return if (option.restKind != RestKind.NAP && area != null && RestAreaFinder.isCityPlace(area)) "기다렸다" else "쉬었다"
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

    /** 쉬는 곳이 코스에서 계획한 쉬다 가는 곳인지 */
    private fun isPlannedStop(area: RestArea?): Boolean {
        val option = state.chosen ?: return false
        if (!state.viaActive || option.kind != CourseKind.REST_STOP) return false
        val stop = option.restArea ?: return false
        if (area != null && area.id == stop.id) return true
        val at = state.position ?: return false
        return Geo.distanceMeters(at, stop.position) <= REST_STOP_NEAR_METERS
    }

    /** 쉼터 안내를 취소: 원래 코스로 이어 간다. 내비가 새 경로를 계산하기 전까지 원래 코스의 계획 값을 보여 준다. */
    private fun cancelRestGuidance(now: Long, direct: Long?, jam: Double) {
        routeChangedAt = now
        viaChangedAt = now
        routePlanMs = courseFromHereMs(direct, jam)
        setMessage(now, "쉼터 안내를 취소했어요. 원래 코스로 이어서 가요.")
    }

    /**
     * 쉬고 다시 출발: 지금 위치에서 계획을 다시 잡는다.
     * - 계획한 곳에서 쉬었으면 목적지로.
     * - 다른 곳에서 쉬었고 경유지가 아직 앞에 있으면, 롱웨이 모드는 그대로 남기고 온타임 모드는 아직 맞을 때만 남긴다
     *   (졸려서 쉬었으면 경치 길은 빼고, 쉬다 가는 곳은 남긴다).
     * - 쉬느라 늦어진 것은 쉬는 동안 알렸으므로 다시 말하지 않는다 (화면으로만).
     */
    private fun resumeAfterRest(now: Long, direct: Long?, jam: Double) {
        val option = state.chosen
        val dest = state.destination
        val hadVia = state.viaActive
        val course = if (hadVia && !restAtStop) courseFromHereMs(direct, jam) else null
        val keep = course != null && viaStillWorth(now, course)
        if (hadVia && !keep) state = state.copy(viaCleared = true)
        val planned = (if (keep) course else null) ?: direct
        planFrom = state.position
        planDirectMs = direct
        routePlanMs = planned
        routeChangedAt = now
        viaChangedAt = now
        val via = option?.via?.firstOrNull()?.name?.let { RestMateText.spokenName(it) }.orEmpty()
        val skipped = hadVia && !keep && !restAtStop
        val destName = dest?.name ?: "목적지"
        val route = when {
            keep && option?.kind == CourseKind.REST_STOP -> "${via}에서 ${restVerb(option)} 가요."
            keep -> "${DriveText.withObject(via)} 거쳐 가요."
            skipped -> "경유지는 빼고 ${DriveText.toward(destName)} 바로 가요."
            else -> "${DriveText.toward(destName)} 가요."
        }
        if (state.mode == DriveMode.LONGER) {
            // 롱웨이 모드는 마감이 없다: 새 경로의 도착 시각을 새 예정으로 삼는다.
            if (planned != null) state = state.copy(targetAt = now + planned)
            retargetOnSettle = true
            setMessage(now, if (keep) "다시 출발했어요. $route" else "다시 출발했어요.")
            // 롱웨이 모드는 경로가 바뀔 때만 말한다 (고른 경유지로 이어 가는 것은 바뀐 게 아니다).
            if (skipped) speak(Say.VIA, route)
            return
        }
        val target = state.targetAt
        val remain = planned?.let { withStay(now, it) }
        val lateBy = if (target != null && remain != null) now + remain - target else null
        val late = lateBy != null && lateBy > config.lateToleranceMs
        lateSpoken = late
        val lateText = if (late && lateBy != null) " 약 ${DriveText.duration(lateBy)} 늦게 도착해요. 서두르지 말고 안전하게 가세요." else ""
        setMessage(now, "다시 출발했어요. $route$lateText")
        if (keep || skipped) speak(Say.VIA, route)
    }

    /** 쉬고 다시 출발할 때 남은 경유지가 아직 맞는지 ([course]: 지금 위치에서 경유지를 거쳐 가는 운전 시간) */
    private fun viaStillWorth(now: Long, course: Long): Boolean {
        val option = state.chosen ?: return false
        if (state.mode == DriveMode.LONGER) return true
        // 졸려서 쉬었으면 경치 길로 더 돌지 않는다 (쉬다 가는 곳은 남긴다: 더 쉬어 가면 안전하다).
        if (restFatigue && option.kind != CourseKind.REST_STOP) return false
        val target = state.targetAt ?: return false
        return if (option.kind == CourseKind.REST_STOP) {
            target - marginFor() - now - course >= config.minWaitStayMs
        } else {
            now + course <= target + config.lateToleranceMs
        }
    }

    /** 내비가 알려 주는 우리 목적지까지 남은 시간 (내비 목적지가 같을 때만) */
    private fun naviRemainMs(): Long? {
        val dest = state.destination ?: return null
        return state.naviRemainingMs?.takeIf { dest.sameSpot(state.naviDestination) }
    }

    /** 내비 경로에 들어 있는 시간 모드의 경유지 (쉼터로 안내하는 동안은 빼고 본다) */
    private fun routeVia(): List<Place> = if (state.viaActive && !state.paused) state.chosen?.via.orEmpty() else emptyList()

    /**
     * 지금 내비 경로에 들어 있는 경유지: 쉼터로 안내하는 동안(과 쉬기 시작해 경로를 바꾼 직후)은 그 쉼터, 아니면 코스 경유지.
     * 이미 닿은(400m 안) 곳은 뺀다.
     */
    private fun naviRouteVia(): List<Place> {
        val position = state.position
        val rv = restVia?.takeIf { state.paused && state.now < restViaUntil }
        val via = if (rv != null) listOf(Place("쉼터", rv, PlaceKind.WAYPOINT)) else routeVia()
        return via.filter { position == null || Geo.distanceMeters(it.position, position) > REST_STOP_NEAR_METERS }
    }

    private fun planSpeed(position: LatLng): Double {
        val dest = state.destination?.position ?: return config.fallbackSpeedKmh / 3.6
        return TimeFitPlanner.effectiveSpeedMps(planFrom ?: position, dest, planDirectMs, config)
    }

    /** 내비 남은 시간 ÷ 모델 추정(내비 경로의 경유지 포함) = 정체 비율 (1~3배). 내비 값이 없으면 1 */
    private fun naviJam(position: LatLng, speed: Double): Double {
        val dest = state.destination ?: return 1.0
        val navi = naviRemainMs() ?: return 1.0
        val withVia = TimeFitPlanner.pathMs(position, naviRouteVia(), dest.position, speed, config).coerceAtLeast(1L)
        return (navi.toDouble() / withVia).coerceIn(1.0, 3.0)
    }

    /**
     * 지금 위치에서 고른 코스(남은 경유지)를 거쳐 목적지까지 운전하는 시간:
     * 바로 가는 시간 [direct] + 경유지로 돌아가는 시간 (정체 비율 [jam] 반영)
     */
    private fun courseFromHereMs(direct: Long?, jam: Double): Long? {
        if (direct == null) return null
        val dest = state.destination ?: return direct
        val position = state.position ?: return direct
        val via = (if (state.viaActive) state.chosen?.via.orEmpty() else emptyList())
            .filter { Geo.distanceMeters(it.position, position) > REST_STOP_NEAR_METERS }
        if (via.isEmpty()) return direct
        val speed = planSpeed(position)
        val withVia = TimeFitPlanner.pathMs(position, via, dest.position, speed, config)
        val plain = TimeFitPlanner.pathMs(position, emptyList(), dest.position, speed, config)
        return direct + ((withVia - plain).coerceAtLeast(0L) * jam).toLong()
    }

    /**
     * 목적지까지 남은 운전 시간. 내비 값(경유지 포함)이 있으면 그것을, 없으면 남은 경로를 직선거리로 추정한다.
     * 쉼터로 안내받는 중이면 내비 경로에 쉼터가 들어 있으므로 원래 코스로 고쳐 본다.
     */
    private fun drivingRemainingMs(): Long? {
        val navi = naviRemainMs()
        val position = state.position
        if (navi != null) {
            val viaRest = restVia != null && state.paused && state.now < restViaUntil
            if (!viaRest || position == null) return navi
            return courseFromHereMs(directFromHereMs(), naviJam(position, planSpeed(position)))
        }
        val dest = state.destination ?: return null
        if (position == null) return null
        return TimeFitPlanner.pathMs(position, routeVia(), dest.position, planSpeed(position), config)
    }

    /** 쉬다 가는 곳에 들르는 코스는 그곳에서 시간을 맞춰 출발하므로 '목표 − 여유'보다 일찍 닿지 않는다. */
    private fun withStay(now: Long, driving: Long): Long {
        val option = state.chosen
        if (state.viaActive && !state.paused && option?.kind == CourseKind.REST_STOP) {
            val target = state.targetAt ?: return driving + option.stayMs
            return max(driving, target - marginFor() - now)
        }
        return driving
    }

    /**
     * 지금 위치에서 경유지 없이 목적지까지 가는 시간.
     * 내비 경로에 경유지(코스 경유지나 쉼터)가 있으면 내비 남은 시간 ÷ 모델 추정으로 정체 비율(1~3배)을 구해 반영한다.
     */
    private fun directFromHereMs(): Long? {
        val dest = state.destination ?: return null
        val navi = naviRemainMs()
        val position = state.position
        if (navi != null && naviRouteVia().isEmpty()) return navi
        if (position == null) return navi ?: planDirectMs
        val speed = planSpeed(position)
        val model = TimeFitPlanner.pathMs(position, emptyList(), dest.position, speed, config)
        if (navi == null) return model
        return (model * naviJam(position, speed)).toLong()
    }

    /** 경로를 막 바꿨을 때 계획한 남은 운전 시간 (내비 값이 아직 옛 경로일 때) */
    private fun plannedRemainingMs(now: Long): Long? {
        val plan = routePlanMs ?: return null
        return (plan - (now - routeChangedAt)).coerceAtLeast(0L)
    }

    private fun arrive(now: Long) {
        val dest = state.destination
        // 픽업 알림: 마지막 구간이 짧아(기다리던 곳이 학원 바로 옆 등) '곧 도착' 문자를 아직 못 띄웠으면 도착하면서 띄운다.
        if (state.pickupTo != null && !approachSent && !state.circling && dest != null) {
            approachSent = true
            send(now, PickupNoticeKind.APPROACH, PickupText.approach(dest.name, DriveText.clock(now), state.position), state.position)
        }
        // 경유지가 남은 채 도착했으면 (지나가다 1분 머묾, '자리 없음'으로 돌다 빈자리에 세움) 내비 경로에서 경유지를 뺀다.
        if (state.viaActive && !state.paused && dest != null) {
            effects += DriveEffect.Route(dest, emptyList())
            state = state.copy(viaCleared = true)
        }
        val target = state.targetAt
        val diff = target?.let { now - it }
        // 말하는 분은 화면의 '도착 ○ · 목표 ○' 두 시각의 차이로 (1분 어긋나지 않게)
        val shown = target?.let { DriveText.gapMs(now, it) } ?: 0L
        // 여유(3분)를 두고 맞추므로 목표보다 조금(여유 + 2분 안) 일찍 닿는 것까지 '딱 맞춰'로 본다.
        val message = when {
            state.circling -> "다시 도착했어요."
            state.mode == DriveMode.LONGER -> "도착했어요."
            diff == null -> "도착했어요."
            diff > config.lateToleranceMs -> "약 ${DriveText.duration(shown)} 늦게 도착했어요."
            shown == 0L || diff >= -30 * SECOND_MS -> "딱 맞춰 도착했어요."
            -shown <= config.marginMs + 2 * MINUTE_MS -> "딱 맞춰 도착했어요. ${DriveText.duration(shown)} 여유가 있어요."
            else -> "${DriveText.duration(shown)} 일찍 도착했어요."
        }
        state = state.copy(
            now = now,
            step = DriveStep.ARRIVED,
            arrivedAt = now,
            diffMs = diff,
            status = if (diff == null) FitStatus.UNKNOWN else statusFor(diff),
            extendOffered = false,
            extendOption = null,
            stopArriveAt = null,
            stopDepartAt = null,
            message = message,
            messageAt = now,
            circling = false,
        )
        offerMessage = null
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
        refreshAsked = false
        lateSince = null
        lateSteps = 0
        lateSpoken = false
        restedThisDrive = false
        restFatigue = false
        restAtStop = false
        restStopReached = false
        routePlanMs = null
        planFrom = null
        planDirectMs = null
        planMadeAt = Long.MIN_VALUE / 2
        extendCheckedAt = Long.MIN_VALUE / 2
        extendQuietUntil = Long.MIN_VALUE / 2
        viaChangedAt = Long.MIN_VALUE / 2
        offerMessage = null
        retargetOnSettle = false
        waitShortSince = null
        nearDestinationSince = null
        approachSent = false
        parkedNoticeAt = null
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

    /** 출발할 때 하는 말. 실제 도착 예상과 맞게: 일찍 닿는 코스면 몇 분 일찍인지, 늦는 코스면 몇 분 늦는지 */
    private fun startSpeech(option: CourseOption, now: Long, direct: Long): String {
        val destName = state.destination?.name ?: "목적지"
        val via = option.via.firstOrNull()?.name?.let { RestMateText.spokenName(it) }.orEmpty()
        if (state.mode == DriveMode.LONGER) {
            // 롱웨이 모드는 시각 없이 짧게 (혼자일 때 경로 변경만 말한다)
            return if (option.kind == CourseKind.DIRECT) "${DriveText.toward(destName)} 바로 갈게요." else "${routePhrase(option)}로 갈게요."
        }
        val target = state.targetAt ?: return "안내를 시작할게요."
        val arrival = now + direct + option.addedMs
        // 말하는 시각(분 반올림)과 분이 맞게
        val early = -DriveText.gapMs(arrival, target)
        val lead = when (option.kind) {
            CourseKind.DIRECT -> null
            CourseKind.SCENIC -> "${DriveText.withObject(via)} 거쳐 가요."
            CourseKind.DETOUR -> "주변으로 돌아서 가요."
            CourseKind.REST_STOP -> if (option.restKind == RestKind.NAP) "${via}에 들러 쉬다 가요." else "${via}에서 ${restVerb(option)} 가요."
        }
        val tail = when {
            early < -config.lateToleranceMs ->
                if (option.kind == CourseKind.DIRECT) {
                    "바로 가도 약 ${DriveText.duration(early)} 늦어요. 가장 빠른 길로 안내할게요."
                } else {
                    "이 길로 가면 약 ${DriveText.duration(early)} 늦어요."
                }
            option.kind == CourseKind.REST_STOP -> "${DriveText.spokenClock(target)}에 맞춰 출발 시각을 알려 드릴게요."
            early > config.marginMs + 2 * MINUTE_MS ->
                if (option.kind == CourseKind.DIRECT) {
                    "바로 가면 ${DriveText.spokenClock(arrival)}, ${DriveText.duration(early)} 일찍 도착해요."
                } else {
                    "${DriveText.spokenClock(arrival)}쯤, ${DriveText.duration(early)} 일찍 도착해요."
                }
            option.kind == CourseKind.DIRECT -> "바로 가도 ${DriveText.spokenClock(target)}에 맞아요."
            else -> "${DriveText.spokenClock(target)}에 맞춰 도착하도록 안내할게요."
        }
        return listOfNotNull(lead, tail).joinToString(" ")
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
        /** '조금 더 돌기'는 지금보다 이만큼은 늦게 끝나야 권한다 */
        private const val EXTEND_MIN_GAIN_MS = 3 * MINUTE_MS
        /** 권한 코스가 맞지 않게 되어 거둔 뒤 이만큼은 새로 권하지 않는다 (버튼이 자꾸 바뀌지 않게) */
        private const val EXTEND_SWITCH_QUIET_MS = 3 * MINUTE_MS
        /** 이미 권한 코스는 목적지까지 이만큼 남을 때까지 둔다 (새로 권하는 기준보다 짧게, 버튼이 금방 사라지지 않게) */
        private const val EXTEND_KEEP_MIN_REMAIN_MS = 3 * MINUTE_MS
        /** 늦을 것 같다는 음성은 이만큼 넘게 늦을 때만 (내비 예상이 1분쯤은 흔들린다). 화면 칩은 30초부터 */
        private const val LATE_SPEECH_MS = 2 * MINUTE_MS
        /** 저장해 둔 시각이 이만큼 안에 막 지났으면 그 시각을 그대로 목표로 (늦었어도 바로 가게) */
        private const val SAVED_JUST_PASSED_MS = HOUR_MS
        /** 기다릴 시간이 모자란 상태가 이만큼 이어지면 기다리는 곳을 뺀다 */
        private const val WAIT_SHORT_OBSERVE_MS = MINUTE_MS
        /** 경유지가 남아 있을 때 목적지 가까이에 이만큼 머물러야 도착으로 본다 (지나가는 것과 구분) */
        private const val PASSING_BY_MS = MINUTE_MS
        /** 코스 후보 화면에 머무는 동안 후보를 다시 내는 간격 */
        private const val CHOOSE_REFRESH_MS = MINUTE_MS
        /** 코스 후보를 낸 뒤 이만큼 움직였으면 바로 가는 시간을 다시 묻는다 */
        private const val CHOOSE_MOVED_METERS = 300.0
        private const val DAY_MS = 24 * HOUR_MS
        private const val HALF_DAY_MS = 12 * HOUR_MS
        /** '자리 없음'으로 한 바퀴 돌 때 거쳐 가는 지점 이름 (내비·미니맵에 보인다) */
        const val CIRCLE_VIA_NAME = "한 바퀴 도는 길"
        /** 픽업 알림: 같은 자리에서 다시 P단으로 세우면 '세운 곳'을 또 보내지 않는다 */
        private const val PARKED_RESEND_METERS = 30.0
        /** 차가 이만큼 움직일 때마다 달리는 방향을 다시 잡는다 */
        private const val BEARING_STEP_METERS = 15.0

        fun roundUp(ms: Long, step: Long): Long = ((ms + step - 1) / step) * step

        /**
         * 시각 칩 4개: (지금 + 바로 가는 시간 + 10분)을 올림한 시각부터.
         * 30분 안 길이면 10분, 1시간 30분 안이면 30분, 그보다 멀면 1시간 간격 (먼 길도 3시 체크인 같은 시각을 칩으로 고르게)
         */
        fun targetChoices(now: Long, directMs: Long): List<Long> {
            val step = when {
                directMs < 30 * MINUTE_MS -> 10 * MINUTE_MS
                directMs < 90 * MINUTE_MS -> 30 * MINUTE_MS
                else -> HOUR_MS
            }
            val base = roundUp(now + directMs + 10 * MINUTE_MS, step)
            return List(4) { base + it * step }
        }
    }
}
