package kr.contest.pleosapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.contest.pleosapp.app.UiState
import kr.contest.pleosapp.core.CourseKind
import kr.contest.pleosapp.core.DriveConfig
import kr.contest.pleosapp.core.DriveMode
import kr.contest.pleosapp.core.DriveState
import kr.contest.pleosapp.core.DriveText
import kr.contest.pleosapp.core.FitStatus
import kr.contest.pleosapp.core.MINUTE_MS
import kr.contest.pleosapp.core.RestKind
import kr.contest.pleosapp.core.RestMateText
import kr.contest.pleosapp.core.RestTiming
import kr.contest.pleosapp.core.SECOND_MS
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 도착 목표보다 이만큼 일찍 닿도록 맞춘다 (DriveConfig.marginMs) */
internal const val MARGIN_MINUTES = 3

private val LATE_OBSERVE_MS = DriveConfig().lateObserveMs

// ---------------------------------------------------------------- 달리는 중: 도착 시각 막대

/**
 * 온타임·롱웨이 모드로 달리는 중: 출발부터 목표까지의 시간 위에 지금 · 도착 예상 · 목표 · 들렀다 가는 곳을 놓는다.
 * 늦을 것 같으면 도착 예상이 목표 너머(호박색 구간)로 넘어가고, 길을 바꾸면 여유 구간(하늘색)으로 돌아오는 것이 보인다.
 */
@Composable
internal fun TimeTrack(ui: UiState) {
    val d = ui.drive
    val now = ui.now
    val target = d.targetAt
    val eta = d.arrivalEstimate
    val longer = d.mode == DriveMode.LONGER
    val color = fitColor(d.status, longer)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (target == null || eta == null) {
            Body("도착 시각을 계산하는 중이에요", RM.textDim)
        } else {
            val gap = DriveText.gapMs(eta, target)
            val compact = LocalScreenSize.current.compact
            val big = if (compact) 34.sp else 44.sp
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 24.dp)) {
                Stat("도착 예상", DriveText.clock(eta), color, valueSize = big)
                Stat(if (longer) "예정" else "목표", DriveText.clock(target), RM.text, valueSize = big)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Chip(
                        if (longer) DriveText.fitLonger(d.status, gap) else DriveText.fit(d.status, gap, (MARGIN_MINUTES + 2) * MINUTE_MS),
                        color,
                        fontSize = if (compact) 17.sp else 20.sp,
                    )
                }
            }
            TrackBar(
                start = min(ui.driveStartedAt ?: now, now),
                now = now,
                eta = eta,
                target = target,
                stop = stopWindow(d),
                margin = !longer,
                accent = modeColor(d.mode),
                etaColor = color,
                targetLabel = if (longer) "예정" else "목표",
            )
            Explain(ui)
        }
    }
}

/** 기다리는 코스: 그곳에 닿는 시각 → 떠나는 시각 */
private fun stopWindow(d: DriveState): Pair<Long, Long>? {
    if (!d.viaActive) return null
    val a = d.stopArriveAt ?: return null
    val b = d.stopDepartAt ?: return null
    return a to b
}

