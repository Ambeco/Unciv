package com.unciv.ui.components.recyclerview.widget

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.HeadlessApplication
import com.badlogic.gdx.scenes.scene2d.ui.Widget
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Covers the async, thread-safe notify path added to [RecyclerView.Adapter] and the opt-in
 * background ViewHolder create/bind (see [RecyclerView.Adapter.notifyDataSetChangedAsync] and
 * [RecyclerView.Adapter.supportsBackgroundBinding]).
 *
 * ### Test harness note
 * These tests run on libGDX's headless "render" thread: [GdxTestRunner] runs each test method from
 * inside the [HeadlessApplication]'s `render()` callback (see that class), so the test body IS the
 * render thread. `Gdx.app.postRunnable` only enqueues; runnables are drained by the headless app's
 * `executeRunnables()` between frames - which never happens while our test body is blocking that same
 * loop. So we pump manually: [pumpUntil] repeatedly calls the (public) `executeRunnables()` until a
 * condition holds. The async apply steps route through [GdxMainDispatcher] (`postRunnable`), so they
 * only take effect once pumped, which is exactly what lets us assert the apply happened on the render
 * thread.
 */
@RunWith(GdxTestRunner::class)
class AsyncNotifyTest {

    /** Drains posted runnables on this (render) thread until [condition] holds or we time out. */
    private fun pumpUntil(timeoutMs: Long = 5000L, condition: () -> Boolean) {
        val app = Gdx.app as HeadlessApplication
        val start = System.currentTimeMillis()
        while (!condition()) {
            app.executeRunnables()
            if (condition()) break
            if (System.currentTimeMillis() - start > timeoutMs)
                throw AssertionError("timed out after ${timeoutMs}ms waiting for async apply")
            Thread.sleep(2)
        }
        app.executeRunnables()
    }

    private class SimpleViewHolder(private val widget: Widget) : RecyclerView.ViewHolder() {
        override fun getItemViews(): List<Widget> = listOf(widget)
    }

    /**
     * Adapter over an [AtomicReference] list. Records, per create/bind call, the id of the thread it
     * ran on, so tests can prove whether view work happened on the render thread or off it.
     * [background] flips [supportsBackgroundBinding].
     */
    private class ThreadRecordingAdapter(
        initial: List<String>,
        private val background: Boolean
    ) : RecyclerView.Adapter<SimpleViewHolder>() {
        val list = AtomicReference(initial)
        val bindThreadIds: MutableSet<Long> = Collections.synchronizedSet(HashSet())
        val createThreadIds: MutableSet<Long> = Collections.synchronizedSet(HashSet())

        override val supportsBackgroundBinding: Boolean get() = background

        fun arm() { bindThreadIds.clear(); createThreadIds.clear() }

        override fun onCreateViewHolder(parent: RecyclerView, viewType: Int): SimpleViewHolder {
            createThreadIds.add(Thread.currentThread().id)
            // A real-sized SizedActor, not a bare zero-pref Widget - matches makeRv's 10f itemSize
            // convention (LinearLayoutManager has no itemSize fallback of its own).
            return SimpleViewHolder(RecyclerViewTestSupport.SizedActor(10f))
        }
        override fun onBindViewHolder(holder: SimpleViewHolder, position: Int) {
            bindThreadIds.add(Thread.currentThread().id)
        }
        override fun getItemCount(): Int = list.get().size
    }

    private fun makeRv(adapter: RecyclerView.Adapter<*>, height: Float = 50f): RecyclerView {
        val rv = RecyclerView()
        rv.setSize(50f, height)
        rv.layoutManager = LinearLayoutManager().apply { bufferItemCount = 0 }
        rv.setAdapter(adapter)
        rv.layout()
        return rv
    }

