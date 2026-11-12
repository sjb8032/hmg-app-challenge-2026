package kr.contest.pleosapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kr.contest.pleosapp.app.LogKind

/*
 * 화면에 쓰는 작은 그림 (아이콘 라이브러리를 더하지 않으려고 직접 그린다). 모두 정사각형 영역에 맞춰 그린다.
 */

/** 앱 표시: 현대 네이비 바탕 위 하늘색 굽은 길과 도착 점 */
@Composable
internal fun Logo(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            Brush.linearGradient(listOf(RM.navyLight, RM.hyundaiNavy), start = Offset.Zero, end = Offset(w, h)),
            cornerRadius = CornerRadius(w * 0.18f),
        )
        val road = Path().apply {
            moveTo(w * 0.24f, h * 0.80f)
            cubicTo(w * 0.24f, h * 0.48f, w * 0.76f, h * 0.62f, w * 0.72f, h * 0.34f)
        }
        drawPath(road, RM.sky, style = Stroke(w * 0.10f, cap = StrokeCap.Round))
        drawCircle(Color.White, w * 0.11f, Offset(w * 0.72f, h * 0.25f))
        drawCircle(RM.hyundaiNavy, w * 0.05f, Offset(w * 0.72f, h * 0.25f))
    }
}

/** 온타임 모드: 시계 */
@Composable
internal fun ClockGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        val w = r * 0.14f
        drawCircle(color, r * 0.86f, c, style = Stroke(w))
        drawLine(color, c, Offset(c.x, c.y - r * 0.52f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(color, c, Offset(c.x + r * 0.40f, c.y + r * 0.14f), strokeWidth = w, cap = StrokeCap.Round)
        drawCircle(color, r * 0.11f, c)
    }
}

/** 롱웨이 모드: 굽이진 길 */
@Composable
internal fun RoadGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val road = Path().apply {
            moveTo(w * 0.18f, h * 0.94f)
            cubicTo(w * 0.18f, h * 0.58f, w * 0.84f, h * 0.64f, w * 0.80f, h * 0.38f)
            cubicTo(w * 0.77f, h * 0.18f, w * 0.42f, h * 0.24f, w * 0.42f, h * 0.06f)
        }
        drawPath(road, color, style = Stroke(w * 0.12f, cap = StrokeCap.Round))
    }
}

/** 레스트 모드: 초승달 */
@Composable
internal fun MoonGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val r = size.minDimension * 0.42f
        val c = Offset(size.width / 2f, size.height / 2f)
        val full = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
        val bite = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(c.x + r * 0.55f, c.y - r * 0.45f), r * 0.92f)) }
        val moon = Path().apply { op(full, bite, PathOperation.Difference) }
        drawPath(moon, color)
    }
}

/** 음성 안내: 스피커 */
@Composable
internal fun SpeakerGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val body = Path().apply {
            moveTo(w * 0.08f, h * 0.38f)
            lineTo(w * 0.28f, h * 0.38f)
            lineTo(w * 0.52f, h * 0.16f)
            lineTo(w * 0.52f, h * 0.84f)
            lineTo(w * 0.28f, h * 0.62f)
            lineTo(w * 0.08f, h * 0.62f)
            close()
        }
        drawPath(body, color)
        val stroke = Stroke(w * 0.08f, cap = StrokeCap.Round)
        drawArc(color, -45f, 90f, false, Offset(w * 0.42f, h * 0.30f), Size(w * 0.30f, h * 0.40f), style = stroke)
        drawArc(color, -50f, 100f, false, Offset(w * 0.36f, h * 0.14f), Size(w * 0.52f, h * 0.72f), style = stroke)
    }
}

/** 내비: 위치 핀 */
@Composable
internal fun PinGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val pin = Path().apply {
            moveTo(w * 0.5f, h * 0.94f)
            cubicTo(w * 0.18f, h * 0.60f, w * 0.14f, h * 0.44f, w * 0.14f, h * 0.38f)
            cubicTo(w * 0.14f, h * 0.17f, w * 0.30f, h * 0.04f, w * 0.5f, h * 0.04f)
            cubicTo(w * 0.70f, h * 0.04f, w * 0.86f, h * 0.17f, w * 0.86f, h * 0.38f)
            cubicTo(w * 0.86f, h * 0.44f, w * 0.82f, h * 0.60f, w * 0.5f, h * 0.94f)
            close()
        }
        drawPath(pin, color)
        drawCircle(RM.panel, w * 0.13f, Offset(w * 0.5f, h * 0.38f))
    }
}

/** 차량 제어: 차 옆모습 */
@Composable
internal fun CarGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val body = Path().apply {
            moveTo(w * 0.06f, h * 0.70f)
            lineTo(w * 0.06f, h * 0.52f)
            lineTo(w * 0.22f, h * 0.48f)
            lineTo(w * 0.34f, h * 0.28f)
            lineTo(w * 0.70f, h * 0.28f)
            lineTo(w * 0.82f, h * 0.48f)
            lineTo(w * 0.94f, h * 0.54f)
            lineTo(w * 0.94f, h * 0.70f)
            close()
        }
        drawPath(body, color)
        drawCircle(RM.panel, w * 0.13f, Offset(w * 0.28f, h * 0.72f))
        drawCircle(color, w * 0.09f, Offset(w * 0.28f, h * 0.72f))
        drawCircle(RM.panel, w * 0.13f, Offset(w * 0.72f, h * 0.72f))
        drawCircle(color, w * 0.09f, Offset(w * 0.72f, h * 0.72f))
    }
}

/** 시연 조작: 재생 삼각형 */
@Composable
internal fun PlayGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val tri = Path().apply {
            moveTo(w * 0.26f, h * 0.16f)
            lineTo(w * 0.84f, h * 0.5f)
            lineTo(w * 0.26f, h * 0.84f)
            close()
        }
        drawPath(tri, color)
    }
}

/** 안내 기록 종류별 그림과 색 */
@Composable
internal fun LogGlyph(kind: LogKind, modifier: Modifier) {
    val color = logColor(kind)
    when (kind) {
        LogKind.VOICE -> SpeakerGlyph(color, modifier)
        LogKind.NAVI -> PinGlyph(color, modifier)
        LogKind.VEHICLE -> CarGlyph(color, modifier)
        LogKind.DEMO -> PlayGlyph(color, modifier)
    }
}

internal fun logColor(kind: LogKind): Color = when (kind) {
    LogKind.VOICE -> RM.amber
    LogKind.NAVI -> RM.blue
    LogKind.VEHICLE -> RM.sky
    LogKind.DEMO -> RM.textFaint
}
