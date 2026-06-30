package com.unciv.ui.components.recyclerview.widget

import com.badlogic.gdx.Gdx
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext

/**
 * A [CoroutineDispatcher] that marshals work onto libGDX's render/GL thread by handing every
 * dispatched block to `com.badlogic.gdx.Gdx.app.postRunnable`.
 *
 * API CHANGE: Android's `ListAdapter`/`AsyncListDiffer` bounce results back to the UI thread via a
 * `Handler(Looper.getMainLooper())` (a `MainThreadExecutor`). There is no Android `Looper` here, and
 * kotlinx-coroutines' [kotlinx.coroutines.Dispatchers.Main] is **not** a safe substitute in this
 * project: `Dispatchers.Main` requires a platform "main dispatcher" artifact
 * (kotlinx-coroutines-android / -javafx / -swing) to be on the classpath, and the desktop/core
 * targets here only depend on kotlinx-coroutines-**core**, so `Dispatchers.Main` throws
 * `IllegalStateException: Module with the Main dispatcher had failed to initialize`. Worse, even
 * where it *does* resolve (the Android target), `Dispatchers.Main` is the Android UI thread, which is
 * not guaranteed to be libGDX's render/GL thread. The only portable, correct way to reach the render
 * thread from any thread on every backend is `Gdx.app.postRunnable`, so that is what this dispatcher
 * (and every async apply step in this package) uses. This is the single shared "hop onto the render
 * thread" primitive that both [RecyclerView.Adapter]'s async notify path and [ListAdapter.submitList]
 * route through.
 *
 * Note it *always* posts, never checking whether the caller is already on the render thread and
 * running inline: libGDX exposes no public, backend-portable "am I on the GL thread?" predicate, so
 * trying to detect-and-skip would be a correctness trap. A block posted from the render thread simply
 * runs on the next `executeRunnables()` pump, which is always safe.
 */
object GdxMainDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        Gdx.app.postRunnable(block)
    }
}
