package kr.contest.pleosapp.sdk

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kr.contest.pleosapp.core.CabinMode
import kr.contest.pleosapp.core.Mood
import kr.contest.pleosapp.core.MoodPreset
import kr.contest.pleosapp.core.RestEnvironment

/**
 * 차 실내 환경(공조·무드등)을 상황에 맞게 바꾸고, 끝나면 바꾸기 전 설정으로 되돌린다.
 *
 * - 휴식: 무드등 은은하게, 원하는 온도, 약한 바람, (선택) 바깥 공기
 * - 깨우기: 무드등을 몇 단계에 걸쳐 밝게, 2도 낮춘 온도, 센 바람, 바깥 공기
 * - 출발: 휴식 시작 전에 읽어 둔 값으로 되돌림
 * - 롱웨이 모드 분위기: 데이트·혼자 쉬기에 맞춘 무드등 밝기와 온도. 도착하면 되돌림
 *
 * 제어할 수 있는 영역과 범위는 차량마다 달라 시작할 때 capability로 읽는다.
 * 제어가 실패해도 앱 흐름은 멈추지 않고, 결과를 문구로 알린다.
 */
class CabinControl(
    private val vehicle: Vehicle,
    private val isConnected: () -> Boolean,
    private val report: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val driver = SeatArea(SeatValue.ROW_1_LEFT)

    private var tempRanges: Map<SeatArea, ClosedFloatingPointRange<Float>> = emptyMap()
    private var fanRanges: Map<SeatArea, IntRange> = emptyMap()
    private var lightRanges: Map<SeatArea, IntRange> = emptyMap()
    private var recircAreas: List<SeatArea> = emptyList()

    private val savedTemp = HashMap<SeatArea, Float>()
    private val savedFan = HashMap<SeatArea, Int>()
    private val savedRecirc = HashMap<SeatArea, Boolean>()
    private val savedLightSwitch = HashMap<SeatArea, LightSwitch>()
    private val savedBrightness = HashMap<SeatArea, Int>()
    private var changed = false
    private var failureReported = false
    private var generation = 0
    /** 마지막으로 되돌린 시각. 되돌린 직후에는 되돌린 값이 아직 반영되지 않았을 수 있어 다시 읽지 않는다. */
    private var restoredAt = Long.MIN_VALUE / 2

    /** 차량 연결 뒤 한 번: 제어 가능한 영역과 범위를 읽는다. */
    fun prepare() {
        if (!isConnected()) return
        safely("temperature range") {
            vehicle.getHVAC().checkHvacTemperatureCapabilities(
                { bounds -> post { tempRanges = floatRanges(bounds) } },
                { e -> warn("temperature range", e) },
            )
        }
        safely("fan range") {
            vehicle.getHVAC().checkHvacFanSpeedCapabilities(
                { bounds -> post { fanRanges = intRanges(bounds) } },
                { e -> warn("fan range", e) },
            )
        }
        safely("recirculation areas") {
            vehicle.getHVAC().checkHvacRecirculationCapabilities(
                { areas -> post { recircAreas = areas?.filterNotNull().orEmpty() } },
                { e -> warn("recirculation areas", e) },
            )
        }
        safely("ambient range") {
            vehicle.getLight().checkAmbientLightBrightnessCapabilities(
                { _, bounds -> post { lightRanges = intRanges(bounds) } },
                { e -> warn("ambient range", e) },
            )
        }
    }

    fun apply(mode: CabinMode, env: RestEnvironment) {
        if (!isConnected()) {
            report("차량이 연결되지 않아 실내 환경은 바꾸지 않았어요.")
            return
        }
        val gen = ++generation
        failureReported = false
        // 원래 설정을 읽는 동안 기다렸다가 바꾼다. 그 사이에 다른 단계로 넘어가면(예: 바로 출발) 건너뛴다.
        when (mode) {
            CabinMode.REST -> if (env.enabled) {
                // 처음 바꿀 때만 원래 설정을 읽어 둔다 (깨우기에서 다시 잠들 때·분위기 중 쉴 때는 그대로 둔다).
                val wait = beginChange()
                main.postDelayed({ if (gen == generation) applyRest(env) }, wait)
            }
            CabinMode.WAKE -> if (env.enabled) {
                val wait = beginChange()
                main.postDelayed({ if (gen == generation) applyWake(env) }, wait)
            }
            CabinMode.RESTORE -> if (changed) restore("출발: 실내 환경을 휴식 전 설정으로 되돌렸어요.")
        }
    }

    /** 롱웨이 모드의 분위기를 켠다. null이면 분위기 전 설정으로 되돌린다. */
    fun applyMood(mood: Mood?) {
        if (mood == null) {
            if (changed) restore("드라이브를 마쳐서 실내 환경을 원래대로 되돌렸어요.")
            return
        }
        if (!isConnected()) {
            report("차량이 연결되지 않아 분위기는 바꾸지 않았어요.")
            return
        }
        val gen = ++generation
        failureReported = false
        val preset = MoodPreset.of(mood)
        val wait = beginChange()
        main.postDelayed({ if (gen == generation) applyPreset(preset) }, wait)
    }

    /** 바꾸기 시작: 처음이면 원래 설정을 읽어 두고, 읽는 동안 기다릴 시간을 돌려준다. */
    private fun beginChange(): Long {
        val fresh = !changed
        changed = true
        if (!fresh) return 0L
        // 막 되돌린 직후면 읽어 둔 원래 설정을 그대로 쓴다 (되돌린 값이 아직 반영되지 않았을 수 있음).
        if (SystemClock.elapsedRealtime() - restoredAt < RESNAPSHOT_GUARD_MS) return 0L
        snapshot()
        return SNAPSHOT_WAIT_MS
    }

    // ---- 단계별 설정 ----

    private fun applyRest(env: RestEnvironment) {
        for (area in hvacAreas()) {
            setTemperature(area, env.restTemperatureC)
            setFan(area, fraction = 0.25)
        }
        if (env.freshAir) recircTargets().forEach { setRecirculation(it, false) }
        if (env.dimLight) {
            lightTargets().forEach { area ->
                setLightSwitch(area, LightSwitch.ON)
                setBrightness(area, fraction = 0.15)
            }
        }
        val parts = buildList {
            add("${formatTemp(env.restTemperatureC)} 약한 바람")
            if (env.freshAir) add("바깥 공기")
            if (env.dimLight) add("무드등 은은하게")
        }
        report("휴식 모드: ${parts.joinToString(" · ")}")
    }

    private fun applyWake(env: RestEnvironment) {
        val gen = generation
        for (area in hvacAreas()) {
            setTemperature(area, env.restTemperatureC - 2f)
            setFan(area, fraction = 0.7)
        }
        recircTargets().forEach { setRecirculation(it, false) }
        val lights = lightTargets()
        lights.forEach { setLightSwitch(it, LightSwitch.ON) }
        // 무드등을 몇 단계에 걸쳐 밝힌다 (해 뜨는 것처럼).
        for (step in 1..WAKE_LIGHT_STEPS) {
            main.postDelayed({
                if (gen == generation) lights.forEach { setBrightness(it, fraction = step.toDouble() / WAKE_LIGHT_STEPS) }
            }, step * WAKE_LIGHT_STEP_MS)
        }
        report("깨우기: 무드등을 점점 밝게 · ${formatTemp(env.restTemperatureC - 2f)} 센 바람 · 바깥 공기")
    }

    private fun applyPreset(p: MoodPreset) {
        for (area in hvacAreas()) {
            setTemperature(area, p.temperatureC)
            setFan(area, fraction = p.fan)
        }
        lightTargets().forEach { area ->
            setLightSwitch(area, LightSwitch.ON)
            setBrightness(area, fraction = p.brightness)
        }
        report("${p.label} 분위기: 무드등 ${Math.round(p.brightness * 100)}% · ${formatTemp(p.temperatureC)} · 약한 바람")
    }

    private fun restore(message: String) {
        generation++
        savedTemp.forEach { (area, value) -> setTemperature(area, value) }
        savedFan.forEach { (area, value) -> setFanExact(area, value) }
        savedRecirc.forEach { (area, value) -> setRecirculation(area, value) }
        savedBrightness.forEach { (area, value) -> setBrightnessExact(area, value) }
        savedLightSwitch.forEach { (area, value) -> setLightSwitch(area, value) }
        changed = false
        restoredAt = SystemClock.elapsedRealtime()
        report(message)
    }

    // ---- 휴식 전 설정 읽기 ----

    private fun snapshot() {
        savedTemp.clear()
        savedFan.clear()
        savedRecirc.clear()
        savedLightSwitch.clear()
        savedBrightness.clear()
        for (area in hvacAreas()) {
            safely("read temperature") {
                vehicle.getHVAC().getHvacTemperature(
                    area,
                    TemperatureUnit.CELSIUS,
                    { value -> number(value)?.let { v -> post { savedTemp[area] = v.toFloat() } } },
                    { e -> warn("read temperature", e) },
                )
            }
            safely("read fan") {
                vehicle.getHVAC().getHvacFanSpeed(
                    area,
                    { value -> number(value)?.let { v -> post { savedFan[area] = v.toInt() } } },
                    { e -> warn("read fan", e) },
                )
            }
        }
        for (area in recircTargets()) {
            safely("read recirculation") {
                vehicle.getHVAC().getHvacRecirculationState(
                    area,
                    { value -> ((value as Any?) as? Boolean)?.let { v -> post { savedRecirc[area] = v } } },
                    { e -> warn("read recirculation", e) },
                )
            }
        }
        for (area in lightTargets()) {
            safely("read ambient switch") {
                vehicle.getLight().getAmbientLightSwitch(
                    area,
                    { value -> ((value as Any?) as? LightSwitch)?.let { v -> post { savedLightSwitch[area] = v } } },
                    { e -> warn("read ambient switch", e) },
                )
            }
            safely("read ambient brightness") {
                vehicle.getLight().getAmbientLightBrightness(
                    area,
                    { value -> number(value)?.let { v -> post { savedBrightness[area] = v.toInt() } } },
                    { e -> warn("read ambient brightness", e) },
                )
            }
        }
    }

    // ---- 개별 제어 ----

    private fun setTemperature(area: SeatArea, celsius: Float) {
        val value = tempRanges[area]?.let { celsius.coerceIn(it.start, it.endInclusive) } ?: celsius
        safely("set temperature") {
            vehicle.getHVAC().setHvacTemperature(area, value, TemperatureUnit.CELSIUS, { _, _ -> }, { e -> failed("공조 온도", e) })
        }
    }

    private fun setFan(area: SeatArea, fraction: Double) {
        val range = fanRanges[area] ?: DEFAULT_FAN_RANGE
        setFanExact(area, level(range, fraction))
    }

    private fun setFanExact(area: SeatArea, speed: Int) {
        safely("set fan") {
            vehicle.getHVAC().setHvacFanSpeed(area, speed, { _, _ -> }, { e -> failed("바람 세기", e) })
        }
    }

    private fun setRecirculation(area: SeatArea, on: Boolean) {
        safely("set recirculation") {
            vehicle.getHVAC().setHvacRecirculation(area, on, { _, _ -> }, { e -> failed("내외기", e) })
        }
    }

    private fun setLightSwitch(area: SeatArea, value: LightSwitch) {
        safely("set ambient switch") {
            vehicle.getLight().setAmbientLightSwitch(area, value, { _, _ -> }, { e -> failed("무드등", e) })
        }
    }

    private fun setBrightness(area: SeatArea, fraction: Double) {
        // 범위를 모르면 밝기는 건드리지 않는다 (차종마다 단계가 다름).
        val range = lightRanges[area] ?: return
        setBrightnessExact(area, level(range, fraction))
    }

    private fun setBrightnessExact(area: SeatArea, value: Int) {
        safely("set ambient brightness") {
            vehicle.getLight().setAmbientLightBrightness(area, value, { _, _ -> }, { e -> failed("무드등 밝기", e) })
        }
    }

    // ---- 도움 함수 ----

    private fun hvacAreas(): List<SeatArea> = tempRanges.keys.toList().ifEmpty { listOf(driver) }

    private fun recircTargets(): List<SeatArea> = recircAreas.ifEmpty { listOf(driver) }

    private fun lightTargets(): List<SeatArea> = lightRanges.keys.toList().ifEmpty { listOf(driver) }

    private fun level(range: IntRange, fraction: Double): Int {
        val span = range.last - range.first
        return (range.first + Math.round(span * fraction.coerceIn(0.0, 1.0)).toInt()).coerceIn(range.first, range.last)
    }

    private fun failed(what: String, e: Any?) {
        warn("control $what", e)
        post {
            if (!failureReported) {
                failureReported = true
                report("$what 제어가 되지 않았어요. 차량 제어 권한과 차종 지원 여부를 확인해 주세요.")
            }
        }
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    private fun warn(what: String, e: Any?) {
        Log.w(TAG, "$what failed: $e")
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            warn(what, e)
        }
    }

    companion object {
        private const val TAG = "RestMateCabin"
        private const val SNAPSHOT_WAIT_MS = 800L
        private const val RESNAPSHOT_GUARD_MS = 5_000L
        private const val WAKE_LIGHT_STEPS = 5
        private const val WAKE_LIGHT_STEP_MS = 4_000L
        private val DEFAULT_FAN_RANGE = 1..8

        private fun number(value: Any?): Number? = value as? Number

        private fun formatTemp(c: Float): String =
            if (c % 1f == 0f) "${c.toInt()}°C" else String.format(java.util.Locale.ROOT, "%.1f°C", c)

        private fun floatRanges(bounds: Map<SeatArea, *>?): Map<SeatArea, ClosedFloatingPointRange<Float>> {
            val out = HashMap<SeatArea, ClosedFloatingPointRange<Float>>()
            bounds?.forEach { (area, b) ->
                val lo = boundValue(b, min = true)?.toFloat()
                val hi = boundValue(b, min = false)?.toFloat()
                if (area != null && lo != null && hi != null && lo <= hi) out[area] = lo..hi
            }
            return out
        }

        private fun intRanges(bounds: Map<SeatArea, *>?): Map<SeatArea, IntRange> {
            val out = HashMap<SeatArea, IntRange>()
            bounds?.forEach { (area, b) ->
                val lo = boundValue(b, min = true)?.toInt()
                val hi = boundValue(b, min = false)?.toInt()
                if (area != null && lo != null && hi != null && lo <= hi) out[area] = lo..hi
            }
            return out
        }

        private fun boundValue(b: Any?, min: Boolean): Number? {
            val bounds = b as? ai.pleos.playground.vehicle.api.model.capability.AreaBounds<*> ?: return null
            return (if (min) bounds.minValue else bounds.maxValue) as? Number
        }
    }
}
