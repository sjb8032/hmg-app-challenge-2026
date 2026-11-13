package kr.contest.pleosapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.contest.pleosapp.app.DriveLeg
import kr.contest.pleosapp.app.UiState
import kr.contest.pleosapp.core.DriveStep
import kr.contest.pleosapp.core.Geo
import kr.contest.pleosapp.core.LatLng
import kr.contest.pleosapp.core.MapFrame
import kr.contest.pleosapp.core.Phase
import kr.contest.pleosapp.core.Place
import kr.contest.pleosapp.core.MapCamera
import kr.contest.pleosapp.core.RoadPath
import kr.contest.pleosapp.core.TileLayout
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * 위에서 내려다본 경로 미니맵. 바탕에 OpenStreetMap 지도 타일을 깔고 그 위에 그린다:
 * 지나온 길(회색) · 갈 길(모드 색) · 코스 후보(번호) · 경유지 · 쉼터(P) · 목적지 · 진행 방향을 가리키는 차.
 * 내비 SDK로는 경로 모양을 받을 수 없어 경유지 사이를 곧은 선으로 잇는다.
 * 인터넷이 안 되어 타일을 못 받으면 격자 바탕의 도식 지도로 보인다 (오른쪽 아래에 '도식 지도').
 */

/** 미니맵에 찍는 곳 */
internal enum class MarkKind { ORIGIN, SCENIC, STOP, DESTINATION, REST_TARGET, REST_AHEAD, CANDIDATE }

/** @param badge 점 위에 얹는 번호·글자 (코스 후보 "1", 쉼터 "P") */
internal data class MapMark(
    val position: LatLng,
    val kind: MarkKind,
    val label: String?,
    val color: Color,
    val badge: String? = null,
)

internal data class MapLine(val points: List<LatLng>, val color: Color, val width: Dp, val dashed: Boolean = false)

/** 주행 화면: 차([center])를 아래쪽 가운데 두고 진행 방향([heading])을 위로, 화면 너비에 [spanMeters]를 담는다 */
internal data class FollowView(val center: LatLng, val heading: Double, val spanMeters: Double)

/** 미니맵 한 장면 */
internal data class MapScene(
    val trail: List<LatLng> = emptyList(),
    val lines: List<MapLine> = emptyList(),
    val marks: List<MapMark> = emptyList(),
    val car: LatLng? = null,
    val heading: Double? = null,
    val carColor: Color = RM.blue,
    /** 이 지점들만 화면에 담는다 (가까운 곳을 크게 볼 때). null이면 모두 */
    val focus: List<LatLng>? = null,
    /** 달리는 중이면 주행 화면. null이면 북쪽이 위인 전체 보기 */
    val follow: FollowView? = null,
    /** 도로 모양을 따라 그린 길이 하나라도 있는지 (출처 표기) */
    val roads: Boolean = false,
) {
    val isEmpty: Boolean get() = car == null && marks.isEmpty() && trail.isEmpty()

    /** 화면에 담을 지점 (지나온 길은 성기게) */
    fun fitPoints(): List<LatLng> {
        focus?.let { if (it.isNotEmpty()) return it }
        val out = ArrayList<LatLng>()
        car?.let { out += it }
        trail.forEachIndexed { i, p -> if (i % 6 == 0 || i == trail.lastIndex) out += p }
        for (m in marks) out += m.position
        for (l in lines) l.points.forEachIndexed { i, p -> if (i % 4 == 0 || i == l.points.lastIndex) out += p }
        return out
    }
}

/** 코스 후보 번호별 색 (지도의 선·번호와 코스 카드의 번호가 같은 색) */
internal fun candidateColor(index: Int): Color = when (index % 4) {
    0 -> RM.sky
    1 -> RM.sand
    2 -> RM.blue
    else -> RM.amber
}

// ---------------------------------------------------------------- 도로 모양

private fun lerp(a: LatLng, b: LatLng, t: Double) = LatLng(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)

/**
 * 화면에 그릴 시연 구간 하나. 모의 차는 [leg.from][DriveLeg.from]에서 곧게 달리고, 화면에는 [from]에서 시작하는 도로 길([road])의
 * 같은 비율 지점에 그린다. 도로 길이 없으면 곧은 선.
 * @param carNow 지금 달리는 구간이면 모의 차의 실제 자리 (도로 길이 없을 때 차를 그 자리에 그린다)
 */
