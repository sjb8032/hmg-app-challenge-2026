package kr.contest.pleosapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import kr.contest.pleosapp.app.ConnectionStatus
import kr.contest.pleosapp.app.LogEntry
import kr.contest.pleosapp.app.LogKind
import kr.contest.pleosapp.app.RestMateActions
import kr.contest.pleosapp.app.RestMateController
import kr.contest.pleosapp.app.RouteView
import kr.contest.pleosapp.app.UiState
import kr.contest.pleosapp.core.CheckInAnswer
import kr.contest.pleosapp.core.CourseFlavor
import kr.contest.pleosapp.core.CourseKind
import kr.contest.pleosapp.core.CourseOption
import kr.contest.pleosapp.core.CourseTag
import kr.contest.pleosapp.core.DemoTrips
import kr.contest.pleosapp.core.DriveMode
import kr.contest.pleosapp.core.DriveState
import kr.contest.pleosapp.core.DriveStep
import kr.contest.pleosapp.core.FitStatus
import kr.contest.pleosapp.core.Gear
import kr.contest.pleosapp.core.Geo
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
import kr.contest.pleosapp.core.RestAreaType
import kr.contest.pleosapp.core.RestEnvironment
import kr.contest.pleosapp.core.RestKind
import kr.contest.pleosapp.core.RestMateState
import kr.contest.pleosapp.core.RestSession
import kr.contest.pleosapp.core.SECOND_MS
import kr.contest.pleosapp.core.SavedTrip
import kr.contest.pleosapp.sdk.ConsoleLinkState
import kr.contest.pleosapp.sdk.ConsoleStatus
import kr.contest.pleosapp.sdk.VehicleExtras

/**
 * Android Studio 미리보기와 화면 캡처(스냅숏)에 쓰는 표본 상태. 시연 모드의 두 이야기를 따른다.
 * - 시내: 논현역 → 광화문 약속 장소, 오후 6:32 출발, 7:10 약속 (온타임 모드)
 * - 고속도로: 용인 → 강릉 숙소, 오전 11:40 출발, 오후 3시 체크인 (레스트 모드)
 * 시각은 한국 시간 기준이다.
 */
internal object PreviewSamples {
    /** 2026-11-13 오후 6:32 (한국 시간) */
    const val CITY_START = 1_794_562_320_000L

    /** 2026-11-13 오전 11:40 (한국 시간) */
    const val HIGHWAY_START = 1_794_537_600_000L

    private val nonhyeon = DemoTrips.CITY.start
    private val appointment = DemoTrips.CITY.destination
    private val naksan = Place("낙산공원", LatLng(37.5806, 127.0076), PlaceKind.WAYPOINT)
    private val sowol = Place("남산 소월로", LatLng(37.5535, 126.9800), PlaceKind.WAYPOINT)
    private val sejongnoArea = RestArea("city-sejongno-p", "세종로 공영주차장", RestAreaType.PARKING, LatLng(37.5727, 126.9771))
    private val sejongno = Place(sejongnoArea.name, sejongnoArea.position, PlaceKind.WAYPOINT)
    private val jamwonArea = RestArea("city-jamwon-p", "잠원한강공원 주차장", RestAreaType.PARK, LatLng(37.5208, 127.0115))

    val places = listOf(
        Place("집", LatLng(37.5145, 127.1059), PlaceKind.HOME),
        Place("회사", LatLng(37.5219, 126.9245), PlaceKind.OFFICE),
        Place("아이 학원", LatLng(37.4946, 127.0576), PlaceKind.BOOKMARK),
    )

    private val target = CITY_START + 38 * MINUTE_MS
    private const val DIRECT_MS = 22 * MINUTE_MS

    val options = listOf(
        CourseOption(
            "scenic-naksan", CourseKind.SCENIC, "낙산공원 거쳐 가기", "야경·공원 · 약 13분 더",
            listOf(naksan), 13 * MINUTE_MS, tags = setOf(CourseTag.NIGHT_VIEW, CourseTag.PARK),
        ),
        CourseOption(
            "scenic-sowol", CourseKind.SCENIC, "남산 소월로 거쳐 가기", "야경·산길 · 약 12분 더",
            listOf(sowol), 12 * MINUTE_MS, tags = setOf(CourseTag.NIGHT_VIEW, CourseTag.MOUNTAIN),
        ),
        CourseOption(
            "rest-sejongno", CourseKind.REST_STOP, "세종로 공영주차장에서 기다리기", "목적지 바로 옆에 세워 두고 약 12분 기다려요",
            listOf(sejongno), 14 * MINUTE_MS, stayMs = 12 * MINUTE_MS, restArea = sejongnoArea, restKind = RestKind.WAIT,
        ),
    )

