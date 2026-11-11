package kr.contest.pleosapp.core

/** PT 시연용 출발 상황. 실제 쉼터 데이터에서 만든다. */
data class DemoScenario(
    val start: LatLng,
    val headingDeg: Double,
    val destination: LatLng?,
    val destinationName: String?,
    val description: String,
)

object DemoScenarios {
    private const val LEAD_METERS = 9_000.0

    /** 경부고속도로 부산 방향, 안성휴게소(부산방면) 9km 앞에서 출발. 데이터에 없으면 다른 고속도로 쉼터를 쓴다. */
    fun pick(areas: List<RestArea>): DemoScenario {
        val anseong = areas.firstOrNull { it.name.contains("안성") && it.directionLabel == "부산" && it.onExpressway }
        if (anseong != null) {
            val giheung = areas.firstOrNull {
                it.onExpressway && RestMateText.spokenName(it.name).replace(" ", "") == "기흥휴게소"
            }
            val heading = when {
                giheung != null -> Geo.bearingDegrees(giheung.position, anseong.position)
                anseong.directionTarget != null -> Geo.bearingDegrees(anseong.position, anseong.directionTarget)
                else -> 180.0
            }
            return scenarioBefore(anseong, heading, "경부고속도로 부산 방향")
        }
        val any = areas.firstOrNull { it.onExpressway && it.directionTarget != null }
            ?: areas.firstOrNull { it.directionTarget != null }
        if (any?.directionTarget != null) {
            val heading = Geo.bearingDegrees(any.position, any.directionTarget)
            return scenarioBefore(any, heading, "${any.directionLabel} 방향")
        }
        return DemoScenario(LatLng(37.0, 127.1), 180.0, null, null, "기본 위치 (쉼터 데이터 없음)")
    }

    private fun scenarioBefore(area: RestArea, heading: Double, road: String): DemoScenario {
        val start = Geo.destinationPoint(area.position, (heading + 180.0) % 360.0, LEAD_METERS)
        return DemoScenario(
            start = start,
            headingDeg = heading,
            destination = area.directionTarget,
            destinationName = area.directionLabel,
            description = "$road, ${area.name} ${(LEAD_METERS / 1000).toInt()}km 전",
        )
    }
}
