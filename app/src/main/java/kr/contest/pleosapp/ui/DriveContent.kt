package kr.contest.pleosapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.contest.pleosapp.app.RestMateActions
import kr.contest.pleosapp.app.UiState
import kr.contest.pleosapp.core.CourseFlavor
import kr.contest.pleosapp.core.CourseKind
import kr.contest.pleosapp.core.CourseOption
import kr.contest.pleosapp.core.DriveEngine
import kr.contest.pleosapp.core.DriveMode
import kr.contest.pleosapp.core.DriveState
import kr.contest.pleosapp.core.DriveStep
import kr.contest.pleosapp.core.DriveText
import kr.contest.pleosapp.core.FitStatus
import kr.contest.pleosapp.core.MINUTE_MS
import kr.contest.pleosapp.core.Mood
import kr.contest.pleosapp.core.OnTimeStyle
import kr.contest.pleosapp.core.Place
import kr.contest.pleosapp.core.PlaceKind
import kr.contest.pleosapp.core.RestMateText
import kr.contest.pleosapp.core.SECOND_MS
import kr.contest.pleosapp.core.SavedTrip
import kr.contest.pleosapp.core.toSavedTrip

/**
 * 온타임 모드·롱웨이 모드 화면.
 * 처음 → 설정(끝낼 곳·시각·분위기) → 코스 고르기 → 달리는 중(도착 예상과 목표의 차이) → 도착
 * 넓은 화면에서는 왼쪽에 고를 것·숫자, 오른쪽에 미니맵을 둔다.
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
    TwoPane(
        wide = LocalScreenSize.current.wide,
        left = {
            NaviDestinationPanel(ui)
            SavedTripsPanel(ui.saved, actions)
        },
        right = {
            MapPanel(ui)
            RestStatusPanel(ui)
        },
    )
}

@Composable
private fun NaviDestinationPanel(ui: UiState) {
    val d = ui.drive
    Panel {
        Label("지금 내비 목적지")
        val dest = d.naviDestination
        if (dest == null) {
            Body("목적지가 없어요. 모드를 고르면 집·회사·즐겨찾기에서 끝낼 곳을 고를 수 있어요.", RM.textDim)
        } else {
            Text(
                dest.name,
                color = RM.text,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val remain = d.naviRemainingMs
            if (remain != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Stat("남은 시간", DriveText.duration(remain), RM.text, valueSize = 26.sp)
                    Stat("도착 예정", DriveText.clock(ui.now + remain), RM.blue, valueSize = 26.sp)
                }
            }
        }
    }
}

/** 자주 쓰는 설정 칩 (최대 4개). 누르면 그 설정으로 바로 코스를 찾는다. '편집'을 누르면 지울 수 있다. */
@Composable
private fun SavedTripsPanel(saved: List<SavedTrip>, actions: RestMateActions) {
    var editing by remember { mutableStateOf(false) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("자주 쓰는 설정", modifier = Modifier.weight(1f))
            if (saved.isNotEmpty()) {
                ActionButton(if (editing) "완료" else "편집", { editing = !editing }, Modifier.width(100.dp), minHeight = 44.dp, textSize = 16.sp)
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

/** 모드 카드: 색 바탕 + 그림 + 한 줄 설명 + 쓰는 상황 */
@Composable
private fun ModeCard(mode: DriveMode, examples: String, modifier: Modifier, onClick: () -> Unit) {
    val onTime = mode == DriveMode.ON_TIME
    val top = if (onTime) RM.teal else RM.violet
    val bottom = if (onTime) RM.tealDeep else RM.violetDeep
    val content = if (onTime) RM.onTeal else RM.onViolet
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier
            .heightIn(min = 150.dp)
            .clip(shape)
            .background(Brush.linearGradient(listOf(top, bottom)))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Box(
            Modifier
                .size(76.dp)
                .background(content.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (onTime) ClockGlyph(content, Modifier.size(42.dp)) else RoadGlyph(content, Modifier.size(42.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(mode.title, color = content, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(mode.subtitle, color = content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(examples, color = content.copy(alpha = 0.72f), fontSize = 16.sp)
        }
    }
}

// ---------------------------------------------------------------- 설정

@Composable
private fun DriveSetup(ui: UiState, actions: RestMateActions) {
    val d = ui.drive
    val mode = d.mode ?: return
    val tone = modeTone(mode)
    TwoPane(
        wide = LocalScreenSize.current.wide,
        left = {
            Panel {
                Label(if (mode == DriveMode.ON_TIME) "어디에 맞춰 갈까요?" else "어디서 끝낼까요?")
                PlaceChoices(placeChoices(d), d.destination, tone, actions::setDriveDestination)
            }
            when (mode) {
                DriveMode.ON_TIME -> TargetPanel(ui, actions, tone)
                DriveMode.LONGER -> LongerPanel(ui, actions, tone)
            }
        },
        right = {
            MapPanel(ui)
            SetupSummary(ui)
        },
    )
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

/** 설정 화면 오른쪽: 바로 가면 언제 닿는지와 무엇을 맞추는지 */
@Composable
private fun SetupSummary(ui: UiState) {
    val d = ui.drive
    val dest = d.destination ?: return
    val direct = d.directMs
    Panel {
        Label(dest.name)
        if (direct == null) {
            Caption("바로 가면 얼마나 걸리는지 내비에 묻는 중이에요…")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Stat("바로 가면", DriveText.duration(direct), RM.text, valueSize = 26.sp)
                Stat("도착", DriveText.clock(ui.now + direct), RM.blue, valueSize = 26.sp)
                val target = d.targetAt
                if (d.mode == DriveMode.ON_TIME && target != null) Stat("목표", DriveText.clock(target), RM.teal, valueSize = 26.sp)
                if (d.mode == DriveMode.LONGER) {
                    Stat("더 달리면", DriveText.clock(ui.now + direct + d.extraMinutes * MINUTE_MS), RM.violet, valueSize = 26.sp)
                }
            }
            if (d.directEstimated) Caption("내비 값이 없어 거리로 추정했어요.")
        }
    }
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
                style = NumStyle,
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
                    minHeight = 58.dp,
                    textSize = 19.sp,
                )
            }
        }
        val direct = d.directMs
        if (direct != null && target != null) {
            // 남는 시간 = 목표 − 지금 − 바로 가는 시간 − 여유 (코스 후보의 '쉬는·기다리는 시간'과 같은 기준)
            val late = ui.now + direct - target
            val spare = -late - MARGIN_MINUTES * MINUTE_MS
            when {
                late > 30 * SECOND_MS -> Body("바로 가도 약 ${DriveText.duration(late)} 늦어요. 목표 시각을 늦추거나 바로 가세요.", RM.amber)
                spare >= MINUTE_MS -> Body("남는 시간 약 ${DriveText.duration(spare)} · 목표 ${MARGIN_MINUTES}분 전에 닿게 채워요", RM.teal)
                else -> Body("바로 가면 시간에 거의 딱 맞아요. 남는 시간이 없어요.", RM.textDim)
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
    TwoPane(
        wide = LocalScreenSize.current.wide,
        left = {
            Panel(border = modeColor(mode)) {
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
                Label("코스 후보 ${d.options.size}개")
                if (head != null) Text(head, color = RM.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                d.destination?.let { Caption("끝낼 곳: ${it.name}") }
                d.message?.let { Caption(it, RM.amber) }
            }
            if (mode == DriveMode.LONGER) FlavorChips(d.flavor, tone, actions)
            d.options.forEachIndexed { i, option ->
                val target = d.targetAt.takeIf { mode == DriveMode.ON_TIME }
                CourseCard(i, option, arrivalFor(option, d, ui.now), target, tone) { actions.chooseCourse(option) }
            }
        },
        right = {
            MapPanel(ui)
            Caption("번호는 왼쪽 코스 카드와 같아요. 코스를 고르면 내비 경로에 경유지가 들어가요.")
        },
    )
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (flavor in CourseFlavor.entries) {
            ActionButton(
                flavor.label,
                { actions.setFlavor(flavor) },
                Modifier.weight(1f),
                if (selected == flavor) tone else Tone.NEUTRAL,
                minHeight = 54.dp,
                textSize = 17.sp,
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
private fun CourseCard(index: Int, option: CourseOption, arrival: Long?, target: Long?, tone: Tone, onChoose: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val color = if (option.via.isEmpty()) RM.textDim else candidateColor(index)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RM.card)
            .border(1.dp, RM.border, shape)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        NumberBadge("${index + 1}", color, size = 40.dp, ring = RM.card)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    option.title,
                    color = RM.text,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Chip(kindLabel(option.kind), kindColor(option.kind), fontSize = 13.sp)
            }
            Caption(option.reason)
            if (arrival != null) {
                // 바로 가기는 이유에 이미 몇 분 일찍·늦게인지 적혀 있다.
                val note = if (option.kind == CourseKind.DIRECT) "" else fitNote(arrival, target)
                Text(
                    "${DriveText.clock(arrival)} 도착 예상$note",
                    color = kindColor(option.kind),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = NumStyle,
                )
            }
        }
        ActionButton("이 코스로", onChoose, Modifier.width(150.dp), tone, minHeight = 64.dp, textSize = 20.sp)
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

private fun kindLabel(kind: CourseKind): String = when (kind) {
    CourseKind.DIRECT -> "바로"
    CourseKind.SCENIC -> "경치"
    CourseKind.DETOUR -> "돌아가기"
    CourseKind.REST_STOP -> "들렀다 가기"
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
    val border = if (d.status == FitStatus.UNKNOWN) RM.line else fitColor(d.status, longer)
    TwoPane(
        wide = LocalScreenSize.current.wide,
        left = {
            Panel(border = border, spacing = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val course = if (d.viaActive) d.chosen?.title else "목적지로 바로"
                    Label(listOfNotNull(mode?.title, course).joinToString(" · "), modifier = Modifier.weight(1f))
                    if (longer) Chip("${d.mood.label} 분위기", RM.violet, fontSize = 14.sp)
                }
                TimeTrack(ui)
                // 기다리는 코스: 그곳에 언제 닿고 언제 떠나는지
                val stopArrive = d.stopArriveAt
                val stopDepart = d.stopDepartAt
                val stop = d.chosen?.restArea
                if (d.viaActive && stop != null && stopArrive != null && stopDepart != null) {
                    Body("${RestMateText.spokenName(stop.name)} ${DriveText.clock(stopArrive)} 도착 → ${DriveText.clock(stopDepart)} 출발")
                }
            }
            // 음성으로 말한 문구는 자막으로 이미 보이므로 겹쳐 띄우지 않는다.
            val message = d.message
            if (message != null && !(captionVisible(ui) && message == ui.lastMessage)) Notice(message, RM.text)
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
                    ActionButton("+15분 더", { actions.addDriveTime(15) }, Modifier.width(150.dp), minHeight = 58.dp, textSize = 19.sp)
                    Caption("${d.mood.label} 분위기로 무드등·공조를 맞췄어요. 도착하거나 끝내면 원래대로 돌려요.", modifier = Modifier.weight(1f))
                } else {
                    ActionButton("+5분", { actions.addDriveTime(5) }, Modifier.width(120.dp), minHeight = 58.dp, textSize = 19.sp)
                    ActionButton("+10분", { actions.addDriveTime(10) }, Modifier.width(120.dp), minHeight = 58.dp, textSize = 19.sp)
                    Caption("약속이 늦춰지면 목표를 미뤄요. 더 돌 코스가 있으면 채워요.", modifier = Modifier.weight(1f))
                }
            }
        },
        right = {
            MapPanel(ui)
            RouteLine(ui)
        },
    )
}

/** 미니맵 아래 한 줄: 남은 경유지 → 목적지 */
@Composable
private fun RouteLine(ui: UiState) {
    val d = ui.drive
    val dest = d.destination ?: return
    val via = if (d.viaActive) (ui.route?.via ?: d.chosen?.via).orEmpty() else emptyList()
    val stops = via.map { it.name } + dest.name
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Label("다음")
        Text(
            stops.joinToString("  →  "),
            color = RM.text,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------- 도착

@Composable
private fun DriveArrived(ui: UiState, actions: RestMateActions) {
    val d = ui.drive
    val longer = d.mode == DriveMode.LONGER
    val color = if (d.status == FitStatus.UNKNOWN) modeColor(d.mode) else fitColor(d.status, longer)
    TwoPane(
        wide = LocalScreenSize.current.wide,
        left = {
            Panel(border = color, spacing = 14.dp) {
                Label(d.destination?.name ?: "도착")
                Text(d.message ?: "도착했어요.", color = RM.text, fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 50.sp)
                val arrived = d.arrivedAt
                val target = d.targetAt
                if (arrived != null && target != null) {
                    // 롱웨이 모드의 시각은 마감이 아니라 예정이다.
                    val gap = DriveText.gapMs(arrived, target)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Stat("도착", DriveText.clock(arrived), color, valueSize = 36.sp)
                        Stat(if (longer) "예정" else "목표", DriveText.clock(target), RM.text, valueSize = 36.sp)
                        Chip(DriveText.diff(gap), color, fontSize = 18.sp)
                    }
                }
            }
            ActionButton("처음으로", actions::finishDrive, Modifier.fillMaxWidth(), modeTone(d.mode))
        },
        right = {
            MapPanel(ui)
        },
    )
}

private val EXTRA_OPTIONS = listOf(15, 30, 45, 60)
private const val MAX_PLACE_CHOICES = 6
private const val DEFAULT_DIRECT_MS = 20 * MINUTE_MS
