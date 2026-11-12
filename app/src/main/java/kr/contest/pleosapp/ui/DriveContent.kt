package kr.contest.pleosapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.contest.pleosapp.app.RestMateActions
import kr.contest.pleosapp.app.UiState
import kr.contest.pleosapp.core.CourseKind
import kr.contest.pleosapp.core.CourseOption
import kr.contest.pleosapp.core.DemoTrips
import kr.contest.pleosapp.core.DriveMode
import kr.contest.pleosapp.core.DriveState
import kr.contest.pleosapp.core.DriveStep
import kr.contest.pleosapp.core.DriveText
import kr.contest.pleosapp.core.FitStatus
import kr.contest.pleosapp.core.Gear
import kr.contest.pleosapp.core.LatLng
import kr.contest.pleosapp.core.MINUTE_MS
import kr.contest.pleosapp.core.Mood
import kr.contest.pleosapp.core.Place
import kr.contest.pleosapp.core.PlaceKind
import kr.contest.pleosapp.core.RestMateState
import kr.contest.pleosapp.core.RoadStyle

/**
 * 온타임 모드·롱웨이 모드 화면.
 * 처음 → 설정(끝낼 곳·시각·분위기) → 코스 고르기 → 달리는 중(도착 예상과 목표의 차이) → 도착
 */
@Composable
internal fun DriveContent(ui: UiState, actions: RestMateActions) {
    when (ui.drive.step) {
        DriveStep.HOME -> DriveHome(ui, actions)
        DriveStep.SETUP -> DriveSetup(ui, actions)
        DriveStep.CHOOSE -> DriveChoose(ui, actions)
        DriveStep.ACTIVE -> DriveActive(ui, actions)
        DriveStep.ARRIVED -> DriveArrived(ui, actions)
    }
}

// ---------------------------------------------------------------- 처음: 모드 고르기

@Composable
private fun DriveHome(ui: UiState, actions: RestMateActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ModeCard(DriveMode.ON_TIME, "약속 장소 · 아이 픽업 · 숙소 체크인", Modifier.weight(1f)) {
            actions.openMode(DriveMode.ON_TIME)
        }
        ModeCard(DriveMode.LONGER, "데이트 · 퇴근길 혼자 쉬기", Modifier.weight(1f)) {
            actions.openMode(DriveMode.LONGER)
        }
    }
    val d = ui.drive
    Panel {
        Label("지금 내비 목적지")
        val dest = d.naviDestination
        if (dest == null) {
            Body("목적지가 없어요. 모드를 고르면 집·회사·즐겨찾기에서 끝낼 곳을 고를 수 있어요.")
        } else {
            Text(
                dest.name,
                color = RM.text,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            d.naviRemainingMs?.let { Caption("${DriveText.duration(it)} 남음 · ${DriveText.clock(ui.now + it)} 도착 예정") }
        }
    }
    RestStatusPanel(ui)
}

@Composable
private fun ModeCard(mode: DriveMode, examples: String, modifier: Modifier, onClick: () -> Unit) {
    val tone = modeTone(mode)
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 168.dp),
        shape = RoundedCornerShape(22.dp),
        colors = ButtonDefaults.buttonColors(containerColor = tone.container, contentColor = tone.content),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(mode.title, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Text(mode.subtitle, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(examples, fontSize = 16.sp, modifier = Modifier.alpha(0.8f))
        }
    }
}

// ---------------------------------------------------------------- 설정

@Composable
private fun DriveSetup(ui: UiState, actions: RestMateActions) {
    val d = ui.drive
    val mode = d.mode ?: return
    val tone = modeTone(mode)
    Panel {
        Label(if (mode == DriveMode.ON_TIME) "어디에 맞춰 갈까요?" else "어디서 끝낼까요?")
        PlaceChoices(placeChoices(d), d.destination, tone, actions::setDriveDestination)
    }
    when (mode) {
        DriveMode.ON_TIME -> TargetPanel(ui, actions, tone)
        DriveMode.LONGER -> LongerPanel(ui, actions, tone)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionButton(
            if (d.planPending) "시간 확인 중…" else "코스 찾기",
            actions::planCourses,
            Modifier.weight(2f),
            tone,
            enabled = d.destination != null && !d.planPending,
        )
        ActionButton("뒤로", actions::driveBack, Modifier.weight(1f))
    }
    d.message?.let { Notice(it, RM.textDim) }
}

