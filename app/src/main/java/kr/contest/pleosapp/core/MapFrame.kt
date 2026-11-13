package kr.contest.pleosapp.core

import kotlin.math.cos
import kotlin.math.max

/** 미니맵 안의 위치. x: 0(서쪽) → 1(동쪽), y: 0(북쪽) → 1(남쪽) */
data class MapPoint(val x: Float, val y: Float)

/**
 * 몇 개 지점을 한 화면에 담는 평면 투영 (도시·고속도로 한 구간 정도라 위도에 맞춘 등장방형으로 충분하다).
 * 화면 비율(너비/높이)을 지키므로 동서·남북 거리가 같은 비율로 보인다.
 */
class MapFrame private constructor(
    private val originLat: Double,
    private val metersPerLon: Double,
    private val centerX: Double,
    private val centerY: Double,
    /** 화면 너비·높이에 해당하는 거리 (미터) */
    val widthMeters: Double,
    val heightMeters: Double,
) {
    fun project(p: LatLng): MapPoint {
        val x = p.lon * metersPerLon
        val y = (p.lat - originLat) * METERS_PER_LAT
        return MapPoint(
            x = ((x - centerX) / widthMeters + 0.5).toFloat(),
            y = (0.5 - (y - centerY) / heightMeters).toFloat(),
        )
    }

    companion object {
        private const val METERS_PER_LAT = 110_574.0
        private const val METERS_PER_LON_AT_EQUATOR = 111_320.0

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
            val originLat = points.sumOf { it.lat } / points.size
            val metersPerLon = METERS_PER_LON_AT_EQUATOR * cos(Math.toRadians(originLat))
            var minX = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var maxY = -Double.MAX_VALUE
            for (p in points) {
                val x = p.lon * metersPerLon
                val y = (p.lat - originLat) * METERS_PER_LAT
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            var width = max(maxX - minX, minSpanMeters)
            var height = max(maxY - minY, minSpanMeters / aspect)
            // 화면 비율에 맞춰 짧은 쪽을 늘린다.
            if (width / height < aspect) width = height * aspect else height = width / aspect
            val scale = 1.0 + 2.0 * padding
            return MapFrame(
                originLat = originLat,
                metersPerLon = metersPerLon,
                centerX = (minX + maxX) / 2.0,
                centerY = (minY + maxY) / 2.0,
                widthMeters = width * scale,
                heightMeters = height * scale,
            )
        }
    }
}
