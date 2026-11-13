package kr.contest.pleosapp.app

import kr.contest.pleosapp.core.LatLng
import kr.contest.pleosapp.core.Place
import kr.contest.pleosapp.core.PlaceHit

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

    /** 픽업 알림 문자 (시연: 실제로 보내지 않는다) */
    MESSAGE,
}

/** 내비가 지금 따라가는 경로: 남은 경유지 + 목적지 (미니맵) */
data class RouteView(val destination: Place, val via: List<Place>)

/**
 * 시연용 모의 차가 곧게 달린 구간 하나: [from]에서 [to]로 [progress](0~1)만큼 왔다.
 * 모의 차는 시간 계산을 단순하게 두려고 곧게 움직이고, 화면에서는 이 비율만큼 도로 모양을 따라간 자리에 차와 지나온 길을 그린다.
 */
data class DriveLeg(val from: LatLng, val to: LatLng, val progress: Float)

/** 장소를 찾는 까닭 (고른 곳을 어디에 쓰는지) */
enum class PlacePurpose(val title: String, val hint: String, val confirm: String) {
    /** 처음 화면: 내비 목적지 정하기 (시연은 모의 내비, 실제 차는 차량 내비에 경로 요청) */
    NAVI_DESTINATION("목적지 검색", "어디로 갈까요? 장소·주소", "목적지로"),

    /** 온타임·롱웨이 설정: 끝낼 곳 */
    END_PLACE("끝낼 곳 찾기", "어디에 맞춰 갈까요? 장소·주소", "끝낼 곳으로"),

    /** 시연: 모의 차가 출발할 곳 (가상 시계는 지금 시각부터) */
    DEMO_START("출발지 정하기", "어디서 출발할까요? 장소·주소", "여기서 출발"),
}

/** 화면 위에 띄우는 입력 창 */
sealed interface InputSheet {
    /** 장소 찾기: 이름·주소로 검색하거나 지도에서 고른다 */
    data class FindPlace(
        val purpose: PlacePurpose,
        /** 마지막으로 찾은 검색어 */
        val query: String = "",
        val results: List<PlaceHit> = emptyList(),
        val searching: Boolean = false,
        /** 결과 아래 한 줄 (찾은 곳이 없음, 인터넷이 안 됨, 주소를 찾는 중 등) */
        val note: String? = null,
        /** 지도에서 고르는 중 */
        val onMap: Boolean = false,
        /** 지도에서 고르기를 처음 보여 줄 가운데 (차 위치) */
        val mapCenter: LatLng? = null,
        /** 검색어를 넣기 전에 보여 줄 곳 (최근에 고른 곳, 즐겨찾기·최근 목적지) */
        val suggestions: List<PlaceHit> = emptyList(),
        /** 검색 서비스 (출처 표시) */
        val service: String = "",
    ) : InputSheet

    /** 도착 목표 시각 고르기 (온타임 설정) */
    data object PickTime : InputSheet
}