    private val saved = listOf(
        SavedTrip(DriveMode.ON_TIME, places[2], minuteOfDay = 16 * 60 + 50, onTimeStyle = OnTimeStyle.WAIT_NEAR),
        SavedTrip(DriveMode.LONGER, places[0], extraMinutes = 30, mood = Mood.DATE, flavor = CourseFlavor.NIGHT_VIEW),
    )

    private fun base(now: Long) = UiState(
        now = now,
        demoMode = true,
        demoTrip = DemoTrips.CITY,
        connection = ConnectionStatus(RestMateController.CONNECTED, RestMateController.CONNECTED),
        restAreaCount = 1004,
        speechAvailable = false,
        saved = saved,
        origin = nonhyeon,
        env = RestEnvironment(),
    )

    private fun lerp(a: LatLng, b: LatLng, f: Double) = LatLng(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)

    /** a에서 b까지 f만큼 간 길 (지나온 길 표본) */
    private fun trail(a: LatLng, b: LatLng, f: Double, steps: Int = 30): List<LatLng> =
        (0..steps).map { lerp(a, b, f * it / steps) }

    // ---------------------------------------------------------------- 시내 (온타임 모드)

    /** 처음 화면: 모드 고르기 */
    fun home(): UiState = base(CITY_START).copy(
        core = RestMateState(
            now = CITY_START,
            continuousDrivingMs = 38 * MINUTE_MS,
            gear = Gear.DRIVE,
            speedKmh = 0.0,
            position = nonhyeon,
            headingDeg = Geo.bearingDegrees(nonhyeon, appointment.position),
            onExpressway = false,
            nextAhead = RestAreaCandidate(jamwonArea, 1_300.0, 1_800.0, 5.0, surface = true),
        ),
        drive = DriveState(
            now = CITY_START,
            naviDestination = appointment,
            naviRemainingMs = DIRECT_MS,
            places = places,
        ),
        route = RouteView(appointment, emptyList()),
        demoClockHeld = true,
        console = ConsoleStatus(ConsoleLinkState.ON, Gear.DRIVE, 0, browserActive = true),
    )

    /** 설정: 약속 장소와 7:10 */
    fun setup(): UiState = home().copy(
        drive = DriveState(
            now = CITY_START,
            step = DriveStep.SETUP,
            mode = DriveMode.ON_TIME,
            naviDestination = appointment,
            naviRemainingMs = DIRECT_MS,
            places = places,
            destination = appointment,
            targetAt = target,
            directMs = DIRECT_MS,
        ),
    )

    /** 코스 고르기: 번호 붙은 후보 3개 */
    fun choose(): UiState = setup().copy(
        drive = setup().drive.copy(step = DriveStep.CHOOSE, options = options),
    )

    private val activeNow = CITY_START + 9 * MINUTE_MS
    private val car = lerp(nonhyeon, naksan.position, 0.45)

    /** 달리는 중: 낙산공원을 거쳐 7:07 도착 예상 (목표 7:10, 여유 3분) */
    fun active(): UiState = base(activeNow).copy(
        core = RestMateState(
            now = activeNow,
            continuousDrivingMs = 47 * MINUTE_MS,
            gear = Gear.DRIVE,
            speedKmh = 32.0,
            position = car,
            headingDeg = Geo.bearingDegrees(car, naksan.position),
            onExpressway = false,
        ),
        drive = DriveState(
            now = activeNow,
            step = DriveStep.ACTIVE,
            mode = DriveMode.ON_TIME,
            position = car,
            destination = appointment,
            targetAt = target,
            directMs = DIRECT_MS,
            chosen = options[0],
            arrivalEstimate = target - 3 * MINUTE_MS,
            diffMs = -3 * MINUTE_MS,
            status = FitStatus.ON_TIME,
        ),
        demoDriving = true,
        driveStartedAt = CITY_START,
        trail = trail(nonhyeon, car, 1.0),
        route = RouteView(appointment, listOf(naksan)),
        lastMessage = "낙산공원을 거쳐 가요. 7시 10분에 맞춰 도착하도록 안내할게요.",
        log = listOf(
            LogEntry(CITY_START + 30 * SECOND_MS, "낙산공원을 거쳐 가요. 7시 10분에 맞춰 도착하도록 안내할게요.", LogKind.VOICE),
            LogEntry(CITY_START + 30 * SECOND_MS, "내비에 낙산공원 경유 경로를 요청했어요.", LogKind.NAVI),
        ),
        console = ConsoleStatus(ConsoleLinkState.ON, Gear.DRIVE, 32, browserActive = true),
    )

