package kr.contest.pleosapp.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kr.contest.pleosapp.audio.AlarmPlayer
import kr.contest.pleosapp.audio.Speaker
import kr.contest.pleosapp.core.CabinMode
import kr.contest.pleosapp.core.CheckInAnswer
import kr.contest.pleosapp.core.CourseFlavor
import kr.contest.pleosapp.core.CourseOption
import kr.contest.pleosapp.core.DemoNavi
import kr.contest.pleosapp.core.DemoNaviEvent
import kr.contest.pleosapp.core.DemoTrip
import kr.contest.pleosapp.core.DemoTrips
import kr.contest.pleosapp.core.DriveEffect
import kr.contest.pleosapp.core.DriveEngine
import kr.contest.pleosapp.core.DriveMode
import kr.contest.pleosapp.core.DriveState
import kr.contest.pleosapp.core.DriveStep
import kr.contest.pleosapp.core.DriveText
import kr.contest.pleosapp.core.Effect
import kr.contest.pleosapp.core.Gear
import kr.contest.pleosapp.core.HOUR_MS
import kr.contest.pleosapp.core.LatLng
import kr.contest.pleosapp.core.MINUTE_MS
import kr.contest.pleosapp.core.Mood
import kr.contest.pleosapp.core.OnTimeStyle
import kr.contest.pleosapp.core.Phase
import kr.contest.pleosapp.core.Place
import kr.contest.pleosapp.core.PlaceKind
import kr.contest.pleosapp.core.RestArea
import kr.contest.pleosapp.core.RestAreaCandidate
import kr.contest.pleosapp.core.RestAreaFinder
import kr.contest.pleosapp.core.RestEnvironment
import kr.contest.pleosapp.core.RestKind
import kr.contest.pleosapp.core.RestMateConfig
import kr.contest.pleosapp.core.RestMateEngine
import kr.contest.pleosapp.core.RestMateState
import kr.contest.pleosapp.core.SECOND_MS
import kr.contest.pleosapp.core.SavedTrip
import kr.contest.pleosapp.core.VehicleEvent
import kr.contest.pleosapp.data.CityRestPlaces
import kr.contest.pleosapp.data.CourseCatalog
import kr.contest.pleosapp.data.RestAreaRepository
import kr.contest.pleosapp.sdk.NaviSignals
import kr.contest.pleosapp.sdk.VehicleExtras
import kr.contest.pleosapp.sdk.VehicleSignals
import java.util.Calendar

data class ConnectionStatus(
    val vehicle: String = "연결 중…",
    val navi: String = "연결 중…",
)

data class UiState(
    /** 지금 시각 (시연 모드에서는 가상 시계) */
    val now: Long = 0L,
    /** 레스트 모드 */
    val core: RestMateState = RestMateState(),
    /** 온타임 모드·롱웨이 모드 */
    val drive: DriveState = DriveState(),
    /** 시연 모드: 실제 차량·내비 신호 대신 모의 내비와 가상 시계를 쓴다. 화면에 늘 표시한다. */
    val demoMode: Boolean = false,
    val demoTrip: DemoTrip? = null,
    val demoDriving: Boolean = false,
    val demoJammed: Boolean = false,
    val extras: VehicleExtras = VehicleExtras(),
    val connection: ConnectionStatus = ConnectionStatus(),
    val restAreaCount: Int = 0,
    val speechAvailable: Boolean = false,
    /** 마지막으로 말한 안내 문구. 음성이 안 나와도 화면으로 볼 수 있게 한다. */
    val lastMessage: String? = null,
    val naviNote: String? = null,
    /** 실내 환경 제어 결과 */
    val cabinNote: String? = null,
    val env: RestEnvironment = RestEnvironment(),
    val config: RestMateConfig = RestMateConfig(),
    /** 자주 쓰는 설정 (처음 화면 칩, 최대 4개) */
    val saved: List<SavedTrip> = emptyList(),
)

