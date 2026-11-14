package kr.contest.pleosapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapCameraTest {
    private val nonhyeon = LatLng(37.5110, 127.0214)
    private val gwanghwamun = LatLng(37.5717, 126.9768)

    @Test
    fun overviewCameraMatchesTheFrame() {
        val frame = MapFrame.fit(listOf(nonhyeon, gwanghwamun), aspect = 1.5f)!!
        val camera = frame.camera(900f, 600f)
        for (p in listOf(nonhyeon, gwanghwamun)) {
            val m = frame.project(p)
            val s = camera.screen(p)
            assertEquals(m.x * 900f, s.x, 0.5f)
            assertEquals(m.y * 600f, s.y, 0.5f)
        }
        assertEquals(frame.widthMeters / 900.0, camera.metersPerPx, frame.widthMeters / 900.0 * 0.01)
    }

    @Test
    fun followPutsTheCarAtTheAnchorAndTheHeadingUp() {
        for (heading in listOf(0.0, 45.0, 135.0, 290.0)) {
            val camera = MapCamera.follow(nonhyeon, heading, spanMeters = 2_000.0, width = 800f, height = 600f)
            val car = camera.screen(nonhyeon)
            assertEquals(400f, car.x, 0.5f)
            assertEquals(420f, car.y, 0.5f)
            // 진행 방향으로 500m 앞은 차 바로 위
            val ahead = camera.screen(Geo.destinationPoint(nonhyeon, heading, 500.0))
            assertEquals("heading=$heading", 400f, ahead.x, 1.5f)
            assertEquals(420f - 200f, ahead.y, 2f)
            // 오른쪽 90°는 차의 오른쪽
            val right = camera.screen(Geo.destinationPoint(nonhyeon, heading + 90.0, 500.0))
            assertEquals(400f + 200f, right.x, 2f)
            assertEquals(420f, right.y, 1.5f)
        }
    }

    @Test
    fun tilesCoverTheRotatedScreen() {
        val camera = MapCamera.follow(nonhyeon, heading = 290.0, spanMeters = 3_000.0, width = 900f, height = 600f)
        val layout = camera.tiles(density = 1f)!!
        // 화면 네 모서리를 돌리기 전 좌표로 되돌린 점이 모두 어떤 타일 안에 든다
        val r = Math.toRadians(camera.rotation)
        for ((sx, sy) in listOf(0f to 0f, 900f to 0f, 0f to 600f, 900f to 600f)) {
            val dx = sx - camera.anchorX
            val dy = sy - camera.anchorY
            val ux = camera.anchorX + dx * Math.cos(r) - dy * Math.sin(r)
            val uy = camera.anchorY + dx * Math.sin(r) + dy * Math.cos(r)
            assertTrue(layout.tiles.any { ux >= it.left && ux <= it.right && uy >= it.top && uy <= it.bottom })
        }
        // 타일 한 장이 256dp 안팎
        val t = layout.tiles.first()
        assertTrue((t.right - t.left) in 180f..364f)
    }
}
