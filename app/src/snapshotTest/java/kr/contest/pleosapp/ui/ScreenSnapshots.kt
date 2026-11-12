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
 */
class ScreenSnapshots {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = landscape(1696, 1180), maxPercentDifference = 0.1)

    @Before
    fun koreanTime() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
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
}

/** 작은 가로 화면 (1280×720)에서도 깨지지 않는지 */
class SmallScreenSnapshots {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = landscape(1280, 720), maxPercentDifference = 0.1)

    @Before
    fun koreanTime() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
    }

    @Test
    fun small_active() = paparazzi.snapshot { PreviewScreen(PreviewSamples.active()) }

    @Test
    fun small_choose() = paparazzi.snapshot { PreviewScreen(PreviewSamples.choose()) }
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