/** 화면에서 부르는 동작. */
interface RestMateActions {
    // ---- 레스트 모드 ----
    fun pressDrowsy()
    fun selectRestArea(candidate: RestAreaCandidate)
    fun cancelGuidance()
    fun answerCheckIn(answer: CheckInAnswer)
    fun acceptPrompt()
    fun dismissPrompt()
    fun startRest()
    fun setRestMinutes(minutes: Int)
    fun extendRest(minutes: Int)
    fun stopAlarm()
    fun wakeUp()
    fun departNow()
    fun setRestEnvironment(env: RestEnvironment)
    /** '주차하셨네요' 물음에 답: 그냥 기다리기·잠깐 머물기·눈 붙이기 */
    fun restHere(kind: RestKind)
    /** 휴식 중에 어떻게 쉴지 바꾸기 */
    fun setRestKind(kind: RestKind)

    // ---- 온타임 모드·롱웨이 모드 ----
    fun openMode(mode: DriveMode)
    fun driveBack()
    fun setDriveDestination(place: Place)
    fun shiftTarget(minutes: Int)
    fun setTarget(at: Long)
    fun setExtraMinutes(minutes: Int)
    fun setMood(mood: Mood)
    fun setOnTimeStyle(style: OnTimeStyle)
    fun setFlavor(flavor: CourseFlavor)
    fun planCourses()
    fun chooseCourse(option: CourseOption)
    fun goDirect()
    fun extendCourse()
    /** 달리는 중 시간 더하기 (온타임 +5·+10분, 롱웨이 +15분) */
    fun addDriveTime(minutes: Int)
    fun finishDrive()
    fun saveTrip()
    fun applySavedTrip(trip: SavedTrip)
    fun removeSavedTrip(trip: SavedTrip)

    // ---- 시연 ----
    fun setDemoMode(on: Boolean)
    fun selectDemoTrip(id: String)
    fun demoDrive()
    fun demoPark()
    fun demoTraffic()
    fun demoSkip(minutes: Int)
    fun demoAddDrivingTime()
    fun demoAttentionWarning()
    fun demoSkipRest()
    fun demoRestart()
}

/**
 * 두 엔진([RestMateEngine]: 레스트 모드, [DriveEngine]: 시간 모드)과 SDK·음성·알람을 잇는다. 모든 호출은 메인 스레드에서 한다.
 *
 * 내비 경로는 여기서만 요청한다.
 * - 시간 모드가 고른 코스: 목적지 + 경유지
 * - 레스트 모드: 가던 목적지가 있으면 쉼터를 경유지로 넣고, 없으면 쉼터를 목적지로
 * - 쉼터 안내를 취소하면: 가던 목적지 + 원래 경유지로 되돌림
 * - 쉬고 다시 출발하면: 가던 목적지로 바로
 *
 * 시간 모드로 달리는 중이면 도착 시각을 레스트 모드에 넘겨, 쉬는 동안 알람·출발 시각을 거기에 맞춘다.
 */
class RestMateController(context: Context) : RestMateActions, VehicleSignals.Sink, NaviSignals.Sink {
    private val appContext = context.applicationContext
    private val highwayAreas: List<RestArea> = runCatching { RestAreaRepository.load(appContext) }
        .onFailure { Log.e(TAG, "rest area data", it) }
        .getOrDefault(emptyList())
    private val areas: List<RestArea> = highwayAreas + CityRestPlaces.SAMPLES
    private val finder = RestAreaFinder(areas)
    private var engine = RestMateEngine(finder)
    private var drive = newDriveEngine()
    private val vehicle = VehicleSignals(appContext, this)
    private val navi = NaviSignals(appContext, this)
    private val speaker = Speaker(appContext)
    private val alarm = AlarmPlayer(appContext)
    private val main = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var env = loadEnvironment()

    private val _ui = MutableStateFlow(UiState(restAreaCount = areas.size, env = env, saved = loadSaved()))
    val ui: StateFlow<UiState> = _ui

    private var nextManeuverMeters: Double? = null

    // 실제 차량·내비에서 마지막으로 받은 값. 시연 모드를 끌 때 새 엔진에 다시 넣는다.
    private var realGear = Gear.UNKNOWN
    private var realSpeedLimit: Int? = null
    private var realDestination: Place? = null
    private var realRemainingMs: Long? = null
    private var realPlaces: List<Place> = emptyList()

