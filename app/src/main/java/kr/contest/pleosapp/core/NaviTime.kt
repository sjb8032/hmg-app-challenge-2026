package kr.contest.pleosapp.core

/** 내비가 준 시간 값 해석. SDK 문서에 단위가 없어 거리와 맞춰 보고 판단한다. */
object NaviTime {
    private const val DAY_MS = 24 * HOUR_MS
    private const val MIN_KMH = 3.0
    private const val MAX_KMH = 180.0

    /**
     * 경로 조회(requestTrip)의 eta를 '바로 가면 걸리는 시간(ms)'으로 바꾼다.
     * - 10억 이상이면 도착 시각(epoch 초)으로 본다.
     * - 아니면 초로 보고, 거리와 맞지 않으면(평균 시속 3~180km 밖) 분으로 다시 본다.
     * 어느 쪽도 맞지 않으면 null (앱은 거리로 추정한 값을 쓴다).
     */
    fun tripDurationMs(eta: Int, distanceMeters: Int, nowEpochMs: Long): Long? {
        if (eta <= 0) return null
        if (eta >= 1_000_000_000) {
            val ms = eta * SECOND_MS - nowEpochMs
            return ms.takeIf { it in 1L..DAY_MS }
        }
        val asSeconds = eta * SECOND_MS
        if (distanceMeters <= 0) return asSeconds.takeIf { it <= DAY_MS }
        if (plausible(distanceMeters, asSeconds)) return asSeconds
        val asMinutes = eta * MINUTE_MS
        if (plausible(distanceMeters, asMinutes)) return asMinutes
        return null
    }

    private fun plausible(meters: Int, ms: Long): Boolean {
        if (ms <= 0 || ms > DAY_MS) return false
        val kmh = meters / 1000.0 / (ms / HOUR_MS.toDouble())
        return kmh in MIN_KMH..MAX_KMH
    }
}