    @Test
    fun `async notify from a background thread applies on the render thread via postRunnable`() {
        val adapter = ThreadRecordingAdapter((0 until 5).map { "item$it" }, background = false)
        makeRv(adapter)
        val renderThreadId = Thread.currentThread().id

        val applyThreadId = AtomicReference<Long>(null)
        val applied = AtomicInteger(0)
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                applyThreadId.set(Thread.currentThread().id)
                applied.incrementAndGet()
            }
        })

        // Fire from a genuine background thread to prove any-thread safety.
        Thread { adapter.notifyDataSetChangedAsync { adapter.list.set((0 until 5).map { "changed$it" }) } }.start()

        pumpUntil { applied.get() > 0 }
        assertEquals("apply step must run on the render thread", renderThreadId, applyThreadId.get())
        assertEquals(listOf("changed0", "changed1", "changed2", "changed3", "changed4"), adapter.list.get())
    }

    @Test
    fun `opt-in background binding creates and binds off the render thread, attaches on the render thread`() {
        val adapter = ThreadRecordingAdapter((0 until 5).map { "item$it" }, background = true)
        val rv = makeRv(adapter)
        val renderThreadId = Thread.currentThread().id

        val applied = AtomicInteger(0)
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { applied.incrementAndGet() }
        })

        adapter.arm() // ignore the create/bind from the initial layout above
        Thread { adapter.notifyDataSetChangedAsync { adapter.list.set((0 until 5).map { "bg$it" }) } }.start()
        pumpUntil { applied.get() > 0 }
        rv.layout() // consume the prebound holders (no rebind)

        assertTrue("background create/bind should have happened", adapter.bindThreadIds.isNotEmpty())
        assertFalse("no bind may run on the render thread when background binding is on",
            adapter.bindThreadIds.contains(renderThreadId))
        assertFalse("no create may run on the render thread when background binding is on",
            adapter.createThreadIds.contains(renderThreadId))
        // ...but the resulting Actor is attached to the scenegraph (that part ran on the render thread).
        val holder = rv.findViewHolderForAdapterPosition(0)
        assertNotNull(holder)
        assertTrue("prebound Actor must be attached to the RecyclerView on the render thread",
            holder!!.getItemViews()[0].parent === rv)
    }

    @Test
    fun `without opt-in, binding still happens on the render thread even under async notify`() {
        val adapter = ThreadRecordingAdapter((0 until 5).map { "item$it" }, background = false)
        val rv = makeRv(adapter)
        val renderThreadId = Thread.currentThread().id

        val applied = AtomicInteger(0)
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { applied.incrementAndGet() }
        })

        adapter.arm()
        Thread { adapter.notifyDataSetChangedAsync { adapter.list.set((0 until 5).map { "rt$it" }) } }.start()
        pumpUntil { applied.get() > 0 }
        rv.layout() // binds now, on this render thread

        assertTrue("binding should have occurred on relayout", adapter.bindThreadIds.isNotEmpty())
        assertEquals("all binds must be on the render thread when not opted in",
            setOf(renderThreadId), adapter.bindThreadIds.toSet())
    }

    @Test
    fun `concurrent async notifies do not corrupt recycler state and settle consistently`() {
        val adapter = ThreadRecordingAdapter((0 until 20).map { "v0-$it" }, background = true)
        val rv = makeRv(adapter, height = 50f) // 5 visible at itemSize 10

        val applied = AtomicInteger(0)
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { applied.incrementAndGet() }
        })

        val rounds = 12
        repeat(rounds) { r ->
            Thread { adapter.notifyDataSetChangedAsync { adapter.list.set((0 until 20).map { "v$r-$it" }) } }.start()
        }
        // Wait for every async round to have applied, pumping the whole time.
        pumpUntil(timeoutMs = 15000L) { applied.get() >= rounds }
        rv.layout()

        // Final visible state must be internally consistent: every attached holder maps to a valid,
        // in-range position, and no Actor is doubly-tracked.
        val positions = rv.recycler.getPositions()
        assertTrue("some items should be visible", positions.isNotEmpty())
        assertTrue("all visible positions must be in range", positions.all { it in 0 until adapter.getItemCount() })
        for (pos in positions) {
            val holder = rv.findViewHolderForAdapterPosition(pos)
            assertNotNull("position $pos must have a holder", holder)
            assertEquals(pos, holder!!.adapterPosition)
            assertTrue(holder.getItemViews()[0].parent === rv)
        }
    }
}
