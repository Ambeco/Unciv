package com.unciv.ui.components.recyclerview.widget

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.utils.viewport.FitViewport
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

/** Covers [RecyclerView]'s fling (real Android RecyclerView API parity - a fast drag release keeps
 *  scrolling with decaying velocity) and edge auto-scroll (an opt-in, non-Android feature - see
 *  [RecyclerView.isAutoScrollEnabled]'s KDoc). */
@RunWith(GdxTestRunner::class)
class FlingAndAutoScrollTest {

    private lateinit var stage: Stage

    private fun makeRecycler(count: Int = 100_000, itemSize: Float = 10f, height: Float = 50f):
        Pair<RecyclerView, LinearLayoutManager> {
        val rv = RecyclerView()
        rv.setSize(50f, height)
        val lm = LinearLayoutManager().apply { bufferItemCount = 0 }
        rv.layoutManager = lm
        rv.setAdapter(RecyclerViewTestSupport.StringAdapter((0 until count).map { "item$it" }, itemSize = itemSize))
        stage = Stage(FitViewport(200f, 200f), mock(Batch::class.java))
        stage.viewport.update(200, 200, true) // real screen pixel size, needed by edge auto-scroll
        stage.addActor(rv)
        rv.layout()
        return rv to lm
    }

    private fun pump(rv: RecyclerView, frames: Int = 60) {
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

    // region fling

    @Test
    fun `a fast drag release starts a fling that keeps scrolling after touchUp`() {
        val (rv, lm) = makeRecycler()
        var fakeTime = 0L
        rv.currentTimeMillis = { fakeTime }

        fire(rv, InputEvent.Type.touchDown, 25f, 40f)
        fakeTime += 16 // one frame later
        fire(rv, InputEvent.Type.touchDragged, 25f, 10f) // fast finger-up drag => fast forward scroll
        fire(rv, InputEvent.Type.touchUp, 25f, 10f)

        val positionRightAfterRelease = lm.findFirstVisibleItemPosition()
        pump(rv, frames = 30)
        assertTrue("fling should have advanced further than where the drag left off",
            lm.findFirstVisibleItemPosition() > positionRightAfterRelease)
    }

    @Test
    fun `a slow drag release does not start a fling`() {
        val (rv, lm) = makeRecycler()
        var fakeTime = 0L
        rv.currentTimeMillis = { fakeTime }

        fire(rv, InputEvent.Type.touchDown, 25f, 40f)
        fakeTime += 1000 // a full second for a 1px movement - well under MIN_FLING_VELOCITY
        fire(rv, InputEvent.Type.touchDragged, 25f, 39f)
        fire(rv, InputEvent.Type.touchUp, 25f, 39f)

        val positionAfterRelease = lm.findFirstVisibleItemPosition()
        pump(rv, frames = 30)
        assertEquals(positionAfterRelease, lm.findFirstVisibleItemPosition())
    }

    @Test
    fun `a fling eventually decelerates to a stop`() {
        val (rv, lm) = makeRecycler()
        var fakeTime = 0L
        rv.currentTimeMillis = { fakeTime }

        fire(rv, InputEvent.Type.touchDown, 25f, 40f)
        fakeTime += 16
        fire(rv, InputEvent.Type.touchDragged, 25f, 10f)
        fire(rv, InputEvent.Type.touchUp, 25f, 10f)

        pump(rv, frames = 600) // 10 seconds - the fling should be long since decayed to zero
        val positionAfterLongCoast = lm.findFirstVisibleItemPosition()
        pump(rv, frames = 60)
        assertEquals("fling should have already stopped, no further movement",
            positionAfterLongCoast, lm.findFirstVisibleItemPosition())
    }

    @Test
    fun `a fling toward the start of the list stops at position 0 instead of overshooting`() {
        val (rv, lm) = makeRecycler()
        var fakeTime = 0L
        rv.currentTimeMillis = { fakeTime }

        // Already at position 0; drag as if trying to scroll further backward.
        fire(rv, InputEvent.Type.touchDown, 25f, 10f)
        fakeTime += 16
        fire(rv, InputEvent.Type.touchDragged, 25f, 40f)
        fire(rv, InputEvent.Type.touchUp, 25f, 40f)

        pump(rv, frames = 60)
        assertEquals(0, lm.findFirstVisibleItemPosition())
    }

    @Test
    fun `a new touch stops an in-flight fling`() {
        val (rv, lm) = makeRecycler()
        var fakeTime = 0L
        rv.currentTimeMillis = { fakeTime }

        fire(rv, InputEvent.Type.touchDown, 25f, 40f)
        fakeTime += 16
        fire(rv, InputEvent.Type.touchDragged, 25f, 10f)
        fire(rv, InputEvent.Type.touchUp, 25f, 10f)
        rv.act(1f / 60f); rv.layout() // let a little fling actually happen first

        fire(rv, InputEvent.Type.touchDown, 25f, 20f) // grab it again, mid-fling
        val positionWhenGrabbed = lm.findFirstVisibleItemPosition()
        pump(rv, frames = 30) // no further drag/release - if the fling weren't cancelled, this would move a lot
        assertEquals(positionWhenGrabbed, lm.findFirstVisibleItemPosition())
    }

    // endregion
    // region edge auto-scroll

    /** Swaps [Gdx.input] for a mock reporting a fixed pointer position/touch state, running [block],
     *  then restores the original - [RecyclerView.act] reads [Gdx.input] directly for auto-scroll. */
    private fun withFakeInput(x: Int, y: Int, isTouched: Boolean, block: () -> Unit) {
        val fakeInput = mock(Input::class.java)
        `when`(fakeInput.x).thenReturn(x)
        `when`(fakeInput.y).thenReturn(y)
        `when`(fakeInput.isTouched).thenReturn(isTouched)
        val original = Gdx.input
        Gdx.input = fakeInput
        try {
            block()
        } finally {
            Gdx.input = original
        }
    }

    @Test
    fun `edge auto-scroll pans while the pointer rests near a stage edge`() {
        val (rv, lm) = makeRecycler()
        rv.scrollToPosition(500)
        rv.layout()
        rv.isAutoScrollEnabled = true
        val positionBefore = lm.findFirstVisibleItemPosition()

        withFakeInput(x = 100, y = 1, isTouched = false) { pump(rv, frames = 30) }

        assertNotEquals(positionBefore, lm.findFirstVisibleItemPosition())
    }

    @Test
    fun `edge auto-scroll does nothing when disabled`() {
        val (rv, lm) = makeRecycler()
        rv.scrollToPosition(500)
        rv.layout()
        // isAutoScrollEnabled left at its default (false).
        val positionBefore = lm.findFirstVisibleItemPosition()

        withFakeInput(x = 100, y = 1, isTouched = false) { pump(rv, frames = 30) }

        assertEquals(positionBefore, lm.findFirstVisibleItemPosition())
    }

    @Test
    fun `edge auto-scroll does nothing while the pointer is actively touching`() {
        val (rv, lm) = makeRecycler()
        rv.scrollToPosition(500)
        rv.layout()
        rv.isAutoScrollEnabled = true
        val positionBefore = lm.findFirstVisibleItemPosition()

        withFakeInput(x = 100, y = 1, isTouched = true) { pump(rv, frames = 30) }

        assertEquals(positionBefore, lm.findFirstVisibleItemPosition())
    }

    @Test
    fun `edge auto-scroll does nothing when the pointer is away from every edge`() {
        val (rv, lm) = makeRecycler()
        rv.scrollToPosition(500)
        rv.layout()
        rv.isAutoScrollEnabled = true
        val positionBefore = lm.findFirstVisibleItemPosition()

        withFakeInput(x = 100, y = 100, isTouched = false) { pump(rv, frames = 30) }

        assertEquals(positionBefore, lm.findFirstVisibleItemPosition())
    }

    // endregion
}
