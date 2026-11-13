package kr.contest.pleosapp.app

import kr.contest.pleosapp.core.CheckInAnswer
import kr.contest.pleosapp.core.CourseOption
import kr.contest.pleosapp.core.DemoNavi
import kr.contest.pleosapp.core.DemoNaviEvent
import kr.contest.pleosapp.core.DemoTrip
import kr.contest.pleosapp.core.DemoTrips
import kr.contest.pleosapp.core.DriveEffect
import kr.contest.pleosapp.core.DriveEngine
import kr.contest.pleosapp.core.DriveMode
import kr.contest.pleosapp.core.DriveStep
import kr.contest.pleosapp.core.Effect
import kr.contest.pleosapp.core.Gear
import kr.contest.pleosapp.core.LatLng
import kr.contest.pleosapp.core.Mood
import kr.contest.pleosapp.core.OnTimeStyle
import kr.contest.pleosapp.core.Phase
import kr.contest.pleosapp.core.PickupNotice
import kr.contest.pleosapp.core.Place
import kr.contest.pleosapp.core.PlaceKind
import kr.contest.pleosapp.core.RestArea
import kr.contest.pleosapp.core.RestAreaCleaner
import kr.contest.pleosapp.core.RestAreaFinder
import kr.contest.pleosapp.core.RestAreaType
import kr.contest.pleosapp.core.RestMateConfig
import kr.contest.pleosapp.core.RestMateEngine
import kr.contest.pleosapp.core.SECOND_MS
import kr.contest.pleosapp.core.VirtualClock
import kr.contest.pleosapp.data.CityRestPlaces
import kr.contest.pleosapp.data.CourseCatalog
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * 시연 모드의 컨트롤러([RestMateController])를 안드로이드 없이 흉내 내는 시험대.
 * 모의 내비·가상 시계·두 엔진을 컨트롤러와 같은 순서로 잇는다 (자동 시연 대본을 실제 엔진으로 돌려 보려고).
 *
 * 실제 시간은 [advance]로 흘린다: 0.2초마다 모의 차를 옮기고(demoLoop), 1초마다 엔진 시계를 돌린다(ticker).
 */
class DemoRig(highwayAreas: List<RestArea> = loadHighwayAreas()) : AutoDemoHost {
    private val areas = highwayAreas + CityRestPlaces.SAMPLES
    private val finder = RestAreaFinder(areas)
    var engine = RestMateEngine(finder)
        private set
    var drive = DriveEngine(CourseCatalog.POINTS, areas)
        private set

    /** 가짜 SystemClock.elapsedRealtime */
    var real = 0L
        private set

    private class Session(val trip: DemoTrip, realNow: () -> Long, day: Long) {
        val navi = DemoNavi(trip)
        val clock = VirtualClock(day + (trip.startTimeOfDayMs ?: 0L), trip.timeScale, realNow)
        var lastStepAt = clock.now()
    }

    private var demo: Session? = null
    private var tripDestination: Place? = null
    private var restAsDestination = false
    private var restEndedOnArrival = false
    private var lastPhase = Phase.DRIVING

    /** 차가 한 말·내비 요청·알람 (시각과 함께) */
    val said = ArrayList<String>()
    val notes = ArrayList<String>()
    /** 보낸 픽업 알림 (시연: 실제로 보내지 않는다) */
    val sent = ArrayList<PickupNotice>()
    var alarmOn = false
        private set
    var lastRoute: Pair<Place, List<Place>>? = null
        private set

    private var sinceTick = 0L

    fun now(): Long = demo?.clock?.now() ?: 0L

    /** 실제 시간을 ms만큼 흘린다 (0.2초 단위) */
    fun advance(ms: Long, each: () -> Unit = {}) {
        var left = ms
        while (left > 0) {
            val step = minOf(STEP_MS, left)
            real += step
            left -= step
            stepDemo()
            publish()
            sinceTick += step
            if (sinceTick >= TICK_MS) {
                sinceTick = 0
                val t = now()
                shareDeadline()
                engine.onTick(t)
                drive.onTick(t)
                checkRestStopParking(t)
                publish()
            }
            each()
        }
    }

