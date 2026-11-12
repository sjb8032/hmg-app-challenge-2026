package kr.contest.pleosapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedTripTest {
    private val academy = Place("아이\t학원", LatLng(37.4946, 127.0576), PlaceKind.BOOKMARK, poiId = "123", poiSubId = "0")
    private val pickup = SavedTrip(DriveMode.ON_TIME, academy, minuteOfDay = 16 * 60 + 50, onTimeStyle = OnTimeStyle.WAIT_NEAR)
    private val date = SavedTrip(
        DriveMode.LONGER,
        Place("집", LatLng(37.5145, 127.1059), PlaceKind.HOME),
        extraMinutes = 30,
        mood = Mood.DATE,
        flavor = CourseFlavor.NIGHT_VIEW,
    )

    @Test
    fun roundTripsThroughText() {
        val decoded = SavedTrip.decode(pickup.encode())!!
        // 이름 속 탭은 빈칸으로 바꿔 저장한다.
        assertEquals(pickup.copy(place = academy.copy(name = "아이 학원")), decoded)
        assertEquals(listOf(decoded, date), SavedTrip.decodeAll(SavedTrip.encodeAll(listOf(pickup, date))))
    }

    @Test
    fun ignoresBrokenOrUnknownLines() {
        assertTrue(SavedTrip.decodeAll("garbage\nv1\tON_TIME\n\n").isEmpty())
        assertNull(SavedTrip.decode(pickup.encode().replaceFirst("v1", "v9")))
        assertTrue(SavedTrip.decodeAll(null).isEmpty())
    }

    @Test
    fun addingReplacesSameTripAndKeepsFourNewestFirst() {
        var list = emptyList<SavedTrip>()
        for (m in 0 until 5) list = SavedTrip.added(list, pickup.copy(minuteOfDay = 9 * 60 + m * 10))
        assertEquals(SavedTrip.MAX, list.size)
        assertEquals(9 * 60 + 40, list.first().minuteOfDay)
        list = SavedTrip.added(list, pickup.copy(minuteOfDay = 9 * 60 + 20))
        assertEquals(9 * 60 + 20, list.first().minuteOfDay)
        assertEquals(SavedTrip.MAX, list.size)
        assertEquals(1, list.count { it.minuteOfDay == 9 * 60 + 20 })
    }

    @Test
    fun chipTextSaysModeTimeAndStyle() {
        assertEquals("온타임 · 오후 4:50 · 근처에서 기다리기", pickup.detail)
        assertEquals("롱웨이 · +30분 · 데이트", date.detail)
    }
}