    /** 레스트 모드 전에 가던 목적지 (쉼터를 경유지로 넣고, 쉰 뒤 다시 안내할 때 쓴다) */
    private var tripDestination: Place? = null
    /** 가던 목적지가 없어 쉼터를 목적지로 안내했는지 */
    private var restAsDestination = false
    private var lastPhase = Phase.DRIVING

    private var demo: DemoSession? = null

    /** 시연: 모의 내비와 가상 시계 (실제 1초에 trip.timeScale초가 흐른다) */
    private class DemoSession(val trip: DemoTrip) {
        val navi = DemoNavi(trip)
        private val realBase = SystemClock.elapsedRealtime()
        private var virtualBase = trip.startTimeOfDayMs?.let { todayAt(it) } ?: System.currentTimeMillis()
        var lastStepAt = now()

        fun now(): Long = virtualBase + ((SystemClock.elapsedRealtime() - realBase) * trip.timeScale).toLong()

        fun shift(ms: Long) {
            virtualBase += ms
        }

        private fun todayAt(timeOfDayMs: Long): Long {
            val c = Calendar.getInstance()
            c.set(Calendar.HOUR_OF_DAY, 0)
            c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis + timeOfDayMs
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val t = now()
            shareDeadline()
            engine.onTick(t)
            drive.onTick(t)
            checkRestStopParking(t)
            speaker.flush()
            publish()
            main.postDelayed(this, TICK_MS)
        }
    }

    private val demoLoop = object : Runnable {
        override fun run() {
            stepDemo()
            publish()
            main.postDelayed(this, DEMO_STEP_MS)
        }
    }

    fun start() {
        // 회전·진입 안내 직전에는 말을 미룬다 (실제 내비 안내를 따를 때만).
        speaker.shouldHold = { demo == null && (nextManeuverMeters ?: Double.MAX_VALUE) < HOLD_SPEECH_METERS }
        vehicle.start()
        navi.start()
        main.post(ticker)
        publish()
    }

    fun stop() {
        main.removeCallbacks(ticker)
        main.removeCallbacks(demoLoop)
        // 쉬는 중이거나 분위기를 바꿔 둔 채 앱이 꺼지면 실내 환경을 원래대로 돌려 둔다.
        if (engine.state.phase == Phase.RESTING) vehicle.cabin.apply(CabinMode.RESTORE, env)
        vehicle.cabin.applyMood(null)
        vehicle.stop()
        navi.stop()
        speaker.release()
        alarm.release()
    }

    private fun now(): Long = demo?.now() ?: System.currentTimeMillis()

    private fun newDriveEngine() = DriveEngine(CourseCatalog.POINTS, areas)

    /** 시간 모드의 도착 시각을 레스트 모드에 넘긴다 (쉬는 동안 알람을 맞추는 데 씀). */
    private fun shareDeadline() {
        engine.setDeadline(drive.deadline())
    }

    private fun publish() {
        val t = now()
        shareDeadline()
        for (effect in engine.drainEffects()) handle(effect)
        syncRest(t)
        for (effect in drive.drainEffects()) handleDrive(t, effect)
        val d = demo
        _ui.update {
            it.copy(
                now = t,
                core = engine.state,
                drive = drive.state,
                speechAvailable = speaker.available,
                config = engine.config,
                demoDriving = d?.navi?.moving == true,
                demoJammed = d?.navi?.jammed == true,
            )
        }
    }

    /** 레스트 모드가 진행 중이면 시간 맞추기를 멈추고, 쉬고 출발하면 가던 목적지로 다시 안내한다. */
    private fun syncRest(t: Long) {
        val phase = engine.state.phase
        drive.onRestPhase(t, phase)
        if (lastPhase != Phase.RESTING && phase == Phase.RESTING) {
            // 쉬기 시작하면 쉰 곳이 경유지를 대신한다: 남은 시간이 바로 가는 길 기준이 되도록 경유지를 뺀다.
            val dest = currentTrip()
            if (dest != null && !restAsDestination) routeNavi(dest, emptyList())
        }
        if (lastPhase == Phase.RESTING && phase == Phase.DRIVING) {
            val dest = currentTrip()
            if (dest != null && !restAsDestination) {
                routeNavi(dest, emptyList())
                note("내비에 ${dest.name}까지 다시 안내를 요청했어요.")
            }
            shareDeadline()
            restAsDestination = false
        }
        lastPhase = phase
    }

