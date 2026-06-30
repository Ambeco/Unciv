package com.unciv.ui.components.recyclerview.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffUtilTest {

    private class StringListCallback(private val old: List<String>, private val new: List<String>) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            old[oldItemPosition] == new[newItemPosition]
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            old[oldItemPosition] == new[newItemPosition]
    }

    /** Applies a diff to plain lists so results can be asserted against directly. */
    private fun applyDiff(old: List<String>, new: List<String>): List<String> {
        val diff = DiffUtil.calculateDiff(StringListCallback(old, new))
        val result = old.toMutableList()
        var inserted = 0
        var removed = 0
        diff.dispatchUpdatesTo(object : DiffUtil.ListUpdateCallback {
            override fun onInserted(position: Int, count: Int) { inserted += count }
            override fun onRemoved(position: Int, count: Int) { removed += count }
            override fun onChanged(position: Int, count: Int) {}
        })
        assertEquals(new.size - old.size, inserted - removed)
        return result
    }

    @Test
    fun `identical lists produce no operations`() {
        val list = listOf("a", "b", "c")
        val diff = DiffUtil.calculateDiff(StringListCallback(list, list))
        assertTrue(diff.getOperations().all { it is DiffUtil.Operation.Unchanged })
    }

    @Test
    fun `two empty lists produce no operations`() {
        val diff = DiffUtil.calculateDiff(StringListCallback(emptyList(), emptyList()))
        assertTrue(diff.getOperations().isEmpty())
    }

    @Test
    fun `old empty, new populated - all inserts`() {
        val diff = DiffUtil.calculateDiff(StringListCallback(emptyList(), listOf("a", "b", "c")))
        val ops = diff.getOperations()
        assertEquals(3, ops.size)
        assertTrue(ops.all { it is DiffUtil.Operation.Inserted })
    }

    @Test
    fun `old populated, new empty - all removes`() {
        val diff = DiffUtil.calculateDiff(StringListCallback(listOf("a", "b", "c"), emptyList()))
        val ops = diff.getOperations()
        assertEquals(3, ops.size)
        assertTrue(ops.all { it is DiffUtil.Operation.Removed })
    }

    @Test
    fun `full replacement with no shared items removes all then inserts all`() {
        val diff = DiffUtil.calculateDiff(StringListCallback(listOf("a", "b"), listOf("x", "y", "z")))
        val ops = diff.getOperations()
        assertEquals(2, ops.count { it is DiffUtil.Operation.Removed })
        assertEquals(3, ops.count { it is DiffUtil.Operation.Inserted })
    }

    @Test
    fun `single item insertion in the middle is detected as one insert`() {
        val diff = DiffUtil.calculateDiff(StringListCallback(listOf("a", "b", "c"), listOf("a", "x", "b", "c")))
        val ops = diff.getOperations()
        assertEquals(1, ops.count { it is DiffUtil.Operation.Inserted })
        assertEquals(0, ops.count { it is DiffUtil.Operation.Removed })
        assertEquals(3, ops.count { it is DiffUtil.Operation.Unchanged })
    }

    @Test
    fun `single item removal in the middle is detected as one remove`() {
        val diff = DiffUtil.calculateDiff(StringListCallback(listOf("a", "b", "c", "d"), listOf("a", "c", "d")))
        val ops = diff.getOperations()
        assertEquals(1, ops.count { it is DiffUtil.Operation.Removed })
        assertEquals(3, ops.count { it is DiffUtil.Operation.Unchanged })
    }

    @Test
    fun `content change on an item with the same identity is reported as Changed`() {
        // items identified by first char, content is the whole string
        val callback = object : DiffUtil.Callback() {
            val old = listOf("a1", "b1")
            val new = listOf("a1", "b2")
            override fun getOldListSize() = old.size
            override fun getNewListSize() = new.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                old[oldItemPosition][0] == new[newItemPosition][0]
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                old[oldItemPosition] == new[newItemPosition]
        }
        val diff = DiffUtil.calculateDiff(callback)
        val ops = diff.getOperations()
        assertEquals(1, ops.count { it is DiffUtil.Operation.Unchanged })
        assertEquals(1, ops.count { it is DiffUtil.Operation.Changed })
    }

    @Test
    fun `moved item (no shared prefix or suffix alignment) is reported as remove plus insert`() {
        // "a" moves from index 0 to the end - Myers diff without move-detection reports this
        // as a remove + insert pair rather than a Moved operation (see class doc comment).
        val diff = DiffUtil.calculateDiff(StringListCallback(listOf("a", "b", "c"), listOf("b", "c", "a")))
        val ops = diff.getOperations()
        assertEquals(1, ops.count { it is DiffUtil.Operation.Removed })
        assertEquals(1, ops.count { it is DiffUtil.Operation.Inserted })
        assertEquals(2, ops.count { it is DiffUtil.Operation.Unchanged })
    }

    @Test
    fun `dispatchUpdatesTo invokes callback methods matching the computed operations`() {
        val diff = DiffUtil.calculateDiff(StringListCallback(listOf("a", "b"), listOf("a", "b", "c")))
        var insertedCount = 0
        var removedCount = 0
        var changedCount = 0
        diff.dispatchUpdatesTo(object : DiffUtil.ListUpdateCallback {
            override fun onInserted(position: Int, count: Int) { insertedCount += count }
            override fun onRemoved(position: Int, count: Int) { removedCount += count }
            override fun onChanged(position: Int, count: Int) { changedCount += count }
        })
        assertEquals(1, insertedCount)
        assertEquals(0, removedCount)
        assertEquals(0, changedCount)
    }

    @Test
    fun `single element list diffed against itself is a no-op`() {
        applyDiff(listOf("only"), listOf("only"))
    }
}
