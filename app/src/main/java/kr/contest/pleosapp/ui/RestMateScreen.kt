package kr.contest.pleosapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.contest.pleosapp.app.AutoCue
import kr.contest.pleosapp.app.RestMateActions
import kr.contest.pleosapp.app.RestMateController
import kr.contest.pleosapp.app.UiState
import kr.contest.pleosapp.core.CheckInAnswer
import kr.contest.pleosapp.core.DriveMode
import kr.contest.pleosapp.core.DriveState
import kr.contest.pleosapp.core.DriveStep
import kr.contest.pleosapp.core.DriveText
import kr.contest.pleosapp.core.MINUTE_MS
import kr.contest.pleosapp.core.Phase
import kr.contest.pleosapp.core.Prompt
import kr.contest.pleosapp.core.RestArea
import kr.contest.pleosapp.core.RestAreaCandidate
import kr.contest.pleosapp.core.RestKind
import kr.contest.pleosapp.core.RestMateState
import kr.contest.pleosapp.core.RestMateText
import kr.contest.pleosapp.core.RestSession
import kr.contest.pleosapp.core.RestTiming
import kr.contest.pleosapp.core.SECOND_MS
import kr.contest.pleosapp.sdk.VehicleExtras
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 드라이브메이트 화면 (가로형 차량 디스플레이).
 * 왼쪽: 지금 상태에 맞는 내용(넓으면 내용 | 미니맵 두 칸), 음성 안내 자막, 늘 있는 '레스트 모드' 버튼
 * 오른쪽: 차 상태·연결·안내 기록·시연 조작
 *
 * 운전 중에 읽을 글은 짧게, 누를 곳은 크게 둔다. 화면이 하는 말은 음성으로도 나간다.
 */
@Composable
fun RestMateScreen(ui: UiState, actions: RestMateActions) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideWidth = when {
            maxWidth >= 1500.dp -> 400.dp
            maxWidth >= 1200.dp -> 320.dp
            else -> 300.dp
        }
        val mainWidth = maxWidth - sideWidth - 72.dp
        // 미니맵은 남는 높이를 채운다 (머리말·아래 버튼·여백을 뺀 만큼, 280~640dp)
        // 자동 시연 해설이 떠 있으면 그만큼 미니맵을 낮춘다
        val narration = if (ui.auto != null) NARRATION_HEIGHT else 0.dp
        val screen = ScreenSize(
            wide = mainWidth >= 840.dp,
            mapHeight = (maxHeight - 560.dp - narration).coerceIn(280.dp, 640.dp),
            compact = mainWidth < 1100.dp,
        )
        CompositionLocalProvider(LocalScreenSize provides screen) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // 장소 찾기·도착 시각 고르기 창은 왼쪽 화면 위에 띄운다 (오른쪽 차 상태·시연 조작은 그대로)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    MainPanel(ui, actions, Modifier.fillMaxSize())
                    ui.sheet?.let { InputSheetOverlay(it, ui, actions, Modifier.fillMaxSize()) }
                }
                SidePanel(ui, actions, Modifier.width(sideWidth).fillMaxHeight())
            }
        }
    }
}

// ---------------------------------------------------------------- 왼쪽: 상태별 화면

@Composable
private fun MainPanel(ui: UiState, actions: RestMateActions, modifier: Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Header(ui)
        AutoNarrationSlot(ui)
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
        CaptionSlot(ui)
        BottomAction(ui, actions)
    }
}

@Composable
private fun Header(ui: UiState) {
    val (title, dot) = headerTitle(ui)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val compact = LocalScreenSize.current.compact
        Dot(dot, 14.dp)
        Text(
            title,
            color = RM.text,
            fontSize = if (compact) 26.sp else 30.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!compact && ui.core.phase == Phase.DRIVING) StepIndicator(ui.drive.step, ui.drive.mode)
        if (ui.demoMode) {
            // 출발 전에는 가상 시계가 멈춰 있음을 보여 준다 (시각이 왜 안 가는지 알 수 있게)
            val scale = ui.demoTrip?.timeScale?.roundToInt()
            val text = when {
                ui.demoClockHeld -> "시연 · 서 있어 시계 멈춤"
                scale != null -> "시연 · 시계 ×$scale"
                else -> "시연 모드"
            }
            Chip(text, if (ui.demoClockHeld) RM.textDim else RM.amber)
        }
        if (ui.now > 0L) {
            Text(DriveText.clockNow(ui.now), color = RM.text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, style = NumStyle)
        }
    }
}

