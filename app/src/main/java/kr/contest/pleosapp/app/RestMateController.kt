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
import kr.contest.pleosapp.BuildConfig
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
import kr.contest.pleosapp.core.Geo
import kr.contest.pleosapp.core.HOUR_MS
import kr.contest.pleosapp.core.LatLng
import kr.contest.pleosapp.core.MINUTE_MS
import kr.contest.pleosapp.core.Mood
import kr.contest.pleosapp.core.OnTimeStyle
import kr.contest.pleosapp.core.Phase
import kr.contest.pleosapp.core.PickupNotice
import kr.contest.pleosapp.core.Place
import kr.contest.pleosapp.core.PlaceHit
import kr.contest.pleosapp.core.PlaceKind
import kr.contest.pleosapp.core.PlaceSearch
import kr.contest.pleosapp.core.PlaceSource
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
import kr.contest.pleosapp.core.VirtualClock
import kr.contest.pleosapp.data.CityRestPlaces
import kr.contest.pleosapp.data.CourseCatalog
import kr.contest.pleosapp.data.PlaceSearchClient
import kr.contest.pleosapp.data.RestAreaRepository
import kr.contest.pleosapp.data.SearchFailure
import kr.contest.pleosapp.sdk.ConsoleStatus
import kr.contest.pleosapp.sdk.NaviSignals
import kr.contest.pleosapp.sdk.VehicleExtras
import kr.contest.pleosapp.sdk.VehicleSignals
import kr.contest.pleosapp.sdk.VirtualConsoleLink
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
    /** 화면 표시용 실제 경과 시각 (SystemClock.elapsedRealtime). 자막을 몇 초 띄울지 정할 때 쓴다 */
    val realNow: Long = 0L,
    /** [lastMessage]를 말한 실제 경과 시각 */
    val lastMessageAt: Long = 0L,
    /** 안내 기록 (최근 것이 앞) */
    val log: List<LogEntry> = emptyList(),
    /** 지나온 길 (미니맵) */
    val trail: List<LatLng> = emptyList(),
    /** 이번 주행을 시작한 곳 (미니맵의 '출발') */
    val origin: LatLng? = null,
    /** 내비가 지금 따라가는 경로 */
    val route: RouteView? = null,
    /** 시간 모드로 달리기 시작한 시각 (시간 막대의 시작) */
    val driveStartedAt: Long? = null,
    /** 시연: 출발 전이라 가상 시계를 멈춘 중 */
    val demoClockHeld: Boolean = false,
    /** 시연: Virtual Console(계기판·3D 주행 화면) 연동 상태 */
    val console: ConsoleStatus = ConsoleStatus(),
    /** 자동 시연 중이면 지금 장면과 해설 */
    val auto: AutoStatus? = null,
    /** 시연: 모의 차가 지금 달리는 구간과 지나온 구간 (미니맵이 도로 모양을 따라 그린다). 실제 주행에서는 비어 있다 */
    val leg: DriveLeg? = null,
    val legs: List<DriveLeg> = emptyList(),
    /** 픽업 알림을 켜면 받을 사람 (시연: 표본 연락처 '아이', 실제: 휴대폰 연동 전이라 '동승자') */
    val pickupContact: String = RestMateController.DEFAULT_PICKUP_CONTACT,
    /** 화면 위에 띄운 입력 창 (장소 찾기, 도착 시각 고르기) */
    val sheet: InputSheet? = null,
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
    /** 눈 붙이기를 도착 시각에 맞춰 줄이기 ('시간에 맞춰 N분만 쉬기') */
    fun fitNapToArrival()
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
    /** 온타임 모드 픽업 알림 켜기·끄기 (도착 3분 전·세운 곳·자리 없음을 문자로. 시연: 화면에만) */
    fun setPickup(on: Boolean)
    /** '자리 없음': 픽업할 곳에 세울 곳이 없으면 한 바퀴 돌고 다시 오기 */
    fun circleForSpot()

    // ---- 장소·시각 입력 ----
    /** 장소 찾기 창 열기 (내비 목적지, 끝낼 곳, 시연 출발지) */
    fun openPlaceSearch(purpose: PlacePurpose)
    /** 이름·주소로 찾기 ('검색'을 누를 때만 묻는다) */
    fun searchPlaces(query: String)
    /** 찾은 곳 가운데 하나를 고른다 */
    fun choosePlace(hit: PlaceHit)
    /** 검색 결과 대신 지도에서 고르기 */
    fun showPlaceMap(on: Boolean)
    /** 지도 가운데 핀 자리를 고른다 (주소를 찾아 이름을 붙인다) */
    fun pickPlaceOnMap(at: LatLng)
    /** 도착 목표 시각 고르기 창 열기 */
    fun openTimePicker()
    fun closeSheet()

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

    /** 자동 시연 시작. [chapter]가 null이면 세 이야기를 모두 */
    fun startAutoDemo(chapter: AutoChapter?)
    fun stopAutoDemo()
    fun nextAutoScene()
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
class RestMateController(context: Context) : RestMateActions, AutoDemoHost, VehicleSignals.Sink, NaviSignals.Sink {
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
    /** 목적지 바로 옆에서 기다리다 목적지에 닿아 휴식이 끝났는지 (그러면 내비를 다시 안내하지 않는다) */
    private var restEndedOnArrival = false
    private var lastPhase = Phase.DRIVING