private class LegView(val leg: DriveLeg, val from: LatLng, val road: List<LatLng>?, carNow: LatLng?) {
    val f = leg.progress.toDouble()

    /** 모의 차가 곧게 달려 닿은 자리 */
    val straightEnd: LatLng = lerp(leg.from, leg.to, f)

    /** 화면에 그린 끝 자리 */
    val end: LatLng = road?.let { RoadPath.pointAt(it, f) } ?: carNow ?: lerp(from, leg.to, f)
    val driven: List<LatLng> = road?.let { RoadPath.head(it, f) } ?: listOf(from, end)
    val left: List<LatLng> = road?.let { RoadPath.tail(it, f) } ?: listOf(end, leg.to)
    val heading: Double? = road?.let { RoadPath.bearingAt(it, f) }
}

/**
 * 시연 구간들 (지난 구간 → 지금 구간). 도중에 경로가 바뀐 곳에서 시작한 구간은 앞 구간을 화면에 그린 자리(도로 위)에서 이어 그린다
 * (모의 차는 곧은 선 위에 있어 도로에서 떨어져 있을 수 있다). 이때는 달리던 방향을 함께 넘겨, 길찾기가 맞은편 차로에서
 * 출발해 돌아오는 길을 내지 않게 한다. [road]로 두 곳 사이 도로 길을 읽는다 (출발 방향을 알면 셋째 값).
 */
private fun legViews(ui: UiState, road: (LatLng, LatLng, Double?) -> List<LatLng>?): List<LegView> {
    val all = ui.legs + listOfNotNull(ui.leg)
    val out = ArrayList<LegView>(all.size)
    all.forEachIndexed { i, leg ->
        val prev = out.lastOrNull()
        val chained = prev != null && Geo.distanceMeters(leg.from, prev.straightEnd) < CHAIN_METERS
        val from = if (chained) prev!!.end else leg.from
        // 도중에 바뀐 곳(앞 구간을 다 달리지 않음)에서만 달리던 방향을 이어 간다
        val bearing = if (chained && prev!!.leg.progress < 1f) {
            prev.road?.let { RoadPath.bearingAt(it, prev.f, aheadMeters = 60.0) } ?: Geo.bearingDegrees(prev.leg.from, prev.leg.to)
        } else {
            null
        }
        val current = ui.leg != null && i == all.lastIndex
        val carNow = if (current && from == leg.from) ui.core.position else null
        out += LegView(leg, from, road(from, leg.to, bearing), carNow)
    }
    return out
}

private const val CHAIN_METERS = 30.0

/** 장면을 만드는 동안 읽은 도로 길 (출처 표기를 정하려고) */
private class RoadUse {
    var used = false

    fun get(a: LatLng, b: LatLng): List<LatLng>? = RoadRoutes.get(a, b)?.also { used = true }

    /** 두 곳 사이 도로 길. 아직 없으면 곧은 선 */
    fun between(a: LatLng, b: LatLng): List<LatLng> = get(a, b) ?: listOf(a, b)

    /** 차에서 [stops]를 차례로 지나는 길: 지금 달리는 구간이면 도로 길의 남은 부분부터 */
    fun ahead(current: LegView?, car: LatLng, stops: List<LatLng>): List<LatLng> {
        if (stops.isEmpty()) return emptyList()
        val parts = ArrayList<List<LatLng>>()
        parts += if (current != null && samePlace(current.leg.to, stops.first())) current.left else listOf(car, stops.first())
        for (i in 0 until stops.lastIndex) parts += between(stops[i], stops[i + 1])
        return RoadPath.join(parts)
    }
}

/** 지금 장면에서 갈 곳들 (남은 경유지 + 목적지). 쉼터로 가는 중이면 쉼터 + 목적지 */
private fun stopsAhead(ui: UiState): List<LatLng> {
    val s = ui.core
    val route = ui.route
    if (s.phase == Phase.TO_REST_AREA) {
        val area = s.target?.area?.position ?: return emptyList()
        val dest = route?.destination?.position
        return if (dest != null && !samePlace(dest, area)) listOf(area, dest) else listOf(area)
    }
    val d = ui.drive
    if (s.phase != Phase.DRIVING) return emptyList()
    return when (d.step) {
        DriveStep.HOME -> {
            val dest = route?.destination ?: d.naviDestination ?: return emptyList()
            route?.via.orEmpty().map { it.position } + dest.position
        }
        DriveStep.ACTIVE -> {
            val dest = d.destination ?: route?.destination ?: return emptyList()
            val via = when {
                !d.viaActive -> emptyList()
                route != null -> route.via
                else -> d.chosen?.via.orEmpty()
            }
            via.map { it.position } + dest.position
        }
        else -> emptyList()
    }
}

