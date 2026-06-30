package com.unciv.ui.components.recyclerview.widget

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup
import com.badlogic.gdx.scenes.scene2d.utils.Layout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A scene2d-native, virtualized list/grid widget. This is **not** a line-by-line port of
 * `androidx.recyclerview.widget.RecyclerView` - it is a small, self-contained scene2d
 * [WidgetGroup] with no dependency on anything outside libGDX (`com.badlogic.gdx.*`) and the
 * Kotlin/Java standard library, so it can be lifted out wholesale later (e.g. upstreamed).
 *
 * Its *public API shape* deliberately mirrors Android's RecyclerView as closely as the scene2d
 * scenegraph allows - same class/method names for [Adapter], [ViewHolder], [LayoutManager],
 * [Recycler], [State], [LayoutParams], [ItemDecoration] - so that existing Android RecyclerView
 * knowledge (tutorials, StackOverflow answers, docs) mostly transfers. Every point where behavior,
 * a signature, or a concept had to change is marked with an `// API CHANGE:` comment.
 *
 * API CHANGE: dropped entirely, with no scene2d equivalent or use case: GapWorker background
 * prefetching, ViewInfoStore/ChildHelper hidden-view bookkeeping, accessibility delegates,
 * Parcelable state save/restore, RTL support, nested scrolling, ItemTouchHelper drag & drop,
 * StaggeredGridLayoutManager, SortedList, AsyncListUtil/TileList paging, MessageThreadUtil,
 * ConcatAdapter. [Adapter.registerAdapterDataObserver] *is* supported (multiple observers), and
 * the RecyclerView registers itself as one to react to notify* calls. There is no full ItemAnimator,
 * but [smoothScrollToPosition] animates, drags rubber-band past the content edges (overscroll), and
 * a per-bind [Action] hook (see [Adapter]) covers lightweight appear/update animations.
 */
open class RecyclerView : WidgetGroup() {

    // region public configuration

    private var adapter: Adapter<ViewHolder>? = null

    var layoutManager: LayoutManager? = null
        set(value) {
            field = value
            value?.recyclerView = this
            requestLayout()
        }

    private val decorations = mutableListOf<ItemDecoration>()

    /** API CHANGE: Android's setHasFixedSize is a real perf hint tied to measure/layout caching.
     * Accepted here for API compatibility (so ported call sites compile) but currently unused -
     * this implementation always positions items using the LayoutManager's own size model. */
    var hasFixedSize: Boolean = false
        private set

    /** API CHANGE: Android tracks scroll offset diffusely (via LayoutManager child positions);
     * here each axis's offset is owned directly by the [LayoutManager] for simplicity, and
     * exposed read-only here for convenience/inspection. */
    val scrollOffsetX: Float get() = layoutManager?.scrollOffsetX ?: 0f
    val scrollOffsetY: Float get() = layoutManager?.scrollOffsetY ?: 0f

    val recycler = Recycler()
    private val state = State()

    // endregion

    // region scroll animation & overscroll state

    /** Target adapter position for an in-flight [smoothScrollToPosition] animation, or null. */
    private var smoothTargetPosition: Int? = null
    /** True while a touch drag is in progress (suspends overscroll settle). */
    private var dragging = false
    /** Current rubber-band displacement (in scroll-delta units) along each axis; children are
     * translated by its negation. Settles back to 0 in [act] after the finger lifts. Independent
     * per axis so a LayoutManager that scrolls both axes at once (e.g. [GridLayoutManager] with
     * cross-axis scrolling enabled) can rubber-band each edge separately - a LayoutManager that
     * only ever claims one axis via `canScrollHorizontally`/`canScrollVertically` never accumulates
     * anything on the other (see [touchDragged]), so this generalization changes nothing for it. */
    private var overscrollX = 0f
    private var overscrollY = 0f
    /** How much child translation is currently applied per axis, so [applyOverscrollTranslation] can
     * move by the incremental delta instead of re-translating from scratch each frame. */
    private var appliedOverscrollX = 0f
    private var appliedOverscrollY = 0f

    /** API CHANGE: exposes the current rubber-band overscroll magnitude (px). Android hides this
     * inside EdgeEffect; surfaced here read-only for inspection/testing. This overload reports
     * whichever axis is this LayoutManager's *main* one (matching the single-axis meaning this had
     * before cross-axis scrolling existed) - see [overscrollDistanceX]/[overscrollDistanceY] for the
     * two axes independently. */
    val overscrollDistance: Float get() = if (scrollIsVertical) overscrollY else overscrollX
    val overscrollDistanceX: Float get() = overscrollX
    val overscrollDistanceY: Float get() = overscrollY

    private val scrollIsVertical: Boolean get() = layoutManager?.orientation != Orientation.HORIZONTAL

    private fun currentMainOffset(): Float {
        val lm = layoutManager ?: return 0f
        return if (lm.orientation == Orientation.VERTICAL) lm.scrollOffsetY else lm.scrollOffsetX
    }

    // endregion

    // region fling & edge auto-scroll

    /** Drag velocity (px/sec), exponentially smoothed across [touchDragged] samples - see
     *  [VELOCITY_SMOOTHING] - so a single jittery sample right before release doesn't dominate the
     *  fling this seeds on [touchUp]. Reset to 0 on [touchDown]. Real Android RecyclerView parity
     *  (unlike overscroll/smooth-scroll, which have no exact Android equivalent shape) - matches
     *  its own VelocityTracker-fed fling, just via simple smoothing instead of a multi-sample history. */
    private var velocityX = 0f
    private var velocityY = 0f
    private var lastDragTimeMs = 0L

    /** Test seam: real wall-clock elapsed time (ms), used to measure drag velocity for fling
     *  detection. Defaults to [System.currentTimeMillis] - touch samples arrive at real
     *  wall-clock-timed intervals (unlike [act]'s frame-based `delta`), so velocity genuinely needs
     *  real elapsed time, not a frame count. Tests wanting deterministic velocity (rather than
     *  whatever real time elapses between two back-to-back dispatched events) inject a fake clock
     *  here instead. */
    var currentTimeMillis: () -> Long = System::currentTimeMillis

    /** Current fling velocity (px/sec); 0 means not flinging. Independent per axis, same reasoning
     *  as [overscrollX]/[overscrollY] - a LayoutManager scrolling both axes at once flings on both,
     *  one that only claims one axis never accumulates anything on the other. Decays via
     *  [FLING_FRICTION] each frame in [act], and is zeroed for whichever axis can't consume any more
     *  scroll (content edge reached), handing off to overscroll rubber-band from there - same as a
     *  drag hitting an edge does. */
    private var flingVelocityX = 0f
    private var flingVelocityY = 0f

    /** API CHANGE: no Android equivalent - opt-in continuous scrolling while the pointer/mouse rests
     *  near a stage edge without touching/dragging, for desktop-style edge-scrolling. Off by default;
     *  matches [com.unciv.ui.components.widgets.ZoomableScrollPane]'s identical feature (see [act]
     *  for the one behavioral difference: speed here scales off this class's own per-frame [act]
     *  delta directly, not a separate fixed-tick-rate normalization ZoomableScrollPane's draw()-based
     *  version needed). */
    var isAutoScrollEnabled: Boolean = false
    /** Edge-scroll speed for [isAutoScrollEnabled], in the same px/sec units [scrollBy] takes. */
    var autoScrollSpeed: Float = 400f

    /** Feeds [requestedX]/[requestedY] (already zeroed on any axis the LayoutManager doesn't claim -
     *  see [touchDragged]'s identical gating) through [scrollMainBy], turning whatever's left over
     *  (a content edge was hit) into rubber-band overscroll - shared by [touchDragged] and this
     *  class's own fling/edge-auto-scroll stepping in [act], all three of which hit a content edge
     *  the same way. Returns the per-axis consumed amount, so a caller tracking its own velocity
     *  (fling) can tell whether it was fully consumed or should stop on that axis. */
    private fun scrollAndOverscroll(requestedX: Float, requestedY: Float): Pair<Float, Float> {
        val (consumedX, consumedY) = scrollMainBy(requestedX, requestedY)
        val leftoverX = requestedX - consumedX
        if (leftoverX != 0f) {
            val cap = width * MAX_OVERSCROLL_FRACTION
            overscrollX = (overscrollX + leftoverX * OVERSCROLL_RESISTANCE).coerceIn(-cap, cap)
        }
        val leftoverY = requestedY - consumedY
        if (leftoverY != 0f) {
            val cap = height * MAX_OVERSCROLL_FRACTION
            overscrollY = (overscrollY + leftoverY * OVERSCROLL_RESISTANCE).coerceIn(-cap, cap)
        }
        applyOverscrollTranslation()
        return consumedX to consumedY
    }