    private var demo: DemoSession? = null

    /** 시연: 계기판·Virtual Console을 모의 차량에 맞춘다 */
    private val console = VirtualConsoleLink(onStatus = { status -> _ui.update { it.copy(console = status) } })

    /** 장소 검색 (카카오 키가 있으면 카카오 로컬, 없으면 OpenStreetMap) */
    private val placeSearch = PlaceSearchClient(BuildConfig.KAKAO_REST_KEY)
    /** 장소 검색 차례: 창을 닫거나 다시 찾으면 늦게 온 답은 버린다 */
    private var searchToken = 0
    /** 최근에 고른 곳 (검색 창에 먼저 보여 준다) */
    private val recentPicks = ArrayDeque<PlaceHit>()

    /** 앱에 있는 곳: 경치 지점, 시내 쉴 곳, 휴게소·졸음쉼터 (인터넷이 안 될 때도 찾는다) */
    private val appPlaces: List<PlaceHit> by lazy {
        val out = ArrayList<PlaceHit>()
        for (p in CourseCatalog.POINTS) out += PlaceHit(p.name, "", p.position, PlaceSource.APP, "경치 지점")
        for (a in areas) out += PlaceHit(a.name, "", a.position, PlaceSource.APP, if (a.onExpressway) "휴게소·쉼터" else "쉴 곳")
        out
    }

    /** 시연 목적지 (광화문 약속 장소·아이 학원·강릉 숙소): 시연 모드에서만 찾는다 (실제 차량 검색에 가짜 장소가 먼저 나오지 않게) */
    private val demoPlaces: List<PlaceHit> by lazy {
        DemoTrips.ALL.mapNotNull { trip -> trip.destination?.let { PlaceHit.of(it).copy(category = "시연 목적지") } }
    }

    /** 미니맵: 지나온 길, 실제 내비에 요청한 경로, 주행을 시작한 시각 */
    private val trail = ArrayList<LatLng>()
    private var requestedRoute: RouteView? = null
    private var driveStartedAt: Long? = null
    private var lastDriveStep = DriveStep.HOME

    /** 안내 기록 (최근 것이 앞) */
    private val log = ArrayDeque<LogEntry>()

    /** 시연: 모의 차가 달리는 구간 (미니맵) */
    private val legs = LegTracker()

    /** 시연: 모의 내비와 가상 시계 (실제 1초에 trip.timeScale초가 흐른다) */
    private class DemoSession(val trip: DemoTrip) {
        val navi = DemoNavi(trip)
        val clock = VirtualClock(
            start = trip.startTimeOfDayMs?.let { todayAt(it) } ?: System.currentTimeMillis(),
            scale = trip.timeScale,
            realNow = { SystemClock.elapsedRealtime() },
        )
        var lastStepAt = now()

        fun now(): Long = clock.now()