/** 차가 서 있는지 (코스 후보를 고를 때 차에서 경유지까지 도로 길을 받아도 되는지) */
private fun standing(ui: UiState): Boolean = ui.demoClockHeld || (!ui.demoDriving && (ui.core.speedKmh ?: 0.0) < 3.0)

/**
 * 지금 장면에 필요한 도로 길 구간. 위치가 계속 바뀌는 차에서 시작하는 구간은 묻지 않는다
 * (시연의 달리는 구간은 시작점이 고정이라 묻는다, 코스 후보는 차가 서 있을 때만).
 */
internal fun roadLegsFor(ui: UiState): List<RoadLeg> {
    val out = LinkedHashSet<RoadLeg>()
    legViews(ui) { a, b, bearing ->
        out += RoadLeg(a, b, bearing)
        RoadRoutes.get(a, b)
    }
    val stops = stopsAhead(ui)
    for (i in 0 until stops.lastIndex) out += RoadLeg(stops[i], stops[i + 1])
    val d = ui.drive
    val car = ui.core.position
    if (ui.core.phase == Phase.DRIVING && d.step == DriveStep.CHOOSE) {
        val dest = d.destination
        if (dest != null) {
            for (option in d.options) {
                val chain = option.via.map { it.position } + dest.position
                if (car != null && standing(ui)) out += RoadLeg(car, chain.first())
                for (i in 0 until chain.lastIndex) out += RoadLeg(chain[i], chain[i + 1])
            }
        }
    }
    return out.toList()
}

