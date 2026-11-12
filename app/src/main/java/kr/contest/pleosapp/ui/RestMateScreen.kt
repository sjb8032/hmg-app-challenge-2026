package kr.contest.pleosapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.contest.pleosapp.app.RestMateActions
import kr.contest.pleosapp.app.RestMateController
import kr.contest.pleosapp.app.UiState
import kr.contest.pleosapp.core.CheckInAnswer
import kr.contest.pleosapp.core.CourseOption
import kr.contest.pleosapp.core.DemoTrips
import kr.contest.pleosapp.core.DriveMode
import kr.contest.pleosapp.core.DriveState
import kr.contest.pleosapp.core.DriveStep
import kr.contest.pleosapp.core.DriveText
import kr.contest.pleosapp.core.Gear
import kr.contest.pleosapp.core.HOUR_MS
import kr.contest.pleosapp.core.LatLng
import kr.contest.pleosapp.core.MINUTE_MS
import kr.contest.pleosapp.core.Mood
import kr.contest.pleosapp.core.Phase
import kr.contest.pleosapp.core.Place
import kr.contest.pleosapp.core.PlaceKind
import kr.contest.pleosapp.core.Prompt
import kr.contest.pleosapp.core.RestArea
import kr.contest.pleosapp.core.RestAreaCandidate
import kr.contest.pleosapp.core.RestAreaType
import kr.contest.pleosapp.core.RestEnvironment
import kr.contest.pleosapp.core.RestMateState
import kr.contest.pleosapp.core.RestMateText
import kr.contest.pleosapp.core.RestSession
import kr.contest.pleosapp.core.RoadStyle
import kr.contest.pleosapp.data.CityRestPlaces
import kr.contest.pleosapp.data.RestAreaRepository
import kr.contest.pleosapp.sdk.VehicleExtras
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 드라이브메이트 화면 (가로형 차량 디스플레이).
 * 왼쪽: 지금 상태에 맞는 내용과, 아래에 늘 있는 '쉬었다 가요' 버튼 / 오른쪽: 차량·내비 연결 상태, 최근 안내, 시연 모드
 *
 * 운전 중에 읽을 글은 짧게, 누를 곳은 크게 둔다. 화면이 하는 말은 음성으로도 나간다.
 */
@Composable
fun RestMateScreen(ui: UiState, actions: RestMateActions) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(RM.background)
            .padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        MainPanel(ui, actions, Modifier.weight(1f).fillMaxHeight())
        SidePanel(ui, actions, Modifier.width(360.dp).fillMaxHeight())
    }
}

// ---------------------------------------------------------------- 왼쪽: 상태별 화면

@Composable
private fun MainPanel(ui: UiState, actions: RestMateActions, modifier: Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Header(ui)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (ui.core.phase) {
                Phase.DRIVING -> DrivingContent(ui, actions)
                Phase.TO_REST_AREA -> GuidanceContent(ui, actions)
                Phase.RESTING -> RestingContent(ui, actions)
            }
        }
        BottomAction(ui, actions)
    }
}

