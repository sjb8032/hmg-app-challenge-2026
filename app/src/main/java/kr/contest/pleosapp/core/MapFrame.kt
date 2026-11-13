package kr.contest.pleosapp.core

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh

/** 미니맵 안의 위치. x: 0(서쪽) → 1(동쪽), y: 0(북쪽) → 1(남쪽) */
data class MapPoint(val x: Float, val y: Float)

/** 지도 타일 한 장 (OSM 타일 번호)과 미니맵 안에서 차지하는 자리 (틀의 너비·높이에 대한 비율, 틀 밖으로 나갈 수 있음) */
data class MapTile(
    val x: Int,
    val y: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** 미니맵 한 장에 깔 타일들 */
data class TileCover(val zoom: Int, val tiles: List<MapTile>)

/**
 * 몇 개 지점을 한 화면에 담는 틀. 웹 지도와 같은 메르카토르 투영(EPSG:3857)이라 지도 타일과 정확히 겹친다.
 * 화면 비율(너비/높이)을 지키므로 동서·남북 거리가 같은 비율로 보인다 (틀 가운데 위도 기준).
 *
 * 세계 좌표: 경도 -180~180 → x 0~1, 위도 85~-85 → y 0~1 (타일 번호를 2^줌으로 나눈 값과 같음).
 */
class MapFrame private constructor(
    /** 틀 왼쪽 위의 세계 좌표 */
    private val x0: Double,
    private val y0: Double,
    /** 틀 너비·높이 (세계 좌표) */
    private val worldWidth: Double,
    private val worldHeight: Double,
    /** 틀 가운데 위도에서 세계 좌표 1이 몇 m인지 */
    private val metersPerWorld: Double,
) {
    /** 화면 너비·높이에 해당하는 거리 (미터, 틀 가운데 위도 기준) */
    val widthMeters: Double get() = worldWidth * metersPerWorld
    val heightMeters: Double get() = worldHeight * metersPerWorld

    fun project(p: LatLng): MapPoint = MapPoint(
        x = ((worldX(p.lon) - x0) / worldWidth).toFloat(),
        y = ((worldY(p.lat) - y0) / worldHeight).toFloat(),
    )

    /**
     * 이 틀을 [widthDp] 너비로 그릴 때 깔 지도 타일. 타일 한 장(256px 타일)을 256dp 안팎으로 그리는 줌을 고른다
     * (타일 글자가 차 화면에서 너무 작거나 크지 않게). 장수가 [maxTiles]를 넘으면 줌을 낮춘다.
     */
    fun tiles(widthDp: Float, maxZoom: Int = MAX_ZOOM, maxTiles: Int = MAX_TILES): TileCover? {
        if (widthDp <= 0f || worldWidth <= 0.0 || worldHeight <= 0.0) return null
        val ideal = log2(widthDp / worldWidth / TILE_DP)
        var zoom = ideal.roundToInt().coerceIn(0, maxZoom)
        while (true) {
            val n = 1 shl zoom
            val xs = tileRange(x0, worldWidth, n)
            val ys = tileRange(y0, worldHeight, n)
            val count = (xs.last - xs.first + 1) * (ys.last - ys.first + 1)
            if (count <= maxTiles || zoom == 0) {
                val tiles = ArrayList<MapTile>(count)
                for (ty in ys) {
                    for (tx in xs) {
                        tiles += MapTile(
                            x = tx,
                            y = ty,
                            left = ((tx.toDouble() / n - x0) / worldWidth).toFloat(),
                            top = ((ty.toDouble() / n - y0) / worldHeight).toFloat(),
                            right = (((tx + 1).toDouble() / n - x0) / worldWidth).toFloat(),
                            bottom = (((ty + 1).toDouble() / n - y0) / worldHeight).toFloat(),
                        )
                    }
                }
                return TileCover(zoom, tiles)
            }
            zoom--
        }
    }

    private fun tileRange(start: Double, span: Double, n: Int): IntRange {
        val first = floor(start * n).toInt().coerceIn(0, n - 1)
        val last = floor((start + span) * n).toInt().coerceIn(0, n - 1)
        return first..max(first, last)
    }

    companion object {
        /** 적도 둘레 (m) */
        const val EARTH_CIRCUMFERENCE = 40_075_016.686
        private const val MAX_LAT = 85.05112878

        /** 타일 한 장을 그리는 크기 (dp) */
        const val TILE_DP = 256.0
        const val MAX_ZOOM = 18
        private const val MAX_TILES = 48

        fun worldX(lon: Double): Double = (lon + 180.0) / 360.0

        fun worldY(lat: Double): Double {
            val s = sin(Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT)))
            return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
        }

        fun latOf(worldY: Double): Double = Math.toDegrees(atan(sinh(PI * (1 - 2 * worldY))))

        /**
         * [points]를 모두 담는 틀. [aspect]는 화면 너비/높이, [padding]은 가장자리에 남길 비율(한쪽),
         * [minSpanMeters]는 지점이 가까이 모여 있어도 이만큼은 보여 주는 최소 범위다. 지점이 없으면 null.
         */
        fun fit(
            points: List<LatLng>,
            aspect: Float,
            padding: Float = 0.12f,
            minSpanMeters: Double = 1_500.0,
        ): MapFrame? {
            if (points.isEmpty() || aspect <= 0f) return null
            var minX = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var maxY = -Double.MAX_VALUE
            for (p in points) {
                val x = worldX(p.lon)
                val y = worldY(p.lat)
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)
            }
            val centerX = (minX + maxX) / 2.0
            val centerY = (minY + maxY) / 2.0
            val metersPerWorld = EARTH_CIRCUMFERENCE * cos(Math.toRadians(latOf(centerY)))
            val minSpan = minSpanMeters / metersPerWorld
            var width = max(maxX - minX, minSpan)
            var height = max(maxY - minY, minSpan / aspect)
            // 화면 비율에 맞춰 짧은 쪽을 늘린다.
            if (width / height < aspect) width = height * aspect else height = width / aspect
            val scale = 1.0 + 2.0 * padding
            width *= scale
            height *= scale
            return MapFrame(
                x0 = centerX - width / 2.0,
                y0 = centerY - height / 2.0,
                worldWidth = width,
                worldHeight = height,
                metersPerWorld = metersPerWorld,
            )
        }
    }
}
