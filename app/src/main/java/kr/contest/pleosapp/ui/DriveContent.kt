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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.contest.pleosapp.app.RestMateActions
import kr.contest.pleosapp.app.UiState
import kr.contest.pleosapp.core.CourseFlavor
import kr.contest.pleosapp.core.CourseKind
import kr.contest.pleosapp.core.CourseOption
import kr.contest.pleosapp.core.DemoTrips
import kr.contest.pleosapp.core.DriveEngine
import kr.contest.pleosapp.core.DriveMode
import kr.contest.pleosapp.core.DriveState
import kr.contest.pleosapp.core.DriveStep
import kr.contest.pleosapp.core.DriveText
import kr.contest.pleosapp.core.FitStatus
import kr.contest.pleosapp.core.Gear
import kr.contest.pleosapp.core.LatLng
import kr.contest.pleosapp.core.MINUTE_MS
import kr.contest.pleosapp.core.Mood
import kr.contest.pleosapp.core.OnTimeStyle
import kr.contest.pleosapp.core.Place
import kr.contest.pleosapp.core.PlaceKind
import kr.contest.pleosapp.core.RestMateState
import kr.contest.pleosapp.core.RestMateText
import kr.contest.pleosapp.core.SECOND_MS
import kr.contest.pleosapp.core.SavedTrip
import kr.contest.pleosapp.core.toSavedTrip

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
    SavedTripsPanel(ui.saved, actions)
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

