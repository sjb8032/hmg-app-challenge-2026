package kr.contest.pleosapp.ui

import kr.contest.pleosapp.core.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 미니맵 장면과 시연 안내가 화면 표본(PreviewSamples) 상태에 맞게 나오는지 */
class MapSceneTest {
    @Test
    fun candidatesAreNumberedLikeTheCourseCards() {
        val ui = PreviewSamples.choose()
        val scene = mapSceneFor(ui)
        val badges = scene.marks.filter { it.kind == MarkKind.CANDIDATE }.map { it.badge }
        // 코스 카드 번호(옵션 순서)와 같은 번호
        assertEquals(listOf("1", "2", "3"), badges)
        val first = scene.marks.first { it.badge == "1" }
        assertEquals(ui.drive.options[0].via.first().position, first.position)
        assertEquals(candidateColor(0), first.color)
    }

    @Test
    fun activeRouteGoesThroughRemainingViaToDestination() {
        val ui = PreviewSamples.active()
        val scene = mapSceneFor(ui)
        val route = scene.lines.single { !it.dashed }
        assertEquals(ui.core.position, route.points.first())
        assertEquals(ui.drive.destination?.position, route.points.last())
        assertTrue(scene.marks.any { it.kind == MarkKind.SCENIC && it.label == "낙산공원" })
        assertNotNull(scene.car)
    }

    @Test
    fun arrivedShowsVisitedViaAndHidesTheCar() {
        val scene = mapSceneFor(PreviewSamples.arrived())
        assertNull(scene.car)
        assertTrue(scene.marks.any { it.kind == MarkKind.SCENIC })
        assertTrue(scene.marks.any { it.kind == MarkKind.DESTINATION })
    }

    @Test
    fun restGuidanceFocusesOnCarAndRestArea() {
        val ui = PreviewSamples.guidance()
        val scene = mapSceneFor(ui)
        val focus = scene.focus
        assertNotNull(focus)
        val area = ui.core.target!!.area.position
        assertTrue(focus!!.any { Geo.distanceMeters(it, area) < 1.0 })
        // 먼 목적지(강릉 숙소)는 화면에 담지 않는다
        val dest = ui.route!!.destination.position
        assertFalse(focus.any { Geo.distanceMeters(it, dest) < 1.0 })
    }

    @Test
    fun demoHintFollowsTheScenario() {
        assertTrue(demoHint(PreviewSamples.home())!!.contains("모드를 골라"))
        assertTrue(demoHint(PreviewSamples.choose())!!.contains("코스를 고르면"))
        assertTrue(demoHint(PreviewSamples.active())!!.contains("막힘"))
        assertTrue(demoHint(PreviewSamples.watchingLate())!!.contains("지켜보는 중"))
        assertNull(demoHint(PreviewSamples.home().copy(demoMode = false)))
    }

    @Test
    fun captionShowsOnlyForAWhileAfterSpeaking() {
        val spoken = PreviewSamples.active().copy(lastMessageAt = 100_000L)
        assertTrue(captionVisible(spoken.copy(realNow = 100_000L + 5_000L)))
        assertFalse(captionVisible(spoken.copy(realNow = 100_000L + CAPTION_MS + 1L)))
        assertFalse(captionVisible(spoken.copy(lastMessage = null, realNow = 100_000L)))
    }
}
