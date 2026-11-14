package kr.contest.pleosapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 화면 색 한 벌. 낮에는 흰 바탕([RM.Day]), 차가 야간 모드면 남색 바탕([RM.Night])을 쓴다.
 * 같은 이름의 색이 두 벌에서 같은 역할을 하도록 맞췄다 (예: [sky]는 온타임·선택을 뜻하고, 흰 바탕에서는 글자로 읽히게 진하게).
 */
@Immutable
class Palette(
    val dark: Boolean,
    // 바탕·면
    val background: Color,
    val backgroundTop: Color,
    val panel: Color,
    val card: Color,
    val cardHigh: Color,
    val button: Color,
    val border: Color,
    val line: Color,
    // 글자
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    // 뜻이 있는 색: 글자·테두리·선으로 쓰는 색과, 버튼을 채우는 색(…Fill)·그 위 글자(on…)
    val sky: Color,
    val skyDeep: Color,
    val onSky: Color,
    val sand: Color,
    val sandFill: Color,
    val sandDeep: Color,
    val onSand: Color,
    val amber: Color,
    val amberFill: Color,
    val amberDeep: Color,
    val onAmber: Color,
    /** 진한 색 위의 손잡이·글자 (스위치 등) */
    val onAccent: Color,
    val blue: Color,
    val red: Color,
    // 미니맵 (지도 타일을 못 받을 때의 바탕과 그 위 표시)
    val mapTop: Color,
    val map: Color,
    val mapGrid: Color,
    val mapLabel: Color,
    val trail: Color,
    /** 경로선 테두리 (선이 지도 위에서 떠 보이게) */
    val casing: Color,
    /** 음성 안내 자막 바탕 */
    val caption: Color,
)

/**
 * 현대자동차 디자인 시스템(KR 제품 화면)의 색과 형태를 차 안 화면에 맞춰 옮겼다.
 *
 * - 낮(기본): 현대 웹처럼 흰 면과 옅은 회색 바탕, 남색 글자. Pleos Connect 화면이 밝은 바탕이라 그에 맞춘다.
 * - 밤: 차가 야간 모드(시스템 다크 모드)로 바뀌면 현대 네이비를 어둡게 내린 바탕 (밤 운전에 눈부시지 않게).
 * - 하늘색: 온타임 모드·선택·정상 (흰 바탕에서는 액티브 블루 #007FA8로 진하게)
 * - 샌드: 롱웨이 모드·경치 (흰 바탕에서는 글자로 읽히게 갈색 쪽으로)
 * - 호박색: 레스트 모드·주의·기다리기. 안전 경고라 브랜드 색으로 바꾸지 않는다.
 * - 빨강: 연결 실패 같은 오류
 *
 * 형태: 버튼은 각지게(현대 웹의 0px 버튼·6px 페이저를 따라 작은 모서리), 그림자 없이 평평하게.
 * 글꼴: Hyundai Sans는 라이선스 받은 파일이 없어 시스템 글꼴을 쓰고, 버튼 굵기(500)와 좁은 자간만 맞춘다.
 */
object RM {
    // 현대 브랜드 색 (디자인 시스템 문서의 값 그대로, 낮·밤 같음)
    val hyundaiNavy = Color(0xFF002C5F)
    val activeBlue = Color(0xFF007FA8)
    val skyBlue = Color(0xFF00AAD2)

    /** 네이비를 조금 밝힌 색 (모드 카드·로고의 위쪽) */
    val navyLight = Color(0xFF003C7E)
    val sandBrand = Color(0xFFE4DCD3)

