package kr.contest.pleosapp.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kr.contest.pleosapp.audio.AlarmPlayer
import kr.contest.pleosapp.audio.Speaker
import kr.contest.pleosapp.core.CheckInAnswer
import kr.contest.pleosapp.core.DemoScenarios
import kr.contest.pleosapp.core.Effect
import kr.contest.pleosapp.core.Gear
import kr.contest.pleosapp.core.Geo
import kr.contest.pleosapp.core.HOUR_MS
import kr.contest.pleosapp.core.LatLng
import kr.contest.pleosapp.core.Phase
import kr.contest.pleosapp.core.RestArea
import kr.contest.pleosapp.core.RestAreaCandidate
import kr.contest.pleosapp.core.RestAreaFinder
import kr.contest.pleosapp.core.RestMateConfig
import kr.contest.pleosapp.core.RestMateEngine
import kr.contest.pleosapp.core.RestMateState
import kr.contest.pleosapp.data.RestAreaRepository
import kr.contest.pleosapp.sdk.NaviSignals
import kr.contest.pleosapp.sdk.VehicleExtras
import kr.contest.pleosapp.sdk.VehicleSignals
import kotlin.math.PI
import kotlin.math.sin

data class ConnectionStatus(
    val vehicle: String = "연결 중…",
    val navi: String = "연결 중…",
)

data class UiState(
    val core: RestMateState = RestMateState(),
    /** 시연 모드: 실제 차량 신호 대신 모의 신호를 쓰고 대기 시간을 줄인다. 화면에 늘 표시한다. */
    val demoMode: Boolean = false,
    val demoDriving: Boolean = false,
    val demoWobbly: Boolean = true,
    val demoScenario: String? = null,
    val extras: VehicleExtras = VehicleExtras(),
    val connection: ConnectionStatus = ConnectionStatus(),
    val restAreaCount: Int = 0,
    val speechAvailable: Boolean = false,
    /** 마지막으로 말한 안내 문구. 음성이 안 나와도 화면으로 볼 수 있게 한다. */
    val lastMessage: String? = null,
    val naviNote: String? = null,
    val config: RestMateConfig = RestMateConfig(),
)

/** 화면에서 부르는 동작. */
interface RestMateActions {
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
    fun departNow()
    fun dismissRecovery()
    fun acceptRecoveryRest()

    fun setDemoMode(on: Boolean)
    fun demoDrive(wobbly: Boolean)
    fun demoPark()
    fun demoAddDrivingTime()
    fun demoAttentionWarning()
    fun demoSkipRest()
    fun demoRestart()
}

/**
 * 엔진과 SDK·음성·알람을 잇는다. 모든 호출은 메인 스레드에서 한다.
 */
class RestMateController(context: Context) : RestMateActions, VehicleSignals.Sink, NaviSignals.Sink {
    private val appContext = context.applicationContext
    private val areas: List<RestArea> = runCatching { RestAreaRepository.load(appContext) }
        .onFailure { Log.e(TAG, "rest area data", it) }
        .getOrDefault(emptyList())
    private val finder = RestAreaFinder(areas)
    private var engine = RestMateEngine(finder)
    private val vehicle = VehicleSignals(appContext, this)
    private val navi = NaviSignals(appContext, this)
    private val speaker = Speaker(appContext)
    private val alarm = AlarmPlayer(appContext)
    private val main = Handler(Looper.getMainLooper())

    private val _ui = MutableStateFlow(UiState(restAreaCount = areas.size))
    val ui: StateFlow<UiState> = _ui

    private var useRealSignals = true
    private var nextManeuverMeters: Double? = null

    // 실제 차량·내비에서 마지막으로 받은 값. 시연 모드를 끌 때 새 엔진에 다시 넣는다.
    private var realGear = Gear.UNKNOWN
    private var realSpeedLimit: Int? = null
    private var realDestination: LatLng? = null
    private var realRemainingMs: Long? = null

    private val scenario by lazy { DemoScenarios.pick(areas) }
    private var demoPosition: LatLng? = null
    private var demoHeading = 180.0
    private var demoClock = 0.0

    private val ticker = object : Runnable {
        override fun run() {
            engine.onTick(now())
            speaker.flush()
            publish()
            main.postDelayed(this, TICK_MS)
        }
    }

    private val demoLoop = object : Runnable {
        override fun run() {
            demoStep()
            main.postDelayed(this, DEMO_STEP_MS)
        }
    }

    fun start() {
        // 회전·진입 안내 직전에는 말을 미룬다 (실제 내비 안내를 따를 때만).
        speaker.shouldHold = { useRealSignals && (nextManeuverMeters ?: Double.MAX_VALUE) < HOLD_SPEECH_METERS }
        vehicle.start()
        navi.start()
        main.post(ticker)
        publish()
    }