/** 지금 무슨 일이 일어나는지 한 줄 (보는 사람이 기능을 바로 알 수 있게) */
@Composable
private fun Explain(ui: UiState) {
    val d = ui.drive
    val target = d.targetAt ?: return
    val eta = d.arrivalEstimate ?: return
    val gap = DriveText.gapMs(eta, target)
    val lateSince = d.lateSince
    val viaName = d.chosen?.via?.firstOrNull()?.name
    val restName = d.chosen?.restArea?.name?.let { RestMateText.spokenName(it) }
    val stopDepart = d.stopDepartAt
    val extend = d.extendOption
    if (lateSince != null && d.viaActive && !d.paused) {
        WatchBanner(ui.now - lateSince)
        return
    }
    val (text, color) = when {
        d.paused -> "쉬었다 가는 동안 시간 맞추기를 잠시 멈췄어요. 다시 출발하면 이어서 맞춰요." to RM.amber
        // '자리 없음'으로 한 바퀴 도는 중: 늦어도 길을 줄이지 않는다 (세울 곳이 없어 도는 것이라)
        d.circling && gap > 30 * SECOND_MS ->
            "세울 곳이 없어 한 바퀴 돌고 ${DriveText.clock(eta)}쯤 다시 와요 · 목표보다 약 ${DriveText.duration(gap)} 늦어요." to RM.amber
        d.circling -> "세울 곳이 없어 한 바퀴 돌고 ${DriveText.clock(eta)}쯤 다시 와요." to RM.textDim
        d.mode != DriveMode.LONGER && d.status == FitStatus.LATE ->
            "바로 가도 약 ${DriveText.duration(gap)} 늦어요. 서두르지 말고 안전하게 가세요." to RM.amber
        d.extendOffered && extend != null -> {
            val how = if (extend.kind == CourseKind.REST_STOP) "들렀다 기다리기" else "조금 더 돌기"
            "${DriveText.duration(gap)} 일찍 닿을 것 같아요 · 남는 시간은 '$how'로 채울 수 있어요." to RM.sand
        }
        d.mode == DriveMode.LONGER -> longerExplain(d) to RM.textDim
        d.viaActive && d.chosen?.kind == CourseKind.REST_STOP && stopDepart != null && restName != null ->
            "${restName}에서 기다렸다 ${DriveText.clock(stopDepart)}에 출발하면 목표 ${MARGIN_MINUTES}분 전에 닿아요." to RM.textDim
        // 경치 코스가 남는 시간을 다 못 채웠으면 '맞춰요'라고 하지 않는다 (위 칩의 'N분 일찍'과 같은 말로)
        d.viaActive && viaName != null && d.status == FitStatus.EARLY ->
            "${DriveText.withObject(viaName)} 거쳐 가도 약 ${DriveText.duration(gap)} 일찍 닿아요. 지나고 나서 더 채울 곳이 있으면 권할게요." to RM.textDim
        d.viaActive && viaName != null ->
            "${DriveText.withObject(viaName)} 거쳐 남는 시간을 채우고, 목표 ${MARGIN_MINUTES}분 전에 닿게 맞춰요." to RM.textDim
        d.status == FitStatus.EARLY -> "목표보다 약 ${DriveText.duration(gap)} 일찍 닿아요." to RM.textDim
        gap <= 30 * SECOND_MS -> "목표 시각 안에 도착해요. 길이 막히면 바로 다시 맞춰요." to RM.textDim
        else -> "약 ${DriveText.duration(gap)} 늦을 수 있어요." to RM.amber
    }
    Caption(text, color)
}

/**
 * 롱웨이 모드 한 줄: 고른 코스가 실제로 더 달리는 시간 (바란 시간과 다르면 둘 다 쓴다).
 * 경유지를 지났거나 '바로 집으로'로 뺐으면 코스 이야기 대신 지금 어디로 가는지.
 */
private fun longerExplain(d: DriveState): String {
    val dest = DriveText.toward(d.destination?.name ?: "목적지")
    val chosen = d.chosen
    return when {
        d.viaActive && chosen != null -> {
            val added = DriveText.minutes(chosen.addedMs)
            val lead = if (abs(added - d.extraMinutes) <= LONGER_CLOSE_MINUTES) {
                "${d.extraMinutes}분 더 달리는 코스예요."
            } else {
                "약 ${added}분 더 달리는 코스예요 (바란 시간 ${d.extraMinutes}분)."
            }
            "$lead 예정보다 많이 늦어지면 짧은 코스로 바꿔요."
        }
        d.viaReached -> "경유지를 지나 $dest 가요. 더 달리고 싶으면 '+15분 더'를 눌러요."
        else -> "$dest 바로 가요. 더 달리고 싶으면 '+15분 더'를 눌러요."
    }
}

/** 롱웨이 코스가 바란 시간과 이만큼(분) 안이면 바란 시간 그대로 말한다 */
private const val LONGER_CLOSE_MINUTES = 3

