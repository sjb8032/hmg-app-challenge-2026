package kr.contest.pleosapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import kr.contest.pleosapp.app.AutoChapter
import kr.contest.pleosapp.app.AutoCue
import kr.contest.pleosapp.app.AutoDemoScript
import kr.contest.pleosapp.app.AutoStatus
import kr.contest.pleosapp.app.ConnectionStatus
import kr.contest.pleosapp.app.DriveLeg
import kr.contest.pleosapp.app.LogEntry
import kr.contest.pleosapp.app.LogKind
import kr.contest.pleosapp.app.InputSheet
import kr.contest.pleosapp.app.PlacePurpose
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
import kr.contest.pleosapp.core.DriveConfig
import kr.contest.pleosapp.core.DriveEngine
import kr.contest.pleosapp.core.DriveMode
import kr.contest.pleosapp.core.DriveState
import kr.contest.pleosapp.core.DriveStep
import kr.contest.pleosapp.core.DriveText
import kr.contest.pleosapp.core.FitStatus
import kr.contest.pleosapp.core.Gear
import kr.contest.pleosapp.core.Geo
import kr.contest.pleosapp.core.HOUR_MS
import kr.contest.pleosapp.core.LatLng
import kr.contest.pleosapp.core.MINUTE_MS
import kr.contest.pleosapp.core.Mood
import kr.contest.pleosapp.core.OnTimeStyle
import kr.contest.pleosapp.core.Phase
import kr.contest.pleosapp.core.PickupNotice
import kr.contest.pleosapp.core.PickupNoticeKind
import kr.contest.pleosapp.core.PickupText
import kr.contest.pleosapp.core.Place
import kr.contest.pleosapp.core.PlaceSource
import kr.contest.pleosapp.core.PlaceHit
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
import kr.contest.pleosapp.core.TimeFitPlanner
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
    /** 전체 자동 시연 대본 (캡처의 '몇 번째 장면 / 전체'가 대본과 맞게) */
    private val autoScenes by lazy { AutoDemoScript.scenes(null) }
    private val autoSceneCount: Int get() = autoScenes.size

    private fun autoSceneNo(title: String): Int = autoScenes.indexOfFirst { it.title == title } + 1

    /** 2026-11-13 오후 6:32 (한국 시간) */
    const val CITY_START = 1_794_562_320_000L

    /** 2026-11-13 오전 11:40 (한국 시간) */
    const val HIGHWAY_START = 1_794_537_600_000L

    private val nonhyeon = DemoTrips.CITY.start
    private val appointment = DemoTrips.CITY.destination!!
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
        SavedTrip(DriveMode.ON_TIME, places[2], minuteOfDay = 21 * 60 + 50, onTimeStyle = OnTimeStyle.WAIT_NEAR),
        SavedTrip(DriveMode.LONGER, places[0], extraMinutes = 30, mood = Mood.DATE, flavor = CourseFlavor.NIGHT_VIEW),
    )

    private fun base(now: Long) = UiState(
        now = now,
        demoMode = true,
        demoTrip = DemoTrips.CITY,
        connection = ConnectionStatus(RestMateController.CONNECTED, RestMateController.CONNECTED),
        restAreaCount = 1005,
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

    // ---------------------------------------------------------------- 직접 해 보기: 목적지 검색·지도에서 고르기·도착 시각

    private const val SEARCH_SERVICE = "OpenStreetMap Nominatim (© OpenStreetMap contributors)"

    /** '롯데월드타워'로 찾은 곳 (화면 표본, 좌표는 대략) */
    private val lotteHits = listOf(
        PlaceHit("롯데월드타워", "서울특별시 송파구 올림픽로 300", LatLng(37.5126, 127.1025), PlaceSource.OSM, "관광지"),
        PlaceHit("롯데월드타워 주차장", "서울특별시 송파구 올림픽로 300", LatLng(37.5131, 127.1016), PlaceSource.OSM, "주차장"),
        PlaceHit("잠실역", "서울특별시 송파구 올림픽로", LatLng(37.5133, 127.1001), PlaceSource.OSM, "역"),
        PlaceHit("롯데월드", "서울특별시 송파구 올림픽로 240", LatLng(37.5111, 127.0981), PlaceSource.OSM, "관광지"),
    )

    /** 처음 화면에서 '목적지 검색': 검색 결과 (거리와 함께) */
    fun placeSearch(): UiState = home().copy(
        sheet = InputSheet.FindPlace(
            purpose = PlacePurpose.NAVI_DESTINATION,
            query = "롯데월드타워",
            results = lotteHits,
            mapCenter = nonhyeon,
            service = SEARCH_SERVICE,
        ),
    )

    /** 끝낼 곳 '지도에서 고르기': 지도를 끌어 가운데 핀을 맞춘다 */
    fun placeMap(): UiState = setup().copy(
        sheet = InputSheet.FindPlace(
            purpose = PlacePurpose.END_PLACE,
            onMap = true,
            mapCenter = LatLng(37.5700, 126.9830),
            service = SEARCH_SERVICE,
        ),
    )

    /** 도착 목표 시각 고르기: 오늘·오후 7시 10분 */
    fun timePicker(): UiState = setup().copy(sheet = InputSheet.PickTime)

    /** 시연 '직접 해 보기'의 출발지 고르기: 검색어 없이 누르는 '바로 찾기'와 지금 차가 있는 곳·즐겨찾기 */
    fun startSearch(): UiState = home().copy(
        sheet = InputSheet.FindPlace(
            purpose = PlacePurpose.DEMO_START,
            suggestions = listOf(PlaceHit(RestMateController.HERE_NAME, "가상 시계만 지금 시각으로", nonhyeon, PlaceSource.APP)) +
                places.map { PlaceHit.of(it) },
            mapCenter = nonhyeon,
            service = SEARCH_SERVICE,
        ),
    )

    private val seoulStation = LatLng(37.5547, 126.9707)

    /** 직접 해 보기: 서울역에서 출발, 목적지는 아직 없음 (가상 시계는 지금 시각부터) */
    fun freeHome(): UiState = base(CITY_START).copy(
        demoTrip = DemoTrips.free(seoulStation, "서울역"),
        core = RestMateState(
            now = CITY_START,
            gear = Gear.DRIVE,
            speedKmh = 0.0,
            position = seoulStation,
            headingDeg = 0.0,
            onExpressway = false,
        ),
        drive = DriveState(now = CITY_START, places = places),
        origin = seoulStation,
        route = null,
        demoClockHeld = true,
        pickupContact = "동승자",
        log = listOf(
            LogEntry(CITY_START, "시연: 서울역에서 출발해요. 가상 시계는 지금 시각부터 흘러요. 목적지를 검색해 정해 주세요.", LogKind.DEMO),
        ),
        console = ConsoleStatus(ConsoleLinkState.ON, Gear.DRIVE, 0, browserActive = true),
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
        leg = DriveLeg(nonhyeon, naksan.position, 0.45f),
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
            leg = DriveLeg(nonhyeon, naksan.position, 0.62f),
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
                viaReached = true,
                arrivedAt = now,
                diffMs = -3 * MINUTE_MS,
                status = FitStatus.ON_TIME,
                message = "딱 맞춰 도착했어요. 3분 여유가 있어요.",
            ),
            demoDriving = false,
            trail = path,
            route = null,
            leg = null,
            legs = listOf(DriveLeg(nonhyeon, naksan.position, 1f), DriveLeg(naksan.position, appointment.position, 1f)),
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
            leg = DriveLeg(nonhyeon, eungbong.position, 0.6f),
            cabinNote = "데이트 분위기: 무드등 35% · 22°C · 약한 바람",
            log = listOf(
                LogEntry(CITY_START + MINUTE_MS, "데이트 분위기: 무드등 35% · 22°C · 약한 바람", LogKind.VEHICLE),
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
        val car = lerp(highway.start, highway.destination!!.position, 0.36)
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
            route = RouteView(highway.destination!!, listOf(Place(munmakArea.name, munmakArea.position, PlaceKind.WAYPOINT))),
            // 강릉 숙소 쪽으로 달리다(36%) 레스트 모드를 눌러 휴게소로 꺾은 참
            legs = listOf(DriveLeg(highway.start, highway.destination!!.position, 0.36f)),
            leg = DriveLeg(car, munmakArea.position, 0f),
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
            route = RouteView(highway.destination!!, emptyList()),
            lastMessage = null,
            extras = VehicleExtras(windowsAllClosed = true, recirculationOn = false, fuelPercent = 42, rangeKm = 310.0),
            cabinNote = "휴식 모드: 23°C · 약한 바람 · 바깥 공기 · 무드등 은은하게",
            console = ConsoleStatus(ConsoleLinkState.ON, Gear.PARK, 0, browserActive = true),
        )
    }

    // ---------------------------------------------------------------- 학원 픽업 알림 (온타임 모드, 시연 '학원 픽업')

    /** 2026-11-13 오후 9:32 (한국 시간): 학원 픽업 시연은 밤 9시 30분에 시작한다 */
    const val PICKUP_START = CITY_START + 3 * HOUR_MS

    /** 내비에 걸린 학원 (시연 '학원 픽업'의 목적지) */
    private val academy = DemoTrips.PICKUP.destination!!

    /** 픽업 목표 오후 9:50 (바로 가면 9:41쯤 닿는다) */
    private val pickupTarget = PICKUP_START + 18 * MINUTE_MS
    private const val ACADEMY_DIRECT_MS = 9 * MINUTE_MS

    /** 학원 픽업 표본의 문자 받는 사람 (아이에게 보내는 말투) */
    private const val KID = "아이"

    /** 학원 근처에 들를 만한 경치 지점이 없어 계산으로 잡은 돌아가는 지점 */
    private val detourPoint = Place("돌아가는 지점", LatLng(37.5085, 127.0505), PlaceKind.WAYPOINT)
    private val detour = CourseOption(
        "detour", CourseKind.DETOUR, "주변으로 돌아서 가기", "근처에 들를 만한 지점이 없어 약 5분 돌아가요",
        listOf(detourPoint), 5 * MINUTE_MS,
    )

    /** '자리 없음'으로 한 바퀴 돌 지점: 학원으로 오던 방향에서 오른쪽 앞 (엔진과 같은 계산) */
    private fun circlePlace(): Place {
        val heading = Geo.bearingDegrees(detourPoint.position, academy.position)
        val speed = DriveConfig().fallbackSpeedKmh / 3.6
        val at = TimeFitPlanner.circlePoint(academy.position, heading, 3 * MINUTE_MS, speed, DriveConfig())
        return Place(DriveEngine.CIRCLE_VIA_NAME, at, PlaceKind.WAYPOINT)
    }

    private fun notice(at: Long, kind: PickupNoticeKind, text: String, position: LatLng?) =
        PickupNotice(at, kind, "아이", text, position)

    /** 설정: 끝낼 곳은 내비에 걸린 '아이 학원', 9:50, 돌면서 맞추기. 자동 시연이 픽업 알림 켜기를 짚는 중 */
    fun pickupSetup(): UiState = base(PICKUP_START).copy(
        demoTrip = DemoTrips.PICKUP,
        core = RestMateState(
            now = PICKUP_START,
            continuousDrivingMs = 12 * MINUTE_MS,
            gear = Gear.DRIVE,
            speedKmh = 0.0,
            position = nonhyeon,
            headingDeg = Geo.bearingDegrees(nonhyeon, academy.position),
            onExpressway = false,
        ),
        drive = DriveState(
            now = PICKUP_START,
            step = DriveStep.SETUP,
            mode = DriveMode.ON_TIME,
            naviDestination = academy,
            naviRemainingMs = ACADEMY_DIRECT_MS,
            places = places,
            destination = academy,
            targetAt = pickupTarget,
            directMs = ACADEMY_DIRECT_MS,
            onTimeStyle = OnTimeStyle.LOOP,
        ),
        route = RouteView(academy, emptyList()),
        demoClockHeld = true,
        console = ConsoleStatus(ConsoleLinkState.ON, Gear.DRIVE, 0, browserActive = true),
        pickupContact = KID,
        auto = AutoStatus(
            chapter = AutoChapter.PICKUP,
            scene = autoSceneNo("픽업 알림 켜기"),
            total = autoSceneCount,
            title = "픽업 알림 켜기",
            text = "도착 소식을 아이에게 문자로 알려요.",
            cue = AutoCue.PICKUP,
            cueLabel = "픽업 알림 켜기",
        ),
    )

    /** 달리는 중: 돌아가는 지점을 지나 학원까지 3분 남아 '곧 도착' 문자를 띄움 */
    fun pickupActive(): UiState {
        val now = PICKUP_START + 13 * MINUTE_MS
        val car = lerp(detourPoint.position, academy.position, 0.55)
        val eta = now + 3 * MINUTE_MS
        val approach = notice(now, PickupNoticeKind.APPROACH, PickupText.approach(KID, academy.name, eta, car), car)
        val a = active()
        return a.copy(
            now = now,
            demoTrip = DemoTrips.PICKUP,
            core = a.core.copy(now = now, continuousDrivingMs = 25 * MINUTE_MS, position = car, headingDeg = Geo.bearingDegrees(car, academy.position)),
            drive = DriveState(
                now = now,
                step = DriveStep.ACTIVE,
                mode = DriveMode.ON_TIME,
                position = car,
                places = places,
                destination = academy,
                targetAt = pickupTarget,
                directMs = ACADEMY_DIRECT_MS,
                chosen = detour,
                viaCleared = true,
                viaReached = true,
                arrivalEstimate = eta,
                diffMs = eta - pickupTarget,
                status = FitStatus.ON_TIME,
                pickupTo = "아이",
                pickupNotices = listOf(approach),
            ),
            pickupContact = KID,
            driveStartedAt = PICKUP_START,
            trail = trail(nonhyeon, detourPoint.position, 1.0, 20) + trail(detourPoint.position, car, 1.0, 10),
            route = RouteView(academy, emptyList()),
            leg = DriveLeg(detourPoint.position, academy.position, 0.55f),
            legs = listOf(DriveLeg(nonhyeon, detourPoint.position, 1f)),
            lastMessage = "아이에게 보낼 도착 문자를 띄웠어요.",
            lastMessageAt = 0L,
            log = listOf(
                LogEntry(now, "아이에게 보낼 도착 문자를 띄웠어요.", LogKind.VOICE),
                LogEntry(now, "문자(시연) → 아이 · 곧 도착", LogKind.MESSAGE),
                LogEntry(PICKUP_START + 7 * MINUTE_MS, "모의: 경유지 돌아가는 지점에 도착했어요.", LogKind.NAVI),
            ),
            auto = AutoStatus(
                chapter = AutoChapter.PICKUP,
                scene = autoSceneNo("도착 3분 전 · 아이에게 문자"),
                total = autoSceneCount,
                title = "도착 3분 전 · 아이에게 문자",
                text = "아이에게 보낼 문자를 띄웠어요. \"${approach.body}\" 지금 차 위치는 지도 링크로 넣어요.",
            ),
        )
    }

    /** 학원 앞에 세울 곳이 없어 '자리 없음' → 한 바퀴 도는 중 (4:52쯤 다시 도착) */
    fun pickupCircling(): UiState {
        val p = pickupActive()
        val pressed = PICKUP_START + 16 * MINUTE_MS + 30 * SECOND_MS
        val now = pressed + MINUTE_MS
        val loop = circlePlace()
        val back = pressed + 3 * MINUTE_MS
        val car = lerp(academy.position, loop.position, 0.7)
        val circle = CourseOption("circle", CourseKind.DETOUR, "한 바퀴 돌고 다시 오기", "세울 곳이 없어 주변을 약 3분 돌아요", listOf(loop), 3 * MINUTE_MS)
        val circling = notice(pressed, PickupNoticeKind.CIRCLING, PickupText.circling(KID, academy.name, back), null)
        return p.copy(
            now = now,
            core = p.core.copy(now = now, position = car, headingDeg = Geo.bearingDegrees(car, loop.position)),
            drive = p.drive.copy(
                now = now,
                position = car,
                chosen = circle,
                viaCleared = false,
                circling = true,
                arrivalEstimate = back,
                diffMs = back - pickupTarget,
                status = FitStatus.LATE,
                message = "세울 곳이 없어 한 바퀴 돌고 ${DriveText.clock(back)}쯤 다시 와요.",
                messageAt = pressed,
                pickupNotices = p.drive.pickupNotices + circling,
            ),
            trail = p.trail + trail(p.drive.position ?: academy.position, academy.position, 1.0, 6) + trail(academy.position, car, 1.0, 6),
            route = RouteView(academy, listOf(loop)),
            leg = DriveLeg(academy.position, loop.position, 0.7f),
            legs = p.legs + DriveLeg(detourPoint.position, academy.position, 1f),
            lastMessage = null,
            log = listOf(
                LogEntry(pressed, "문자(시연) → 아이 · 자리 없음", LogKind.MESSAGE),
                LogEntry(pressed, "한 바퀴 돌고 4시 52분쯤 다시 올게요. 아이에게 보낼 문자도 띄웠어요.", LogKind.VOICE),
                LogEntry(pressed, "내비에 한 바퀴 도는 길 경유 경로를 요청했어요.", LogKind.NAVI),
            ) + p.log,
            auto = null,
        )
    }

    /** 한 바퀴 돌고 다시 와서 학원 바로 앞에 P단: 세운 곳 문자를 다시 띄움 */
    fun pickupArrived(): UiState {
        val c = pickupCircling()
        val arrived = PICKUP_START + 19 * MINUTE_MS + 40 * SECOND_MS
        val now = arrived + 20 * SECOND_MS
        val parked = notice(now, PickupNoticeKind.PARKED, PickupText.parked(KID, academy.name, 0.0, academy.position), academy.position)
        val loop = circlePlace()
        return c.copy(
            now = now,
            core = c.core.copy(now = now, gear = Gear.PARK, speedKmh = 0.0, position = academy.position),
            drive = c.drive.copy(
                now = now,
                step = DriveStep.ARRIVED,
                position = academy.position,
                viaCleared = true,
                viaReached = true,
                circling = false,
                arrivedAt = arrived,
                diffMs = arrived - pickupTarget,
                status = FitStatus.LATE,
                message = "다시 도착했어요.",
                messageAt = arrived,
                pickupNotices = c.drive.pickupNotices + parked,
            ),
            demoDriving = false,
            trail = c.trail + trail(c.drive.position ?: loop.position, loop.position, 1.0, 4) + trail(loop.position, academy.position, 1.0, 8),
            route = null,
            leg = null,
            legs = c.legs + DriveLeg(academy.position, loop.position, 1f) + DriveLeg(loop.position, academy.position, 1f),
            log = listOf(
                LogEntry(now, "아이에게 보낼 세운 곳 문자를 띄웠어요.", LogKind.VOICE),
                LogEntry(now, "문자(시연) → 아이 · 세운 곳", LogKind.MESSAGE),
                LogEntry(arrived, "다시 도착했어요.", LogKind.VOICE),
            ) + c.log,
            console = ConsoleStatus(ConsoleLinkState.ON, Gear.PARK, 0, browserActive = true),
        )
    }

    // ---------------------------------------------------------------- 자동 시연

    /** 자동 시연: 온타임 모드 카드를 짚는 중 (곧 누름) */
    fun autoCue(): UiState = home().copy(
        auto = AutoStatus(
            chapter = AutoChapter.ON_TIME,
            scene = autoSceneNo("온타임 모드 켜기"),
            total = autoSceneCount,
            title = "온타임 모드 켜기",
            text = "약속·픽업 시각에 딱 맞춰 도착하게 길을 짜 주는 모드예요.",
            cue = AutoCue.MODE_ON_TIME,
            cueLabel = "'온타임 모드' 누르기",
        ),
    )

    /** 자동 시연: 길이 막혀 늦음을 지켜보는 장면의 해설 */
    fun autoWatching(): UiState = watchingLate().copy(
        auto = AutoStatus(
            chapter = AutoChapter.ON_TIME,
            scene = autoSceneNo("갑자기 길이 막혔어요"),
            total = autoSceneCount,
            title = "갑자기 길이 막혔어요",
            text = "도착 예상이 목표보다 늦어졌어요. 잠깐 막힌 것일 수도 있어서 바로 길을 바꾸지 않고, " +
                "1분 30초 지켜본 뒤에도 늦으면 지금 자리에서 늦지 않는 코스로 바꿔요.",
            tag = "시연 조작 · 막힘 +6분",
        ),
    )

    /** 자동 시연: 레스트 모드로 쉼터 안내를 시작한 장면의 해설 */
    fun autoGuidance(): UiState = guidance().copy(
        auto = AutoStatus(
            chapter = AutoChapter.REST,
            scene = autoSceneNo("졸릴 때는 레스트 모드 한 번"),
            total = autoSceneCount,
            title = "졸릴 때는 레스트 모드 한 번",
            text = "진행 방향에서 가장 빨리 닿는 문막휴게소(11.4km, 약 7분)로 안내해요. " +
                "가던 목적지는 그대로 두고 쉼터를 경유지로 넣어요.",
        ),
    )

    /** 자동 시연: 알람이 울리는 휴식 화면에서 '알람 끄기'를 짚는 중 */
    fun autoAlarm(): UiState {
        val r = resting()
        val rest = r.core.rest ?: return r
        val now = rest.alarmAt
        return r.copy(
            now = now,
            core = r.core.copy(now = now, rest = rest.copy(alarmRinging = true, wakeStartedAt = now, wakeMs = 10 * MINUTE_MS)),
            auto = AutoStatus(
                chapter = AutoChapter.REST,
                scene = autoSceneNo("깬 뒤 몸 깨우기"),
                total = autoSceneCount,
                title = "깬 뒤 몸 깨우기",
                text = "알람을 꺼요.",
                cue = AutoCue.BOTTOM,
                cueLabel = "'알람 끄기' 누르기",
            ),
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
    override fun setPickup(on: Boolean) = Unit
    override fun circleForSpot() = Unit
    override fun openPlaceSearch(purpose: PlacePurpose) = Unit
    override fun searchPlaces(query: String) = Unit
    override fun choosePlace(hit: PlaceHit) = Unit
    override fun showPlaceMap(on: Boolean) = Unit
    override fun pickPlaceOnMap(at: LatLng) = Unit
    override fun openTimePicker() = Unit
    override fun closeSheet() = Unit
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
    override fun startAutoDemo(chapter: AutoChapter?) = Unit
    override fun stopAutoDemo() = Unit
    override fun nextAutoScene() = Unit
}

/** 미리보기 한 장. [dark]면 차가 야간 모드일 때의 화면 */
@Composable
internal fun PreviewScreen(ui: UiState, dark: Boolean = false) {
    RestMateTheme(dark = dark) {
        RestMateScreen(ui, PreviewActions)
    }
}

// 미리보기 크기: Pleos Connect 에뮬레이터 화면에 앱이 차지하는 크기에 가깝게 (가로형)

@Preview(name = "1 처음", widthDp = 1696, heightDp = 1180)
@Composable
private fun HomePreview() = PreviewScreen(PreviewSamples.home())

@Preview(name = "F1 목적지 검색", widthDp = 1696, heightDp = 1180)
@Composable
private fun PlaceSearchPreview() = PreviewScreen(PreviewSamples.placeSearch())

@Preview(name = "F2 지도에서 고르기", widthDp = 1696, heightDp = 1180)
@Composable
private fun PlaceMapPreview() = PreviewScreen(PreviewSamples.placeMap())

@Preview(name = "F3 도착 시각 고르기", widthDp = 1696, heightDp = 1180)
@Composable
private fun TimePickerPreview() = PreviewScreen(PreviewSamples.timePicker())

@Preview(name = "F4 직접 해 보기", widthDp = 1696, heightDp = 1180)
@Composable
private fun FreeHomePreview() = PreviewScreen(PreviewSamples.freeHome())

@Preview(name = "F5 직접 해 보기 출발지 (작은 화면)", widthDp = 1280, heightDp = 720)
@Composable
private fun StartSearchPreview() = PreviewScreen(PreviewSamples.startSearch())

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

@Preview(name = "7 자동 시연 해설", widthDp = 1696, heightDp = 1180)
@Composable
private fun AutoWatchingPreview() = PreviewScreen(PreviewSamples.autoWatching())

@Preview(name = "8 자동 시연 짚기", widthDp = 1696, heightDp = 1180)
@Composable
private fun AutoCuePreview() = PreviewScreen(PreviewSamples.autoCue())

@Preview(name = "9 밤 화면 · 달리는 중", widthDp = 1696, heightDp = 1180)
@Composable
private fun NightActivePreview() = PreviewScreen(PreviewSamples.active(), dark = true)

@Preview(name = "10 픽업 알림 · 도착 3분 전", widthDp = 1696, heightDp = 1180)
@Composable
private fun PickupActivePreview() = PreviewScreen(PreviewSamples.pickupActive())

@Preview(name = "11 픽업 알림 · 다시 와서 세움", widthDp = 1696, heightDp = 1180)
@Composable
private fun PickupArrivedPreview() = PreviewScreen(PreviewSamples.pickupArrived())

@Preview(name = "작은 화면", widthDp = 1280, heightDp = 720)
@Composable
private fun SmallActivePreview() = PreviewScreen(PreviewSamples.active())