private fun headerTitle(ui: UiState): Pair<String, Color> {
    val rest = ui.core.rest
    val wokeAt = rest?.wakeStartedAt
    val waking = wokeAt != null
    // 깨우는 시간이 끝났으면 출발을 기다리는 중 (늦어서 다시 울리는 알람은 깨우기가 아니다)
    val wakeDone = rest != null && wokeAt != null && ui.core.now >= wokeAt + rest.wakeMs &&
        (!rest.alarmRinging || rest.lateWarnedAt != null)
    return when (ui.core.phase) {
        Phase.DRIVING -> driveTitle(ui.drive)
        Phase.TO_REST_AREA -> "레스트 모드 · 쉴 곳으로 안내 중" to RM.amber
        Phase.RESTING -> when {
            // 온타임 모드로 계획한 곳에서 기다리는 것은 졸려서 쉬는 것과 구분한다.
            rest?.kind == RestKind.WAIT && rest.strictTarget && !rest.fatigue -> "온타임 모드 · 기다리는 중" to RM.sky
            rest?.kind == RestKind.WAIT -> "레스트 모드 · 기다리는 중" to RM.sky
            rest?.kind == RestKind.STAY -> "레스트 모드 · 잠깐 머무는 중" to RM.sand
            wakeDone -> "레스트 모드 · 출발 준비" to RM.sky
            waking -> "레스트 모드 · 깨우는 중" to RM.amber
            else -> "레스트 모드 · 휴식 중" to RM.sky
        }
    }
}

private fun driveTitle(d: DriveState): Pair<String, Color> {
    val mode = d.mode
    val color = modeColor(mode)
    return when (d.step) {
        DriveStep.HOME -> "어떻게 갈까요?" to RM.textDim
        DriveStep.SETUP -> (mode?.title ?: "드라이브") to color
        DriveStep.CHOOSE -> "${mode?.title ?: "드라이브"} · 코스 고르기" to color
        // '달리는 중'이라고 하면 출발 전(신호 대기·D단으로 서 있음)과 맞지 않아 내비처럼 '안내 중'이라고 쓴다
        DriveStep.ACTIVE -> "${mode?.title ?: "드라이브"} · 안내 중" to color
        DriveStep.ARRIVED -> "도착했어요" to color
    }
}

@Composable
private fun DrivingContent(ui: UiState, actions: RestMateActions) {
    val s = ui.core
    s.prompt?.let { PromptCard(it, ui, actions) }
    DriveContent(ui, actions)
    s.searchFailure?.let { Notice(it, RM.amber) }
}

/** 처음 화면에 두는 연속 주행 시간과 진행 방향 가까운 쉼터 */
@Composable
internal fun RestStatusPanel(ui: UiState) {
    val s = ui.core
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Label("연속 주행")
                Text(RestMateText.drivingTime(s.continuousDrivingMs), color = RM.text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                val fraction = s.continuousDrivingMs.toFloat() / ui.config.longDriveMs.toFloat()
                Bar(fraction, if (fraction >= 1f) RM.amber else RM.sky)
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
                            fontSize = 22.sp,
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
        Prompt.ParkedHere -> ParkedCard(ui, actions)
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MoonGlyph(tone.container, Modifier.padding(top = 2.dp).width(30.dp).height(30.dp))
            Text(title, color = RM.text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        }
        if (detail != null) Body(detail)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(primary, onPrimary, Modifier.weight(1f), tone)
            ActionButton(secondary, onSecondary, Modifier.weight(1f), Tone.NEUTRAL)
        }
    }
}

/** 안내 없이 P단으로 세웠을 때: 어떻게 쉴지 큰 버튼 두 개로 묻는다 (롱웨이 모드면 '잠깐 머물기'). */
@Composable
private fun ParkedCard(ui: UiState, actions: RestMateActions) {
    val longer = ui.drive.step == DriveStep.ACTIVE && ui.drive.mode == DriveMode.LONGER
    val other = if (longer) RestKind.STAY else RestKind.WAIT
    val napMinutes = if (ui.core.onExpressway == false) ui.config.cityRestMinutes else ui.config.defaultRestMinutes
    Panel(color = RM.cardHigh, border = RM.sky) {
        Text("주차하셨네요. 여기서 쉬어 갈까요?", color = RM.text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(
                other.label,
                { actions.restHere(other) },
                Modifier.weight(1f),
                if (longer) Tone.SAND else Tone.SKY,
                minHeight = 88.dp,
                sub = if (longer) "분위기 그대로" else "출발 시각만 알려 드려요",
            )
            ActionButton(
                RestKind.NAP.label,
                { actions.restHere(RestKind.NAP) },
                Modifier.weight(1f),
                Tone.SKY,
                minHeight = 88.dp,
                sub = "알람 ${napMinutes}분 · 깨우기까지",
            )
            ActionButton("아니요", actions::dismissPrompt, Modifier.weight(0.7f), Tone.NEUTRAL, minHeight = 88.dp)
        }
    }
}