/** 지금 상태에 맞는 미니맵 장면 */
internal fun mapSceneFor(ui: UiState): MapScene {
    val s = ui.core
    val d = ui.drive
    val roads = RoadUse()
    val views = legViews(ui) { a, b, _ -> roads.get(a, b) }
    val current = views.lastOrNull()?.takeIf { ui.leg != null }
    // 차: 시연에서는 지금 구간을 온 비율만큼 도로 길을 따라간 자리
    val car = current?.end ?: s.position
    val carHeading = current?.heading ?: s.headingDeg
    val route = ui.route
    val marks = ArrayList<MapMark>()
    val lines = ArrayList<MapLine>()
    val mode = d.mode
    var carColor = if (mode != null && d.step == DriveStep.ACTIVE) modeColor(mode) else RM.blue
    var showCar = true
    var focus: List<LatLng>? = null

    // 지나온 길: 시연에서는 달린 구간들을 도로 모양을 따라, 실제 주행에서는 받은 위치 그대로
    val trail = if (views.isEmpty()) ui.trail else RoadPath.join(views.map { it.driven })

    val origin = ui.origin
    if (origin != null && trail.size > 1 && (car == null || !samePlace(origin, car))) {
        marks += MapMark(origin, MarkKind.ORIGIN, "출발", RM.textDim)
    }

    when (s.phase) {
        Phase.TO_REST_AREA -> {
            carColor = RM.amber
            val area = s.target?.area
            val dest = route?.destination
            if (area != null) {
                if (car != null) lines += MapLine(roads.ahead(current, car, listOf(area.position)), RM.amber, 6.dp)
                if (dest != null && !samePlace(dest.position, area.position)) {
                    lines += MapLine(roads.between(area.position, dest.position), RM.textFaint, 3.dp, dashed = true)
                    marks += destinationMark(dest)
                }
                marks += MapMark(area.position, MarkKind.REST_TARGET, area.name, RM.amber, badge = "P")
                // 쉼터까지를 크게 본다 (목적지는 점선 방향으로만)
                if (car != null) focus = listOf(car, area.position) + trail.takeLast(FOCUS_TRAIL)
            }
        }
        Phase.RESTING -> {
            carColor = RM.amber
            val dest = route?.destination ?: d.destination.takeIf { d.step == DriveStep.ACTIVE }
            if (dest != null) {
                if (car != null) {
                    val path = listOf(car) + route?.via.orEmpty().map { it.position } + dest.position
                    lines += MapLine(path, RM.textFaint, 3.dp, dashed = true)
                }
                marks += destinationMark(dest)
            }
        }
        Phase.DRIVING -> when (d.step) {
            DriveStep.HOME -> {
                val dest = route?.destination ?: d.naviDestination
                if (dest != null) {
                    if (car != null) lines += MapLine(roads.ahead(current, car, stopsAhead(ui)), RM.blue, 5.dp)
                    marks += destinationMark(dest)
                }
                val ahead = s.nextAhead?.area
                if (ahead != null) marks += MapMark(ahead.position, MarkKind.REST_AHEAD, ahead.name, RM.textFaint, badge = "P")
            }
            DriveStep.SETUP -> {
                val dest = d.destination ?: route?.destination ?: d.naviDestination
                if (dest != null) {
                    if (car != null) lines += MapLine(listOf(car, dest.position), RM.blue, 4.dp, dashed = true)
                    marks += destinationMark(dest)
                }
            }
            DriveStep.CHOOSE -> {
                val dest = d.destination
                if (dest != null) {
                    if (car != null) lines += MapLine(listOf(car, dest.position), RM.textFaint, 3.dp, dashed = true)
                    d.options.forEachIndexed { i, option ->
                        if (car != null && option.via.isNotEmpty()) {
                            val color = candidateColor(i)
                            val chain = listOf(car) + option.via.map { it.position } + dest.position
                            val path = RoadPath.join((0 until chain.lastIndex).map { k -> roads.between(chain[k], chain[k + 1]) })
                            lines += MapLine(path, color, 4.dp)
                            val stop = option.via.first()
                            marks += MapMark(stop.position, MarkKind.CANDIDATE, stop.name, color, badge = "${i + 1}")
                        }
                    }
                    marks += destinationMark(dest)
                }
            }
            DriveStep.ACTIVE, DriveStep.ARRIVED -> {
                val dest = d.destination ?: route?.destination
                val active = d.step == DriveStep.ACTIVE
                val via = when {
                    // 도착: 거쳐 온 경유지를 그대로 보여 준다
                    !active -> d.chosen?.via.orEmpty()
                    !d.viaActive -> emptyList()
                    route != null -> route.via
                    else -> d.chosen?.via.orEmpty()
                }
                // 도착하면 차 대신 목적지를 보인다
                if (!active) showCar = false
                if (dest != null) {
                    if (active && car != null) {
                        lines += MapLine(roads.ahead(current, car, via.map { it.position } + dest.position), carColor, 6.dp)
                    }
                    val stopArea = d.chosen?.restArea
                    for (p in via) {
                        val isStop = stopArea != null && samePlace(stopArea.position, p.position)
                        marks += if (isStop) {
                            MapMark(p.position, MarkKind.STOP, p.name, RM.amber, badge = "P")
                        } else {
                            MapMark(p.position, MarkKind.SCENIC, p.name, RM.sand)
                        }
                    }
                    marks += destinationMark(dest)
                }
            }
        }
    }
    return MapScene(
        trail = trail,
        lines = lines,
        marks = marks,
        car = car.takeIf { showCar },
        heading = carHeading,
        carColor = carColor,
        focus = focus,
        follow = followView(ui, car.takeIf { showCar }, carHeading),
        roads = roads.used,
    )
}

/**
 * 달리는 중이면 주행 화면: 진행 방향이 위, 다음에 닿을 곳까지 거리에 맞춰 확대
 * (가까워질수록 크게, 고속도로는 넓게). 코스를 고르거나 서 있을 때·도착하면 전체 보기.
 */
private fun followView(ui: UiState, car: LatLng?, heading: Double?): FollowView? {
    if (car == null || heading == null) return null
    val s = ui.core
    val d = ui.drive
    val moving = ui.demoDriving || (s.speedKmh ?: 0.0) >= 5.0
    if (!moving) return null
    val driving = when (s.phase) {
        Phase.TO_REST_AREA -> true
        Phase.DRIVING -> d.step == DriveStep.ACTIVE || d.step == DriveStep.HOME
        Phase.RESTING -> false
    }
    if (!driving) return null
    val next = ui.leg?.to ?: stopsAhead(ui).firstOrNull()
    val highway = s.onExpressway == true || (s.speedKmh ?: 0.0) >= 70.0
    val min = if (highway) FOLLOW_MIN_HIGHWAY else FOLLOW_MIN_CITY
    val max = if (highway) FOLLOW_MAX_HIGHWAY else FOLLOW_MAX_CITY
    val span = next?.let { (Geo.distanceMeters(car, it) * 2.4).coerceIn(min, max) } ?: (min * 1.5)
    return FollowView(car, heading, span)
}