/** 고를 수 있는 끝낼 곳: 내비의 지금 목적지, 즐겨찾기(집·회사 먼저), 최근 목적지 */
private fun placeChoices(d: DriveState): List<Place> {
    val out = ArrayList<Place>()
    d.naviDestination?.let { out += it.copy(kind = PlaceKind.CURRENT) }
    for (p in d.places) if (out.none { it.sameSpot(p) }) out += p
    d.destination?.let { dest -> if (out.none { it.sameSpot(dest) }) out.add(0, dest) }
    return out.take(MAX_PLACE_CHOICES)
}

@Composable
private fun PlaceChoices(places: List<Place>, selected: Place?, tone: Tone, onSelect: (Place) -> Unit) {
    if (places.isEmpty()) {
        Body("고를 곳이 없어요. 내비에 목적지를 정하거나 집·회사를 즐겨찾기에 넣어 주세요.")
        return
    }
    for (row in places.chunked(3)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (p in row) {
                val on = selected?.sameSpot(p) == true
                ActionButton(
                    p.name,
                    { onSelect(p) },
                    Modifier.weight(1f),
                    if (on) tone else Tone.NEUTRAL,
                    minHeight = 68.dp,
                    textSize = 20.sp,
                    sub = p.kind.label,
                )
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun TargetPanel(ui: UiState, actions: RestMateActions, tone: Tone) {
    val d = ui.drive
    val target = d.targetAt
    Panel {
        Label("몇 시에 도착할까요?")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StepButton("−5분") { actions.shiftTarget(-5) }
            Text(
                target?.let { DriveText.clock(it) } ?: "—",
                color = RM.text,
                fontSize = 46.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            StepButton("+5분") { actions.shiftTarget(5) }
            StepButton("+30분") { actions.shiftTarget(30) }
        }
        val direct = d.directMs
        if (direct == null) {
            Caption("바로 가면 얼마나 걸리는지 내비에 묻는 중이에요…")
        } else {
            val source = if (d.directEstimated) " (거리로 추정)" else ""
            Caption("바로 가면 ${DriveText.clock(ui.now + direct)} 도착 · ${DriveText.duration(direct)}$source")
            if (target != null) {
                val spare = target - ui.now - direct
                if (spare >= MINUTE_MS) {
                    Body("남는 시간 약 ${DriveText.duration(spare)}", RM.teal)
                } else {
                    Body("바로 가도 빠듯해요. 목표 시각을 늦추거나 바로 가세요.", RM.amber)
                }
            }
        }
        Label("어떤 길이 좋아요?")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (style in RoadStyle.entries) {
                ActionButton(
                    style.label,
                    { actions.setRoadStyle(style) },
                    Modifier.weight(1f),
                    if (d.style == style) tone else Tone.NEUTRAL,
                    minHeight = 60.dp,
                    textSize = 20.sp,
                )
            }
        }
    }
}

@Composable
private fun LongerPanel(ui: UiState, actions: RestMateActions, tone: Tone) {
    val d = ui.drive
    Panel {
        Label("얼마나 더 달릴까요?")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (m in EXTRA_OPTIONS) {
                ActionButton(
                    "+${m}분",
                    { actions.setExtraMinutes(m) },
                    Modifier.weight(1f),
                    if (d.extraMinutes == m) tone else Tone.NEUTRAL,
                    minHeight = 64.dp,
                    textSize = 22.sp,
                )
            }
        }
        d.directMs?.let { direct ->
            Caption("바로 가면 ${DriveText.duration(direct)} · ${d.extraMinutes}분 더 달리면 ${DriveText.clock(ui.now + direct + d.extraMinutes * MINUTE_MS)} 도착")
        }
        Label("차 안 분위기")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (mood in Mood.entries) {
                ActionButton(
                    mood.label,
                    { actions.setMood(mood) },
                    Modifier.weight(1f),
                    if (d.mood == mood) tone else Tone.NEUTRAL,
                    minHeight = 64.dp,
                    textSize = 22.sp,
                )
            }
        }
        Caption("무드등 밝기와 온도를 분위기에 맞추고, 도착하면 원래대로 돌려요.")
    }
}