@Composable
private fun CheckInCard(actions: RestMateActions) {
    Panel(color = RM.cardHigh, border = RM.sky) {
        Text("지금은 어떠세요?", color = RM.text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val tall = 88.dp
            ActionButton("괜찮아요", { actions.answerCheckIn(CheckInAnswer.OK) }, Modifier.weight(1f), Tone.SKY, tall)
            ActionButton("더 졸려요", { actions.answerCheckIn(CheckInAnswer.MORE_DROWSY) }, Modifier.weight(1f), Tone.AMBER, tall)
            ActionButton("조용히", { actions.answerCheckIn(CheckInAnswer.QUIET) }, Modifier.weight(1f), Tone.NEUTRAL, tall)
        }
        Caption("'조용히'를 누르면 도착할 때까지 다시 묻지 않아요.")
    }
}

// ---------------------------------------------------------------- 레스트 모드: 쉴 곳으로 안내 중

@Composable
private fun GuidanceContent(ui: UiState, actions: RestMateActions) {
    val s = ui.core
    var showAlternatives by remember { mutableStateOf(false) }
    TwoPane(
        wide = LocalScreenSize.current.wide,
        left = {
            val target = s.target
            if (target != null) {
                val live = target.updatedFor(s.position, s.speedKmh)
                val near = (s.targetRemainingMeters ?: Double.MAX_VALUE) <= ui.config.nearArrivalMeters
                Panel(border = RM.amber, spacing = 12.dp) {
                    Label("${DriveText.toward(target.area.type.label)} 안내 중")
                    AreaName(target.area)
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Stat("남은 거리", "약 ${RestMateText.distance(live.roadMeters)}", RM.text, valueSize = 30.sp)
                        Stat("걸리는 시간", "${RestMateText.etaMinutes(live.etaMinutes)}분", RM.amber, valueSize = 30.sp)
                    }
                    if (near) {
                        Caption("곧 도착해요. 내비 안내에 집중해 주세요.", RM.amber)
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
                // 롱웨이 모드에는 맞출 마감이 없다: 쉬고 나면 고른 코스를 이어서 간다.
                val text = if (ui.drive.mode == DriveMode.LONGER) {
                    "쉬고 나면 고른 코스를 이어서 가요."
                } else {
                    "쉬었다 가는 동안 도착 시각 맞추기는 잠시 멈춰요. 다시 출발하면 이어서 맞춰요."
                }
                Notice(text, RM.textDim)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton("주차했어요", actions::startRest, Modifier.weight(1f), Tone.SKY, sub = "휴식 시작")
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
            RestFlowPanel(ui)
        },
        right = {
            MapPanel(ui)
        },
    )
}

/** 쉼터로 안내받는 동안: 닿으면 어떻게 되는지 순서대로 (처음 보는 사람도 흐름을 알 수 있게) */
@Composable
private fun RestFlowPanel(ui: UiState) {
    val napMinutes = if (ui.core.onExpressway == false) ui.config.cityRestMinutes else ui.config.defaultRestMinutes
    val d = ui.drive
    val target = d.targetAt
    val dest = d.destination?.name
    val resume = when {
        d.step == DriveStep.ACTIVE && d.mode == DriveMode.ON_TIME && target != null ->
            "${dest ?: "목적지"} ${DriveText.clock(target)} 도착에 맞춰 출발 시각을 알려 드려요"
        d.step == DriveStep.ACTIVE && dest != null -> "${DriveText.toward(dest)} 고른 코스를 이어서 가요"
        else -> "출발하면 가던 목적지로 다시 안내해요"
    }
    val steps = listOf(
        "쉼터에 세우고 P단으로 바꾸면 쉬기 시작해요",
        "눈 붙이기 ${napMinutes}분 → 알람 → 깬 뒤 몸 깨우기 (최대 ${RestMateText.duration(ui.config.wakeUpMs)})",
        resume,
    )
    Panel {
        Label("쉼터에 닿으면")
        steps.forEachIndexed { i, text ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberBadge("${i + 1}", RM.amber, size = 26.dp, ring = RM.card)
                Text(text, color = RM.text, fontSize = 18.sp, lineHeight = 25.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AlternativeRow(c: RestAreaCandidate, s: RestMateState, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RM.card, CardShape)
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
        ActionButton("여기로", onSelect, Modifier.width(140.dp), Tone.SKY, 64.dp)
    }
}

// ---------------------------------------------------------------- 레스트 모드: 쉬는 중

@Composable
private fun RestingContent(ui: UiState, actions: RestMateActions) {
    val s = ui.core
    val rest = s.rest ?: return
    val wokeAt = rest.wakeStartedAt
    RestKindRow(rest, ui, actions)
    TwoPane(
        wide = LocalScreenSize.current.wide,
        left = {
            // 온타임 모드: 깬 뒤에도 출발 시각을 크게 보여 준다 (기다릴 시간이 남았거나 늦기 시작했을 때).
            val departs = rest.strictTarget && rest.callAt != null
            val wakeDone = wokeAt != null && s.now >= wokeAt + rest.wakeMs
            when {
                rest.kind != RestKind.NAP -> DepartPanel(rest, ui, actions)
                departs && (rest.lateWarnedAt != null || (wakeDone && !rest.alarmRinging)) -> DepartPanel(rest, ui, actions)
                wokeAt != null && !rest.alarmRinging -> WakeUpPanel(wokeAt, rest, ui)
                else -> RestTimerPanel(rest, s.now)
            }
            if (rest.kind == RestKind.NAP && wokeAt == null) FitNotice(rest, ui, actions)
            val minutesMatter = rest.kind == RestKind.NAP || rest.targetAt == null
            if (minutesMatter && wokeAt == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (m in REST_OPTIONS) {
                        ActionButton(
                            "${m}분",
                            { actions.setRestMinutes(m) },
                            Modifier.weight(1f),
                            if (rest.plannedMinutes == m) Tone.SKY else Tone.NEUTRAL,
                            minHeight = 64.dp,
                            textSize = 21.sp,
                        )
                    }
                    // '+10분'은 기다리는 화면에서 '도착 목표 미루기'라 이름을 달리한다.
                    ActionButton("10분 더 쉬기", { actions.extendRest(10) }, Modifier.weight(1.3f), minHeight = 64.dp, textSize = 19.sp)
                }
                if (rest.kind == RestKind.NAP && rest.plannedMinutes > 20) {
                    Notice("20분 넘게 자면 깊은 잠에 들어 깬 뒤 더 멍할 수 있어요. 깨우는 시간을 넉넉히 두세요.", RM.amber)
                }
            }
            // 눈 붙이기 전에 약속이 늦춰졌다는 연락을 받았을 때 (기다리는 화면의 +5·+10분과 같은 동작)
            if (rest.kind == RestKind.NAP && wokeAt == null && ui.drive.step == DriveStep.ACTIVE && ui.drive.mode == DriveMode.ON_TIME) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton("+5분", { actions.addDriveTime(5) }, Modifier.width(120.dp), minHeight = 58.dp, textSize = 19.sp)
                    ActionButton("+10분", { actions.addDriveTime(10) }, Modifier.width(120.dp), minHeight = 58.dp, textSize = 19.sp)
                    Caption("약속이 늦춰지면 도착 목표를 미뤄요.", modifier = Modifier.weight(1f))
                }
            }
            if (rest.kind == RestKind.NAP) RestEnvironmentPanel(ui, actions)
        },
        right = {
            Panel { RestTrack(ui) }
            ArrivalPanel(rest, ui)
            CabinPanel(ui.extras)
        },
    )
    Notice("공회전 제한 지역에서 시동을 켠 채 오래 머물면 과태료가 나올 수 있어요.", RM.textDim)
}

/** 어떻게 쉴지 바꾸기: 눈 붙이기 / 그냥 기다리기 (롱웨이 모드면 잠깐 머물기) */
@Composable
private fun RestKindRow(rest: RestSession, ui: UiState, actions: RestMateActions) {
    val longer = ui.drive.step == DriveStep.ACTIVE && ui.drive.mode == DriveMode.LONGER
    val kinds = if (longer || rest.kind == RestKind.STAY) listOf(RestKind.NAP, RestKind.STAY) else listOf(RestKind.NAP, RestKind.WAIT)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (kind in kinds) {
            ActionButton(
                kind.label,
                { actions.setRestKind(kind) },
                Modifier.weight(1f),
                if (rest.kind == kind) Tone.SKY else Tone.NEUTRAL,
                minHeight = 60.dp,
                textSize = 20.sp,
                sub = kind.detail,
            )
        }
    }
}

