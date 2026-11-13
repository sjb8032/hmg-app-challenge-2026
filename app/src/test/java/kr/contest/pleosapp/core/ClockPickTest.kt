package kr.contest.pleosapp.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** 도착 시각 고르기: 오늘·내일, 오전·오후 12시, 창을 연 채 자정 넘기기, 서머타임 */
class ClockPickTest {
    private val seoul = TimeZone.getTimeZone("Asia/Seoul")
    private lateinit var saved: TimeZone

    @Before
    fun setUp() {
        saved = TimeZone.getDefault()
        TimeZone.setDefault(seoul)
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(saved)
    }

    private fun at(day: Int, hour: Int, minute: Int, zone: TimeZone = seoul): Long = Calendar.getInstance(zone).run {
        clear()
        set(2026, Calendar.NOVEMBER, day, hour, minute, 0)
        timeInMillis
    }

    @Test
    fun splitsAndRebuildsNoonMidnightAndTomorrow() {
        val base = ClockPick.dayStart(at(13, 23, 50))
        assertEquals(at(13, 0, 0), base)
        for (target in listOf(at(14, 0, 0), at(14, 0, 30), at(14, 12, 0), at(13, 23, 55), at(14, 11, 59), at(13, 12, 5))) {
            val pick = ClockPick.of(base, target)
            assertEquals("$pick", target, pick.at(base))
        }
        // 0시는 오전 12시, 12시는 오후 12시
        assertEquals(ClockPick(1, false, 12, 0), ClockPick.of(base, at(14, 0, 0)))
        assertEquals(ClockPick(1, true, 12, 0), ClockPick.of(base, at(14, 12, 0)))
        assertEquals(ClockPick(0, true, 7, 10), ClockPick.of(base, at(13, 19, 10)))
        // 고를 수 있는 날은 오늘·내일뿐: 모레는 내일로
        assertEquals(1, ClockPick.of(base, at(15, 9, 0)).day)
    }

    @Test
    fun keepsTheDayItWasOpenedOnAcrossMidnight() {
        // 23:58에 열고 '내일 오전 12:30'을 고른 뒤 0:01에 눌러도 그날(14일) 0:30이다 (하루 밀리지 않음)
        val base = ClockPick.dayStart(at(13, 23, 58))
        val pick = ClockPick(day = 1, pm = false, hour12 = 12, minute = 30)
        assertEquals(at(14, 0, 30), pick.at(base))
        // 자정을 넘긴 뒤에도 창을 연 날을 기준으로 한다
        assertEquals(base, ClockPick.dayStart(at(13, 0, 0)))
        assertEquals(at(14, 0, 30), ClockPick.of(base, at(14, 0, 30)).at(base))
    }

    @Test
    fun addsCalendarDaysAcrossDaylightSavingChanges() {
        // 서머타임이 끝나는 날(미국 동부 2026-11-01)에도 '내일 오전 8시'는 달력으로 다음 날 8시 (24시간 뒤가 아님)
        val ny = TimeZone.getTimeZone("America/New_York")
        TimeZone.setDefault(ny)
        val base = Calendar.getInstance(ny).run {
            clear()
            set(2026, Calendar.NOVEMBER, 1, 0, 0, 0)
            timeInMillis
        }
        val expected = Calendar.getInstance(ny).run {
            clear()
            set(2026, Calendar.NOVEMBER, 2, 8, 0, 0)
            timeInMillis
        }
        assertEquals(expected, ClockPick(1, false, 8, 0).at(base))
        assertEquals(ClockPick(1, false, 8, 0), ClockPick.of(base, expected))
    }
}
