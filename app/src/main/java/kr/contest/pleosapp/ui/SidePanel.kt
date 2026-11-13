package kr.contest.pleosapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.contest.pleosapp.app.AutoChapter
import kr.contest.pleosapp.app.LogKind
import kr.contest.pleosapp.app.RestMateActions
import kr.contest.pleosapp.app.RestMateController
import kr.contest.pleosapp.app.UiState
import kr.contest.pleosapp.core.DemoTrips
import kr.contest.pleosapp.core.DriveText
import kr.contest.pleosapp.core.Gear
import kr.contest.pleosapp.core.HOUR_MS
import kr.contest.pleosapp.core.MINUTE_MS
import kr.contest.pleosapp.core.Phase
import kr.contest.pleosapp.core.RestMateText
import kr.contest.pleosapp.data.CityRestPlaces
import kr.contest.pleosapp.data.RestAreaRepository
import kr.contest.pleosapp.sdk.ConsoleLinkState
import kr.contest.pleosapp.sdk.ConsoleStatus
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 오른쪽 패널: 앱 이름, 차 상태(기어·속도·연속 주행), 연결 상태, 안내 기록, 시연 조작.
 * 운전자가 보는 왼쪽 화면과 달리 '지금 앱이 무엇을 알고 무엇을 했는지'를 보여 준다.
 */
@Composable
internal fun SidePanel(ui: UiState, actions: RestMateActions, modifier: Modifier) {
    val shape = CardShape
    Column(
        modifier
            .clip(shape)
            .background(RM.panel)
            .border(1.dp, RM.border, shape)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Brand()
        VehicleTiles(ui)
        Connections(ui)
        // 시연할 때 누를 것이 늘 보이게 시연 카드를 안내 기록보다 위에 둔다
        DemoCard(ui, actions)
        Rule()
        GuidanceLog(ui)
        Caption("쉼터 데이터 ${ui.restAreaCount}곳 · ${RestAreaRepository.ATTRIBUTION}", RM.textFaint)
        Caption("${CityRestPlaces.ATTRIBUTION} · 경치 지점은 팀이 고른 표본", RM.textFaint)
    }
}

@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Logo(Modifier.size(46.dp))
        Column {
            Text("드라이브메이트", color = RM.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Caption("도착 시각에 맞춰 달리고, 졸리면 쉬었다 가요")
        }
    }
}

/** 기어 · 속도 · 연속 주행 */
@Composable
private fun VehicleTiles(ui: UiState) {
    val s = ui.core
    val long = s.continuousDrivingMs >= ui.config.longDriveMs
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile("기어", gearLabel(s.gear), gearColor(s.gear), Modifier.weight(0.75f))
        Tile("속도", s.speedKmh?.roundToInt()?.toString() ?: "—", RM.text, Modifier.weight(1f), unit = "km/h")
        Tile("연속 주행", hoursMinutes(s.continuousDrivingMs), if (long) RM.amber else RM.text, Modifier.weight(1.1f))
    }
    val road = when (s.onExpressway) {
        true -> "고속도로"
        false -> "일반 도로"
        null -> null
    }
    val parts = listOfNotNull(road, "${hoursText(ui.config.longDriveMs)}마다 휴식 권장")
    Caption(parts.joinToString(" · "), RM.textFaint)
}

@Composable
private fun Tile(label: String, value: String, color: Color, modifier: Modifier, unit: String? = null) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier
            .clip(shape)
            .background(RM.card)
            .border(1.dp, RM.border, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        val compact = LocalScreenSize.current.compact
        Caption(label)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, color = color, fontSize = if (compact) 25.sp else 30.sp, fontWeight = FontWeight.SemiBold, style = NumStyle, maxLines = 1)
            if (unit != null) {
                Text(unit, color = RM.textDim, fontSize = 13.sp, maxLines = 1, softWrap = false, modifier = Modifier.padding(bottom = 5.dp))
            }
        }
    }
}