    private fun handle(effect: Effect) {
        when (effect) {
            is Effect.StartGuidance -> routeToRest(effect.area)
            Effect.CancelGuidance -> cancelRestRoute()
            is Effect.Speak -> say(effect.text)
            is Effect.Alarm -> if (effect.on) alarm.start() else alarm.stop()
            is Effect.Cabin -> {
                vehicle.cabin.apply(effect.mode, env)
                // 휴식 환경을 되돌리면 롱웨이 모드 분위기도 같이 꺼지므로 다시 켠다.
                if (effect.mode == CabinMode.RESTORE) drive.moodInEffect?.let { vehicle.cabin.applyMood(it) }
            }
        }
    }

    private fun handleDrive(t: Long, effect: DriveEffect) {
        when (effect) {
            is DriveEffect.AskDirectTime -> askDirectTime(t, effect.destination)
            is DriveEffect.Route -> {
                val sent = routeNavi(effect.destination, effect.via)
                note(
                    when {
                        !sent -> "내비가 연결되지 않아 화면으로만 안내해요."
                        effect.via.isEmpty() -> "내비에 ${effect.destination.name}까지 바로 가는 길을 요청했어요."
                        else -> "내비에 ${effect.via.joinToString(", ") { it.name }} 경유 경로를 요청했어요."
                    },
                )
            }
            is DriveEffect.Speak -> say(effect.text)
            is DriveEffect.CabinMood -> vehicle.cabin.applyMood(effect.mood)
        }
    }

    private fun say(text: String) {
        speaker.say(text)
        _ui.update { it.copy(lastMessage = text) }
    }

    private fun note(text: String) {
        _ui.update { it.copy(naviNote = text) }
    }

    // ---- 내비 경로 (실제 내비 또는 시연용 모의 내비) ----

    private fun routeNavi(destination: Place, via: List<Place>): Boolean {
        val d = demo
        if (d != null) {
            d.navi.routeTo(destination, via)
            return true
        }
        return navi.routeTo(destination, via)
    }

    private fun cancelNavi() {
        val d = demo
        if (d != null) d.navi.cancel() else navi.cancelRoute()
    }

    private fun askDirectTime(t: Long, place: Place) {
        val d = demo
        if (d != null) {
            drive.onDirectTime(t, place, d.navi.tripTimeMs(place))
        } else {
            navi.requestTripTime(place)
        }
    }

    /** 지금 가는 목적지: 시간 모드로 달리는 중이면 그 목적지, 아니면 쉼터 안내 전에 내비에 걸려 있던 목적지 */
    private fun currentTrip(): Place? {
        val d = drive.state
        if (d.step == DriveStep.ACTIVE) d.destination?.let { return it }
        return tripDestination
    }

    private fun routeToRest(area: RestArea) {
        val stop = Place(area.name, area.position, PlaceKind.WAYPOINT)
        val dest = currentTrip()
        val sent = if (dest != null && !dest.sameSpot(stop)) {
            restAsDestination = false
            routeNavi(dest, listOf(stop))
        } else {
            restAsDestination = true
            routeNavi(stop, emptyList())
        }
        note(
            when {
                !sent -> "내비가 연결되지 않아 화면으로만 안내해요."
                restAsDestination -> "내비에 ${area.name} 안내를 요청했어요."
                else -> "내비에 ${DriveText.withObject(area.name)} 경유지로 넣었어요. " +
                    "쉬고 나면 ${DriveText.toward(dest?.name ?: "목적지")} 이어서 가요."
            },
        )
    }

    /** 쉼터 안내를 취소하면 가던 목적지와 원래 경유지(시간 모드 코스)로 되돌린다. */
    private fun cancelRestRoute() {
        val dest = currentTrip()
        if (dest != null && !restAsDestination) {
            val via = drive.activeVia()
            routeNavi(dest, via)
            note(
                if (via.isEmpty()) "내비를 ${DriveText.toward(dest.name)} 가는 길로 되돌렸어요."
                else "내비를 ${via.joinToString(", ") { it.name }} 거쳐 가는 원래 길로 되돌렸어요.",
            )
        } else {
            cancelNavi()
        }
        restAsDestination = false
    }

