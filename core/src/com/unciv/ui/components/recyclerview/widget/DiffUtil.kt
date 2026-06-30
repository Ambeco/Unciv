package com.unciv.ui.components.recyclerview.widget

/**
 * Calculates the minimal set of add/remove operations between two lists (Eugene Myers' diff
 * algorithm), mirroring Android's `androidx.recyclerview.widget.DiffUtil`.
 *
 * API CHANGE: none of substance - this algorithm is plain Kotlin with no Android dependency in
 * the original either. Simplified here to only emit insert/remove (no move-detection contiguous
 * block matching Android does via its "unique id" search); items that only change position are
 * reported as a remove-at-old + insert-at-new pair by [DispatchTarget]. Given this game's list
 * sizes, that's a good complexity/behavior tradeoff.
 */
object DiffUtil {

    /** Callback describing how to compare/read two lists to diff, keyed like Android's. */
    abstract class Callback {
        abstract fun getOldListSize(): Int
        abstract fun getNewListSize(): Int
        /** Whether the item at [oldItemPosition] is conceptually the "same" item as [newItemPosition] (identity). */
        abstract fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean
        /** Whether the contents equal - only invoked when [areItemsTheSame] returned true. */
        abstract fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean
    }

    /** A single unit of diff output: either items are unchanged, changed in place, or removed/inserted. */
    sealed class Operation {
        data class Unchanged(val oldPosition: Int, val newPosition: Int) : Operation()
        data class Changed(val oldPosition: Int, val newPosition: Int) : Operation()
        data class Removed(val oldPosition: Int) : Operation()
        data class Inserted(val newPosition: Int) : Operation()
    }

    class DiffResult internal constructor(private val ops: List<Operation>) {
        /** Applies the diff to a [ListUpdateCallback], in Android naming. */
        fun dispatchUpdatesTo(callback: ListUpdateCallback) {
            for (op in ops) {
                when (op) {
                    is Operation.Removed -> callback.onRemoved(op.oldPosition, 1)
                    is Operation.Inserted -> callback.onInserted(op.newPosition, 1)
                    is Operation.Changed -> callback.onChanged(op.newPosition, 1)
                    is Operation.Unchanged -> {}
                }
            }
        }
        fun getOperations(): List<Operation> = ops
    }

    interface ListUpdateCallback {
        fun onInserted(position: Int, count: Int)
        fun onRemoved(position: Int, count: Int)
        fun onChanged(position: Int, count: Int)
    }

    /** Computes the diff between the two lists described by [callback]. */
    fun calculateDiff(callback: Callback): DiffResult {
        val n = callback.getOldListSize()
        val m = callback.getNewListSize()
        // Standard O(N*M) LCS-of-"same items" DP - simple and adequate for game-UI list sizes.
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (callback.areItemsTheSame(i, j)) dp[i + 1][j + 1] + 1
                else max(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val ops = mutableListOf<Operation>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                callback.areItemsTheSame(i, j) -> {
                    ops.add(
                        if (callback.areContentsTheSame(i, j)) Operation.Unchanged(i, j)
                        else Operation.Changed(i, j)
                    )
                    i++; j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> { ops.add(Operation.Removed(i)); i++ }
                else -> { ops.add(Operation.Inserted(j)); j++ }
            }
        }
        while (i < n) { ops.add(Operation.Removed(i)); i++ }
        while (j < m) { ops.add(Operation.Inserted(j)); j++ }
        return DiffResult(ops)
    }

    private fun max(a: Int, b: Int) = if (a >= b) a else b
}