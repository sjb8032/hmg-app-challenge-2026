package kr.contest.pleosapp.app

import kr.contest.pleosapp.core.LatLng
import kr.contest.pleosapp.core.Place

/** 안내 기록 한 줄: 무엇을 말했고(음성), 내비·차량에 무엇을 요청했는지 시각과 함께 남긴다. */
data class LogEntry(val at: Long, val text: String, val kind: LogKind)

enum class LogKind {
    /** 음성 안내 (에뮬레이터처럼 소리가 안 나와도 화면으로 볼 수 있게) */
    VOICE,

    /** 내비 경로 요청·경유지·도착 */
    NAVI,

    /** 공조·무드등 같은 차량 제어 */
    VEHICLE,

    /** 시연 조작 (모의 정체, 시간 건너뛰기) */
    DEMO,
}

/** 내비가 지금 따라가는 경로: 남은 경유지 + 목적지 (미니맵) */
data class RouteView(val destination: Place, val via: List<Place>)

/**
 * 시연용 모의 차가 곧게 달린 구간 하나: [from]에서 [to]로 [progress](0~1)만큼 왔다.
 * 모의 차는 시간 계산을 단순하게 두려고 곧게 움직이고, 화면에서는 이 비율만큼 도로 모양을 따라간 자리에 차와 지나온 길을 그린다.
 */
data class DriveLeg(val from: LatLng, val to: LatLng, val progress: Float)
