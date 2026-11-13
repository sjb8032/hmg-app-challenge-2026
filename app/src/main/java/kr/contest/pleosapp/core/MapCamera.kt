package kr.contest.pleosapp.core

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 화면 위 점 (px) */
data class ScreenPoint(val x: Float, val y: Float)

/** 지도 타일 한 장을 그릴 자리 (지도를 돌리기 전 화면 좌표, px) */
data class TileRect(val x: Int, val y: Int, val left: Float, val top: Float, val right: Float, val bottom: Float)

data class TileLayout(val zoom: Int, val tiles: List<TileRect>)

/**
 * 미니맵 카메라. 세계 좌표(메르카토르, [MapFrame.worldX]·[MapFrame.worldY])의 한 점을 화면의 기준점([anchorX],[anchorY])에 두고,
 * [pxPerWorld]배로 키운 뒤, 화면 위쪽이 [rotation] 방위(0 = 북쪽, 시계 방향)를 가리키게 돌린다.
 *
 * - 전체 보기: 북쪽이 위, 모든 지점이 들어오게 ([MapFrame.camera])
 * - 주행 화면: 차를 아래쪽 가운데 두고 진행 방향이 위로 ([follow])
 *
 * 지도(타일·선·점)는 [unrotated] 좌표로 그린 뒤 캔버스를 -[rotation]도 돌리고, 이름표처럼 똑바로 세울 것은 [screen] 좌표에 얹는다.
 */
class MapCamera(
    val centerX: Double,
    val centerY: Double,
    val pxPerWorld: Double,
    val rotation: Double,
    val anchorX: Float,
    val anchorY: Float,
    val width: Float,
    val height: Float,
) {
    /** 돌리기 전 화면 좌표 */
    fun unrotated(p: LatLng): ScreenPoint = ScreenPoint(
        ((MapFrame.worldX(p.lon) - centerX) * pxPerWorld + anchorX).toFloat(),
        ((MapFrame.worldY(p.lat) - centerY) * pxPerWorld + anchorY).toFloat(),
    )

    /** 화면 좌표 */
    fun screen(p: LatLng): ScreenPoint = rotate(unrotated(p), -rotation)

    /** 같은 자리·크기에서 돌리는 각도만 바꾼 카메라 */
    fun withRotation(degrees: Double): MapCamera =
        MapCamera(centerX, centerY, pxPerWorld, degrees, anchorX, anchorY, width, height)

    /** 화면 1px이 몇 m인지 (기준점 위도) */
    val metersPerPx: Double
        get() = MapFrame.EARTH_CIRCUMFERENCE * cos(Math.toRadians(MapFrame.latOf(centerY))) / pxPerWorld

    /** 기준점 둘레로 [degrees]만큼 돌린 점 (캔버스 rotate와 같은 방향: 양수면 시계 방향) */
    private fun rotate(p: ScreenPoint, degrees: Double): ScreenPoint {
        if (degrees == 0.0) return p
        val r = Math.toRadians(degrees)
        val c = cos(r)
        val s = sin(r)
        val dx = p.x - anchorX
        val dy = p.y - anchorY
        return ScreenPoint((anchorX + dx * c - dy * s).toFloat(), (anchorY + dx * s + dy * c).toFloat())
    }

    /**
     * 화면을 덮는 지도 타일. 타일 한 장(256px)을 256dp 안팎으로 그리는 줌을 고르고, 장수가 [maxTiles]를 넘으면 줌을 낮춘다.
     * @param density dp당 px
     */
    fun tiles(density: Float, maxZoom: Int = MapFrame.MAX_ZOOM, maxTiles: Int = 64): TileLayout? {
        if (width <= 0f || height <= 0f || pxPerWorld <= 0.0 || density <= 0f) return null
        // 화면 네 모서리를 돌리기 전 좌표로 되돌려 세계 좌표 범위를 구한다
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for ((sx, sy) in listOf(0f to 0f, width to 0f, 0f to height, width to height)) {
            val u = rotate(ScreenPoint(sx, sy), rotation)
            val wx = (u.x - anchorX) / pxPerWorld + centerX
            val wy = (u.y - anchorY) / pxPerWorld + centerY
            minX = min(minX, wx)
            maxX = max(maxX, wx)
            minY = min(minY, wy)
            maxY = max(maxY, wy)
        }
        val ideal = log2(pxPerWorld / density / MapFrame.TILE_DP)
        var zoom = ideal.roundToInt().coerceIn(0, maxZoom)
        while (true) {
            val n = 1 shl zoom
            val x0 = floor(minX * n).toInt().coerceIn(0, n - 1)
            val x1 = floor(maxX * n).toInt().coerceIn(0, n - 1)
            val y0 = floor(minY * n).toInt().coerceIn(0, n - 1)
            val y1 = floor(maxY * n).toInt().coerceIn(0, n - 1)
            val count = (x1 - x0 + 1) * (y1 - y0 + 1)
            if (count <= maxTiles || zoom == 0) {
                val tiles = ArrayList<TileRect>(count)
                for (ty in y0..y1) {
                    for (tx in x0..x1) {
                        tiles += TileRect(
                            x = tx,
                            y = ty,
                            left = ((tx.toDouble() / n - centerX) * pxPerWorld + anchorX).toFloat(),
                            top = ((ty.toDouble() / n - centerY) * pxPerWorld + anchorY).toFloat(),
                            right = (((tx + 1).toDouble() / n - centerX) * pxPerWorld + anchorX).toFloat(),
                            bottom = (((ty + 1).toDouble() / n - centerY) * pxPerWorld + anchorY).toFloat(),
                        )
                    }
                }
                return TileLayout(zoom, tiles)
            }
            zoom--
        }
    }

    companion object {
        /**
         * 주행 화면: 차를 화면 가운데 아래쪽([anchorFraction] 높이)에 두고 진행 방향([heading])이 위로 오게 한다.
         * [spanMeters]는 화면 너비에 담을 거리.
         */
        fun follow(
            car: LatLng,
            heading: Double,
            spanMeters: Double,
            width: Float,
            height: Float,
            anchorFraction: Float = 0.7f,
        ): MapCamera {
            val metersPerWorld = MapFrame.EARTH_CIRCUMFERENCE * cos(Math.toRadians(car.lat))
            return MapCamera(
                centerX = MapFrame.worldX(car.lon),
                centerY = MapFrame.worldY(car.lat),
                pxPerWorld = width / (spanMeters / metersPerWorld),
                rotation = heading,
                anchorX = width / 2f,
                anchorY = height * anchorFraction,
                width = width,
                height = height,
            )
        }
    }
}
