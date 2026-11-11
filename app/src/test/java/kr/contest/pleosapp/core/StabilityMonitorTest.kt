package kr.contest.pleosapp.core

import org.junit.Assert.assertEquals
import org.junit.Test

class StabilityMonitorTest {
    private val start = 1_000_000_000L

    /** 1초마다 조향각을 넣는다. amplitude 도 크기로 좌우를 번갈아 흔든다. */
    private fun drive(monitor: StabilityMonitor, from: Long, minutes: Int, amplitude: Double) {
        val samples = minutes * 60
        for (i in 0 until samples) {
            val sign = if (i % 2 == 0) 1.0 else -1.0
            monitor.onSteeringAngle(from + i * SECOND_MS, sign * amplitude)
        }
    }

    @Test
    fun countsSteeringReversalsOnlyWhenQualified() {
        val monitor = StabilityMonitor()
        // 속도가 낮으면 세지 않는다.
        monitor.onSpeed(start, 30.0)
        drive(monitor, start, 1, 3.0)
        monitor.onSpeed(start + MINUTE_MS, 100.0)
        drive(monitor, start + MINUTE_MS, 1, 3.0)
        monitor.onSpeed(start + 2 * MINUTE_MS, 0.0)

        val m = monitor.metrics(start, start + 2 * MINUTE_MS)
        assertEquals(1.0, m.qualifiedMinutes, 0.01)
        // 60개 샘플이 좌우로 번갈아: 첫 방향 확정 뒤 58번 뒤집힘
        assertEquals(58, m.steeringCorrections)
    }

    @Test
    fun smallMovementsAreNotCorrections() {
        val monitor = StabilityMonitor()
        monitor.onSpeed(start, 100.0)
        drive(monitor, start, 2, 0.5)
        assertEquals(0, monitor.metrics(start, start + 2 * MINUTE_MS).steeringCorrections)
    }

    @Test
    fun turnSignalPausesCounting() {
        val monitor = StabilityMonitor()
        monitor.onSpeed(start, 100.0)
        monitor.onTurnSignal(start, true)
        drive(monitor, start, 1, 3.0)
        val m = monitor.metrics(start, start + MINUTE_MS)
        assertEquals(0, m.steeringCorrections)
        assertEquals(0.0, m.qualifiedMinutes, 0.001)
    }

    @Test
    fun calmerAfterRestIsMoreStable() {
        val monitor = StabilityMonitor()
        monitor.onSpeed(start, 100.0)
        drive(monitor, start, 10, 3.0)
        val restAt = start + 10 * MINUTE_MS
        monitor.onSpeed(restAt, 0.0)
        val departAt = restAt + 20 * MINUTE_MS
        monitor.onSpeed(departAt, 100.0)
        drive(monitor, departAt, 5, 0.5)

        val cmp = monitor.compare(start, restAt, departAt, departAt + 5 * MINUTE_MS)
        assertEquals(StabilityVerdict.MORE_STABLE, cmp.verdict)
    }

    @Test
    fun wobblierAfterRestIsLessStable() {
        val monitor = StabilityMonitor()
        monitor.onSpeed(start, 100.0)
        drive(monitor, start, 10, 0.5)
        val restAt = start + 10 * MINUTE_MS
        monitor.onSpeed(restAt, 0.0)
        val departAt = restAt + 20 * MINUTE_MS
        monitor.onSpeed(departAt, 100.0)
        drive(monitor, departAt, 5, 3.0)

        val cmp = monitor.compare(start, restAt, departAt, departAt + 5 * MINUTE_MS)
        assertEquals(StabilityVerdict.LESS_STABLE, cmp.verdict)
    }

    @Test
    fun bothCalmIsSimilar() {
        val monitor = StabilityMonitor()
        monitor.onSpeed(start, 100.0)
        drive(monitor, start, 10, 0.5)
        val restAt = start + 10 * MINUTE_MS
        monitor.onSpeed(restAt, 0.0)
        val departAt = restAt + 20 * MINUTE_MS
        monitor.onSpeed(departAt, 100.0)
        drive(monitor, departAt, 5, 0.5)

        val cmp = monitor.compare(start, restAt, departAt, departAt + 5 * MINUTE_MS)
        assertEquals(StabilityVerdict.SIMILAR, cmp.verdict)
    }

    @Test
    fun warningAfterRestIsLessStableEvenWithLittleData() {
        val monitor = StabilityMonitor()
        monitor.onSpeed(start, 100.0)
        monitor.onWarning(start + 30 * SECOND_MS)
        val cmp = monitor.compare(start - 20 * MINUTE_MS, start - 10 * MINUTE_MS, start, start + MINUTE_MS)
        assertEquals(StabilityVerdict.LESS_STABLE, cmp.verdict)
    }

    @Test
    fun shortDrivingIsNotEnoughData() {
        val monitor = StabilityMonitor()
        monitor.onSpeed(start, 100.0)
        drive(monitor, start, 2, 3.0)
        val restAt = start + 2 * MINUTE_MS
        monitor.onSpeed(restAt, 0.0)
        val departAt = restAt + 20 * MINUTE_MS
        monitor.onSpeed(departAt, 100.0)
        drive(monitor, departAt, 5, 0.5)

        val cmp = monitor.compare(start, restAt, departAt, departAt + 5 * MINUTE_MS)
        assertEquals(StabilityVerdict.NOT_ENOUGH_DATA, cmp.verdict)
    }

    @Test
    fun brakePressesAreCountedAtSpeedOnly() {
        val monitor = StabilityMonitor()
        monitor.onSpeed(start, 100.0)
        monitor.onBrake(start + 1_000, true)
        monitor.onBrake(start + 2_000, false)
        monitor.onBrake(start + 3_000, true)
        monitor.onBrake(start + 4_000, false)
        monitor.onSpeed(start + 5_000, 20.0)
        monitor.onBrake(start + 6_000, true)
        assertEquals(2, monitor.metrics(start, start + MINUTE_MS).brakePresses)
    }
}