private const val FOLLOW_MIN_CITY = 1_400.0
private const val FOLLOW_MAX_CITY = 4_500.0
private const val FOLLOW_MIN_HIGHWAY = 4_000.0
private const val FOLLOW_MAX_HIGHWAY = 14_000.0

/** 쉼터로 갈 때 화면에 담는 지나온 길 끝부분 (점 개수) */
private const val FOCUS_TRAIL = 4

private fun destinationMark(p: Place) = MapMark(p.position, MarkKind.DESTINATION, p.name, RM.sky)

private fun samePlace(a: LatLng, b: LatLng): Boolean = Geo.distanceMeters(a, b) < SAME_PLACE_METERS

private const val SAME_PLACE_METERS = 60.0

/** 미니맵 + 범례. 높이는 화면 크기에 맞춘 값 */
@Composable
internal fun MapPanel(ui: UiState, height: Dp = LocalScreenSize.current.mapHeight) {
    // 도로 길을 먼저 받는다 (앱에서는 백그라운드로, 도착하면 다시 그린다)
    RoadRoutes.Load(ui)
    val scene = mapSceneFor(ui)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RouteMap(scene, Modifier.fillMaxWidth().height(height))
        MapLegend(scene)
    }
}

/** 지도를 돌리는 각도: 진행 방향이 바뀌면 짧은 쪽으로 0.7초 동안 돌린다 */
@Composable
private fun rememberMapRotation(target: Double): Float {
    val last = remember { floatArrayOf(Float.NaN) }
    val prev = last[0]
    val goal = if (prev.isNaN()) target.toFloat() else prev + shortestTurn(prev, target.toFloat())
    last[0] = goal
    val angle by animateFloatAsState(goal, tween(700, easing = FastOutSlowInEasing), label = "mapRotation")
    return angle
}

private fun shortestTurn(from: Float, to: Float): Float {
    var d = (to - from) % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
}

