package kr.contest.pleosapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import kr.contest.pleosapp.app.AutoCue
import kr.contest.pleosapp.app.PlacePurpose
import kr.contest.pleosapp.app.RestMateActions
import kr.contest.pleosapp.app.RestMateController
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
        ModeCard(
            DriveMode.ON_TIME,
            "약속 장소 · 아이 픽업 · 숙소 체크인",
            Modifier.weight(1f).cueRing(ui.cueing(AutoCue.MODE_ON_TIME), CardShape),
        ) {
            actions.openMode(DriveMode.ON_TIME)
        }
        ModeCard(
            DriveMode.LONGER,
            "데이트 · 퇴근길 혼자 쉬기",
            Modifier.weight(1f).cueRing(ui.cueing(AutoCue.MODE_LONGER), CardShape),
        ) {
            actions.openMode(DriveMode.LONGER)
        }
    }
    TwoPane(
        wide = LocalScreenSize.current.wide,
        left = {
            NaviDestinationPanel(ui, actions)
            SavedTripsPanel(ui.saved, actions)
            RestStatusPanel(ui)
        },
        right = {
            MapPanel(ui)
        },
    )
}

@Composable
private fun NaviDestinationPanel(ui: UiState, actions: RestMateActions) {
    val d = ui.drive
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("지금 내비 목적지", modifier = Modifier.weight(1f))
            // 실제 내비처럼 목적지를 이름·주소로 찾거나 지도에서 골라 바로 안내받는다
            ActionButton(
                if (d.naviDestination == null) "목적지 검색" else "목적지 바꾸기",
                { actions.openPlaceSearch(PlacePurpose.NAVI_DESTINATION) },
                Modifier.width(200.dp),
                if (d.naviDestination == null) Tone.SKY else Tone.NEUTRAL,
                minHeight = 52.dp,
                textSize = 18.sp,
            )
        }
        val dest = d.naviDestination
        if (dest == null) {
            Body("목적지가 없어요. '목적지 검색'으로 찾거나, 모드를 고르면 집·회사·즐겨찾기에서 끝낼 곳을 고를 수 있어요.", RM.textDim)
        } else {
            Text(
                dest.name,
                color = RM.text,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
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
            Caption("코스 후보 화면에서 '이 설정 저장'을 누르면 여기서 한 번에 다시 쓸 수 있어요 (학원 픽업 9:50 같은).")
            return@Panel
        }
        for (row in saved.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (trip in row) {
                    ActionButton(
                        // 같은 장소 칩이 여럿이면 큰 글씨에 시각을 붙인다 ("9:50 아이 학원")
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

/**
 * 모드 카드: 현대 네이비 바탕 + 모드 색 테두리·원 + 한 줄 설명 + 쓰는 상황.
 * 온타임은 하늘색, 롱웨이는 샌드로 구분하고, 오른쪽 화살표로 누를 수 있음을 보인다.
 */
@Composable
private fun ModeCard(mode: DriveMode, examples: String, modifier: Modifier, onClick: () -> Unit) {
    // 남색 카드라 낮 화면에서도 밝은 브랜드 색을 쓴다
    val accent = if (mode == DriveMode.ON_TIME) RM.skyBlue else RM.sandBrand
    val shape = CardShape
    Row(
        modifier
            .heightIn(min = 150.dp)
            .clip(shape)
            .background(Brush.linearGradient(listOf(RM.navyLight, RM.hyundaiNavy)))
            .border(1.dp, accent.copy(alpha = 0.6f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Box(
            Modifier
                .size(72.dp)
                .background(accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (mode == DriveMode.ON_TIME) {
                ClockGlyph(RM.hyundaiNavy, Modifier.size(40.dp))
            } else {
                RoadGlyph(RM.hyundaiNavy, Modifier.size(40.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(mode.title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp)
            Text(mode.subtitle, color = accent, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text(examples, color = HERO_DIM, fontSize = 16.sp)
        }
        Text("›", color = accent, fontSize = 44.sp, fontWeight = FontWeight.Light)
    }
}

/** 남색 모드 카드의 옅은 글자 */
private val HERO_DIM = Color(0xFFB5C3D6)

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
                PlaceChoices(placeChoices(d), d.destination, tone, actions::setDriveDestination) {
                    actions.openPlaceSearch(PlacePurpose.END_PLACE)
                }
            }
            when (mode) {
                DriveMode.ON_TIME -> TargetPanel(ui, actions, tone)
                DriveMode.LONGER -> LongerPanel(ui, actions, tone)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (mode == DriveMode.ON_TIME && noSpareTime(ui)) {
                    // 남는 시간이 없으면 채울 코스가 없다: 위 문구('바로 가세요')와 같은 버튼을 둔다
                    ActionButton("바로 가기", actions::goDirect, Modifier.weight(2f), tone, sub = "코스 없이 바로 출발")
                } else {
                    ActionButton(
                        if (d.planPending) "시간 확인 중…" else "코스 찾기",
                        actions::planCourses,
                        Modifier.weight(2f).cueRing(ui.cueing(AutoCue.PLAN)),
                        tone,
                        enabled = d.destination != null && !d.planPending,
                    )
                }
                ActionButton("뒤로", actions::driveBack, Modifier.weight(1f))
            }
            d.message?.let { Notice(it, RM.textDim) }
        },
        right = {
            // 아래 요약이 함께 보이게 미니맵을 조금 낮춘다
            MapPanel(ui, (LocalScreenSize.current.mapHeight - 200.dp).coerceAtLeast(260.dp))
            SetupSummary(ui)
            // 픽업 알림은 오른쪽에 둔다 (왼쪽의 '코스 찾기'가 자동 시연 해설이 떠 있어도 화면 안에 보이게)
            if (mode == DriveMode.ON_TIME) Panel { PickupToggle(ui, actions, tone) }
        },
    )
}

/**
 * 온타임 설정: 바로 가도 남는 시간이 1분도 없는지 (늦거나 딱 맞음). 바로 가는 시간을 아직 모르면 false.
 * 목표 칸의 문구·요약과 같은 기준 (남는 시간 = 목표 − 지금 − 바로 가는 시간 − 여유 3분)
 */
private fun noSpareTime(ui: UiState): Boolean {
    val d = ui.drive
    val direct = d.directMs ?: return false
    val target = d.targetAt ?: return false
    return target - ui.now - direct - MARGIN_MINUTES * MINUTE_MS < MINUTE_MS
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
                if (d.mode == DriveMode.ON_TIME && target != null) Stat("목표", DriveText.clock(target), RM.sky, valueSize = 26.sp)
                if (d.mode == DriveMode.LONGER) {
                    Stat("더 달리면", DriveText.clock(ui.now + direct + d.extraMinutes * MINUTE_MS), RM.sand, valueSize = 26.sp)
                }
            }
            val target = d.targetAt
            if (d.mode == DriveMode.ON_TIME && target != null) TimeBudget(ui.now, direct, target)
            if (d.directEstimated) Caption("내비 값이 없어 거리로 추정했어요.")
        }
    }
}

/**
 * 온타임 모드의 시간 나눔: 지금부터 목표까지 = 바로 가는 시간 + 남는 시간 + 여유 3분.
 * 남는 시간을 경치 코스나 근처에서 기다리기로 채운다는 것을 한눈에 보여 준다.
 */
@Composable
private fun TimeBudget(now: Long, direct: Long, target: Long) {
    val total = target - now
    val margin = MARGIN_MINUTES * MINUTE_MS
    val spare = total - direct - margin
    if (total <= 0L || spare < 0L || direct <= 0L) {
        // 왼쪽 목표 칸과 같은 기준·같은 말로 (늦으면 몇 분 늦는지, 아니면 남는 시간이 없다고)
        val late = now + direct - target
        if (late > 30 * SECOND_MS) {
            Caption("바로 가도 약 ${DriveText.duration(late)} 늦어요.", RM.amber)
        } else {
            Caption("바로 가면 시간에 거의 딱 맞아요. 남는 시간이 없어요.", RM.textDim)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(30.dp)
                .clip(RoundedCornerShape(4.dp)),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // 칸이 좁으면(전체의 15% 미만) 글자를 빼고 아래 설명으로 대신한다
            fun label(text: String, ms: Long) = if (ms.toFloat() / total >= 0.15f) "$text ${DriveText.minutes(ms)}분" else ""
            BudgetPart(label("바로 가는 길", direct), RM.blue, Modifier.weight(direct.toFloat()))
            if (spare >= MINUTE_MS) BudgetPart(label("남는 시간", spare), RM.sand, Modifier.weight(spare.toFloat()))
            BudgetPart(label("여유", margin), RM.sky, Modifier.weight(margin.toFloat()))
        }
        Caption(
            "목표까지 ${DriveText.duration(total)} = 바로 가는 길 ${DriveText.duration(direct)} + 남는 시간 ${DriveText.duration(spare)}" +
                " + 여유 ${MARGIN_MINUTES}분 · 남는 시간을 코스로 채워요",
        )
    }
}

@Composable
private fun BudgetPart(label: String, color: Color, modifier: Modifier) {
    Box(
        modifier
            .fillMaxHeight()
            .background(color.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = RM.background,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
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
private fun PlaceChoices(places: List<Place>, selected: Place?, tone: Tone, onSelect: (Place) -> Unit, onSearch: () -> Unit) {
    if (places.isEmpty()) Body("고를 곳이 없어요. '다른 곳 찾기'로 검색하거나 지도에서 골라 주세요.", RM.textDim)
    // 끝에 '다른 곳 찾기'(이름·주소 검색, 지도에서 고르기)를 둔다 (null 자리)
    val items: List<Place?> = places + listOf(null)
    for (row in items.chunked(3)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (p in row) {
                if (p == null) {
                    ActionButton("다른 곳 찾기", onSearch, Modifier.weight(1f), minHeight = 68.dp, textSize = 20.sp, sub = "이름·주소, 지도")
                    continue
                }
                val on = selected?.sameSpot(p) == true
                ActionButton(
                    p.name,
                    { onSelect(p) },
                    Modifier.weight(1f),
                    if (on) tone else Tone.NEUTRAL,
                    minHeight = 68.dp,
                    textSize = 20.sp,
                    // '집'·'회사'처럼 이름과 같으면 아랫줄을 비운다
                    sub = p.kind.label.takeIf { it != p.name },
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("몇 시에 도착할까요?", modifier = Modifier.weight(1f))
            Caption("시각을 누르면 직접 골라요", RM.textFaint)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StepButton("−5분") { actions.shiftTarget(-5) }
            // 시각을 누르면 내비의 '도착 시간 설정'처럼 날짜·오전오후·시·분을 고른다
            Text(
                target?.let { DriveText.clock(it) } ?: "—",
                color = RM.text,
                fontSize = 46.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                style = NumStyle,
                modifier = Modifier
                    .weight(1f)
                    .clip(ButtonShape)
                    .border(1.dp, RM.line, ButtonShape)
                    .clickable(onClick = actions::openTimePicker)
                    .padding(vertical = 4.dp),
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
                spare >= MINUTE_MS -> Body("남는 시간 약 ${DriveText.duration(spare)} · 목표 ${MARGIN_MINUTES}분 전에 닿게 채워요", RM.sky)
                else -> Body("바로 가면 시간에 거의 딱 맞아요. 남는 시간이 없어요.", RM.textDim)
            }
        }
        // 남는 시간이 없으면 쓸 방법도 묻지 않는다 (늦을 때는 위 문구와 '바로 가기'만)
        if (noSpareTime(ui)) return@Panel
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
                if (head != null) Text(head, color = RM.text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                d.destination?.let { Caption("끝낼 곳: ${it.name}") }
                d.message?.let { Caption(it, RM.amber) }
            }
            if (mode == DriveMode.LONGER) FlavorChips(d.flavor, tone, actions)
            d.options.forEachIndexed { i, option ->
                val target = d.targetAt.takeIf { mode == DriveMode.ON_TIME }
                val cue = i == 0 && ui.cueing(AutoCue.FIRST_COURSE)
                CourseCard(i, option, arrivalFor(option, d, ui.now), target, tone, cue) { actions.chooseCourse(option) }
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
private fun CourseCard(
    index: Int,
    option: CourseOption,
    arrival: Long?,
    target: Long?,
    tone: Tone,
    cue: Boolean,
    onChoose: () -> Unit,
) {
    val shape = CardShape
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
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 30.sp,
                    maxLines = 2,
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
        val compact = LocalScreenSize.current.compact
        ActionButton(
            "이 코스로",
            onChoose,
            Modifier.width(if (compact) 118.dp else 150.dp).cueRing(cue),
            tone,
            minHeight = 64.dp,
            textSize = if (compact) 18.sp else 20.sp,
        )
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
    CourseKind.SCENIC -> RM.sand
    CourseKind.DETOUR, CourseKind.DIRECT -> RM.sky
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
                    if (longer) Chip("${d.mood.label} 분위기", RM.sand, fontSize = 14.sp)
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
            CourseStops(ui)
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
                    ActionButton(
                        "+15분 더",
                        { actions.addDriveTime(15) },
                        Modifier.width(150.dp).cueRing(ui.cueing(AutoCue.ADD_TIME)),
                        minHeight = 58.dp,
                        textSize = 19.sp,
                    )
                    // 실내 분위기는 차량이 연결돼 실제로 바꿀 때만 '맞췄어요'라고 쓴다
                    val cabin = if (ui.connection.vehicle == RestMateController.CONNECTED) {
                        "${d.mood.label} 분위기로 무드등·공조를 맞췄어요. 도착하거나 끝내면 원래대로 돌려요."
                    } else {
                        "차량이 연결되면 ${d.mood.label} 분위기로 무드등·공조를 맞춰요."
                    }
                    Caption(cabin, modifier = Modifier.weight(1f))
                } else {
                    ActionButton("+5분", { actions.addDriveTime(5) }, Modifier.width(120.dp), minHeight = 58.dp, textSize = 19.sp)
                    ActionButton("+10분", { actions.addDriveTime(10) }, Modifier.width(120.dp), minHeight = 58.dp, textSize = 19.sp)
                    Caption("약속이 늦춰지면 목표를 미뤄요. 더 돌 코스가 있으면 채워요.", modifier = Modifier.weight(1f))
                }
            }
        },
        right = {
            MapWithPickup(ui)
        },
    )
}

/** 미니맵. 픽업 알림을 켰으면 미니맵을 조금 줄이고 아래에 픽업 알림 카드를 둔다 (왼쪽 버튼들이 밀리지 않게). */
@Composable
private fun MapWithPickup(ui: UiState) {
    if (!ui.drive.pickupOn()) {
        MapPanel(ui)
        return
    }
    MapPanel(ui, (LocalScreenSize.current.mapHeight - PICKUP_CARD_HEIGHT).coerceAtLeast(260.dp))
    PickupPanel(ui)
}

/** 코스 한 곳: 출발 · 경유지 · 쉼터 · 목적지. [detailColor]는 아랫줄 색 (늦을 것 같으면 호박색) */
private data class CourseStop(
    val name: String,
    val detail: String,
    val color: Color,
    val done: Boolean,
    val detailColor: Color = color,
)

/**
 * 고른 코스를 세로로: 출발 → 경유지(지났는지) → 목적지(도착 예상). 미니맵의 점과 같은 색을 쓴다.
 * 달리는 중과 도착 화면에서 '어떤 길로 시간을 맞췄는지'를 보여 준다.
 */
@Composable
private fun CourseStops(ui: UiState) {
    val d = ui.drive
    val dest = d.destination ?: return
    val chosen = d.chosen
    val remaining = if (d.viaActive) (ui.route?.via ?: chosen?.via).orEmpty() else emptyList()
    val stopArea = chosen?.restArea
    val arrived = d.step == DriveStep.ARRIVED
    val stops = ArrayList<CourseStop>()
    stops += CourseStop("출발", ui.driveStartedAt?.let { DriveText.clock(it) }.orEmpty(), RM.textDim, done = true)
    for (p in chosen?.via.orEmpty()) {
        val isRest = stopArea != null && Place(stopArea.name, stopArea.position).sameSpot(p)
        val passed = arrived || remaining.none { it.sameSpot(p) }
        val arriveAt = d.stopArriveAt
        val departAt = d.stopDepartAt
        val detail = when {
            passed -> "지났어요"
            isRest && arriveAt != null && departAt != null ->
                "${DriveText.clock(arriveAt)} 도착 → ${DriveText.clock(departAt)} 출발"
            isRest -> "들러서 기다렸다 가요"
            else -> listOf("경유").plus(chosen?.tags.orEmpty().map { it.label }).joinToString(" · ")
        }
        stops += CourseStop(p.name, detail, if (isRest) RM.amber else RM.sand, done = passed)
    }
    val eta = if (arrived) d.arrivedAt else d.arrivalEstimate
    val target = d.targetAt
    val targetText = target?.let { " · ${if (d.mode == DriveMode.LONGER) "예정" else "목표"} ${DriveText.clock(it)}" }.orEmpty()
    val destDetail = when {
        eta == null -> ""
        arrived -> "${DriveText.clock(eta)} 도착$targetText"
        else -> "${DriveText.clock(eta)} 도착 예상$targetText"
    }
    val late = !arrived && d.mode == DriveMode.ON_TIME && d.status == FitStatus.LATE
    stops += CourseStop(dest.name, destDetail, RM.sky, done = arrived, detailColor = if (late) RM.amber else RM.sky)
    Panel(spacing = 0.dp) {
        Label("코스", modifier = Modifier.padding(bottom = 12.dp))
        stops.forEachIndexed { i, stop -> StopRow(stop, last = i == stops.lastIndex) }
    }
}

@Composable
private fun StopRow(stop: CourseStop, last: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        Column(
            Modifier
                .width(30.dp)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .size(16.dp)
                    .background(if (stop.done && !last) stop.color.copy(alpha = 0.45f) else stop.color, CircleShape),
            )
            if (!last) {
                Box(
                    Modifier
                        .padding(vertical = 4.dp)
                        .width(2.dp)
                        .weight(1f)
                        .background(RM.line),
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 8.dp, bottom = if (last) 0.dp else 16.dp),
        ) {
            Text(
                stop.name,
                color = if (stop.done && !last) RM.textDim else RM.text,
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (stop.detail.isNotEmpty()) {
                Text(stop.detail, color = if (stop.done && !last) RM.textFaint else stop.detailColor, fontSize = 16.sp, style = NumStyle)
            }
        }
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
                Text(d.message ?: "도착했어요.", color = RM.text, fontSize = 40.sp, fontWeight = FontWeight.SemiBold, lineHeight = 50.sp)
                val arrived = d.arrivedAt
                val target = d.targetAt
                if (arrived != null && target != null) {
                    // 롱웨이 모드의 시각은 마감이 아니라 예정이다.
                    val gap = DriveText.gapMs(arrived, target)
                    // 좁은 화면에서는 시각을 조금 줄여 '약 2분 늦어요' 칩이 잘리지 않게 한다
                    val compact = LocalScreenSize.current.compact
                    val size = if (compact) 30.sp else 36.sp
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 24.dp)) {
                        Stat("도착", DriveText.clock(arrived), color, valueSize = size)
                        Stat(if (longer) "예정" else "목표", DriveText.clock(target), RM.text, valueSize = size)
                        val text = if (longer) DriveText.fitLonger(d.status, gap) else DriveText.fit(d.status, gap, (MARGIN_MINUTES + 2) * MINUTE_MS)
                        Chip(text, color, fontSize = 18.sp)
                    }
                }
            }
            CourseStops(ui)
            if (d.pickupOn()) {
                // 픽업: 세울 곳이 없으면 한 바퀴 돌고 다시 오기 (픽업할 사람에게 다시 오는 시각을 알린다)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton(
                        "자리 없음",
                        actions::circleForSpot,
                        Modifier.weight(1f).cueRing(ui.cueing(AutoCue.NO_SPOT)),
                        modeTone(d.mode),
                        sub = "한 바퀴 돌고 다시 오기",
                    )
                    ActionButton("처음으로", actions::finishDrive, Modifier.weight(1f))
                }
            } else {
                ActionButton("처음으로", actions::finishDrive, Modifier.fillMaxWidth(), modeTone(d.mode))
            }
        },
        right = {
            MapWithPickup(ui)
        },
    )
}

private val EXTRA_OPTIONS = listOf(15, 30, 45, 60)
private const val MAX_PLACE_CHOICES = 6
private const val DEFAULT_DIRECT_MS = 20 * MINUTE_MS
