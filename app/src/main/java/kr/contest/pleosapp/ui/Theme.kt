package kr.contest.pleosapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * 차 안에서 한눈에 읽히도록 어두운 바탕에 대비가 큰 색만 쓴다. 색은 뜻이 있을 때만 쓴다.
 * - 청록: 온타임 모드·휴식·정상 / 보라: 롱웨이 모드·경치 / 호박색: 레스트 모드·주의·기다리기 / 파랑: 경로·내비
 * - 빨강: 연결 실패 같은 오류
 */
object RM {
    val background = Color(0xFF090D12)
    val backgroundTop = Color(0xFF0F1620)
    val panel = Color(0xFF0F151D)
    val card = Color(0xFF141B24)
    val cardHigh = Color(0xFF1B242F)
    val button = Color(0xFF212B37)
    val border = Color(0xFF222C38)
    val line = Color(0xFF2A3441)
    val text = Color(0xFFF3F6F9)
    val textDim = Color(0xFF9AA6B4)
    val textFaint = Color(0xFF6C7887)
    val teal = Color(0xFF3AD3C2)
    val tealDeep = Color(0xFF1E9C90)
    val onTeal = Color(0xFF00211E)
    val amber = Color(0xFFFFB547)
    val amberDeep = Color(0xFFE08A12)
    val onAmber = Color(0xFF2A1A00)
    val violet = Color(0xFFAA99FF)
    val violetDeep = Color(0xFF7A66E6)
    val onViolet = Color(0xFF1A1240)
    val blue = Color(0xFF5AA8FF)
    val red = Color(0xFFFF6B6B)

    // 미니맵
    val mapTop = Color(0xFF101A24)
    val map = Color(0xFF0B1218)
    val mapGrid = Color(0xFF16212B)
    val mapLabel = Color(0xE60B1117)
    val trail = Color(0xFF5B6878)

    /** 음성 안내 자막 바탕 */
    val caption = Color(0xF2111921)
}

/** 시각·숫자: 자리마다 폭이 같은 숫자로 (시계처럼 흔들리지 않게) */
val NumStyle = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun RestMateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = RM.teal,
            onPrimary = RM.onTeal,
            secondary = RM.amber,
            onSecondary = RM.onAmber,
            tertiary = RM.violet,
            onTertiary = RM.onViolet,
            background = RM.background,
            onBackground = RM.text,
            surface = RM.panel,
            onSurface = RM.text,
            error = RM.red,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = RM.background, contentColor = RM.text) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(RM.backgroundTop, RM.background))),
            ) {
                CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.merge(TextStyle(color = RM.text))) {
                    content()
                }
            }
        }
    }
}