    /** 내비 목적지가 바뀌면 기억해 둔다. 쉼터로 안내하는 동안에는 바꾸지 않는다. */
    private fun noteNaviDestination(place: Place?) {
        if (engine.state.phase != Phase.DRIVING || restAsDestination) return
        tripDestination = place
    }

    /** 쉬다 가는(기다리는) 코스에서 그곳에 P단으로 세우면 도착 시각에 맞춰 출발을 알려 주도록 휴식을 시작한다. */
    private fun checkRestStopParking(t: Long) {
        if (engine.state.gear != Gear.PARK || engine.state.phase != Phase.DRIVING) return
        val area = drive.restStopHere(engine.state.position) ?: return
        shareDeadline()
        engine.startRest(t, drive.plannedRestKind, area)
    }

    private inline fun act(block: (Long) -> Unit) {
        block(now())
        publish()
    }

    // ---- 화면 조작: 레스트 모드 ----

    override fun pressDrowsy() = act { engine.pressDrowsy(it) }
    override fun selectRestArea(candidate: RestAreaCandidate) = act { engine.selectRestArea(it, candidate) }
    override fun cancelGuidance() = act { engine.cancelGuidance(it) }
    override fun answerCheckIn(answer: CheckInAnswer) = act { engine.answerCheckIn(it, answer) }
    override fun acceptPrompt() = act { engine.acceptPrompt(it) }
    override fun dismissPrompt() = act { engine.dismissPrompt(it) }
    override fun setRestMinutes(minutes: Int) = act { engine.setRestMinutes(it, minutes) }
    override fun extendRest(minutes: Int) = act { engine.extendRest(it, minutes) }
    override fun stopAlarm() = act { engine.stopAlarm(it) }
    override fun wakeUp() = act { engine.wakeUp(it) }
    override fun restHere(kind: RestKind) = act { engine.restHere(it, kind) }
    override fun setRestKind(kind: RestKind) = act { engine.setRestKind(it, kind) }

    override fun departNow() {
        val d = demo
        if (d != null && engine.state.phase == Phase.RESTING) {
            // 시연에서는 '출발할게요'를 누르면 모의 차량도 출발한다.
            d.navi.moving = true
            engine.onGear(now(), Gear.DRIVE)
        }
        act { engine.departNow(it) }
    }

    override fun startRest() {
        // 시연 중 '주차했어요'를 누르면 모의 차량도 세운다.
        val d = demo
        if (d != null && d.navi.moving) {
            d.navi.moving = false
            engine.onSpeed(now(), 0.0)
        }
        act { engine.startRest(it) }
    }

    override fun setRestEnvironment(env: RestEnvironment) {
        val previous = this.env
        this.env = env
        saveEnvironment(env)
        _ui.update { it.copy(env = env) }
        val rest = engine.state.rest ?: return
        // 쉬는 중에 바꾸면 바로 반영한다. 끄면 원래 설정으로 돌린다.
        when {
            !env.enabled && previous.enabled -> vehicle.cabin.apply(CabinMode.RESTORE, env)
            env.enabled && rest.wakeStartedAt == null -> vehicle.cabin.apply(CabinMode.REST, env)
        }
    }

    // ---- 화면 조작: 온타임 모드·롱웨이 모드 ----

    override fun openMode(mode: DriveMode) {
        if (demo == null) navi.requestPlaces()
        act { drive.openMode(it, mode) }
    }

    override fun driveBack() = act { drive.back(it) }
    override fun setDriveDestination(place: Place) = act { drive.setDestination(it, place) }
    override fun shiftTarget(minutes: Int) = act { drive.shiftTarget(it, minutes) }
    override fun setTarget(at: Long) = act { drive.setTarget(it, at) }
    override fun setExtraMinutes(minutes: Int) = act { drive.setExtraMinutes(minutes) }
    override fun setMood(mood: Mood) = act { drive.setMood(mood) }
    override fun setOnTimeStyle(style: OnTimeStyle) = act { drive.setOnTimeStyle(style) }
    override fun setFlavor(flavor: CourseFlavor) = act { drive.setFlavor(it, flavor) }
    override fun planCourses() = act { drive.plan(it) }
    override fun chooseCourse(option: CourseOption) = act { drive.choose(it, option) }
    override fun goDirect() = act { drive.goDirect(it) }
    override fun extendCourse() = act { drive.extend(it) }
    override fun addDriveTime(minutes: Int) = act { drive.addTime(it, minutes) }
    override fun finishDrive() = act { drive.finish(it) }

