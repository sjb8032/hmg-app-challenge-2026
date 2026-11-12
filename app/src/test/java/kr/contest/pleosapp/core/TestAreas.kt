package kr.contest.pleosapp.core

/** 위도 0.009도 ≈ 1km. 테스트는 북위 37도, 동경 127도에서 남쪽(180도)으로 달리는 차를 기준으로 한다. */
object TestAreas {
    val origin = LatLng(37.0, 127.0)
    private const val KM_LAT = 0.009

    fun southKm(km: Double, lon: Double = 127.0) = LatLng(37.0 - km * KM_LAT, lon)

    fun area(
        id: String,
        position: LatLng,
        type: RestAreaType = RestAreaType.SERVICE_AREA,
        directionTarget: LatLng? = null,
    ) = RestArea(
        id = id,
        name = "${id}휴게소",
        type = type,
        position = position,
        directionLabel = directionTarget?.let { "방향" },
        directionTarget = directionTarget,
    )

    /** 남쪽 끝 도시 (진행 방향) */
    val southCity = LatLng(35.0, 127.0)

    /** 북쪽 끝 도시 (반대 방향) */
    val northCity = LatLng(38.5, 127.0)
}