@Composable
private fun Header(ui: UiState) {
    val waking = ui.core.rest?.wakeStartedAt != null
    val (title, dot) = when (ui.core.phase) {
        Phase.DRIVING -> driveTitle(ui.drive)
        Phase.TO_REST_AREA -> "쉬었다 가요 · 쉴 곳으로 안내 중" to RM.amber
        Phase.RESTING -> if (waking) "쉬었다 가요 · 깨우는 중" to RM.amber else "쉬었다 가요 · 휴식 중" to RM.teal
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(14.dp).background(dot, CircleShape))
        Text(
            title,
            color = RM.text,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (ui.demoMode) Chip("시연 모드", RM.amber)
        if (ui.now > 0L) Text(DriveText.clock(ui.now), color = RM.textDim, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun driveTitle(d: DriveState): Pair<String, Color> {
    val mode = d.mode
    val color = modeColor(mode)
    return when (d.step) {
        DriveStep.HOME -> "어떻게 갈까요?" to RM.textDim
        DriveStep.SETUP -> (mode?.title ?: "드라이브") to color
        DriveStep.CHOOSE -> "${mode?.title ?: "드라이브"} · 코스 고르기" to color
        DriveStep.ACTIVE -> "${mode?.title ?: "드라이브"} · 달리는 중" to color
        DriveStep.ARRIVED -> "도착했어요" to color
    }
}

internal fun modeColor(mode: DriveMode?): Color = when (mode) {
    DriveMode.ON_TIME -> RM.teal
    DriveMode.LONGER -> RM.violet
    null -> RM.textDim
}

internal fun modeTone(mode: DriveMode?): Tone = when (mode) {
    DriveMode.LONGER -> Tone.VIOLET
    else -> Tone.TEAL
}

@Composable
private fun DrivingContent(ui: UiState, actions: RestMateActions) {
    val s = ui.core
    s.prompt?.let { PromptCard(it, ui, actions) }
    DriveContent(ui, actions)
    s.searchFailure?.let { Notice(it, RM.amber) }
}

/** 처음 화면 아래에 두는 연속 주행 시간과 진행 방향 가까운 쉼터 */
@Composable
internal fun RestStatusPanel(ui: UiState) {
    val s = ui.core
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Label("연속 주행")
                Text(RestMateText.drivingTime(s.continuousDrivingMs), color = RM.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                val fraction = s.continuousDrivingMs.toFloat() / ui.config.longDriveMs.toFloat()
                Bar(fraction, if (fraction >= 1f) RM.amber else RM.teal)
                Caption("${hoursText(ui.config.longDriveMs)}마다 한 번 쉬어 가기를 권해요.")
            }
            Column(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Label("진행 방향 가까운 쉴 곳")
                val next = s.nextAhead
                when {
                    next != null -> {
                        Text(
                            next.area.name,
                            color = RM.text,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Caption(liveSummary(next, s, withType = true))
                    }
                    s.position == null -> Caption("현재 위치를 기다리는 중이에요. 내비게이션 연결을 확인해 주세요.")
                    else -> Caption("진행 방향 앞쪽에서 쉴 곳을 찾지 못했어요.")
                }
            }
        }
    }
}

@Composable
private fun PromptCard(prompt: Prompt, ui: UiState, actions: RestMateActions) {
    val s = ui.core
    when (prompt) {
        is Prompt.LongDrive -> ChoiceCard(
            title = "${prompt.drivenMinutes / 60}시간 넘게 운전하셨어요",
            detail = prompt.next?.let { "${it.area.name} · ${liveSummary(it, s)}" }
                ?: "가장 빨리 닿는 쉴 곳을 찾아 안내할게요.",
            primary = "쉬었다 갈게요",
            onPrimary = actions::acceptPrompt,
            secondary = "${RestMateText.duration(ui.config.longDriveSnoozeMs)} 뒤에",
            onSecondary = actions::dismissPrompt,
        )
        is Prompt.RestGapAhead -> ChoiceCard(
            title = prompt.gapMinutes?.let { "앞 쉼터를 지나면 ${it}분 동안 쉴 곳이 없어요" }
                ?: "앞 쉼터를 지나면 한동안 쉴 곳이 없어요",
            detail = "${prompt.next.area.name} · ${liveSummary(prompt.next, s)}",
            primary = "여기서 쉬기",
            onPrimary = actions::acceptPrompt,
            secondary = "괜찮아요",
            onSecondary = actions::dismissPrompt,
        )
        Prompt.ParkedHere -> ChoiceCard(
            title = "주차하셨네요. 여기서 쉬어 갈까요?",
            detail = "휴식 타이머와 알람을 켜 드릴게요.",
            primary = "휴식 시작",
            onPrimary = actions::acceptPrompt,
            secondary = "아니요",
            onSecondary = actions::dismissPrompt,
            tone = Tone.TEAL,
        )
        Prompt.CheckIn -> CheckInCard(actions)
    }
}

@Composable
private fun ChoiceCard(
    title: String,
    detail: String?,
    primary: String,
    onPrimary: () -> Unit,
    secondary: String,
    onSecondary: () -> Unit,
    tone: Tone = Tone.AMBER,
) {
    Panel(color = RM.cardHigh, border = tone.container) {
        Text(title, color = RM.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        if (detail != null) Body(detail)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(primary, onPrimary, Modifier.weight(1f), tone)
            ActionButton(secondary, onSecondary, Modifier.weight(1f), Tone.NEUTRAL)
        }
    }
}

@Composable
private fun CheckInCard(actions: RestMateActions) {
    Panel(color = RM.cardHigh, border = RM.teal) {
        Text("지금은 어떠세요?", color = RM.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val tall = 88.dp
            ActionButton("괜찮아요", { actions.answerCheckIn(CheckInAnswer.OK) }, Modifier.weight(1f), Tone.TEAL, tall)
            ActionButton("더 졸려요", { actions.answerCheckIn(CheckInAnswer.MORE_DROWSY) }, Modifier.weight(1f), Tone.AMBER, tall)
            ActionButton("조용히", { actions.answerCheckIn(CheckInAnswer.QUIET) }, Modifier.weight(1f), Tone.NEUTRAL, tall)
        }
        Caption("'조용히'를 누르면 도착할 때까지 다시 묻지 않아요.")
    }
}

@Composable
private fun GuidanceContent(ui: UiState, actions: RestMateActions) {
    val s = ui.core
    var showAlternatives by remember { mutableStateOf(false) }
    val target = s.target
    if (target != null) {
        val live = target.updatedFor(s.position, s.speedKmh)
        val near = (s.targetRemainingMeters ?: Double.MAX_VALUE) <= ui.config.nearArrivalMeters
        Panel(border = RM.amber) {
            Label("${DriveText.toward(target.area.type.label)} 안내 중")
            AreaName(target.area)
            Text(
                "약 ${RestMateText.distance(live.roadMeters)} · ${RestMateText.etaMinutes(live.etaMinutes)}분",
                color = RM.text,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (near) {
                Caption("곧 진입로예요. 내비 안내에 집중해 주세요.", RM.amber)
            } else {
                Caption("도착해서 P단으로 주차하면 휴식 화면으로 바뀌어요.")
            }
        }
    }
    val prompt = s.prompt
    when {
        prompt == Prompt.CheckIn -> CheckInCard(actions)
        prompt != null -> PromptCard(prompt, ui, actions)
        s.quiet -> Notice("조용히 모드예요. 도착할 때까지 상태를 묻지 않아요.", RM.textDim)
    }
    if (ui.drive.step == DriveStep.ACTIVE) {
        Notice("쉬었다 가는 동안 도착 시각 맞추기는 잠시 멈춰요. 다시 출발하면 이어서 맞춰요.", RM.textDim)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionButton("주차했어요", actions::startRest, Modifier.weight(1f), Tone.TEAL, sub = "휴식 시작")
        ActionButton(
            text = if (showAlternatives) "목록 닫기" else "다른 곳",
            onClick = { showAlternatives = !showAlternatives },
            modifier = Modifier.weight(1f),
            sub = if (showAlternatives) null else "${s.alternatives.size}곳",
            enabled = s.alternatives.isNotEmpty(),
        )
        ActionButton("안내 취소", actions::cancelGuidance, Modifier.weight(1f))
    }
    if (showAlternatives) {
        for (c in s.alternatives) {
            AlternativeRow(c, s) {
                showAlternatives = false
                actions.selectRestArea(c)
            }
        }
    }
    s.searchFailure?.let { Notice(it, RM.amber) }
}

@Composable
private fun AlternativeRow(c: RestAreaCandidate, s: RestMateState, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RM.card, RoundedCornerShape(18.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                c.area.name,
                color = RM.text,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Caption(liveSummary(c, s, withType = true))
        }
        ActionButton("여기로", onSelect, Modifier.width(150.dp), Tone.TEAL, 64.dp)
    }
}

@Composable
private fun RestingContent(ui: UiState, actions: RestMateActions) {
    val s = ui.core
    val rest = s.rest ?: return
    val wokeAt = rest.wakeStartedAt
    if (wokeAt != null && !rest.alarmRinging) {
        WakeUpPanel(wokeAt, ui)
    } else {
        RestTimerPanel(rest, s.now)
    }
    val drive = ui.drive
    val target = drive.targetAt
    if (drive.step == DriveStep.ACTIVE && target != null) {
        Notice("${DriveText.clock(target)} 도착에 맞춰 깨워 드려요. 시간을 바꾸면 도착 시각도 달라져요.", RM.teal)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (m in REST_OPTIONS) {
            ActionButton(
                "${m}분",
                { actions.setRestMinutes(m) },
                Modifier.weight(1f),
                if (rest.plannedMinutes == m) Tone.TEAL else Tone.NEUTRAL,
            )
        }
        ActionButton("+10분", { actions.extendRest(10) }, Modifier.weight(1f))
    }
    if (rest.plannedMinutes > 20) {
        Notice("20분 넘게 자면 깊은 잠에 들어 깬 뒤 더 멍할 수 있어요. 깨우는 시간을 넉넉히 두세요.", RM.amber)
    }
    RestEnvironmentPanel(ui, actions)
    ArrivalPanel(rest, ui)
    CabinPanel(ui.extras)
    Notice("공회전 제한 지역에서 시동을 켠 채 오래 머물면 과태료가 나올 수 있어요.", RM.textDim)
}

@Composable
private fun RestTimerPanel(rest: RestSession, now: Long) {
    val left = rest.alarmAt - now
    val rested = (now - rest.startedAt).coerceAtLeast(0L)
    Panel(border = if (rest.alarmRinging) RM.amber else RM.teal) {
        Label(rest.area?.name ?: "지금 있는 곳에서 휴식")
        when {
            rest.alarmRinging ->
                Text("휴식 시간이 끝났어요", color = RM.amber, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            left > 0 ->
                Text(clock(left), color = RM.text, fontSize = 80.sp, fontWeight = FontWeight.Bold)
            else ->
                Text("쉬는 시간이 지났어요", color = RM.text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        }
        val total = (rest.alarmAt - rest.startedAt).coerceAtLeast(1L)
        Bar(rested.toFloat() / total.toFloat(), RM.teal)
        Caption("${DriveText.clock(rest.alarmAt)} 알람 · 지금까지 ${RestMateText.duration(rested)} 쉬었어요")
        if (!rest.alarmRinging) {
            Caption("커피는 자기 전에 마시면 깰 무렵 효과가 나요 (효과까지 20분쯤 걸려요).")
        }
    }
}

/** 깬 뒤 바로 출발하지 않도록 몸을 깨우는 시간 (수면 관성). */
@Composable
private fun WakeUpPanel(wokeAt: Long, ui: UiState) {
    val left = wokeAt + ui.config.wakeUpMs - ui.core.now
    val ready = left <= 0
    Panel(border = if (ready) RM.teal else RM.amber) {
        Label("깬 직후에는 반응이 느릴 수 있어요")
        if (ready) {
            Text("이제 출발해도 좋아요", color = RM.teal, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        } else {
            Text(clock(left), color = RM.text, fontSize = 72.sp, fontWeight = FontWeight.Bold)
            Caption("${RestMateText.duration(ui.config.wakeUpMs)} 동안 몸을 깨운 뒤 출발해 주세요.")
        }
        val elapsed = (ui.core.now - wokeAt).toFloat() / ui.config.wakeUpMs.toFloat()
        Bar(elapsed, if (ready) RM.teal else RM.amber)
        for (item in WAKE_CHECKLIST) Body("· $item")
    }
}

@Composable
private fun RestEnvironmentPanel(ui: UiState, actions: RestMateActions) {
    val env = ui.env
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("휴식 환경")
                Caption("쉬는 동안 공조와 무드등을 바꾸고, 출발하면 원래대로 돌려요.")
            }
            Switch(
                checked = env.enabled,
                onCheckedChange = { actions.setRestEnvironment(env.copy(enabled = it)) },
                colors = SwitchDefaults.colors(checkedThumbColor = RM.onTeal, checkedTrackColor = RM.teal),
            )
        }
        if (env.enabled) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("온도", color = RM.textDim, fontSize = 19.sp, modifier = Modifier.width(72.dp))
                ActionButton(
                    "−",
                    { actions.setRestEnvironment(env.copy(restTemperatureC = (env.restTemperatureC - 0.5f).coerceAtLeast(17f))) },
                    Modifier.width(88.dp),
                    minHeight = 60.dp,
                )
                Text(
                    tempText(env.restTemperatureC),
                    color = RM.text,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(110.dp),
                )
                ActionButton(
                    "+",
                    { actions.setRestEnvironment(env.copy(restTemperatureC = (env.restTemperatureC + 0.5f).coerceAtMost(30f))) },
                    Modifier.width(88.dp),
                    minHeight = 60.dp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(
                    "무드등 은은하게",
                    { actions.setRestEnvironment(env.copy(dimLight = !env.dimLight)) },
                    Modifier.weight(1f),
                    if (env.dimLight) Tone.TEAL else Tone.NEUTRAL,
                    minHeight = 60.dp,
                    textSize = 19.sp,
                )
                ActionButton(
                    "바깥 공기",
                    { actions.setRestEnvironment(env.copy(freshAir = !env.freshAir)) },
                    Modifier.weight(1f),
                    if (env.freshAir) Tone.TEAL else Tone.NEUTRAL,
                    minHeight = 60.dp,
                    textSize = 19.sp,
                )
            }
        }
        ui.cabinNote?.let { Caption(it) }
    }
}

@Composable
private fun ArrivalPanel(rest: RestSession, ui: UiState) {
    Panel {
        Label("휴식 시간별 도착 예상")
        val route = rest.remainingRouteMs
        if (route == null) {
            Body("내비에 목적지를 설정하면 쉬는 시간에 따라 언제 도착할지 보여 드려요.")
        } else {
            val wakeMs = ui.config.wakeUpMs
            val options = (REST_OPTIONS + rest.plannedMinutes).distinct().sorted()
            for (m in options) {
                val departAt = maxOf(rest.startedAt + m * MINUTE_MS, ui.core.now)
                val arriveAt = departAt + wakeMs + route
                val chosen = m == rest.plannedMinutes
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${m}분 쉬면",
                        color = if (chosen) RM.teal else RM.textDim,
                        fontSize = 22.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${DriveText.clock(arriveAt)} 도착",
                        color = if (chosen) RM.teal else RM.text,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            val source = if (rest.remainingRouteEstimated) {
                "목적지까지 남은 시간은 직선거리로 추정했어요."
            } else {
                "목적지까지 남은 시간은 휴식을 시작할 때 내비 기준이에요."
            }
            Caption("깨우는 시간(${RestMateText.duration(wakeMs)})을 더했어요. $source")
        }
    }
}

@Composable
private fun CabinPanel(extras: VehicleExtras) {
    val items = buildList<Pair<String, String>> {
        extras.windowsAllClosed?.let { add("창문" to if (it) "모두 닫힘" else "열려 있음") }
        extras.recirculationOn?.let { add("공조" to if (it) "내기 순환" else "외기 유입") }
        extras.fuelPercent?.let { add("연료" to "$it%") }
        extras.rangeKm?.let { add("주행 가능" to "${it.roundToInt()}km") }
    }
    if (items.isEmpty()) return
    Panel {
        Label("차 안 상태")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((name, value) in items) {
                Column(Modifier.weight(1f)) {
                    Caption(name)
                    Text(value, color = RM.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        for (hint in cabinHints(extras)) Body(hint)
    }
}

/** 화면 아래 큰 버튼. 달리는 중에는 어느 화면에서나 '쉬었다 가요'를 누를 수 있다. */
@Composable
private fun BottomAction(ui: UiState, actions: RestMateActions) {
    val s = ui.core
    when (s.phase) {
        Phase.DRIVING ->
            BigButton("쉬었다 가요", "가까운 휴게소·졸음쉼터·주차장으로 바로 안내해요", Tone.AMBER, actions::pressDrowsy)
        Phase.TO_REST_AREA ->
            BigButton("더 빨리 쉴래요", "더 빨리 닿는 곳이 있으면 바꿔서 안내해요", Tone.AMBER, actions::pressDrowsy)
        Phase.RESTING -> {
            val rest = s.rest
            when {
                rest?.alarmRinging == true ->
                    BigButton("알람 끄기", null, Tone.AMBER, actions::stopAlarm)
                rest?.wakeStartedAt == null ->
                    BigButton("일어났어요", "알람 전에 깼다면 깨우기를 시작해요", Tone.TEAL, actions::wakeUp)
                else ->
                    BigButton("출발할게요", "D단으로 바꾸면 자동으로 출발로 알아봐요", Tone.TEAL, actions::departNow)
            }
        }
    }
}

// ---------------------------------------------------------------- 오른쪽: 상태·시연

@Composable
private fun SidePanel(ui: UiState, actions: RestMateActions, modifier: Modifier) {
    val s = ui.core
    Column(
        modifier = modifier
            .background(RM.panel, RoundedCornerShape(24.dp))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("드라이브메이트", color = RM.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Caption("도착 시각에 맞춰 달리고, 졸리면 쉬었다 가요")
        Rule()
        StatusRow("기어", gearLabel(s.gear))
        StatusRow("속도", s.speedKmh?.let { "${it.roundToInt()} km/h" } ?: "—")
        StatusRow(
            "도로",
            when (s.onExpressway) {
                true -> "고속도로"
                false -> "일반 도로"
                null -> "—"
            },
        )
        StatusRow("연속 주행", RestMateText.drivingTime(s.continuousDrivingMs))
        StatusRow("차량 SDK", ui.connection.vehicle, connectionColor(ui.connection.vehicle))
        StatusRow("내비 SDK", ui.connection.navi, connectionColor(ui.connection.navi))
        StatusRow(
            "음성 안내",
            if (ui.speechAvailable) "켜짐" else "화면 안내만",
            if (ui.speechAvailable) RM.teal else RM.textDim,
        )
        Rule()
        Label("최근 안내")
        Text(ui.lastMessage ?: "아직 없어요", color = RM.text, fontSize = 19.sp, lineHeight = 27.sp)
        ui.naviNote?.let { Caption(it) }
        ui.cabinNote?.let { Caption(it) }
        Rule()
        DemoControls(ui, actions)
        Rule()
        Caption("쉼터 데이터 ${ui.restAreaCount}곳 · ${RestAreaRepository.ATTRIBUTION}")
        Caption("${CityRestPlaces.ATTRIBUTION} · 경치 지점은 팀이 고른 표본")
    }
}

@Composable
private fun DemoControls(ui: UiState, actions: RestMateActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("시연 모드", color = RM.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Caption("모의 내비 · 빨리 가는 가상 시계")
        }
        Switch(
            checked = ui.demoMode,
            onCheckedChange = actions::setDemoMode,
            colors = SwitchDefaults.colors(checkedThumbColor = RM.onAmber, checkedTrackColor = RM.amber),
        )
    }
    if (!ui.demoMode) return
    val trip = ui.demoTrip
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (t in DemoTrips.ALL) {
            ActionButton(
                t.title.substringBefore(" ·"),
                { actions.selectDemoTrip(t.id) },
                Modifier.weight(1f),
                if (trip?.id == t.id) Tone.AMBER else Tone.NEUTRAL,
                minHeight = 52.dp,
                textSize = 17.sp,
            )
        }
    }
    if (trip != null) Caption("${trip.title} · 가상 시계 ×${trip.timeScale.roundToInt()}")
    val speed = ui.core.speedKmh?.roundToInt() ?: 0
    Caption(
        when {
            ui.demoDriving && ui.demoJammed -> "모의 주행 중 · 시속 ${speed}km (정체)"
            ui.demoDriving -> "모의 주행 중 · 시속 ${speed}km"
            else -> "서 있는 중"
        },
        if (ui.demoDriving) RM.amber else RM.textDim,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton("출발 · 주행", actions::demoDrive, Modifier.weight(1f))
        SmallButton("P단 주차", actions::demoPark, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton("막힘 +6분", actions::demoTraffic, Modifier.weight(1f), enabled = ui.demoDriving)
        SmallButton("10분 건너뛰기", { actions.demoSkip(10) }, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton("졸음 경고(모의)", actions::demoAttentionWarning, Modifier.weight(1f))
        SmallButton("+2시간 운전", actions::demoAddDrivingTime, Modifier.weight(1f))
    }
    SmallButton(
        "휴식 시간 건너뛰기",
        actions::demoSkipRest,
        Modifier.fillMaxWidth(),
        enabled = ui.core.phase == Phase.RESTING && ui.core.rest?.wakeStartedAt == null,
    )
    SmallButton("처음부터", actions::demoRestart, Modifier.fillMaxWidth())
    Caption("시연 모드의 위치·속도·시각은 실제 값이 아니에요.")
}

// ---------------------------------------------------------------- 공통 요소

internal enum class Tone(val container: Color, val content: Color) {
    TEAL(RM.teal, RM.onTeal),
    VIOLET(RM.violet, RM.onViolet),
    AMBER(RM.amber, RM.onAmber),
    NEUTRAL(RM.button, RM.text),
}

@Composable
internal fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.NEUTRAL,
    minHeight: Dp = 76.dp,
    textSize: TextUnit = 24.sp,
    sub: String? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = minHeight),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = tone.container,
            contentColor = tone.content,
            disabledContainerColor = RM.cardHigh,
            disabledContentColor = RM.textDim.copy(alpha = 0.5f),
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, fontSize = textSize, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            if (sub != null) {
                Text(sub, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.alpha(0.8f))
            }
        }
    }
}

@Composable
internal fun BigButton(text: String, sub: String?, tone: Tone, onClick: () -> Unit) {
    ActionButton(text, onClick, Modifier.fillMaxWidth(), tone, minHeight = 112.dp, textSize = 40.sp, sub = sub)
}

@Composable
internal fun SmallButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    ActionButton(text, onClick, modifier, Tone.NEUTRAL, minHeight = 56.dp, textSize = 17.sp, enabled = enabled)
}

@Composable
internal fun Panel(
    modifier: Modifier = Modifier,
    color: Color = RM.card,
    border: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val base = modifier.fillMaxWidth().background(color, shape)
    val framed = if (border != null) base.border(2.dp, border, shape) else base
    Column(modifier = framed.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
internal fun Label(text: String, color: Color = RM.textDim) {
    Text(text, color = color, fontSize = 17.sp, fontWeight = FontWeight.Medium)
}

@Composable
internal fun Body(text: String, color: Color = RM.text) {
    Text(text, color = color, fontSize = 21.sp, lineHeight = 30.sp)
}

@Composable
internal fun Caption(text: String, color: Color = RM.textDim) {
    Text(text, color = color, fontSize = 16.sp, lineHeight = 23.sp)
}

@Composable
internal fun Notice(text: String, color: Color) {
    Text(text, color = color, fontSize = 19.sp, lineHeight = 27.sp, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun AreaName(area: RestArea) {
    Text(
        area.name,
        color = RM.text,
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun Chip(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .border(1.5.dp, color, CircleShape)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
internal fun Bar(fraction: Float, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .background(RM.line, CircleShape),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(10.dp)
                .background(color, CircleShape),
        )
    }
}

@Composable
internal fun Rule() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(RM.line),
    )
}

@Composable
private fun StatusRow(label: String, value: String, valueColor: Color = RM.text) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = RM.textDim, fontSize = 17.sp, modifier = Modifier.width(92.dp))
        Text(
            value,
            color = valueColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------- 문구·계산

private val REST_OPTIONS = listOf(15, 20, 30)

private val WAKE_CHECKLIST = listOf(
    "무드등을 밝히고 시원한 바깥 바람을 넣었어요",
    "차에서 내려 2~3분 걷거나 스트레칭해요",
    "물 한 잔 마시고 밝은 곳을 봐요",
)

private fun tempText(c: Float): String =
    if (c % 1f == 0f) "${c.toInt()}°C" else String.format(Locale.ROOT, "%.1f°C", c)

private fun liveSummary(c: RestAreaCandidate, s: RestMateState, withType: Boolean = false): String {
    val live = c.updatedFor(s.position, s.speedKmh)
    val parts = buildList<String> {
        if (withType) add(c.area.type.label)
        add("약 ${RestMateText.distance(live.roadMeters)}")
        add("${RestMateText.etaMinutes(live.etaMinutes)}분")
    }
    return parts.joinToString(" · ")
}

private fun cabinHints(e: VehicleExtras): List<String> = buildList {
    if (e.windowsAllClosed == true && e.recirculationOn == true) {
        add("창문이 닫혀 있고 내기 순환 중이에요. 쉬는 동안 한 번 환기해 주세요.")
    } else if (e.recirculationOn == true) {
        add("다시 출발할 때 외기 유입으로 바꾸면 실내 공기를 바꾸는 데 도움이 돼요.")
    }
    val lowFuel = (e.fuelPercent ?: 100) <= 15 || (e.rangeKm ?: Double.MAX_VALUE) <= 80.0
    if (lowFuel) add("연료가 얼마 남지 않았어요. 쉬는 곳에 주유소가 있는지 확인해 보세요.")
}

private fun hoursText(ms: Long): String =
    if (ms % HOUR_MS == 0L) "${ms / HOUR_MS}시간" else RestMateText.drivingTime(ms)

private fun gearLabel(gear: Gear): String = when (gear) {
    Gear.PARK -> "P"
    Gear.REVERSE -> "R"
    Gear.NEUTRAL -> "N"
    Gear.DRIVE -> "D"
    Gear.UNKNOWN -> "—"
}

private fun connectionColor(status: String): Color = when {
    status == RestMateController.CONNECTED -> RM.teal
    status.startsWith("실패") -> RM.red
    else -> RM.textDim
}

/** 남은 시간 표시: "12:05", "1:02:03" */
internal fun clock(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) + 999L) / 1000L
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    val sec = total % 60L
    return if (h > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", m, sec)
    }
}

// ---------------------------------------------------------------- 미리보기 (Android Studio)

internal object PreviewActions : RestMateActions {
    override fun pressDrowsy() = Unit
    override fun selectRestArea(candidate: RestAreaCandidate) = Unit
    override fun cancelGuidance() = Unit
    override fun answerCheckIn(answer: CheckInAnswer) = Unit
    override fun acceptPrompt() = Unit
    override fun dismissPrompt() = Unit
    override fun startRest() = Unit
    override fun setRestMinutes(minutes: Int) = Unit
    override fun extendRest(minutes: Int) = Unit
    override fun stopAlarm() = Unit
    override fun wakeUp() = Unit
    override fun departNow() = Unit
    override fun setRestEnvironment(env: RestEnvironment) = Unit
    override fun openMode(mode: DriveMode) = Unit
    override fun driveBack() = Unit
    override fun setDriveDestination(place: Place) = Unit
    override fun shiftTarget(minutes: Int) = Unit
    override fun setExtraMinutes(minutes: Int) = Unit
    override fun setMood(mood: Mood) = Unit
    override fun setRoadStyle(style: RoadStyle) = Unit
    override fun planCourses() = Unit
    override fun chooseCourse(option: CourseOption) = Unit
    override fun goDirect() = Unit
    override fun extendCourse() = Unit
    override fun finishDrive() = Unit
    override fun setDemoMode(on: Boolean) = Unit
    override fun selectDemoTrip(id: String) = Unit
    override fun demoDrive() = Unit
    override fun demoPark() = Unit
    override fun demoTraffic() = Unit
    override fun demoSkip(minutes: Int) = Unit
    override fun demoAddDrivingTime() = Unit
    override fun demoAttentionWarning() = Unit
    override fun demoSkipRest() = Unit
    override fun demoRestart() = Unit
}

private val previewArea = RestArea(
    id = "preview",
    name = "평창 휴게소 (강릉 방향)",
    type = RestAreaType.SERVICE_AREA,
    position = LatLng(37.6058, 128.4534),
    onExpressway = true,
)

internal val PREVIEW_NOW = 1_700_000_000_000L

internal val previewPlaces = listOf(
    Place("집", LatLng(37.5145, 127.1059), PlaceKind.HOME),
    Place("회사", LatLng(37.5219, 126.9245), PlaceKind.OFFICE),
    Place("아이 학원", LatLng(37.4946, 127.0576), PlaceKind.BOOKMARK),
)

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun HomePreview() {
    val next = RestAreaCandidate(previewArea, 8_000.0, 10_000.0, 6.0)
    RestMateTheme {
        RestMateScreen(
            UiState(
                now = PREVIEW_NOW,
                core = RestMateState(
                    now = PREVIEW_NOW,
                    continuousDrivingMs = 65 * MINUTE_MS,
                    gear = Gear.DRIVE,
                    speedKmh = 32.0,
                    onExpressway = false,
                    nextAhead = next,
                ),
                drive = DriveState(
                    now = PREVIEW_NOW,
                    naviDestination = Place("광화문 약속 장소", LatLng(37.5717, 126.9768), PlaceKind.CURRENT),
                    naviRemainingMs = 19 * MINUTE_MS,
                    places = previewPlaces,
                ),
                demoMode = true,
                demoTrip = DemoTrips.CITY,
                restAreaCount = 1132,
            ),
            PreviewActions,
        )
    }
}

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun RestingPreview() {
    RestMateTheme {
        RestMateScreen(
            UiState(
                now = PREVIEW_NOW,
                core = RestMateState(
                    now = PREVIEW_NOW,
                    phase = Phase.RESTING,
                    gear = Gear.PARK,
                    rest = RestSession(
                        startedAt = PREVIEW_NOW - 6 * MINUTE_MS,
                        plannedMinutes = 20,
                        alarmAt = PREVIEW_NOW + 14 * MINUTE_MS,
                        area = previewArea,
                        remainingRouteMs = 40 * MINUTE_MS,
                    ),
                ),
                extras = VehicleExtras(windowsAllClosed = true, recirculationOn = true, fuelPercent = 42, rangeKm = 310.0),
            ),
            PreviewActions,
        )
    }
}
