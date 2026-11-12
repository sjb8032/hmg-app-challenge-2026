package kr.contest.pleosapp.core

/**
 * 시연용 가상 시계: 실제 1초에 [scale]초가 흐른다.
 *
 * - [hold]: 멈춘다. 멈춘 동안 흐른 실제 시간은 가상 시계에 더하지 않는다.
 *   시연에서 출발 전(D단으로 서 있을 때)에 멈춰, 코스를 읽고 고르는 사이에 남는 시간이 줄거나 후보가 바뀌지 않게 한다.
 * - [shift]: 앞으로 돌린다 ('10분 건너뛰기'). 멈춰 있어도 돌린 만큼은 흐른다.
 *
 * 실제 시각은 [realNow]에서 받는다 (단조 증가하는 시계, 예: SystemClock.elapsedRealtime). 안드로이드에 의존하지 않는다.
 */
class VirtualClock(start: Long, val scale: Double, private val realNow: () -> Long) {
    private var anchorReal = realNow()
    private var anchorVirtual = start

    var held: Boolean = false
        private set

    fun now(): Long = if (held) anchorVirtual else anchorVirtual + ((realNow() - anchorReal) * scale).toLong()

    fun hold(on: Boolean) {
        if (held == on) return
        anchorVirtual = now()
        anchorReal = realNow()
        held = on
    }

    fun shift(ms: Long) {
        anchorVirtual += ms
    }
}