/** 그냥 기다리기·잠깐 머물기 (온타임 모드에서는 깬 뒤의 눈 붙이기도): 출발할 시각을 크게 보여 준다. */
@Composable
private fun DepartPanel(rest: RestSession, ui: UiState, actions: RestMateActions) {
    val now = ui.core.now
    val depart = rest.departAt
    val call = rest.callAt
    val target = rest.targetAt
    val called = rest.departCalledAt != null || rest.remindedAt != null
    Panel(border = if (called) RM.amber else RM.sky) {
        val doing = when (rest.kind) {
            RestKind.STAY -> "잠깐 머물기"
            RestKind.WAIT -> "기다리기"
            RestKind.NAP -> "휴식"
        }
        Label(rest.area?.name ?: "지금 있는 곳에서 $doing")
        when {
            rest.kind == RestKind.STAY -> StayInfo(rest, now)
            depart != null && call != null && target != null -> {
                val route = rest.remainingRouteMs ?: (target - depart)
                val arriveNow = now + route
                val lateNow = DriveText.gapMs(arriveNow, target)
                // 졸려서 쉬는 중이면 늦는 것은 받아들였으니 재촉하지 않는다.
                val drowsy = rest.fatigue || rest.warned
                val holding = call > now + 30 * SECOND_MS
                val headline = when {
                    holding -> "${DriveText.clock(call)} 출발"
                    drowsy -> "출발해도 좋아요"
                    lateNow <= 30 * SECOND_MS -> "지금 출발하면 맞아요"
                    else -> "지금 출발해 주세요"
                }
                Text(headline, color = if (called && !drowsy) RM.amber else RM.text, fontSize = 60.sp, fontWeight = FontWeight.SemiBold, style = NumStyle)
                if (holding) {
                    Caption("${DriveText.clock(call)}에 출발하면 ${DriveText.clock(call + route)} 도착 · 목표 ${DriveText.clock(target)}")
                }
                // 졸려서 쉬는 동안에는 '지금 출발하면'을 보여 주지 않는다 (바로 떠나라는 말처럼 읽혀서).
                if (!(drowsy && holding)) {
                    Caption(
                        when {
                            lateNow > 30 * SECOND_MS ->
                                "지금 출발해도 ${DriveText.clock(arriveNow)} 도착 (약 ${DriveText.duration(lateNow)} 늦어요)"
                            lateNow < -30 * SECOND_MS ->
                                "지금 출발하면 ${DriveText.clock(arriveNow)} 도착 (${DriveText.duration(lateNow)} 일찍)"
                            else -> "지금 출발하면 ${DriveText.clock(arriveNow)} 도착 (정각)"
                        },
                        if (lateNow > 30 * SECOND_MS && !drowsy) RM.amber else RM.textDim,
                    )
                }
                if (call > depart + MINUTE_MS && call > now) {
                    Caption("졸릴 때는 충분히 쉬어 가요. ${DriveText.clock(call)}까지는 쉬고 출발해 주세요.", RM.amber)
                }
                val movedFrom = rest.callMovedFrom
                if (movedFrom != null && rest.departCalledAt == null) {
                    Caption("남은 길이 바뀌어 출발 시각을 다시 맞췄어요 (${DriveText.clock(movedFrom)} → ${DriveText.clock(call)}).", RM.amber)
                }
                if (ui.drive.step == DriveStep.ACTIVE && ui.drive.mode == DriveMode.ON_TIME) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionButton("+5분", { actions.addDriveTime(5) }, Modifier.width(120.dp), minHeight = 58.dp, textSize = 19.sp)
                        ActionButton("+10분", { actions.addDriveTime(10) }, Modifier.width(120.dp), minHeight = 58.dp, textSize = 19.sp)
                        if (rest.alarmRinging) {
                            ActionButton("알람 끄기", actions::stopAlarm, Modifier.width(140.dp), Tone.AMBER, minHeight = 58.dp, textSize = 19.sp)
                        }
                        Caption("약속이 늦춰지면 출발 시각을 미뤄요.", modifier = Modifier.weight(1f))
                    }
                }
            }
            else -> {
                val left = rest.alarmAt - now
                Text(if (left > 0) clock(left) else "시간이 됐어요", color = RM.text, fontSize = 72.sp, fontWeight = FontWeight.SemiBold, style = NumStyle)
                Caption("${DriveText.clock(rest.alarmAt)}에 알려 드려요 · 깨우는 알람은 울리지 않아요")
            }
        }
    }
}

