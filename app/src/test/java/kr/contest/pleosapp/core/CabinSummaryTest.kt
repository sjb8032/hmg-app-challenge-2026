package kr.contest.pleosapp.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** 실내 환경 결과 한 줄: 되지 않은 제어는 빼고 짧게 덧붙인다 (에뮬레이터는 바람 세기를 바꿀 수 없다) */
class CabinSummaryTest {
    private val rest = CabinSummary(
        "휴식 모드",
        listOf(
            "23°C" to CabinPart.TEMPERATURE,
            "약한 바람" to CabinPart.FAN,
            "바깥 공기" to CabinPart.RECIRCULATION,
            "무드등 은은하게" to CabinPart.AMBIENT,
        ),
    )

    @Test
    fun allChanged() {
        assertEquals("휴식 모드: 23°C · 약한 바람 · 바깥 공기 · 무드등 은은하게", rest.text())
    }

    @Test
    fun fanUnavailableIsDroppedAndNotedBriefly() {
        assertEquals(
            "휴식 모드: 23°C · 바깥 공기 · 무드등 은은하게 (바람 세기는 이 차에서 바꿀 수 없어 그대로 뒀어요)",
            rest.text(setOf(CabinPart.FAN)),
        )
        // 받침에 맞는 조사, 여러 개는 순서대로
        assertEquals(
            "휴식 모드: 23°C · 바깥 공기 (바람 세기·무드등은 이 차에서 바꿀 수 없어 그대로 뒀어요)",
            rest.text(setOf(CabinPart.AMBIENT, CabinPart.FAN)),
        )
    }

    @Test
    fun powerFailureIsNotedEvenWithoutItsOwnPart() {
        assertEquals(
            "휴식 모드: 23°C · 바깥 공기 · 무드등 은은하게 (공조 전원·바람 세기는 이 차에서 바꿀 수 없어 그대로 뒀어요)",
            rest.text(setOf(CabinPart.POWER, CabinPart.FAN)),
        )
    }

    @Test
    fun nothingChangedAsksToCheck() {
        assertEquals(CabinSummary.ALL_FAILED, rest.text(CabinPart.entries.toSet()))
    }

    @Test
    fun restoredNotesWhatCouldNotBeRestored() {
        val message = "출발: 실내 환경을 휴식 전 설정으로 되돌렸어요."
        assertEquals(message, CabinSummary.restored(message, emptyList()))
        assertEquals(
            "출발: 실내 환경을 휴식 전 설정으로 되돌렸어요. 온도·무드등은 되돌리지 못했으니 직접 확인해 주세요.",
            CabinSummary.restored(message, listOf(CabinPart.AMBIENT, CabinPart.TEMPERATURE)),
        )
    }

    @Test
    fun topicParticle() {
        assertEquals("바람 세기는", DriveText.topic("바람 세기"))
        assertEquals("무드등은", DriveText.topic("무드등"))
        assertEquals("공조 전원은", DriveText.topic("공조 전원"))
    }
}