/**
 * 늦을 것 같아 지켜보는 중: 잠깐 막힌 것에 흔들리지 않게 1분 30초 지켜본 뒤 길을 바꾼다.
 * 남은 초와 막대로 보여 줘서 '왜 아직 안 바꾸는지'가 보이게 한다.
 */
@Composable
private fun WatchBanner(elapsedMs: Long) {
    val left = ((LATE_OBSERVE_MS - elapsedMs) / SECOND_MS).coerceAtLeast(0L)
    val fraction = (elapsedMs.toFloat() / LATE_OBSERVE_MS.toFloat()).coerceIn(0f, 1f)
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RM.amber.copy(alpha = 0.12f))
            .border(1.dp, RM.amber.copy(alpha = 0.5f), shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "늦을 것 같아 지켜보는 중",
                color = RM.amber,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text("${left}초 남음", color = RM.amber, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, style = NumStyle)
        }
        Bar(fraction, RM.amber)
        Caption("잠깐 막힌 것일 수 있어 1분 30초 지켜봐요. 그래도 늦으면 지금 위치에서 더 짧은 길로 바꿔요.", RM.text)
    }
}

@Composable
private fun TrackBar(
    start: Long,
    now: Long,
    eta: Long,
    target: Long,
    stop: Pair<Long, Long>?,
    margin: Boolean,
    accent: Color,
    etaColor: Color,
    targetLabel: String,
) {
    val end = maxOf(target, eta, stop?.second ?: 0L) + 2 * MINUTE_MS
    val from = min(start, now)
    val span = max(end - from, 10 * MINUTE_MS).toFloat()
    BoxWithConstraints(Modifier.fillMaxWidth().height(86.dp)) {
        val w = constraints.maxWidth.toFloat()
        val density = LocalDensity.current
        val pad = with(density) { 14.dp.toPx() }
        val cy = with(density) { 40.dp.toPx() }
        val below = with(density) { 26.dp.toPx() }
        fun x(t: Long): Float = pad + ((t - from) / span).coerceIn(0f, 1f) * (w - 2 * pad)
        Canvas(Modifier.fillMaxSize()) {
            val barH = 10.dp.toPx()
            val r = CornerRadius(barH / 2, barH / 2)
            drawRoundRect(RM.line, Offset(pad, cy - barH / 2), Size(w - 2 * pad, barH), r)
            // 목표를 넘는 구간 (늦음)
            drawRect(RM.amber.copy(alpha = 0.18f), Offset(x(target), cy - barH / 2), Size(max(0f, x(end) - x(target)), barH))
            // 목표 바로 앞 여유 구간
            if (margin) {
                val m0 = x(target - MARGIN_MINUTES * MINUTE_MS)
                drawRect(RM.sky.copy(alpha = 0.35f), Offset(m0, cy - barH / 2), Size(max(0f, x(target) - m0), barH))
            }
            // 지나온 시간
            drawRoundRect(accent.copy(alpha = 0.75f), Offset(pad, cy - barH / 2), Size(max(0f, x(now) - pad), barH), r)
            // 들렀다 가는 곳에서 머무는 시간
            if (stop != null) {
                val s0 = x(stop.first)
                val s1 = x(stop.second)
                drawRoundRect(
                    RM.amber,
                    Offset(s0, cy - 9.dp.toPx()),
                    Size(max(4.dp.toPx(), s1 - s0), 18.dp.toPx()),
                    CornerRadius(6.dp.toPx()),
                )
            }
            // 목표: 깃발
            val tx = x(target)
            drawLine(RM.text, Offset(tx, cy - 22.dp.toPx()), Offset(tx, cy + 12.dp.toPx()), strokeWidth = 2.5.dp.toPx())
            val flag = Path().apply {
                moveTo(tx, cy - 22.dp.toPx())
                lineTo(tx + 13.dp.toPx(), cy - 17.dp.toPx())
                lineTo(tx, cy - 12.dp.toPx())
                close()
            }
            drawPath(flag, RM.text)
            // 지금
            drawCircle(RM.text, 6.dp.toPx(), Offset(x(now), cy))
            // 도착 예상
            val ex = x(eta)
            drawCircle(etaColor.copy(alpha = 0.25f), 17.dp.toPx(), Offset(ex, cy))
            drawCircle(RM.text, 10.dp.toPx(), Offset(ex, cy))
            drawCircle(etaColor, 7.dp.toPx(), Offset(ex, cy))
        }
        val pins = ArrayList<Pin>()
        // 출발 시각 (지금과 붙어 있으면 뺀다)
        if (x(now) - x(from) > with(density) { 90.dp.toPx() }) {
            pins += TextPin(Offset(x(from) + with(density) { 24.dp.toPx() }, cy + below), "${shortClock(from)} 출발", RM.textFaint)
        }
        // 지금과 도착 예상이 붙어 있으면 '지금'은 뺀다
        if (abs(x(eta) - x(now)) > with(density) { 72.dp.toPx() }) pins += TextPin(Offset(x(now), cy + below), "지금", RM.textDim)
        pins += TextPin(Offset(x(eta), cy + below), "도착 예상", etaColor)
        if (stop != null && abs(x(stop.first) - x(eta)) > with(density) { 80.dp.toPx() }) {
            pins += TextPin(Offset(x(stop.first), cy + below), "기다리기", RM.amber)
        }
        pins += TextPin(Offset(x(target) + with(density) { 30.dp.toPx() }, cy - with(density) { 30.dp.toPx() }), targetLabel, RM.text)
        PinLayer(pins, Modifier.fillMaxSize())
    }
}