    // endregion

    init {
        addListener(object : InputListener() {
            private var lastY = 0f
            private var lastX = 0f
            override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                lastY = y; lastX = x
                dragging = true
                smoothTargetPosition = null // a fresh drag cancels any running smooth scroll
                velocityX = 0f; velocityY = 0f
                flingVelocityX = 0f; flingVelocityY = 0f // a fresh touch stops any in-flight fling
                lastDragTimeMs = currentTimeMillis()
                return true
            }
            override fun touchDragged(event: InputEvent?, x: Float, y: Float, pointer: Int) {
                val dx = x - lastX
                val dy = y - lastY
                val lm = layoutManager
                // Dragging content up/left should scroll the offset the opposite way, matching
                // Android's touch-scroll convention (finger moves content, not the viewport). Only
                // attempted on an axis the LayoutManager actually claims (canScrollHorizontally/
                // Vertically) - both axes at once for a manager that supports simultaneous 2D
                // scrolling (e.g. a hex map), same single axis as before for anything that doesn't.
                val requestedX = if (lm?.canScrollHorizontally() == true) -dx else 0f
                val requestedY = if (lm?.canScrollVertically() == true) -dy else 0f
                scrollAndOverscroll(requestedX, requestedY)
                lastX = x; lastY = y

                // Smoothed instantaneous velocity (px/sec), seeding a fling on touchUp if it's fast
                // enough - see velocityX/velocityY's KDoc.
                val now = currentTimeMillis()
                val dtMs = (now - lastDragTimeMs).coerceAtLeast(1L)
                val instVelX = requestedX / dtMs * 1000f
                val instVelY = requestedY / dtMs * 1000f
                velocityX = VELOCITY_SMOOTHING * instVelX + (1f - VELOCITY_SMOOTHING) * velocityX
                velocityY = VELOCITY_SMOOTHING * instVelY + (1f - VELOCITY_SMOOTHING) * velocityY
                lastDragTimeMs = now
            }
            override fun touchUp(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) {
                // Release: let act() animate the rubber-band back to the clamped bound, and/or
                // start a fling if the release velocity clears the minimum threshold.
                dragging = false
                if (kotlin.math.abs(velocityX) >= MIN_FLING_VELOCITY) flingVelocityX = velocityX
                if (kotlin.math.abs(velocityY) >= MIN_FLING_VELOCITY) flingVelocityY = velocityY
            }
        })
    }

    /** Translates all attached children by the incremental change in overscroll, on whichever
     *  axis/axes currently have any (independent per axis - see [overscrollX]/[overscrollY]). */
    private fun applyOverscrollTranslation() {
        val targetX = -overscrollX
        val targetY = -overscrollY
        val deltaX = targetX - appliedOverscrollX
        val deltaY = targetY - appliedOverscrollY
        if (deltaX == 0f && deltaY == 0f) return
        for (child in children) {
            child.x += deltaX
            child.y += deltaY
        }
        appliedOverscrollX = targetX
        appliedOverscrollY = targetY
    }

    /** API CHANGE: RecyclerView is a scene2d [Actor], so smooth-scroll physics and overscroll
     * settle are driven from [act] (per-frame) rather than Android's Choreographer/ViewFlinger.
     * Requires the RecyclerView to be on a running stage (or [act] to be pumped manually, as tests
     * do); with no stage it simply never animates, and immediate [scrollToPosition] still works. */
    override fun act(delta: Float) {
        super.act(delta)
        val lm = layoutManager

        // Smooth scroll: step the anchor toward the target position each frame, snapping on arrival.
        val target = smoothTargetPosition
        if (target != null && lm != null) {
            val direction = lm.compareAnchorTo(target)
            if (direction == 0) {
                lm.scrollToPosition(target); requestLayout(); smoothTargetPosition = null
            } else {
                val beforeAnchor = lm.currentAnchorPosition()
                val beforeOffset = currentMainOffset()
                val step = lm.mainStepSize() * SMOOTH_SCROLL_SPEED * delta * direction
                if (scrollIsVertical) scrollBy(0f, step) else scrollBy(step, 0f)
                val newDirection = lm.compareAnchorTo(target)
                when {
                    // Crossed or reached the target row/position: snap exactly onto it.
                    newDirection == 0 || newDirection != direction -> {
                        lm.scrollToPosition(target); requestLayout(); smoothTargetPosition = null
                    }
                    // No progress (hit a content edge before reaching the target): give up.
                    lm.currentAnchorPosition() == beforeAnchor && currentMainOffset() == beforeOffset ->
                        smoothTargetPosition = null
                }
            }
        }

        // Fling: continue scrolling with decaying velocity after a fast drag release - real Android
        // RecyclerView API parity (see flingVelocityX/Y's KDoc), not an API CHANGE like smooth-scroll/
        // overscroll. Stops immediately per axis once that axis can't consume any more scroll (a
        // content edge was hit), via the same scrollAndOverscroll helper touchDragged uses, so a
        // fling that runs into an edge rubber-bands exactly like a drag that does.
        if (!dragging) {
            if (flingVelocityX != 0f || flingVelocityY != 0f) {
                val (consumedX, consumedY) = scrollAndOverscroll(flingVelocityX * delta, flingVelocityY * delta)
                if (flingVelocityX != 0f) {
                    if (consumedX != flingVelocityX * delta) flingVelocityX = 0f // hit an edge - stop this axis
                    else {
                        flingVelocityX *= (1f - FLING_FRICTION * delta).coerceAtLeast(0f)
                        if (kotlin.math.abs(flingVelocityX) < MIN_FLING_VELOCITY) flingVelocityX = 0f
                    }
                }
                if (flingVelocityY != 0f) {
                    if (consumedY != flingVelocityY * delta) flingVelocityY = 0f // hit an edge - stop this axis
                    else {
                        flingVelocityY *= (1f - FLING_FRICTION * delta).coerceAtLeast(0f)
                        if (kotlin.math.abs(flingVelocityY) < MIN_FLING_VELOCITY) flingVelocityY = 0f
                    }
                }
            }

            // Edge auto-scroll: continuous scrolling while the pointer/mouse rests near a stage edge,
            // opt-in via isAutoScrollEnabled - see that property's KDoc. Only when not also flinging,
            // so the two don't fight over the same axis.
            if (isAutoScrollEnabled && !Gdx.input.isTouched && flingVelocityX == 0f && flingVelocityY == 0f) {
                val stg = stage
                // A viewport that hasn't had update() called yet (screenWidth/screenHeight still 0)
                // would otherwise make every posX/posY count as "near the right/bottom edge" - skip
                // auto-scroll entirely until the stage is actually sized.
                if (stg != null && stg.viewport.screenWidth > 0 && stg.viewport.screenHeight > 0) {
                    val posX = Gdx.input.x
                    val posY = Gdx.input.y // screen coords: Y grows downward, unlike world coordinates
                    val edgeDirX = when {
                        posX <= EDGE_AUTO_SCROLL_MARGIN -> -1
                        posX >= stg.viewport.screenWidth - EDGE_AUTO_SCROLL_MARGIN -> 1
                        else -> 0
                    }
                    val edgeDirY = when {
                        posY <= EDGE_AUTO_SCROLL_MARGIN -> 1
                        posY >= stg.viewport.screenHeight - EDGE_AUTO_SCROLL_MARGIN -> -1
                        else -> 0
                    }
                    if (edgeDirX != 0 || edgeDirY != 0) {
                        scrollAndOverscroll(edgeDirX * autoScrollSpeed * delta, edgeDirY * autoScrollSpeed * delta)
                    }
                }
            }
        }

        // Overscroll settle: exponentially decay the rubber-band back to zero once released, per axis.
        // Only once fling/edge-auto-scroll aren't actively driving that axis this frame - both already
        // route through scrollAndOverscroll, which updates overscroll itself when they hit an edge.
        if (!dragging) {
            if (overscrollX != 0f && flingVelocityX == 0f) {
                overscrollX *= (1f - OVERSCROLL_SETTLE_RATE * delta).coerceAtLeast(0f)
                if (kotlin.math.abs(overscrollX) < 0.5f) overscrollX = 0f
            }
            if (overscrollY != 0f && flingVelocityY == 0f) {
                overscrollY *= (1f - OVERSCROLL_SETTLE_RATE * delta).coerceAtLeast(0f)
                if (kotlin.math.abs(overscrollY) < 0.5f) overscrollY = 0f
            }
        }
        applyOverscrollTranslation()
    }

    // region public API (Android-shaped)

    /** True once any structural adapter change (plain [Adapter.notifyDataSetChanged], or
     * insert/remove/move - anything that can shift which position a holder should show) has been
     * observed since the last drained layout - see [drainPendingAdapterChanges]. Supersedes every
     * entry in [pendingItemChanges] (a full invalidate rebinds everything anyway regardless of what
     * else was pending), so those are simply discarded once this is set. */
    private var pendingFullInvalidate = false

    /** Bookkeeping for one position's not-yet-applied [Adapter.notifyItemChanged]/
     *  [Adapter.notifyItemRangeChanged] calls - see [pendingItemChanges]. Mirrors Android's own
     *  `ViewHolder.addChangePayload`: a payload-less notify sets [fullUpdate] (which then makes any
     *  accumulated [payloads] irrelevant - a full bind already re-renders everything), while payloads
     *  from multiple notifies for the same position before the next layout simply accumulate. */
    private class PendingItemChange {
        var fullUpdate = false
        val payloads = mutableListOf<Any>()
    }

    /** Positions with a not-yet-applied same-position content change, in first-changed order - see
     *  [PendingItemChange]. Applied and cleared by [drainPendingAdapterChanges] right before the next
     *  layout pass actually runs, *not* synchronously when the notify call happens. */
    private val pendingItemChanges = LinkedHashMap<Int, PendingItemChange>()

    /** RecyclerView participates in the adapter's observer list like any other observer, but -
     * mirroring Android's own internal behavior (its `AdapterHelper` defers/coalesces pending
     * notifications until the next layout traversal rather than reacting to each one immediately,
     * even though the public [Adapter] notify surface itself is only ever single-position/contiguous-
     * range - see [drainPendingAdapterChanges]) - a notify call here only *records* what changed and
     * marks this dirty; nothing is actually rebound until [layout] next runs. That's what lets several
     * separate notify calls in the same frame - even non-contiguous ones, which the public API has no
     * single batch call for - resolve into one relayout, with any newly-added views across all of them
     * inserted via one sort/merge pass (see [Recycler.beginAttachBatch]) instead of one sort-scan per
     * notify call. */
    private val dataObserver = object : AdapterDataObserver() {
        override fun onChanged() {
            pendingFullInvalidate = true
            requestLayout()
        }
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
            for (position in positionStart until positionStart + itemCount) {
                pendingItemChanges.getOrPut(position) { PendingItemChange() }.fullUpdate = true
            }
            requestLayout()
        }
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
            for (position in positionStart until positionStart + itemCount) {
                val change = pendingItemChanges.getOrPut(position) { PendingItemChange() }
                if (payload != null) change.payloads.add(payload) else change.fullUpdate = true
            }
            requestLayout()
        }
    }

    fun setAdapter(adapter: Adapter<*>?) {
        recycler.clear()
        this.adapter?.unregisterAdapterDataObserver(dataObserver)
        @Suppress("UNCHECKED_CAST")
        this.adapter = adapter as Adapter<ViewHolder>?
        adapter?.recyclerView = this
        adapter?.registerAdapterDataObserver(dataObserver)
        requestLayout()
    }

    fun getAdapter(): Adapter<*>? = adapter

    fun addItemDecoration(decoration: ItemDecoration) {
        decorations.add(decoration)
        requestLayout()
    }

    fun removeItemDecoration(decoration: ItemDecoration) {
        decorations.remove(decoration)
        requestLayout()
    }

    fun invalidateItemDecorations() = requestLayout()

    fun setHasFixedSize(hasFixedSize: Boolean) {
        this.hasFixedSize = hasFixedSize
    }

    fun scrollToPosition(position: Int) {
        layoutManager?.scrollToPosition(position)
        requestLayout()
    }

    /** API CHANGE: Android's smoothScrollToPosition delegates to a pluggable SmoothScroller with
     * deceleration physics. Here it starts a per-frame anchor-walking animation driven by [act]:
     * the anchor steps toward [position] (advancing through intermediate items so variable item
     * sizes are respected) and snaps onto it on arrival. Same method name, so ported call sites
     * keep working; it needs a running stage (or manual [act] pumping) to actually animate. */
    fun smoothScrollToPosition(position: Int) {
        val a = adapter ?: return
        if (layoutManager == null) return
        smoothTargetPosition = position.coerceIn(0, (a.getItemCount() - 1).coerceAtLeast(0))
    }

    /** API CHANGE: Android's `View.scrollBy` semantics, but delegates straight to the
     * LayoutManager's `scrollHorizontallyBy`/`scrollVerticallyBy`, matching how Android's
     * RecyclerView itself dispatches scrolling. */
    fun scrollBy(dx: Float, dy: Float) {
        scrollMainBy(dx, dy)
    }

    /** Dispatches a scroll to the LayoutManager on each axis independently, returning the distance
     * actually consumed on each - the per-axis leftover feeds the touch-drag overscroll rubber-band
     * (see [touchDragged]). Both axes are always attempted; an axis the LayoutManager doesn't claim
     * via `canScrollHorizontally`/`canScrollVertically` simply consumes nothing, same as before this
     * became two independent axes instead of one combined value. */
    private fun scrollMainBy(dx: Float, dy: Float): Pair<Float, Float> {
        val lm = layoutManager ?: return 0f to 0f
        state.itemCount = adapter?.getItemCount() ?: 0
        val consumedX = if (dx != 0f && lm.canScrollHorizontally()) lm.scrollHorizontallyBy(dx, recycler, state) else 0f
        val consumedY = if (dy != 0f && lm.canScrollVertically()) lm.scrollVerticallyBy(dy, recycler, state) else 0f
        return consumedX to consumedY
    }

    fun findViewHolderForAdapterPosition(position: Int): ViewHolder? = recycler.activeViews[position]

    fun getChildViewHolder(child: Actor): ViewHolder? = recycler.childViewHolders[child]

    /** Accumulated offsets (left/top as x/y, right/bottom as width/height) from every registered
     * [ItemDecoration] for [position]. Used by [LayoutManager] implementations to inset item
     * bounds, mirroring how Android's LayoutManager consults ItemDecoration.getItemOffsets. */
    fun getItemDecorationInsetsForPosition(position: Int): Rectangle {
        val out = Rectangle()
        for (d in decorations) {
            val r = Rectangle()
            d.getItemOffsets(r, position, this, state)
            out.x += r.x
            out.y += r.y
            out.width += r.width
            out.height += r.height
        }
        return out
    }

    // endregion

    private fun requestLayout() = invalidate()

    override fun layout() {
        val lm = layoutManager ?: return
        val a = adapter ?: return
        drainPendingAdapterChanges(a)
        state.itemCount = a.getItemCount()
        lm.onLayoutChildren(recycler, state)
        // onLayoutChildren re-set every child's bounds from scratch, dropping any overscroll
        // translation; re-apply the current rubber-band on top of the fresh layout.
        appliedOverscrollX = 0f
        appliedOverscrollY = 0f
        applyOverscrollTranslation()
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        for (d in decorations) d.onDraw(batch, this, state)
        super.draw(batch, parentAlpha)
        for (d in decorations) d.onDrawOver(batch, this, state)
    }

    /** Applies every adapter change [dataObserver] has recorded but not yet acted on - see
     * [pendingFullInvalidate]/[pendingItemChanges] - right before [layout] actually runs the
     * LayoutManager. This is where the deferred-until-layout batching described on [dataObserver]
     * happens:
     * - If [pendingFullInvalidate] is set (any structural change - [Adapter.notifyDataSetChanged],
     *   insert/remove/move - happened since the last drain), every pending item-change is discarded
     *   as redundant and every currently-active holder is evicted via [Recycler.invalidateBindings]
     *   and rebuilt from scratch on the layout pass that follows - matching [Adapter.notifyItemInserted]/
     *   [notifyItemRemoved]/[notifyItemMoved]'s Android semantics (positions may have shifted, so
     *   nothing can be trusted to still show what it showed before), just resolved once per drain
     *   instead of once per notify call even if several fired since the last layout.
     * - Otherwise, every pending same-position content change (from [Adapter.notifyItemChanged]/
     *   [Adapter.notifyItemRangeChanged], however many separate calls contributed to it - matches
     *   Android's own internal `AdapterHelper` coalescing, done here explicitly) is applied in one
     *   [Recycler.beginAttachBatch]/[Recycler.endAttachBatch]-bracketed pass: each currently-active
     *   changed position is rebound *in place* via [Recycler.rebindInPlace] (never detached from the
     *   scenegraph, no other holder touched), with any newly-added Actors across the whole pass
     *   inserted via one sort/merge instead of one scan per view - see [Recycler.endAttachBatch]. A
     *   position with no currently-active holder (off-screen) needs nothing done: it binds fresh with
     *   current data whenever it next scrolls into view. [PendingItemChange.fullUpdate] forces a plain
     *   (non-payload) rebind for that position even if payloads were also accumulated for it,
     *   matching Android's `ViewHolder.addChangePayload(null)` behavior. */
    private fun drainPendingAdapterChanges(adapter: Adapter<ViewHolder>) {
        if (pendingFullInvalidate) {
            pendingFullInvalidate = false
            pendingItemChanges.clear() // superseded - a full invalidate rebinds everything anyway
            recycler.invalidateBindings()
            layoutManager?.invalidateSizeCache()
            return
        }
        if (pendingItemChanges.isEmpty()) return
        val changes = pendingItemChanges.entries.toList()
        pendingItemChanges.clear()
        recycler.beginAttachBatch()
        try {
            for ((position, change) in changes) {
                if (position !in recycler.activeViews) continue
                val payloads = if (change.fullUpdate) emptyList() else change.payloads
                recycler.rebindInPlace(position, adapter, payloads)
            }
        } finally {
            recycler.endAttachBatch()
        }
        layoutManager?.invalidateSizeCache()
    }

    // region nested types

    enum class Orientation { VERTICAL, HORIZONTAL }

    /**
     * API CHANGE: wraps bare scene2d [Actor]s, not an Android `View`. Unlike Android's `View`, a
     * plain [Actor] doesn't necessarily know how to lay itself out - only [Layout] implementors
     * (e.g. `Widget`, `WidgetGroup`) report a real `prefWidth`/`prefHeight`/etc. Rather than require
     * every item to be a [Layout] (which would rule out plain [Group]-based item views that don't
     * happen to implement it), a supporting LayoutManager's per-item measurement (see
     * [LinearLayoutManager.measure]) checks [layoutParams] for `null` and measures a non-[Layout]
     * item as 0 - see that method's KDoc. [layoutParams] is attached directly to the holder rather
     * than to a wrapped Actor (Android attaches `LayoutParams` to the `View`) since here they're
     * conceptually one unit regardless of how many Actors the holder binds.
     *
     * API CHANGE: no constructor Actor - Android's ViewHolder requires exactly one `View` to exist
     * before construction finishes (`ViewHolder(itemView: View)`). Here a holder builds/assigns
     * whatever it needs in its own `init` (or lazily, in [onCreateViewHolder]/[onBindViewHolder]) and
     * exposes it via [getItemViews], which is also how a holder binds *more than one* Actor - e.g.
     * contributing an image into each of several independently-managed shared containers, rather
     * than one `View` per item the way Android's model requires. There is deliberately no single
     * `itemView` convenience: callers that only ever bind one Actor use `getItemViews()[0]`.
     */
    abstract class ViewHolder {
        var adapterPosition: Int = NO_POSITION
            internal set
        var itemViewType: Int = 0
            internal set

        /** Explicit override for [layoutParams] - `null` (the default) means "use the fallback",
         *  i.e. the item Actor's own reported size if it happens to implement [Layout], else no
         *  size info at all. Backing field for [layoutParams]'s custom getter/setter; see that
         *  property's KDoc. */
        private var explicitLayoutParams: Layout? = null

        /**
         * Sizing hint a supporting LayoutManager's per-item measurement reads (see
         * [LinearLayoutManager.measure]) - reuses Gdx's own [Layout] interface (`minWidth`/
         * `prefWidth`/`maxWidth`, `minHeight`/`prefHeight`/`maxHeight`) rather than a parallel type of
         * our own. The default getter falls back to `getItemViews().firstOrNull()`, but only if that
         * Actor actually implements [Layout] (e.g. a `Widget`/`WidgetGroup`) - a plain [Group]-based
         * item view (implements neither) falls back to `null`, and measurement treats that the same
         * as "reports no size" (0).
         *
         * Still fully overridable per holder: assign an explicit [Layout] (see [LayoutParams] for a
         * ready-made settable one, e.g. in the holder's own `init` or in [Adapter.onBindViewHolder])
         * to report different sizing than the item Actor's own - e.g. for an item that reports no
         * meaningful `prefWidth`/`prefHeight` of its own, or doesn't implement [Layout] at all. Assign
         * `null` to explicitly go back to the default fallback.
         */
        var layoutParams: Layout?
            get() = explicitLayoutParams ?: (getItemViews().firstOrNull() as? Layout)
            set(value) { explicitLayoutParams = value }

        /** Every [Actor] this holder currently wants attached and positioned by the RecyclerView.
         *  Most holders bind exactly one; override to bind more (see class doc). Implementations
         *  should keep this stable (same Actors, same order) across the holder's bound lifetime,
         *  since callers may read it more than once per layout pass. */
        abstract fun getItemViews(): List<Actor>

        companion object {
            const val NO_POSITION = -1
        }
    }

    /**
     * API CHANGE: Android's Adapter also manages stable IDs (dropped here). [AdapterDataObserver]
     * registration *is* supported and matches Android's contract - notify* methods fan out to every
     * registered observer. The owning RecyclerView registers itself as an observer (see [setAdapter])
     * to relayout on data changes; extra observers may be added for other bookkeeping. No per-item
     * add/remove/move animations are implemented; the observer callbacks just describe the change.
     *
     * ### Per-bind animation hook
     * Before each bind the item actor's leftover [Action]s are cleared and its alpha reset (see
     * [Recycler.getViewForPosition]), so [onBindViewHolder] is free to kick off a fresh per-item
     * appear/update animation without fighting stale state left over from pooling. For example:
     * ```
     * override fun onBindViewHolder(holder: MyHolder, position: Int) {
     *     holder.bind(items[position])
     *     holder.getItemViews()[0].addAction(Actions.fadeIn(0.2f)) // clean fade-in on every (re)bind
     * }
     * ```
     */
    abstract class Adapter<VH : ViewHolder> {
        internal var recyclerView: RecyclerView? = null
        private val observers = mutableListOf<AdapterDataObserver>()

        abstract fun onCreateViewHolder(parent: RecyclerView, viewType: Int): VH
        abstract fun onBindViewHolder(holder: VH, position: Int)
        abstract fun getItemCount(): Int
        open fun getItemViewType(position: Int): Int = 0

        /**
         * Payload-aware bind, matching Android's `onBindViewHolder(holder, position, payloads)`.
         * Called instead of the plain [onBindViewHolder] when [holder] is being rebound *in place*
         * (see [RecyclerView.drainPendingAdapterChanges]) for a [notifyItemChanged]/[notifyItemRangeChanged] call
         * that supplied a non-null payload - [payloads] is never empty when this is called. Lets an
         * adapter apply a specific, cheap partial update (e.g. "just re-render this one field")
         * instead of redoing the full bind. Default implementation ignores [payloads] and just
         * forwards to the plain [onBindViewHolder] - matching Android's default - so overriding this
         * is opt-in and only worthwhile where a genuinely cheaper partial path exists.
         */
        open fun onBindViewHolder(holder: VH, position: Int, payloads: List<Any>) = onBindViewHolder(holder, position)

        /**
         * API CHANGE: additive, no Android equivalent. When non-null, the RecyclerView keeps every
         * attached item Actor inserted in sorted order (per this comparator, comparing the individual
         * Actors directly - not the [ViewHolder]s that own them) within their shared container,
         * instead of simply appending on attach - e.g. for items whose views should interleave with
         * *other* items' by some ordering key (depth/z across overlapping content) regardless of the
         * arbitrary order recycling attaches/reattaches them in. A single per-adapter comparator (not
         * per-holder): the ordering rule is a property of what the adapter's items *mean*, not of any
         * one holder instance.
         *
         * Deliberately compares Actors, not holders: a holder whose [ViewHolder.getItemViews] returns
         * more than one Actor (e.g. [com.unciv.ui.screens.worldscreen.worldmap.HexTileAdapter]'s
         * per-tile layer wrappers) may need its *own* views to interleave with *other* holders' views
         * by some finer key than "which holder" (there, layer index primary, tile depth secondary) -
         * a holder-level comparator can only ever keep one holder's views grouped together, never
         * express that. An adapter whose comparator needs to know which holder (or which of that
         * holder's several views) a given Actor is can resolve that itself from its own bookkeeping -
         * see [HexTileAdapter]'s `wrapperInfo` for the pattern. Only Actors [RecyclerView] itself
         * already recognizes as *some* holder's item view ever reach this comparator (see
         * [attachHolderViews]/[Recycler.endAttachBatch]) - any other attached child (e.g.
         * [com.unciv.ui.screens.worldscreen.worldmap.WorldMapHolder.unitActionOverlays]) is invisible
         * to this sort and keeps whatever position it already has.
         *
         * Returning `null` (the default) means "no particular order": views are just appended,
         * matching plain scene2d/Android z-order (later-added = frontmost).
         */
        open fun getViewComparator(): Comparator<Actor>? = null

        fun registerAdapterDataObserver(observer: AdapterDataObserver) {
            if (observer !in observers) observers.add(observer)
        }

        fun unregisterAdapterDataObserver(observer: AdapterDataObserver) {
            observers.remove(observer)
        }

        internal fun createViewHolder(parent: RecyclerView, viewType: Int): ViewHolder {
            val holder = onCreateViewHolder(parent, viewType)
            holder.itemViewType = viewType
            return holder
        }

        @Suppress("UNCHECKED_CAST")
        internal fun bindViewHolder(holder: ViewHolder, position: Int, payloads: List<Any> = emptyList()) {
            holder.adapterPosition = position
            if (payloads.isEmpty()) onBindViewHolder(holder as VH, position)
            else onBindViewHolder(holder as VH, position, payloads)
        }

        // Iterate over a snapshot so an observer that unregisters itself during dispatch is safe.
        private inline fun forEachObserver(action: (AdapterDataObserver) -> Unit) {
            for (o in observers.toList()) action(o)
        }

        // API CHANGE: like Android's real `notify*` contract, every synchronous notify below must be
        // called on the render/GL thread. They fan out to observers, which (for the owning
        // RecyclerView) mutate unsynchronized recycler maps and touch scene2d Actors - none of which
        // is thread-safe. Android requires the UI thread for the same reason; keeping that
        // requirement here is honest, not a regression. To trigger a data change from a background
        // thread, use the async entry points ([notifyDataSetChangedAsync]) below, which do the
        // render-thread hop for you via [GdxMainDispatcher].
        fun notifyDataSetChanged() = forEachObserver { it.onChanged() }

        /** [payload]: API CHANGE-additive, matching Android's `notifyItemChanged(position, payload)`.
         *  `null` (the default) dispatches the plain [AdapterDataObserver.onItemRangeChanged]
         *  overload, same as before; non-null dispatches the payload overload instead, which
         *  [RecyclerView] uses to call [onBindViewHolder]'s payload-aware overload - see
         *  [RecyclerView.drainPendingAdapterChanges]. */
        fun notifyItemChanged(position: Int, payload: Any? = null) = notifyItemRangeChanged(position, 1, payload)

        /** @see notifyItemChanged */
        fun notifyItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any? = null) {
            if (payload == null) forEachObserver { it.onItemRangeChanged(positionStart, itemCount) }
            else forEachObserver { it.onItemRangeChanged(positionStart, itemCount, payload) }
        }
        fun notifyItemInserted(position: Int) = forEachObserver { it.onItemRangeInserted(position, 1) }
        fun notifyItemRangeInserted(positionStart: Int, itemCount: Int) = forEachObserver { it.onItemRangeInserted(positionStart, itemCount) }
        fun notifyItemRemoved(position: Int) = forEachObserver { it.onItemRangeRemoved(position, 1) }
        fun notifyItemRangeRemoved(positionStart: Int, itemCount: Int) = forEachObserver { it.onItemRangeRemoved(positionStart, itemCount) }
        fun notifyItemMoved(fromPosition: Int, toPosition: Int) = forEachObserver { it.onItemRangeMoved(fromPosition, toPosition, 1) }

        // region async notify (API CHANGE: additive - no Android equivalent on the bare Adapter)

        /**
         * API CHANGE: Android has no async `notify*` on a plain `Adapter` - only `ListAdapter`/
         * `AsyncListDiffer` diff off-thread. This generalizes that pattern to any [Adapter]: it is
         * safe to call from **any** thread. [computeOnBackground] runs on [Dispatchers.Default] (do
         * your data mutation - swap a backing list, recompute rows - there), then the apply step
         * (dispatching [notifyDataSetChanged] to observers, which relayouts the RecyclerView) is
         * always marshaled onto the render/GL thread via [GdxMainDispatcher] (`Gdx.app.postRunnable`)
         * - never run inline, never thread-detected (see [GdxMainDispatcher]).
         *
         * If [supportsBackgroundBinding] is true and a [RecyclerView] is attached, the create/bind of
         * the ViewHolders for the currently-visible positions also happens on the background thread
         * (producing fully-bound, still-detached Actors handed off through a thread-safe queue); only
         * the final scenegraph attach happens on the render thread inside the posted apply step. When
         * it is false (the default), no view work happens off-thread - only the diff/notify dispatch
         * is async, and all binding happens on the render thread in the normal synchronous layout pass
         * once the apply step has posted back.
         */
        fun notifyDataSetChangedAsync(computeOnBackground: () -> Unit = {}) {
            asyncNotifyScope.launch {
                computeOnBackground()
                val rv = recyclerView
                if (supportsBackgroundBinding && rv != null) {
                    // Read the target (currently-visible) positions on the render thread - the
                    // recycler maps must never be touched from a background thread.
                    val targets = withContext(GdxMainDispatcher) { rv.recycler.getPositions().toList() }
                    // Create + bind holders for those positions off the render thread. This only ever
                    // constructs new ViewHolder objects and mutates their (not-yet-attached) Actors;
                    // it does NOT touch any shared recycler map. The bound holders are handed back via
                    // the recycler's thread-safe prebound queue.
                    for (pos in targets) {
                        if (pos < 0 || pos >= getItemCount()) continue
                        val viewType = getItemViewType(pos)
                        val holder = createViewHolder(rv, viewType)
                        bindViewHolder(holder, pos)
                        rv.recycler.offerPrebound(holder)
                    }
                }
                withContext(GdxMainDispatcher) { notifyDataSetChanged() }
            }
        }

        /**
         * Opt-in flag: when `true`, this adapter promises its [onCreateViewHolder]/[onBindViewHolder]
         * are safe to invoke off the render/GL thread, letting [notifyDataSetChangedAsync] do view
         * creation/binding on a background coroutine.
         *
         * ### Safety contract (the adapter MUST uphold this when returning `true`)
         * When this is `true`, [onCreateViewHolder] and [onBindViewHolder] must **not**:
         * - create any GL object (a `Texture`, `TextureRegion` backed by a fresh `Texture`, `Mesh`,
         *   `Shader`, `FrameBuffer`, `Pixmap`-to-`Texture` upload, etc.),
         * - draw with, or otherwise touch, a `Batch`,
         * - load a font/`BitmapFont` that lazily uploads a glyph page, or touch anything else that
         *   requires a bound GL context.
         *
         * Plain scene2d construction and field assignment is fine: `new Table()`, `new Label(text,
         * style)` **provided the style's font/textures already exist**, setting text/positions/colors,
         * wiring listeners, reading model data. If in doubt, leave this `false` (the default): binding
         * then always happens on the render thread, and only the async notify *dispatch* is off-thread.
         */
        open val supportsBackgroundBinding: Boolean = false

        /** API CHANGE: additive. Background scope for the async notify path. A [SupervisorJob] so one
         * failed async update doesn't cancel the scope for subsequent ones. Not exposed as Android
         * has no equivalent; overridable point deliberately omitted to keep the surface small. */
        private val asyncNotifyScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // endregion
    }

    /**
     * Observer of adapter data changes, matching Android's `RecyclerView.AdapterDataObserver`.
     * Every callback defaults to routing through [onChanged]; subclasses override the granular ones
     * they care about. Register/unregister via [Adapter.registerAdapterDataObserver].
     */
    abstract class AdapterDataObserver {
        open fun onChanged() {}
        open fun onItemRangeChanged(positionStart: Int, itemCount: Int) = onChanged()
        /** API CHANGE: additive, matching Android's payload-aware notify overload. Default ignores
         *  [payload] and forwards to the plain two-arg overload; override this instead of (or in
         *  addition to) that one to observe payloads. */
        open fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) =
            onItemRangeChanged(positionStart, itemCount)
        open fun onItemRangeInserted(positionStart: Int, itemCount: Int) = onChanged()
        open fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = onChanged()
        open fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = onChanged()
    }

    /**
     * API CHANGE: greatly reduced from Android's LayoutManager (no measure specs, no
     * save/restore state, no SmoothScroller plumbing). What's kept, with matching names/roles:
     * [onLayoutChildren] (pulls views from [Recycler] and positions them, exactly like Android's
     * pattern), [canScrollVertically]/[canScrollHorizontally], [scrollVerticallyBy]/
     * [scrollHorizontallyBy] (return consumed delta, like Android), [generateDefaultLayoutParams],
     * and [scrollToPosition].
     */
    abstract class LayoutManager(val orientation: Orientation = Orientation.VERTICAL) {
        internal var recyclerView: RecyclerView? = null

        /** API CHANGE: Android computes/derives scroll offset from child view positions; here the
         * LayoutManager owns it directly as plain floats for simplicity. */
        var scrollOffsetX: Float = 0f
            protected set
        var scrollOffsetY: Float = 0f
            protected set

        open fun canScrollVertically(): Boolean = orientation == Orientation.VERTICAL
        open fun canScrollHorizontally(): Boolean = orientation == Orientation.HORIZONTAL

        /** Pulls the currently-visible (plus small buffer) positions from [recycler] and positions
         * their item actors. Must call [Recycler.recycleViewAt] for positions no longer visible. */
        abstract fun onLayoutChildren(recycler: Recycler, state: State)

        /** Returns the actual distance scrolled (Android convention: may be less than requested
         * at scroll-range limits). Default no-op for LayoutManagers that don't scroll vertically. */
        open fun scrollVerticallyBy(dy: Float, recycler: Recycler, state: State): Float = 0f

        /** @see scrollVerticallyBy */
        open fun scrollHorizontallyBy(dx: Float, recycler: Recycler, state: State): Float = 0f

        open fun scrollToPosition(position: Int) {}

        open fun generateDefaultLayoutParams(): LayoutParams = LayoutParams()

        /** Drops any cached per-item/per-row measurements after an adapter data change, so sizes are
         * re-measured lazily. No-op for managers that don't cache (the anchor-model default). */
        open fun invalidateSizeCache() {}

        /** Adapter position currently pinned to the leading viewport edge - used by
         * [smoothScrollToPosition] to detect arrival. Default 0 for non-scrolling managers. */
        open fun currentAnchorPosition(): Int = 0

        /** Sign of "[position] is after (+1) / at (0) / before (-1) the current anchor", used to
         * pick the direction a smooth scroll should step in. */
        open fun compareAnchorTo(position: Int): Int = 0

        /** Nominal per-step main-axis size used to scale smooth-scroll animation speed. */
        open fun mainStepSize(): Float = 50f
    }

    /**
     * A minimal, mutable, ready-to-assign [Layout] implementation, for a [ViewHolder] whose item
     * Actor reports no meaningful size of its own (either a bare, unconfigured `Widget` - which
     * reports 0 for every [Layout] property by default - or an Actor that isn't a [Layout] at all,
     * e.g. a plain [Group]) - or that wants to report different sizing than its item's own - to
     * assign directly to [ViewHolder.layoutParams]
     * (`holder.layoutParams = RecyclerView.LayoutParams(prefHeightValue = 40f)`) rather than needing to
     * write a whole custom [Layout] implementation just to specify a few numbers.
     *
     * API CHANGE: Android's `LayoutParams` carries margins and a Rect-like frame plus interacts with
     * Android's measure/layout passes; here it's reduced to exactly [Layout]'s six size properties,
     * since that's the only part of it anything in this library reads (a supporting LayoutManager's
     * per-item measurement - see [LinearLayoutManager.measure]) - measuring here can never involve a
     * real measure pass the way Android's `onMeasure` does, since that would mean binding an
     * off-screen item just to size it, defeating virtualization. [layout]/[invalidate]/
     * [invalidateHierarchy]/[validate]/[pack]/[setLayoutEnabled] are consequently all no-ops: nothing
     * in this library ever calls them - they're only implemented because [Layout] requires them.
     */
    open class LayoutParams(
        var minWidthValue: Float = 0f,
        var prefWidthValue: Float = 0f,
        var maxWidthValue: Float = 0f,
        var minHeightValue: Float = 0f,
        var prefHeightValue: Float = 0f,
        var maxHeightValue: Float = 0f
    ) : Layout {
        // Named *Value above and delegated to below, not `override var minWidth` etc. directly:
        // Layout is a plain Java interface (getMinWidth()/setLayoutEnabled()/... - getter-only, no
        // setters), and Kotlin can only override a Java interface method that way when the property
        // name and the method's implied name are otherwise unambiguous - a same-named `var` here would
        // generate its own `getMinWidth()` accessor that collides at the JVM level with this class's
        // own `override fun getMinWidth()` (both compile to the same signature) - a "platform
        // declaration clash" the compiler rejects outright.
        override fun getMinWidth() = minWidthValue
        override fun getMinHeight() = minHeightValue
        override fun getPrefWidth() = prefWidthValue
        override fun getPrefHeight() = prefHeightValue
        override fun getMaxWidth() = maxWidthValue
        override fun getMaxHeight() = maxHeightValue
        override fun layout() {}
        override fun invalidate() {}
        override fun invalidateHierarchy() {}
        override fun validate() {}
        override fun pack() {}
        override fun setLayoutEnabled(enabled: Boolean) {}
        override fun setFillParent(fillParent: Boolean) {}
    }

    /**
     * API CHANGE: Android's `RecyclerView.State` carries a lot of internal scroll-animation/
     * pre-layout bookkeeping (`isPreLayout`, `didStructureChange`, target scroll position, etc.)
     * used to coordinate ItemAnimator transitions across layout passes. Since no ItemAnimator is
     * implemented here, [State] is reduced to just the current item count - kept as a real
     * parameter (rather than dropped) so [LayoutManager] method signatures stay Android-shaped.
     */
    class State internal constructor() {
        var itemCount: Int = 0
            internal set
    }

    /**
     * API CHANGE: Android's Recycler has multiple internal caches (scrap, cached views, view
     * cache extension, recycled pool with a per-type max size). This keeps only the two that
     * matter for virtualization: currently-attached views (`activeViews`) and a simple unbounded
     * per-view-type pool of detached, unbound holders ready for reuse.
     */
    inner class Recycler internal constructor() {
        internal val activeViews = LinkedHashMap<Int, ViewHolder>()
        internal val childViewHolders = HashMap<Actor, ViewHolder>()
        private val scrapPool = HashMap<Int, MutableList<ViewHolder>>()
        /** Holders detached at the start of a layout pass ([scrapActiveViews]); a position still
         * visible is pulled back out here (no rebind), the rest are pooled by [recycleScrap]. This
         * mirrors Android's transient "scrap" and is what lets a survivor keep its binding while a
         * position leaving the window frees its holder for a position entering it in the same pass. */
        private val scrap = LinkedHashMap<Int, ViewHolder>()

        /** API CHANGE: additive - supports [Adapter.notifyDataSetChangedAsync] with
         * [Adapter.supportsBackgroundBinding]. Holders created **and bound on a background thread**
         * are handed off here for the render thread to attach. This is the ONE recycler structure a
         * background thread ever touches, and only via [offerPrebound] (a `ConcurrentLinkedQueue.add`,
         * which is thread-safe); every other recycler map (`activeViews`/`scrapPool`/`scrap`/
         * `childViewHolders`) is render-thread-only. Consumed (and any leftover cleared) on the render
         * thread during the layout pass - see [getViewForPosition] and [recycleScrap]. */
        private val preboundHolders = ConcurrentLinkedQueue<ViewHolder>()

        /** Background-thread-safe handoff of a fully-created+bound holder to the render thread. The
         * only [Recycler] method that may be called off the render thread. */
        internal fun offerPrebound(holder: ViewHolder) {
            preboundHolders.add(holder)
        }

        /** Render-thread-only: claims a prebound holder matching [position] and [viewType], or null. */
        private fun takePreboundFor(position: Int, viewType: Int): ViewHolder? {
            val it = preboundHolders.iterator()
            while (it.hasNext()) {
                val holder = it.next()
                if (holder.adapterPosition == position && holder.itemViewType == viewType) {
                    it.remove()
                    return holder
                }
            }
            return null
        }

        /** Moves every currently-attached holder into [scrap] (staying bound and attached) at the
         * start of a layout pass; call [recycleScrap] at the end to pool whatever wasn't reclaimed. */
        internal fun scrapActiveViews() {
            scrap.putAll(activeViews)
            activeViews.clear()
        }

        /** Removes and returns any scrapped holder of [viewType] (a holder leaving the window that
         * can be rebound for an entering position), or null if none remain. Exact-position survivors
         * are claimed earlier in [getHolderForPosition], so this only ever hands back leaving holders. */
        private fun removeScrapOfType(viewType: Int): ViewHolder? {
            val entry = scrap.entries.firstOrNull { it.value.itemViewType == viewType } ?: return null
            scrap.remove(entry.key)
            return entry.value
        }

        /** Non-null while a batch started by [beginAttachBatch] is in progress: newly-attached views
         *  that would otherwise each cost their own O(childCount) comparator scan in [attachHolderViews]
         *  are instead collected here, to be inserted all at once by [endAttachBatch] in a single
         *  sort + merge pass. Null (the default) means [attachHolderViews] inserts immediately, as
         *  before - the batch is opt-in per call site (see [drainPendingAdapterChanges]). */
        private var pendingBatchAttach: MutableList<Actor>? = null

        /** Starts deferring newly-attached views' comparator-sorted insertion into [pendingBatchAttach]
         *  instead of [attachHolderViews] inserting each one immediately - callers must pair this with
         *  [endAttachBatch] once every holder in the batch has been (re)bound. A no-op (and leaves
         *  [pendingBatchAttach] `null`) when [Adapter.getViewComparator] is null: with no comparator,
         *  [attachHolderViews] already just appends, so there is no per-view scan to batch away. */
        internal fun beginAttachBatch() {
            if (adapter?.getViewComparator() == null) return
            pendingBatchAttach = mutableListOf()
        }

        /** Ends a batch started by [beginAttachBatch]. Sorts every deferred view once by
         *  [Adapter.getViewComparator], then inserts them all in a single left-to-right merge against
         *  the already-attached (and already correctly sorted, by the same invariant this maintains on
         *  every insertion) siblings - O(N + K log K) total for K deferred views among N existing
         *  siblings, instead of the O(N) scan [attachHolderViews] would otherwise repeat once per view
         *  (O(N*K)) across the same batch. Only Actors already known to be *some* holder's item view
         *  (i.e. present in [childViewHolders]) count as existing siblings to merge against - any other
         *  attached child is invisible to the scan and keeps its current slot untouched (see
         *  [Adapter.getViewComparator]'s KDoc). No-op if [beginAttachBatch] wasn't called, or found no
         *  comparator to batch (see its KDoc). */
        internal fun endAttachBatch() {
            val pending = pendingBatchAttach ?: return
            pendingBatchAttach = null
            if (pending.isEmpty()) return
            val comparator = adapter?.getViewComparator()
            if (comparator == null) { // comparator removed mid-batch (adapter swapped) - just append
                for (view in pending) addActor(view)
                return
            }
            val sorted = pending.sortedWith(comparator)
            var searchFrom = 0
            for (view in sorted) {
                var index = -1
                var i = searchFrom
                while (i < children.size) {
                    val child = children[i]
                    if (childViewHolders[child] != null && comparator.compare(view, child) < 0) { index = i; break }
                    i++
                }
                if (index < 0) {
                    addActor(view)
                    searchFrom = children.size // nothing after the new tail needs scanning again
                } else {
                    addActorAt(index, view)
                    searchFrom = index + 1
                }
            }
        }

        /** Attaches every one of [holder]'s [ViewHolder.getItemViews] that isn't already attached. If
         * [Adapter.getViewComparator] is non-null, each view is inserted at whatever index keeps
         * already-attached item-view Actors sorted by it instead of simply appended - see that
         * method's KDoc (this deliberately compares each of [holder]'s views against other Actors
         * directly, not [holder] itself against other holders, so two of *this same holder's* views
         * can be correctly interleaved with each other too, not just kept grouped together). Comparing
         * against every attached child is
         * O(childCount) per view; fine for this library's list/grid use cases, but a caller with very
         * large sorted sets and its own indexing should not lean on this for insertion. Between
         * [beginAttachBatch] and [endAttachBatch], a newly-attached view is deferred into the batch
         * instead - see those methods' KDoc. */
        private fun attachHolderViews(holder: ViewHolder) {
            val comparator = adapter?.getViewComparator()
            for (view in holder.getItemViews()) {
                childViewHolders[view] = holder
                if (view.parent === this@RecyclerView) continue
                if (comparator == null) {
                    addActor(view)
                    continue
                }
                val batch = pendingBatchAttach
                if (batch != null) {
                    batch.add(view)
                    continue
                }
                val index = children.indexOfFirst { child ->
                    childViewHolders[child] != null && comparator.compare(view, child) < 0
                }
                if (index < 0) addActor(view) else addActorAt(index, view)
            }
        }

        /** Detaches every one of [holder]'s [ViewHolder.getItemViews] and forgets their lookup entries. */
        private fun detachHolderViews(holder: ViewHolder) {
            for (view in holder.getItemViews()) {
                childViewHolders.remove(view)
                removeActor(view)
            }
        }

        /** Rebinds the already-active holder at [position] in place for a same-position data change
         *  (see [RecyclerView.drainPendingAdapterChanges]) - unlike [recycleViewAt]-based recycling, this never
         *  detaches the holder from the scenegraph and never touches any other holder.
         *
         *  If [Adapter.getItemViewType] for [position] no longer matches the holder's existing
         *  [ViewHolder.itemViewType] (view type isn't expected to change under a plain
         *  `notifyItemChanged` - same assumption Android makes), falls back to evicting it via
         *  [recycleViewAt] instead: the next layout pass then creates/binds a fresh holder of the
         *  correct type for that position, same as any other position becoming newly visible.
         *
         *  Otherwise: snapshots [ViewHolder.getItemViews] *before* rebinding, then calls
         *  [Adapter.bindViewHolder] with [payloads] (empty means a full bind via the plain
         *  [Adapter.onBindViewHolder]; non-empty calls its payload-aware overload instead - see
         *  [RecyclerView.drainPendingAdapterChanges]), then diffs against the *post*-bind list: any Actor no
         *  longer present is detached and forgotten; any newly-added Actor is attached via
         *  [attachHolderViews] (respecting [Adapter.getViewComparator] like a fresh attach would).
         *  Actors present in both lists are left exactly where they are - still attached, still
         *  positioned wherever the LayoutManager last placed them - until the pending layout pass
         *  (triggered by the caller) re-measures/re-places them.
         *
         *  The bind-time animation hook (clearing actions/resetting alpha, same contract as a pooled
         *  rebind - see [Adapter]'s KDoc) only runs for a full ([payloads] empty) bind: a payload-based
         *  partial update is meant to be minimally disruptive, so leftover actions/alpha from an
         *  unrelated in-flight animation are deliberately left alone. */
        internal fun rebindInPlace(position: Int, adapter: Adapter<ViewHolder>, payloads: List<Any> = emptyList()) {
            val holder = activeViews[position] ?: return
            if (adapter.getItemViewType(position) != holder.itemViewType) {
                recycleViewAt(position)
                return
            }
            val before = holder.getItemViews().toList()
            if (payloads.isEmpty()) {
                for (view in before) {
                    view.clearActions()
                    view.color.a = 1f
                }
            }
            adapter.bindViewHolder(holder, position, payloads)
            val after = holder.getItemViews()
            for (view in before) {
                if (view !in after) {
                    childViewHolders.remove(view)
                    removeActor(view)
                }
            }
            attachHolderViews(holder)
        }

        /** Pools every holder still in [scrap] (i.e. no longer visible) after a layout pass. */
        internal fun recycleScrap() {
            for (holder in scrap.values) {
                detachHolderViews(holder)
                scrapPool.getOrPut(holder.itemViewType) { mutableListOf() }.add(holder)
            }
            scrap.clear()
            // Drop any prebound holders this layout pass didn't consume (e.g. a position that
            // scrolled out before layout, or a superseded concurrent async update). Their Actors were
            // never attached, so discarding them is safe and keeps stale-bound holders from being
            // picked up for a later, unrelated data change.
            preboundHolders.clear()
        }

        /** Returns the item actor for [position] (the first entry of [ViewHolder.getItemViews] - the
         * common single-view case), creating/binding (or reusing from the pool) as needed. For a
         * holder binding more than one Actor, use [getViewsForPosition] instead. */
        fun getViewForPosition(position: Int): Actor = getHolderForPosition(position).getItemViews()[0]

        /** @see getViewForPosition - returns every Actor the holder binds ([ViewHolder.getItemViews]). */
        fun getViewsForPosition(position: Int): List<Actor> = getHolderForPosition(position).getItemViews()

        /** Returns the [ViewHolder] for [position], creating/binding (or reusing from the pool) as
         * needed, and attaching all of its views (see [attachHolderViews]). */
        fun getHolderForPosition(position: Int): ViewHolder {
            val a = adapter ?: error("RecyclerView has no adapter attached")
            activeViews[position]?.let { return it }
            // A holder for this exact position survived from the previous layout - reuse as-is,
            // no rebind (its content is already correct).
            scrap.remove(position)?.let { holder ->
                activeViews[position] = holder
                return holder
            }

            val viewType = a.getItemViewType(position)
            // A holder for this position was created AND bound on a background thread (async notify
            // with supportsBackgroundBinding); it arrives fully bound, so we skip create+bind and
            // only do the render-thread scenegraph attach. We do NOT clearActions()/reset alpha here:
            // unlike a pooled holder there is no leftover state to wipe, and clearing would kill any
            // fresh appear animation onBindViewHolder queued during the background bind.
            takePreboundFor(position, viewType)?.let { holder ->
                activeViews[position] = holder
                attachHolderViews(holder)
                return holder
            }
            // Otherwise reclaim a same-type holder that is leaving the window this pass (rebind it
            // for the new position) before touching the pool - this is what recycles a holder
            // scrolling off one edge into a position scrolling on at the other edge in one pass.
            val stolen = removeScrapOfType(viewType)
            val pooled = stolen
                ?: scrapPool[viewType]?.let { if (it.isNotEmpty()) it.removeAt(it.size - 1) else null }
            val holder = pooled ?: a.createViewHolder(this@RecyclerView, viewType)
            // Bind-time animation hook: wipe leftover state from every one of this holder's views
            // before rebinding so onBindViewHolder starts clean and can freely addAction() a fresh
            // appear/update animation (see Adapter's KDoc). We clear actions (a fade/move Action left
            // mid-flight when the holder was recycled would otherwise resume on the new item), and
            // reset alpha to fully-opaque (a fadeIn interrupted at alpha 0.3 would leave the reused
            // view stuck translucent). We deliberately do NOT reset the full color/scale/rotation:
            // those are commonly set once in onCreateViewHolder or every bind by the adapter, and
            // clobbering them here would fight legitimate per-type styling; alpha is the one field
            // the built-in fade helpers touch, so resetting just alpha is the safe minimum.
            for (view in holder.getItemViews()) {
                view.clearActions()
                view.color.a = 1f
            }
            a.bindViewHolder(holder, position)

            activeViews[position] = holder
            attachHolderViews(holder)
            return holder
        }

        /** Detaches the view at [position] (if attached) and returns its holder to the pool. */
        fun recycleViewAt(position: Int) {
            val holder = activeViews.remove(position) ?: return
            detachHolderViews(holder)
            scrapPool.getOrPut(holder.itemViewType) { mutableListOf() }.add(holder)
        }

        /** Detaches [view] and returns its holder to the pool, matching Android's signature. */
        fun recycleView(view: Actor) {
            val holder = childViewHolders[view] ?: return
            recycleViewAt(holder.adapterPosition)
        }

        fun getPositions(): Set<Int> = activeViews.keys

        internal fun clear() {
            activeViews.values.forEach { detachHolderViews(it) }
            scrap.values.forEach { detachHolderViews(it) }
            activeViews.clear()
            scrap.clear()
            childViewHolders.clear()
            scrapPool.clear()
            preboundHolders.clear()
        }

        /** Forces every currently-attached holder to be re-bound on the next [getViewForPosition]
         * call for its position, by evicting it back to the pool - used after notifyDataSetChanged. */
        internal fun invalidateBindings() {
            activeViews.keys.toList().forEach { recycleViewAt(it) }
        }
    }

    /**
     * API CHANGE: Android's ItemDecoration.getItemOffsets receives the child `View` itself
     * (`getItemOffsets(outRect, view, parent, state)`); here it receives the adapter `position`
     * directly since positions - not Views - are the stable identity while scrolling.
     * Canvas/Paint drawing becomes a scene2d [Batch].
     */
    abstract class ItemDecoration {
        open fun getItemOffsets(outRect: Rectangle, position: Int, parent: RecyclerView, state: State) {}
        open fun onDraw(batch: Batch, parent: RecyclerView, state: State) {}
        open fun onDrawOver(batch: Batch, parent: RecyclerView, state: State) {}
    }

    // endregion

    companion object {
        const val INVALID_TYPE = -1
        const val NO_POSITION = -1

        /** Smooth-scroll speed factor: fraction of [LayoutManager.mainStepSize] traversed per second
         * is roughly this * mainStepSize(); tuned for a snappy-but-visible glide. */
        private const val SMOOTH_SCROLL_SPEED = 12f
        /** Fraction of a drag past the edge that actually translates content (rubber-band feel). */
        private const val OVERSCROLL_RESISTANCE = 0.5f
        /** Per-second exponential decay rate the overscroll settles back to zero at after release. */
        private const val OVERSCROLL_SETTLE_RATE = 12f
        /** Overscroll is capped at this fraction of the viewport's main-axis size. */
        private const val MAX_OVERSCROLL_FRACTION = 0.35f

        /** Weight (0..1) of each new [touchDragged] sample in the running [velocityX]/[velocityY]
         *  exponential moving average - higher tracks the latest motion more closely, lower rides
         *  out jitter from a single noisy sample more (see those properties' KDoc). */
        private const val VELOCITY_SMOOTHING = 0.6f
        /** Minimum release speed (px/sec) for [touchUp] to start a fling at all - below this, a slow
         *  drag release just stops, matching Android's `ViewConfiguration` fling-velocity threshold. */
        private const val MIN_FLING_VELOCITY = 50f
        /** Per-second decay rate [flingVelocityX]/[flingVelocityY] settle toward zero at - same
         *  clamped-linear shape as [OVERSCROLL_SETTLE_RATE], just slower, so a fling coasts noticeably
         *  rather than stopping the instant the finger lifts. */
        private const val FLING_FRICTION = 2.5f
        /** Distance (px) from a stage edge that counts as "near it" for [isAutoScrollEnabled]. */
        private const val EDGE_AUTO_SCROLL_MARGIN = 5f
    }
}