    // ---- 자주 쓰는 설정 ----

    override fun saveTrip() {
        val trip = drive.saveable() ?: return
        val list = SavedTrip.added(_ui.value.saved, trip)
        saveSaved(list)
        _ui.update { it.copy(saved = list) }
        note("자주 쓰는 설정에 저장했어요: ${trip.place.name} · ${trip.detail}")
    }

    override fun applySavedTrip(trip: SavedTrip) {
        if (demo == null) navi.requestPlaces()
        act { drive.applySaved(it, trip) }
    }

    override fun removeSavedTrip(trip: SavedTrip) {
        val list = _ui.value.saved.filterNot { it == trip }
        saveSaved(list)
        _ui.update { it.copy(saved = list) }
    }

    // ---- 시연 모드 ----

    override fun setDemoMode(on: Boolean) {
        if (on) startDemo(demo?.trip ?: DemoTrips.CITY) else stopDemo()
    }

    override fun selectDemoTrip(id: String) {
        val trip = DemoTrips.ALL.firstOrNull { it.id == id } ?: return
        startDemo(trip)
    }

    override fun demoRestart() {
        demo?.let { startDemo(it.trip) }
    }

    override fun demoDrive() {
        val d = demo ?: return
        d.navi.moving = true
        engine.onGear(now(), Gear.DRIVE)
        stepDemo()
        publish()
    }

    override fun demoPark() {
        if (demo == null) return
        parkDemoCar(now())
        publish()
    }

    override fun demoTraffic() {
        val d = demo ?: return
        d.navi.jam(DEMO_JAM_MS)
        note("모의 정체: 앞길이 막혀 도착이 약 ${DEMO_JAM_MS / MINUTE_MS}분 늦어져요.")
        stepDemo()
        publish()
    }

    /** 가상 시계를 minutes분 앞으로 돌린다. 차와 엔진은 30초씩 나눠서 따라간다. */
    override fun demoSkip(minutes: Int) {
        val d = demo ?: return
        repeat(minutes * 2) {
            d.shift(30 * SECOND_MS)
            stepDemo()
            val t = now()
            shareDeadline()
            engine.onTick(t)
            drive.onTick(t)
            checkRestStopParking(t)
            publish()
        }
    }

    override fun demoAddDrivingTime() {
        if (demo != null) act { engine.addDrivingTime(it, 2 * HOUR_MS) }
    }

    override fun demoAttentionWarning() {
        if (demo != null) act { engine.onAttentionWarning(it) }
    }

    override fun demoSkipRest() {
        if (demo != null) act { engine.skipToAlarm(it) }
    }

    private fun startDemo(trip: DemoTrip) {
        main.removeCallbacks(demoLoop)
        resetEngines()
        val session = DemoSession(trip)
        demo = session
        val t = now()
        engine = RestMateEngine(finder, RestMateConfig.forDemo(trip.timeScale))
        engine.setOnExpressway(trip.expressway)
        // 출발 직전: D단으로 서 있는 상태 ('주행'을 누르면 움직인다)
        engine.onGear(t, Gear.DRIVE)
        engine.onSpeed(t, 0.0)
        drive.onPlaces(trip.places)
        feedDemo(t)
        _ui.update { it.copy(demoMode = true, demoTrip = trip, lastMessage = null, naviNote = null) }
        main.post(demoLoop)
        publish()
    }

    private fun stopDemo() {
        main.removeCallbacks(demoLoop)
        resetEngines()
        demo = null
        engine = RestMateEngine(finder)
        replayRealSignals()
        _ui.update { it.copy(demoMode = false, demoTrip = null, lastMessage = null, naviNote = null) }
        publish()
    }

