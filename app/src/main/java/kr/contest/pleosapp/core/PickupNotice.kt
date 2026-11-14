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

/**
 * 픽업 알림 문구. 문자 앞에 앱 이름을 붙여 자동으로 보낸 문자임을 알린다.
 *
 * 받는 사람이 아이면 부모가 아이에게 보내듯 짧은 반말로 쓴다 (학원 픽업 시연). 장소는 부모가 붙인 이름에서
 * '아이 '를 떼고 ("아이 학원" → "학원"), 시각은 "9시 50분"처럼 읽기 쉽게 쓴다. 그 밖의 사람(동승자)에게는 존댓말로 쓴다.
 */
object PickupText {
    const val PREFIX = "[드라이브메이트]"

    /** 카카오맵 위치 링크 (map.kakao.com/link/map/이름,위도,경도). 이름은 문자에서 링크가 끊기지 않게 영문으로 둔다. */
    fun mapLink(p: LatLng): String =
        String.format(Locale.US, "https://map.kakao.com/link/map/DriveMate,%.5f,%.5f", p.lat, p.lon)

    /** 아이에게 보내는 문자인지 (받는 사람 이름으로 본다: 아이·아들·딸, '우리 아이'처럼 '아이'로 끝나는 이름) */
    fun toChild(to: String): Boolean = to.trim().let { it in CHILD_NAMES || it.endsWith(" 아이") }

    /**
     * 도착 3분 전.
     * 아이에게: "곧 도착해! 9시 50분쯤 학원 앞에서 만나자. 지금 차 위치: …"
     * 그 밖에: "오후 9:50쯤 광화문 약속 장소 앞에 도착해요. 광화문 약속 장소 앞에서 만나요. 지금 차 위치: …"
     */
    fun approach(to: String, place: String, eta: Long, car: LatLng?): String {
        val where = car?.let { " $NOW_LABEL ${mapLink(it)}" }.orEmpty()
        val body = if (toChild(to)) {
            "곧 도착해! ${DriveText.spokenClock(eta)}쯤 ${childPlace(place)} 앞에서 만나자."
        } else {
            "${DriveText.clock(eta)}쯤 $place 앞에 도착해요. $place 앞에서 만나요."
        }
        return "$PREFIX $body$where"
    }

    /** P단으로 세운 곳: 목적지에서 40m 안이면 '바로 앞', 아니면 10m 단위 거리 */
    fun parked(to: String, place: String, metersFromPlace: Double, car: LatLng): String {
        val near = metersFromPlace < NEAR_METERS
        val meters = roundTen(metersFromPlace)
        val body = if (toChild(to)) {
            val p = childPlace(place)
            if (near) "$p 바로 앞에 차 세웠어. 천천히 나와!" else "${p}에서 ${meters}m쯤 떨어진 곳에 차 세웠어. 지도 보고 천천히 와!"
        } else {
            if (near) "$place 바로 앞에 차를 세웠어요." else "${place}에서 약 ${meters}m 떨어진 곳에 차를 세웠어요."
        }
        return "$PREFIX $body $CAR_LABEL ${mapLink(car)}"
    }

    /**
     * 자리 없음.
     * 아이에게: "학원 앞에 차 세울 데가 없어서 한 바퀴 돌고 9시 54분쯤 다시 올게. 만나는 곳은 그대로니까 조금만 기다려 줘!"
     * 그 밖에: "… 앞에 세울 곳이 없어 한 바퀴 돌고 오후 9:54쯤 다시 올게요. 만날 곳은 그대로예요."
     */
    fun circling(to: String, place: String, backAt: Long): String {
        val body = if (toChild(to)) {
            "${childPlace(place)} 앞에 차 세울 데가 없어서 한 바퀴 돌고 ${DriveText.spokenClock(backAt)}쯤 다시 올게. " +
                "만나는 곳은 그대로니까 조금만 기다려 줘!"
        } else {
            "$place 앞에 세울 곳이 없어 한 바퀴 돌고 ${DriveText.clock(backAt)}쯤 다시 올게요. 만날 곳은 그대로예요."
        }
        return "$PREFIX $body"
    }

    /** 앱 이름과 끝의 위치 링크 문장을 뺀 본문 */
    fun body(text: String): String =
        text.removePrefix(PREFIX).substringBefore(" $NOW_LABEL").substringBefore(" $CAR_LABEL").trim()

    /** 아이에게 말할 장소 이름: 부모가 붙인 '아이 '를 뗀다 ("아이 학원" → "학원") */
    private fun childPlace(place: String): String = place.removePrefix(CHILD_PREFIX).trim().ifEmpty { place }

    private const val NOW_LABEL = "지금 차 위치:"
    private const val CAR_LABEL = "차 위치:"
    private const val CHILD_PREFIX = "아이 "
    private val CHILD_NAMES = setOf("아이", "아들", "딸")

    private fun roundTen(meters: Double): Int = ((meters / 10.0).roundToInt() * 10).coerceAtLeast(10)

    private const val NEAR_METERS = 40.0
}
