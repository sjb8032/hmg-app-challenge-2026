package kr.contest.pleosapp.core

import kr.contest.pleosapp.core.TestAreas.area
import kr.contest.pleosapp.core.TestAreas.origin
import kr.contest.pleosapp.core.TestAreas.southKm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestMateEngineTest {
    private val t0 = 1_000_000_000L
    private val a5 = area("a5", southKm(5.0))
    private val a25 = area("a25", southKm(25.0))

    private fun engine(vararg areas: RestArea = arrayOf(a5, a25)) =
        RestMateEngine(RestAreaFinder(areas.toList()))

    private fun comparingEngine() =
        RestMateEngine(RestAreaFinder(listOf(a5, a25)), RestMateConfig(compareAfterRest = true))

    private fun RestMateEngine.driveAt(now: Long, position: LatLng = origin) {
        onGear(now, Gear.DRIVE)
        onSpeed(now, 100.0)
        onLocation(now, position, 180.0)
    }

    /** 휴게소까지 안내받고 P단으로 주차해 휴식을 시작한 시각을 돌려준다. */
    private fun RestMateEngine.restAtNearest(start: Long): Long {
        driveAt(start)
        pressDrowsy(start + 1_000)
        onSpeed(start + 2_000, 0.0)
        onGear(start + 2_000, Gear.PARK)
        onTick(start + 12_000)
        return start + 12_000
    }

    @Test
    fun drowsyPressStartsGuidanceToNearestAhead() {
        val e = engine()
        e.driveAt(t0)
        e.drainEffects()
        e.pressDrowsy(t0 + 1_000)

        assertEquals(Phase.TO_REST_AREA, e.state.phase)
        assertEquals("a5", e.state.target?.area?.id)
        assertEquals(listOf("a25"), e.state.alternatives.map { it.area.id })
        assertTrue(e.drainEffects().any { it is Effect.StartGuidance && it.area.id == "a5" })
    }

    @Test
    fun drowsyWithoutLocationReportsFailure() {
        val e = engine()
        e.onGear(t0, Gear.DRIVE)
        e.pressDrowsy(t0 + 1_000)
        assertEquals(Phase.DRIVING, e.state.phase)
        assertNotNull(e.state.searchFailure)
    }

    @Test
    fun parkingConfirmedAfterTenSecondsStartsRest() {
        val e = engine()
        e.driveAt(t0)
        e.pressDrowsy(t0 + 1_000)
        e.onLocation(t0 + 1_500, southKm(4.9), 180.0)
        e.onSpeed(t0 + 2_000, 0.0)
        e.onGear(t0 + 2_000, Gear.PARK)

        e.onTick(t0 + 11_000)
        assertEquals(Phase.TO_REST_AREA, e.state.phase)
        e.onTick(t0 + 12_000)
        assertEquals(Phase.RESTING, e.state.phase)
        assertEquals("a5", e.state.rest?.area?.id)
        assertTrue(e.state.rest!!.fatigue)
    }

    @Test
    fun parkingFarBeforeTheGuidedAreaRestsWhereTheCarIs() {
        val e = engine()
        val restAt = e.restAtNearest(t0)
        // 쉼터 5km 전에 세웠다: 쉼터 이름을 붙이지 않는다 ('지금 있는 곳').
        assertEquals(Phase.RESTING, e.state.phase)
        assertNull(e.state.rest?.area)
        assertTrue(restAt > t0)
    }

    @Test
    fun alarmRingsOnTimeAndCanBeExtended() {
        val e = engine()
        val restAt = e.restAtNearest(t0)
        e.drainEffects()

        e.onTick(restAt + 20 * MINUTE_MS - 1)
        assertFalse(e.state.rest!!.alarmRinging)
        e.onTick(restAt + 20 * MINUTE_MS)
        assertTrue(e.state.rest!!.alarmRinging)
        assertTrue(e.drainEffects().contains(Effect.Alarm(true)))

        e.extendRest(restAt + 20 * MINUTE_MS + 1_000, 10)
        assertFalse(e.state.rest!!.alarmRinging)
        assertEquals(30, e.state.rest!!.plannedMinutes)
        assertTrue(e.drainEffects().contains(Effect.Alarm(false)))
    }

    @Test
    fun stoppedAlarmDoesNotRingAgain() {
        val e = engine()
        val restAt = e.restAtNearest(t0)
        e.onTick(restAt + 20 * MINUTE_MS)
        e.stopAlarm(restAt + 20 * MINUTE_MS + 1_000)
        e.onTick(restAt + 21 * MINUTE_MS)
        assertFalse(e.state.rest!!.alarmRinging)
    }

    @Test
    fun departureStartsComparisonAndResetsDrivingTime() {
        val e = comparingEngine()
        e.driveAt(t0)
        e.addDrivingTime(t0 + 1, 2 * HOUR_MS)
        val restAt = e.restAtNearest(t0 + 1_000)
        assertEquals(Phase.RESTING, e.state.phase)

        val departAt = restAt + 20 * MINUTE_MS
        e.onGear(departAt, Gear.DRIVE)
        e.onSpeed(departAt, 100.0)

        assertEquals(Phase.DRIVING, e.state.phase)
        assertEquals(0L, e.state.continuousDrivingMs)
        assertEquals(departAt, e.state.recovery?.departedAt)
        assertNull(e.state.recovery?.result)

        for (s in 1..20) e.onTick(departAt + s * 30 * SECOND_MS)
        val result = e.state.recovery?.result
        assertNotNull(result)
        // 휴식 전 고속 주행이 몇 초뿐이라 비교하지 않는다.
        assertEquals(StabilityVerdict.NOT_ENOUGH_DATA, result!!.verdict)
    }

    @Test
    fun departureWithoutComparisonJustResumesGuidance() {
        val e = engine()
        e.driveAt(t0)
        val restAt = e.restAtNearest(t0 + 1_000)
        e.drainEffects()
        val departAt = restAt + 20 * MINUTE_MS
        e.onGear(departAt, Gear.DRIVE)
        e.onSpeed(departAt, 100.0)
        assertEquals(Phase.DRIVING, e.state.phase)
        assertNull(e.state.recovery)
        val speech = e.drainEffects().filterIsInstance<Effect.Speak>().joinToString(" ") { it.text }
        assertTrue(speech, speech.contains("원래 가던 길"))
    }

    @Test
    fun parkingAtDestinationDoesNotAskToRest() {
        val e = engine()
        e.driveAt(t0)
        e.onTripArrived(t0 + 1_000)
        e.onSpeed(t0 + 30_000, 0.0)
        e.onGear(t0 + 30_000, Gear.PARK)
        e.onTick(t0 + 60_000)
        assertNull(e.state.prompt)
    }

    @Test
    fun parkingElsewhereStillAsksToRest() {
        val e = engine()
        e.driveAt(t0)
        e.onSpeed(t0 + 1_000, 0.0)
        e.onGear(t0 + 1_000, Gear.PARK)
        e.onTick(t0 + 20_000)
        assertEquals(Prompt.ParkedHere, e.state.prompt)
    }

    /** P단으로 세워 '주차하셨네요' 물음까지 */
    private fun RestMateEngine.parkWithoutGuidance(start: Long): Long {
        driveAt(start)
        onSpeed(start + 1_000, 0.0)
        onGear(start + 1_000, Gear.PARK)
        onTick(start + 12_000)
        return start + 12_000
    }

    @Test
    fun cityNapDefaultsToFifteenMinutes() {
        val e = engine()
        e.setOnExpressway(false)
        val at = e.parkWithoutGuidance(t0)
        assertEquals(Prompt.ParkedHere, e.state.prompt)
        e.restHere(at, RestKind.NAP)
        assertEquals(RestKind.NAP, e.state.rest?.kind)
        assertEquals(15, e.state.rest?.plannedMinutes)
    }

    @Test
    fun napIsFittedToOnTimeArrival() {
        val e = engine()
        e.driveAt(t0)
        // 도착 목표 35분 뒤, 남은 길 10분, 여유 3분 → 22분 뒤에는 출발해야 한다.
        e.setDeadline(TripDeadline(targetAt = t0 + 35 * MINUTE_MS, marginMs = 3 * MINUTE_MS, strict = true, remainingMs = 10 * MINUTE_MS))
        e.drainEffects()
        e.startRest(t0)
        val rest = e.state.rest!!
        assertTrue(rest.fitsTarget)
        assertNull(rest.lateByMs)
        assertEquals(t0 + 22 * MINUTE_MS, rest.departAt)
        // 낮잠 + 깨우는 시간(낮잠의 절반)이 22분 안: 낮잠은 기본 20분보다 짧은 약 14분 40초
        val nap = rest.alarmAt - rest.startedAt
        assertTrue("${nap / SECOND_MS}초", nap in 14 * MINUTE_MS..15 * MINUTE_MS)
        val speech = e.drainEffects().filterIsInstance<Effect.Speak>().single().text
        assertTrue(speech, speech.contains("도착에 맞춰"))
    }

    @Test
    fun napThatCannotFitSaysHowLateFirst() {
        val e = engine()
        e.driveAt(t0)
        e.setDeadline(TripDeadline(t0 + 20 * MINUTE_MS, 3 * MINUTE_MS, strict = true, remainingMs = 10 * MINUTE_MS))
        e.drainEffects()
        e.startRest(t0)
        val rest = e.state.rest!!
        assertFalse(rest.fitsTarget)
        // 안전이 먼저: 10분보다 짧게 줄이지 않고, 얼마나 늦는지 알린다.
        // 7분 뒤 출발해야 하는데 10분 자고 5분 깨우면 8분 늦게 출발: 여유 3분을 빼면 목표보다 5분 늦음
        assertEquals(10 * MINUTE_MS, rest.alarmAt - rest.startedAt)
        assertEquals(5 * MINUTE_MS, rest.lateByMs)
        val speech = e.drainEffects().filterIsInstance<Effect.Speak>().single().text
        assertTrue(speech, speech.startsWith("지금 쉬면 도착이 약 5분 늦어져요"))
    }

    @Test
    fun waitingAtPlannedStopRemindsWithoutAlarmOrWakeUp() {
        val e = engine()
        e.driveAt(t0)
        e.setDeadline(TripDeadline(t0 + 40 * MINUTE_MS, 3 * MINUTE_MS, strict = true, remainingMs = 8 * MINUTE_MS))
        e.onSpeed(t0 + 1_000, 0.0)
        e.onGear(t0 + 1_000, Gear.PARK)
        e.drainEffects()
        e.startRest(t0 + 2_000, RestKind.WAIT, a5)
        val rest = e.state.rest!!
        assertEquals(RestKind.WAIT, rest.kind)
        assertEquals(t0 + 29 * MINUTE_MS, rest.departAt)
        // 출발 2분 전에 알린다 (출발 시각은 분 단위로 내려 맞춘다).
        assertEquals(floorMinute(t0 + 29 * MINUTE_MS) - 2 * MINUTE_MS, rest.alarmAt)
        val start = e.drainEffects()
        assertTrue(start.none { it is Effect.Cabin })
        assertTrue(start.filterIsInstance<Effect.Speak>().single().text.contains("출발하면"))

        e.onTick(t0 + 27 * MINUTE_MS)
        val reminder = e.drainEffects()
        assertTrue(reminder.none { it is Effect.Alarm || it is Effect.Cabin })
        assertEquals(1, reminder.count { it is Effect.Speak })
        assertNotNull(e.state.rest?.remindedAt)
        assertNull(e.state.rest?.wakeStartedAt)
    }

    @Test
    fun alarmMovesWhenTrafficChangesWhileWaiting() {
        val e = engine()
        e.driveAt(t0)
        e.setDeadline(TripDeadline(t0 + 40 * MINUTE_MS, 3 * MINUTE_MS, strict = true, remainingMs = 8 * MINUTE_MS))
        e.onSpeed(t0 + 1_000, 0.0)
        e.onGear(t0 + 1_000, Gear.PARK)
        e.startRest(t0 + 2_000, RestKind.WAIT, a5)
        // 쉬는 동안 남은 길이 5분 더 막힘
        e.setDeadline(TripDeadline(t0 + 40 * MINUTE_MS, 3 * MINUTE_MS, strict = true, remainingMs = 13 * MINUTE_MS))
        e.onTick(t0 + 2_000 + 61 * SECOND_MS)
        assertEquals(floorMinute(t0 + 24 * MINUTE_MS) - 2 * MINUTE_MS, e.state.rest?.alarmAt)
        assertEquals(floorMinute(t0 + 29 * MINUTE_MS) - 2 * MINUTE_MS, e.state.rest?.previousAlarmAt)
    }

    @Test
    fun parkedPromptCanStartWaitingInsteadOfNap() {
        val e = engine()
        val at = e.parkWithoutGuidance(t0)
        e.drainEffects()
        e.restHere(at, RestKind.WAIT)
        assertEquals(Phase.RESTING, e.state.phase)
        assertEquals(RestKind.WAIT, e.state.rest?.kind)
        assertTrue(e.drainEffects().none { it is Effect.Cabin })
    }

    @Test
    fun switchingRestKindSwitchesCabin() {
        val e = engine()
        val restAt = e.restAtNearest(t0)
        e.drainEffects()
        e.setRestKind(restAt + MINUTE_MS, RestKind.WAIT)
        // 쉬는 방식만 바꿨으니 '출발'이 아니라 휴식 환경을 끄는 것이다.
        assertTrue(e.drainEffects().contains(Effect.Cabin(CabinMode.RESTORE, departing = false)))
        e.setRestKind(restAt + 2 * MINUTE_MS, RestKind.NAP)
        assertTrue(e.drainEffects().contains(Effect.Cabin(CabinMode.REST)))
    }

    @Test
    fun wakeUpTimeIsHalfOfRestUpToTenMinutes() {
        assertEquals(0L, RestTiming.wakeMsFor(4 * MINUTE_MS, 10 * MINUTE_MS))
        assertEquals(6 * MINUTE_MS, RestTiming.wakeMsFor(12 * MINUTE_MS, 10 * MINUTE_MS))
        assertEquals(10 * MINUTE_MS, RestTiming.wakeMsFor(40 * MINUTE_MS, 10 * MINUTE_MS))
        assertEquals(30 * MINUTE_MS, RestTiming.longestNapMs(40 * MINUTE_MS, 10 * MINUTE_MS))
        assertEquals(10 * MINUTE_MS, RestTiming.longestNapMs(15 * MINUTE_MS, 10 * MINUTE_MS))
        assertEquals(0L, RestTiming.longestNapMs(6 * MINUTE_MS, 10 * MINUTE_MS))

        val e = engine()
        val restAt = e.restAtNearest(t0)
        e.wakeUp(restAt + 8 * MINUTE_MS)
        assertEquals(4 * MINUTE_MS, e.state.rest?.wakeMs)
    }

    @Test
    fun shortStopDoesNotResetDrivingTime() {
        val e = engine()
        e.driveAt(t0)
        e.addDrivingTime(t0 + 1, 90 * MINUTE_MS)
        val restAt = e.restAtNearest(t0 + 1_000)
        val departAt = restAt + 5 * MINUTE_MS
        e.onGear(departAt, Gear.DRIVE)
        e.onSpeed(departAt, 100.0)
        assertTrue(e.state.continuousDrivingMs >= 90 * MINUTE_MS)
    }

    @Test
    fun asksToRestAfterTwoHoursOfDriving() {
        val e = engine()
        e.driveAt(t0)
        for (m in 1..119) e.onTick(t0 + m * MINUTE_MS)
        assertNull(e.state.prompt)
        e.onTick(t0 + 120 * MINUTE_MS)
        assertTrue(e.state.prompt is Prompt.LongDrive)

        e.dismissPrompt(t0 + 120 * MINUTE_MS + 1_000)
        e.onTick(t0 + 125 * MINUTE_MS)
        assertNull(e.state.prompt)
    }

    @Test
    fun parkingWithoutGuidanceAsksIfResting() {
        val e = engine()
        e.driveAt(t0)
        e.onSpeed(t0 + 1_000, 0.0)
        e.onGear(t0 + 1_000, Gear.PARK)
        e.onTick(t0 + 11_000)
        assertEquals(Prompt.ParkedHere, e.state.prompt)

        e.acceptPrompt(t0 + 12_000)
        assertEquals(Phase.RESTING, e.state.phase)
    }

    @Test
    fun passingTheTargetSwitchesToNextRestArea() {
        val e = engine()
        e.driveAt(t0)
        e.pressDrowsy(t0 + 1_000)
        e.onLocation(t0 + 2_000, southKm(4.8), 180.0)
        e.onLocation(t0 + 3_000, southKm(6.5), 180.0)

        assertEquals(Phase.TO_REST_AREA, e.state.phase)
        assertEquals("a25", e.state.target?.area?.id)
    }

    @Test
    fun advisesBeforeALongGapBetweenRestAreas() {
        val a60 = area("a60", southKm(60.0))
        val e = engine(a5, a60)
        e.driveAt(t0)
        e.addDrivingTime(t0 + 1, 95 * MINUTE_MS)
        e.onTick(t0 + 31 * SECOND_MS)

        val prompt = e.state.prompt
        assertTrue(prompt is Prompt.RestGapAhead)
        prompt as Prompt.RestGapAhead
        assertEquals("a5", prompt.next.area.id)
        assertEquals(41, prompt.gapMinutes)
    }

    @Test
    fun checksInWhileHeadingToRestAreaUntilQuiet() {
        val e = engine()
        e.driveAt(t0)
        e.pressDrowsy(t0 + 1_000)
        e.onTick(t0 + 1_000 + 150 * SECOND_MS)
        assertEquals(Prompt.CheckIn, e.state.prompt)

        e.answerCheckIn(t0 + 160 * SECOND_MS, CheckInAnswer.QUIET)
        assertTrue(e.state.quiet)
        e.onTick(t0 + 600 * SECOND_MS)
        assertNull(e.state.prompt)
    }

    @Test
    fun manualRestWhileStillRollingWaitsForAStopBeforeDetectingDeparture() {
        val e = engine()
        e.driveAt(t0)
        e.pressDrowsy(t0 + 1_000)
        e.startRest(t0 + 2_000) // 아직 시속 100km로 달리는 중에 '주차했어요'
        e.onTick(t0 + 3_000)
        assertEquals(Phase.RESTING, e.state.phase)

        e.onSpeed(t0 + 10_000, 0.0)
        e.onTick(t0 + 60_000)
        assertEquals(Phase.RESTING, e.state.phase)

        e.onSpeed(t0 + 120_000, 30.0)
        assertEquals(Phase.DRIVING, e.state.phase)
    }

    @Test
    fun reversingToFixTheParkingSpotDoesNotEndRest() {
        val e = engine()
        val restAt = e.restAtNearest(t0)
        e.onGear(restAt + MINUTE_MS, Gear.REVERSE)
        e.onSpeed(restAt + MINUTE_MS, 3.0)
        e.onTick(restAt + MINUTE_MS + 5_000)
        assertEquals(Phase.RESTING, e.state.phase)
    }

    @Test
    fun departButtonWhileStillInParkDoesNotAskToRestAgain() {
        val e = engine()
        val restAt = e.restAtNearest(t0)
        e.departNow(restAt + MINUTE_MS)
        assertEquals(Phase.DRIVING, e.state.phase)
        e.onTick(restAt + 2 * MINUTE_MS)
        assertNull(e.state.prompt)
    }

    @Test
    fun liveEstimateFollowsCurrentPosition() {
        val c = RestAreaFinder(listOf(a5)).findAhead(origin, 180.0, speedKmh = 100.0).best!!
        val later = c.updatedFor(southKm(3.0), 100.0)
        assertEquals(2_000.0 * RestAreaFinder.DEFAULT_DETOUR_FACTOR, later.roadMeters, 30.0)
        assertTrue(later.etaMinutes < c.etaMinutes)
    }

    @Test
    fun restSwitchesCabinAndAlarmStartsWakeUp() {
        val e = engine()
        val restAt = e.restAtNearest(t0)
        assertTrue(e.drainEffects().contains(Effect.Cabin(CabinMode.REST)))

        e.onTick(restAt + 20 * MINUTE_MS)
        assertEquals(restAt + 20 * MINUTE_MS, e.state.rest?.wakeStartedAt)
        val effects = e.drainEffects()
        assertTrue(effects.contains(Effect.Alarm(true)))
        assertTrue(effects.contains(Effect.Cabin(CabinMode.WAKE)))
    }

    @Test
    fun wakingUpEarlyCancelsTheAlarm() {
        val e = engine()
        val restAt = e.restAtNearest(t0)
        e.wakeUp(restAt + 8 * MINUTE_MS)
        assertEquals(restAt + 8 * MINUTE_MS, e.state.rest?.wakeStartedAt)
        assertTrue(e.drainEffects().contains(Effect.Cabin(CabinMode.WAKE)))

        e.onTick(restAt + 25 * MINUTE_MS)
        assertFalse(e.state.rest!!.alarmRinging)
    }

    @Test
    fun goingBackToSleepReturnsToRestMode() {
        val e = engine()
        val restAt = e.restAtNearest(t0)
        e.onTick(restAt + 20 * MINUTE_MS)
        e.drainEffects()

        e.extendRest(restAt + 20 * MINUTE_MS + 5_000, 10)
        assertNull(e.state.rest?.wakeStartedAt)
        val effects = e.drainEffects()
        assertTrue(effects.contains(Effect.Alarm(false)))
        assertTrue(effects.contains(Effect.Cabin(CabinMode.REST)))
    }

    @Test
    fun departingRightAfterWakingAsksToGoSlowAndRestoresCabin() {
        val e = engine()
        val restAt = e.restAtNearest(t0)
        e.onTick(restAt + 20 * MINUTE_MS)
        e.stopAlarm(restAt + 20 * MINUTE_MS + 10_000)
        e.drainEffects()

        val departAt = restAt + 22 * MINUTE_MS // 깬 지 2분
        e.onGear(departAt, Gear.DRIVE)
        e.onSpeed(departAt, 30.0)
        val effects = e.drainEffects()
        assertTrue(effects.contains(Effect.Cabin(CabinMode.RESTORE)))
        assertTrue(effects.any { it is Effect.Speak && it.text.contains("천천히") })
    }

    @Test
    fun departingAfterWakeUpTimeDoesNotWarn() {
        val e = engine()
        val restAt = e.restAtNearest(t0)
        e.onTick(restAt + 20 * MINUTE_MS)
        e.stopAlarm(restAt + 20 * MINUTE_MS + 10_000)
        e.drainEffects()

        e.onGear(restAt + 31 * MINUTE_MS, Gear.DRIVE)
        e.onSpeed(restAt + 31 * MINUTE_MS, 30.0)
        assertEquals(Phase.DRIVING, e.state.phase)
        assertFalse(e.drainEffects().any { it is Effect.Speak && it.text.contains("천천히") })
    }

    @Test
    fun shiftingToDriveWithoutMovingDoesNotEndRest() {
        val e = engine()
        val restAt = e.restAtNearest(t0)
        // 주차장 안에서 자리만 옮김: D단, 시속 8km, 40m
        e.onGear(restAt + MINUTE_MS, Gear.DRIVE)
        e.onSpeed(restAt + MINUTE_MS, 8.0)
        e.onLocation(restAt + MINUTE_MS + 5_000, Geo.destinationPoint(origin, 180.0, 40.0), 180.0)
        e.onGear(restAt + MINUTE_MS + 20_000, Gear.PARK)
        e.onSpeed(restAt + MINUTE_MS + 20_000, 0.0)
        e.onTick(restAt + 2 * MINUTE_MS)
        assertEquals(Phase.RESTING, e.state.phase)
        // 속도를 모르면 D단이 1분 이어질 때 출발로 본다.
        e.onSpeed(restAt + 3 * MINUTE_MS, null)
        e.onGear(restAt + 3 * MINUTE_MS, Gear.DRIVE)
        e.onTick(restAt + 3 * MINUTE_MS + 30_000)
        assertEquals(Phase.RESTING, e.state.phase)
        e.onTick(restAt + 4 * MINUTE_MS + 1_000)
        assertEquals(Phase.DRIVING, e.state.phase)
    }

    @Test
    fun stopAlarmBeforeItRingsDoesNothing() {
        val e = engine()
        val restAt = e.restAtNearest(t0)
        e.stopAlarm(restAt + MINUTE_MS)
        e.onTick(restAt + 20 * MINUTE_MS)
        assertTrue(e.state.rest!!.alarmRinging)
    }

    // ---- 온타임 모드의 출발 알림 ----

    private fun RestMateEngine.parkAt(now: Long) {
        driveAt(now)
        onSpeed(now + 1_000, 0.0)
        onGear(now + 1_000, Gear.PARK)
    }

    private fun spoken(e: RestMateEngine) = e.drainEffects().filterIsInstance<Effect.Speak>().map { it.text }

    /** 출발 알림 시각은 분 단위로 내려 맞춘다 (화면 시계와 맞게) */
    private fun floorMinute(ms: Long) = ms / MINUTE_MS * MINUTE_MS

    @Test
    fun waitingCallsAtDepartTimeThenRingsWhenItGetsLate() {
        val e = engine()
        // 도착 목표 40분 뒤, 남은 길 8분, 여유 3분 → 29분 뒤 출발
        val deadline = TripDeadline(t0 + 40 * MINUTE_MS, 3 * MINUTE_MS, strict = true, remainingMs = 8 * MINUTE_MS)
        e.setDeadline(deadline)
        e.parkAt(t0)
        e.startRest(t0 + 2_000, RestKind.WAIT, a5)
        e.drainEffects()
        e.onTick(t0 + 27 * MINUTE_MS)
        assertEquals(1, spoken(e).size) // 2분 전
        e.onTick(t0 + 29 * MINUTE_MS)
        val call = e.drainEffects()
        assertTrue(call.contains(Effect.Chime))
        assertTrue(call.filterIsInstance<Effect.Speak>().single().text.startsWith("출발할 시간이에요."))
        assertFalse(e.state.rest!!.alarmRinging)
        // 그대로 있으면: 늦기 시작할 때(목표보다 1분 늦을 때) 알람과 함께 한 번 더
        e.onTick(t0 + 32 * MINUTE_MS)
        assertTrue(spoken(e).isEmpty())
        e.onTick(t0 + 33 * MINUTE_MS)
        val late = e.drainEffects()
        assertTrue(late.contains(Effect.Alarm(true)))
        assertEquals("지금 출발해도 약 1분 늦어요.", late.filterIsInstance<Effect.Speak>().single().text)
        e.onTick(t0 + 40 * MINUTE_MS)
        assertTrue(spoken(e).isEmpty())
    }

    @Test
    fun startingToWaitWhenAlreadyLateSaysItOnce() {
        val e = engine()
        e.setDeadline(TripDeadline(t0 + 10 * MINUTE_MS, 3 * MINUTE_MS, strict = true, remainingMs = 12 * MINUTE_MS))
        e.parkAt(t0)
        e.startRest(t0 + 2_000, RestKind.WAIT, a5)
        assertEquals(listOf("지금 출발해도 약 2분 늦어요."), spoken(e))
        e.onTick(t0 + 30_000)
        assertTrue(spoken(e).isEmpty())
    }

    @Test
    fun drowsyRestSwitchedToWaitingStillRestsAWhile() {
        val e = engine()
        e.setDeadline(TripDeadline(t0 + 10 * MINUTE_MS, 3 * MINUTE_MS, strict = true, remainingMs = 8 * MINUTE_MS))
        val restAt = e.restAtNearest(t0)
        e.drainEffects()
        e.setRestKind(restAt + MINUTE_MS, RestKind.WAIT)
        val rest = e.state.rest!!
        // 레스트 모드로 와서 쉬는 중이면 출발 시각이 지났어도 10분은 쉬고 가게 한다.
        assertEquals(floorMinute(restAt + 10 * MINUTE_MS), rest.callAt)
        val speech = spoken(e).single()
        assertTrue(speech, speech.contains("쉬고 출발해 주세요"))
        e.onTick(restAt + 2 * MINUTE_MS)
        assertTrue(spoken(e).none { it.contains("출발해 주세요") || it.contains("출발할 시간") })
    }

    @Test
    fun napAfterAttentionWarningIsNotShortenedToFitArrival() {
        val e = engine()
        e.driveAt(t0)
        e.setDeadline(TripDeadline(t0 + 35 * MINUTE_MS, 3 * MINUTE_MS, strict = true, remainingMs = 10 * MINUTE_MS))
        e.onAttentionWarning(t0 + 1_000)
        e.onLocation(t0 + 2_000, southKm(4.9), 180.0)
        e.onSpeed(t0 + 3_000, 0.0)
        e.onGear(t0 + 3_000, Gear.PARK)
        e.onTick(t0 + 14_000)
        val rest = e.state.rest!!
        assertTrue(rest.warned)
        assertEquals(20 * MINUTE_MS, rest.alarmAt - rest.startedAt)
        assertNotNull(rest.lateByMs)
    }

    @Test
    fun napWithTimeToSpareSaysWhenToLeaveAndCallsThen() {
        val e = engine()
        e.driveAt(t0)
        // 도착 목표 2시간 뒤, 남은 길 40분 → 77분 뒤 출발. 낮잠 20분 + 깨우기 10분이면 47분이 남는다.
        e.setDeadline(TripDeadline(t0 + 120 * MINUTE_MS, 3 * MINUTE_MS, strict = true, remainingMs = 40 * MINUTE_MS))
        e.parkAt(t0)
        e.drainEffects()
        e.startRest(t0 + 2_000, RestKind.NAP, a5)
        val start = spoken(e).single()
        assertTrue(start, start.contains("출발은"))
        assertEquals(floorMinute(t0 + 77 * MINUTE_MS), e.state.rest!!.callAt)
        e.onTick(t0 + 2_000 + 20 * MINUTE_MS)
        e.stopAlarm(t0 + 21 * MINUTE_MS)
        e.drainEffects()
        e.onTick(t0 + 75 * MINUTE_MS)
        assertTrue(spoken(e).single().contains("출발 준비해 주세요"))
        e.onTick(t0 + 77 * MINUTE_MS)
        assertTrue(e.drainEffects().contains(Effect.Chime))
    }

    @Test
    fun jamAfterWakingMovesTheDepartureTimeAndShowsTheOldOne() {
        val e = engine()
        e.driveAt(t0)
        val d = TripDeadline(t0 + 120 * MINUTE_MS, 3 * MINUTE_MS, strict = true, remainingMs = 40 * MINUTE_MS)
        e.setDeadline(d)
        e.parkAt(t0)
        e.startRest(t0 + 2_000, RestKind.NAP, a5)
        e.onTick(t0 + 2_000 + 20 * MINUTE_MS)
        e.stopAlarm(t0 + 21 * MINUTE_MS)
        val told = e.state.rest!!.callAt!!
        assertEquals(floorMinute(t0 + 77 * MINUTE_MS), told)
        // 깬 뒤에 앞길이 6분 더 막혔다: 출발 시각을 당기고, 처음 알린 시각을 화면에 남긴다.
        e.setDeadline(d.copy(remainingMs = 46 * MINUTE_MS))
        e.onTick(t0 + 23 * MINUTE_MS)
        assertEquals(floorMinute(t0 + 71 * MINUTE_MS), e.state.rest!!.callAt)
        assertEquals(told, e.state.rest!!.callMovedFrom)
        // 2분 전 알림이 새 시각을 말하면 지운다.
        e.drainEffects()
        e.onTick(t0 + 69 * MINUTE_MS)
        assertTrue(spoken(e).single().contains("출발 준비해 주세요"))
        assertNull(e.state.rest!!.callMovedFrom)
    }

    @Test
    fun laterTargetWhileWaitingMovesTheDepartureCall() {
        val e = engine()
        val first = TripDeadline(t0 + 40 * MINUTE_MS, 3 * MINUTE_MS, strict = true, remainingMs = 8 * MINUTE_MS)
        e.setDeadline(first)
        e.parkAt(t0)
        e.startRest(t0 + 2_000, RestKind.WAIT, a5)
        e.onTick(t0 + 27 * MINUTE_MS)
        e.drainEffects()
        // 2분 전 알림 뒤에 약속이 10분 늦춰졌다.
        e.setDeadline(first.copy(targetAt = first.targetAt + 10 * MINUTE_MS))
        assertEquals(floorMinute(t0 + 39 * MINUTE_MS), e.state.rest!!.callAt)
        assertEquals(listOf("10시 1분에 출발하면 돼요.").size, spoken(e).size)
        e.onTick(t0 + 29 * MINUTE_MS)
        assertTrue(e.drainEffects().none { it == Effect.Chime })
        e.onTick(t0 + 39 * MINUTE_MS)
        assertTrue(e.drainEffects().contains(Effect.Chime))
    }

    @Test
    fun stayReminderIsSilentOnADate() {
        val e = engine()
        e.setDeadline(TripDeadline(t0 + 30 * MINUTE_MS, 0L, strict = false, remainingMs = 10 * MINUTE_MS, quiet = true))
        e.parkAt(t0)
        e.onTick(t0 + 12_000)
        e.restHere(t0 + 13_000, RestKind.STAY)
        e.drainEffects()
        e.onTick(t0 + 25 * MINUTE_MS)
        assertNotNull(e.state.rest!!.remindedAt)
        assertTrue(spoken(e).isEmpty())
    }

    // ---- 쉼터 안내 ----

    @Test
    fun askingForAFasterStopRightBeforeTheShelterKeepsIt() {
        val e = engine()
        e.driveAt(t0)
        e.pressDrowsy(t0 + 1_000)
        // 쉼터 300m 앞: 검색에서는 빠지지만 그대로 두고 곧 도착한다고 답한다.
        e.onLocation(t0 + 2_000, southKm(4.7), 180.0)
        e.drainEffects()
        e.pressDrowsy(t0 + 3_000)
        assertEquals("a5", e.state.target?.area?.id)
        assertTrue(e.drainEffects().none { it is Effect.StartGuidance })
    }

    @Test
    fun drowsyNearTheDestinationSaysRestThere() {
        val e = engine()
        e.driveAt(t0)
        e.onDestination(southKm(4.0))
        e.onRemainingRoute(3 * MINUTE_MS)
        e.drainEffects()
        e.onAttentionWarning(t0 + 1_000)
        assertEquals(Phase.DRIVING, e.state.phase)
        val speech = spoken(e).single()
        assertTrue(speech, speech.contains("목적지까지 약 3분"))
    }

    @Test
    fun noGapAdviceOrLongDriveCardRightBeforeArrival() {
        val a60 = area("a60", southKm(60.0))
        val e = engine(a5, a60)
        e.driveAt(t0)
        e.onDestination(southKm(70.0))
        e.onRemainingRoute(20 * MINUTE_MS)
        e.addDrivingTime(t0 + 1, 2 * HOUR_MS)
        e.onTick(t0 + 31 * SECOND_MS)
        assertNull(e.state.prompt)
        assertTrue(spoken(e).single().contains("도착하면 꼭 쉬어 가세요"))
    }

    @Test
    fun dismissedGapAdviceIsNotFollowedByAnotherRightAway() {
        val a60 = area("a60", southKm(60.0))
        val e = engine(a5, a60)
        e.driveAt(t0)
        e.addDrivingTime(t0 + 1, 95 * MINUTE_MS)
        e.onTick(t0 + 31 * SECOND_MS)
        assertTrue(e.state.prompt is Prompt.RestGapAhead)
        e.dismissPrompt(t0 + 40 * SECOND_MS)
        // 넘긴 뒤 30분 동안은 2시간 알림도 다른 쉼터 공백 안내도 하지 않는다.
        for (m in 1..28) {
            e.onTick(t0 + 40 * SECOND_MS + m * MINUTE_MS)
            assertNull("${m}분 뒤", e.state.prompt)
        }
    }

    @Test
    fun arrivingClearsRestCards() {
        val a60 = area("a60", southKm(60.0))
        val e = engine(a5, a60)
        e.driveAt(t0)
        e.addDrivingTime(t0 + 1, 95 * MINUTE_MS)
        e.onTick(t0 + 31 * SECOND_MS)
        assertTrue(e.state.prompt is Prompt.RestGapAhead)
        e.onTripArrived(t0 + MINUTE_MS)
        assertNull(e.state.prompt)
    }

    @Test
    fun driverAttentionTelltaleStartsGuidance() {
        val e = engine()
        e.driveAt(t0)
        e.onVehicleEvent(t0 + 1_000, VehicleEvent.DRIVER_ATTENTION)
        assertEquals(Phase.TO_REST_AREA, e.state.phase)
        assertEquals("a5", e.state.target?.area?.id)
    }

    @Test
    fun laneAssistEventsDoNotTriggerGuidanceByThemselves() {
        val e = engine()
        e.driveAt(t0)
        e.onVehicleEvent(t0 + 1_000, VehicleEvent.LANE_ASSIST)
        assertEquals(Phase.DRIVING, e.state.phase)
    }

    @Test
    fun spokenNameDropsDirectionInParentheses() {
        assertEquals("안성휴게소", RestMateText.spokenName("안성휴게소(부산방향)"))
        assertEquals("안성휴게소", RestMateText.spokenName("안성휴게소 (부산방향)"))
    }

    /** 목적지 바로 옆(100m) 쉼터에 세워 기다리기 시작한다. */
    private fun RestMateEngine.waitNextToDestination(dest: LatLng): Long {
        onDestination(dest)
        driveAt(t0, southKm(5.0))
        onSpeed(t0 + 1_000, 0.0)
        onGear(t0 + 1_000, Gear.PARK)
        startRest(t0 + 2_000, RestKind.WAIT, a5)
        return t0 + 2_000
    }

    @Test
    fun creepingTowardTheDestinationNextDoorEndsTheWait() {
        val e = engine()
        val dest = southKm(5.1)
        e.setDeadline(TripDeadline(t0 + 30 * MINUTE_MS, 3 * MINUTE_MS, strict = true, remainingMs = MINUTE_MS))
        e.waitNextToDestination(dest)
        e.drainEffects()
        // 막힌 길로 시속 8km로 목적지 쪽 40m: 15km·150m에 못 미쳐도 출발로 본다 (목적지에 닿기 전에).
        e.onGear(t0 + 60_000, Gear.DRIVE)
        e.onSpeed(t0 + 60_000, 8.0)
        e.onLocation(t0 + 70_000, southKm(5.04), 180.0)
        e.onTick(t0 + 71_000)
        assertEquals(Phase.DRIVING, e.state.phase)
        // 목적지가 코앞이라 '다시 출발하시네요'는 하지 않는다.
        assertTrue(spoken(e).isEmpty())
    }

    @Test
    fun reachingTheDestinationWhileWaitingEndsTheRestQuietly() {
        val e = engine()
        val dest = southKm(5.1)
        e.setDeadline(TripDeadline(t0 + 30 * MINUTE_MS, 3 * MINUTE_MS, strict = true, remainingMs = MINUTE_MS))
        e.waitNextToDestination(dest)
        e.drainEffects()
        // 세운 자리에서 움직이지 않았는데 도착 안내가 오면 그대로 기다린다.
        e.onTripArrived(t0 + 3 * MINUTE_MS)
        assertEquals(Phase.RESTING, e.state.phase)
        // 목적지로 옮겨 와 세웠다: 쉬기를 조용히 끝낸다 (늦음 알람이나 '다시 출발' 안내 없이).
        e.onLocation(t0 + 4 * MINUTE_MS, dest, 180.0)
        e.onTripArrived(t0 + 4 * MINUTE_MS)
        assertEquals(Phase.DRIVING, e.state.phase)
        assertTrue(e.drainEffects().none { it is Effect.Speak || it == Effect.Alarm(true) })
    }

    @Test
    fun drowsyRestCallsGentlyAndNeverRingsForLateness() {
        val e = engine()
        // 도착 목표가 빠듯한데 졸려서 레스트 모드로 쉬고, 기다리기로 바꾼다 (10분은 쉬므로 늦는다).
        e.setDeadline(TripDeadline(t0 + 15 * MINUTE_MS, 3 * MINUTE_MS, strict = true, remainingMs = 10 * MINUTE_MS))
        val start = e.restAtNearest(t0)
        e.setRestKind(start + 1_000, RestKind.WAIT)
        e.drainEffects()
        val call = e.state.rest!!.callAt!!
        e.onTick(call - 2 * MINUTE_MS)
        // 2분 전 음성은 늦는 것을 숨기지 않는다 ('도착에 맞아요' 대신 언제 닿는지).
        val notice = spoken(e).single()
        assertTrue(notice, notice.contains("쯤 도착해요"))
        e.onTick(call)
        val effects = e.drainEffects()
        assertTrue(effects.contains(Effect.Chime))
        assertEquals("충분히 쉬셨으면 천천히 출발해 주세요.", effects.filterIsInstance<Effect.Speak>().single().text)
        e.onTick(call + 10 * MINUTE_MS)
        e.onTick(call + 20 * MINUTE_MS)
        val later = e.drainEffects()
        assertTrue(later.none { it is Effect.Alarm || it is Effect.Speak || it == Effect.Chime })
    }

    @Test
    fun lateAlarmStopsByItselfThenRemindsEveryFiveMinutesForAWhile() {
        val e = engine()
        e.setDeadline(TripDeadline(t0 + 40 * MINUTE_MS, 3 * MINUTE_MS, strict = true, remainingMs = 8 * MINUTE_MS))
        e.parkAt(t0)
        e.startRest(t0 + 2_000, RestKind.WAIT, a5)
        e.onTick(t0 + 27 * MINUTE_MS)
        e.onTick(t0 + 29 * MINUTE_MS)
        e.onTick(t0 + 33 * MINUTE_MS)
        assertTrue(e.drainEffects().contains(Effect.Alarm(true)))
        // 빈 차에서 계속 울리지 않게 2분 뒤 저절로 멈춘다.
        e.onTick(t0 + 35 * MINUTE_MS)
        assertTrue(e.drainEffects().contains(Effect.Alarm(false)))
        assertFalse(e.state.rest!!.alarmRinging)
        // 그래도 그대로면 5분마다 짧게 다시 알린다.
        e.onTick(t0 + 38 * MINUTE_MS)
        val again = e.drainEffects()
        assertTrue(again.contains(Effect.Chime))
        assertEquals("지금 출발해도 약 6분 늦어요.", again.filterIsInstance<Effect.Speak>().single().text)
        e.onTick(t0 + 43 * MINUTE_MS)
        e.onTick(t0 + 48 * MINUTE_MS)
        e.drainEffects()
        // 출발 알림 뒤 20분이 지나면 일부러 머무는 것으로 보고 더 알리지 않는다.
        e.onTick(t0 + 53 * MINUTE_MS)
        assertTrue(e.drainEffects().none { it == Effect.Chime })
    }

    @Test
    fun sleepingThroughTheAlarmStartsWakeTimeWhenItIsStopped() {
        val e = engine()
        e.driveAt(t0)
        e.setDeadline(TripDeadline(t0 + 120 * MINUTE_MS, 3 * MINUTE_MS, strict = true, remainingMs = 40 * MINUTE_MS))
        e.parkAt(t0)
        e.startRest(t0 + 2_000, RestKind.NAP, a5)
        e.onTick(t0 + 2_000 + 20 * MINUTE_MS)
        e.drainEffects()
        // 15분 동안 알람을 못 듣고 자다가 끈다: 몸을 깨우는 시간은 지금부터 센다.
        val stop = t0 + 37 * MINUTE_MS
        e.stopAlarm(stop)
        assertEquals(stop, e.state.rest!!.wakeStartedAt)
        assertTrue(spoken(e).single().startsWith("막 깨셨으니"))
        // 곧바로 떠나면 천천히 가라고 한다.
        e.departNow(stop + 30_000)
        assertTrue(spoken(e).single().contains("천천히"))
    }

    @Test
    fun noLongDriveCardRightAfterArriving() {
        val e = engine()
        e.driveAt(t0)
        var t = t0
        while (t < t0 + 115 * MINUTE_MS) {
            t += 5 * MINUTE_MS
            e.onTick(t)
        }
        assertNull(e.state.prompt)
        // 도착 안내 직후 아직 D단인 틱에 2시간이 찼다: 숙소 주차장에서 '다음 쉼터에 들를까요?'를 묻지 않는다.
        e.onTripArrived(t)
        e.onTick(t + 5 * MINUTE_MS)
        e.onTick(t + 10 * MINUTE_MS)
        assertNull(e.state.prompt)
    }

    @Test
    fun jamWhileWaitingMovesTheDepartureTimeRightAwayAndSaysWhy() {
        val e = engine()
        val d = TripDeadline(t0 + 40 * MINUTE_MS, 3 * MINUTE_MS, strict = true, remainingMs = 8 * MINUTE_MS)
        e.setDeadline(d)
        e.parkAt(t0)
        e.startRest(t0 + 2_000, RestKind.WAIT, a5)
        e.onTick(t0 + 10 * MINUTE_MS)
        val before = e.state.rest!!.callAt!!
        e.drainEffects()
        // 기다리는 중 앞길이 6분 더 막혔다: 1분을 기다리지 않고 바로 출발 시각을 당긴다.
        e.setDeadline(d.copy(remainingMs = 14 * MINUTE_MS))
        assertEquals(before - 6 * MINUTE_MS, e.state.rest!!.callAt)
        assertEquals(before, e.state.rest!!.callMovedFrom)
        // 2분 전 음성이 왜 바뀌었는지 말한다.
        e.onTick(before - 8 * MINUTE_MS)
        val notice = spoken(e).single()
        assertTrue(notice, notice.startsWith("남은 길이 바뀌어 출발 시각을") && notice.contains("당겼어요"))
    }
}
