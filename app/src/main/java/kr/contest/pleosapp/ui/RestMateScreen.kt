package kr.contest.pleosapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import kr.contest.pleosapp.core.Gear
import kr.contest.pleosapp.core.HOUR_MS
import kr.contest.pleosapp.core.LatLng
import kr.contest.pleosapp.core.MINUTE_MS
import kr.contest.pleosapp.core.Phase
import kr.contest.pleosapp.core.Prompt
import kr.contest.pleosapp.core.RecoveryCheck
import kr.contest.pleosapp.core.RestArea
import kr.contest.pleosapp.core.RestAreaCandidate
import kr.contest.pleosapp.core.RestAreaType
import kr.contest.pleosapp.core.RestMateState
import kr.contest.pleosapp.core.RestMateText
import kr.contest.pleosapp.core.RestSession
import kr.contest.pleosapp.core.StabilityComparison
import kr.contest.pleosapp.core.StabilityVerdict
import kr.contest.pleosapp.data.RestAreaRepository
import kr.contest.pleosapp.sdk.VehicleExtras
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 레스트메이트 화면 (가로형 차량 디스플레이).
 * 왼쪽: 지금 상태에 맞는 내용과 큰 버튼 하나 / 오른쪽: 차량·내비 연결 상태, 최근 안내, 시연 모드
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
    val (title, dot) = when (ui.core.phase) {
        Phase.DRIVING -> "주행 중" to RM.textDim
        Phase.TO_REST_AREA -> "쉼터로 안내 중" to RM.amber
        Phase.RESTING -> "휴식 중" to RM.teal
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(14.dp).background(dot, CircleShape))
        Text(title, color = RM.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        if (ui.demoMode) Chip("시연 모드 · 모의 신호", RM.amber)
    }
}

@Composable
private fun DrivingContent(ui: UiState, actions: RestMateActions) {
    val s = ui.core
    s.prompt?.let { PromptCard(it, ui, actions) }
    s.recovery?.let { RecoveryCard(it, ui, actions) }
    Panel {
        Label("연속 주행")
        Text(
            RestMateText.drivingTime(s.continuousDrivingMs),
            color = RM.text,
            fontSize = 52.sp,
            fontWeight = FontWeight.Bold,
        )
        val fraction = s.continuousDrivingMs.toFloat() / ui.config.longDriveMs.toFloat()
        Bar(fraction, if (fraction >= 1f) RM.amber else RM.teal)
        Caption("${hoursText(ui.config.longDriveMs)}마다 한 번 쉬어 가기를 권해요.")
    }
    NextRestPanel(s)
    s.searchFailure?.let { Notice(it, RM.amber) }
}

