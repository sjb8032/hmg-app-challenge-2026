package kr.contest.pleosapp.sdk

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kr.contest.pleosapp.core.Gear
import kr.contest.pleosapp.core.VehicleEvent

/** 경고등이 어떻게 바뀌면 사건으로 볼지 */
private enum class Trigger { TURNS_ON, STARTS_BLINKING }

/** 휴식 화면에서 쓰는 차량 실내·연료 정보. 못 읽으면 null */
data class VehicleExtras(
    /** 1·2열 창문이 모두 닫혀 있는지 (창문 위치 0을 닫힘으로 본다) */
    val windowsAllClosed: Boolean? = null,
    val recirculationOn: Boolean? = null,
    val fuelPercent: Int? = null,
    val rangeKm: Double? = null,
)

/**
 * Pleos Vehicle SDK에서 레스트메이트가 쓰는 신호를 받아 메인 스레드로 넘긴다.
 * - 기어·조향각·브레이크·깜빡이·경고등은 리스너로 받는다.
 * - 창문·공조·연료는 휴식 화면용으로 주기적으로 읽는다.
 * - 휴식 모드의 실내 환경 제어는 [cabin]이 맡는다.
 */
class VehicleSignals(context: Context, private val sink: Sink) {
    interface Sink {
        fun onVehicleConnection(connected: Boolean, message: String?)
        fun onGear(gear: Gear)
        fun onSteeringAngle(degrees: Double)
        fun onBrake(pressed: Boolean)
        fun onTurnSignal(on: Boolean)
        fun onExtras(extras: VehicleExtras)
        /** 경고등에서 읽은 사건 (새로 켜지거나 깜빡이기 시작한 경고만) */
        fun onVehicleEvent(event: VehicleEvent)
        /** 실내 환경 제어 결과 문구 */
        fun onCabinNote(message: String)
    }

    private val vehicle = Vehicle(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private val unregisters = ArrayList<() -> Unit>()
    private var connected = false
    private var extras = VehicleExtras()
    private val windowPositions = HashMap<WindowValue, Int>()
    private val telltaleStates = HashMap<TelltaleType, TelltaleState>()
    private val telltaleCounts = HashMap<TelltaleType, Int>()

    /** 휴식 모드·깨우기용 실내 환경 제어 */
    val cabin = CabinControl(vehicle, { connected }, { message -> sink.onCabinNote(message) })

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
        registerTelltales()
        cabin.prepare()
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

    private fun registerTelltales() {
        safely("telltales") {
            val api = vehicle.getSafety()
            val listener = listenerProxy<TelltaleInfoListener> { method, args ->
                val info = args.firstOrNull()
                if (method == "onTelltaleInfoUpdated") post { onTelltales(info) }
                else if (method == "onFailed") warn("telltale listener", info)
            }
            api.registerTelltales(listener)
            unregisters += { api.unregisterTelltales(listener) }
            // 처음 읽은 상태는 기준으로만 쓴다 (앱을 켜기 전부터 켜져 있던 경고는 사건으로 세지 않음).
            api.getTelltalesInfo({ info -> post { onTelltales(info) } }, { e -> warn("telltales", e) })
        }
    }

    /** 경고등 목록을 이전 상태와 비교해, 새로 켜지거나 깜빡이기 시작한 경고(또는 경고 횟수 증가)만 사건으로 넘긴다. */
    private fun onTelltales(info: Any?) {
        val list = (info as? Telltales)?.telltales ?: return
        for (item in list) {
            val type = (item?.type as Any?) as? TelltaleType ?: continue
            val (event, trigger) = WATCHED[type] ?: continue
            val state = (item.state as Any?) as? TelltaleState ?: TelltaleState.OFF
            val count = item.warningCount
            val prevState = telltaleStates.put(type, state)
            val prevCount = telltaleCounts.put(type, count)
            if (prevState == null || prevCount == null) continue
            val triggered = when (trigger) {
                Trigger.TURNS_ON -> prevState == TelltaleState.OFF && state != TelltaleState.OFF
                Trigger.STARTS_BLINKING -> prevState != TelltaleState.BLINK && state == TelltaleState.BLINK
            }
            if (triggered || count > prevCount) sink.onVehicleEvent(event)
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
            // 남은 주행 거리는 미터로 온다 (차량 표준 속성 RANGE_REMAINING). 에뮬레이터 계기판의 500km가 500000으로 왔다.
            vehicle.getOdometer().getRangeRemainingDistance(
                { meters -> post { updateExtras(extras.copy(rangeKm = ((meters as Any?) as? Number)?.toDouble()?.div(METERS_PER_KM))) } },
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
        private const val METERS_PER_KM = 1000.0

        /**
         * 지켜볼 경고등. 정상 작동 중에도 켜져 있는 표시등(LKA·FCA)은 깜빡이기 시작할 때만 센다.
         * 실제 차량의 표시 방식은 에뮬레이터 Virtual Console과 실차에서 확인이 필요하다.
         */
        private val WATCHED: Map<TelltaleType, Pair<VehicleEvent, Trigger>> = mapOf(
            TelltaleType.DRIVER_ATTENTION to (VehicleEvent.DRIVER_ATTENTION to Trigger.TURNS_ON),
            TelltaleType.ELK to (VehicleEvent.LANE_ASSIST to Trigger.TURNS_ON),
            TelltaleType.LKA to (VehicleEvent.LANE_ASSIST to Trigger.STARTS_BLINKING),
            TelltaleType.FCA to (VehicleEvent.COLLISION_WARNING to Trigger.STARTS_BLINKING),
        )
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