/** 잠깐 머물기 (롱웨이 모드): 마감이 느슨하므로 '늦어요' 없이 언제쯤 출발하면 되는지만 */
@Composable
private fun StayInfo(rest: RestSession, now: Long) {
    val headline = if (rest.alarmAt > now) "${DriveText.clock(rest.alarmAt)}쯤 출발" else "슬슬 출발할 때예요"
    Text(headline, color = RM.text, fontSize = 60.sp, fontWeight = FontWeight.SemiBold, style = NumStyle)
    val route = rest.remainingRouteMs
    val target = rest.targetAt
    if (route != null) {
        val arriveNow = now + route
        val more = if (target != null && arriveNow - target >= 2 * MINUTE_MS) " (예정보다 약 ${DriveText.minutes(arriveNow - target)}분 더)" else ""
        Caption("지금 출발하면 ${DriveText.clock(arriveNow)} 도착$more")
    }
    Caption("분위기는 그대로예요. 출발할 때쯤 알려 드릴게요.")
}

/**
 * 눈 붙이기 (온타임 모드): 알람을 도착 시각에 맞췄는지, 깨운 뒤 기다렸다 출발하는지, 이대로 쉬면 늦어지는지.
 * 남은 길이 바뀌어 알람을 옮겼으면 보여 준다.
 */