    /** 진행 중이던 안내·휴식·분위기를 정리하고 엔진을 새로 만든다. */
    private fun resetEngines() {
        when (engine.state.phase) {
            Phase.TO_REST_AREA -> cancelRestRoute()
            Phase.RESTING -> vehicle.cabin.apply(CabinMode.RESTORE, env)
            Phase.DRIVING -> Unit
        }
        if (drive.state.step != DriveStep.HOME) {
            drive.finish(now())
            for (effect in drive.drainEffects()) {
                if (effect is DriveEffect.CabinMood) vehicle.cabin.applyMood(effect.mood)
            }
        }
        alarm.stop()
        engine.drainEffects()
        drive = newDriveEngine()
        tripDestination = null
        restAsDestination = false
        lastPhase = Phase.DRIVING
    }

    private fun replayRealSignals() {
        val t = now()
        engine.onGear(t, realGear)
        engine.onSpeedLimit(realSpeedLimit)
        engine.onDestination(realDestination?.position)
        engine.onRemainingRoute(realRemainingMs)
        drive.onPlaces(realPlaces)
        drive.onNaviDestination(t, realDestination, realRemainingMs)
        tripDestination = realDestination
    }

    /** 모의 차를 가상 시계에 맞춰 옮기고, 경유지·목적지에 닿으면 실제 내비 사건처럼 처리한다. */
    private fun stepDemo() {
        val d = demo ?: return
        val t = now()
        val dt = t - d.lastStepAt
        d.lastStepAt = t
        val events = d.navi.advance(dt)
        feedDemo(t)
        for (event in events) {
            when (event) {
                is DemoNaviEvent.WaypointArrived -> {
                    drive.onWaypointArrived(t)
                    note("모의: 경유지 ${event.place.name}에 도착했어요.")
                    // 쉬러 들른 곳이면 진입해서 P단으로 주차하는 것까지 흉내 낸다.
                    if (isRestTarget(event.place)) parkDemoCar(t)
                }
                is DemoNaviEvent.DestinationArrived -> {
                    note("모의: ${event.place.name}에 도착해 P단으로 주차했어요.")
                    engine.onTripArrived(t)
                    parkDemoCar(t)
                    drive.onDestinationArrived(t)
                }
            }
        }
    }

    private fun isRestTarget(place: Place): Boolean {
        val target = engine.state.target?.area
        if (target != null && Place(target.name, target.position).sameSpot(place)) return true
        return drive.restStopHere(place.position) != null
    }

    private fun parkDemoCar(t: Long) {
        demo?.navi?.moving = false
        engine.onSpeed(t, 0.0)
        engine.onGear(t, Gear.PARK)
        checkRestStopParking(t)
    }

    private fun feedDemo(t: Long) {
        val n = demo?.navi ?: return
        engine.onSpeed(t, n.speedKmh)
        engine.onLocation(t, n.position, n.headingDeg)
        drive.onLocation(t, n.position)
        val dest = n.destination
        val remain = n.remainingMs()
        engine.onDestination(dest?.position)
        engine.onRemainingRoute(remain)
        drive.onNaviDestination(t, dest, remain)
        noteNaviDestination(dest)
    }

    // ---- 차량 신호 ----

    override fun onVehicleConnection(connected: Boolean, message: String?) {
        _ui.update { it.copy(connection = it.connection.copy(vehicle = if (connected) CONNECTED else "실패: $message")) }
    }

    override fun onGear(gear: Gear) {
        realGear = gear
        if (demo != null) return
        val t = now()
        engine.onGear(t, gear)
        checkRestStopParking(t)
        publish()
    }

    override fun onSteeringAngle(degrees: Double) {
        if (demo == null) engine.onSteeringAngle(now(), degrees)
    }

    override fun onBrake(pressed: Boolean) {
        if (demo == null) engine.onBrake(now(), pressed)
    }

    override fun onTurnSignal(on: Boolean) {
        if (demo == null) engine.onTurnSignal(now(), on)
    }

    override fun onExtras(extras: VehicleExtras) {
        _ui.update { it.copy(extras = extras) }
    }

    /** 경고등 사건은 시연 모드에서도 받는다 (Virtual Console로 운전자 주의 경고를 켜서 보여 줄 수 있게). */
    override fun onVehicleEvent(event: VehicleEvent) {
        engine.onVehicleEvent(now(), event)
        publish()
    }