    /** 길이 막혀 늦을 것 같아 지켜보는 중 (1분 30초 이어지면 더 짧은 길로) */
    fun watchingLate(): UiState {
        val now = activeNow + 6 * MINUTE_MS
        val jammedCar = lerp(nonhyeon, naksan.position, 0.62)
        val a = active()
        return a.copy(
            now = now,
            core = a.core.copy(now = now, speedKmh = 8.0, position = jammedCar, continuousDrivingMs = 53 * MINUTE_MS),
            drive = a.drive.copy(
                now = now,
                position = jammedCar,
                arrivalEstimate = target + 4 * MINUTE_MS,
                diffMs = 4 * MINUTE_MS,
                status = FitStatus.LATE,
                lateSince = now - 35 * SECOND_MS,
            ),
            demoJammed = true,
            trail = trail(nonhyeon, jammedCar, 1.0),
            lastMessage = null,
            log = listOf(LogEntry(now - 40 * SECOND_MS, "모의 정체: 앞길이 막혀 도착이 약 6분 늦어져요.", LogKind.DEMO)) + a.log,
            console = ConsoleStatus(ConsoleLinkState.ON, Gear.DRIVE, 8, browserActive = true),
        )
    }

    /** 도착: 7:07 도착, 목표 7:10 */
    fun arrived(): UiState {
        val now = target - 3 * MINUTE_MS
        val a = active()
        val path = trail(nonhyeon, naksan.position, 1.0, 20) + trail(naksan.position, appointment.position, 1.0, 10)
        return a.copy(
            now = now,
            core = a.core.copy(now = now, gear = Gear.PARK, speedKmh = 0.0, position = appointment.position),
            drive = a.drive.copy(
                now = now,
                step = DriveStep.ARRIVED,
                position = appointment.position,
                viaCleared = true,
                arrivedAt = now,
                diffMs = -3 * MINUTE_MS,
                status = FitStatus.ON_TIME,
                message = "딱 맞춰 도착했어요. 3분 여유가 있어요.",
            ),
            demoDriving = false,
            trail = path,
            route = null,
            lastMessage = null,
            log = listOf(
                LogEntry(now, "딱 맞춰 도착했어요. 3분 여유가 있어요.", LogKind.VOICE),
                LogEntry(now, "모의: 광화문 약속 장소에 도착해 P단으로 주차했어요.", LogKind.NAVI),
                LogEntry(now - 17 * MINUTE_MS, "모의: 경유지 낙산공원에 도착했어요.", LogKind.NAVI),
            ) + a.log,
            console = ConsoleStatus(ConsoleLinkState.ON, Gear.PARK, 0, browserActive = true),
        )
    }

    /** 롱웨이 모드: 데이트, 30분 더 달려 응봉산 야경을 거쳐 집으로 */
    fun longerActive(): UiState {
        val now = CITY_START + 12 * MINUTE_MS
        val eungbong = Place("응봉산", LatLng(37.5506, 127.0322), PlaceKind.WAYPOINT)
        val home = places[0]
        val at = lerp(nonhyeon, eungbong.position, 0.6)
        val option = CourseOption(
            "scenic-eungbong", CourseKind.SCENIC, "응봉산 거쳐 가기", "야경 · 약 30분 더",
            listOf(eungbong), 30 * MINUTE_MS, tags = setOf(CourseTag.NIGHT_VIEW),
        )
        val planned = CITY_START + 58 * MINUTE_MS
        return base(now).copy(
            core = RestMateState(
                now = now,
                continuousDrivingMs = 50 * MINUTE_MS,
                gear = Gear.DRIVE,
                speedKmh = 30.0,
                position = at,
                headingDeg = Geo.bearingDegrees(at, eungbong.position),
                onExpressway = false,
            ),
            drive = DriveState(
                now = now,
                step = DriveStep.ACTIVE,
                mode = DriveMode.LONGER,
                position = at,
                destination = home,
                targetAt = planned,
                extraMinutes = 30,
                mood = Mood.DATE,
                flavor = CourseFlavor.NIGHT_VIEW,
                chosen = option,
                arrivalEstimate = planned + 2 * MINUTE_MS,
                diffMs = 2 * MINUTE_MS,
                status = FitStatus.ON_TIME,
            ),
            demoDriving = true,
            driveStartedAt = CITY_START,
            trail = trail(nonhyeon, at, 1.0),
            route = RouteView(home, listOf(eungbong)),
            cabinNote = "분위기: 데이트 · 무드등 35% · 22°C",
            log = listOf(
                LogEntry(CITY_START + MINUTE_MS, "분위기: 데이트 · 무드등 35% · 22°C", LogKind.VEHICLE),
                LogEntry(CITY_START + MINUTE_MS, "내비에 응봉산 경유 경로를 요청했어요.", LogKind.NAVI),
            ),
            console = ConsoleStatus(ConsoleLinkState.ON, Gear.DRIVE, 30, browserActive = true),
        )
    }

