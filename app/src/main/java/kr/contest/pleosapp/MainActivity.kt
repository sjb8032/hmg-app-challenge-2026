package kr.contest.pleosapp

import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 개발 환경 점검 화면.
 *
 * Pleos Connect 에뮬레이터에서 Vehicle SDK와 NaviHelper SDK가 연결되는지,
 * 차량 값(도어 위치)을 실제로 읽을 수 있는지 확인한다.
 * 아이디어가 정해지면 이 화면을 실제 앱 화면으로 교체한다.
 */
class MainActivity : ComponentActivity() {

    private lateinit var vehicle: Vehicle
    private lateinit var naviHelper: NaviHelper
    private var vehicleReady = false

    private var vehicleStatus by mutableStateOf("연결 중…")
    private var naviStatus by mutableStateOf("연결 중…")
    private var doorStatus by mutableStateOf("아직 읽지 않음")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectVehicle()
        connectNavi()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EnvironmentCheckScreen(
                        vehicleStatus = vehicleStatus,
                        naviStatus = naviStatus,
                        doorStatus = doorStatus,
                        onReadDoor = ::readFirstDoorPosition,
                    )
                }
            }
        }
    }

    private fun connectVehicle() {
        vehicle = Vehicle(applicationContext)
        vehicleStatus = try {
            vehicle.initialize()
            vehicleReady = true
            "연결됨"
        } catch (e: Exception) {
            "실패: ${e.message}"
        }
    }

    private fun connectNavi() {
        naviHelper = NaviHelper(applicationContext)
        naviHelper.initialize(
            onConnected = { runOnUiThread { naviStatus = "연결됨" } },
            onFailed = { error -> runOnUiThread { naviStatus = "실패: ${error.message}" } },
        )
    }

    private fun readFirstDoorPosition() {
        if (!vehicleReady) {
            doorStatus = "Vehicle SDK가 연결되지 않았습니다"
            return
        }
        val area = DoorValue.entries.first()
        doorStatus = "${area.name} 읽는 중…"
        try {
            vehicle.getDoor().getCurrentDoorPosition(
                area = DoorArea(area),
                onComplete = { position -> runOnUiThread { doorStatus = "${area.name}: $position" } },
                onFailed = { error -> runOnUiThread { doorStatus = "${area.name} 실패: ${error.message}" } },
            )
        } catch (e: Exception) {
            doorStatus = "${area.name} 실패: ${e.message}"
        }
    }

    override fun onDestroy() {
        if (vehicleReady) vehicle.release()
        naviHelper.release()
        super.onDestroy()
    }
}

@Composable
private fun EnvironmentCheckScreen(
    vehicleStatus: String,
    naviStatus: String,
    doorStatus: String,
    onReadDoor: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text("개발 환경 점검", style = MaterialTheme.typography.headlineLarge)
        StatusItem(label = "Vehicle SDK", value = vehicleStatus)
        StatusItem(label = "NaviHelper SDK", value = naviStatus)
        StatusItem(label = "도어 위치 (첫 번째 영역)", value = doorStatus)
        Button(onClick = onReadDoor) {
            Text("도어 위치 읽기")
        }
        Text(
            "Virtual Console에서 도어 값을 바꾼 뒤 다시 읽어 보세요.",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun StatusItem(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