@Composable
private fun NextRestPanel(s: RestMateState) {
    Panel {
        Label("진행 방향 가까운 쉼터")
        val next = s.nextAhead
        when {
            next != null -> {
                AreaName(next.area)
                Body(liveSummary(next, s, withType = true))
            }
            s.position == null -> Body("현재 위치를 기다리는 중이에요. 내비게이션 연결을 확인해 주세요.")
            else -> Body("진행 방향 앞쪽에서 쉼터를 찾지 못했어요.")
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
                ?: "가장 빨리 닿는 쉼터를 찾아 안내할게요.",
            primary = "쉬어 갈게요",
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
private fun RecoveryCard(rec: RecoveryCheck, ui: UiState, actions: RestMateActions) {
    val result = rec.result
    if (result == null) {
        val left = rec.departedAt + ui.config.afterWindowMs - ui.core.now
        Panel(border = RM.line) {
            Label("휴식 전후 주행 비교")
            Text(
                "다시 출발한 뒤의 주행을 살펴보는 중이에요",
                color = RM.text,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Caption(
                "남은 시간 ${clock(left)} · 시속 ${ui.config.stability.minSpeedKmh.roundToInt()}km 이상, " +
                    "깜빡이를 켜지 않은 구간만 비교해요.",
            )
            Caption(RestMateText.NOT_A_DIAGNOSIS)
            ActionButton("그만 보기", actions::dismissRecovery, Modifier.fillMaxWidth(), Tone.NEUTRAL, 64.dp, 20.sp)
        }
    } else {
        val verdict = result.verdict
        val accent = when (verdict) {
            StabilityVerdict.MORE_STABLE -> RM.teal
            StabilityVerdict.LESS_STABLE -> RM.amber
            StabilityVerdict.SIMILAR, StabilityVerdict.NOT_ENOUGH_DATA -> RM.line
        }
        Panel(color = RM.cardHigh, border = accent) {
            Label("휴식 전후 주행 비교")
            Text(RestMateText.recoveryTitle(verdict), color = RM.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Body(RestMateText.recoveryDetail(verdict, rec.nextRest))
            if (verdict != StabilityVerdict.NOT_ENOUGH_DATA) Caption(metricsLine(result))
            Caption(RestMateText.NOT_A_DIAGNOSIS)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (verdict == StabilityVerdict.LESS_STABLE) {
                    ActionButton("한 번 더 쉬기", actions::acceptRecoveryRest, Modifier.weight(1f), Tone.AMBER)
                }
                ActionButton("닫기", actions::dismissRecovery, Modifier.weight(1f), Tone.NEUTRAL)
            }
        }
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
            Label("${target.area.type.label}로 안내 중")
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
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionButton("주차했어요", actions::startRest, Modifier.weight(1f), Tone.TEAL, sub = "휴식 시작")
        ActionButton(
            text = if (showAlternatives) "목록 닫기" else "다른 쉼터",
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
    val left = rest.alarmAt - s.now
    val rested = (s.now - rest.startedAt).coerceAtLeast(0L)
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
        Caption("${wallClock(rest.alarmAt)} 알람 · 지금까지 ${RestMateText.duration(rested)} 쉬었어요")
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
    ArrivalPanel(rest, ui)
    CabinPanel(ui.extras)
    Notice("공회전 제한 지역에서는 시동을 끄고 쉬어 주세요.", RM.textDim)
}

@Composable
private fun ArrivalPanel(rest: RestSession, ui: UiState) {
    Panel {
        Label("휴식 시간별 도착 예상")
        val route = rest.remainingRouteMs
        if (route == null) {
            Body("내비에 목적지를 설정하면 쉬는 시간에 따라 언제 도착할지 보여 드려요.")
        } else {
            val prepMs = ui.config.prepMinutes * MINUTE_MS
            val options = (REST_OPTIONS + rest.plannedMinutes).distinct().sorted()
            for (m in options) {
                val departAt = maxOf(rest.startedAt + m * MINUTE_MS, ui.core.now)
                val arriveAt = departAt + prepMs + route
                val chosen = m == rest.plannedMinutes
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${m}분 쉬면",
                        color = if (chosen) RM.teal else RM.textDim,
                        fontSize = 22.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${wallClock(arriveAt)} 도착",
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
            Caption("출발 준비 ${ui.config.prepMinutes}분을 더했어요. $source")
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

@Composable
private fun BottomAction(ui: UiState, actions: RestMateActions) {
    val s = ui.core
    when (s.phase) {
        Phase.DRIVING ->
            BigButton("졸려요", "가장 빨리 닿는 쉼터로 바로 안내해요", Tone.AMBER, actions::pressDrowsy)
        Phase.TO_REST_AREA ->
            BigButton("더 졸려요", "더 빨리 닿는 곳이 있으면 바꿔서 안내해요", Tone.AMBER, actions::pressDrowsy)
        Phase.RESTING ->
            if (s.rest?.alarmRinging == true) {
                BigButton("알람 끄기", null, Tone.AMBER, actions::stopAlarm)
            } else {
                BigButton("출발할게요", "D단으로 바꾸면 자동으로 출발로 알아봐요", Tone.TEAL, actions::departNow)
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
        Text("레스트메이트", color = RM.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Caption("졸릴 때 쉼터 안내부터 휴식, 다시 출발까지")
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
        Rule()
        DemoControls(ui, actions)
        Rule()
        Caption("쉼터 데이터 ${ui.restAreaCount}곳 · ${RestAreaRepository.ATTRIBUTION}")
    }
}

@Composable
private fun DemoControls(ui: UiState, actions: RestMateActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("시연 모드", color = RM.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Caption("모의 차량 신호 · 대기 시간 단축")
        }
        Switch(
            checked = ui.demoMode,
            onCheckedChange = actions::setDemoMode,
            colors = SwitchDefaults.colors(checkedThumbColor = RM.onAmber, checkedTrackColor = RM.amber),
        )
    }
    if (ui.demoMode) {
        ui.demoScenario?.let { Caption("출발: $it") }
        Caption(
            if (ui.demoDriving) {
                "모의 주행 중 · 시속 100km · ${if (ui.demoWobbly) "조향 흔들림" else "조향 안정"}"
            } else {
                "정차 중"
            },
            if (ui.demoDriving) RM.amber else RM.textDim,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("주행 · 흔들림", { actions.demoDrive(true) }, Modifier.weight(1f))
            SmallButton("주행 · 안정", { actions.demoDrive(false) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("P단 주차", actions::demoPark, Modifier.weight(1f))
            SmallButton("+2시간 운전", actions::demoAddDrivingTime, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("주의 경고(모의)", actions::demoAttentionWarning, Modifier.weight(1f))
            SmallButton(
                "휴식 건너뛰기",
                actions::demoSkipRest,
                Modifier.weight(1f),
                enabled = ui.core.phase == Phase.RESTING,
            )
        }
        SmallButton("처음 위치로", actions::demoRestart, Modifier.fillMaxWidth())
        Caption("위치는 실제 시간보다 6배 빠르게 움직여요. 시연 모드의 차량 신호는 실제 값이 아니에요.")
    }
}

// ---------------------------------------------------------------- 공통 요소

private enum class Tone(val container: Color, val content: Color) {
    TEAL(RM.teal, RM.onTeal),
    AMBER(RM.amber, RM.onAmber),
    NEUTRAL(RM.button, RM.text),
}

@Composable
private fun ActionButton(
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
private fun BigButton(text: String, sub: String?, tone: Tone, onClick: () -> Unit) {
    ActionButton(text, onClick, Modifier.fillMaxWidth(), tone, minHeight = 112.dp, textSize = 40.sp, sub = sub)
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    ActionButton(text, onClick, modifier, Tone.NEUTRAL, minHeight = 56.dp, textSize = 17.sp, enabled = enabled)
}

@Composable
private fun Panel(
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
private fun Label(text: String, color: Color = RM.textDim) {
    Text(text, color = color, fontSize = 17.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun Body(text: String, color: Color = RM.text) {
    Text(text, color = color, fontSize = 21.sp, lineHeight = 30.sp)
}

@Composable
private fun Caption(text: String, color: Color = RM.textDim) {
    Text(text, color = color, fontSize = 16.sp, lineHeight = 23.sp)
}

@Composable
private fun Notice(text: String, color: Color) {
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
private fun Chip(text: String, color: Color) {
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
private fun Bar(fraction: Float, color: Color) {
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
private fun Rule() {
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

private fun liveSummary(c: RestAreaCandidate, s: RestMateState, withType: Boolean = false): String {
    val live = c.updatedFor(s.position, s.speedKmh)
    val parts = buildList<String> {
        if (withType) add(c.area.type.label)
        add("약 ${RestMateText.distance(live.roadMeters)}")
        add("${RestMateText.etaMinutes(live.etaMinutes)}분")
    }
    return parts.joinToString(" · ")
}

private fun metricsLine(c: StabilityComparison): String {
    fun f(v: Double) = String.format(Locale.KOREA, "%.1f", v)
    return "분당 조향 보정 ${f(c.before.steeringCorrectionsPerMin)}회 → ${f(c.after.steeringCorrectionsPerMin)}회 · " +
        "10분당 제동 ${f(c.before.brakePressesPer10Min)}회 → ${f(c.after.brakePressesPer10Min)}회"
}

private fun cabinHints(e: VehicleExtras): List<String> = buildList {
    if (e.windowsAllClosed == true && e.recirculationOn == true) {
        add("창문이 닫혀 있고 내기 순환 중이에요. 쉬는 동안 한 번 환기해 주세요.")
    } else if (e.recirculationOn == true) {
        add("다시 출발할 때 외기 유입으로 바꾸면 실내 공기를 바꾸는 데 도움이 돼요.")
    }
    val lowFuel = (e.fuelPercent ?: 100) <= 15 || (e.rangeKm ?: Double.MAX_VALUE) <= 80.0
    if (lowFuel) add("연료가 얼마 남지 않았어요. 이 휴게소에 주유소가 있는지 확인해 보세요.")
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

private fun clock(ms: Long): String {
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

private fun wallClock(ms: Long): String = SimpleDateFormat("a h:mm", Locale.KOREA).format(Date(ms))

// ---------------------------------------------------------------- 미리보기 (Android Studio)

private object PreviewActions : RestMateActions {
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
    override fun departNow() = Unit
    override fun dismissRecovery() = Unit
    override fun acceptRecoveryRest() = Unit
    override fun setDemoMode(on: Boolean) = Unit
    override fun demoDrive(wobbly: Boolean) = Unit
    override fun demoPark() = Unit
    override fun demoAddDrivingTime() = Unit
    override fun demoAttentionWarning() = Unit
    override fun demoSkipRest() = Unit
    override fun demoRestart() = Unit
}

private val previewArea = RestArea(
    id = "preview",
    name = "안성휴게소(부산방향)",
    type = RestAreaType.SERVICE_AREA,
    position = LatLng(37.03, 127.17),
    onExpressway = true,
)

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun DrivingPreview() {
    val now = 1_700_000_000_000L
    val next = RestAreaCandidate(previewArea, 8_000.0, 10_000.0, 6.0)
    RestMateTheme {
        RestMateScreen(
            UiState(
                core = RestMateState(
                    now = now,
                    continuousDrivingMs = 2 * HOUR_MS + 5 * MINUTE_MS,
                    gear = Gear.DRIVE,
                    speedKmh = 98.0,
                    onExpressway = true,
                    nextAhead = next,
                    prompt = Prompt.LongDrive(125, next),
                ),
                demoMode = true,
                restAreaCount = 1119,
                lastMessage = "2시간 넘게 운전하셨어요. 다음 쉼터에 들를까요?",
            ),
            PreviewActions,
        )
    }
}

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun RestingPreview() {
    val now = 1_700_000_000_000L
    RestMateTheme {
        RestMateScreen(
            UiState(
                core = RestMateState(
                    now = now,
                    phase = Phase.RESTING,
                    gear = Gear.PARK,
                    rest = RestSession(
                        startedAt = now - 6 * MINUTE_MS,
                        plannedMinutes = 20,
                        alarmAt = now + 14 * MINUTE_MS,
                        area = previewArea,
                        remainingRouteMs = 2 * HOUR_MS + 40 * MINUTE_MS,
                    ),
                ),
                extras = VehicleExtras(windowsAllClosed = true, recirculationOn = true, fuelPercent = 42, rangeKm = 310.0),
            ),
            PreviewActions,
        )
    }
}
