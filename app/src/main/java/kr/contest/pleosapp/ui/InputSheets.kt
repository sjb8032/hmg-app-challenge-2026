package kr.contest.pleosapp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kr.contest.pleosapp.app.InputSheet
import kr.contest.pleosapp.app.PlacePurpose
import kr.contest.pleosapp.app.RestMateActions
import kr.contest.pleosapp.app.RestMateController
import kr.contest.pleosapp.app.UiState
import kr.contest.pleosapp.core.ClockPick
import kr.contest.pleosapp.core.DriveEngine
import kr.contest.pleosapp.core.DriveText
import kr.contest.pleosapp.core.Geo
import kr.contest.pleosapp.core.LatLng
import kr.contest.pleosapp.core.MINUTE_MS
import kr.contest.pleosapp.core.MapCamera
import kr.contest.pleosapp.core.MapFrame
import kr.contest.pleosapp.core.PlaceHit
import kr.contest.pleosapp.core.PlaceSearch
import kr.contest.pleosapp.core.SECOND_MS
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow

/*
 * 실제 내비처럼 직접 넣는 창: 목적지·끝낼 곳·시연 출발지 찾기(이름·주소 검색, 지도에서 고르기)와 도착 목표 시각 고르기.
 * 왼쪽 화면 위를 덮어 띄우고, 오른쪽 차 상태·시연 조작은 그대로 보인다.
 */

/** 화면 위에 띄운 입력 창 */
@Composable
internal fun InputSheetOverlay(sheet: InputSheet, ui: UiState, actions: RestMateActions, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(RM.background)
            // 아래 화면이 눌리지 않게 막는다 (누른 자국 없이)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
    ) {
        when (sheet) {
            is InputSheet.FindPlace -> FindPlaceSheet(sheet, ui, actions)
            InputSheet.PickTime -> PickTimeSheet(ui, actions)
        }
    }
}

@Composable
private fun SheetHeader(title: String, sub: String?, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = RM.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            if (sub != null) Caption(sub)
        }
        ActionButton("닫기", onClose, Modifier.width(120.dp), minHeight = 58.dp, textSize = 20.sp)
    }
}

// ---------------------------------------------------------------- 장소 찾기

/** 검색어 없이 눌러 볼 수 있는 곳 (에뮬레이터에서 한글을 치기 번거로울 때) */
private val QUICK_KEYWORDS = listOf("서울역", "강남역", "광화문", "롯데월드타워", "김포공항", "강릉역")

