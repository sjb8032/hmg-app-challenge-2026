package kr.contest.pleosapp.ui

import android.util.DisplayMetrics
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Pleos 아래 바(공조·독)가 앱 창 아래를 덮고 있는 높이.
 *
 * 보통은 앱 창이 아래 바 바로 위에서 끝나 0이다. 그런데 Pleos 에뮬레이터를 다시 켠 뒤 앱 창이 화면 맨 아래까지
 * 잡히고 아래 바가 그 위를 덮는 때가 있다. 아래 바는 창 여백(인셋)을 알려 주지 않아 시스템이 비워 주지 않으므로,
 * 창이 아래 바 자리에 걸친 만큼을 직접 재서 비운다. 그러지 않으면 화면 아래 '레스트 모드' 버튼이 바 밑에 깔린다.
 */
@Composable
internal fun rememberBottomBarOverlap(): Dp {
    // 미리보기·화면 캡처 도구에는 시스템 바가 없다
    if (LocalInspectionMode.current) return 0.dp
    val view = LocalView.current
    var overlapPx by remember { mutableIntStateOf(0) }
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener { overlapPx = bottomBarOverlapPx(view) }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        overlapPx = bottomBarOverlapPx(view)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return with(LocalDensity.current) { overlapPx.toDp() }
}

/** Pleos Connect 화면(2560×1440)에서 아래 바 높이는 140px. 다른 해상도에서는 화면 높이에 비례해 잡는다. */
private const val BOTTOM_BAR_RATIO = 140f / 1440f

private fun bottomBarOverlapPx(view: View): Int {
    val display = view.display ?: return 0
    val real = DisplayMetrics()
    @Suppress("DEPRECATION")
    display.getRealMetrics(real)
    val screenHeight = real.heightPixels
    if (screenHeight <= 0 || view.height <= 0) return 0
    val barTop = screenHeight - (screenHeight * BOTTOM_BAR_RATIO).toInt()
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    val windowBottom = location[1] + view.height
    return (windowBottom - barTop).coerceIn(0, screenHeight - barTop)
}
