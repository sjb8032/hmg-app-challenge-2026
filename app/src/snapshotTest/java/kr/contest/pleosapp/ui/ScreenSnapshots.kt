package kr.contest.pleosapp.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.TimeZone

/**
 * 화면 캡처 (Paparazzi). 앱 빌드에는 들어가지 않고, CI의 'Screen snapshots' 작업에서만 이 폴더를 테스트에 붙여 돌린다.
 * 결과 PNG는 작업의 아티팩트(screens)로 올라간다. 표본 상태는 [PreviewSamples]에 있다.
 * 미니맵 지도 타일은 그리기 전에 그 자리에서 받는다 (인터넷이 안 되면 도식 지도로 그려진다).
 */
class ScreenSnapshots {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = landscape(1696, 1180), maxPercentDifference = 0.1)

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
        MapTiles.mode = MapTiles.Mode.BLOCKING
        RoadRoutes.mode = MapTiles.Mode.BLOCKING
    }

    @Test
    fun s1_home() = paparazzi.snapshot { PreviewScreen(PreviewSamples.home()) }

    @Test
    fun s2_setup() = paparazzi.snapshot { PreviewScreen(PreviewSamples.setup()) }

    @Test
    fun s3_choose() = paparazzi.snapshot { PreviewScreen(PreviewSamples.choose()) }

    @Test
    fun s4_active() = paparazzi.snapshot { PreviewScreen(PreviewSamples.active()) }

    @Test
    fun s5_watchingLate() = paparazzi.snapshot { PreviewScreen(PreviewSamples.watchingLate()) }

    @Test
    fun s6_arrived() = paparazzi.snapshot { PreviewScreen(PreviewSamples.arrived()) }

    @Test
    fun s6b_longerActive() = paparazzi.snapshot { PreviewScreen(PreviewSamples.longerActive()) }

    @Test
    fun s7_restGuidance() = paparazzi.snapshot { PreviewScreen(PreviewSamples.guidance()) }

    @Test
    fun s8_resting() = paparazzi.snapshot { PreviewScreen(PreviewSamples.resting()) }

    @Test
    fun s9_autoCue() = paparazzi.snapshot { PreviewScreen(PreviewSamples.autoCue()) }

    @Test
    fun s9b_autoWatching() = paparazzi.snapshot { PreviewScreen(PreviewSamples.autoWatching()) }

    @Test
    fun s9c_autoGuidance() = paparazzi.snapshot { PreviewScreen(PreviewSamples.autoGuidance()) }

    @Test
    fun s9d_autoAlarm() = paparazzi.snapshot { PreviewScreen(PreviewSamples.autoAlarm()) }

    // 밤 화면 (차가 야간 모드)

    @Test
    fun n1_home() = paparazzi.snapshot { PreviewScreen(PreviewSamples.home(), dark = true) }

    @Test
    fun n2_active() = paparazzi.snapshot { PreviewScreen(PreviewSamples.active(), dark = true) }

    @Test
    fun n3_restGuidance() = paparazzi.snapshot { PreviewScreen(PreviewSamples.guidance(), dark = true) }
}

/** 작은 가로 화면 (1280×720)에서도 깨지지 않는지 */
class SmallScreenSnapshots {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = landscape(1280, 720), maxPercentDifference = 0.1)

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
        MapTiles.mode = MapTiles.Mode.BLOCKING
        RoadRoutes.mode = MapTiles.Mode.BLOCKING
    }

    @Test
    fun small_active() = paparazzi.snapshot { PreviewScreen(PreviewSamples.active()) }

    @Test
    fun small_choose() = paparazzi.snapshot { PreviewScreen(PreviewSamples.choose()) }

    @Test
    fun small_home() = paparazzi.snapshot { PreviewScreen(PreviewSamples.home()) }

    @Test
    fun small_autoWatching() = paparazzi.snapshot { PreviewScreen(PreviewSamples.autoWatching()) }
}

/** 1px = 1dp인 가로 화면 */
private fun landscape(width: Int, height: Int) = DeviceConfig.PIXEL_5.copy(
    screenWidth = width,
    screenHeight = height,
    xdpi = 160,
    ydpi = 160,
    density = Density.MEDIUM,
    orientation = ScreenOrientation.LANDSCAPE,
    softButtons = false,
)