    override fun onCabinNote(message: String) {
        _ui.update { it.copy(cabinNote = message) }
    }

    // ---- 내비 신호 ----

    override fun onNaviConnection(connected: Boolean, message: String?) {
        _ui.update { it.copy(connection = it.connection.copy(navi = if (connected) CONNECTED else "실패: $message")) }
    }

    override fun onNaviLocation(position: LatLng, headingDeg: Double?, speedKmh: Double?) {
        if (demo != null) return
        val t = now()
        engine.onSpeed(t, speedKmh)
        engine.onLocation(t, position, headingDeg)
        drive.onLocation(t, position)
        publish()
    }

    override fun onNaviDestination(destination: Place?, remainSeconds: Int?) {
        realDestination = destination
        realRemainingMs = remainSeconds?.takeIf { it > 0 }?.let { it * 1_000L }
        if (demo != null) return
        engine.onDestination(destination?.position)
        engine.onRemainingRoute(realRemainingMs)
        drive.onNaviDestination(now(), destination, realRemainingMs)
        noteNaviDestination(destination)
    }

    override fun onNaviNextManeuver(meters: Double?) {
        nextManeuverMeters = meters
    }

    override fun onNaviSpeedLimit(kmh: Int?) {
        realSpeedLimit = kmh
        if (demo == null) engine.onSpeedLimit(kmh)
    }

    override fun onNaviWaypointArrived(name: String?) {
        if (demo != null) return
        drive.onWaypointArrived(now())
        note("경유지 도착: ${name ?: "경유지"}")
        publish()
    }

    override fun onNaviArrived(name: String?) {
        if (demo != null) return
        val t = now()
        engine.onTripArrived(t)
        drive.onDestinationArrived(t)
        note("목적지 도착: ${name ?: "목적지"}")
        publish()
    }

    override fun onNaviPlaces(places: List<Place>) {
        realPlaces = places
        if (demo == null) drive.onPlaces(places)
    }

    override fun onNaviTripTime(destination: Place, durationMs: Long) {
        if (demo != null) return
        drive.onDirectTime(now(), destination, durationMs)
        publish()
    }

    override fun onNaviMessage(message: String) {
        note(message)
    }

    private fun loadEnvironment(): RestEnvironment {
        val d = RestEnvironment()
        return RestEnvironment(
            enabled = prefs.getBoolean(KEY_ENV_ENABLED, d.enabled),
            restTemperatureC = prefs.getFloat(KEY_ENV_TEMP, d.restTemperatureC),
            dimLight = prefs.getBoolean(KEY_ENV_DIM, d.dimLight),
            freshAir = prefs.getBoolean(KEY_ENV_FRESH, d.freshAir),
        )
    }

    private fun loadSaved(): List<SavedTrip> = SavedTrip.decodeAll(prefs.getString(KEY_SAVED, null))

    private fun saveSaved(list: List<SavedTrip>) {
        prefs.edit().putString(KEY_SAVED, SavedTrip.encodeAll(list)).apply()
    }

    private fun saveEnvironment(env: RestEnvironment) {
        prefs.edit()
            .putBoolean(KEY_ENV_ENABLED, env.enabled)
            .putFloat(KEY_ENV_TEMP, env.restTemperatureC)
            .putBoolean(KEY_ENV_DIM, env.dimLight)
            .putBoolean(KEY_ENV_FRESH, env.freshAir)
            .apply()
    }

    companion object {
        const val CONNECTED = "연결됨"
        private const val PREFS = "restmate"
        private const val KEY_ENV_ENABLED = "env_enabled"
        private const val KEY_ENV_TEMP = "env_temp"
        private const val KEY_ENV_DIM = "env_dim"
        private const val KEY_ENV_FRESH = "env_fresh"
        private const val KEY_SAVED = "saved_trips"
        private const val TAG = "DriveMate"
        private const val TICK_MS = 1_000L
        private const val HOLD_SPEECH_METERS = 300.0
        private const val DEMO_STEP_MS = 200L
        private const val DEMO_JAM_MS = 6 * MINUTE_MS
    }
}
