package kr.contest.pleosapp.core

/** 실내 환경을 바꿀 때 하나하나 제어하는 것. [label]은 되지 않았을 때 쓰는 이름 */
enum class CabinPart(val label: String) {
    POWER("공조 전원"),
    TEMPERATURE("온도"),
    FAN("바람 세기"),
    RECIRCULATION("내외기"),
    AMBIENT("무드등"),
}

/**
 * 실내 환경을 바꾼 결과 한 줄: "휴식 모드: 23°C · 약한 바람 · 바깥 공기 · 무드등 은은하게".
 *
 * 이 차에서 되지 않은 제어는 줄에서 빼고 끝에 짧게 덧붙인다. 하나가 안 됐다고 오류 문구만 남기면
 * 바꾼 것까지 안 보인다 (Pleos Connect 에뮬레이터는 공조를 켜도 바람 세기를 바꿀 수 없다).
 * 바꾸려던 것이 하나도 되지 않았을 때만 권한·차종을 확인해 달라고 쓴다.
 *
 * @param parts 보여 줄 말과 그 말이 달린 제어
 */
data class CabinSummary(val title: String, val parts: List<Pair<String, CabinPart>>) {
    fun text(failed: Collection<CabinPart> = emptyList()): String {
        val shown = parts.filter { (_, part) -> part !in failed }
        if (shown.isEmpty() && parts.isNotEmpty()) return ALL_FAILED
        val body = "$title: ${shown.joinToString(" · ") { it.first }}"
        if (failed.isEmpty()) return body
        return "$body (${DriveText.topic(names(failed))} 이 차에서 바꿀 수 없어 그대로 뒀어요)"
    }

    companion object {
        const val ALL_FAILED = "실내 환경을 바꾸지 못했어요. 차량 제어 권한과 차종 지원 여부를 확인해 주세요."

        /** 되돌린 결과: 되돌리지 못한 것이 있으면 직접 확인해 달라고 덧붙인다 */
        fun restored(message: String, failed: Collection<CabinPart>): String {
            if (failed.isEmpty()) return message
            return "$message ${DriveText.topic(names(failed))} 되돌리지 못했으니 직접 확인해 주세요."
        }

        private fun names(parts: Collection<CabinPart>): String =
            parts.distinct().sortedBy { it.ordinal }.joinToString("·") { it.label }
    }
}
