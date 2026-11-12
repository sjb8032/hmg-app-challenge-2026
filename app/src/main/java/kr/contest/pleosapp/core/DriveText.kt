package kr.contest.pleosapp.core

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/** 드라이브 모드의 화면·음성 문구 도우미 */
object DriveText {
    /** 반올림한 분 (1분 미만은 1분) */
    fun minutes(ms: Long): Int = ((abs(ms) + 30 * SECOND_MS) / MINUTE_MS).toInt().coerceAtLeast(1)

    /** 화면용 시각: "오후 7:05" */
    fun clock(ms: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val (h, m) = hourMinute(ms, zone)
        val ampm = if (h < 12) "오전" else "오후"
        return "$ampm ${hour12(h)}:${m.toString().padStart(2, '0')}"
    }

    /** 음성용 시각: "7시", "9시 50분" */
    fun spokenClock(ms: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val (h, m) = hourMinute(ms, zone)
        return if (m == 0) "${hour12(h)}시" else "${hour12(h)}시 ${m}분"
    }

    /** 도착 예상과 목표의 차이: "정각", "3분 일찍", "5분 늦게" */
    fun diff(diffMs: Long): String = when {
        abs(diffMs) < MINUTE_MS -> "정각"
        diffMs < 0 -> "${minutes(diffMs)}분 일찍"
        else -> "${minutes(diffMs)}분 늦게"
    }

    /** 달리는 중 도착 예상 표시: "정각", "3분 여유", "8분 일찍", "약 4분 늦어요" */
    fun fit(status: FitStatus, diffMs: Long): String = when {
        status == FitStatus.LATE -> "약 ${minutes(diffMs)}분 늦어요"
        status == FitStatus.EARLY -> "${minutes(diffMs)}분 일찍"
        diffMs < -MINUTE_MS -> "${minutes(diffMs)}분 여유"
        else -> "정각"
    }

    /** 걸리는 시간: "25분", "1시간 10분" */
    fun duration(ms: Long): String {
        val total = minutes(ms)
        val h = total / 60
        val m = total % 60
        return when {
            h == 0 -> "${m}분"
            m == 0 -> "${h}시간"
            else -> "${h}시간 ${m}분"
        }
    }

    /** 받침에 맞춰 '을/를'을 붙인다: "남산을", "팔각정을", "북악스카이웨이를" */
    fun withObject(word: String): String {
        val last = word.trimEnd().lastOrNull() ?: return word
        if (last !in '가'..'힣') return "$word(을)를"
        val hasFinal = (last.code - '가'.code) % 28 != 0
        return word + if (hasFinal) "을" else "를"
    }

    /** 받침에 맞춰 '로/으로'를 붙인다 (ㄹ 받침은 '로'): "휴게소로", "공영 주차장으로", "서울로" */
    fun toward(word: String): String {
        val last = word.trimEnd().lastOrNull() ?: return word
        if (last !in '가'..'힣') return "$word(으)로"
        val final = (last.code - '가'.code) % 28
        return word + if (final == 0 || final == RIEUL_FINAL) "로" else "으로"
    }

    private const val RIEUL_FINAL = 8

    private fun hourMinute(ms: Long, zone: TimeZone): Pair<Int, Int> {
        val c = Calendar.getInstance(zone).apply { timeInMillis = ms }
        return c.get(Calendar.HOUR_OF_DAY) to c.get(Calendar.MINUTE)
    }

    private fun hour12(h: Int): Int = if (h % 12 == 0) 12 else h % 12
}