/** "6:32" (오전·오후 없이) */
private fun shortClock(ms: Long): String = DriveText.shortClockOfDay(DriveText.minuteOfDay(ms))

/** 도착 예상 색. 롱웨이 모드는 마감이 없어 늦어도 경고 색을 쓰지 않는다. */
internal fun fitColor(status: FitStatus, longer: Boolean): Color = if (longer) {
    when (status) {
        FitStatus.EARLY -> RM.sky
        FitStatus.UNKNOWN -> RM.textDim
        else -> RM.sand
    }
} else {
    when (status) {
        FitStatus.ON_TIME -> RM.sky
        FitStatus.EARLY -> RM.sand
        FitStatus.LATE -> RM.amber
        FitStatus.UNKNOWN -> RM.textDim
    }
}

// ---------------------------------------------------------------- 쉬는 중: 시간표

/**
 * 쉬는 동안의 시간표: 눈 붙이기(또는 기다리기) → 깨우기 → 출발 → 도착, 그리고 목표.
 * 온타임 모드에서 알람을 도착 시각에 어떻게 맞췄는지 한눈에 보인다.
 */
@Composable
internal fun RestTrack(ui: UiState) {
    val rest = ui.core.rest ?: return
    val now = ui.now
    val target = rest.targetAt
    val route = rest.remainingRouteMs
    val nap = rest.kind == RestKind.NAP
    val segments = ArrayList<Segment>()
    val depart: Long
    if (nap) {
        val napEnd = rest.wakeStartedAt ?: rest.alarmAt
        val wakeMs = if (rest.wakeStartedAt != null) rest.wakeMs else RestTiming.wakeMsFor(rest.alarmAt - rest.startedAt, ui.config.wakeUpMs)
        val wakeEnd = napEnd + wakeMs
        segments += Segment(rest.startedAt, napEnd, "눈 붙이기", RM.sky)
        if (wakeMs > 0) segments += Segment(napEnd, wakeEnd, "깨우기", RM.amber)
        val call = rest.callAt
        if (call != null && call > wakeEnd + MINUTE_MS) segments += Segment(wakeEnd, call, "기다리기", RM.textFaint)
        depart = max(max(wakeEnd, call ?: wakeEnd), now)
    } else {
        val restEnd = rest.callAt ?: rest.alarmAt
        segments += Segment(rest.startedAt, restEnd, if (rest.kind == RestKind.STAY) "머물기" else "기다리기", RM.sky)
        depart = max(restEnd, now)
    }
    val arrive = route?.let { depart + it }
    if (arrive != null) segments += Segment(depart, arrive, "주행", RM.blue)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(if (rest.kind == RestKind.NAP) "쉬는 시간표" else "기다리는 시간표", modifier = Modifier.weight(1f))
            if (target != null && arrive != null) {
                val gap = DriveText.gapMs(arrive, target)
                val text = when {
                    gap > 30 * SECOND_MS -> "약 ${DriveText.duration(gap)} 늦어요"
                    gap < -30 * SECOND_MS -> "${DriveText.duration(gap)} 여유"
                    else -> "정각"
                }
                Chip(text, if (gap > 30 * SECOND_MS) RM.amber else RM.sky)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (nap) Stat("알람", DriveText.clock(rest.alarmAt), RM.sky, Modifier.weight(1f), valueSize = 26.sp)
            Stat("출발", DriveText.clock(depart), RM.text, Modifier.weight(1f), valueSize = 26.sp)
            if (arrive != null) Stat("도착 예상", DriveText.clock(arrive), RM.blue, Modifier.weight(1f), valueSize = 26.sp)
            if (target != null) Stat(if (rest.strictTarget) "목표" else "예정", DriveText.clock(target), RM.text, Modifier.weight(1f), valueSize = 26.sp)
        }
        SegmentBar(segments, now, target)
    }
}

