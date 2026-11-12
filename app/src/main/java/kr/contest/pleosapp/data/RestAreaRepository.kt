package kr.contest.pleosapp.data

import android.content.Context
import kr.contest.pleosapp.core.LatLng
import kr.contest.pleosapp.core.RestArea
import kr.contest.pleosapp.core.RestAreaCleaner
import kr.contest.pleosapp.core.RestAreaType
import org.json.JSONArray

/**
 * 앱에 내장한 휴게소·졸음쉼터 데이터 (assets/rest_areas.json).
 *
 * 출처: © OpenStreetMap contributors, ODbL 1.0 (Geofabrik 한국 추출본에서 highway=rest_area·services 추출).
 * 공공데이터(한국도로공사 휴게소·졸음쉼터 현황)로 바꿀 때도 같은 형식으로 만들면 된다.
 * 읽은 뒤 쉴 수 없는 항목(퀵서비스·주유소·잘못 찍힌 좌표 등)은 [RestAreaCleaner]로 거른다.
 */
object RestAreaRepository {
    const val ATTRIBUTION = "쉼터 위치 © OpenStreetMap contributors"

    fun load(context: Context): List<RestArea> {
        val text = context.assets.open("rest_areas.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val array = JSONArray(text)
        val out = ArrayList<RestArea>(array.length())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val target = if (o.has("dlat") && o.has("dlon")) LatLng(o.getDouble("dlat"), o.getDouble("dlon")) else null
            out += RestArea(
                id = o.getString("id"),
                name = o.getString("n"),
                type = when (o.optString("t")) {
                    "S" -> RestAreaType.SERVICE_AREA
                    "D" -> RestAreaType.DROWSY_SHELTER
                    else -> RestAreaType.REST_STOP
                },
                position = LatLng(o.getDouble("lat"), o.getDouble("lon")),
                directionLabel = if (o.has("dl")) o.getString("dl") else null,
                directionTarget = target,
                onExpressway = o.optInt("e", 0) == 1,
            )
        }
        return RestAreaCleaner.clean(out)
    }
}
