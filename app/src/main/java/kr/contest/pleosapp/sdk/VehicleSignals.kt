package kr.contest.pleosapp.sdk

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kr.contest.pleosapp.core.Gear

/** 휴식 화면에서 쓰는 차량 실내·연료 정보. 못 읽으면 null */
data class VehicleExtras(
    /** 1·2열 창문이 모두 닫혀 있는지 (창문 위치 0을 닫힘으로 본다) */
    val windowsAllClosed: Boolean? = null,
    val recirculationOn: Boolean? = null,
    val fuelPercent: Int? = null,
    val rangeKm: Double? = null,
)

/**
 * Pleos Vehicle SDK(조회 전용)에서 레스트메이트가 쓰는 신호를 받아 메인 스레드로 넘긴다.
 * 기어·조향각·브레이크·깜빡이는 리스너로 받고, 창문·공조·연료는 휴식 화면용으로 주기적으로 읽는다.
 */
class VehicleSignals(context: Context, private val sink: Sink) {
    interface Sink {
        fun onVehicleConnection(connected: Boolean, message: String?)
        fun onGear(gear: Gear)
        fun onSteeringAngle(degrees: Double)
        fun onBrake(pressed: Boolean)
        fun onTurnSignal(on: Boolean)
        fun onExtras(extras: VehicleExtras)
    }

    private val vehicle = Vehicle(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private val unregisters = ArrayList<() -> Unit>()
    private var connected = false
    private var extras = VehicleExtras()
    private val windowPositions = HashMap<WindowValue, Int>()

    private val extrasPoll = object : Runnable {
        override fun run() {
            readExtras()
            main.postDelayed(this, EXTRAS_INTERVAL_MS)
        }
    }

    fun start() {
        try {
            vehicle.initialize()
            connected = true
            sink.onVehicleConnection(true, null)
        } catch (e: Exception) {
            sink.onVehicleConnection(false, e.message ?: e.javaClass.simpleName)
            return
        }
        registerGear()
        registerSteering()
        registerBrake()
        registerTurnSignal()
        main.post(extrasPoll)
    }

    fun stop() {
        main.removeCallbacks(extrasPoll)
        unregisters.forEach { runCatching(it) }
        unregisters.clear()
        if (connected) runCatching { vehicle.release() }
        connected = false
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    private fun warn(what: String, e: Any?) {
        Log.w(TAG, "$what failed: $e")
    }

    private fun registerGear() {
        safely("gear") {
            val api = vehicle.getGear()
            val listener = listenerProxy<GearSelectionListener> { method, args ->
                if (method == "onSelected") post { sink.onGear(mapGear(args.firstOrNull())) }
                else if (method == "onFailed") warn("gear listener", args.firstOrNull())
            }
            api.registerGearSelection(listener)
            unregisters += { api.unregisterGearSelection(listener) }
            api.getCurrentGearSelectionState({ gear -> post { sink.onGear(mapGear(gear)) } }, { e -> warn("gear", e) })
        }
    }

    private fun registerSteering() {
        safely("steering") {
            val api = vehicle.getSteeringWheel()
            val listener = listenerProxy<SteeringWheelAngleListener> { method, args ->
                val angle = (args.firstOrNull() as? Number)?.toDouble()
                if (method == "onSteeringWheelAngleUpdated" && angle != null) post { sink.onSteeringAngle(angle) }
                else if (method == "onFailed") warn("steering listener", args.firstOrNull())
            }
            api.registerSteeringWheelAngle(listener)
            unregisters += { api.unregisterSteeringWheelAngle(listener) }
        }
    }

    private fun registerBrake() {
        safely("brake") {
            val api = vehicle.getBrake()
            val listener = listenerProxy<BrakePedalStatusListener> { method, args ->
                val pressed = args.firstOrNull() as? Boolean
                if (method == "onBrakePedalStatusUpdated" && pressed != null) post { sink.onBrake(pressed) }
                else if (method == "onFailed") warn("brake listener", args.firstOrNull())
            }
            api.registerBrakePedalStatus(listener)
            unregisters += { api.unregisterBrakePedalStatus(listener) }
        }
    }

    private fun registerTurnSignal() {
        safely("turn signal") {
            val api = vehicle.getTurnSignal()
            val listener = listenerProxy<TurnSignalStateListener> { method, args ->
                val value = args.firstOrNull() as? TurnSignalValue
                if (method == "onTurnSignalStateUpdated" && value != null) post { sink.onTurnSignal(value != TurnSignalValue.OFF) }
                else if (method == "onFailed") warn("turn signal listener", args.firstOrNull())
            }
            api.registerTurnSignalState(listener)
            unregisters += { api.unregisterTurnSignalState(listener) }
        }
    }

    private fun readExtras() {
        if (!connected) return
        for (value in WINDOWS) {
            safely("window $value") {
                vehicle.getWindow().getCurrentWindowPosition(
                    WindowArea(value),
                    { position ->
                        val p = (position as Any?) as? Number
                        post {
                            if (p != null) windowPositions[value] = p.toInt() else windowPositions.remove(value)
                            val known = WINDOWS.mapNotNull { windowPositions[it] }
                            val allClosed = if (known.isEmpty()) null else known.all { it <= 0 }
                            updateExtras(extras.copy(windowsAllClosed = allClosed))
                        }
                    },
                    { e -> warn("window", e) },
                )
            }
        }
        safely("recirculation") {
            vehicle.getHVAC().getHvacRecirculationState(
                SeatArea(SeatValue.ROW_1_LEFT),
                { on -> post { updateExtras(extras.copy(recirculationOn = (on as Any?) as? Boolean)) } },
                { e -> warn("recirculation", e) },
            )
        }
        safely("fuel") {
            vehicle.getFuel().getFuelPercent(
                { percent -> post { updateExtras(extras.copy(fuelPercent = ((percent as Any?) as? Number)?.toInt())) } },
                { e -> warn("fuel", e) },
            )
        }
        safely("range") {
            vehicle.getOdometer().getRangeRemainingDistance(
                { km -> post { updateExtras(extras.copy(rangeKm = ((km as Any?) as? Number)?.toDouble())) } },
                { e -> warn("range", e) },
            )
        }
    }

    private fun updateExtras(next: VehicleExtras) {
        if (next == extras) return
        extras = next
        sink.onExtras(next)
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            warn(what, e)
        }
    }

    companion object {
        private const val TAG = "RestMateVehicle"
        private const val EXTRAS_INTERVAL_MS = 20_000L
        private val WINDOWS = listOf(WindowValue.ROW_1_LEFT, WindowValue.ROW_1_RIGHT, WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT)

        fun mapGear(value: Any?): Gear = when (value as? VehicleGear) {
            VehicleGear.GEAR_PARK -> Gear.PARK
            VehicleGear.GEAR_REVERSE -> Gear.REVERSE
            VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
            null, VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
            else -> Gear.DRIVE
        }
    }
}
