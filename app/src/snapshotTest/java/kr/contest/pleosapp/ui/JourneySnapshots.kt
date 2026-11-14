package kr.contest.pleosapp.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import kr.contest.pleosapp.app.ConnectionStatus
import kr.contest.pleosapp.app.DemoRig
import kr.contest.pleosapp.app.InputSheet
import kr.contest.pleosapp.app.RestMateController
import kr.contest.pleosapp.app.RouteView
import kr.contest.pleosapp.app.UiState
import kr.contest.pleosapp.core.DriveMode
import kr.contest.pleosapp.core.DriveStep
import kr.contest.pleosapp.core.Geo
import kr.contest.pleosapp.core.HOUR_MS
import kr.contest.pleosapp.core.LatLng
import kr.contest.pleosapp.core.Phase
import kr.contest.pleosapp.core.Place
import kr.contest.pleosapp.core.PlaceKind
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.TimeZone

/**
 * 사용자처럼 직접 써 보는 흐름을 차례로 캡처한다. 화면 상태는 표본이 아니라 실제 엔진(시험대 [DemoRig])으로 만든다.
 * CI의 'Screen snapshots' 작업에서 단위 테스트 폴더와 함께 돈다 (DemoRig가 그 폴더에 있다).
 */
class JourneySnapshots {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1696,
            screenHeight = 1180,
            xdpi = 160,
            ydpi = 160,
            density = Density.MEDIUM,
            orientation = ScreenOrientation.LANDSCAPE,
            softButtons = false,
        ),
        maxPercentDifference = 0.1,
    )

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
        MapTiles.mode = MapTiles.Mode.BLOCKING
        RoadRoutes.mode = MapTiles.Mode.BLOCKING
    }

    /** 시험대를 돌리며 컨트롤러가 화면에 넘기는 것(지나온 길, 출발 시각, 마지막 안내)을 같이 모은다 */
    private class Journey(startAt: LatLng, name: String, timeOfDayMs: Long) {
        val rig = DemoRig()
        private val trail = ArrayList<LatLng>()
        private var driveStartedAt: Long? = null

        init {
            rig.startFree(startAt, name, timeOfDayMs)
            advance(1_000)
        }

        fun advance(ms: Long) = rig.advance(ms) {
            val p = rig.navi?.position ?: return@advance
            if (trail.lastOrNull()?.let { Geo.distanceMeters(it, p) < 40.0 } != true) trail += p
            if (driveStartedAt == null && rig.navi?.moving == true) driveStartedAt = rig.now()
        }

        /** [done]이 될 때까지 1초씩 (최대 [maxMs]) */
        fun advanceUntil(maxMs: Long = 600_000, done: () -> Boolean) {
            var left = maxMs
            while (!done() && left > 0) {
                advance(1_000)
                left -= 1_000
            }
        }

        fun ui(sheet: InputSheet? = null): UiState {
            val navi = rig.navi
            return UiState(
                now = rig.now(),
                core = rig.engine.state,
                drive = rig.drive.state,
                demoMode = true,
                demoTrip = rig.trip,
                demoDriving = navi?.moving == true,
                demoJammed = navi?.jammed == true,
                demoClockHeld = rig.clockHeld,
                connection = ConnectionStatus(RestMateController.CONNECTED, RestMateController.CONNECTED),
                restAreaCount = 1005,
                lastMessage = rig.said.lastOrNull(),
                realNow = rig.real,
                lastMessageAt = rig.real,
                trail = trail.toList(),
                origin = rig.trip?.start,
                route = navi?.destination?.let { RouteView(it, navi.via) },
                driveStartedAt = driveStartedAt?.takeIf { rig.drive.state.step == DriveStep.ACTIVE || rig.drive.state.step == DriveStep.ARRIVED },
                pickupContact = rig.trip?.pickupContact ?: RestMateController.DEFAULT_PICKUP_CONTACT,
                sheet = sheet,
            )
        }
    }

    private fun shot(name: String, ui: UiState) = paparazzi.snapshot(name) { PreviewScreen(ui) }

    /** 직접 해 보기: 서울역에서 롯데월드타워까지, 온타임 모드로 오후 시간에 맞춰 */
    @Test
    fun j1_freeOnTime() {
        val j = Journey(SEOUL_STATION, "서울역", 14 * HOUR_MS)
        val rig = j.rig
        shot("1_start", j.ui())
        rig.setNaviDestination(LOTTE_TOWER)
        j.advance(1_000)
        shot("2_destination", j.ui())
        rig.openMode(DriveMode.ON_TIME)
        j.advance(1_000)
        shot("3_setup", j.ui())
        shot("4_timePicker", j.ui(InputSheet.PickTime))
        // 너무 이른 시각을 고르면
        val chosen = rig.drive.state.targetAt
        rig.setTarget(rig.now() + 15 * 60_000L)
        j.advance(1_000)
        shot("5_setupLate", j.ui())
        chosen?.let { rig.setTarget(it) }
        rig.planCourses()
        j.advance(1_000)
        shot("6_choose", j.ui())
        rig.drive.state.options.firstOrNull()?.let { rig.chooseCourse(it) }
        j.advance(1_000)
        shot("7_active", j.ui())
        rig.demoDrive()
        j.advance(40_000)
        shot("8_driving", j.ui())
        j.advanceUntil { rig.drive.state.step == DriveStep.ARRIVED }
        j.advance(2_000)
        shot("9_arrived", j.ui())
    }

    /** 직접 해 보기: 서울역에서 강릉역까지 고속도로, 달리다 졸려서 레스트 모드 */
    @Test
    fun j2_freeHighwayRest() {
        val j = Journey(SEOUL_STATION, "서울역", 14 * HOUR_MS)
        val rig = j.rig
        rig.setNaviDestination(GANGNEUNG_STATION)
        j.advance(1_000)
        shot("1_destination", j.ui())
        rig.demoDrive()
        j.advanceUntil { (rig.navi?.let { Geo.distanceMeters(SEOUL_STATION, it.position) } ?: 0.0) > 40_000.0 }
        shot("2_driving", j.ui())
        rig.pressDrowsy()
        j.advance(2_000)
        shot("3_restGuidance", j.ui())
        j.advanceUntil { rig.engine.state.phase == Phase.RESTING }
        j.advance(3_000)
        shot("4_resting", j.ui())
        rig.demoSkipRest()
        j.advance(3_000)
        shot("5_alarm", j.ui())
    }

    /** 직접 해 보기: 처음 쓰는 사람이 모드부터 누를 때 (내비 목적지 없음) */
    @Test
    fun j3_freeModeFirst() {
        val j = Journey(SEOUL_STATION, "서울역", 14 * HOUR_MS)
        val rig = j.rig
        rig.openMode(DriveMode.ON_TIME)
        j.advance(1_000)
        shot("1_setupNoDestination", j.ui())
        rig.openMode(DriveMode.LONGER)
        j.advance(1_000)
        shot("2_longerNoDestination", j.ui())
    }

    private companion object {
        val SEOUL_STATION = LatLng(37.5547, 126.9707)
        val LOTTE_TOWER = Place("롯데월드타워", LatLng(37.5126, 127.1025), PlaceKind.CURRENT, address = "서울특별시 송파구 올림픽로 300")
        val GANGNEUNG_STATION = Place("강릉역", LatLng(37.7637, 128.8996), PlaceKind.CURRENT, address = "강원특별자치도 강릉시 용지로 176")
    }
}