@Composable
private fun FitNotice(rest: RestSession, ui: UiState, actions: RestMateActions) {
    val target = rest.targetAt ?: return
    if (!rest.strictTarget) return
    val late = rest.lateByMs
    val call = rest.callAt
    val wakeEnd = rest.alarmAt + RestTiming.wakeMsFor(rest.alarmAt - rest.startedAt, ui.config.wakeUpMs)
    when {
        late != null -> Notice("이대로 쉬면 ${DriveText.clock(target)} 도착이 약 ${DriveText.duration(late)} 늦어져요. 안전이 먼저예요.", RM.amber)
        call != null && call - wakeEnd > 2 * MINUTE_MS ->
            Notice("${DriveText.clock(rest.alarmAt)}에 깨우고, ${DriveText.clock(call)}에 출발하면 ${DriveText.clock(target)} 도착에 맞아요.", RM.sky)
        rest.fitNap -> Notice("${DriveText.clock(target)} 도착에 맞춰 깨워 드려요.", RM.sky)
        else -> Notice("${DriveText.clock(target)} 도착에 맞아요.", RM.sky)
    }
    // 늦는 것은 알리기만 하고, 줄일지는 운전자가 고른다.
    val fit = rest.fitNapMinutes
    if (fit != null) {
        // 줄여도 조금 늦으면(최소 10분은 잔다) '시간에 맞춰'라고 하지 않고 남는 늦음을 같이 보여 준다.
        val still = rest.fitNapLateMs
        val label = if (still == null) "시간에 맞춰 ${fit}분만 쉬기" else "${fit}분만 쉬기 (약 ${DriveText.duration(still)} 늦어요)"
        ActionButton(label, { actions.fitNapToArrival() }, Modifier.fillMaxWidth())
    }
    val previous = rest.previousAlarmAt
    if (previous != null) {
        Caption("남은 길이 바뀌어 알람 ${DriveText.clock(previous)} → ${DriveText.clock(rest.alarmAt)}", RM.amber)
    }
}