    fun stop() {
        main.removeCallbacks(ticker)
        main.removeCallbacks(demoLoop)
        vehicle.stop()
        navi.stop()
        speaker.release()
        alarm.release()
    }

    private fun now() = System.currentTimeMillis()

    private fun publish() {
        for (effect in engine.drainEffects()) handle(effect)
        _ui.update { it.copy(core = engine.state, speechAvailable = speaker.available, config = engine.config) }
    }

    private fun handle(effect: Effect) {
        when (effect) {
            is Effect.StartGuidance -> {
                val sent = navi.guideTo(effect.area)
                _ui.update {
                    it.copy(naviNote = if (sent) "내비에 ${effect.area.name} 안내를 요청했어요." else "내비가 연결되지 않아 화면으로만 안내해요.")
                }
            }
            Effect.CancelGuidance -> navi.cancelGuidance()
            is Effect.Speak -> {
                speaker.say(effect.text)
                _ui.update { it.copy(lastMessage = effect.text) }
            }
            is Effect.Alarm -> if (effect.on) alarm.start() else alarm.stop()
        }
    }

    private inline fun act(block: (Long) -> Unit) {
        block(now())
        publish()
    }

    // ---- 화면 조작 ----

    override fun pressDrowsy() = act { engine.pressDrowsy(it) }
    override fun selectRestArea(candidate: RestAreaCandidate) = act { engine.selectRestArea(it, candidate) }
    override fun cancelGuidance() = act { engine.cancelGuidance(it) }
    override fun answerCheckIn(answer: CheckInAnswer) = act { engine.answerCheckIn(it, answer) }
    override fun acceptPrompt() = act { engine.acceptPrompt(it) }
    override fun dismissPrompt() = act { engine.dismissPrompt(it) }
    override fun setRestMinutes(minutes: Int) = act { engine.setRestMinutes(it, minutes) }
    override fun extendRest(minutes: Int) = act { engine.extendRest(it, minutes) }
    override fun stopAlarm() = act { engine.stopAlarm(it) }
    override fun departNow() = act { engine.departNow(it) }
    override fun dismissRecovery() = act { engine.dismissRecovery(it) }
    override fun acceptRecoveryRest() = act { engine.acceptRecoveryRest(it) }

    override fun startRest() {
        // 시연 중 '주차했어요'를 누르면 모의 차량도 세운다.
        if (_ui.value.demoMode && _ui.value.demoDriving) {
            stopDemoDriving()
            engine.onSpeed(now(), 0.0)
        }
        act { engine.startRest(it) }
    }

    // ---- 시연 모드 ----

    override fun setDemoMode(on: Boolean) {
        stopDemoDriving()
        useRealSignals = !on
        resetEngine(if (on) RestMateConfig.DEMO else RestMateConfig())
        if (on) placeDemoCar() else replayRealSignals()
        _ui.update {
            it.copy(
                demoMode = on,
                demoScenario = if (on) scenario.description else null,
                lastMessage = null,
                naviNote = null,
            )
        }
        publish()
    }

    override fun demoRestart() {
        if (_ui.value.demoMode) setDemoMode(true)
    }

    override fun demoDrive(wobbly: Boolean) {
        if (!_ui.value.demoMode) return
        engine.onGear(now(), Gear.DRIVE)
        _ui.update { it.copy(demoDriving = true, demoWobbly = wobbly) }
        main.removeCallbacks(demoLoop)
        main.post(demoLoop)
        publish()
    }

    override fun demoPark() {
        if (!_ui.value.demoMode) return
        stopDemoDriving()
        val t = now()
        engine.onSpeed(t, 0.0)
        engine.onGear(t, Gear.PARK)
        publish()
    }

    override fun demoAddDrivingTime() {
        if (_ui.value.demoMode) act { engine.addDrivingTime(it, 2 * HOUR_MS) }
    }

    override fun demoAttentionWarning() {
        if (_ui.value.demoMode) act { engine.onAttentionWarning(it) }
    }

    override fun demoSkipRest() {
        if (_ui.value.demoMode) act { engine.skipToAlarm(it) }
    }

    /** 새 엔진으로 처음부터 시작한다. 진행 중이던 안내와 알람은 끈다. */
    private fun resetEngine(config: RestMateConfig) {
        if (engine.state.phase == Phase.TO_REST_AREA) navi.cancelGuidance()
        alarm.stop()
        engine = RestMateEngine(finder, config)
    }

    private fun placeDemoCar() {
        val t = now()
        val s = scenario
        demoPosition = s.start
        demoHeading = s.headingDeg
        demoClock = 0.0
        engine.setOnExpressway(true)
        engine.onDestination(s.destination)
        engine.onGear(t, Gear.DRIVE)
        engine.onSpeed(t, 0.0)
        engine.onLocation(t, s.start, s.headingDeg)
    }

