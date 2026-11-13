package kr.contest.pleosapp.core

import java.util.Locale
import kotlin.math.roundToInt

/** 픽업 알림 문자의 종류 */
enum class PickupNoticeKind(val label: String) {
    /** 곧 도착 (마지막 구간에서 도착 3분 전, 그보다 짧으면 도착할 때): 도착 예정 시각·만날 곳·지금 차 위치 */
    APPROACH("곧 도착"),

    /** P단으로 세운 곳 */
    PARKED("세운 곳"),

    /** 세울 곳이 없어 한 바퀴 돌고 다시 옴 */
    CIRCLING("자리 없음"),
}

/**
 * 픽업할 사람에게 보내는 문자 한 통 (온타임 모드 '픽업 알림').
 *
 * Pleos SDK에 문자 기능이 없고 에뮬레이터는 휴대폰과 연결할 수 없어, 지금은 보낼 내용을 화면과 안내 기록에만 남긴다
 * (시연 기능). 실제로 보내는 길은 휴대폰 연동 문자나 서버 문자 API로 붙인다.
 */
data class PickupNotice(
    val at: Long,
    val kind: PickupNoticeKind,
    /** 받는 사람 (시연은 표본 연락처 '아이') */
    val to: String,
    val text: String,
    /** 문자에 넣은 차 위치 */
    val position: LatLng? = null,
) {
    /** 앱 이름과 지도 링크를 뺀 본문 (화면 말풍선·해설용) */
    val body: String get() = PickupText.body(text)

    /** 문자에 넣은 지도 링크 */
    val link: String? get() = position?.let(PickupText::mapLink)
}

/** 픽업 알림 문구. 문자 앞에 앱 이름을 붙여 자동으로 보낸 문자임을 알린다. */
object PickupText {
    const val PREFIX = "[드라이브메이트]"

    /** 카카오맵 위치 링크 (map.kakao.com/link/map/이름,위도,경도). 이름은 문자에서 링크가 끊기지 않게 영문으로 둔다. */
    fun mapLink(p: LatLng): String =
        String.format(Locale.US, "https://map.kakao.com/link/map/DriveMate,%.5f,%.5f", p.lat, p.lon)

    /** 도착 3분 전: "오후 4:50쯤 아이 학원 앞에 도착해요. 아이 학원 앞에서 만나요. 지금 차 위치: …" */
    fun approach(place: String, etaClock: String, car: LatLng?): String {
        val where = car?.let { " 지금 차 위치: ${mapLink(it)}" }.orEmpty()
        return "$PREFIX ${etaClock}쯤 $place 앞에 도착해요. $place 앞에서 만나요.$where"
    }

    /** P단으로 세운 곳: 목적지에서 40m 안이면 '바로 앞', 아니면 10m 단위 거리 */
    fun parked(place: String, metersFromPlace: Double, car: LatLng): String {
        val where = if (metersFromPlace < NEAR_METERS) {
            "$place 바로 앞에 차를 세웠어요."
        } else {
            "${place}에서 약 ${roundTen(metersFromPlace)}m 떨어진 곳에 차를 세웠어요."
        }
        return "$PREFIX $where 차 위치: ${mapLink(car)}"
    }

    /** 자리 없음: "아이 학원 앞에 세울 곳이 없어 한 바퀴 돌고 오후 4:54쯤 다시 올게요. 만날 곳은 그대로예요." */
    fun circling(place: String, backClock: String): String =
        "$PREFIX $place 앞에 세울 곳이 없어 한 바퀴 돌고 ${backClock}쯤 다시 올게요. 만날 곳은 그대로예요."

    /** 앱 이름과 끝의 위치 링크 문장을 뺀 본문 */
    fun body(text: String): String =
        text.removePrefix(PREFIX).substringBefore(" $NOW_LABEL").substringBefore(" $CAR_LABEL").trim()

    private const val NOW_LABEL = "지금 차 위치:"
    private const val CAR_LABEL = "차 위치:"

    private fun roundTen(meters: Double): Int = ((meters / 10.0).roundToInt() * 10).coerceAtLeast(10)

    private const val NEAR_METERS = 40.0
}
