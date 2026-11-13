package kr.contest.pleosapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapFrameTest {
    private val nonhyeon = LatLng(37.5110, 127.0214)
    private val gwanghwamun = LatLng(37.5717, 126.9768)

    @Test
    fun noPointsMeansNoFrame() {
        assertNull(MapFrame.fit(emptyList(), aspect = 1.5f))
    }

    @Test
    fun allPointsFallInsideWithPadding() {
        val frame = MapFrame.fit(listOf(nonhyeon, gwanghwamun), aspect = 1.6f, padding = 0.1f)!!
        for (p in listOf(nonhyeon, gwanghwamun)) {
            val m = frame.project(p)
            assertTrue("x=${m.x}", m.x in 0.05f..0.95f)
            assertTrue("y=${m.y}", m.y in 0.05f..0.95f)
        }
    }

    @Test
    fun northIsUpAndEastIsRight() {
        val frame = MapFrame.fit(listOf(nonhyeon, gwanghwamun), aspect = 1f)!!
        val south = frame.project(nonhyeon)
        val north = frame.project(gwanghwamun)
        // 광화문이 논현역보다 북서쪽
        assertTrue(north.y < south.y)
        assertTrue(north.x < south.x)
    }

    @Test
    fun keepsAspectRatioSoDistancesLookTheSameBothWays() {
        val frame = MapFrame.fit(listOf(nonhyeon, gwanghwamun), aspect = 2f)!!
        assertEquals(2.0, frame.widthMeters / frame.heightMeters, 1e-9)
    }

    @Test
    fun worldCoordinatesMatchOsmTileNumbers() {
        // 서울시청은 줌 12에서 OSM 타일 3492/1586 안에 있다
        val cityHall = LatLng(37.5665, 126.9780)
        assertEquals(3492, (MapFrame.worldX(cityHall.lon) * 4096).toInt())
        assertEquals(1586, (MapFrame.worldY(cityHall.lat) * 4096).toInt())
        assertEquals(37.5665, MapFrame.latOf(MapFrame.worldY(37.5665)), 1e-9)
    }

    @Test
    fun tilesCoverTheWholeFrameAtAboutTileSize() {
        val frame = MapFrame.fit(listOf(nonhyeon, gwanghwamun), aspect = 1.4f)!!
        val widthDp = 840f
        val cover = frame.tiles(widthDp)!!
        // 타일 한 장이 256dp의 0.7~1.42배로 그려진다
        val tileDp = (cover.tiles[0].right - cover.tiles[0].left) * widthDp
        assertTrue("tile=$tileDp", tileDp in 180f..364f)
        assertTrue(cover.tiles.minOf { it.left } <= 0f && cover.tiles.maxOf { it.right } >= 1f)
        assertTrue(cover.tiles.minOf { it.top } <= 0f && cover.tiles.maxOf { it.bottom } >= 1f)
        // 지점은 그 지점이 든 타일 안에 그려진다
        val n = 1 shl cover.zoom
        for (p in listOf(nonhyeon, gwanghwamun)) {
            val tile = cover.tiles.single {
                it.x == (MapFrame.worldX(p.lon) * n).toInt() && it.y == (MapFrame.worldY(p.lat) * n).toInt()
            }
            val m = frame.project(p)
            assertTrue(m.x in tile.left..tile.right && m.y in tile.top..tile.bottom)
        }
        // 이웃 타일은 틈 없이 붙는다
        val first = cover.tiles.first()
        val right = cover.tiles.first { it.x == first.x + 1 && it.y == first.y }
        assertEquals(first.right, right.left, 1e-6f)
    }

    @Test
    fun tooManyTilesLowersTheZoom() {
        val frame = MapFrame.fit(listOf(nonhyeon, gwanghwamun), aspect = 1.4f)!!
        val cover = frame.tiles(4_000f, maxTiles = 12)!!
        assertTrue(cover.tiles.size <= 12)
        assertTrue(cover.zoom < frame.tiles(4_000f)!!.zoom)
    }

    @Test
    fun singlePointGetsMinimumSpanAndSitsInTheMiddle() {
        val frame = MapFrame.fit(listOf(nonhyeon), aspect = 1.5f, padding = 0f, minSpanMeters = 1_500.0)!!
        assertEquals(1_500.0, frame.widthMeters, 1e-6)
        val m = frame.project(nonhyeon)
        assertEquals(0.5f, m.x, 1e-4f)
        assertEquals(0.5f, m.y, 1e-4f)
    }
}
