package kr.contest.pleosapp.core

/**
 * 시연 모드의 모의 차량(기어·속도)을 Pleos Connect 에뮬레이터의 Virtual Console에 맞추는 계획.
 * 그러면 계기판과 콘솔의 3D 주행 화면이 앱의 모의 주행과 같이 움직인다. 안드로이드·네트워크에 의존하지 않는다
 * (실제로 보내는 것은 [kr.contest.pleosapp.sdk.VirtualConsoleLink]).
 *
 * 콘솔 화면(브라우저)이 열려 있으면 콘솔이 가속·브레이크 페달로 속도를 직접 계산하고, 달리는 동안에는 밖에서 보낸 속도를
 * 받지 않는다. 그래서 출발할 때 속도와 그 속도를 그대로 유지하는 가속 페달 값을 함께 보내 정속으로 달리게 하고,
 * 달리는 중 속도 변화(정체)는 콘솔 화면이 없을 때만 보낸다. 세울 때는 P단을 넣어 콘솔 화면도 멈추게 하고,
 * D단으로 멈춰 설 때는 브레이크를 밟는다.
 */
object ConsoleSync {
    /**
     * 콘솔 주행 모델: 가속 = 19 × 페달(0~1) km/h/s, 저항 = 0.012 × 속도 km/h/s.
     * 둘이 같아지는 속도 = 페달 1%당 19 / 0.012 / 100 ≈ 15.8 km/h.
     */
    const val KMH_PER_ACCEL_PCT = 19.0 / 0.012 / 100.0

    /** 콘솔에 맞출 상태. 기어는 P·D만 쓴다. */
    data class Target(val gear: Gear, val speedKmh: Int)

    /** D단으로 서 있을 때 밟는 브레이크 페달 (%) */
    const val STAND_BRAKE_PCT = 100.0

    sealed interface Write {
        /** 속도·가속 페달·브레이크 페달 (%) */
        data class Controls(val speedKmh: Int, val acceleratorPct: Double, val brakePct: Double = 0.0) : Write

        data class SetGear(val gear: Gear) : Write
    }

    /** 이 속도를 유지하는 가속 페달 값 (%) */
    fun acceleratorFor(speedKmh: Int): Double = if (speedKmh <= 0) 0.0 else speedKmh / KMH_PER_ACCEL_PCT

    /**
     * 브레이크 페달 값 (%): D단으로 서 있으면 밟는다. 콘솔 화면은 저항만으로는 천천히 멈추므로(약 83초)
     * 픽업 장소 앞처럼 D단으로 멈춰 설 때 3D 주행 화면이 미끄러져 가지 않게 한다.
     */
    fun brakeFor(target: Target): Double = if (target.gear == Gear.DRIVE && target.speedKmh <= 0) STAND_BRAKE_PCT else 0.0

    /**
     * 지금 콘솔에 보내 둔 상태 [sent](처음이면 null)에서 [next]로 바꾸려면 보낼 것.
     * 콘솔 화면이 열려 있으면 [browserActive]. 보낼 것이 없으면 빈 목록.
     */
    fun plan(sent: Target?, next: Target, browserActive: Boolean): List<Write> {
        if (sent == next) return emptyList()
        val controls = Write.Controls(next.speedKmh, acceleratorFor(next.speedKmh), brakeFor(next))
        return when {
            sent == null || sent.gear != next.gear ->
                if (next.gear == Gear.PARK) {
                    // 세울 때는 P단을 먼저 넣어 콘솔 화면이 제동해 멈추게 하고, 그다음 속도를 0으로
                    listOf(Write.SetGear(Gear.PARK), Write.Controls(0, 0.0))
                } else {
                    // 출발·정차 준비: 속도(와 유지 페달)를 먼저 맞추고 기어를 넣는다
                    listOf(controls, Write.SetGear(next.gear))
                }
            // 같은 기어에서 서 있다가 출발하거나 멈추면 콘솔 화면도 같이 움직인다.
            sent.speedKmh == 0 || next.speedKmh == 0 -> listOf(controls)
            // 달리는 중 속도만 바뀜: 콘솔 화면이 속도를 계산하는 중이면 보내지 않는다 (서로 덮어써 흔들리지 않게).
            browserActive -> emptyList()
            else -> listOf(controls)
        }
    }
}