    /** 낮: 흰 바탕 */
    val Day = Palette(
        dark = false,
        background = Color(0xFFF1F3F6),
        backgroundTop = Color(0xFFFAFBFC),
        panel = Color(0xFFFFFFFF),
        card = Color(0xFFFFFFFF),
        cardHigh = Color(0xFFF3F7FA),
        button = Color(0xFFF4F6F9),
        border = Color(0xFFDFE4EA),
        line = Color(0xFFC8D0DA),
        text = Color(0xFF0D1B2E),
        textDim = Color(0xFF4F5E72),
        textFaint = Color(0xFF7F8C9D),
        sky = activeBlue,
        skyDeep = Color(0xFF00668A),
        onSky = Color(0xFFFFFFFF),
        sand = Color(0xFF8C6A43),
        // 롱웨이 모드 버튼(고른 칸·'코스 찾기')은 온타임의 하늘색처럼 진한 샌드에 흰 글자로 (옅은 베이지는 흰 바탕에서 꺼진 버튼처럼 보였다)
        sandFill = Color(0xFF8C6A43),
        sandDeep = Color(0xFF6F5232),
        onSand = Color(0xFFFFFFFF),
        amber = Color(0xFFB85C00),
        amberFill = Color(0xFFFFB547),
        amberDeep = Color(0xFF9A4D00),
        onAmber = Color(0xFF2A1A00),
        onAccent = Color(0xFFFFFFFF),
        blue = Color(0xFF2F5FD0),
        red = Color(0xFFD23C3C),
        mapTop = Color(0xFFEEF2F7),
        map = Color(0xFFE6ECF2),
        mapGrid = Color(0xFFD7DFE8),
        mapLabel = Color(0xF2FFFFFF),
        trail = Color(0xFF8592A3),
        casing = Color(0xE6FFFFFF),
        caption = Color(0xFFFFF6E5),
    )

    /** 밤: 남색 바탕 */
    val Night = Palette(
        dark = true,
        background = Color(0xFF020B17),
        backgroundTop = Color(0xFF051A33),
        panel = Color(0xFF061932),
        card = Color(0xFF0A2140),
        cardHigh = Color(0xFF0E2A50),
        button = Color(0xFF0D2647),
        border = Color(0xFF16345C),
        line = Color(0xFF214472),
        text = Color(0xFFFFFFFF),
        textDim = Color(0xFFA9B7C9),
        textFaint = Color(0xFF7C90AC),
        sky = skyBlue,
        skyDeep = activeBlue,
        onSky = Color(0xFF00213F),
        sand = sandBrand,
        sandFill = sandBrand,
        sandDeep = Color(0xFFBFB2A3),
        onSand = hyundaiNavy,
        amber = Color(0xFFFFB547),
        amberFill = Color(0xFFFFB547),
        amberDeep = Color(0xFFE08A12),
        onAmber = Color(0xFF2A1A00),
        onAccent = Color(0xFF00213F),
        blue = Color(0xFF7AA2FF),
        red = Color(0xFFFF6B6B),
        mapTop = Color(0xFF062243),
        map = Color(0xFF03142A),
        mapGrid = Color(0xFF0B2A4D),
        mapLabel = Color(0xE6031428),
        trail = Color(0xFF55708F),
        casing = Color(0x73000000),
        caption = Color(0xF2061A33),
    )

    /**
     * 지금 쓰는 색 한 벌. [RestMateTheme]이 화면을 그리기 전에 정한다.
     * 낮·밤이 바뀌면 [RestMateTheme]이 화면 전체를 새로 그려 모든 색이 함께 바뀐다.
     */
    @Volatile
    var palette: Palette = Day
        internal set

