package kr.contest.pleosapp.core

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/** 드라이브 모드의 화면·음성 문구 도우미 */
object DriveText {
    /** 반올림한 분 (1분 미만은 1분) */
    fun minutes(ms: Long): Int = ((abs(ms) + 30 * SECOND_MS) / MINUTE_MS).toInt().coerceAtLeast(1)

    /**
     * 화면용 시각: "오후 7:05". 분 단위로 반올림한다 ([minutes]와 같은 기준이라 "7:05 도착 · 5분 일찍"처럼 서로 맞는다).
     */
    fun clock(ms: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val (h, m) = hourMinute(ms + 30 * SECOND_MS, zone)
        val ampm = if (h < 12) "오전" else "오후"
        return "$ampm ${hour12(h)}:${m.toString().padStart(2, '0')}"
    }

    /** 지금 시각 표시용 (반올림하지 않음): 시계처럼 분이 바뀔 때 바뀐다 */
    fun clockNow(ms: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val (h, m) = hourMinute(ms, zone)
        val ampm = if (h < 12) "오전" else "오후"
        return "$ampm ${hour12(h)}:${m.toString().padStart(2, '0')}"
    }

    /** 음성용 시각: "7시", "9시 50분" (분 단위 반올림) */
    fun spokenClock(ms: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val (h, m) = hourMinute(ms + 30 * SECOND_MS, zone)
        return if (m == 0) "${hour12(h)}시" else "${hour12(h)}시 ${m}분"
    }

    /** 도착 예상과 목표의 차이: "정각", "3분 일찍", "5분 늦게" */
    fun diff(diffMs: Long): String = when {
        abs(diffMs) < MINUTE_MS -> "정각"
        diffMs < 0 -> "${duration(diffMs)} 일찍"
        else -> "${duration(diffMs)} 늦게"
    }

    /**
     * 달리는 중 도착 예상 표시 (목표 시각 기준): "정각", "4분 여유", "9분 일찍", "약 4분 늦어요".
     * @param spareMs 이만큼 안으로 일찍 닿는 것은 계획한 여유라 '여유'로 쓴다 (도착하면 '딱 맞춰 도착했어요'와 같은 기준)
     */
    fun fit(status: FitStatus, diffMs: Long, spareMs: Long = 0L): String = when {
        status == FitStatus.LATE || diffMs > 30 * SECOND_MS -> "약 ${duration(diffMs)} 늦어요"
        diffMs < -30 * SECOND_MS && -diffMs <= spareMs -> "${duration(diffMs)} 여유"
        diffMs < -30 * SECOND_MS -> "${duration(diffMs)} 일찍"
        else -> "정각"
    }

    /**
     * 화면에 보이는 두 시각(분 반올림)의 차이. "7:06 도착 · 목표 7:20"이면 정확히 -14분이 되어
     * 함께 쓰는 '14분 일찍'이 시각과 1분 어긋나지 않는다.
     */
    fun gapMs(at: Long, target: Long): Long = roundMinute(at) - roundMinute(target)

    private fun roundMinute(ms: Long): Long = Math.floorDiv(ms + 30 * SECOND_MS, MINUTE_MS) * MINUTE_MS

    /** 롱웨이 모드는 마감이 없어 '늦어요' 대신: "예정대로", "약 6분 더", "8분 일찍" */
    fun fitLonger(status: FitStatus, diffMs: Long): String = when {
        status == FitStatus.EARLY -> "${duration(diffMs)} 일찍"
        diffMs >= 2 * MINUTE_MS -> "약 ${duration(diffMs)} 더"
        else -> "예정대로"
    }

    /** 자정부터 분 → "오후 4:50" */
    fun clockOfDay(minuteOfDay: Int): String {
        val h = minuteOfDay / 60
        val m = minuteOfDay % 60
        val ampm = if (h < 12) "오전" else "오후"
        return "$ampm ${hour12(h)}:${m.toString().padStart(2, '0')}"
    }

    /** 자정부터 분 → 칩용 짧은 시각 "4:50" (오전·오후 없이) */
    fun shortClockOfDay(minuteOfDay: Int): String {
        val h = minuteOfDay / 60
        val m = minuteOfDay % 60
        return "${hour12(h)}:${m.toString().padStart(2, '0')}"
    }

    /** 그 시각이 하루 중 몇 분째인지 */
    fun minuteOfDay(ms: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        val (h, m) = hourMinute(ms, zone)
        return h * 60 + m
    }

    fun hourOfDay(ms: Long, zone: TimeZone = TimeZone.getDefault()): Int = hourMinute(ms, zone).first

    /** 몇 월인지 (1~12) */
    fun monthOf(ms: Long, zone: TimeZone = TimeZone.getDefault()): Int =
        Calendar.getInstance(zone).apply { timeInMillis = ms }.get(Calendar.MONTH) + 1

    /** [now]와 같은 날 [minuteOfDay]분의 시각 */
    fun todayAt(now: Long, minuteOfDay: Int, zone: TimeZone = TimeZone.getDefault()): Long {
        val c = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
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

    /** 받침에 맞춰 '을/를'을 붙인다: "남산을", "팔각정을", "북악스카이웨이를", "용인 휴게소 (강릉 방향)를" */
    fun withObject(word: String): String {
        val last = particleBase(word) ?: return word
        if (last !in '가'..'힣') return "$word(을)를"
        val hasFinal = (last.code - '가'.code) % 28 != 0
        return word + if (hasFinal) "을" else "를"
    }

    /** 받침에 맞춰 '로/으로'를 붙인다 (ㄹ 받침은 '로'): "휴게소로", "공영 주차장으로", "서울로" */
    fun toward(word: String): String {
        val last = particleBase(word) ?: return word
        if (last !in '가'..'힣') return "$word(으)로"
        val final = (last.code - '가'.code) % 28
        return word + if (final == 0 || final == RIEUL_FINAL) "로" else "으로"
    }

    /** 조사를 정할 글자: 끝의 괄호 설명은 빼고 본다 ("용인 휴게소 (강릉 방향)" → '소') */
    private fun particleBase(word: String): Char? {
        var w = word.trimEnd()
        while (w.endsWith(")")) {
            val open = w.lastIndexOf('(')
            if (open <= 0) break
            w = w.substring(0, open).trimEnd()
        }
        return w.lastOrNull()
    }

    private const val RIEUL_FINAL = 8

    private fun hourMinute(ms: Long, zone: TimeZone): Pair<Int, Int> {
        val c = Calendar.getInstance(zone).apply { timeInMillis = ms }
        return c.get(Calendar.HOUR_OF_DAY) to c.get(Calendar.MINUTE)
    }

    private fun hour12(h: Int): Int = if (h % 12 == 0) 12 else h % 12
}