    override fun view() = AutoView(
        now = now(),
        core = engine.state,
        drive = drive.state,
        tripId = demo?.trip?.id,
        driving = demo?.navi?.moving == true,
        jammed = demo?.navi?.jammed == true,
        clockHeld = demo?.clock?.held == true,
    )

    // ---- 컨트롤러와 같은 동작 ----

    private fun publish() {
        updateDemoClock()
        val t = now()
        shareDeadline()
        for (effect in engine.drainEffects()) handle(effect)
        syncRest(t)
        for (effect in drive.drainEffects()) handleDrive(t, effect)
    }

    private fun updateDemoClock() {
        val d = demo ?: return
        val standing = !d.navi.moving && engine.state.gear == Gear.DRIVE && engine.state.phase == Phase.DRIVING
        d.clock.hold(standing)
    }

    private fun shareDeadline() = engine.setDeadline(drive.deadline())

    private fun syncRest(t: Long) {
        val phase = engine.state.phase
        drive.onRestPhase(t, phase, engine.state.rest, engine.state.target?.area)
        if (lastPhase != Phase.RESTING && phase == Phase.RESTING) {
            val dest = currentTrip()
            if (dest != null && !restAsDestination) routeNavi(dest, emptyList())
        }
        if (lastPhase == Phase.RESTING && phase == Phase.DRIVING) {
            val dest = currentTrip()
            if (dest != null && !restAsDestination && !restEndedOnArrival) {
                routeNavi(dest, drive.activeVia())
                notes += "다시 안내: ${dest.name}"
            }
            shareDeadline()
            restAsDestination = false
        }
        restEndedOnArrival = false
        lastPhase = phase
    }

    private fun tripArrived(t: Long) {
        val resting = engine.state.phase == Phase.RESTING
        engine.onTripArrived(t)
        if (resting && engine.state.phase != Phase.RESTING) restEndedOnArrival = true
    }

    private fun handle(effect: Effect) {
        when (effect) {
            is Effect.StartGuidance -> routeToRest(effect.area)
            Effect.CancelGuidance -> cancelRestRoute()
            is Effect.Speak -> said += effect.text
            is Effect.Alarm -> alarmOn = effect.on
            Effect.Chime -> Unit
            is Effect.Cabin -> notes += "실내: ${effect.mode}"
        }
    }

    private fun handleDrive(t: Long, effect: DriveEffect) {
        when (effect) {
            is DriveEffect.AskDirectTime -> {
                val d = demo
                if (d != null) drive.onDirectTime(t, effect.destination, d.navi.tripTimeMs(effect.destination))
            }
            is DriveEffect.Route -> {
                routeNavi(effect.destination, effect.via)
                notes += "경로: ${effect.via.joinToString { it.name }} → ${effect.destination.name}"
            }
            is DriveEffect.Speak -> said += effect.text
            is DriveEffect.CabinMood -> Unit
            is DriveEffect.SendPickup -> {
                sent += effect.notice
                notes += "문자(시연) → ${effect.notice.to} · ${effect.notice.kind.label}"
            }
        }
    }

    private fun routeNavi(destination: Place, via: List<Place>) {
        lastRoute = destination to via
        demo?.navi?.routeTo(destination, via)
    }

    private fun currentTrip(): Place? {
        val d = drive.state
        if (d.step == DriveStep.ACTIVE) d.destination?.let { return it }
        return tripDestination
    }

    private fun routeToRest(area: RestArea) {
        val stop = Place(area.name, area.position, PlaceKind.WAYPOINT)
        val dest = currentTrip()
        if (dest != null && !dest.sameSpot(stop)) {
            restAsDestination = false
            routeNavi(dest, listOf(stop))
        } else {
            restAsDestination = true
            routeNavi(stop, emptyList())
        }
        notes += "쉼터 안내: ${area.name}"
    }