@Composable
private fun RestTimerPanel(rest: RestSession, now: Long) {
    val left = rest.alarmAt - now
    val rested = (now - rest.startedAt).coerceAtLeast(0L)
    Panel(border = if (rest.alarmRinging) RM.amber else RM.sky) {
        Label(rest.area?.name ?: "지금 있는 곳에서 휴식")
        when {
            rest.alarmRinging ->
                Text("휴식 시간이 끝났어요", color = RM.amber, fontSize = 44.sp, fontWeight = FontWeight.SemiBold)
            left > 0 ->
                // 시각(오후 10:00)과 헷갈리지 않게 남은 시간임을 붙인다.
                Text("${clock(left)} 남음", color = RM.text, fontSize = 80.sp, fontWeight = FontWeight.SemiBold, style = NumStyle)
            else ->
                Text("쉬는 시간이 지났어요", color = RM.text, fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
        }
        val total = (rest.alarmAt - rest.startedAt).coerceAtLeast(1L)
        Bar(rested.toFloat() / total.toFloat(), RM.sky)
        Caption("${DriveText.clock(rest.alarmAt)} 알람 · 지금까지 ${RestMateText.duration(rested)} 쉬었어요")
        if (!rest.alarmRinging) {
            Caption("커피는 자기 전에 마시면 깰 무렵 효과가 나요 (효과까지 20분쯤 걸려요).")
        }
    }
}

/** 깬 뒤 바로 출발하지 않도록 몸을 깨우는 시간 (수면 관성). 쉰 시간의 절반, 최대 10분 */
@Composable
private fun WakeUpPanel(wokeAt: Long, rest: RestSession, ui: UiState) {
    val wake = rest.wakeMs
    val left = wokeAt + wake - ui.core.now
    val ready = left <= 0
    Panel(border = if (ready) RM.sky else RM.amber) {
        Label("깬 직후에는 반응이 느릴 수 있어요")
        if (ready) {
            Text("이제 출발해도 좋아요", color = RM.sky, fontSize = 44.sp, fontWeight = FontWeight.SemiBold)
        } else {
            Text("${clock(left)} 남음", color = RM.text, fontSize = 72.sp, fontWeight = FontWeight.SemiBold, style = NumStyle)
            Caption("${RestMateText.duration(wake)} 동안 몸을 깨운 뒤 출발해 주세요.")
        }
        // 온타임 모드: 지금 떠나면 언제 닿는지도 보여 준다 (늦더라도 판단은 운전자가).
        val target = rest.targetAt
        val route = rest.remainingRouteMs
        if (rest.strictTarget && target != null && route != null) {
            val arrive = ui.core.now + route
            val late = DriveText.gapMs(arrive, target)
            Caption(
                when {
                    late > 30 * SECOND_MS -> "지금 출발해도 ${DriveText.clock(arrive)} 도착 (약 ${DriveText.duration(late)} 늦어요)"
                    late < -30 * SECOND_MS -> "지금 출발하면 ${DriveText.clock(arrive)} 도착 (${DriveText.duration(late)} 일찍)"
                    else -> "지금 출발하면 ${DriveText.clock(arrive)} 도착 (정각)"
                },
            )
        }
        val elapsed = if (wake <= 0) 1f else (ui.core.now - wokeAt).toFloat() / wake.toFloat()
        Bar(elapsed, if (ready) RM.sky else RM.amber)
        for (item in wakeChecklist(ui)) Body("· $item")
    }
}

/** 깬 뒤 할 일. 실내 환경은 차량이 연결돼 실제로 바꿀 때만 '넣었어요'라고 쓴다 (연결 안 되면 바꾸지 않으므로). */
private fun wakeChecklist(ui: UiState): List<String> {
    val cabin = when {
        !ui.env.enabled -> null
        ui.connection.vehicle == RestMateController.CONNECTED -> "무드등을 밝히고 시원한 바깥 바람을 넣었어요"
        else -> "차량이 연결되면 무드등을 밝히고 바깥 바람을 넣어요"
    }
    return listOfNotNull(cabin) + WAKE_CHECKLIST
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
                colors = SwitchDefaults.colors(checkedThumbColor = RM.onAccent, checkedTrackColor = RM.sky),
            )
        }
        if (env.enabled) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("온도", color = RM.textDim, fontSize = 18.sp, modifier = Modifier.width(56.dp))
                ActionButton(
                    "−",
                    { actions.setRestEnvironment(env.copy(restTemperatureC = (env.restTemperatureC - 0.5f).coerceAtLeast(17f))) },
                    Modifier.width(80.dp),
                    minHeight = 56.dp,
                )
                Text(
                    tempText(env.restTemperatureC),
                    color = RM.text,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    style = NumStyle,
                    modifier = Modifier.width(100.dp),
                )
                ActionButton(
                    "+",
                    { actions.setRestEnvironment(env.copy(restTemperatureC = (env.restTemperatureC + 0.5f).coerceAtMost(30f))) },
                    Modifier.width(80.dp),
                    minHeight = 56.dp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(
                    "무드등 은은하게",
                    { actions.setRestEnvironment(env.copy(dimLight = !env.dimLight)) },
                    Modifier.weight(1f),
                    if (env.dimLight) Tone.SKY else Tone.NEUTRAL,
                    minHeight = 56.dp,
                    textSize = 18.sp,
                )
                ActionButton(
                    "바깥 공기",
                    { actions.setRestEnvironment(env.copy(freshAir = !env.freshAir)) },
                    Modifier.weight(1f),
                    if (env.freshAir) Tone.SKY else Tone.NEUTRAL,
                    minHeight = 56.dp,
                    textSize = 18.sp,
                )
            }
        }
        ui.cabinNote?.let { Caption(it) }
    }
}