/** 차량·내비 SDK, 음성, (시연) 계기판 연동 */
@Composable
private fun Connections(ui: UiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusLine("차량 SDK", ui.connection.vehicle, connectionColor(ui.connection.vehicle))
        StatusLine("내비 SDK", ui.connection.navi, connectionColor(ui.connection.navi))
        StatusLine(
            "음성 안내",
            if (ui.speechAvailable) "켜짐" else "화면 자막으로",
            if (ui.speechAvailable) RM.sky else RM.textDim,
        )
        if (ui.demoMode) {
            val (text, color) = consoleText(ui.console)
            StatusLine("계기판·콘솔", text, color)
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Dot(color, 9.dp)
        Text(
            label,
            color = RM.textDim,
            fontSize = 16.sp,
            modifier = Modifier
                .padding(start = 10.dp)
                .width(96.dp),
        )
        Text(
            value,
            color = if (color == RM.textDim) RM.textDim else RM.text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 안내 기록: 차가 말한 것(음성), 내비·차량에 요청한 것, 시연 조작을 시각과 함께 */
@Composable
private fun GuidanceLog(ui: UiState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label("안내 기록")
        if (ui.log.isEmpty()) {
            Caption("아직 없어요. 음성 안내와 내비 요청이 시각과 함께 여기에 쌓여요.")
        }
        for (entry in ui.log.take(LOG_SHOWN)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LogGlyph(entry.kind, Modifier.padding(top = 3.dp).size(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        DriveText.clockNow(entry.at),
                        color = RM.textFaint,
                        fontSize = 13.sp,
                        style = NumStyle,
                    )
                    Text(
                        entry.text,
                        color = if (entry.kind == LogKind.VOICE) RM.text else RM.textDim,
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private const val LOG_SHOWN = 6

/** 시연 모드: 모의 내비와 빨리 가는 가상 시계로 실제 주행 없이 기능을 보여 준다 */
@Composable
private fun DemoCard(ui: UiState, actions: RestMateActions) {
    Panel(color = RM.card, border = if (ui.demoMode) RM.amber else null, padding = 18.dp, spacing = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("시연 모드", color = RM.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Caption("모의 내비 · 빨리 가는 가상 시계")
            }
            Switch(
                checked = ui.demoMode,
                onCheckedChange = actions::setDemoMode,
                colors = SwitchDefaults.colors(checkedThumbColor = RM.onAccent, checkedTrackColor = RM.amber),
            )
        }
        AutoDemoControls(ui, actions)
        // 자동 시연 중에는 손으로 누르는 시연 조작을 숨긴다 (대본과 엇갈리지 않게)
        if (ui.demoMode && ui.auto == null) DemoControls(ui, actions)
    }
}

/** 자동 시연: 네 이야기(온타임·픽업·롱웨이·레스트)를 한 번에, 또는 하나씩. 진행 중이면 몇 번째 장면인지와 다음·멈추기 */
@Composable
private fun AutoDemoControls(ui: UiState, actions: RestMateActions) {
    val auto = ui.auto
    if (auto == null) {
        ActionButton(
            "자동 시연 시작",
            { actions.startAutoDemo(null) },
            Modifier.fillMaxWidth(),
            Tone.AMBER,
            minHeight = 60.dp,
            textSize = 20.sp,
            sub = "온타임 → 픽업 → 롱웨이 → 레스트 · 약 4분 40초",
        )
        // 이야기 넷: 두 줄로 (옆 칸이 좁아 한 줄에 넷이면 글자가 꺾인다)
        for (row in AutoChapter.entries.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (chapter in row) {
                    SmallButton(chapter.label, { actions.startAutoDemo(chapter) }, Modifier.weight(1f))
                }
            }
        }
        return
    }
    StatusLine(
        "자동 시연",
        "${auto.scene} / ${auto.total} · ${auto.chapter?.label ?: "인사"}",
        chapterColor(auto.chapter),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton("다음 장면", actions::nextAutoScene, Modifier.weight(1f))
        SmallButton("멈추기", actions::stopAutoDemo, Modifier.weight(1f))
    }
    Caption("자동 시연 중에는 시연 조작 버튼을 숨겨요. 멈추면 그 자리에서 손으로 이어 갈 수 있어요.", RM.textFaint)
}

@Composable
private fun DemoControls(ui: UiState, actions: RestMateActions) {
    val trip = ui.demoTrip
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (t in DemoTrips.ALL) {
            ActionButton(
                t.title.substringBefore(" ·"),
                { actions.selectDemoTrip(t.id) },
                Modifier.weight(1f),
                if (trip?.id == t.id) Tone.AMBER else Tone.NEUTRAL,
                minHeight = 50.dp,
                textSize = 17.sp,
                sub = t.title.substringAfter("· ", ""),
            )
        }
    }
    val scale = trip?.timeScale?.roundToInt() ?: 1
    StatusLine(
        "가상 시계",
        if (ui.demoClockHeld) "×$scale · D단으로 서 있어 멈춤" else "×$scale · 1초에 ${scale}초씩",
        if (ui.demoClockHeld) RM.textDim else RM.amber,
    )
    val speed = ui.core.speedKmh?.roundToInt() ?: 0
    StatusLine(
        "모의 주행",
        when {
            ui.demoDriving && ui.demoJammed -> "시속 ${speed}km · 정체"
            ui.demoDriving -> "시속 ${speed}km로 달리는 중"
            else -> "서 있는 중"
        },
        if (ui.demoDriving) RM.sky else RM.textDim,
    )
    demoHint(ui)?.let { hint ->
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(RM.amber.copy(alpha = 0.10f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PlayGlyph(RM.amber, Modifier.padding(top = 3.dp).size(14.dp))
            Text(hint, color = RM.text, fontSize = 15.sp, lineHeight = 21.sp)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton("출발 · 주행", actions::demoDrive, Modifier.weight(1f))
        SmallButton("P단 주차", actions::demoPark, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // 기다리는 중에도 누를 수 있다 (쉬는 동안 길이 막히면 출발 시각을 다시 맞추는 것을 보여 줄 때).
        SmallButton("막힘 +6분", actions::demoTraffic, Modifier.weight(1f), enabled = ui.demoDriving || ui.core.phase == Phase.RESTING)
        SmallButton("10분 건너뛰기", { actions.demoSkip(10) }, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton("졸음 경고(모의)", actions::demoAttentionWarning, Modifier.weight(1f))
        SmallButton("+2시간 운전", actions::demoAddDrivingTime, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton(
            "휴식 건너뛰기",
            actions::demoSkipRest,
            Modifier.weight(1f),
            enabled = ui.core.phase == Phase.RESTING && ui.core.rest?.wakeStartedAt == null && ui.core.rest?.remindedAt == null,
        )
        SmallButton("처음부터", actions::demoRestart, Modifier.weight(1f))
    }
    Caption("시연 모드의 위치·속도·시각은 실제 값이 아니에요.", RM.textFaint)
}

private fun consoleText(c: ConsoleStatus): Pair<String, Color> = when (c.state) {
    ConsoleLinkState.OFF -> "꺼짐" to RM.textDim
    ConsoleLinkState.CONNECTING -> "연결 중…" to RM.textDim
    ConsoleLinkState.UNREACHABLE -> "연결 안 됨 (adb forward 확인)" to RM.amber
    ConsoleLinkState.ON -> {
        val gear = c.gear?.let { gearLabel(it) } ?: "—"
        "연동 중 · $gear · ${c.speedKmh ?: 0} km/h" to RM.sky
    }
}

internal fun gearLabel(gear: Gear): String = when (gear) {
    Gear.PARK -> "P"
    Gear.REVERSE -> "R"
    Gear.NEUTRAL -> "N"
    Gear.DRIVE -> "D"
    Gear.UNKNOWN -> "—"
}

private fun gearColor(gear: Gear): Color = when (gear) {
    Gear.DRIVE -> RM.sky
    Gear.PARK -> RM.amber
    Gear.REVERSE -> RM.red
    else -> RM.textDim
}

private fun connectionColor(status: String): Color = when {
    status == RestMateController.CONNECTED -> RM.sky
    status.startsWith("실패") -> RM.red
    else -> RM.textDim
}

/** "2시간", "1시간 30분" */
internal fun hoursText(ms: Long): String =
    if (ms % HOUR_MS == 0L) "${ms / HOUR_MS}시간" else RestMateText.drivingTime(ms)

/** "1:05" (시:분) */
private fun hoursMinutes(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / MINUTE_MS
    return String.format(Locale.ROOT, "%d:%02d", total / (HOUR_MS / MINUTE_MS), total % (HOUR_MS / MINUTE_MS))
}
