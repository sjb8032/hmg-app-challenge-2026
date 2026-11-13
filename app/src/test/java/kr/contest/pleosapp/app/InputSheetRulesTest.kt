package kr.contest.pleosapp.app

import kr.contest.pleosapp.core.DriveMode
import kr.contest.pleosapp.core.DriveStep
import kr.contest.pleosapp.core.Phase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 띄운 입력 창을 언제 닫는지: 쉼터 안내·휴식, 자동 시연, 창을 연 화면 단계를 벗어남 */
class InputSheetRulesTest {
    private fun fits(
        sheet: InputSheet,
        phase: Phase = Phase.DRIVING,
        step: DriveStep = DriveStep.HOME,
        mode: DriveMode? = null,
        demo: Boolean = true,
        auto: Boolean = false,
    ) = sheet.fitsNow(phase, step, mode, demo, auto)

    @Test
    fun destinationSearchOnlyOnTheHomeScreenWhileDriving() {
        val search = InputSheet.FindPlace(PlacePurpose.NAVI_DESTINATION)
        assertTrue(fits(search))
        assertTrue(fits(search, demo = false))
        // 쉼터 안내·휴식이 시작되면 닫는다 (안내 화면과 '레스트 모드' 버튼을 덮지 않고, 쉼터 경로를 덮어쓰지 않게)
        assertFalse(fits(search, phase = Phase.TO_REST_AREA))
        assertFalse(fits(search, phase = Phase.RESTING))
        // 처음 화면을 벗어나면(달리는 중에 고르면 코스 경로를 덮어쓴다) 닫는다
        assertFalse(fits(search, step = DriveStep.SETUP))
        assertFalse(fits(search, step = DriveStep.ACTIVE))
        // 자동 시연이 돌면 닫는다
        assertFalse(fits(search, auto = true))
    }

    @Test
    fun endPlaceAndTimeOnlyOnTheSetupScreen() {
        val end = InputSheet.FindPlace(PlacePurpose.END_PLACE)
        assertTrue(fits(end, step = DriveStep.SETUP, mode = DriveMode.LONGER))
        assertTrue(fits(end, step = DriveStep.SETUP, mode = DriveMode.ON_TIME))
        assertFalse(fits(end, step = DriveStep.CHOOSE, mode = DriveMode.ON_TIME))
        assertFalse(fits(end, step = DriveStep.SETUP, mode = DriveMode.ON_TIME, phase = Phase.TO_REST_AREA))
        // 도착 시각은 온타임 설정에서만
        assertTrue(fits(InputSheet.PickTime, step = DriveStep.SETUP, mode = DriveMode.ON_TIME))
        assertFalse(fits(InputSheet.PickTime, step = DriveStep.SETUP, mode = DriveMode.LONGER))
        assertFalse(fits(InputSheet.PickTime, step = DriveStep.CHOOSE, mode = DriveMode.ON_TIME))
    }

    @Test
    fun demoStartWheneverTheDemoIsOn() {
        // 시연 출발지 고르기는 시연을 처음부터 다시 시작하는 것이라, 쉬는 중이나 달리는 중에도 연다
        val start = InputSheet.FindPlace(PlacePurpose.DEMO_START)
        assertTrue(fits(start, phase = Phase.RESTING, step = DriveStep.ACTIVE))
        assertFalse(fits(start, demo = false))
        assertFalse(fits(start, auto = true))
    }
}
