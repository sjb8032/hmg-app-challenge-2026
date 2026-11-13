package kr.contest.pleosapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.contest.pleosapp.app.AutoCue
import kr.contest.pleosapp.app.RestMateActions
import kr.contest.pleosapp.app.UiState
import kr.contest.pleosapp.core.DriveState
import kr.contest.pleosapp.core.DriveStep
import kr.contest.pleosapp.core.DriveText
import kr.contest.pleosapp.core.PickupNotice
import kr.contest.pleosapp.core.PickupNoticeKind

/*
 * 온타임 모드 '픽업 알림': 도착 3분 전·P단으로 세운 곳·자리 없음을 픽업할 사람에게 문자로 알린다.
 * Pleos SDK에 문자 기능이 없고 에뮬레이터는 휴대폰과 연결할 수 없어, 지금은 보낼 문자를 화면에 보여 주는 시연 기능이다.
 * 그래서 설정 버튼과 카드에 늘 '실제로 보내지 않음'을 함께 둔다 (시연 모드가 아니어도).
 */

/** 달리는 중·도착 화면에서 미니맵 아래 픽업 알림 카드가 차지하는 높이 (미니맵을 이만큼 줄인다) */
internal val PICKUP_CARD_HEIGHT = 230.dp

/** 픽업 알림을 켠 온타임 드라이브인지 */
internal fun DriveState.pickupOn(): Boolean = pickupTo != null

/** 설정 화면: 픽업 알림 켜기·끄기 (온타임 모드) */
@Composable
internal fun PickupToggle(ui: UiState, actions: RestMateActions, tone: Tone) {
    val d = ui.drive
    val on = d.pickupOn()
    val name = d.pickupTo ?: ui.pickupContact
    // 아직 실제로 보내는 수단이 없어 시연이든 아니든 보낼 문자를 화면에 띄우기만 한다.
    Label("픽업 알림 문자 · 보낼 문자를 화면에 띄워요 (실제로 보내지는 않아요)")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionButton(
            "알림 끄기",
            { actions.setPickup(false) },
            Modifier.weight(1f),
            if (!on) tone else Tone.NEUTRAL,
            minHeight = 64.dp,
            textSize = 20.sp,
        )
        ActionButton(
            "알림 켜기",
            { actions.setPickup(true) },
            Modifier.weight(1f).cueRing(ui.cueing(AutoCue.PICKUP)),
            if (on) tone else Tone.NEUTRAL,
            minHeight = 64.dp,
            textSize = 20.sp,
            sub = "$name · 3분 전 · 세운 곳 · 자리 없음",
        )
    }
}

/**
 * 픽업 알림 카드: 받는 사람, 가장 최근에 보낸 문자(말풍선), 앞서 보낸 문자, 다음에 무엇을 보내는지.
 * 지도 링크까지 실제 문자에 들어갈 그대로 보여 준다.
 */
@Composable
internal fun PickupPanel(ui: UiState) {
    val d = ui.drive
    val to = d.pickupTo ?: return
    val notices = d.pickupNotices
    Panel(spacing = 10.dp, padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MessageGlyph(RM.sky, Modifier.size(22.dp))
            Label("픽업 알림 · 받는 사람 $to", modifier = Modifier.weight(1f))
            Chip(if (ui.demoMode) "시연 · 실제로 보내지 않음" else "실제로 보내지 않음", RM.textDim, fontSize = 13.sp)
        }
        val last = notices.lastOrNull()
        if (last == null) {
            Caption(pickupHint(d))
            return@Panel
        }
        MessageBubble(last)
        val earlier = notices.dropLast(1)
        if (earlier.isNotEmpty()) {
            Caption(
                "앞서 띄운 문자: " + earlier.joinToString(" · ") { "${DriveText.clockNow(it.at)} ${it.kind.label}" },
                RM.textFaint,
            )
        }
        Caption(pickupHint(d))
    }
}

/** 다음에 어떤 문자를 띄우는지 (지금 단계에 맞춰 한 줄) */
private fun pickupHint(d: DriveState): String {
    val kinds = d.pickupNotices.map { it.kind }
    return when {
        d.circling -> "한 바퀴 돌고 와서 P단으로 세우면 세운 곳을 문자로 띄워요."
        d.step == DriveStep.ARRIVED && kinds.lastOrNull() == PickupNoticeKind.PARKED ->
            "다른 곳으로 옮겨 세우면 새 자리를 다시 띄워요."
        d.step == DriveStep.ARRIVED -> "P단으로 세우면 세운 곳을 문자로 띄워요. 세울 곳이 없으면 '자리 없음'."
        PickupNoticeKind.APPROACH !in kinds -> "도착 3분 전에 도착 시각·만날 곳·지금 차 위치를 문자로 띄워요."
        else -> "도착해서 P단으로 세우면 세운 곳을 문자로 띄워요."
    }
}

/** 보낸 문자 말풍선: 본문, 지도 링크, 보낸 시각과 종류 */
@Composable
private fun MessageBubble(n: PickupNotice) {
    val shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 4.dp)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(RM.sky.copy(alpha = 0.10f))
                .border(1.dp, RM.sky.copy(alpha = 0.35f), shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(n.body, color = RM.text, fontSize = 18.sp, lineHeight = 25.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            n.link?.let {
                Text(it, color = RM.blue, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text("${DriveText.clockNow(n.at)} · ${n.kind.label}", color = RM.textFaint, fontSize = 13.sp, style = NumStyle)
    }
}
