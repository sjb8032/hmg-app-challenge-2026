package kr.contest.pleosapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 현대자동차 디자인 시스템(KR 제품 화면)의 색과 형태를 차 안 화면에 맞춰 옮겼다.
 *
 * - 바탕: 현대 네이비(#002C5F)를 어둡게 내린 밤 화면. 웹은 흰 바탕이지만 차 안에서는 밤 운전에 눈부시지 않게 어둡게 둔다.
 * - 하늘색(스카이 블루 #00AAD2): 온타임 모드·선택·정상 / 액티브 블루(#007FA8): 진행 막대·눌린 곳
 * - 샌드: 롱웨이 모드·경치 (여유로운 쪽을 따뜻한 색으로 구분)
 * - 호박색: 레스트 모드·주의·기다리기. 안전 경고라 브랜드 색으로 바꾸지 않는다.
 * - 빨강: 연결 실패 같은 오류
 *
 * 형태: 버튼은 각지게(현대 웹의 0px 버튼·6px 페이저를 따라 작은 모서리), 그림자 없이 평평하게.
 * 글꼴: Hyundai Sans는 라이선스 받은 파일이 없어 시스템 글꼴을 쓰고, 버튼 굵기(500)와 좁은 자간(-0.4)만 맞춘다.
 */
object RM {
    // 현대 브랜드 색 (디자인 시스템 문서의 값 그대로)
    val hyundaiNavy = Color(0xFF002C5F)
    val activeBlue = Color(0xFF007FA8)
    val skyBlue = Color(0xFF00AAD2)

    /** 네이비를 조금 밝힌 색 (모드 카드의 위쪽) */
    val navyLight = Color(0xFF003C7E)

    // 바탕·면 (네이비를 어둡게)
    val background = Color(0xFF020B17)
    val backgroundTop = Color(0xFF051A33)
    val panel = Color(0xFF061932)
    val card = Color(0xFF0A2140)
    val cardHigh = Color(0xFF0E2A50)
    val button = Color(0xFF0D2647)
    val border = Color(0xFF16345C)
    val line = Color(0xFF214472)

    // 글자
    val text = Color(0xFFFFFFFF)
    val textDim = Color(0xFFA9B7C9)
    val textFaint = Color(0xFF7C90AC)

    // 뜻이 있는 색
    val sky = skyBlue
    val skyDeep = activeBlue
    val onSky = Color(0xFF00213F)
    val sand = Color(0xFFE4DCD3)
    val sandDeep = Color(0xFFBFB2A3)
    val onSand = hyundaiNavy
    val amber = Color(0xFFFFB547)
    val amberDeep = Color(0xFFE08A12)
    val onAmber = Color(0xFF2A1A00)
    val blue = Color(0xFF7AA2FF)
    val red = Color(0xFFFF6B6B)

    // 미니맵
    val mapTop = Color(0xFF062243)
    val map = Color(0xFF03142A)
    val mapGrid = Color(0xFF0B2A4D)
    val mapLabel = Color(0xE6031428)
    val trail = Color(0xFF55708F)

    /** 음성 안내 자막 바탕 */
    val caption = Color(0xF2061A33)
}

/** 버튼: 현대 웹의 각진 버튼을 따라 작은 모서리 */
internal val ButtonShape = RoundedCornerShape(6.dp)

/** 카드·패널 */
internal val CardShape = RoundedCornerShape(10.dp)

/** 상태 칩 (현대 웹 페이저의 6px) */
internal val ChipShape = RoundedCornerShape(6.dp)

/** 시각·숫자: 자리마다 폭이 같은 숫자로 (시계처럼 흔들리지 않게) */
val NumStyle = TextStyle(fontFeatureSettings = "tnum")

/** 기본 글자: 현대 제품 화면처럼 자간을 조금 좁게 */
private val BaseTextStyle = TextStyle(color = RM.text, letterSpacing = (-0.3).sp)

@Composable
fun RestMateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = RM.sky,
            onPrimary = RM.onSky,
            secondary = RM.amber,
            onSecondary = RM.onAmber,
            tertiary = RM.sand,
            onTertiary = RM.onSand,
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
                CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.merge(BaseTextStyle)) {
                    content()
                }
            }
        }
    }
}