/** 자주 쓰는 설정 칩 (최대 4개). 누르면 그 설정으로 바로 코스를 찾는다. '편집'을 누르면 지울 수 있다. */
@Composable
private fun SavedTripsPanel(saved: List<SavedTrip>, actions: RestMateActions) {
    var editing by remember { mutableStateOf(false) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("자주 쓰는 설정", modifier = Modifier.weight(1f))
            if (saved.isNotEmpty()) {
                ActionButton(if (editing) "완료" else "편집", { editing = !editing }, Modifier.width(110.dp), minHeight = 48.dp, textSize = 17.sp)
            }
        }
        if (saved.isEmpty()) {
            Caption("코스 후보 화면에서 '이 설정 저장'을 누르면 여기서 한 번에 다시 쓸 수 있어요 (학원 픽업 4:50 같은).")
            return@Panel
        }
        for (row in saved.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (trip in row) {
                    ActionButton(
                        // 같은 장소 칩이 여럿이면 큰 글씨에 시각을 붙인다 ("4:50 아이 학원")
                        trip.title(saved),
                        { if (editing) actions.removeSavedTrip(trip) else actions.applySavedTrip(trip) },
                        Modifier.weight(1f),
                        if (editing) Tone.AMBER else modeTone(trip.mode),
                        minHeight = 68.dp,
                        textSize = 21.sp,
                        sub = if (editing) "지우기" else trip.detail,
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
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
        }
        // 바로 가면 도착하는 시각 뒤로 칩 4개 (가까우면 10분, 멀면 30분 간격)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (at in DriveEngine.targetChoices(ui.now, d.directMs ?: DEFAULT_DIRECT_MS)) {
                ActionButton(
                    DriveText.clock(at),
                    { actions.setTarget(at) },
                    Modifier.weight(1f),
                    if (target == at) tone else Tone.NEUTRAL,
                    minHeight = 60.dp,
                    textSize = 20.sp,
                )
            }
        }
        val direct = d.directMs
        if (direct == null) {
            Caption("바로 가면 얼마나 걸리는지 내비에 묻는 중이에요…")
        } else {
            val source = if (d.directEstimated) " (거리로 추정)" else ""
            Caption("바로 가면 ${DriveText.clock(ui.now + direct)} 도착 · ${DriveText.duration(direct)}$source · 목표보다 ${MARGIN_MINUTES}분 일찍 닿도록 맞춰요")
            if (target != null) {
                // 남는 시간 = 목표 − 지금 − 바로 가는 시간 − 여유 (코스 후보의 '쉬는·기다리는 시간'과 같은 기준)
                val late = ui.now + direct - target
                val spare = -late - MARGIN_MINUTES * MINUTE_MS
                when {
                    late > 30 * SECOND_MS -> Body("바로 가도 약 ${DriveText.duration(late)} 늦어요. 목표 시각을 늦추거나 바로 가세요.", RM.amber)
                    spare >= MINUTE_MS -> Body("남는 시간 약 ${DriveText.duration(spare)}", RM.teal)
                    else -> Body("바로 가면 시간에 거의 딱 맞아요. 남는 시간이 없어요.", RM.textDim)
                }
            }
        }
        Label("남는 시간은 어떻게 쓸까요?")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (style in OnTimeStyle.entries) {
                ActionButton(
                    style.label,
                    { actions.setOnTimeStyle(style) },
                    Modifier.weight(1f),
                    if (d.onTimeStyle == style) tone else Tone.NEUTRAL,
                    minHeight = 64.dp,
                    textSize = 20.sp,
                    sub = style.detail,
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
    Panel(border = modeColor(mode)) {
        Label("코스 후보")
        val target = d.targetAt
        // 바로 가도 늦으면 '맞춰요' 대신 얼마나 늦는지
        val late = d.directMs?.let { direct -> target?.let { ui.now + direct - it } }
        val head = when {
            mode == DriveMode.LONGER -> "${d.extraMinutes}분 더 달릴 코스"
            target != null && late != null && late > 30 * SECOND_MS ->
                "목표 ${DriveText.clock(target)} · 바로 가도 약 ${DriveText.duration(late)} 늦어요"
            target != null -> "목표 ${DriveText.clock(target)} · ${MARGIN_MINUTES}분 일찍 닿게 맞춰요"
            else -> null
        }
        if (head != null) Text(head, color = RM.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        d.destination?.let { Caption("끝낼 곳: ${it.name}") }
        d.message?.let { Caption(it, RM.amber) }
    }
    if (mode == DriveMode.LONGER) FlavorChips(d.flavor, tone, actions)
    for (option in d.options) {
        CourseCard(option, arrivalFor(option, d, ui.now), d.targetAt.takeIf { mode == DriveMode.ON_TIME }, tone) { actions.chooseCourse(option) }
    }
    val candidate = d.toSavedTrip()
    val saved = candidate != null && ui.saved.any { it.sameAs(candidate) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionButton("바로 가기", actions::goDirect, Modifier.weight(1f))
        ActionButton(
            if (saved) "저장했어요" else "이 설정 저장",
            actions::saveTrip,
            Modifier.weight(1f),
            sub = if (saved) "처음 화면에서 바로" else "다음엔 한 번에",
            enabled = candidate != null && !saved,
        )
        ActionButton("뒤로", actions::driveBack, Modifier.weight(1f))
    }
}

/** 롱웨이 모드: 어떤 길이 좋은지 고르면 후보를 다시 낸다 (시각·분위기로 기본값을 정해 둠). */
@Composable
private fun FlavorChips(selected: CourseFlavor?, tone: Tone, actions: RestMateActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (flavor in CourseFlavor.entries) {
            ActionButton(
                flavor.label,
                { actions.setFlavor(flavor) },
                Modifier.weight(1f),
                if (selected == flavor) tone else Tone.NEUTRAL,
                minHeight = 56.dp,
                textSize = 19.sp,
            )
        }
    }
}

/** 도착 예상: 바로 가는 시간 + 더 걸리는 시간. 쉬다 가는(기다리는) 코스는 목표에서 여유를 뺀 시각 */
private fun arrivalFor(option: CourseOption, d: DriveState, now: Long): Long? {
    if (d.chosen != null) return null
    val direct = d.directMs ?: return null
    val target = d.targetAt
    if (option.kind == CourseKind.REST_STOP && target != null && d.mode == DriveMode.ON_TIME) return target - MARGIN_MINUTES * MINUTE_MS
    return now + direct + option.addedMs
}

@Composable
private fun CourseCard(option: CourseOption, arrival: Long?, target: Long?, tone: Tone, onChoose: () -> Unit) {
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
            if (arrival != null) {
                // 바로 가기는 이유에 이미 몇 분 일찍·늦게인지 적혀 있다.
                val note = if (option.kind == CourseKind.DIRECT) "" else fitNote(arrival, target)
                Caption("${DriveText.clock(arrival)} 도착 예상$note", kindColor(option.kind))
            }
        }
        ActionButton("이 코스로", onChoose, Modifier.width(170.dp), tone, minHeight = 72.dp, textSize = 22.sp)
    }
}