@Composable
internal fun RouteMap(scene: MapScene, modifier: Modifier = Modifier, emptyText: String = "위치를 기다리는 중이에요") {
    val shape = CardShape
    BoxWithConstraints(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(RM.mapTop, RM.map)))
            .border(1.dp, RM.border, shape),
    ) {
        val bounded = constraints.hasBoundedWidth && constraints.hasBoundedHeight
        val widthPx = if (bounded) constraints.maxWidth.toFloat() else 0f
        val heightPx = if (bounded) constraints.maxHeight.toFloat() else 0f
        val follow = scene.follow
        val angle = rememberMapRotation(follow?.heading ?: 0.0)
        val camera: MapCamera? = when {
            scene.isEmpty || widthPx <= 0f || heightPx <= 0f -> null
            follow != null -> MapCamera.follow(follow.center, angle.toDouble(), follow.spanMeters, widthPx, heightPx)
            else -> {
                // 가까운 곳을 크게 볼 때는 가장자리를 넉넉히 둔다
                val padding = if (scene.focus != null) 0.28f else 0.14f
                MapFrame.fit(scene.fitPoints(), widthPx / heightPx, padding = padding, minSpanMeters = 1_200.0)
                    ?.camera(widthPx, heightPx)
                    ?.withRotation(angle.toDouble())
            }
        }
        MapGrid(Modifier.fillMaxSize())
        if (camera == null) {
            Text(emptyText, color = RM.textDim, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center))
        } else {
            // 바탕 지도 타일: 미리보기(인터넷 없음)에서는 받지 않는다. 화면 캡처 시험은 BLOCKING으로 그 자리에서 받는다.
            val inspection = LocalInspectionMode.current
            val tilesOn = MapTiles.mode == MapTiles.Mode.BLOCKING || (!inspection && MapTiles.mode == MapTiles.Mode.ASYNC)
            val density = LocalDensity.current.density
            val layout = if (tilesOn) camera.tiles(density) else null
            val keys = layout?.let { l -> l.tiles.map { TileKey(l.zoom, it.x, it.y) } }.orEmpty()
            if (MapTiles.mode == MapTiles.Mode.BLOCKING) {
                remember(keys) { MapTiles.request(keys) }
            } else {
                LaunchedEffect(keys) { MapTiles.request(keys) }
            }
            val tilesShown = keys.any { MapTiles.get(it) != null }
            // 지도(타일·선·점)는 돌리기 전 좌표로 그리고 캔버스를 돌린다. 이름표와 차는 화면 좌표에 똑바로.
            val flat: (LatLng) -> Offset = { p -> camera.unrotated(p).let { Offset(it.x, it.y) } }
            val at: (LatLng) -> Offset = { p -> camera.screen(p).let { Offset(it.x, it.y) } }
            val pivot = Offset(camera.anchorX, camera.anchorY)
            Canvas(Modifier.fillMaxSize()) {
                rotate(degrees = (-camera.rotation).toFloat(), pivot = pivot) {
                    if (layout != null) drawTiles(layout)
                    if (scene.trail.size >= 2) {
                        drawPolyline(scene.trail.map(flat), RM.trail, 5.dp.toPx(), dashed = false, casing = false)
                    }
                    for (line in scene.lines) {
                        if (line.points.size >= 2) {
                            drawPolyline(line.points.map(flat), line.color, line.width.toPx(), line.dashed, casing = !line.dashed)
                        }
                    }
                    for (m in scene.marks) drawMark(m, flat(m.position))
                }
                val car = scene.car
                if (car != null) {
                    val heading = scene.heading?.let { it - camera.rotation }
                    drawCar(at(car), heading, scene.carColor)
                }
            }
            // 화면 밖(가까운 곳을 크게 볼 때의 먼 목적지 등)은 이름표를 달지 않는다
            fun inside(o: Offset) = o.x in 0f..widthPx && o.y in 0f..heightPx
            val pins = ArrayList<Pin>()
            for (m in scene.marks) {
                val badge = m.badge ?: continue
                val p = at(m.position)
                if (!inside(p)) continue
                val small = m.kind == MarkKind.REST_AHEAD
                pins += BadgePin(p, badge, m.color, if (small) 22.dp else 28.dp)
            }
            for (m in scene.marks) {
                val label = m.label ?: continue
                val p = at(m.position)
                if (!inside(p)) continue
                pins += LabelPin(p, label, m.color, strong = m.kind == MarkKind.DESTINATION)
            }
            PinLayer(pins, Modifier.fillMaxSize())
            ScaleBar(camera.metersPerPx, Modifier.align(Alignment.BottomStart).padding(12.dp))
            NorthArrow((-camera.rotation).toFloat(), Modifier.align(Alignment.TopEnd).padding(12.dp))
            // 출처: 지도 타일·도로 길을 그렸으면 그 출처 (이용 조건), 아니면 '도식 지도'
            val credit = listOfNotNull(
                MapTiles.ATTRIBUTION.takeIf { tilesShown },
                RoadRoutes.ATTRIBUTION.takeIf { scene.roads },
            ).joinToString(" · ").ifEmpty { "도식 지도" }
            Text(
                credit,
                color = if (tilesShown) RM.textDim else RM.textFaint,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(if (tilesShown) RM.mapLabel else Color.Transparent, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun MapGrid(modifier: Modifier) {
    Canvas(modifier) {
        val step = 48.dp.toPx()
        val stroke = 1.dp.toPx()
        var x = step
        while (x < size.width) {
            drawLine(RM.mapGrid, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
            x += step
        }
        var y = step
        while (y < size.height) {
            drawLine(RM.mapGrid, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
            y += step
        }
    }
}

/** 받아 둔 지도 타일을 제자리에 그린다 (돌리기 전 좌표, 이웃 타일 사이에 틈이 보이지 않게 반 픽셀씩 겹친다) */
private fun DrawScope.drawTiles(layout: TileLayout) {
    val filter = if (RM.dark) MapTiles.nightFilter else MapTiles.dayFilter
    for (t in layout.tiles) {
        val image = MapTiles.get(TileKey(layout.zoom, t.x, t.y)) ?: continue
        val left = floor(t.left).toInt()
        val top = floor(t.top).toInt()
        val right = ceil(t.right).toInt()
        val bottom = ceil(t.bottom).toInt()
        if (right <= left || bottom <= top) continue
        drawImage(
            image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(left, top),
            dstSize = IntSize(right - left, bottom - top),
            colorFilter = filter,
            filterQuality = FilterQuality.Medium,
        )
    }
}

private fun DrawScope.drawPolyline(points: List<Offset>, color: Color, width: Float, dashed: Boolean, casing: Boolean) {
    val path = Path()
    points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
    if (casing) {
        drawPath(path, RM.casing, style = Stroke(width + 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(), 9.dp.toPx())) else null
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
}

private fun DrawScope.drawMark(m: MapMark, p: Offset) {
    when (m.kind) {
        MarkKind.ORIGIN -> {
            drawCircle(RM.map, 7.dp.toPx(), p)
            drawCircle(RM.textDim, 7.dp.toPx(), p, style = Stroke(2.5.dp.toPx()))
        }
        MarkKind.SCENIC -> {
            drawCircle(m.color.copy(alpha = 0.25f), 16.dp.toPx(), p)
            drawCircle(m.color, 9.dp.toPx(), p)
            drawCircle(RM.map, 3.5.dp.toPx(), p)
        }
        MarkKind.DESTINATION -> {
            drawCircle(m.color.copy(alpha = 0.22f), 22.dp.toPx(), p)
            drawCircle(Color.White, 12.dp.toPx(), p)
            drawCircle(m.color, 8.dp.toPx(), p)
        }
        MarkKind.CANDIDATE, MarkKind.STOP, MarkKind.REST_TARGET -> drawCircle(m.color.copy(alpha = 0.22f), 22.dp.toPx(), p)
        MarkKind.REST_AHEAD -> Unit
    }
}

private fun DrawScope.drawCar(p: Offset, heading: Double?, color: Color) {
    drawCircle(color.copy(alpha = 0.18f), 28.dp.toPx(), p)
    drawCircle(Color.White, 14.dp.toPx(), p)
    drawCircle(color, 14.dp.toPx(), p, style = Stroke(3.5.dp.toPx()))
    val arrow = Path().apply {
        moveTo(p.x, p.y - 8.dp.toPx())
        lineTo(p.x + 6.dp.toPx(), p.y + 6.dp.toPx())
        lineTo(p.x, p.y + 3.dp.toPx())
        lineTo(p.x - 6.dp.toPx(), p.y + 6.dp.toPx())
        close()
    }
    rotate(degrees = (heading ?: 0.0).toFloat(), pivot = p) {
        drawPath(arrow, color)
    }
}

@Composable
private fun MapLabel(text: String, color: Color, strong: Boolean) {
    val shape = RoundedCornerShape(4.dp)
    Text(
        text,
        color = RM.text,
        fontSize = if (strong) 15.sp else 14.sp,
        fontWeight = if (strong) FontWeight.SemiBold else FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(RM.mapLabel, shape)
            .border(1.dp, color.copy(alpha = 0.6f), shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** 축척: 화면에서 1px이 몇 m인지로 보기 좋은 거리(100m~100km)를 고른다 */
@Composable
private fun ScaleBar(metersPerPx: Double, modifier: Modifier) {
    val density = LocalDensity.current
    val targetPx = with(density) { 90.dp.toPx() }
    val meters = NICE_METERS.lastOrNull { it / metersPerPx <= targetPx } ?: NICE_METERS.first()
    val lengthDp = with(density) { (meters / metersPerPx).toFloat().toDp() }
    Column(modifier) {
        Text(
            if (meters >= 1_000.0) "${(meters / 1_000.0).roundToInt()} km" else "${meters.roundToInt()} m",
            color = RM.textDim,
            fontSize = 12.sp,
        )
        Box(
            Modifier
                .padding(top = 3.dp)
                .width(lengthDp)
                .height(3.dp)
                .background(RM.textDim, RoundedCornerShape(2.dp)),
        )
    }
}

private val NICE_METERS = listOf(100.0, 200.0, 500.0, 1_000.0, 2_000.0, 5_000.0, 10_000.0, 20_000.0, 50_000.0, 100_000.0)

/** 북쪽 표시. 지도를 돌린 만큼 같이 돌아 북쪽을 가리킨다 */
@Composable
private fun NorthArrow(degrees: Float, modifier: Modifier) {
    Column(modifier.rotate(degrees), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(12.dp)) {
            val path = Path().apply {
                moveTo(size.width / 2f, 0f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(path, RM.textDim)
        }
        Text("N", color = RM.textDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 미니맵 아래 범례: 지금 장면에 있는 것만 */
@Composable
private fun MapLegend(scene: MapScene) {
    val items = ArrayList<Pair<String, Color>>()
    if (scene.trail.size >= 2) items += "지나온 길" to RM.trail
    val solid = scene.lines.firstOrNull { !it.dashed && scene.marks.none { m -> m.kind == MarkKind.CANDIDATE } }
    if (solid != null) items += "갈 길" to solid.color
    if (scene.marks.any { it.kind == MarkKind.CANDIDATE }) items += "코스 후보 (번호)" to RM.sand
    if (scene.marks.any { it.kind == MarkKind.SCENIC }) items += "경유지" to RM.sand
    if (scene.marks.any { it.kind == MarkKind.STOP || it.kind == MarkKind.REST_TARGET }) items += "쉼터" to RM.amber
    if (scene.marks.any { it.kind == MarkKind.REST_AHEAD }) items += "앞쪽 쉼터" to RM.textFaint
    if (scene.marks.any { it.kind == MarkKind.DESTINATION }) items += "목적지" to RM.sky
    if (items.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        for ((name, color) in items.take(5)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(width = 16.dp, height = 4.dp).background(color, RoundedCornerShape(2.dp)))
                Text(name, color = RM.textDim, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------- 그림 위 글자 층

/** 그림 위에 얹는 번호·이름표·글자. [at]은 그림 안의 위치 (px) */
internal sealed interface Pin {
    val at: Offset

    /** 점 한가운데에 두는지 (아니면 점 옆) */
    val centered: Boolean
}

internal data class BadgePin(override val at: Offset, val text: String, val color: Color, val size: Dp) : Pin {
    override val centered: Boolean get() = true
}

internal data class LabelPin(override val at: Offset, val text: String, val color: Color, val strong: Boolean) : Pin {
    override val centered: Boolean get() = false
}

internal data class TextPin(override val at: Offset, val text: String, val color: Color) : Pin {
    override val centered: Boolean get() = true
}

/**
 * 그림 위 글자 층. 가운데 두는 것은 점 한가운데, 이름표는 점 오른쪽(모자라면 왼쪽)에 둔다.
 * 이름표끼리 겹치면 아래로 비켜 놓고, 그림 밖으로 나가지 않게 한다.
 */
@Composable
internal fun PinLayer(pins: List<Pin>, modifier: Modifier = Modifier) {
    Layout(
        content = {
            for (pin in pins) {
                when (pin) {
                    is BadgePin -> NumberBadge(pin.text, pin.color, pin.size)
                    is LabelPin -> MapLabel(pin.text, pin.color, pin.strong)
                    is TextPin -> Text(pin.text, color = pin.color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
        val labelMax = if (width > 0) width / 2 else Constraints.Infinity
        val placeables = measurables.mapIndexed { i, m ->
            m.measure(if (pins[i] is LabelPin) Constraints(maxWidth = labelMax) else Constraints())
        }
        val gap = 18.dp.roundToPx()
        val taken = ArrayList<IntArray>()
        layout(width, height) {
            placeables.forEachIndexed { i, p ->
                val pin = pins[i]
                var x: Int
                var y: Int
                if (pin.centered) {
                    x = (pin.at.x - p.width / 2f).roundToInt()
                    y = (pin.at.y - p.height / 2f).roundToInt()
                } else {
                    val fitsRight = pin.at.x + gap + p.width <= width - 8
                    x = if (fitsRight) (pin.at.x + gap).roundToInt() else (pin.at.x - gap - p.width).roundToInt()
                    y = (pin.at.y - p.height / 2f).roundToInt()
                    var tries = 0
                    while (tries < 4 && overlaps(taken, x, y, p.width, p.height)) {
                        y += p.height + 4
                        tries++
                    }
                    taken += intArrayOf(x, y, x + p.width, y + p.height)
                }
                p.place(x.coerceIn(0, max(0, width - p.width)), y.coerceIn(0, max(0, height - p.height)))
            }
        }
    }
}

private fun overlaps(taken: List<IntArray>, x: Int, y: Int, w: Int, h: Int): Boolean =
    taken.any { r -> x < r[2] && x + w > r[0] && y < r[3] && y + h > r[1] }
