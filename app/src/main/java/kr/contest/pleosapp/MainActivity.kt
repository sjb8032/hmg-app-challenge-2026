package kr.contest.pleosapp

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kr.contest.pleosapp.app.RestMateController
import kr.contest.pleosapp.ui.RestMateScreen
import kr.contest.pleosapp.ui.RestMateTheme

/**
 * 드라이브메이트 화면. 차량·내비 신호와 상태 판단은 [RestMateController]가 맡는다.
 *
 * 화면 회전·야간 모드 같은 설정 변경으로 액티비티가 다시 만들어지지 않도록
 * AndroidManifest의 configChanges에 등록해 두었다 (휴식 타이머·도착 시각 맞추기를 잃지 않게).
 */
class MainActivity : ComponentActivity() {

    private lateinit var controller: RestMateController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        controller = RestMateController(this)
        controller.start()

        setContent {
            RestMateTheme {
                val ui by controller.ui.collectAsState()
                RestMateScreen(ui, controller)
            }
        }
    }

    override fun onDestroy() {
        controller.stop()
        super.onDestroy()
    }
}
