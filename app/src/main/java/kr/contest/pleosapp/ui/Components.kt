package kr.contest.pleosapp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.contest.pleosapp.core.DriveMode
import java.util.Locale

/*
 * 드라이브메이트 화면의 공통 부품. 크기 기준:
 * - 누를 곳: 작은 버튼 54dp, 보통 64~76dp, 아래 큰 버튼 104dp (장갑을 끼고도 누를 수 있게)
 * - 글자: 큰 숫자 44~72sp, 제목 26~30sp, 본문 20sp, 설명 15~16sp / 버튼 글자 굵기 500, 자간 좁게
 * - 모서리: 카드 10dp, 버튼·칩 6dp (현대 제품 화면의 각진 버튼) / 테두리 1dp (강조할 때 2dp 색 테두리), 그림자 없음
 */

/**
 * 화면 크기에 따라 바뀌는 배치 값.
 * @param wide 왼쪽 내용 영역이 넓어 두 칸(내용 | 지도)으로 나눌 수 있는지
 * @param compact 두 칸이지만 폭이 빠듯해 큰 숫자·제목을 조금 줄이는지 (1280dp 안팎의 화면)
 * @param mapHeight 미니맵 높이
 */
internal data class ScreenSize(val wide: Boolean, val mapHeight: Dp, val compact: Boolean = false)

internal val LocalScreenSize = staticCompositionLocalOf { ScreenSize(wide = true, mapHeight = 380.dp) }

/** 넓은 화면에서는 왼쪽(조작·숫자) | 오른쪽(지도·시간표) 두 칸, 좁으면 위아래로 쌓는다. */
@Composable
internal fun TwoPane(
    wide: Boolean,
    leftWeight: Float = 1.1f,
    rightWeight: Float = 1f,
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit,
) {
    if (wide) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(leftWeight), verticalArrangement = Arrangement.spacedBy(16.dp), content = left)
            Column(Modifier.weight(rightWeight), verticalArrangement = Arrangement.spacedBy(16.dp), content = right)
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            left()
            right()
        }
    }
}

internal enum class Tone {
    SKY,
    SAND,
    AMBER,
    NEUTRAL,
    ;

    /** 버튼을 채우는 색 (낮·밤 색 한 벌에서 그때그때 읽는다) */
    val container: Color
        get() = when (this) {
            SKY -> RM.sky
            SAND -> RM.sandFill
            AMBER -> RM.amberFill
            NEUTRAL -> RM.button
        }

    /** 그 위 글자 */
    val content: Color
        get() = when (this) {
            SKY -> RM.onSky
            SAND -> RM.onSand
            AMBER -> RM.onAmber
            NEUTRAL -> RM.text
        }
}

/** 모드 색: 온타임 하늘색(스카이 블루), 롱웨이 샌드 */
internal fun modeColor(mode: DriveMode?): Color = when (mode) {
    DriveMode.ON_TIME -> RM.sky
    DriveMode.LONGER -> RM.sand
    null -> RM.textDim
}

internal fun modeTone(mode: DriveMode?): Tone = when (mode) {
    DriveMode.LONGER -> Tone.SAND
    else -> Tone.SKY
}

@Composable
internal fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.NEUTRAL,
    minHeight: Dp = 76.dp,
    textSize: TextUnit = 24.sp,
    sub: String? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = minHeight),
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = tone.container,
            contentColor = tone.content,
            disabledContainerColor = RM.card,
            disabledContentColor = RM.textFaint,
        ),
        border = if (tone == Tone.NEUTRAL) BorderStroke(1.dp, if (enabled) RM.line else RM.border) else null,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 글자 색은 버튼 색에 맞춰 직접 준다. 앱 기본 글자 스타일에 색(RM.text)이 있어,
            // 주지 않으면 실제 기기에서는 하늘색·샌드 버튼 위 글자가 어두운 남색으로 나온다 (캡처 도구에서는 흰색이라 놓치기 쉽다).
            val color = LocalContentColor.current
            // 현대 제품 화면의 버튼 글자 굵기(500)
            Text(text, color = color, fontSize = textSize, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            if (sub != null) {
                Text(
                    sub,
                    color = color,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(0.8f),
                )
            }
        }
    }
}