@Composable
private fun StepButton(text: String, onClick: () -> Unit) {
    ActionButton(text, onClick, Modifier.width(112.dp), minHeight = 64.dp, textSize = 20.sp)
}

// ---------------------------------------------------------------- 코스 고르기

@Composable
private fun DriveChoose(ui: UiState, actions: RestMateActions) {
    val d = ui.drive
    val mode = d.mode
    val tone = modeTone(mode)
    val extending = d.chosen != null
    Panel(border = modeColor(mode)) {
        Label(if (extending) "조금 더 돌기" else "코스 후보")
        val target = d.targetAt
        if (target != null) {
            val head = when (mode) {
                DriveMode.LONGER -> "${d.extraMinutes}분 더 달리고 ${DriveText.clock(target)} 도착"
                else -> "${DriveText.clock(target)} 도착 목표"
            }
            Text(head, color = RM.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        d.destination?.let { Caption("끝낼 곳: ${it.name}") }
        d.message?.let { Caption(it, RM.amber) }
    }
    for (option in d.options) {
        CourseCard(option, arrivalFor(option, d, ui.now), tone) { actions.chooseCourse(option) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionButton(if (extending) "그대로 가기" else "바로 가기", actions::goDirect, Modifier.weight(1f))
        ActionButton("뒤로", actions::driveBack, Modifier.weight(1f))
    }
}

/** 처음 고를 때만 도착 예상을 보여 준다 (바로 가는 시간 + 더 걸리는 시간). */
private fun arrivalFor(option: CourseOption, d: DriveState, now: Long): Long? {
    if (d.chosen != null) return null
    val direct = d.directMs ?: return null
    return now + direct + option.addedMs
}

@Composable
private fun CourseCard(option: CourseOption, arrival: Long?, tone: Tone, onChoose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RM.card, RoundedCornerShape(20.dp))
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                option.title,
                color = RM.text,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Body(option.reason, RM.textDim)
            if (arrival != null) Caption("${DriveText.clock(arrival)} 도착 예상", kindColor(option.kind))
        }
        ActionButton("이 코스로", onChoose, Modifier.width(170.dp), tone, minHeight = 72.dp, textSize = 22.sp)
    }
}

private fun kindColor(kind: CourseKind): Color = when (kind) {
    CourseKind.REST_STOP -> RM.amber
    CourseKind.SCENIC -> RM.violet
    CourseKind.DETOUR, CourseKind.DIRECT -> RM.teal
}

// ---------------------------------------------------------------- 달리는 중