private data class Segment(val from: Long, val to: Long, val label: String, val color: Color)

@Composable
private fun SegmentBar(segments: List<Segment>, now: Long, target: Long?) {
    if (segments.isEmpty()) return
    val from = segments.minOf { it.from }
    val end = maxOf(segments.maxOf { it.to }, target ?: 0L) + MINUTE_MS
    val span = max(end - from, 10 * MINUTE_MS).toFloat()
    BoxWithConstraints(Modifier.fillMaxWidth().height(64.dp)) {
        val w = constraints.maxWidth.toFloat()
        val density = LocalDensity.current
        val pad = with(density) { 10.dp.toPx() }
        val cy = with(density) { 20.dp.toPx() }
        fun x(t: Long): Float = pad + ((t - from) / span).coerceIn(0f, 1f) * (w - 2 * pad)
        Canvas(Modifier.fillMaxSize()) {
            val h = 18.dp.toPx()
            val r = CornerRadius(6.dp.toPx())
            drawRoundRect(RM.line, Offset(pad, cy - h / 2), Size(w - 2 * pad, h), r)
            for (s in segments) {
                val x0 = x(s.from)
                val x1 = x(s.to)
                if (x1 - x0 > 1f) drawRoundRect(s.color.copy(alpha = 0.85f), Offset(x0, cy - h / 2), Size(x1 - x0, h), r)
            }
            if (target != null) {
                val tx = x(target)
                drawLine(RM.text, Offset(tx, cy - 16.dp.toPx()), Offset(tx, cy + 16.dp.toPx()), strokeWidth = 2.5.dp.toPx())
            }
            val nx = x(now)
            drawLine(RM.text.copy(alpha = 0.9f), Offset(nx, cy - 13.dp.toPx()), Offset(nx, cy + 13.dp.toPx()), strokeWidth = 2.dp.toPx())
            drawCircle(RM.text, 5.dp.toPx(), Offset(nx, cy - 13.dp.toPx()))
        }
        val minWidth = with(density) { 56.dp.toPx() }
        val labelY = cy + with(density) { 26.dp.toPx() }
        val pins = ArrayList<Pin>()
        for (s in segments) {
            if (x(s.to) - x(s.from) > minWidth) pins += TextPin(Offset((x(s.from) + x(s.to)) / 2f, labelY), s.label, s.color)
        }
        PinLayer(pins, Modifier.fillMaxSize())
    }
}