    // ---------------------------------------------------------------- 고속도로 (레스트 모드)

    private val highway = DemoTrips.HIGHWAY
    private val munmakArea = RestArea(
        "w223671550", "문막휴게소 (강릉방향)", RestAreaType.SERVICE_AREA, LatLng(37.3352, 127.85817), onExpressway = true,
    )
    private val checkIn = HIGHWAY_START + 3 * HOUR_MS + 20 * MINUTE_MS

    /** 레스트 모드: 졸려서 가장 빨리 닿는 휴게소로 안내 중 */
    fun guidance(): UiState {
        val now = HIGHWAY_START + 70 * MINUTE_MS
        val car = lerp(highway.start, highway.destination.position, 0.36)
        val target = RestAreaCandidate(munmakArea, 9_500.0, 11_400.0, 7.0)
        return base(now).copy(
            demoTrip = highway,
            origin = highway.start,
            core = RestMateState(
                now = now,
                phase = Phase.TO_REST_AREA,
                continuousDrivingMs = 2 * HOUR_MS + 5 * MINUTE_MS,
                gear = Gear.DRIVE,
                speedKmh = 95.0,
                position = car,
                headingDeg = Geo.bearingDegrees(car, munmakArea.position),
                onExpressway = true,
                target = target,
                targetRemainingMeters = 11_400.0,
                alternatives = listOf(
                    RestAreaCandidate(
                        RestArea("n414680791", "횡성휴게소", RestAreaType.SERVICE_AREA, LatLng(37.46308, 128.13487), onExpressway = true),
                        30_000.0, 36_000.0, 23.0,
                    ),
                ),
            ),
            drive = DriveState(
                now = now,
                step = DriveStep.ACTIVE,
                mode = DriveMode.ON_TIME,
                position = car,
                destination = highway.destination,
                targetAt = checkIn,
                arrivalEstimate = checkIn - 40 * MINUTE_MS,
                status = FitStatus.EARLY,
                paused = true,
            ),
            demoDriving = true,
            driveStartedAt = HIGHWAY_START,
            trail = trail(highway.start, car, 1.0),
            route = RouteView(highway.destination, listOf(Place(munmakArea.name, munmakArea.position, PlaceKind.WAYPOINT))),
            lastMessage = "졸릴 땐 쉬어 가요. 가장 빨리 닿는 문막휴게소로 안내할게요.",
            log = listOf(
                LogEntry(now, "졸릴 땐 쉬어 가요. 가장 빨리 닿는 문막휴게소로 안내할게요.", LogKind.VOICE),
                LogEntry(now, "내비에 문막휴게소 (강릉방향) 경유 경로를 요청했어요.", LogKind.NAVI),
            ),
            console = ConsoleStatus(ConsoleLinkState.ON, Gear.DRIVE, 95, browserActive = true),
        )
    }

    /** 레스트 모드: 휴게소에서 눈 붙이는 중. 알람을 오후 3시 체크인에 맞췄다 */
    fun resting(): UiState {
        val started = HIGHWAY_START + 90 * MINUTE_MS
        val now = started + 8 * MINUTE_MS
        val alarm = started + 20 * MINUTE_MS
        val call = alarm + 12 * MINUTE_MS
        val g = guidance()
        return g.copy(
            now = now,
            core = RestMateState(
                now = now,
                phase = Phase.RESTING,
                continuousDrivingMs = 0L,
                gear = Gear.PARK,
                speedKmh = 0.0,
                position = munmakArea.position,
                onExpressway = true,
                rest = RestSession(
                    startedAt = started,
                    plannedMinutes = 20,
                    alarmAt = alarm,
                    area = munmakArea,
                    kind = RestKind.NAP,
                    targetAt = checkIn,
                    strictTarget = true,
                    departAt = call,
                    fitsTarget = true,
                    callAt = call,
                    fatigue = true,
                    remainingRouteMs = checkIn - call - 3 * MINUTE_MS,
                ),
            ),
            drive = g.drive.copy(now = now, position = munmakArea.position),
            demoDriving = false,
            trail = trail(highway.start, munmakArea.position, 1.0),
            route = RouteView(highway.destination, emptyList()),
            lastMessage = null,
            extras = VehicleExtras(windowsAllClosed = true, recirculationOn = true, fuelPercent = 42, rangeKm = 310.0),
            cabinNote = "휴식 환경: 23°C · 무드등 은은하게 · 바깥 공기",
            console = ConsoleStatus(ConsoleLinkState.ON, Gear.PARK, 0, browserActive = true),
        )
    }
}