    val dark: Boolean get() = palette.dark
    val background: Color get() = palette.background
    val backgroundTop: Color get() = palette.backgroundTop
    val panel: Color get() = palette.panel
    val card: Color get() = palette.card
    val cardHigh: Color get() = palette.cardHigh
    val button: Color get() = palette.button
    val border: Color get() = palette.border
    val line: Color get() = palette.line
    val text: Color get() = palette.text
    val textDim: Color get() = palette.textDim
    val textFaint: Color get() = palette.textFaint
    val sky: Color get() = palette.sky
    val skyDeep: Color get() = palette.skyDeep
    val onSky: Color get() = palette.onSky
    val sand: Color get() = palette.sand
    val sandFill: Color get() = palette.sandFill
    val sandDeep: Color get() = palette.sandDeep
    val onSand: Color get() = palette.onSand
    val amber: Color get() = palette.amber
    val amberFill: Color get() = palette.amberFill
    val amberDeep: Color get() = palette.amberDeep
    val onAmber: Color get() = palette.onAmber
    val onAccent: Color get() = palette.onAccent
    val blue: Color get() = palette.blue
    val red: Color get() = palette.red
    val mapTop: Color get() = palette.mapTop
    val map: Color get() = palette.map
    val mapGrid: Color get() = palette.mapGrid
    val mapLabel: Color get() = palette.mapLabel
    val trail: Color get() = palette.trail
    val casing: Color get() = palette.casing
    val caption: Color get() = palette.caption
}

/** 버튼: 현대 웹의 각진 버튼을 따라 작은 모서리 */
internal val ButtonShape = RoundedCornerShape(6.dp)

/** 카드·패널 */
internal val CardShape = RoundedCornerShape(10.dp)

/** 상태 칩 (현대 웹 페이저의 6px) */
internal val ChipShape = RoundedCornerShape(6.dp)

/** 시각·숫자: 자리마다 폭이 같은 숫자로 (시계처럼 흔들리지 않게) */
val NumStyle = TextStyle(fontFeatureSettings = "tnum")

/**
 * @param dark 밤 화면인지. 기본은 시스템 설정을 따른다 (차가 야간 모드면 시스템이 다크 모드가 된다).
 */
@Composable
fun RestMateTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (dark) RM.Night else RM.Day
    RM.palette = palette
    // 낮·밤이 바뀌면 화면 전체를 새로 그린다 (색을 미리 읽어 둔 곳까지 빠짐없이 바뀌게)
    key(dark) {
        val scheme = if (dark) {
            darkColorScheme(
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
            )
        } else {
            lightColorScheme(
                primary = RM.sky,
                onPrimary = RM.onSky,
                secondary = RM.amber,
                onSecondary = RM.onAccent,
                tertiary = RM.sand,
                onTertiary = RM.onAccent,
                background = RM.background,
                onBackground = RM.text,
                surface = RM.panel,
                onSurface = RM.text,
                surfaceVariant = RM.button,
                outline = RM.line,
                error = RM.red,
            )
        }
        MaterialTheme(colorScheme = scheme) {
            Surface(modifier = Modifier.fillMaxSize(), color = RM.background, contentColor = RM.text) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(RM.backgroundTop, RM.background))),
                ) {
                    // 기본 글자: 현대 제품 화면처럼 자간을 조금 좁게. 줄은 어절(띄어쓰기)에서 바꾼다
                    val base = TextStyle(
                        color = RM.text,
                        letterSpacing = (-0.3).sp,
                        lineBreak = KOREAN_LINE_BREAK,
                        localeList = KOREAN,
                    )
                    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.merge(base)) {
                        content()
                    }
                }
            }
        }
    }
}

/**
 * 한국어 줄바꿈: 글자마다가 아니라 어절(띄어쓰기)에서 바꾼다 ('있어요'가 '있 / 어요'로, '레스트'가 '레스 / 트'로 갈리지 않게).
 * 안드로이드 13 이상에서 쓰이고 그 아래는 기본 줄바꿈이다. 글자 언어를 한국어로 두어야 어절 단위로 바뀐다 (기기 언어가 영어여도).
 */
internal val KOREAN_LINE_BREAK = LineBreak(
    strategy = LineBreak.Strategy.HighQuality,
    strictness = LineBreak.Strictness.Strict,
    wordBreak = LineBreak.WordBreak.Phrase,
)

private val KOREAN = LocaleList("ko-KR")
