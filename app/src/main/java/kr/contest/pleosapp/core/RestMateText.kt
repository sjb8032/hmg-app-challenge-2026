package kr.contest.pleosapp.core

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** 화면과 음성 안내에 쓰는 문구. */
object RestMateText {
    private val parenthesis = Regex("\\s*\\([^)]*\\)")

    /** 음성으로 읽을 이름. 괄호 속 방향 표시는 뺀다. */
    fun spokenName(name: String): String = name.replace(parenthesis, "").trim()

    fun duration(ms: Long): String =
        if (ms >= MINUTE_MS) "${ms / MINUTE_MS}분" else "${ms / SECOND_MS}초"

    fun drivingTime(ms: Long): String {
        val h = ms / HOUR_MS
        val m = (ms % HOUR_MS) / MINUTE_MS
        return if (h > 0) "${h}시간 ${m}분" else "${m}분"
    }

    fun distance(meters: Double): String =
        if (meters >= 1_000.0) String.format(Locale.KOREA, "%.1fkm", meters / 1_000.0)
        else "${meters.roundToInt()}m"

    fun etaMinutes(minutes: Double): Int = ceil(minutes).toInt().coerceAtLeast(1)

    fun recoveryTitle(verdict: StabilityVerdict): String = when (verdict) {
        StabilityVerdict.MORE_STABLE -> "휴식 전보다 주행이 안정적이에요"
        StabilityVerdict.SIMILAR -> "휴식 전과 주행이 비슷해요"
        StabilityVerdict.LESS_STABLE -> "아직 주행이 흔들려요"
        StabilityVerdict.NOT_ENOUGH_DATA -> "비교할 주행 기록이 부족해요"
    }

    fun recoveryDetail(verdict: StabilityVerdict, next: RestAreaCandidate?): String = when (verdict) {
        StabilityVerdict.MORE_STABLE -> "다음 휴식은 2시간 뒤쯤 추천해요."
        StabilityVerdict.SIMILAR -> "졸리면 언제든 '졸려요'를 눌러 주세요."
        StabilityVerdict.LESS_STABLE ->
            if (next != null) "${distance(next.roadMeters)} 앞 ${spokenName(next.area.name)}에서 한 번 더 쉬어 가요."
            else "가까운 쉼터에서 한 번 더 쉬어 가요."
        StabilityVerdict.NOT_ENOUGH_DATA -> "고속 주행 기록이 충분히 모이지 않아 비교하지 않았어요."
    }

    fun recoverySpeech(verdict: StabilityVerdict, next: RestAreaCandidate?): String =
        "${recoveryTitle(verdict)}. ${recoveryDetail(verdict, next)}"

    const val NOT_A_DIAGNOSIS = "졸음이나 회복 여부를 판정하지 않아요. 휴식 전후의 조향·제동 패턴만 비교해요."
}