@Composable
private fun DriveActive(ui: UiState, actions: RestMateActions) {
    val d = ui.drive
    val mode = d.mode
    val color = statusColor(d.status)
    Panel(border = color) {
        Label(listOfNotNull(mode?.title, d.chosen?.title).joinToString(" · "))
        val target = d.targetAt
        if (target != null) Text("${DriveText.clock(target)} 도착 목표", color = RM.textDim, fontSize = 22.sp)
        val eta = d.arrivalEstimate
        val diff = d.diffMs
        if (eta == null || diff == null) {
            Text("도착 시각을 계산하는 중이에요", color = RM.text, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("${DriveText.clock(eta)} 도착 예상", color = RM.text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Chip(DriveText.fit(d.status, diff), color)
            }
            val left = (eta - ui.now).coerceAtLeast(0L)
            val parts = buildList {
                add("남은 시간 ${DriveText.duration(left)}")
                if (d.viaActive) d.chosen?.via?.let { via -> add("${via.joinToString(", ") { it.name }} 거쳐 가는 중") }
                d.destination?.let { add("끝낼 곳 ${it.name}") }
            }
            Caption(parts.joinToString(" · "))
        }
        if (d.paused) Caption("쉬었다 가는 동안 시간 맞추기를 잠시 멈췄어요.", RM.amber)
    }
    d.message?.let { Notice(it, RM.text) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (d.extendOffered) {
            ActionButton("조금 더 돌기", actions::extendCourse, Modifier.weight(1f), modeTone(mode), sub = "남는 시간 채우기")
        }
        if (d.viaActive) {
            ActionButton("바로 가기", actions::goDirect, Modifier.weight(1f), sub = "경유지 빼기")
        }
        ActionButton("끝내기", actions::finishDrive, Modifier.weight(1f))
    }
    if (mode == DriveMode.LONGER) {
        Caption("${d.mood.label} 분위기로 무드등·공조를 맞췄어요. 도착하거나 끝내면 원래대로 돌려요.")
    }
}

private fun statusColor(status: FitStatus): Color = when (status) {
    FitStatus.ON_TIME -> RM.teal
    FitStatus.EARLY -> RM.violet
    FitStatus.LATE -> RM.amber
    FitStatus.UNKNOWN -> RM.line
}

// ---------------------------------------------------------------- 도착

@Composable
private fun DriveArrived(ui: UiState, actions: RestMateActions) {
    val d = ui.drive
    Panel(border = statusColor(d.status)) {
        Label(d.destination?.name ?: "도착")
        Text(d.message ?: "도착했어요.", color = RM.text, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        val arrived = d.arrivedAt
        val target = d.targetAt
        if (arrived != null && target != null) {
            Caption("도착 ${DriveText.clock(arrived)} · 목표 ${DriveText.clock(target)}")
        }
    }
    ActionButton("처음으로", actions::finishDrive, Modifier.fillMaxWidth(), modeTone(d.mode))
}

private val EXTRA_OPTIONS = listOf(15, 30, 45, 60)
private const val MAX_PLACE_CHOICES = 6

// ---------------------------------------------------------------- 미리보기 (Android Studio)

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun ChoosePreview() {
    val dest = Place("광화문 약속 장소", LatLng(37.5717, 126.9768), PlaceKind.CURRENT)
    val options = listOf(
        CourseOption(
            "scenic-naksan", CourseKind.SCENIC, "낙산공원 거쳐 가기", "야경·공원 · 약 10분 더",
            listOf(Place("낙산공원", LatLng(37.5806, 127.0076), PlaceKind.WAYPOINT)), 10 * MINUTE_MS,
        ),
        CourseOption(
            "scenic-eungbong", CourseKind.SCENIC, "응봉산 거쳐 가기", "야경 · 약 7분 더",
            listOf(Place("응봉산", LatLng(37.5506, 127.0322), PlaceKind.WAYPOINT)), 7 * MINUTE_MS,
        ),
    )
    RestMateTheme {
        RestMateScreen(
            UiState(
                now = PREVIEW_NOW,
                core = RestMateState(now = PREVIEW_NOW, gear = Gear.DRIVE, speedKmh = 30.0),
                drive = DriveState(
                    now = PREVIEW_NOW,
                    step = DriveStep.CHOOSE,
                    mode = DriveMode.ON_TIME,
                    destination = dest,
                    naviDestination = dest,
                    places = previewPlaces,
                    targetAt = PREVIEW_NOW + 40 * MINUTE_MS,
                    directMs = 20 * MINUTE_MS,
                    options = options,
                ),
                demoMode = true,
                demoTrip = DemoTrips.CITY,
            ),
            PreviewActions,
        )
    }
}

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun ActivePreview() {
    val dest = Place("광화문 약속 장소", LatLng(37.5717, 126.9768), PlaceKind.CURRENT)
    val via = Place("낙산공원", LatLng(37.5806, 127.0076), PlaceKind.WAYPOINT)
    RestMateTheme {
        RestMateScreen(
            UiState(
                now = PREVIEW_NOW,
                core = RestMateState(now = PREVIEW_NOW, gear = Gear.DRIVE, speedKmh = 32.0),
                drive = DriveState(
                    now = PREVIEW_NOW,
                    step = DriveStep.ACTIVE,
                    mode = DriveMode.ON_TIME,
                    destination = dest,
                    targetAt = PREVIEW_NOW + 30 * MINUTE_MS,
                    chosen = CourseOption("scenic-naksan", CourseKind.SCENIC, "낙산공원 거쳐 가기", "야경·공원 · 약 10분 더", listOf(via), 10 * MINUTE_MS),
                    arrivalEstimate = PREVIEW_NOW + 29 * MINUTE_MS,
                    diffMs = -MINUTE_MS,
                    status = FitStatus.ON_TIME,
                ),
            ),
            PreviewActions,
        )
    }
}