    private fun replayRealSignals() {
        val t = now()
        engine.onGear(t, realGear)
        engine.onSpeedLimit(realSpeedLimit)
        engine.onDestination(realDestination)
        engine.onRemainingRoute(realRemainingMs)
    }

    private fun stopDemoDriving() {
        main.removeCallbacks(demoLoop)
        _ui.update { it.copy(demoDriving = false) }
    }

    /** 모의 주행 한 걸음: 시속 100km로 표시하되 위치는 6배 빠르게 옮기고, 조향각을 흔든다. */
    private fun demoStep() {
        val t = now()
        val position = demoPosition ?: return
        val dtSec = DEMO_STEP_MS / 1000.0
        val next = Geo.destinationPoint(position, demoHeading, DEMO_SPEED_KMH / 3.6 * dtSec * DEMO_TIME_SCALE)
        demoPosition = next
        demoClock += dtSec
        val amplitude = if (_ui.value.demoWobbly) WOBBLY_AMPLITUDE_DEG else CALM_AMPLITUDE_DEG
        val angle = amplitude * sin(2 * PI * demoClock / STEERING_PERIOD_SEC)

        engine.onSpeed(t, DEMO_SPEED_KMH)
        engine.onLocation(t, next, demoHeading)
        engine.onSteeringAngle(t, angle)

        // 쉼터에 거의 닿으면 진입해서 P단으로 주차하는 것까지 흉내 낸다.
        val state = engine.state
        val target = state.target
        if (state.phase == Phase.TO_REST_AREA && target != null &&
            Geo.distanceMeters(next, target.area.position) <= DEMO_ARRIVE_METERS
        ) {
            demoPosition = target.area.position
            engine.onLocation(t, target.area.position, demoHeading)
            stopDemoDriving()
            engine.onSpeed(t, 0.0)
            engine.onGear(t, Gear.PARK)
            _ui.update { it.copy(naviNote = "모의: ${target.area.name}에 진입해 P단으로 주차했어요.") }
        }
        publish()
    }

    // ---- 차량 신호 ----

    override fun onVehicleConnection(connected: Boolean, message: String?) {
        _ui.update { it.copy(connection = it.connection.copy(vehicle = if (connected) CONNECTED else "실패: $message")) }
    }

    override fun onGear(gear: Gear) {
        realGear = gear
        if (!useRealSignals) return
        engine.onGear(now(), gear)
        publish()
    }

    override fun onSteeringAngle(degrees: Double) {
        if (useRealSignals) engine.onSteeringAngle(now(), degrees)
    }

    override fun onBrake(pressed: Boolean) {
        if (useRealSignals) engine.onBrake(now(), pressed)
    }

    override fun onTurnSignal(on: Boolean) {
        if (useRealSignals) engine.onTurnSignal(now(), on)
    }

    override fun onExtras(extras: VehicleExtras) {
        _ui.update { it.copy(extras = extras) }
    }

    // ---- 내비 신호 ----

    override fun onNaviConnection(connected: Boolean, message: String?) {
        _ui.update { it.copy(connection = it.connection.copy(navi = if (connected) CONNECTED else "실패: $message")) }
    }

    override fun onNaviLocation(position: LatLng, headingDeg: Double?, speedKmh: Double?) {
        if (!useRealSignals) return
        val t = now()
        engine.onSpeed(t, speedKmh)
        engine.onLocation(t, position, headingDeg)
        publish()
    }

    override fun onNaviDestination(destination: LatLng?, name: String?, remainSeconds: Int?) {
        realDestination = destination
        realRemainingMs = remainSeconds?.takeIf { it > 0 }?.let { it * 1_000L }
        if (!useRealSignals) return
        engine.onDestination(realDestination)
        engine.onRemainingRoute(realRemainingMs)
    }

    override fun onNaviNextManeuver(meters: Double?) {
        nextManeuverMeters = meters
    }

    override fun onNaviSpeedLimit(kmh: Int?) {
        realSpeedLimit = kmh
        if (useRealSignals) engine.onSpeedLimit(kmh)
    }

    override fun onNaviWaypointArrived(name: String?) {
        _ui.update { it.copy(naviNote = "경유지 도착: ${name ?: "쉼터"}") }
    }

    override fun onNaviMessage(message: String) {
        _ui.update { it.copy(naviNote = message) }
    }

    companion object {
        const val CONNECTED = "연결됨"
        private const val TAG = "RestMate"
        private const val TICK_MS = 1_000L
        private const val HOLD_SPEECH_METERS = 300.0
        private const val DEMO_STEP_MS = 200L
        private const val DEMO_SPEED_KMH = 100.0
        private const val DEMO_TIME_SCALE = 6.0
        private const val DEMO_ARRIVE_METERS = 400.0
        private const val WOBBLY_AMPLITUDE_DEG = 4.0
        private const val CALM_AMPLITUDE_DEG = 0.5
        private const val STEERING_PERIOD_SEC = 2.4
    }
}
