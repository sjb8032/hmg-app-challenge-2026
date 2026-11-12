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
        e.onSpeed(t0 + 2_000, 0.0)
        e.onGear(t0 + 2_000, Gear.PARK)

        e.onTick(t0 + 11_000)
        assertEquals(Phase.TO_REST_AREA, e.state.phase)
        e.onTick(t0 + 12_000)
        assertEquals(Phase.RESTING, e.state.phase)
        assertEquals("a5", e.state.rest?.area?.id)
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
        // 출발 2분 전에 알린다.
        assertEquals(t0 + 27 * MINUTE_MS, rest.alarmAt)
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
        assertEquals(t0 + 22 * MINUTE_MS, e.state.rest?.alarmAt)
        assertEquals(t0 + 27 * MINUTE_MS, e.state.rest?.previousAlarmAt)
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
        assertTrue(e.drainEffects().contains(Effect.Cabin(CabinMode.RESTORE)))
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
        assertFalse(e.drainEffects().any { it is Effect.Speak && it.text.contains("천천히") })
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
}