    private fun cancelRestRoute() {
        val dest = currentTrip()
        if (dest != null && !restAsDestination) {
            routeNavi(dest, drive.activeVia())
        } else {
            lastRoute = null
            demo?.navi?.cancel()
        }
        restAsDestination = false
    }

    private fun noteNaviDestination(place: Place?) {
        if (engine.state.phase != Phase.DRIVING || restAsDestination) return
        tripDestination = place
    }

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

    override fun pressDrowsy() = act { engine.pressDrowsy(it) }
    override fun answerCheckIn(answer: CheckInAnswer) = act { engine.answerCheckIn(it, answer) }
    override fun stopAlarm() = act { engine.stopAlarm(it) }
    fun wakeUp() = act { engine.wakeUp(it) }

    override fun departNow() {
        val d = demo
        if (d != null && engine.state.phase == Phase.RESTING) {
            d.navi.moving = true
            engine.onGear(now(), Gear.DRIVE)
        }
        act { engine.departNow(it) }
    }

    override fun openMode(mode: DriveMode) = act { drive.openMode(it, mode) }
    override fun setDriveDestination(place: Place) = act { drive.setDestination(it, place) }
    override fun setTarget(at: Long) = act { drive.setTarget(it, at) }
    override fun setExtraMinutes(minutes: Int) = act { drive.setExtraMinutes(minutes) }
    override fun setMood(mood: Mood) = act { drive.setMood(mood) }
    override fun planCourses() = act { drive.plan(it) }
    override fun chooseCourse(option: CourseOption) = act { drive.choose(it, option) }
    override fun addDriveTime(minutes: Int) = act { drive.addTime(it, minutes) }
    fun setOnTimeStyle(style: OnTimeStyle) = act { drive.setOnTimeStyle(style) }
    override fun setPickup(on: Boolean) = act { drive.setPickup(if (on) demo?.trip?.pickupContact else null) }

    override fun circleForSpot() {
        act { drive.circleForSpot(it) }
        val d = demo ?: return
        if (drive.state.circling && !d.navi.moving) {
            if (engine.state.gear != Gear.DRIVE) engine.onGear(now(), Gear.DRIVE)
            d.navi.moving = true
            publish()
        }
    }

    override fun demoPark() {
        if (demo == null) return
        parkDemoCar(now())
        publish()
    }

    override fun selectDemoTrip(id: String) {
        val trip = DemoTrips.ALL.firstOrNull { it.id == id } ?: return
        startDemo(trip)
    }

    override fun demoDrive() {
        val d = demo ?: return
        // 직접 해 보기는 목적지를 정해야 출발한다 (컨트롤러와 같게)
        if (d.trip.autoProfile && d.navi.destination == null) {
            notes += FREE_TRIP_NEEDS_DESTINATION
            return
        }
        d.navi.moving = true
        engine.onGear(now(), Gear.DRIVE)
        stepDemo()
        publish()
    }

    override fun demoTraffic() {
        val d = demo ?: return
        d.navi.jam(DEMO_JAM_MS)
        stepDemo()
        publish()
    }

    override fun demoSkip(minutes: Int) {
        val d = demo ?: return
        repeat(minutes * 2) {
            d.clock.shift(30 * SECOND_MS)
            stepDemo()
            val t = now()
            shareDeadline()
            engine.onTick(t)
            drive.onTick(t)
            checkRestStopParking(t)
            publish()
        }
    }

    override fun demoSkipRest() = act { engine.skipToAlarm(it) }

    // ---- 직접 해 보기 (컨트롤러의 출발지 고르기·목적지 검색과 같게) ----

    /** 시연 중 모의 내비 */
    val navi: DemoNavi? get() = demo?.navi

    /** '직접 해 보기'로 [start]에서 출발 */
    fun startFree(start: LatLng, name: String) = startDemo(DemoTrips.free(start, name))

    /** 처음 화면의 '목적지 검색'으로 고른 곳을 내비 목적지로 */
    fun setNaviDestination(place: Place) {
        routeNavi(place, emptyList())
        feedDemo(now())
        publish()
    }

