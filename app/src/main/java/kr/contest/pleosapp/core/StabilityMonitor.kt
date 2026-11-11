package kr.contest.pleosapp.core

import kotlin.math.max
import kotlin.math.min

enum class StabilityVerdict { MORE_STABLE, SIMILAR, LESS_STABLE, NOT_ENOUGH_DATA }

data class StabilityMetrics(
    /** 비교에 쓸 수 있었던 주행 시간 (분): 일정 속도 이상, 깜빡이 꺼짐 */
    val qualifiedMinutes: Double,
    val steeringCorrections: Int,
    val brakePresses: Int,
    val warnings: Int,
) {
    val steeringCorrectionsPerMin: Double
        get() = if (qualifiedMinutes > 0.0) steeringCorrections / qualifiedMinutes else 0.0
    val brakePressesPer10Min: Double
        get() = if (qualifiedMinutes > 0.0) brakePresses / qualifiedMinutes * 10.0 else 0.0
    val warningsPer10Min: Double
        get() = if (qualifiedMinutes > 0.0) warnings / qualifiedMinutes * 10.0 else warnings * 10.0

    /** 흔들림 지수. 클수록 불안정. 가중치는 시험값이며 에뮬레이터 시나리오로 조정한다. */
    val wobbleIndex: Double
        get() = steeringCorrectionsPerMin + 2.0 * brakePressesPer10Min + 5.0 * warningsPer10Min
}

data class StabilityComparison(
    val before: StabilityMetrics,
    val after: StabilityMetrics,
    val verdict: StabilityVerdict,
)

data class StabilityConfig(
    /** 이 속도 이상에서만 센다 (km/h). 회전·주차 조작을 빼기 위함 */
    val minSpeedKmh: Double = 60.0,
    /** 조향각이 반대 방향으로 이만큼 이상 바뀌면 보정 1회로 센다 (도). 시험값 */
    val reversalThresholdDeg: Double = 1.5,
    val minBeforeMinutes: Double = 5.0,
    val minAfterMinutes: Double = 3.0,
    /** 이 지수 이하면 흔들림이 거의 없는 상태. 시험값 */
    val calmIndex: Double = 2.0,
    val improveRatio: Double = 0.75,
    val worsenRatio: Double = 1.1,
)

/**
 * 휴식 전후 주행 안정성을 비교하기 위해 조향 보정·제동·경고를 기록한다.
 *
 * 졸음이나 회복을 판정하지 않는다. 같은 조건(일정 속도 이상, 깜빡이 꺼짐)에서
 * 휴식 전후의 조작 패턴이 어떻게 달라졌는지만 비교한다.
 */
class StabilityMonitor(var config: StabilityConfig = StabilityConfig()) {
    private val corrections = ArrayList<Long>()
    private val brakePresses = ArrayList<Long>()
    private val warnings = ArrayList<Long>()
    private val segments = ArrayList<LongArray>()

    private var speedKmh: Double? = null
    private var turnSignalOn = false
    private var qualifiedSince: Long? = null

    private var direction = 0
    private var extreme: Double? = null
    private var brakePressed = false

    private fun isQualified(): Boolean =
        (speedKmh ?: 0.0) >= config.minSpeedKmh && !turnSignalOn

    fun onSpeed(now: Long, kmh: Double?) {
        val was = isQualified()
        speedKmh = kmh
        afterContextChange(now, was)
    }

    fun onTurnSignal(now: Long, on: Boolean) {
        val was = isQualified()
        turnSignalOn = on
        afterContextChange(now, was)
    }

    private fun afterContextChange(now: Long, was: Boolean) {
        val isNow = isQualified()
        if (!was && isNow) qualifiedSince = now
        if (was && !isNow) {
            val start = qualifiedSince
            if (start != null && now > start) segments.add(longArrayOf(start, now))
            qualifiedSince = null
        }
        if (!isNow) {
            direction = 0
            extreme = null
        }
    }

    fun onSteeringAngle(now: Long, angleDeg: Double) {
        if (!isQualified()) return
        val ext = extreme
        if (ext == null) {
            extreme = angleDeg
            direction = 0
            return
        }
        val th = config.reversalThresholdDeg
        when (direction) {
            0 -> when {
                angleDeg - ext >= th -> { direction = 1; extreme = angleDeg }
                ext - angleDeg >= th -> { direction = -1; extreme = angleDeg }
            }
            1 -> when {
                angleDeg > ext -> extreme = angleDeg
                ext - angleDeg >= th -> { corrections.add(now); direction = -1; extreme = angleDeg }
            }
            else -> when {
                angleDeg < ext -> extreme = angleDeg
                angleDeg - ext >= th -> { corrections.add(now); direction = 1; extreme = angleDeg }
            }
        }
    }

    fun onBrake(now: Long, pressed: Boolean) {
        if (pressed && !brakePressed && (speedKmh ?: 0.0) >= config.minSpeedKmh) brakePresses.add(now)
        brakePressed = pressed
    }

    fun onWarning(now: Long) {
        warnings.add(now)
    }

    fun metrics(from: Long, to: Long): StabilityMetrics {
        if (to <= from) return StabilityMetrics(0.0, 0, 0, 0)
        var qualifiedMs = 0L
        for (s in segments) qualifiedMs += overlap(s[0], s[1], from, to)
        qualifiedSince?.let { qualifiedMs += overlap(it, to, from, to) }
        val range = from until to
        return StabilityMetrics(
            qualifiedMinutes = qualifiedMs / 60_000.0,
            steeringCorrections = corrections.count { it in range },
            brakePresses = brakePresses.count { it in range },
            warnings = warnings.count { it in range },
        )
    }

    fun compare(beforeFrom: Long, beforeTo: Long, afterFrom: Long, afterTo: Long): StabilityComparison {
        val before = metrics(beforeFrom, beforeTo)
        val after = metrics(afterFrom, afterTo)
        return StabilityComparison(before, after, verdict(before, after))
    }

    private fun verdict(before: StabilityMetrics, after: StabilityMetrics): StabilityVerdict {
        // 휴식 뒤에 경고가 났다면 데이터 양과 상관없이 한 번 더 쉬기를 권한다.
        if (after.warnings > 0) return StabilityVerdict.LESS_STABLE
        if (before.qualifiedMinutes < config.minBeforeMinutes || after.qualifiedMinutes < config.minAfterMinutes) {
            return StabilityVerdict.NOT_ENOUGH_DATA
        }
        val b = before.wobbleIndex
        val a = after.wobbleIndex
        return when {
            a <= config.calmIndex && b <= config.calmIndex -> StabilityVerdict.SIMILAR
            a <= b * config.improveRatio -> StabilityVerdict.MORE_STABLE
            a >= b * config.worsenRatio && a > config.calmIndex -> StabilityVerdict.LESS_STABLE
            else -> StabilityVerdict.SIMILAR
        }
    }

    /** 오래된 기록을 지운다. */
    fun prune(olderThan: Long) {
        corrections.removeAll { it < olderThan }
        brakePresses.removeAll { it < olderThan }
        warnings.removeAll { it < olderThan }
        segments.removeAll { it[1] < olderThan }
    }

    private fun overlap(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Long =
        max(0L, min(aEnd, bEnd) - max(aStart, bStart))
}