@Composable
private fun ArrivalPanel(rest: RestSession, ui: UiState) {
    val route = rest.remainingRouteMs
    // 기다리기·머물기와 깬 뒤의 온타임 눈 붙이기는 출발 시각 화면에 이미 보여 준다.
    if (rest.kind != RestKind.NAP && rest.departAt != null) return
    if (rest.kind == RestKind.NAP && rest.wakeStartedAt != null && rest.strictTarget && rest.callAt != null) return
    Panel {
        if (route == null) {
            Label("도착 예상")
            Body("내비에 목적지를 설정하면 쉬는 시간에 따라 언제 도착할지 보여 드려요.")
            return@Panel
        }
        val now = ui.core.now
        if (rest.wakeStartedAt != null || rest.kind != RestKind.NAP) {
            // 깬 뒤에는 표 대신 한 줄
            Label("도착 예상")
            Text("지금 출발하면 ${DriveText.clock(now + route)} 도착", color = RM.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        } else {
            // 출발 시각을 따로 정한 계획(깨운 뒤 기다렸다 출발)과 헷갈리지 않게 '바로 출발하면'이라고 쓴다.
            // 몸을 깨우는 시간은 더한다 (아래 설명). '깨자마자'라고 쓰면 깨우는 시간을 더한 것과 어긋난다.
            Label("몸 깨우고 바로 출발하면 (휴식 시간별 도착 예상)")
            val options = (REST_OPTIONS + rest.plannedMinutes).distinct().sorted()
            for (m in options) {
                val wakeMs = RestTiming.wakeMsFor(m * MINUTE_MS, ui.config.wakeUpMs)
                val departAt = maxOf(rest.startedAt + m * MINUTE_MS, now)
                val arriveAt = departAt + wakeMs + route
                val chosen = m == rest.plannedMinutes
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${m}분 쉬면",
                        color = if (chosen) RM.sky else RM.textDim,
                        fontSize = 20.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${DriveText.clock(arriveAt)} 도착",
                        color = if (chosen) RM.sky else RM.text,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = NumStyle,
                    )
                }
            }
            Caption("깨우는 시간(쉰 시간의 절반, 최대 ${RestMateText.duration(ui.config.wakeUpMs)})을 더했어요.")
        }
        val source = if (rest.remainingRouteEstimated) {
            "목적지까지 남은 시간은 직선거리로 추정했어요."
        } else {
            "목적지까지 남은 시간은 내비 기준이에요."
        }
        Caption(source)
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
                    Text(value, color = RM.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        for (hint in cabinHints(extras)) Body(hint)
    }
}

/** 화면 아래 큰 버튼. 달리는 중에는 어느 화면에서나 '레스트 모드'를 누를 수 있다. */
@Composable
private fun BottomAction(ui: UiState, actions: RestMateActions) {
    val s = ui.core
    // 자동 시연이 이 버튼을 누르려고 짚는 중
    val cue = Modifier.cueRing(ui.cueing(AutoCue.BOTTOM))
    when (s.phase) {
        Phase.DRIVING ->
            BigButton("레스트 모드", "졸리면 눌러요 · 가까운 휴게소·졸음쉼터·주차장으로 바로 안내해요", Tone.AMBER, actions::pressDrowsy, cue)
        Phase.TO_REST_AREA ->
            BigButton("더 빨리 쉴래요", "더 빨리 닿는 곳이 있으면 바꿔서 안내해요", Tone.AMBER, actions::pressDrowsy, cue)
        Phase.RESTING -> {
            val rest = s.rest
            when {
                // 늦기 시작해서 울리는 알람: 출발하면 알람도 꺼진다 (못 떠나면 위의 +5분·+10분이나 '알람 끄기')
                rest?.alarmRinging == true && (rest.kind != RestKind.NAP || rest.lateWarnedAt != null) ->
                    BigButton("출발할게요", "알람도 함께 꺼져요", Tone.AMBER, actions::departNow, cue)
                rest?.alarmRinging == true ->
                    BigButton("알람 끄기", null, Tone.AMBER, actions::stopAlarm, cue)
                rest != null && rest.kind != RestKind.NAP ->
                    BigButton("출발할게요", "D단으로 바꾸면 자동으로 출발로 알아봐요", Tone.SKY, actions::departNow, cue)
                rest?.wakeStartedAt == null ->
                    BigButton("일어났어요", "알람 전에 깼다면 깨우기를 시작해요", Tone.SKY, actions::wakeUp, cue)
                else ->
                    BigButton("출발할게요", "D단으로 바꾸면 자동으로 출발로 알아봐요", Tone.SKY, actions::departNow, cue)
            }
        }
    }
}

@Composable
private fun AreaName(area: RestArea) {
    Text(
        area.name,
        color = RM.text,
        fontSize = 34.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

// ---------------------------------------------------------------- 문구·계산

private val REST_OPTIONS = listOf(15, 20, 30)

private val WAKE_CHECKLIST = listOf(
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
