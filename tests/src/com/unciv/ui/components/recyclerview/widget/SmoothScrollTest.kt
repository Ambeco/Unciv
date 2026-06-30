package com.unciv.ui.components.recyclerview.widget

import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.utils.viewport.FitViewport
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import kotlin.math.abs

/** Covers feature 2: animated [RecyclerView.smoothScrollToPosition] physics and touch-drag
 * overscroll (rubber-band past the content edges, settling back on release). */
@RunWith(GdxTestRunner::class)
class SmoothScrollTest {

    // A headless Stage (mock Batch, no GL) so real touch events can be dispatched to the listener,
    // which needs a non-null stage to grab touch focus on touchDown.
    private lateinit var stage: Stage

    private fun makeRecycler(count: Int = 100, itemSize: Float = 10f, height: Float = 50f):
        Pair<RecyclerView, LinearLayoutManager> {
        val rv = RecyclerView()
        rv.setSize(50f, height)
        val lm = LinearLayoutManager().apply { bufferItemCount = 0 }
        rv.layoutManager = lm
        rv.setAdapter(RecyclerViewTestSupport.StringAdapter((0 until count).map { "item$it" }, itemSize = itemSize))
        stage = Stage(FitViewport(200f, 200f), mock(Batch::class.java))
        stage.addActor(rv)
        rv.layout()
        return rv to lm
    }

    /** Pumps a fixed number of ~60fps frames, laying out after each so visible positions update. */
    private fun pump(rv: RecyclerView, frames: Int = 300) {
        repeat(frames) { rv.act(1f / 60f); rv.layout() }
    }

    private fun fire(rv: RecyclerView, type: InputEvent.Type, x: Float, y: Float) {
        val event = InputEvent()
        event.type = type
        event.stage = stage
        event.stageX = x
        event.stageY = y
        event.pointer = 0
        event.button = 0
        rv.fire(event)
    }

    @Test
    fun `smoothScrollToPosition eventually reaches a forward target`() {
        val (rv, lm) = makeRecycler()
        rv.smoothScrollToPosition(40)
        pump(rv)
        assertEquals(40, lm.findFirstVisibleItemPosition())
    }

    @Test
    fun `smoothScrollToPosition eventually reaches a backward target`() {
        val (rv, lm) = makeRecycler()
        rv.scrollToPosition(60)
        rv.layout()
        rv.smoothScrollToPosition(10)
        pump(rv)
        assertEquals(10, lm.findFirstVisibleItemPosition())
    }

    @Test
    fun `smoothScrollToPosition does not jump instantly`() {
        val (rv, lm) = makeRecycler()
        rv.smoothScrollToPosition(40)
        rv.act(1f / 60f)
        rv.layout()
        // After a single frame it has moved at most a little, nowhere near the far target.
        assertTrue(lm.findFirstVisibleItemPosition() < 40)
    }

    @Test
    fun `a new touch cancels an in-flight smooth scroll`() {
        val (rv, lm) = makeRecycler()
        rv.smoothScrollToPosition(80)
        rv.act(1f / 60f); rv.layout()
        val positionWhenTouched = lm.findFirstVisibleItemPosition()
        fire(rv, InputEvent.Type.touchDown, 25f, 25f)
        fire(rv, InputEvent.Type.touchUp, 25f, 25f)
        pump(rv, frames = 60)
        // The smooth scroll was aborted, so it never reached 80.
        assertTrue(lm.findFirstVisibleItemPosition() < 80)
        assertTrue(lm.findFirstVisibleItemPosition() >= positionWhenTouched)
    }

    @Test
    fun `dragging past the top edge produces overscroll that settles back to zero`() {
        val (rv, _) = makeRecycler()
        // At the top already; a downward-in-content drag (finger toward larger y) can't scroll,
        // so it rubber-bands into overscroll.
        fire(rv, InputEvent.Type.touchDown, 25f, 10f)
        fire(rv, InputEvent.Type.touchDragged, 25f, 40f)
        assertTrue("expected non-zero overscroll after over-drag", abs(rv.overscrollDistance) > 0f)

        fire(rv, InputEvent.Type.touchUp, 25f, 40f)
        pump(rv, frames = 120)
        assertEquals("overscroll should settle back to zero after release", 0f, rv.overscrollDistance, 0.001f)
    }

    @Test
    fun `dragging within the content scrolls normally without overscroll`() {
        val (rv, lm) = makeRecycler()
        fire(rv, InputEvent.Type.touchDown, 25f, 40f)
        fire(rv, InputEvent.Type.touchDragged, 25f, 10f) // finger up => content scrolls down
        rv.layout()
        assertTrue(lm.findFirstVisibleItemPosition() > 0)
        assertEquals(0f, rv.overscrollDistance, 0.001f)
    }
}
