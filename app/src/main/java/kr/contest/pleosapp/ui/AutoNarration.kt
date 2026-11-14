package kr.contest.pleosapp.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kr.contest.pleosapp.app.AutoChapter
import kr.contest.pleosapp.app.AutoCue
import kr.contest.pleosapp.app.AutoStatus
import kr.contest.pleosapp.app.UiState

/*
 * 자동 시연 화면: 지금 장면의 해설(화면 위)과, 곧 누를 버튼을 짚는 깜빡이는 테두리.
 * 해설은 발표자 자막처럼 앱 화면과 구분되게 왼쪽에 이야기 색 막대를 둔다.
 */

/** 해설 칸 높이 (미니맵 높이를 그만큼 줄인다) */
internal val NARRATION_HEIGHT = 190.dp

/** 이야기 색: 온타임 하늘색, 롱웨이 샌드, 레스트 호박색 (처음·끝 인사는 하늘색) */
internal fun chapterColor(chapter: AutoChapter?): Color = when (chapter) {
    AutoChapter.LONGER -> RM.sand
    AutoChapter.REST -> RM.amber
    else -> RM.sky
}

/** 자동 시연이 지금 [cue]를 짚고 있는지 */
internal fun UiState.cueing(cue: AutoCue): Boolean = auto?.cue == cue

/** 자동 시연 해설. 자동 시연 중에만 머리말 아래에 펼친다. */
@Composable
internal fun ColumnScope.AutoNarrationSlot(ui: UiState) {
    AnimatedVisibility(
        visible = ui.auto != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        label = "autoNarration",
    ) {
        // 사라지는 동안에도 마지막 해설을 그대로 보여 준다
        ui.auto?.let { AutoNarrationBar(it) }
    }
}

@Composable
internal fun AutoNarrationBar(status: AutoStatus) {
    val accent = chapterColor(status.chapter)
    val compact = LocalScreenSize.current.compact
    val shape = CardShape
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RM.cardHigh)
            .border(1.dp, accent.copy(alpha = 0.55f), shape)
            // 왼쪽 이야기 색 막대
            .drawBehind { drawRect(accent, size = Size(6.dp.toPx(), size.height)) },
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(start = 26.dp, end = 22.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("자동 시연", accent, fontSize = 14.sp)
                Text(
                    status.chapter?.title ?: "드라이브메이트",
                    color = RM.textDim,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                status.tag?.let { Chip(it, RM.amber, fontSize = 13.sp) }
                Text(
                    "${status.scene} / ${status.total}",
                    color = RM.textDim,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    style = NumStyle,
                )
            }
            // 장면이 바뀌면 제목·해설을 살짝 바꿔 끼운다
            AnimatedContent(
                targetState = status,
                transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) },
                contentKey = { it.scene to it.cue },
                label = "autoScene",
            ) { s ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            s.title,
                            color = RM.text,
                            fontSize = if (compact) 24.sp else 28.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        s.cueLabel?.let { CueLabel(it) }
                    }
                    Text(
                        s.text,
                        color = RM.text.copy(alpha = 0.9f),
                        fontSize = if (compact) 18.sp else 21.sp,
                        lineHeight = if (compact) 25.sp else 29.sp,
                        minLines = 2,
                        maxLines = if (compact) 2 else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            SceneProgress(status.scene, status.total, accent)
        }
    }
}

/** 짚는 중: "◎ '코스 찾기' 누르기" */
@Composable
private fun CueLabel(text: String) {
    Row(
        Modifier
            .clip(ChipShape)
            .background(RM.text.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TapGlyph(RM.text, Modifier.size(18.dp))
        Text(text, color = RM.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** 전체 장면 중 어디쯤인지: 지난 장면은 채우고 지금 장면은 옅게 */
@Composable
private fun SceneProgress(scene: Int, total: Int, color: Color) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 1..total) {
            val fill = when {
                i < scene -> color
                i == scene -> color.copy(alpha = 0.55f)
                else -> RM.line
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .background(fill, CircleShape),
            )
        }
    }
}

/**
 * 자동 시연이 곧 누를 곳: 흰 테두리가 숨 쉬듯 깜빡인다 (영상에서 어디를 누르는지 보이게).
 * 하늘색 테두리를 한 겹 더 둘러 버튼 색이 무엇이든 눈에 띄게 한다.
 * 짚는 버튼이 스크롤 아래에 가려 있으면 보이게 끌어온다 (실제 차 화면처럼 세로가 짧고 해설이 떠 있을 때,
 * 롱웨이의 '+15분 더'가 화면 밖에 있어 누르는 것이 보이지 않았다).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.cueRing(on: Boolean, shape: Shape = ButtonShape): Modifier {
    if (!on) return this
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(requester) {
        // 자리를 잡은 뒤에 끌어온다 (막 그려진 순간에는 위치를 아직 모른다)
        delay(BRING_INTO_VIEW_DELAY_MS)
        requester.bringIntoView()
    }
    val pulse = rememberInfiniteTransition(label = "cue")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "cueAlpha",
    )
    // 크기를 바꾸지 않고 버튼 가장자리 위에 그린다 (짚는 동안 화면이 움직이지 않게)
    return this
        .bringIntoViewRequester(requester)
        .border(7.dp, RM.sky.copy(alpha = alpha * 0.5f), shape)
        .border(3.dp, RM.text.copy(alpha = alpha), shape)
}

/** 짚는 버튼을 화면 안으로 끌어오기 전에 기다리는 시간 (자리를 잡을 때까지) */
private const val BRING_INTO_VIEW_DELAY_MS = 80L