@Composable
internal fun BigButton(text: String, sub: String?, tone: Tone, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ActionButton(text, onClick, modifier.fillMaxWidth(), tone, minHeight = 104.dp, textSize = 38.sp, sub = sub)
}

@Composable
internal fun SmallButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    ActionButton(text, onClick, modifier, Tone.NEUTRAL, minHeight = 54.dp, textSize = 16.sp, enabled = enabled)
}

/** 카드. [border]를 주면 그 색으로 강조한다 (테두리 2dp + 아주 옅은 같은 색 바탕). */
@Composable
internal fun Panel(
    modifier: Modifier = Modifier,
    color: Color = RM.card,
    border: Color? = null,
    padding: Dp = 22.dp,
    spacing: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = CardShape
    val background = if (border != null) border.copy(alpha = 0.07f).compositeOver(color) else color
    val stroke = if (border != null) BorderStroke(2.dp, border) else BorderStroke(1.dp, RM.border)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .border(stroke, shape)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

@Composable
internal fun Label(text: String, color: Color = RM.textDim, modifier: Modifier = Modifier) {
    Text(text, color = color, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = modifier)
}

@Composable
internal fun Body(text: String, color: Color = RM.text) {
    Text(text, color = color, fontSize = 20.sp, lineHeight = 29.sp)
}

@Composable
internal fun Caption(text: String, color: Color = RM.textDim, modifier: Modifier = Modifier) {
    Text(text, color = color, fontSize = 15.sp, lineHeight = 22.sp, modifier = modifier)
}

/** 카드 밖에 두는 한 줄 알림. 왼쪽에 색 막대로 종류를 보여 준다. */
@Composable
internal fun Notice(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .padding(end = 12.dp)
                .size(width = 4.dp, height = 24.dp)
                .background(color, RoundedCornerShape(2.dp)),
        )
        Text(text, color = if (color == RM.textDim) RM.textDim else RM.text, fontSize = 19.sp, lineHeight = 27.sp)
    }
}

/** 상태 칩: 옅은 색 바탕 + 같은 색 글자 */
@Composable
internal fun Chip(text: String, color: Color, fontSize: TextUnit = 16.sp) {
    Text(
        text,
        color = color,
        fontSize = fontSize,
        fontWeight = FontWeight.SemiBold,
        style = NumStyle,
        maxLines = 1,
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), ChipShape)
            .border(1.dp, color.copy(alpha = 0.45f), ChipShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** 동그란 번호 (코스 후보 1·2·3, 쉼터 P). 지도와 목록에서 같은 색·같은 번호로 쓴다. */
@Composable
internal fun NumberBadge(text: String, color: Color, size: Dp = 28.dp, ring: Color = RM.map) {
    Box(
        Modifier
            .size(size)
            .background(color, CircleShape)
            .border(2.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = RM.background,
            fontSize = (size.value * 0.52f).sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

/** 이름 + 큰 숫자 (시각·속도) */
@Composable
internal fun Stat(label: String, value: String, color: Color, modifier: Modifier = Modifier, valueSize: TextUnit = 30.sp) {
    Column(modifier) {
        Caption(label)
        Text(value, color = color, fontSize = valueSize, fontWeight = FontWeight.SemiBold, style = NumStyle, maxLines = 1)
    }
}

/** 작은 색 점 (연결 상태 등) */
@Composable
internal fun Dot(color: Color, size: Dp = 10.dp) {
    Box(Modifier.size(size).background(color, CircleShape))
}

@Composable
internal fun Bar(fraction: Float, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(RM.line, CircleShape),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(8.dp)
                .background(color, CircleShape),
        )
    }
}

@Composable
internal fun Rule() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(RM.border),
    )
}

/** 남은 시간 표시: "12:05", "1:02:03" */
internal fun clock(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) + 999L) / 1000L
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    val sec = total % 60L
    return if (h > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", m, sec)
    }
}