@Composable
private fun FindPlaceSheet(sheet: InputSheet.FindPlace, ui: UiState, actions: RestMateActions) {
    var text by rememberSaveable(sheet.purpose) { mutableStateOf(sheet.query) }
    val car = ui.core.position ?: ui.drive.position
    val sub = when (sheet.purpose) {
        PlacePurpose.NAVI_DESTINATION ->
            if (ui.demoMode) "고르면 모의 내비가 그곳으로 안내해요." else "고르면 차량 내비에 경로를 요청해요."
        PlacePurpose.END_PLACE -> "고른 곳까지 몇 분 걸리는지 보고 남는 시간을 채워요."
        PlacePurpose.DEMO_START -> "모의 차를 그곳에 세워 두고, 가상 시계는 지금 시각부터 흘러요."
    }
    Column(Modifier.fillMaxSize().padding(4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SheetHeader(sheet.purpose.title, sub, actions::closeSheet)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MAX_QUERY) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = TextStyle(fontSize = 24.sp, color = RM.text),
                // 안내 글은 흐리게 (입력한 글자와 헷갈리지 않게. 색을 주지 않으면 기기에서 진한 글자로 나온다)
                placeholder = { Text(sheet.purpose.hint, color = RM.textFaint, fontSize = 22.sp) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { actions.searchPlaces(text) }),
                shape = ButtonShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = RM.text,
                    unfocusedTextColor = RM.text,
                    focusedContainerColor = RM.card,
                    unfocusedContainerColor = RM.card,
                    focusedBorderColor = RM.sky,
                    unfocusedBorderColor = RM.line,
                    cursorColor = RM.sky,
                    focusedPlaceholderColor = RM.textFaint,
                    unfocusedPlaceholderColor = RM.textFaint,
                ),
            )
            ActionButton(
                "검색",
                { actions.searchPlaces(text) },
                Modifier.width(130.dp),
                Tone.SKY,
                minHeight = 64.dp,
                textSize = 22.sp,
                enabled = text.isNotBlank() && !sheet.searching,
            )
            ActionButton(
                if (sheet.onMap) "목록으로" else "지도에서 고르기",
                { actions.showPlaceMap(!sheet.onMap) },
                Modifier.width(200.dp),
                if (sheet.onMap) Tone.SKY else Tone.NEUTRAL,
                minHeight = 64.dp,
                textSize = 20.sp,
            )
        }
        if (sheet.onMap) {
            MapPicker(
                start = sheet.mapCenter ?: car ?: DEFAULT_CENTER,
                car = car,
                destination = ui.drive.destination?.position ?: ui.drive.naviDestination?.position,
                busy = sheet.locating,
                confirm = sheet.purpose.confirm,
                onPick = actions::pickPlaceOnMap,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            val showingResults = sheet.query.isNotEmpty()
            if (!showingResults) {
                Label("바로 찾기")
                val perRow = if (LocalScreenSize.current.compact) 3 else QUICK_KEYWORDS.size
                for (row in QUICK_KEYWORDS.chunked(perRow)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (k in row) {
                            ActionButton(
                                k,
                                {
                                    text = k
                                    actions.searchPlaces(k)
                                },
                                Modifier.weight(1f),
                                minHeight = 54.dp,
                                textSize = 18.sp,
                            )
                        }
                    }
                }
            }
            if (sheet.searching) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = RM.sky, strokeWidth = 3.dp)
                    Caption(sheet.note ?: "'${sheet.query}' 찾는 중이에요…")
                }
            } else {
                sheet.note?.let { Notice(it, RM.amber) }
            }
            val list = if (showingResults) sheet.results else sheet.suggestions
            if (!showingResults && list.isNotEmpty()) Label("최근에 고른 곳 · 즐겨찾기")
            if (showingResults && list.isNotEmpty()) Label("찾은 곳 ${list.size}곳")
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list) { hit -> PlaceRow(hit, car, sheet.purpose.confirm) { actions.choosePlace(hit) } }
            }
        }
        Caption("검색: ${sheet.service}", RM.textFaint)
    }
}

