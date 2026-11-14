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
import kr.contest.pleosapp.core.CabinPart
import kr.contest.pleosapp.core.CabinSummary
import kr.contest.pleosapp.core.Mood
import kr.contest.pleosapp.core.MoodPreset
import kr.contest.pleosapp.core.RestEnvironment

/**
 * 차 실내 환경(공조·무드등)을 상황에 맞게 바꾸고, 끝나면 바꾸기 전 설정으로 되돌린다.
 *
 * - 휴식: 무드등 은은하게, 원하는 온도, 약한 바람, (선택) 바깥 공기
 * - 깨우기: 무드등을 몇 단계에 걸쳐 밝게, 2도 낮춘 온도, 센 바람, 바깥 공기
 * - 출발: 휴식 시작 전에 읽어 둔 값으로 되돌림
 * - 롱웨이 모드 분위기: 데이트·혼자 쉬기에 맞춘 무드등 밝기와 온도. 도착하면 무드등은 천천히 되돌림
 *
 * 제어할 수 있는 영역과 범위는 차량마다 달라 시작할 때 capability로 읽는다.
 * 제어가 실패해도 앱 흐름은 멈추지 않는다. 바꾼 뒤 조금 있다 다시 읽어 실제로 바뀌었는지도 본다
 * (Pleos Connect 에뮬레이터는 공조 전원·내외기를 성공으로 답하고도 값은 그대로였다).
 * 결과는 한 줄로 알리고, 이 차에서 되지 않은 제어는 그 줄에서 빼고 짧게 덧붙인다 ([CabinSummary]).
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
    /** 공조 전원을 켜고 끌 수 있는 영역. 모르면 비워 두고 전원은 건드리지 않는다 */
    private var powerAreas: List<SeatArea> = emptyList()

    private val savedTemp = HashMap<SeatArea, Float>()
    private val savedFan = HashMap<SeatArea, Int>()
    private val savedRecirc = HashMap<SeatArea, Boolean>()
    private val savedPower = HashMap<SeatArea, Boolean>()
    private val savedLightSwitch = HashMap<SeatArea, LightSwitch>()
    private val savedBrightness = HashMap<SeatArea, Int>()
    /** 쉬려고 켠 공조 전원 영역. 되돌릴 때 다시 끈다 (원래 켜져 있던 차는 건드리지 않는다) */
    private val poweredOn = HashSet<SeatArea>()
    private var changed = false
    private var generation = 0
    /** 지금 하고 있는 바꾸기. 제어가 실패하면 이 바꾸기의 결과 줄에 모인다 */
    private var current: Change? = null
    /** 이 차에서 되지 않은 제어. 되돌릴 때 같은 제어가 또 안 되어도 다시 알리지 않는다 */
    private val unsupported = HashSet<CabinPart>()
    /** 마지막으로 되돌린 시각. 되돌린 직후에는 되돌린 값이 아직 반영되지 않았을 수 있어 다시 읽지 않는다. */
    private var restoredAt = Long.MIN_VALUE / 2
    /** 켜 둔 분위기의 무드등 밝기 (0~1). 분위기를 천천히 끌 때 여기서부터 원래 밝기로 바꾼다. */
    private var moodBrightness: Double? = null

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
        safely("power areas") {
            vehicle.getHVAC().checkHvacPowerCapabilities(
                { areas -> post { powerAreas = areas?.filterNotNull().orEmpty() } },
                { e -> warn("power areas", e) },
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

    /** @param departing 출발해서 되돌리는지 (아니면 쉬는 방식을 바꿔 휴식 환경만 끈 것) */
    fun apply(mode: CabinMode, env: RestEnvironment, departing: Boolean = true) {
        if (!isConnected()) {
            report("차량이 연결되지 않아 실내 환경은 바꾸지 않았어요.")
            return
        }
        val gen = ++generation
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
            CabinMode.RESTORE -> if (changed) {
                restore(if (departing) "출발: 실내 환경을 휴식 전 설정으로 되돌렸어요." else "휴식 환경을 끄고 원래 설정으로 되돌렸어요.")
            }
        }
    }

    /**
     * 롱웨이 모드의 분위기를 켠다. null이면 분위기 전 설정으로 되돌린다.
     * @param gently 되돌릴 때 무드등을 몇 단계에 걸쳐 천천히 (도착하자마자 확 밝아지지 않게)
     */
    fun applyMood(mood: Mood?, gently: Boolean = false) {
        if (mood == null) {
            if (!changed) return
            if (gently && moodBrightness != null) fadeOutMood() else restore("드라이브를 마쳐서 실내 환경을 원래대로 되돌렸어요.")
            return
        }
        if (!isConnected()) {
            report("차량이 연결되지 않아 분위기는 바꾸지 않았어요.")
            return
        }
        val gen = ++generation
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
        val change = begin(
            CabinSummary(
                "휴식 모드",
                buildList {
                    add(formatTemp(env.restTemperatureC) to CabinPart.TEMPERATURE)
                    add("약한 바람" to CabinPart.FAN)
                    if (env.freshAir) add("바깥 공기" to CabinPart.RECIRCULATION)
                    if (env.dimLight) add("무드등 은은하게" to CabinPart.AMBIENT)
                },
            ),
        )
        withHvacOn {
            hvacAreas().forEach { setTemperature(it, env.restTemperatureC) }
            fanAreas().forEach { setFan(it, fraction = 0.25) }
            if (env.freshAir) recircTargets().forEach { setRecirculation(it, false) }
        }
        if (env.dimLight) {
            lightTargets().forEach { area ->
                setLightSwitch(area, LightSwitch.ON)
                setBrightness(area, fraction = 0.15)
            }
        }
        change.publishLater()
    }

    private fun applyWake(env: RestEnvironment) {
        val gen = generation
        val change = begin(
            CabinSummary(
                "깨우기",
                listOf(
                    "무드등을 점점 밝게" to CabinPart.AMBIENT,
                    formatTemp(env.restTemperatureC - 2f) to CabinPart.TEMPERATURE,
                    "센 바람" to CabinPart.FAN,
                    "바깥 공기" to CabinPart.RECIRCULATION,
                ),
            ),
        )
        withHvacOn {
            hvacAreas().forEach { setTemperature(it, env.restTemperatureC - 2f) }
            fanAreas().forEach { setFan(it, fraction = 0.7) }
            recircTargets().forEach { setRecirculation(it, false) }
        }
        val lights = lightTargets()
        lights.forEach { setLightSwitch(it, LightSwitch.ON) }
        // 무드등을 몇 단계에 걸쳐 밝힌다 (해 뜨는 것처럼).
        for (step in 1..WAKE_LIGHT_STEPS) {
            main.postDelayed({
                if (gen == generation) lights.forEach { setBrightness(it, fraction = step.toDouble() / WAKE_LIGHT_STEPS) }
            }, step * WAKE_LIGHT_STEP_MS)
        }
        change.publishLater()
    }

    private fun applyPreset(p: MoodPreset) {
        val change = begin(
            CabinSummary(
                "${p.label} 분위기",
                listOf(
                    "무드등 ${Math.round(p.brightness * 100)}%" to CabinPart.AMBIENT,
                    formatTemp(p.temperatureC) to CabinPart.TEMPERATURE,
                    "약한 바람" to CabinPart.FAN,
                ),
            ),
        )
        withHvacOn {
            hvacAreas().forEach { setTemperature(it, p.temperatureC) }
            fanAreas().forEach { setFan(it, fraction = p.fan) }
        }
        lightTargets().forEach { area ->
            setLightSwitch(area, LightSwitch.ON)
            setBrightness(area, fraction = p.brightness)
        }
        moodBrightness = p.brightness
        change.publishLater()
    }

    private fun restore(message: String?) {
        val gen = ++generation
        val change = begin(null, message)
        savedTemp.forEach { (area, value) -> setTemperature(area, value) }
        savedFan.forEach { (area, value) -> setFanExact(area, value) }
        savedRecirc.forEach { (area, value) -> setRecirculation(area, value) }
        savedBrightness.forEach { (area, value) -> setBrightnessExact(area, value) }
        savedLightSwitch.forEach { (area, value) -> setLightSwitch(area, value) }
        // 공조 전원은 마지막에: 쉬려고 켠 전원만 다시 끈다. 온도·바람을 되돌린 뒤에 끄도록 조금 기다린다.
        val off = poweredOn.toList()
        poweredOn.clear()
        if (off.isNotEmpty()) {
            main.postDelayed({ if (gen == generation) off.forEach { setPower(it, false) } }, POWER_WAIT_MS)
        }
        changed = false
        moodBrightness = null
        restoredAt = SystemClock.elapsedRealtime()
        change.publishLater()
    }

    /**
     * 분위기를 천천히 끈다: 공조는 바로 되돌리고, 무드등은 몇 단계에 걸쳐 원래 밝기로 (도착하자마자 확 밝아지지 않게).
     * 다 끝날 때까지는 바뀐 상태로 두어(changed), 그 사이 다른 단계가 시작되면 여기서 멈추고 읽어 둔 원래 설정을 그대로 쓴다.
     */
    private fun fadeOutMood() {
        val from = moodBrightness ?: return
        val gen = ++generation
        val change = begin(null, "드라이브를 마쳐서 무드등을 천천히 원래대로 되돌려요.")
        savedTemp.forEach { (area, value) -> setTemperature(area, value) }
        savedFan.forEach { (area, value) -> setFanExact(area, value) }
        savedRecirc.forEach { (area, value) -> setRecirculation(area, value) }
        val targets = HashMap(savedBrightness)
        for (step in 1..MOOD_FADE_STEPS) {
            main.postDelayed({
                if (gen == generation) {
                    if (step < MOOD_FADE_STEPS) {
                        targets.forEach { (area, saved) ->
                            val range = lightRanges[area]
                            if (range != null) {
                                val start = level(range, from)
                                setBrightnessExact(area, start + (saved - start) * step / MOOD_FADE_STEPS)
                            }
                        }
                    } else {
                        // 마지막 단계: 읽어 둔 값 그대로 (무드등을 원래 껐으면 이때 끈다).
                        restore(null)
                    }
                }
            }, step * MOOD_FADE_STEP_MS)
        }
        change.publishLater()
    }

    // ---- 휴식 전 설정 읽기 ----

    private fun snapshot() {
        savedTemp.clear()
        savedFan.clear()
        savedRecirc.clear()
        savedPower.clear()
        savedLightSwitch.clear()
        savedBrightness.clear()
        for (area in powerAreas) {
            safely("read power") {
                vehicle.getHVAC().getHvacPowerState(
                    area,
                    { value -> ((value as Any?) as? Boolean)?.let { v -> post { savedPower[area] = v } } },
                    { e -> warn("read power", e) },
                )
            }
        }
        for (area in hvacAreas()) {
            safely("read temperature") {
                vehicle.getHVAC().getHvacTemperature(
                    area,
                    TemperatureUnit.CELSIUS,
                    { value -> number(value)?.let { v -> post { savedTemp[area] = v.toFloat() } } },
                    { e -> warn("read temperature", e) },
                )
            }
        }
        for (area in fanAreas()) {
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
        control(CabinPart.TEMPERATURE) { change ->
            vehicle.getHVAC().setHvacTemperature(area, value, TemperatureUnit.CELSIUS, { _, _ -> }, { e -> failed(change, CabinPart.TEMPERATURE, e) })
        }
        verify(CabinPart.TEMPERATURE) { change ->
            vehicle.getHVAC().getHvacTemperature(
                area,
                TemperatureUnit.CELSIUS,
                { v -> number(v)?.let { check(change, CabinPart.TEMPERATURE, Math.abs(it.toFloat() - value) <= TEMP_TOLERANCE_C, "$it°C") } },
                { e -> warn("verify temperature", e) },
            )
        }
    }

    private fun setFan(area: SeatArea, fraction: Double) {
        val range = fanRanges[area] ?: DEFAULT_FAN_RANGE
        setFanExact(area, level(range, fraction))
    }

    private fun setFanExact(area: SeatArea, speed: Int) {
        control(CabinPart.FAN) { change ->
            vehicle.getHVAC().setHvacFanSpeed(area, speed, { _, _ -> }, { e -> failed(change, CabinPart.FAN, e) })
        }
        verify(CabinPart.FAN) { change ->
            vehicle.getHVAC().getHvacFanSpeed(
                area,
                { v -> number(v)?.let { check(change, CabinPart.FAN, it.toInt() == speed, "$it") } },
                { e -> warn("verify fan", e) },
            )
        }
    }

    /**
     * 공조 전원이 꺼져 있으면 켠 뒤에 [block]을 한다 (꺼진 공조에 온도·바람만 바꿔 두면 쉬는 동안 바람이 나오지 않는다).
     * 꺼져 있는지 모르는 영역은 건드리지 않는다. 앞서 켜지지 않았으면 다시 켜 본다 (이번 결과 줄에도 그대로 남도록).
     * Pleos Connect 에뮬레이터는 켜기를 성공으로 답하고도 꺼진 채였고, 그래서 바람 세기도 바꿀 수 없었다
     * ('HVAC_FAN_SPEED … error code 2'). 그런 제어는 결과 줄에 짧게 덧붙는다.
     */
    private fun withHvacOn(block: () -> Unit) {
        val off = powerAreas.filter { savedPower[it] == false && (it !in poweredOn || CabinPart.POWER in unsupported) }
        if (off.isEmpty()) {
            block()
            return
        }
        val gen = generation
        off.forEach {
            poweredOn += it
            setPower(it, true)
        }
        main.postDelayed({ if (gen == generation) block() }, POWER_WAIT_MS)
    }

    private fun setPower(area: SeatArea, on: Boolean) {
        control(CabinPart.POWER) { change ->
            vehicle.getHVAC().setHvacPower(area, on, { _, _ -> }, { e -> failed(change, CabinPart.POWER, e) })
        }
        verify(CabinPart.POWER) { change ->
            vehicle.getHVAC().getHvacPowerState(
                area,
                { v -> ((v as Any?) as? Boolean)?.let { check(change, CabinPart.POWER, it == on, "$it") } },
                { e -> warn("verify power", e) },
            )
        }
    }

    private fun setRecirculation(area: SeatArea, on: Boolean) {
        control(CabinPart.RECIRCULATION) { change ->
            vehicle.getHVAC().setHvacRecirculation(area, on, { _, _ -> }, { e -> failed(change, CabinPart.RECIRCULATION, e) })
        }
        verify(CabinPart.RECIRCULATION) { change ->
            vehicle.getHVAC().getHvacRecirculationState(
                area,
                { v -> ((v as Any?) as? Boolean)?.let { check(change, CabinPart.RECIRCULATION, it == on, "$it") } },
                { e -> warn("verify recirculation", e) },
            )
        }
    }

    private fun setLightSwitch(area: SeatArea, value: LightSwitch) {
        control(CabinPart.AMBIENT) { change ->
            vehicle.getLight().setAmbientLightSwitch(area, value, { _, _ -> }, { e -> failed(change, CabinPart.AMBIENT, e) })
        }
        verify(CabinPart.AMBIENT) { change ->
            vehicle.getLight().getAmbientLightSwitch(
                area,
                { v -> ((v as Any?) as? LightSwitch)?.let { check(change, CabinPart.AMBIENT, it == value, "$it") } },
                { e -> warn("verify ambient switch", e) },
            )
        }
    }

    private fun setBrightness(area: SeatArea, fraction: Double) {
        // 범위를 모르면 밝기는 건드리지 않는다 (차종마다 단계가 다름).
        val range = lightRanges[area] ?: return
        setBrightnessExact(area, level(range, fraction))
    }

    private fun setBrightnessExact(area: SeatArea, value: Int) {
        control(CabinPart.AMBIENT) { change ->
            vehicle.getLight().setAmbientLightBrightness(area, value, { _, _ -> }, { e -> failed(change, CabinPart.AMBIENT, e) })
        }
        // 차종마다 밝기 단계가 달라 읽은 값이 조금 다를 수 있다: 범위의 10%까지는 바뀐 것으로 본다.
        val tolerance = lightRanges[area]?.let { maxOf(1, (it.last - it.first) / 10) } ?: 1
        verify(CabinPart.AMBIENT) { change ->
            vehicle.getLight().getAmbientLightBrightness(
                area,
                { v -> number(v)?.let { check(change, CabinPart.AMBIENT, Math.abs(it.toInt() - value) <= tolerance, "$it") } },
                { e -> warn("verify ambient brightness", e) },
            )
        }
    }

    // ---- 도움 함수 ----

    private fun hvacAreas(): List<SeatArea> = tempRanges.keys.toList().ifEmpty { listOf(driver) }

    /**
     * 바람 세기를 바꿀 영역: 차가 알려 준 바람 세기 영역 (온도 영역과 다를 수 있다).
     * 온도는 운전석·조수석 따로인데 바람은 앞뒤 전체 한 영역인 차(Pleos Connect 에뮬레이터 포함)에서
     * 온도 영역으로 바람을 바꾸면 '바람 세기 제어가 되지 않았어요'가 났다.
     */
    private fun fanAreas(): List<SeatArea> = fanRanges.keys.toList().ifEmpty { hvacAreas() }

    private fun recircTargets(): List<SeatArea> = recircAreas.ifEmpty { listOf(driver) }

    /** 내외기 상태를 읽을 영역: 차가 알려 준 내외기 영역 (에뮬레이터는 운전석 영역으로 읽으면 실패한다) */
    fun recirculationArea(): SeatArea = recircTargets().first()

    private fun lightTargets(): List<SeatArea> = lightRanges.keys.toList().ifEmpty { listOf(driver) }

    private fun level(range: IntRange, fraction: Double): Int {
        val span = range.last - range.first
        return (range.first + Math.round(span * fraction.coerceIn(0.0, 1.0)).toInt()).coerceIn(range.first, range.last)
    }

    // ---- 결과 알리기 ----

    private fun begin(summary: CabinSummary?, message: String? = null): Change =
        Change(generation, summary, message).also { current = it }

    /** 제어 하나: 실패는 지금 바꾸기에 모은다 (바로 던진 예외도 실패로 친다) */
    private inline fun control(part: CabinPart, block: (Change?) -> Unit) {
        val change = current
        try {
            block(change)
        } catch (e: Exception) {
            failed(change, part, e)
        }
    }

    private fun failed(change: Change?, part: CabinPart, e: Any?) {
        warn("control ${part.label}", e)
        post { change?.fail(part) }
    }

    /**
     * 바꾼 뒤 [VERIFY_DELAY_MS] 있다가 다시 읽어 본다. 그 사이 다른 바꾸기가 시작됐으면 건너뛴다.
     * 차가 명령을 성공으로 답하고도 값을 바꾸지 않을 수 있다 (Pleos Connect 에뮬레이터의 공조 전원·내외기).
     * 다시 읽지 못하는 값(에뮬레이터의 온도)은 확인하지 않는다.
     */
    private fun verify(part: CabinPart, read: (Change) -> Unit) {
        val change = current ?: return
        main.postDelayed({
            if (change === current) safely("verify ${part.label}") { read(change) }
        }, VERIFY_DELAY_MS)
    }

    private fun check(change: Change, part: CabinPart, applied: Boolean, actual: String) {
        if (applied) return
        warn("control ${part.label}", "not applied (still $actual)")
        post { change.fail(part) }
    }

    /**
     * 바꾸기 한 번 (휴식·깨우기·분위기·되돌리기). 제어 결과가 돌아올 때쯤 결과를 한 줄로 한 번 알리고,
     * 그 뒤에 온 실패는 줄을 고쳐 다시 알린다. 그 사이 다른 단계로 넘어갔으면 알리지 않는다.
     *
     * @param summary 바꾼 것 (되돌리기는 null)
     * @param message 되돌리기를 알릴 문구 (null이면 알리지 않는다)
     */
    private inner class Change(val gen: Int, val summary: CabinSummary?, val message: String?) {
        private val failed = LinkedHashSet<CabinPart>()
        private var due = false
        private var shown: String? = null

        fun fail(part: CabinPart) {
            if (!failed.add(part)) return
            if (summary != null) unsupported += part
            if (due) publish()
        }

        fun publishLater() {
            main.postDelayed({
                due = true
                publish()
            }, REPORT_WAIT_MS)
        }

        private fun publish() {
            if (gen != generation) return
            val text = when {
                summary != null -> summary.text(failed)
                message != null -> CabinSummary.restored(message, failed.filter { it !in unsupported })
                else -> null
            }
            if (text != null && text != shown) {
                shown = text
                report(text)
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

        /** 공조 전원을 켠 뒤(또는 끄기 전) 다른 설정이 먹을 때까지 기다리는 시간 */
        private const val POWER_WAIT_MS = 500L

        /** 바꾼 뒤 실제로 바뀌었는지 다시 읽기까지 기다리는 시간 */
        private const val VERIFY_DELAY_MS = 1_000L

        /** 바꾸기 결과를 알리기 전에 기다리는 시간: 전원을 켜고 기다린 뒤 바꾸고 다시 읽을 때까지 */
        private const val REPORT_WAIT_MS = POWER_WAIT_MS + VERIFY_DELAY_MS + 1_000L

        /** 다시 읽은 온도가 이만큼까지 다르면 바뀐 것으로 본다 (차는 0.5도 단위로 맞추기도 한다) */
        private const val TEMP_TOLERANCE_C = 0.5f
        private const val RESNAPSHOT_GUARD_MS = 5_000L
        private const val WAKE_LIGHT_STEPS = 5
        private const val WAKE_LIGHT_STEP_MS = 4_000L
        private const val MOOD_FADE_STEPS = 5
        private const val MOOD_FADE_STEP_MS = 4_000L
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
