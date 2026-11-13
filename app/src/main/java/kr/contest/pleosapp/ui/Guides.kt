package kr.contest.pleosapp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.contest.pleosapp.app.UiState
import kr.contest.pleosapp.core.DriveMode
import kr.contest.pleosapp.core.DriveStep
import kr.contest.pleosapp.core.Gear
import kr.contest.pleosapp.core.Phase
import kr.contest.pleosapp.core.RestKind

/*
 * 보는 사람이 지금 무슨 일이 일어나는지 바로 알 수 있게 돕는 것들: 음성 안내 자막, 드라이브 단계, 시연 순서 안내.
 */

/** 음성 안내 자막을 띄워 두는 시간 (실제 시간) */
internal const val CAPTION_MS = 14_000L

/** 방금 한 음성 안내를 자막으로 보여 줄 때인지 */
internal fun captionVisible(ui: UiState): Boolean =
    ui.lastMessage != null && ui.realNow - ui.lastMessageAt in 0..CAPTION_MS

/**
 * 음성 안내 자막: 방금 말한 문구를 화면 아래(큰 버튼 위)에 크게 띄웠다가 [CAPTION_MS] 뒤 접는다.
 * 소리가 나지 않는 에뮬레이터와 녹화 영상에서도 차가 무엇을 말했는지 읽힌다.
 */
@Composable
internal fun ColumnScope.CaptionSlot(ui: UiState) {
    AnimatedVisibility(
        visible = captionVisible(ui),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        label = "caption",
    ) {
        CaptionBar(ui.lastMessage.orEmpty())
    }
}

@Composable
private fun CaptionBar(text: String) {
    val shape = CardShape
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RM.caption)
            .border(1.dp, RM.amber.copy(alpha = 0.35f), shape)
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .background(RM.amber.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            SpeakerGlyph(RM.amber, Modifier.size(26.dp))
        }
        Text(
            text,
            color = RM.text,
            fontSize = 23.sp,
            lineHeight = 31.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 드라이브 흐름의 어디쯤인지: ① 설정 › ② 코스 › ③ 주행 › ④ 도착 */
@Composable
internal fun StepIndicator(step: DriveStep, mode: DriveMode?) {
    val index = when (step) {
        DriveStep.HOME -> -1
        DriveStep.SETUP -> 0
        DriveStep.CHOOSE -> 1
        DriveStep.ACTIVE -> 2
        DriveStep.ARRIVED -> 3
    }
    if (index < 0) return
    val accent = modeColor(mode)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        STEP_NAMES.forEachIndexed { i, name ->
            val current = i == index
            val color: Color = when {
                current -> accent
                i < index -> RM.textDim
                else -> RM.textFaint
            }
            Box(
                Modifier
                    .size(26.dp)
                    .background(if (current) accent else Color.Transparent, CircleShape)
                    .border(1.5.dp, color, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (i < index) "✓" else "${i + 1}",
                    color = if (current) RM.background else color,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(name, color = color, fontSize = 17.sp, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium)
            if (i < STEP_NAMES.lastIndex) {
                Box(
                    Modifier
                        .width(18.dp)
                        .height(1.5.dp)
                        .background(RM.line),
                )
            }
        }
    }
}

private val STEP_NAMES = listOf("설정", "코스", "주행", "도착")

/** 시연 중 다음에 보여 줄 것 (발표자용 한 줄). 시연 모드가 아니면 null */
internal fun demoHint(ui: UiState): String? {
    if (!ui.demoMode) return null
    val s = ui.core
    val d = ui.drive
    val driving = ui.demoDriving
    return when (s.phase) {
        Phase.TO_REST_AREA ->
            if (driving) "쉼터에 닿으면 저절로 주차하고 쉬기 시작해요." else "'출발 · 주행'을 누르면 쉼터로 가요."
        Phase.RESTING -> {
            val rest = s.rest
            if (rest != null && rest.kind == RestKind.NAP && rest.wakeStartedAt == null) {
                "'휴식 시간 건너뛰기'로 알람과 깨우기를 바로 보여 줄 수 있어요."
            } else {
                "'출발 · 주행'을 누르면 다시 출발해요."
            }
        }
        Phase.DRIVING -> when (d.step) {
            DriveStep.HOME ->
                if (driving) "달리는 중이에요. '레스트 모드'를 누르면 가까운 쉼터로 안내해요."
                else "모드를 골라 시작해요. 고르는 동안 가상 시계는 멈춰 있어요."
            DriveStep.SETUP -> "끝낼 곳과 시각을 고르고 '코스 찾기'를 눌러요."
            DriveStep.CHOOSE -> "코스를 고르면 내비 경로에 경유지가 들어가요."
            DriveStep.ACTIVE -> when {
                !driving -> "'출발 · 주행'을 누르면 가상 시계가 흐르고 차가 움직여요."
                d.circling -> "한 바퀴 도는 중이에요. '10분 건너뛰기'로 다시 픽업할 곳까지 빨리 갈 수 있어요."
                d.lateSince != null -> "늦음을 지켜보는 중이에요. 이어지면 더 짧은 길로 바꿔요."
                d.viaActive && !ui.demoJammed -> "'막힘 +6분'으로 정체를 넣으면 도착 시각을 다시 맞춰요."
                else -> "'10분 건너뛰기'로 도착까지 빨리 갈 수 있어요."
            }
            DriveStep.ARRIVED -> when {
                d.pickupTo != null && s.gear != Gear.PARK ->
                    "세울 곳이 있으면 'P단 주차'(세운 곳 문자), 없으면 '자리 없음'(한 바퀴 돌고 다시 와요)."
                else -> "'처음으로'를 누르면 다른 모드를 이어서 보여 줄 수 있어요."
            }
        }
    }
}
