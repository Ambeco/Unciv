package com.unciv.ui.components.recyclerview.widget

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A [RecyclerView.Adapter] that manages a backing list of [T] and diffs old/new lists off the
 * render thread using [DiffUtil], applying the result back on the main thread - mirroring
 * Android's `androidx.recyclerview.widget.ListAdapter`.
 *
 * API CHANGE: Android's `ListAdapter`/`AsyncListDiffer` run the diff on a caller-supplied
 * `Executor` (defaulting to `Executors.newFixedThreadPool`) and post the result back via a
 * `MainThreadExecutor`. There is no Android main-thread `Handler` here; instead this uses
 * `kotlinx.coroutines` (already a project dependency - see `build.gradle.kts`), running the diff
 * on [Dispatchers.Default] and applying results via a caller-supplied [mainThreadDispatcher].
 *
 * API CHANGE: the default [mainThreadDispatcher] is [GdxMainDispatcher] (which posts via
 * `Gdx.app.postRunnable`), **not** [Dispatchers.Main]. See [GdxMainDispatcher]'s KDoc for why
 * `Dispatchers.Main` is both unavailable (core/desktop only ship kotlinx-coroutines-core) and
 * semantically wrong (the Android UI thread is not libGDX's render/GL thread). Both this adapter and
 * the generic [RecyclerView.Adapter.notifyDataSetChangedAsync] path share [GdxMainDispatcher] as the
 * one render-thread hop, so the "compute a delta off-thread, apply it on the render thread" plumbing
 * is identical for list-diffing and plain adapters. Tests may inject [kotlinx.coroutines.Dispatchers.Unconfined]
 * here to run `submitList` synchronously without a render-thread pump.
 */
abstract class ListAdapter<T, VH : RecyclerView.ViewHolder>(
    private val diffCallbackFactory: (oldList: List<T>, newList: List<T>) -> DiffUtil.Callback,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val mainThreadDispatcher: kotlinx.coroutines.CoroutineDispatcher = GdxMainDispatcher
) : RecyclerView.Adapter<VH>() {

    private var currentList: List<T> = emptyList()
    private var pendingJob: Job? = null

    fun getCurrentList(): List<T> = currentList

    override fun getItemCount(): Int = currentList.size

    fun getItem(position: Int): T = currentList[position]

    /**
     * Replaces the list, diffing in the background and dispatching granular notify* calls once
     * done. [commitCallback], if given, runs after the swap completes.
     */
    fun submitList(newList: List<T>, commitCallback: (() -> Unit)? = null) {
        pendingJob?.cancel()
        val oldList = currentList
        pendingJob = scope.launch {
            val callback = diffCallbackFactory(oldList, newList)
            val diff = DiffUtil.calculateDiff(callback)
            withContext(mainThreadDispatcher) {
                currentList = newList
                diff.dispatchUpdatesTo(object : DiffUtil.ListUpdateCallback {
                    override fun onInserted(position: Int, count: Int) { notifyItemRangeInserted(position, count) }
                    override fun onRemoved(position: Int, count: Int) { notifyItemRangeRemoved(position, count) }
                    override fun onChanged(position: Int, count: Int) { notifyItemRangeChanged(position, count) }
                })
                commitCallback?.invoke()
            }
        }
    }
}
