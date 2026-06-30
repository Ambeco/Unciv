package com.unciv.ui.components.recyclerview.widget

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Uses [Dispatchers.Unconfined] for both the diffing scope and the "main thread" dispatcher so
 * `submitList` completes synchronously within the test, avoiding both real threading flakiness
 * and the need for a platform Dispatchers.Main implementation (not available on a plain JVM
 * classpath without kotlinx-coroutines-android/javafx/swing).
 */
@RunWith(GdxTestRunner::class)
class ListAdapterTest {

    private class SimpleViewHolder(private val widget: com.badlogic.gdx.scenes.scene2d.ui.Widget) : RecyclerView.ViewHolder() {
        override fun getItemViews(): List<com.badlogic.gdx.scenes.scene2d.ui.Widget> = listOf(widget)
    }

    private class TestListAdapter : ListAdapter<String, SimpleViewHolder>(
        diffCallbackFactory = { old, new ->
            object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = new.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) = old[oldItemPosition] == new[newItemPosition]
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) = old[oldItemPosition] == new[newItemPosition]
            }
        },
        scope = CoroutineScope(Dispatchers.Unconfined),
        mainThreadDispatcher = Dispatchers.Unconfined
    ) {
        override fun onCreateViewHolder(parent: RecyclerView, viewType: Int) = SimpleViewHolder(RecyclerViewTestSupport.SizedActor(10f))
        override fun onBindViewHolder(holder: SimpleViewHolder, position: Int) {}
    }

    @Test
    fun `submitList updates the current list synchronously under Unconfined dispatchers`() {
        val adapter = TestListAdapter()
        assertEquals(0, adapter.getItemCount())

        adapter.submitList(listOf("a", "b", "c"))
        assertEquals(3, adapter.getItemCount())
        assertEquals(listOf("a", "b", "c"), adapter.getCurrentList())
    }

    @Test
    fun `submitList with insertions notifies via notifyItemRangeInserted semantics`() {
        val adapter = TestListAdapter()
        adapter.submitList(listOf("a", "b"))

        var insertedCount = 0
        var removedCount = 0
        var changedCount = 0
        // Piggyback on the RecyclerView notification path: attach to a real RecyclerView and
        // count relayouts as a smoke test that notifications actually fire.
        val rv = RecyclerView()
        rv.setSize(50f, 50f)
        rv.layoutManager = LinearLayoutManager()
        rv.setAdapter(adapter)
        rv.layout()
        assertEquals(2, rv.recycler.getPositions().size)

        adapter.submitList(listOf("a", "b", "c"))
        rv.layout()
        assertEquals(3, adapter.getItemCount())
        assertEquals(3, rv.recycler.getPositions().size)
    }

    @Test
    fun `submitList with a full replacement swaps content correctly`() {
        val adapter = TestListAdapter()
        adapter.submitList(listOf("a", "b"))
        adapter.submitList(listOf("x", "y", "z"))
        assertEquals(listOf("x", "y", "z"), adapter.getCurrentList())
    }

    @Test
    fun `submitList commitCallback runs after the list is applied`() {
        val adapter = TestListAdapter()
        var callbackListSizeAtInvocation = -1
        adapter.submitList(listOf("a", "b", "c")) {
            callbackListSizeAtInvocation = adapter.getItemCount()
        }
        assertEquals(3, callbackListSizeAtInvocation)
    }

    @Test
    fun `getItem returns the element at the given position`() {
        val adapter = TestListAdapter()
        adapter.submitList(listOf("first", "second"))
        assertEquals("first", adapter.getItem(0))
        assertEquals("second", adapter.getItem(1))
    }

    @Test
    fun `submitList with an empty list clears the adapter`() {
        val adapter = TestListAdapter()
        adapter.submitList(listOf("a", "b"))
        adapter.submitList(emptyList())
        assertEquals(0, adapter.getItemCount())
    }
}
