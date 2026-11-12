package kr.contest.pleosapp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color

/**
 * 차 안에서 한눈에 읽히도록 어두운 바탕에 대비가 큰 색만 쓴다.
 * - 청록: 딱 맞춰 가요·휴식·안정 / 보라: 좀 더 달려요 / 호박색: 쉬었다 가요·주의 (행동을 부르는 버튼)
 * - 빨강: 연결 실패 같은 오류
 */
object RM {
    val background = Color(0xFF0B0F14)
    val panel = Color(0xFF131921)
    val card = Color(0xFF1A212B)
    val cardHigh = Color(0xFF222B37)
    val button = Color(0xFF313D4E)
    val line = Color(0xFF2E3846)
    val text = Color(0xFFF1F4F8)
    val textDim = Color(0xFF9BA6B4)
    val teal = Color(0xFF4FD1C5)
    val onTeal = Color(0xFF00201D)
    val amber = Color(0xFFFFB547)
    val onAmber = Color(0xFF2A1A00)
    val violet = Color(0xFFB9A6FF)
    val onViolet = Color(0xFF1C1240)
    val red = Color(0xFFFF6B6B)
}

@Composable
fun RestMateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = RM.teal,
            onPrimary = RM.onTeal,
            secondary = RM.amber,
            onSecondary = RM.onAmber,
            background = RM.background,
            onBackground = RM.text,
            surface = RM.panel,
            onSurface = RM.text,
            error = RM.red,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = RM.background, contentColor = RM.text, content = content)
    }
}