        fun shift(ms: Long) {
            clock.shift(ms)
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

    /** 자동 시연: 장면을 스스로 진행한다 (시연 모드의 모의 차와 따로 돈다) */
    private val autoRunner = AutoDemoRunner(this)

    private val autoLoop = object : Runnable {
        override fun run() {
            autoRunner.tick(SystemClock.elapsedRealtime())
            _ui.update { it.copy(auto = autoRunner.status) }
            if (autoRunner.running) main.postDelayed(this, AUTO_STEP_MS)
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
        main.removeCallbacks(autoLoop)
        autoRunner.stop()
        console.release()
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
        updateDemoClock()
        val t = now()
        shareDeadline()
        for (effect in engine.drainEffects()) handle(effect)
        syncRest(t)
        for (effect in drive.drainEffects()) handleDrive(t, effect)
        trackDriveStart(t)
        val d = demo
        if (d != null) {
            console.update(engine.state.gear, d.navi.speedKmh)
            legs.update(d.navi.legStart, d.navi.target?.position, d.navi.position)
        }
        val route = if (d != null) d.navi.destination?.let { RouteView(it, d.navi.via) } else requestedRoute
        // 띄운 입력 창이 지금 상황에 맞지 않으면 닫는다 (쉼터 안내가 시작되거나 화면 단계가 바뀜). 늦게 온 검색 답은 버린다
        val open = _ui.value.sheet
        val closeSheet = open != null && !sheetFits(open)
        if (closeSheet) searchToken++
        _ui.update {
            it.copy(
                now = t,
                core = engine.state,
                drive = drive.state,
                speechAvailable = speaker.available,
                config = engine.config,
                demoDriving = d?.navi?.moving == true,
                demoJammed = d?.navi?.jammed == true,
                demoClockHeld = d?.clock?.held == true,
                realNow = SystemClock.elapsedRealtime(),
                log = log.toList(),
                trail = trail.toList(),
                origin = d?.trip?.start ?: trail.firstOrNull(),
                route = route,
                driveStartedAt = driveStartedAt,
                leg = legs.current,
                legs = legs.done.toList(),
                pickupContact = pickupContact(),
                sheet = if (closeSheet) null else it.sheet,
            )
        }
    }

    private fun sheetFits(sheet: InputSheet): Boolean =
        sheet.fitsNow(engine.state.phase, drive.state.step, drive.state.mode, demo != null, autoRunner.running)

    /** 픽업 알림을 받을 사람: 시연은 표본 연락처, 실제 차량은 휴대폰 연락처를 받기 전이라 '동승자' */
    private fun pickupContact(): String = demo?.trip?.pickupContact ?: DEFAULT_PICKUP_CONTACT

    /**
     * 시연: 출발 전 D단으로 서 있는 동안에는 가상 시계를 멈춘다.
     * 코스를 읽고 고르는 사이에 남는 시간이 줄거나 후보가 바뀌지 않고, '출발 · 주행'을 누르면 그때부터 흐른다.
     */
    private fun updateDemoClock() {
        val d = demo ?: return
        val standing = !d.navi.moving && engine.state.gear == Gear.DRIVE && engine.state.phase == Phase.DRIVING
        d.clock.hold(standing)
    }

    /** 시간 모드로 달리기 시작한 시각 (시간 막대의 왼쪽 끝). '자리 없음'으로 도착에서 다시 달려도 처음 출발 시각을 둔다 */
    private fun trackDriveStart(t: Long) {
        val step = drive.state.step
        if (step == DriveStep.ACTIVE && lastDriveStep != DriveStep.ACTIVE && lastDriveStep != DriveStep.ARRIVED) driveStartedAt = t
        if (step == DriveStep.HOME || step == DriveStep.SETUP) driveStartedAt = null
        lastDriveStep = step
    }

    private fun addTrail(p: LatLng) {
        val last = trail.lastOrNull()
        if (last != null && Geo.distanceMeters(last, p) < TRAIL_STEP_METERS) return
        trail += p
        // 오래 달리면 앞쪽을 성기게 줄여 개수를 묶어 둔다.
        if (trail.size > TRAIL_MAX) {
            val thinned = trail.filterIndexed { i, _ -> i % 2 == 0 || i >= trail.size - TRAIL_MAX / 2 }
            trail.clear()
            trail.addAll(thinned)
        }
    }

    private fun addLog(text: String, kind: LogKind) {
        if (log.firstOrNull()?.text == text) return
        log.addFirst(LogEntry(now(), text, kind))
        while (log.size > LOG_MAX) log.removeLast()
    }

    /**
     * 레스트 모드가 진행 중이면 시간 맞추기를 멈추고, 쉬고 출발하면 가던 목적지로 다시 안내한다
     * (시간 모드 코스의 경유지가 아직 남아 있으면 그 경유지를 거쳐서).
     */
    private fun syncRest(t: Long) {
        val phase = engine.state.phase
        drive.onRestPhase(t, phase, engine.state.rest, engine.state.target?.area)
        if (lastPhase != Phase.RESTING && phase == Phase.RESTING) {
            // 쉬는 동안은 남은 시간이 바로 가는 길 기준이 되도록 경유지를 뺀다.
            val dest = currentTrip()
            if (dest != null && !restAsDestination) routeNavi(dest, emptyList())
        }
        if (lastPhase == Phase.RESTING && phase == Phase.DRIVING) {
            val dest = currentTrip()
            // 기다리던 곳 바로 옆 목적지에 닿아서 끝난 휴식이면 다시 안내하지 않는다.
            if (dest != null && !restAsDestination && !restEndedOnArrival) {
                val via = drive.activeVia()
                routeNavi(dest, via)
                note(
                    if (via.isEmpty()) "내비에 ${dest.name}까지 다시 안내를 요청했어요."
                    else "내비에 ${DriveText.withObject(via.joinToString(", ") { it.name })} 거쳐 ${dest.name}까지 다시 안내를 요청했어요.",
                )
            }
            shareDeadline()
            restAsDestination = false
        }
        restEndedOnArrival = false
        lastPhase = phase
    }

    /** 목적지 도착 안내. 목적지 바로 옆에서 기다리다 옮겨 와 닿았으면 휴식도 끝나므로 내비를 다시 안내하지 않는다. */
    private fun tripArrived(t: Long) {
        val resting = engine.state.phase == Phase.RESTING
        engine.onTripArrived(t)
        if (resting && engine.state.phase != Phase.RESTING) restEndedOnArrival = true
    }

    private fun handle(effect: Effect) {
        when (effect) {
            is Effect.StartGuidance -> routeToRest(effect.area)
            Effect.CancelGuidance -> cancelRestRoute()
            is Effect.Speak -> say(effect.text)
            is Effect.Alarm -> if (effect.on) alarm.start() else alarm.stop()
            Effect.Chime -> alarm.chime()
            is Effect.Cabin -> {
                vehicle.cabin.apply(effect.mode, env, effect.departing)
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
            // 드라이브를 마칠 때는 무드등을 천천히 되돌린다 (데이트 드라이브가 도착하자마자 확 밝아지지 않게).
            is DriveEffect.CabinMood -> vehicle.cabin.applyMood(effect.mood, gently = true)
            is DriveEffect.SendPickup -> sendPickup(effect.notice)
        }
    }

    /**
     * 픽업 알림 문자. Pleos SDK에 문자 기능이 없고 에뮬레이터는 휴대폰과 연결할 수 없어 지금은 실제로 보내지 않는다:
     * 화면(픽업 알림 카드)과 안내 기록에만 남긴다. 실제 발송(휴대폰 연동 문자나 서버 문자 API)은 여기에 붙인다.
     */
    private fun sendPickup(notice: PickupNotice) {
        addLog("문자(시연) → ${notice.to} · ${notice.kind.label}", LogKind.MESSAGE)
        _ui.update { it.copy(log = log.toList()) }
    }

    private fun say(text: String) {
        speaker.say(text)
        addLog(text, LogKind.VOICE)
        _ui.update { it.copy(lastMessage = text, lastMessageAt = SystemClock.elapsedRealtime(), log = log.toList()) }
    }

    private fun note(text: String, kind: LogKind = LogKind.NAVI) {
        addLog(text, kind)
        _ui.update { it.copy(naviNote = text, log = log.toList()) }
    }

    // ---- 내비 경로 (실제 내비 또는 시연용 모의 내비) ----

    private fun routeNavi(destination: Place, via: List<Place>): Boolean {
        requestedRoute = RouteView(destination, via)
        val d = demo
        if (d != null) {
            d.navi.routeTo(destination, via)
            return true
        }
        return navi.routeTo(destination, via)
    }

    private fun cancelNavi() {
        requestedRoute = null
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
                else "내비를 ${DriveText.withObject(via.joinToString(", ") { it.name })} 거쳐 가는 원래 길로 되돌렸어요.",
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
    override fun fitNapToArrival() = act { engine.fitNapToArrival(it) }
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
            !env.enabled && previous.enabled -> vehicle.cabin.apply(CabinMode.RESTORE, env, departing = false)
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
        val before = _ui.value.saved
        val dropped = SavedTrip.droppedBy(before, trip)
        val list = SavedTrip.added(before, trip)
        saveSaved(list)
        _ui.update { it.copy(saved = list) }
        val tail = dropped?.let { " 꽉 차서 가장 오래 안 쓴 설정(${it.place.name} · ${it.detail})을 뺐어요." }.orEmpty()
        note("자주 쓰는 설정에 저장했어요: ${trip.place.name} · ${trip.detail}.$tail")
    }

    override fun applySavedTrip(trip: SavedTrip) {
        if (demo == null) navi.requestPlaces()
        // 쓴 칩을 맨 앞으로 (꽉 찼을 때 자주 쓰는 칩이 빠지지 않게)
        val list = SavedTrip.used(_ui.value.saved, trip)
        if (list != _ui.value.saved) {
            saveSaved(list)
            _ui.update { it.copy(saved = list) }
        }
        act { drive.applySaved(it, trip, pickupContact()) }
    }

    override fun removeSavedTrip(trip: SavedTrip) {
        val list = _ui.value.saved.filterNot { it == trip }
        saveSaved(list)
        _ui.update { it.copy(saved = list) }
        note("자주 쓰는 설정에서 뺐어요: ${trip.place.name} · ${trip.detail}")
    }

    // ---- 픽업 알림 ----

    override fun setPickup(on: Boolean) = act { drive.setPickup(if (on) pickupContact() else null) }

    override fun circleForSpot() {
        act { drive.circleForSpot(it) }
        val d = demo ?: return
        if (drive.state.circling && !d.navi.moving) {
            // 시연: 모의 차도 다시 출발한다 (P단으로 세웠으면 D단으로). 경로는 위에서 이미 받았다.
            if (engine.state.gear != Gear.DRIVE) engine.onGear(now(), Gear.DRIVE)
            d.navi.moving = true
            publish()
        }
    }

    // ---- 장소·시각 입력 ----

    override fun openPlaceSearch(purpose: PlacePurpose) {
        val sheet = InputSheet.FindPlace(
            purpose = purpose,
            mapCenter = carPosition(),
            suggestions = suggestionsFor(purpose),
            service = placeSearch.serviceName,
        )
        if (!sheetFits(sheet)) return
        searchToken++
        _ui.update { it.copy(sheet = sheet) }
    }

    override fun searchPlaces(query: String) {
        val sheet = _ui.value.sheet as? InputSheet.FindPlace ?: return
        val q = query.trim()
        if (q.isEmpty()) return
        val near = carPosition()
        val local = PlaceSearch.local(q, localPlaces(), near)
        val token = ++searchToken
        _ui.update { it.copy(sheet = sheet.copy(query = q, results = local, searching = true, note = null, onMap = false)) }
        placeSearch.search(q, near) { result ->
            if (token != searchToken) return@search
            val current = _ui.value.sheet as? InputSheet.FindPlace ?: return@search
            val merged = PlaceSearch.merge(local, result.hits)
            val why = when (result.failure) {
                SearchFailure.OFFLINE -> "인터넷에 연결되지 않아"
                SearchFailure.SERVICE -> "검색 서비스가 답하지 않아"
                null -> null
            }
            val note = when {
                why != null && merged.isEmpty() -> "$why 찾지 못했어요. 잠시 뒤 다시 찾거나 '지도에서 고르기'로 골라 주세요."
                why != null -> "$why 앱에 있는 곳에서만 찾았어요."
                merged.isEmpty() -> "'$q'(으)로 찾은 곳이 없어요. 다른 이름이나 주소로 찾아 보세요."
                else -> null
            }
            // 카카오 키가 거절되어 OpenStreetMap으로 바꿨으면 출처 표시도 바꾼다
            _ui.update {
                it.copy(sheet = current.copy(results = merged, searching = false, note = note, service = placeSearch.serviceName))
            }
        }
    }

    override fun choosePlace(hit: PlaceHit) {
        val sheet = _ui.value.sheet as? InputSheet.FindPlace ?: return
        searchToken++
        _ui.update { it.copy(sheet = null) }
        // 창을 연 뒤 쉼터 안내가 시작되었거나 화면 단계가 바뀌었으면 고른 곳을 쓰지 않는다 (안내 중인 경로를 덮어쓰지 않게)
        if (!sheetFits(sheet)) return
        rememberPick(hit)
        when (sheet.purpose) {
            PlacePurpose.NAVI_DESTINATION -> setNaviDestination(hit.toPlace(PlaceKind.CURRENT))
            // 즐겨찾기·최근 목적지면 그 장소 그대로 (POI 번호, 즐겨찾기의 '근처에서 기다리기' 기본값)
            PlacePurpose.END_PLACE -> act { drive.setDestination(it, hit.toPlace()) }
            PlacePurpose.DEMO_START -> startDemo(DemoTrips.free(hit.position, if (hit.name == HERE_NAME) "지금 위치" else hit.name))
        }
    }

    /** 목록 ↔ 지도. 찾는 중이던 검색은 그대로 두어, 목록으로 돌아오면 결과가 보인다 */
    override fun showPlaceMap(on: Boolean) {
        val sheet = _ui.value.sheet as? InputSheet.FindPlace ?: return
        _ui.update { it.copy(sheet = sheet.copy(onMap = on)) }
    }

    override fun pickPlaceOnMap(at: LatLng) {
        val sheet = _ui.value.sheet as? InputSheet.FindPlace ?: return
        if (!sheetFits(sheet)) {
            closeSheet()
            return
        }
        val token = ++searchToken
        _ui.update { it.copy(sheet = sheet.copy(locating = true, searching = false)) }
        placeSearch.reverse(at) { hit ->
            if (token != searchToken) return@reverse
            choosePlace(hit ?: PlaceHit(MAP_PICK_NAME, "", at, PlaceSource.MAP))
        }
    }

    override fun openTimePicker() {
        if (!sheetFits(InputSheet.PickTime)) return
        searchToken++
        _ui.update { it.copy(sheet = InputSheet.PickTime) }
    }

    override fun closeSheet() {
        searchToken++
        _ui.update { it.copy(sheet = null) }
    }

    /** 지금 차 위치 (검색 결과의 거리, 지도에서 고르기의 처음 가운데) */
    private fun carPosition(): LatLng? = engine.state.position ?: drive.state.position

    /** 검색어를 넣기 전에 보여 줄 곳: 최근에 고른 곳, 즐겨찾기·최근 목적지. 시연 출발지는 지금 차가 있는 곳부터 */
    private fun suggestionsFor(purpose: PlacePurpose): List<PlaceHit> {
        val out = ArrayList<PlaceHit>()
        if (purpose == PlacePurpose.DEMO_START) {
            carPosition()?.let { out += PlaceHit(HERE_NAME, "가상 시계만 지금 시각으로", it, PlaceSource.APP) }
        }
        out += recentPicks
        drive.state.naviDestination?.let { out += PlaceHit.of(it.copy(kind = PlaceKind.CURRENT)) }
        for (p in drive.state.places) out += PlaceHit.of(p)
        return PlaceSearch.merge(emptyList(), out, limit = MAX_SUGGESTIONS)
    }

    /** 앱 안에서 찾을 곳: 최근에 고른 곳, 내비 즐겨찾기·최근 목적지, (시연이면) 시연 목적지, 앱에 있는 곳 */
    private fun localPlaces(): List<PlaceHit> =
        recentPicks.toList() + drive.state.places.map { PlaceHit.of(it) } + (if (demo != null) demoPlaces else emptyList()) + appPlaces

    private fun rememberPick(hit: PlaceHit) {
        if (hit.name == HERE_NAME) return
        recentPicks.removeAll { it.name == hit.name && Geo.distanceMeters(it.position, hit.position) < 50.0 }
        recentPicks.addFirst(hit.copy(source = PlaceSource.APP, category = "최근에 고른 곳"))
        while (recentPicks.size > MAX_RECENT_PICKS) recentPicks.removeLast()
    }

    /**
     * 처음 화면에서 고른 목적지로 내비 경로를 요청한다. 시연은 모의 내비, 실제 차량은 차량 내비(NaviHelper 경로 요청).
     * 내비 목적지가 바뀌면 온타임·롱웨이 모드의 끝낼 곳도 그곳이 먼저 나온다.
     */
    private fun setNaviDestination(place: Place) {
        val before = requestedRoute
        val sent = routeNavi(place, emptyList())
        // 내비에 넘기지 못했으면 미니맵에도 그 경로를 그리지 않는다
        if (!sent) requestedRoute = before
        note(if (sent) "내비에 ${place.name} 안내를 요청했어요." else "내비가 연결되지 않아 목적지를 바꾸지 못했어요.")
        val t = now()
        if (demo != null) feedDemo(t)
        publish()
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
        // 직접 해 보기는 목적지를 정해야 출발한다 (목적지 없이 달리면 모의 차가 한쪽으로 끝없이 간다)
        if (d.trip.autoProfile && d.navi.destination == null) {
            note(FREE_TRIP_NEEDS_DESTINATION, LogKind.DEMO)
            publish()
            return
        }
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
        note("모의 정체: 앞길이 막혀 도착이 약 ${DEMO_JAM_MS / MINUTE_MS}분 늦어져요.", LogKind.DEMO)
        stepDemo()
        publish()
    }

    /** 가상 시계를 minutes분 앞으로 돌린다. 차와 엔진은 30초씩 나눠서 따라간다. */
    override fun demoSkip(minutes: Int) {
        val d = demo ?: return
        addLog("시연: 가상 시계를 ${minutes}분 앞으로 돌렸어요.", LogKind.DEMO)
        repeat(minutes * 2) { skipStep(d, 30 * SECOND_MS) }
    }

    /** 가상 시계를 [ms]만큼 돌리고 그 시각으로 한 번 돈다 (시간 건너뛰기의 한 걸음) */
    private fun skipStep(d: DemoSession, ms: Long) {
        d.shift(ms)
        stepDemo()
        val t = now()
        shareDeadline()
        engine.onTick(t)
        drive.onTick(t)
        checkRestStopParking(t)
        publish()
    }

    override fun demoAddDrivingTime() {
        if (demo != null) act { engine.addDrivingTime(it, 2 * HOUR_MS) }
    }

    override fun demoAttentionWarning() {
        if (demo != null) act { engine.onAttentionWarning(it) }
    }

    /**
     * 시연 '휴식 건너뛰기': 알람(기다리기·머물기는 출발 알림)이 울릴 때까지 가상 시계를 돌린다.
     * 쉰 시간을 앞당겨 적지 않고 시계를 돌려서, 화면의 지금 시각·알람 시각·쉰 시간이 서로 맞는다.
     * 쉬는 동안 알람이 옮겨질 수 있어 시각을 미리 정하지 않고 울릴 때까지 30초씩 돌린다 (마지막 걸음은 알람 시각에 맞춘다).
     */
    override fun demoSkipRest() {
        val d = demo ?: return
        val from = now()
        var steps = 0
        while (steps++ < MAX_REST_SKIP_STEPS) {
            val rest = engine.state.rest?.takeIf { engine.state.phase == Phase.RESTING && it.alarmPending } ?: break
            skipStep(d, (rest.alarmAt - now()).coerceIn(SECOND_MS, 30 * SECOND_MS))
        }
        if (now() > from) addLog("시연: 가상 시계를 알람 시각까지 ${DriveText.duration(now() - from)} 돌렸어요.", LogKind.DEMO)
    }

    // ---- 자동 시연 ----

    override fun startAutoDemo(chapter: AutoChapter?) {
        main.removeCallbacks(autoLoop)
        searchToken++
        autoRunner.start(chapter, SystemClock.elapsedRealtime())
        _ui.update { it.copy(auto = autoRunner.status, sheet = null) }
        if (autoRunner.running) main.postDelayed(autoLoop, AUTO_STEP_MS)
    }

    override fun stopAutoDemo() {
        main.removeCallbacks(autoLoop)
        autoRunner.stop()
        _ui.update { it.copy(auto = null) }
    }

    override fun nextAutoScene() {
        autoRunner.skip(SystemClock.elapsedRealtime())
        _ui.update { it.copy(auto = autoRunner.status) }
        if (!autoRunner.running) main.removeCallbacks(autoLoop)
    }

    override fun view(): AutoView {
        val u = _ui.value
        return AutoView(
            now = u.now,
            core = u.core,
            drive = u.drive,
            tripId = u.demoTrip?.id,
            driving = u.demoDriving,
            jammed = u.demoJammed,
            clockHeld = u.demoClockHeld,
        )
    }

    private fun startDemo(trip: DemoTrip) {
        main.removeCallbacks(demoLoop)
        resetEngines()
        val session = DemoSession(trip)
        demo = session
        console.start()
        val t = now()
        engine = RestMateEngine(finder, RestMateConfig.forDemo(trip.timeScale))
        engine.setOnExpressway(trip.expressway)
        // 출발 직전: D단으로 서 있는 상태 ('주행'을 누르면 움직인다)
        engine.onGear(t, Gear.DRIVE)
        engine.onSpeed(t, 0.0)
        drive.onPlaces(trip.places)
        feedDemo(t)
        _ui.update { it.copy(demoMode = true, demoTrip = trip, lastMessage = null, naviNote = null, sheet = null) }
        if (trip.id == DemoTrips.FREE_ID) {
            note("시연: ${trip.startName ?: "고른 곳"}에서 출발해요. 가상 시계는 지금 시각부터 흘러요. 목적지를 검색해 정해 주세요.", LogKind.DEMO)
        }
        main.post(demoLoop)
        publish()
    }

    private fun stopDemo() {
        // 시연 모드를 끄면 자동 시연도 멈춘다.
        stopAutoDemo()
        main.removeCallbacks(demoLoop)
        resetEngines()
        demo = null
        console.stop()
        engine = RestMateEngine(finder)
        replayRealSignals()
        _ui.update { it.copy(demoMode = false, demoTrip = null, lastMessage = null, naviNote = null, sheet = null) }
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
        trail.clear()
        log.clear()
        legs.clear()
        requestedRoute = null
        driveStartedAt = null
        lastDriveStep = DriveStep.HOME
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
                    tripArrived(t)
                    if (pickupTrip()) {
                        // 픽업: 바로 P단으로 세우지 않고 멈춰 선다. 세울 곳이 있으면 'P단 주차'(세운 곳을 보냄), 없으면 '자리 없음'.
                        engine.onSpeed(t, 0.0)
                        note("모의: ${event.place.name} 앞에 왔어요. 세울 곳이 있으면 P단 주차, 없으면 '자리 없음'.")
                    } else {
                        note("모의: ${event.place.name}에 도착해 P단으로 주차했어요.")
                        parkDemoCar(t)
                    }
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

    /**
     * 픽업 알림을 켠 온타임 모드로 가는 중인지 (시연: 목적지에서 바로 P단으로 세우지 않는다).
     * 시간 모드는 목적지 150m 안에서 먼저 도착으로 보므로, 모의 차가 목적지 점에 닿을 때는 이미 '도착'일 수 있다.
     */
    private fun pickupTrip(): Boolean {
        val s = drive.state
        val going = s.step == DriveStep.ACTIVE || s.step == DriveStep.ARRIVED
        return going && s.mode == DriveMode.ON_TIME && s.pickupTo != null && engine.state.phase == Phase.DRIVING
    }

    private fun parkDemoCar(t: Long) {
        demo?.navi?.moving = false
        engine.onSpeed(t, 0.0)
        engine.onGear(t, Gear.PARK)
        parked(t)
        checkRestStopParking(t)
    }

    /** P단: 픽업할 곳 가까이면 세운 곳을 보낸다. 그 주차로 도착했으면 레스트 모드에도 알려 '주차하셨네요'를 묻지 않게 한다. */
    private fun parked(t: Long) {
        if (drive.onParked(t)) tripArrived(t)
    }

    private fun feedDemo(t: Long) {
        val d = demo ?: return
        val n = d.navi
        // 직접 해 보기: 먼 곳으로 가면 고속도로로 보고 쉼터·기본 휴식 시간을 고른다
        if (d.trip.autoProfile) engine.setOnExpressway(n.onHighway)
        addTrail(n.position)
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
        if (gear == Gear.PARK) parked(t)
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
        addLog(message, LogKind.VEHICLE)
        _ui.update { it.copy(cabinNote = message, log = log.toList()) }
    }

    // ---- 내비 신호 ----

    override fun onNaviConnection(connected: Boolean, message: String?) {
        _ui.update { it.copy(connection = it.connection.copy(navi = if (connected) CONNECTED else "실패: $message")) }
    }

    override fun onNaviLocation(position: LatLng, headingDeg: Double?, speedKmh: Double?) {
        if (demo != null) return
        addTrail(position)
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
        requestedRoute = requestedRoute?.let { it.copy(via = it.via.drop(1)) }
        drive.onWaypointArrived(now())
        note("경유지 도착: ${name ?: "경유지"}")
        publish()
    }

    override fun onNaviArrived(name: String?) {
        if (demo != null) return
        val t = now()
        tripArrived(t)
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
        /** 실제 차량에서 픽업 알림 받는 사람 이름 (휴대폰 연락처를 받기 전) */
        const val DEFAULT_PICKUP_CONTACT = "동승자"

        /** 지도에서 고른 곳의 주소를 찾지 못했을 때 붙이는 이름 */
        const val MAP_PICK_NAME = "지도에서 고른 곳"

        /** 시연 출발지: 지금 차가 있는 곳에서 그대로 */
        const val HERE_NAME = "지금 차가 있는 곳"
        private const val MAX_SUGGESTIONS = 8
        private const val MAX_RECENT_PICKS = 6
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
        private const val AUTO_STEP_MS = 200L
        private const val DEMO_JAM_MS = 6 * MINUTE_MS

        /** '휴식 건너뛰기'로 돌리는 가장 긴 시간: 30초씩 6시간 */
        private const val MAX_REST_SKIP_STEPS = 6 * 120

        private const val TRAIL_STEP_METERS = 25.0
        private const val TRAIL_MAX = 600
        private const val LOG_MAX = 8
    }
}