/** 온타임 모드 코스 카드: 남는 시간을 다 못 채워 일찍 닿거나 늦는 코스면 몇 분인지 붙인다 */
private fun fitNote(arrival: Long, target: Long?): String {
    if (target == null) return ""
    val early = -DriveText.gapMs(arrival, target)
    return when {
        early < -30 * SECOND_MS -> " · 약 ${DriveText.duration(early)} 늦어요"
        early > (MARGIN_MINUTES + 2) * MINUTE_MS -> " · ${DriveText.duration(early)} 일찍"
        else -> ""
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
    val longer = mode == DriveMode.LONGER
    val color = if (longer) longerColor(d.status) else statusColor(d.status)
    Panel(border = color) {
        val course = if (d.viaActive) d.chosen?.title else "목적지로 바로"
        Label(listOfNotNull(mode?.title, course).joinToString(" · "))
        val eta = d.arrivalEstimate
        val diff = d.diffMs
        if (eta == null || diff == null) {
            Text("도착 시각을 계산하는 중이에요", color = RM.text, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("${DriveText.clock(eta)} 도착 예상", color = RM.text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                // 분은 화면의 두 시각(도착 예상·목표) 차이로, 계획한 여유(3분 + 2분) 안이면 '여유'로
                val shown = d.targetAt?.let { DriveText.gapMs(eta, it) } ?: diff
                Chip(
                    if (longer) DriveText.fitLonger(d.status, shown) else DriveText.fit(d.status, shown, (MARGIN_MINUTES + 2) * MINUTE_MS),
                    color,
                )
            }
            val target = d.targetAt
            val parts = buildList {
                if (target != null) add("${if (longer) "예정" else "목표"} ${DriveText.clock(target)}")
                d.destination?.let { add(it.name) }
            }
            Caption(parts.joinToString(" · "))
            // 기다리는 코스: 그곳에 언제 닿고 언제 떠나는지
            val stopArrive = d.stopArriveAt
            val stopDepart = d.stopDepartAt
            val stop = d.chosen?.restArea
            if (d.viaActive && stop != null && stopArrive != null && stopDepart != null) {
                Body("${RestMateText.spokenName(stop.name)} ${DriveText.clock(stopArrive)} 도착 → ${DriveText.clock(stopDepart)} 출발", RM.text)
            }
        }
        if (d.paused) Caption("쉬었다 가는 동안 시간 맞추기를 잠시 멈췄어요.", RM.amber)
    }
    d.message?.let { Notice(it, RM.text) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val extend = d.extendOption
        if (d.extendOffered && extend != null) {
            val label = if (extend.kind == CourseKind.REST_STOP) "기다렸다 가기" else "조금 더 돌기"
            ActionButton(label, actions::extendCourse, Modifier.weight(1f), modeTone(mode), sub = extend.title)
        }
        if (d.viaActive) {
            val home = d.destination?.kind == PlaceKind.HOME
            val label = if (longer && home) "바로 집으로" else "바로 가기"
            ActionButton(label, actions::goDirect, Modifier.weight(1f), sub = "경유지 빼기")
        }
        ActionButton("끝내기", actions::finishDrive, Modifier.weight(1f))
    }
    // 달리는 중 시간 더하기: 온타임은 약속이 늦춰졌을 때, 롱웨이는 더 달리고 싶을 때
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (longer) {
            ActionButton("+15분 더", { actions.addDriveTime(15) }, Modifier.width(170.dp), minHeight = 60.dp, textSize = 20.sp)
            Caption("${d.mood.label} 분위기로 무드등·공조를 맞췄어요. 도착하거나 끝내면 원래대로 돌려요.", modifier = Modifier.weight(1f))
        } else {
            ActionButton("+5분", { actions.addDriveTime(5) }, Modifier.width(130.dp), minHeight = 60.dp, textSize = 20.sp)
            ActionButton("+10분", { actions.addDriveTime(10) }, Modifier.width(130.dp), minHeight = 60.dp, textSize = 20.sp)
            Caption("약속이 늦춰지면 목표를 미뤄요. 더 돌 코스가 있으면 채워요.", modifier = Modifier.weight(1f))
        }
    }
}

/** 롱웨이 모드는 마감이 없어 늦어도 경고 색을 쓰지 않는다. */
private fun longerColor(status: FitStatus): Color = when (status) {
    FitStatus.EARLY -> RM.teal
    FitStatus.UNKNOWN -> RM.line
    else -> RM.violet
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
            // 롱웨이 모드의 시각은 마감이 아니라 예정이다.
            val label = if (d.mode == DriveMode.LONGER) "예정" else "목표"
            Caption("도착 ${DriveText.clock(arrived)} · $label ${DriveText.clock(target)}")
        }
    }
    ActionButton("처음으로", actions::finishDrive, Modifier.fillMaxWidth(), modeTone(d.mode))
}

private val EXTRA_OPTIONS = listOf(15, 30, 45, 60)
private const val MAX_PLACE_CHOICES = 6
/** 도착 목표보다 이만큼 일찍 닿도록 맞춘다 (DriveConfig.marginMs) */
private const val MARGIN_MINUTES = 3
private const val DEFAULT_DIRECT_MS = 20 * MINUTE_MS

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
