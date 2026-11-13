package kr.contest.pleosapp.core

import java.util.Calendar

/**
 * 도착 시각 고르기에서 고른 값: 날짜(오늘 0·내일 1), 오전·오후, 시(1~12), 분.
 *
 * '오늘'은 고르기 창을 연 날이다. 창을 열어 둔 채 자정을 넘겨 눌러도 고른 날짜가 하루 밀리지 않는다.
 * 날짜는 달력으로 더해, 서머타임이 있는 시간대에서도 시각이 한 시간 어긋나지 않는다.
 */
data class ClockPick(val day: Int, val pm: Boolean, val hour12: Int, val minute: Int) {
    /** 24시간제 시 (오전 12시 = 0시, 오후 12시 = 12시) */
    val hour24: Int get() = (hour12 % 12) + if (pm) 12 else 0

    /** [baseDay](창을 연 날 0시)부터 센 시각 */
    fun at(baseDay: Long): Long = Calendar.getInstance().run {
        timeInMillis = baseDay
        add(Calendar.DAY_OF_MONTH, day)
        set(Calendar.HOUR_OF_DAY, hour24)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    companion object {
        /** [now]가 든 날의 0시 */
        fun dayStart(now: Long): Long = Calendar.getInstance().run {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            timeInMillis
        }

        /** [at]을 [baseDay] 기준으로 나눈다. 모레 이후는 내일로, 어제 이전은 오늘로 둔다 (고를 수 있는 날이 둘뿐이라) */
        fun of(baseDay: Long, at: Long): ClockPick {
            val c = Calendar.getInstance().apply { timeInMillis = at }
            val hour = c.get(Calendar.HOUR_OF_DAY)
            val tomorrow = Calendar.getInstance().run {
                timeInMillis = baseDay
                add(Calendar.DAY_OF_MONTH, 1)
                timeInMillis
            }
            val h12 = (hour % 12).let { if (it == 0) 12 else it }
            return ClockPick(if (at >= tomorrow) 1 else 0, hour >= 12, h12, c.get(Calendar.MINUTE))
        }
    }
}