    private fun startDemo(trip: DemoTrip) {
        resetEngines()
        val session = Session(trip, { real }, DAY)
        demo = session
        val t = now()
        engine = RestMateEngine(finder, RestMateConfig.forDemo(trip.timeScale))
        engine.setOnExpressway(trip.expressway)
        engine.onGear(t, Gear.DRIVE)
        engine.onSpeed(t, 0.0)
        drive.onPlaces(trip.places)
        feedDemo(t)
        publish()
    }

    private fun resetEngines() {
        if (drive.state.step != DriveStep.HOME) {
            drive.finish(now())
            drive.drainEffects()
        }
        alarmOn = false
        engine.drainEffects()
        drive = DriveEngine(CourseCatalog.POINTS, areas)
        tripDestination = null
        restAsDestination = false
        lastPhase = Phase.DRIVING
        lastRoute = null
    }

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
                    notes += "경유지 도착: ${event.place.name}"
                    if (isRestTarget(event.place)) parkDemoCar(t)
                }
                is DemoNaviEvent.DestinationArrived -> {
                    notes += "목적지 도착: ${event.place.name}"
                    tripArrived(t)
                    if (pickupTrip()) engine.onSpeed(t, 0.0) else parkDemoCar(t)
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

    private fun pickupTrip(): Boolean {
        val s = drive.state
        val going = s.step == DriveStep.ACTIVE || s.step == DriveStep.ARRIVED
        return going && s.mode == DriveMode.ON_TIME && s.pickupTo != null && engine.state.phase == Phase.DRIVING
    }

    private fun parkDemoCar(t: Long) {
        demo?.navi?.moving = false
        engine.onSpeed(t, 0.0)
        engine.onGear(t, Gear.PARK)
        if (drive.onParked(t)) tripArrived(t)
        checkRestStopParking(t)
    }

    private fun feedDemo(t: Long) {
        val d = demo ?: return
        val n = d.navi
        // 직접 해 보기: 고속도로 구간이면 쉼터·기본 휴식 시간을 고속도로 기준으로 (컨트롤러와 같게)
        if (d.trip.autoProfile) engine.setOnExpressway(n.onHighway)
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

    companion object {
        private const val STEP_MS = 200L
        private const val TICK_MS = 1_000L
        private const val DEMO_JAM_MS = 6 * 60 * SECOND_MS

        /** 시험 날짜 (11월이라 오후 5시부터 밤: 롱웨이 기본 길이 야경) */
        val ZONE: TimeZone = TimeZone.getTimeZone("Asia/Seoul")
        val DAY: Long = Calendar.getInstance(ZONE).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 14, 0, 0, 0)
        }.timeInMillis

        /** assets/rest_areas.json을 org.json 없이 읽는다 (항목이 평평한 객체라 정규식으로 충분) */
        fun loadHighwayAreas(): List<RestArea> {
            val file = listOf("src/main/assets/rest_areas.json", "app/src/main/assets/rest_areas.json")
                .map(::File).firstOrNull { it.exists() } ?: error("rest_areas.json 없음")
            val text = file.readText(Charsets.UTF_8)
            val obj = Regex("\\{([^{}]*)\\}")
            val field = Regex("\"(\\w+)\":(\"[^\"]*\"|[-0-9.]+)")
            val out = obj.findAll(text).map { m ->
                val f = field.findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2].trim('"') }
                val target = if (f["dlat"] != null && f["dlon"] != null) LatLng(f.getValue("dlat").toDouble(), f.getValue("dlon").toDouble()) else null
                RestArea(
                    id = f.getValue("id"),
                    name = f.getValue("n"),
                    type = when (f["t"]) {
                        "S" -> RestAreaType.SERVICE_AREA
                        "D" -> RestAreaType.DROWSY_SHELTER
                        else -> RestAreaType.REST_STOP
                    },
                    position = LatLng(f.getValue("lat").toDouble(), f.getValue("lon").toDouble()),
                    directionLabel = f["dl"],
                    directionTarget = target,
                    onExpressway = f["e"] == "1",
                )
            }.toList()
            return RestAreaCleaner.clean(out)
        }
    }
}