/** 미리보기·캡처용: 아무것도 하지 않는 동작 */
internal object PreviewActions : RestMateActions {
    override fun pressDrowsy() = Unit
    override fun selectRestArea(candidate: RestAreaCandidate) = Unit
    override fun cancelGuidance() = Unit
    override fun answerCheckIn(answer: CheckInAnswer) = Unit
    override fun acceptPrompt() = Unit
    override fun dismissPrompt() = Unit
    override fun startRest() = Unit
    override fun setRestMinutes(minutes: Int) = Unit
    override fun extendRest(minutes: Int) = Unit
    override fun fitNapToArrival() = Unit
    override fun stopAlarm() = Unit
    override fun wakeUp() = Unit
    override fun departNow() = Unit
    override fun setRestEnvironment(env: RestEnvironment) = Unit
    override fun restHere(kind: RestKind) = Unit
    override fun setRestKind(kind: RestKind) = Unit
    override fun openMode(mode: DriveMode) = Unit
    override fun driveBack() = Unit
    override fun setDriveDestination(place: Place) = Unit
    override fun shiftTarget(minutes: Int) = Unit
    override fun setTarget(at: Long) = Unit
    override fun setExtraMinutes(minutes: Int) = Unit
    override fun setMood(mood: Mood) = Unit
    override fun setOnTimeStyle(style: OnTimeStyle) = Unit
    override fun setFlavor(flavor: CourseFlavor) = Unit
    override fun planCourses() = Unit
    override fun chooseCourse(option: CourseOption) = Unit
    override fun goDirect() = Unit
    override fun extendCourse() = Unit
    override fun addDriveTime(minutes: Int) = Unit
    override fun finishDrive() = Unit
    override fun saveTrip() = Unit
    override fun applySavedTrip(trip: SavedTrip) = Unit
    override fun removeSavedTrip(trip: SavedTrip) = Unit
    override fun setDemoMode(on: Boolean) = Unit
    override fun selectDemoTrip(id: String) = Unit
    override fun demoDrive() = Unit
    override fun demoPark() = Unit
    override fun demoTraffic() = Unit
    override fun demoSkip(minutes: Int) = Unit
    override fun demoAddDrivingTime() = Unit
    override fun demoAttentionWarning() = Unit
    override fun demoSkipRest() = Unit
    override fun demoRestart() = Unit
}

/** 미리보기 한 장 */
@Composable
internal fun PreviewScreen(ui: UiState) {
    RestMateTheme {
        RestMateScreen(ui, PreviewActions)
    }
}

// 미리보기 크기: Pleos Connect 에뮬레이터 화면에 앱이 차지하는 크기에 가깝게 (가로형)

@Preview(name = "1 처음", widthDp = 1696, heightDp = 1180)
@Composable
private fun HomePreview() = PreviewScreen(PreviewSamples.home())

@Preview(name = "2 코스 고르기", widthDp = 1696, heightDp = 1180)
@Composable
private fun ChoosePreview() = PreviewScreen(PreviewSamples.choose())

@Preview(name = "3 달리는 중", widthDp = 1696, heightDp = 1180)
@Composable
private fun ActivePreview() = PreviewScreen(PreviewSamples.active())

@Preview(name = "4 늦음 지켜보는 중", widthDp = 1696, heightDp = 1180)
@Composable
private fun WatchingPreview() = PreviewScreen(PreviewSamples.watchingLate())

@Preview(name = "5 쉼터 안내", widthDp = 1696, heightDp = 1180)
@Composable
private fun GuidancePreview() = PreviewScreen(PreviewSamples.guidance())

@Preview(name = "6 휴식", widthDp = 1696, heightDp = 1180)
@Composable
private fun RestingPreview() = PreviewScreen(PreviewSamples.resting())

@Preview(name = "작은 화면", widthDp = 1280, heightDp = 720)
@Composable
private fun SmallActivePreview() = PreviewScreen(PreviewSamples.active())