@Composable
private fun PlaceRow(hit: PlaceHit, car: LatLng?, confirm: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .background(RM.card, CardShape)
            .border(1.dp, RM.border, CardShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PinGlyph(RM.sky, Modifier.size(26.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    hit.name,
                    color = RM.text,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                hit.category?.let { Chip(it, RM.textDim, fontSize = 13.sp) }
            }
            if (hit.address.isNotBlank()) {
                Text(hit.address, color = RM.textDim, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // '지금 차가 있는 곳'은 거리를 붙이지 않는다 (늘 0m)
        if (car != null && hit.name != RestMateController.HERE_NAME) {
            Text(
                PlaceSearch.distanceLabel(Geo.distanceMeters(car, hit.position)),
                color = RM.textDim,
                fontSize = 18.sp,
                style = NumStyle,
            )
        }
        Text("$confirm ›", color = RM.sky, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * 지도에서 고르기: 지도를 끌거나 두 손가락으로 키워 가운데 핀을 맞추고 '이 위치를 …'을 누른다 (지도 앱의 '지도에서 선택'처럼).
 * 바탕은 미니맵과 같은 OpenStreetMap 타일이다.
 */
@Composable
private fun MapPicker(
    start: LatLng,
    car: LatLng?,
    destination: LatLng?,
    busy: Boolean,
    confirm: String,
    onPick: (LatLng) -> Unit,
    modifier: Modifier,
) {
    var cx by remember { mutableDoubleStateOf(MapFrame.worldX(start.lon)) }
    var cy by remember { mutableDoubleStateOf(MapFrame.worldY(start.lat)) }
    var zoom by remember { mutableFloatStateOf(PICK_ZOOM) }
    val density = LocalDensity.current.density
    fun pxPerWorld(z: Float): Double = MapFrame.TILE_DP * density * 2.0.pow(z.toDouble())
    fun center(): LatLng = LatLng(MapFrame.latOf(cy), cx * 360.0 - 180.0)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                // 타일은 화면보다 넓게 그려지므로 칸 밖(제목·버튼 위)으로 번지지 않게 자른다
                .clip(CardShape)
                .background(RM.map, CardShape)
                .border(1.dp, RM.border, CardShape),
        ) {
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            val camera = MapCamera(cx, cy, pxPerWorld(zoom), 0.0, w / 2f, h / 2f, w, h)
            val inspection = LocalInspectionMode.current
            val tilesOn = MapTiles.mode == MapTiles.Mode.BLOCKING || (!inspection && MapTiles.mode == MapTiles.Mode.ASYNC)
            val layout = if (tilesOn) camera.tiles(density) else null
            val keys = layout?.let { l -> l.tiles.map { TileKey(l.zoom, it.x, it.y) } }.orEmpty()
            if (MapTiles.mode == MapTiles.Mode.BLOCKING) {
                remember(keys) { MapTiles.request(keys) }
            } else {
                LaunchedEffect(keys) { MapTiles.request(keys) }
            }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            val p = pxPerWorld(zoom)
                            cx = (cx - pan.x / p).coerceIn(0.0, 1.0)
                            cy = (cy - pan.y / p).coerceIn(0.0, 1.0)
                            zoom = (zoom + log2(gestureZoom)).coerceIn(MIN_PICK_ZOOM, MAX_PICK_ZOOM)
                        }
                    },
            ) {
                if (layout != null) drawTiles(layout)
                fun at(p: LatLng): Offset = camera.screen(p).let { Offset(it.x, it.y) }
                destination?.let {
                    drawCircle(RM.sky.copy(alpha = 0.25f), 18.dp.toPx(), at(it))
                    drawCircle(RM.sky, 7.dp.toPx(), at(it))
                }
                car?.let {
                    drawCircle(Color.White, 11.dp.toPx(), at(it))
                    drawCircle(RM.blue, 8.dp.toPx(), at(it))
                }
                // 가운데 십자
                val c = Offset(w / 2f, h / 2f)
                val arm = 14.dp.toPx()
                drawLine(RM.text.copy(alpha = 0.5f), c - Offset(arm, 0f), c + Offset(arm, 0f), 2.dp.toPx())
                drawLine(RM.text.copy(alpha = 0.5f), c - Offset(0f, arm), c + Offset(0f, arm), 2.dp.toPx())
                drawCircle(RM.text.copy(alpha = 0.5f), 4.dp.toPx(), c, style = Stroke(2.dp.toPx()))
            }
            // 가운데 핀: 끝이 가운데를 가리키게 위로 올려 그린다
            PinGlyph(RM.sky, Modifier.align(Alignment.Center).padding(bottom = 52.dp).size(width = 40.dp, height = 52.dp))
            Column(
                Modifier.align(Alignment.TopEnd).padding(12.dp).width(64.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionButton("+", { zoom = (zoom + 1f).coerceAtMost(MAX_PICK_ZOOM) }, Modifier.fillMaxWidth(), minHeight = 56.dp, textSize = 26.sp)
                ActionButton("−", { zoom = (zoom - 1f).coerceAtLeast(MIN_PICK_ZOOM) }, Modifier.fillMaxWidth(), minHeight = 56.dp, textSize = 26.sp)
            }
            Text(
                "지도를 끌어 가운데 핀을 맞추세요",
                color = RM.text,
                fontSize = 16.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .background(RM.mapLabel, RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            val credit = if (keys.any { MapTiles.get(it) != null }) MapTiles.ATTRIBUTION else "도식 지도"
            Text(
                credit,
                color = RM.textDim,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(RM.mapLabel, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (car != null) {
                ActionButton(
                    "차 위치로",
                    {
                        cx = MapFrame.worldX(car.lon)
                        cy = MapFrame.worldY(car.lat)
                    },
                    Modifier.width(170.dp),
                    minHeight = 64.dp,
                    textSize = 20.sp,
                )
            }
            ActionButton(
                if (busy) "주소 찾는 중…" else "이 위치를 $confirm",
                { onPick(center()) },
                Modifier.weight(1f),
                Tone.SKY,
                minHeight = 64.dp,
                textSize = 22.sp,
                enabled = !busy,
            )
        }
    }
}

// ---------------------------------------------------------------- 도착 목표 시각

/**
 * 도착 목표 시각 고르기 (내비의 '도착 시간 설정'처럼): 오늘·내일, 오전·오후, 시·분 바퀴 (분은 5분 단위).
 * 고르는 동안 바로 가면 언제 닿는지와 남는 시간을 같이 보여 준다. 지금부터 5분 뒤보다 이르게는 정할 수 없다.
 */
@Composable
private fun PickTimeSheet(ui: UiState, actions: RestMateActions) {
    val now = ui.now
    val d = ui.drive
    val direct = d.directMs
    val initial = d.targetAt ?: DriveEngine.targetChoices(now, direct ?: 20 * MINUTE_MS).first()
    // '오늘'은 창을 연 날 (열어 둔 채 자정을 넘겨도 고른 날짜가 밀리지 않게)
    val baseDay = remember { ClockPick.dayStart(now) }
    val start = remember { ClockPick.of(baseDay, initial) }
    var day by remember { mutableIntStateOf(start.day) }
    var pm by remember { mutableStateOf(start.pm) }
    var hour12 by remember { mutableIntStateOf(start.hour12) }
    var minute by remember { mutableIntStateOf(start.minute) }
    val minutes = remember { ClockPick.minuteChoices(start.minute) }
    val at = ClockPick(day, pm, hour12, minute).at(baseDay)
    val earliest = DriveEngine.roundUp(now + 5 * MINUTE_MS, 5 * MINUTE_MS)
    val tooEarly = at < earliest
    Column(Modifier.fillMaxSize().padding(4.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SheetHeader("도착 목표 시각", d.destination?.let { "${it.name}에 몇 시에 닿을까요?" }, actions::closeSheet)
        // 남는 높이 가운데에 둔다 (위에 붙으면 아래가 크게 비어 보인다)
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Label("날짜")
                Segmented(listOf("오늘", "내일"), day) { day = it }
                Label("오전·오후")
                Segmented(listOf("오전", "오후"), if (pm) 1 else 0) { pm = it == 1 }
                Spacer(Modifier.height(8.dp))
                Panel(border = if (tooEarly) RM.amber else RM.sky) {
                    Label(if (day == 0) "오늘" else "내일")
                    Text(DriveText.clock(at), color = RM.text, fontSize = 46.sp, fontWeight = FontWeight.SemiBold, style = NumStyle)
                    when {
                        tooEarly -> Caption("지금부터 5분 뒤(${DriveText.clock(earliest)})보다 이르게는 정할 수 없어요.", RM.amber)
                        direct != null -> {
                            val late = now + direct - at
                            // 목표 패널과 같은 기준: 남는 시간 = 목표 − 지금 − 바로 가는 시간 − 여유 3분
                            val spare = -late - MARGIN_MINUTES * MINUTE_MS
                            Caption(
                                when {
                                    late > 30 * SECOND_MS -> "바로 가도 약 ${DriveText.duration(late)} 늦어요."
                                    spare >= MINUTE_MS ->
                                        "바로 가면 ${DriveText.clock(now + direct)} 도착 · 목표 ${MARGIN_MINUTES}분 전에 닿게 하면" +
                                            " 남는 시간 약 ${DriveText.duration(spare)}"
                                    else -> "바로 가면 시간에 거의 딱 맞아요."
                                },
                                if (late > 30 * SECOND_MS) RM.amber else RM.textDim,
                            )
                        }
                        else -> Caption("바로 가는 시간을 내비에 묻는 중이에요.")
                    }
                }
            }
            Row(Modifier.weight(1.1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Wheel(12, hour12 - 1, { "${it + 1}시" }, { hour12 = it + 1 }, Modifier.weight(1f))
                Wheel(
                    minutes.size,
                    minutes.indexOf(minute).coerceAtLeast(0),
                    { String.format(java.util.Locale.ROOT, "%02d분", minutes[it]) },
                    { minute = minutes[it] },
                    Modifier.weight(1f),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("취소", actions::closeSheet, Modifier.weight(1f), minHeight = 72.dp, textSize = 22.sp)
            ActionButton(
                "이 시각으로 정하기",
                {
                    actions.setTarget(at)
                    actions.closeSheet()
                },
                Modifier.weight(2f),
                Tone.SKY,
                minHeight = 72.dp,
                textSize = 24.sp,
                enabled = !tooEarly,
            )
        }
    }
}

/** 두세 개 중 하나 고르기 (오늘·내일, 오전·오후) */
@Composable
private fun Segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        labels.forEachIndexed { i, label ->
            ActionButton(
                label,
                { onSelect(i) },
                Modifier.weight(1f),
                if (i == selected) Tone.SKY else Tone.NEUTRAL,
                minHeight = 64.dp,
                textSize = 22.sp,
            )
        }
    }
}

/**
 * 돌려서 고르는 바퀴 (시·분). 다섯 줄을 보여 주고 가운데 줄이 고른 값이다. 놓으면 가까운 줄에 맞춰 선다.
 * 위아래에 빈 줄을 두 개씩 두어 첫 값·끝 값도 가운데에 올 수 있다. 줄을 누르면 그 값으로 돌린다.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Wheel(count: Int, selected: Int, label: (Int) -> String, onSelect: (Int) -> Unit, modifier: Modifier) {
    // 달리는 차 안에서도 손가락으로 집기 쉽게 줄을 크게 둔다
    val rowHeight = 80.dp
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selected.coerceIn(0, count - 1))
    val fling = rememberSnapFlingBehavior(state)
    val scope = rememberCoroutineScope()
    val center by remember {
        derivedStateOf {
            val bump = if (state.firstVisibleItemScrollOffset > rowPx / 2f) 1 else 0
            (state.firstVisibleItemIndex + bump).coerceIn(0, count - 1)
        }
    }
    LaunchedEffect(center) { if (center != selected) onSelect(center) }
    Box(modifier.height(rowHeight * 5)) {
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(rowHeight)
                .background(RM.sky.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                .border(1.dp, RM.sky.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
        )
        LazyColumn(state = state, flingBehavior = fling, modifier = Modifier.fillMaxSize()) {
            items(count + 4) { i ->
                val value = i - 2
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .then(
                            if (value in 0 until count) {
                                Modifier.clickable { scope.launch { state.animateScrollToItem(value) } }
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (value in 0 until count) {
                        val near = abs(value - center)
                        Text(
                            label(value),
                            color = if (near == 0) RM.text else RM.textFaint,
                            fontSize = if (near == 0) 38.sp else 28.sp,
                            fontWeight = if (near == 0) FontWeight.SemiBold else FontWeight.Normal,
                            style = NumStyle,
                        )
                    }
                }
            }
        }
    }
}

private const val MAX_QUERY = 60
private const val PICK_ZOOM = 15f
private const val MIN_PICK_ZOOM = 6f
private const val MAX_PICK_ZOOM = 18f

/** 위치를 모를 때 지도에서 고르기의 처음 가운데 (서울시청) */
private val DEFAULT_CENTER = LatLng(37.5663, 126.9779)
