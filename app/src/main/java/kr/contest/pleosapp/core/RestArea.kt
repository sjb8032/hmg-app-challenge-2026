package kr.contest.pleosapp.core

enum class RestAreaType(val label: String) {
    SERVICE_AREA("휴게소"),
    DROWSY_SHELTER("졸음쉼터"),
    REST_STOP("쉼터"),
}

data class RestArea(
    val id: String,
    val name: String,
    val type: RestAreaType,
    val position: LatLng,
    /** 이름에 붙은 방향 표시의 지명 (예: "부산방향"의 "부산"). 없으면 null */
    val directionLabel: String? = null,
    /** 방향 표시 지명의 좌표. 지명을 찾지 못했으면 null */
    val directionTarget: LatLng? = null,
    /** 고속도로 본선 바로 옆에 있는 쉼터인지 (고속도로 주행 중에는 이 쉼터만 고른다) */
    val onExpressway: Boolean = false,
)
