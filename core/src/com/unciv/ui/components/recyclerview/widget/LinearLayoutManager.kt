package com.unciv.ui.components.recyclerview.widget

import com.badlogic.gdx.scenes.scene2d.utils.Layout
import kotlin.math.max
import kotlin.math.min

/**
 * Lays items out one after another along [RecyclerView.Orientation.VERTICAL] (top-to-bottom) or
 * [RecyclerView.Orientation.HORIZONTAL] (left-to-right), matching Android's LinearLayoutManager
 * naming, orientation semantics, and `findFirstVisibleItemPosition`/`findLastVisibleItemPosition`
 * query methods.
 *
 * ### Per-item measurement (anchor model)
 * API CHANGE: Android's LinearLayoutManager measures every child with a fill-viewport scrap-view
 * measure pass. Measuring here needs a bound [RecyclerView.ViewHolder] (see [measure]), so we cannot
 * size an item before it is visible without defeating virtualization. Instead scroll position is
 * tracked as an **anchor** - the adapter position pinned to the leading (top/left) viewport edge
 * ([anchorPosition]) plus a float [scrollIntoAnchor] saying how many pixels we've scrolled into that
 * anchor item, both always exact (never estimated): the anchor is always a currently-bound, actually
 * -measured holder. Layout measures the anchor, places it, then *walks outward* measuring and placing
 * one neighbour at a time (see [measure]: [RecyclerView.ViewHolder.layoutParams] has a real size to
 * report if the item Actor implements [Layout] (or the holder explicitly overrides it), else it
 * measures as 0 - see [measure]'s KDoc) until the viewport (+ buffer) is filled. Scrolling consumes
 * the delta against the anchor item's measured size, advancing/retreating the anchor across item
 * boundaries.
 *
 * API CHANGE: because visible geometry depends only on the anchor and the measured chain walking out
 * from it - never on a global cumulative offset table - a size change to an *off-screen* item does not
 * disturb the current visible layout, and we never bind an off-screen item just to size it.
 * [scrollOffsetY]/[scrollOffsetX] (see [syncDerivedOffset]) are published in **position units**, not
 * pixels-from-the-start-of-the-list: `anchorPosition + scrollIntoAnchor / anchorSize`. This is
 * deliberate, not a simplification-for-now - a pixel-from-start value would need the cumulative size
 * of every item *before* the anchor, which virtualization deliberately never measures, forcing an
 * estimate; expressing the same information in position units instead needs nothing but the anchor's
 * own already-exact state, so it's exact unconditionally, for any mix of item sizes. End-of-list
 * clamping ([clampAnchorToEnd]) is the one place this class ever measures items purely to bound
 * scrolling (not to place something visible) - and even that is real measurement of only the (at
 * most one viewport's worth of) trailing items actually needed, discovered reactively (mirroring how
 * Android's own LinearLayoutManager discovers the end: by running out of items mid-fill, not from a
 * precomputed total), never a whole-list scan or a size estimate.
 */
open class LinearLayoutManager(
    orientation: RecyclerView.Orientation = RecyclerView.Orientation.VERTICAL
) : RecyclerView.LayoutManager(orientation) {

    /** Number of extra off-screen items kept alive on each side, to smooth scrolling. */
    var bufferItemCount: Int = 1

    /** Adapter position pinned to the leading (top for VERTICAL, left for HORIZONTAL) viewport edge. */
    var anchorPosition = 0
        private set
    /** Pixels scrolled into [anchorPosition] past the leading edge (0..anchor item's measured size). */
    private var scrollIntoAnchor = 0f

    private var firstVisible = RecyclerView.NO_POSITION
    private var lastVisible = RecyclerView.NO_POSITION

    fun findFirstVisibleItemPosition(): Int = firstVisible
    fun findLastVisibleItemPosition(): Int = lastVisible

    override fun currentAnchorPosition(): Int = anchorPosition
    override fun compareAnchorTo(position: Int): Int = position.compareTo(anchorPosition)

    /** The anchor's own real measured size (exact - it's always a currently-bound holder), or 0f if
     *  there's no adapter/items yet to measure. Used to scale smooth-scroll speed - deliberately the
     *  anchor's actual size, not an average, so the step size some large item won't make
     *  smooth-scrolling suddenly lurch. */
    override fun mainStepSize(): Float {
        val rv = recyclerView ?: return 0f
        val itemCount = rv.getAdapter()?.getItemCount() ?: return 0f
        if (itemCount <= 0) return 0f
        return sizeOf(anchorPosition.coerceIn(0, itemCount - 1), rv.recycler)
    }

    /** No per-position size table is kept (anchor model), so nothing to invalidate here beyond
     * re-clamping the anchor, which the next layout pass does. Kept for API symmetry. */
    override fun invalidateSizeCache() { /* no global cache in the anchor model */ }

    /** Main-axis size [holder] should be measured at: [RecyclerView.ViewHolder.layoutParams]'s
     *  `prefHeight`/`prefWidth` (matching [orientation]), clamped to its `minHeight`/`minWidth` and
     *  `maxHeight`/`maxWidth` if those are positive. [layoutParams] falls back to the item Actor's own
     *  reported size only if that Actor implements [Layout] (e.g. a `Widget`/`WidgetGroup`), unless
     *  the holder explicitly overrides it - see that property's KDoc; an item Actor that doesn't
     *  implement [Layout] at all, or a non-positive/unreported `prefHeight`/`prefWidth` (an
     *  unconfigured `Widget`'s default), both simply measure as 0 - there is no further fallback
     *  constant here, unlike Android's own `View`s, which always have *some* layout-derived size.
     *  Callers that want a uniform item size are expected to actually give their item Actors that
     *  size (e.g. via [RecyclerView.LayoutParams], or a `Widget`'s own `setSize`/pref overrides), the
     *  same as any other libGDX UI code would. */
    protected fun measure(holder: RecyclerView.ViewHolder): Float {
        val layout = holder.layoutParams
        val vertical = orientation == RecyclerView.Orientation.VERTICAL
        val pref = if (vertical) layout?.prefHeight else layout?.prefWidth
        var size = pref?.takeIf { it > 0f } ?: 0f
        val minBound = if (vertical) layout?.minHeight else layout?.minWidth
        val maxBound = if (vertical) layout?.maxHeight else layout?.maxWidth
        if (minBound != null && minBound > 0f) size = max(size, minBound)
        if (maxBound != null && maxBound > 0f) size = min(size, maxBound)
        return size
    }

    private fun sizeOf(position: Int, recycler: RecyclerView.Recycler): Float =
        measure(recycler.getHolderForPosition(position))

    /** Cross-axis (perpendicular to [orientation]) counterpart of [measure] - same fallback rules
     *  (0 unless the item Actor implements [Layout] or the holder overrides [layoutParams]). Used
     *  for cross-axis scrolling (see [crossOverflow]): an item wider (VERTICAL) or taller
     *  (HORIZONTAL) than the viewport is otherwise stretched to fill it exactly like every other
     *  item - see [placeItem] - so this is the one place that item's *own* cross size matters. */
    protected fun measureCross(holder: RecyclerView.ViewHolder): Float {
        val layout = holder.layoutParams
        val vertical = orientation == RecyclerView.Orientation.VERTICAL
        val pref = if (vertical) layout?.prefWidth else layout?.prefHeight
        var size = pref?.takeIf { it > 0f } ?: 0f
        val minBound = if (vertical) layout?.minWidth else layout?.minHeight
        val maxBound = if (vertical) layout?.maxWidth else layout?.maxHeight
        if (minBound != null && minBound > 0f) size = max(size, minBound)
        if (maxBound != null && maxBound > 0f) size = min(size, maxBound)
        return size
    }

    /** Pixels the anchor's own measured cross size currently overflows the viewport's cross-axis
     *  size by, or 0 if it fits (or there's no adapter/anchor yet) - the one case this class scrolls
     *  the cross axis at all (see the class doc). Always recomputed from the *current* anchor, never
     *  cached: which item is the anchor - and its cross size - can change on every layout pass, same
     *  as [mainStepSize]. */
    private fun crossOverflow(): Float {
        val rv = recyclerView ?: return 0f
        val itemCount = rv.getAdapter()?.getItemCount() ?: return 0f
        if (itemCount <= 0) return 0f
        val vertical = orientation == RecyclerView.Orientation.VERTICAL
        val viewportCross = if (vertical) rv.width else rv.height
        val anchorCross = measureCross(rv.recycler.getHolderForPosition(anchorPosition.coerceIn(0, itemCount - 1)))
        return (anchorCross - viewportCross).coerceAtLeast(0f)
    }

    /** Pixels currently scrolled into the cross axis, `0..crossOverflow()` - see [scrollCrossBy]. */
    private var crossOffset = 0f

    override fun canScrollVertically(): Boolean =
        orientation == RecyclerView.Orientation.VERTICAL || crossOverflow() > 0f
    override fun canScrollHorizontally(): Boolean =
        orientation == RecyclerView.Orientation.HORIZONTAL || crossOverflow() > 0f

    /** Scrolls the cross axis by [delta], clamped to `[-crossOverflow(), 0]` (content can't scroll
     *  past either cross-axis edge) - see the class doc's cross-axis note. Unlike the main axis,
     *  this needs no anchor/boundary-crossing model: every item shares the same [crossOffset], so a
     *  plain clamped accumulator is already exact, not an estimate. */
    private fun scrollCrossBy(delta: Float, recycler: RecyclerView.Recycler, state: RecyclerView.State): Float {
        val overflow = crossOverflow()
        if (overflow <= 0f) { crossOffset = 0f; return 0f }
        val before = crossOffset
        crossOffset = (crossOffset + delta).coerceIn(0f, overflow)
        val consumed = crossOffset - before
        if (consumed != 0f) onLayoutChildren(recycler, state)
        return consumed
    }

    /** Publishes the anchor state as an exact **position-units** value (see the class doc): the
     *  integer part is [anchorPosition], the fractional part is how far scrolled into it
     *  ([scrollIntoAnchor] divided by the anchor's own real measured size). Never an estimate. */
    private fun syncDerivedOffset(recycler: RecyclerView.Recycler) {
        val anchorSize = sizeOf(anchorPosition, recycler).coerceAtLeast(MIN_MEASURED_SIZE)
        val estimate = anchorPosition + scrollIntoAnchor / anchorSize
        if (orientation == RecyclerView.Orientation.VERTICAL) scrollOffsetY = estimate else scrollOffsetX = estimate
    }

    /** Real (never estimated) end-of-list anchor state: walks backward from the very last item,
     *  accumulating actual measured sizes, until the accumulated span reaches [viewportMain] (or
     *  position 0) - the furthest the anchor can ever legitimately be scrolled to, i.e. "the last
     *  item's trailing edge exactly at the viewport's trailing edge." Bounded to at most one
     *  viewport's worth of trailing items - never a whole-list scan. Only called by [clampAnchorToEnd]
     *  once it's already established (cheaply - see that method) that the current anchor represents
     *  scrolling past this point. */
    private fun endOfListAnchorState(recycler: RecyclerView.Recycler, itemCount: Int, viewportMain: Float): Pair<Int, Float> {
        var pos = itemCount - 1
        var total = 0f
        while (true) {
            total += sizeOf(pos, recycler)
            if (total >= viewportMain || pos == 0) return pos to (total - viewportMain).coerceAtLeast(0f)
            pos--
        }
    }

    /** Clamps [anchorPosition]/[scrollIntoAnchor] if they currently represent scrolling past the end
     *  of the list, and returns how many pixels of over-scroll that removed (0f if none was needed) -
     *  used by [normalizeAnchor] to report an accurate consumed-scroll delta back to its caller.
     *
     *  Cost profile mirrors Android's own LinearLayoutManager, not a whole-list-size estimate: first
     *  does the *same* forward walk [onLayoutChildren] would do anyway (bounded by [viewportMain],
     *  i.e. proportional to the visible window, not the list) to see whether filling forward from the
     *  current anchor runs out of items before filling the viewport; only in that case (genuinely
     *  near the end) does it fall through to the bounded backward scan in [endOfListAnchorState]. */
    private fun clampAnchorToEnd(recycler: RecyclerView.Recycler, itemCount: Int, viewportMain: Float): Float {
        var pos = anchorPosition
        var filled = -scrollIntoAnchor
        while (pos < itemCount && filled < viewportMain) {
            filled += sizeOf(pos, recycler)
            pos++
        }
        if (pos < itemCount) return 0f // filled the viewport before running out of items - not near the end
        val shortfall = viewportMain - filled
        if (shortfall <= 0f) return 0f // exactly filled (or overfilled) - no gap
        val (endAnchor, endScrollInto) = endOfListAnchorState(recycler, itemCount, viewportMain)
        anchorPosition = endAnchor
        scrollIntoAnchor = endScrollInto
        return shortfall
    }

    /** Normalizes [anchorPosition]/[scrollIntoAnchor] (already bumped by [requestedDelta] before this
     *  is called) across item boundaries after a scroll, clamping the *start* of the list (position 0,
     *  scrollIntoAnchor can't go negative) - both via real measurement, never an estimate - and
     *  returns how much of [requestedDelta] was actually consumed, less than requested if that start
     *  clamp kicked in.
     *
     *  Deliberately does *not* also clamp the *end* of the list here (unlike the start clamp, which
     *  only ever touches [anchorPosition]'s already-active neighbours one boundary-crossing at a time
     *  - safe): the end clamp ([clampAnchorToEnd]) can probe positions far from any currently-active
     *  one, which is only safe to do *after* [Recycler.scrapActiveViews] has freed up the old anchor's
     *  holder for reuse - otherwise a probed position permanently forfeits reusing it, binding a
     *  fresh holder instead. [onLayoutChildren] (called unconditionally right after this, when
     *  [requestedDelta] was actually consumed) applies that clamp in the correct order instead - see
     *  its KDoc. The returned `consumed` therefore doesn't account for an end-of-list clamp; that's an
     *  accepted minor imprecision in the overscroll rubber-band feedback right at the trailing edge,
     *  never a correctness issue in what's actually rendered (which [onLayoutChildren] always gets
     *  right regardless). */
    private fun normalizeAnchor(recycler: RecyclerView.Recycler, itemCount: Int, requestedDelta: Float): Float {
        var consumed = requestedDelta
        // Advance the anchor forward across item boundaries the drag scrolled past.
        while (anchorPosition < itemCount - 1) {
            val size = sizeOf(anchorPosition, recycler)
            if (scrollIntoAnchor < size) break
            scrollIntoAnchor -= size
            anchorPosition++
        }
        // Retreat the anchor backward if the drag scrolled above the current anchor item.
        while (scrollIntoAnchor < 0f && anchorPosition > 0) {
            anchorPosition--
            scrollIntoAnchor += sizeOf(anchorPosition, recycler)
        }
        if (anchorPosition <= 0 && scrollIntoAnchor < 0f) {
            consumed += scrollIntoAnchor // scrollIntoAnchor is negative here: the start-of-list overshoot
            anchorPosition = 0
            scrollIntoAnchor = 0f
        }
        return consumed
    }

    /** Applies the same computed bounds to every one of [holder]'s [RecyclerView.ViewHolder.getItemViews] -
     *  they're treated as overlapping layers occupying one item "slot", not independently placed.
     *  Cross-axis size is normally the viewport's own cross size (every item stretches to fill it,
     *  as before cross-axis scrolling existed) - but grows to the item's own [measureCross] if that's
     *  bigger, offset by [crossOffset], so an item wider (VERTICAL) or taller (HORIZONTAL) than the
     *  viewport becomes cross-scrollable instead of simply overflowing/clipping - see the class doc's
     *  cross-axis note and [crossOverflow]. */
    private fun placeItem(rv: RecyclerView, holder: RecyclerView.ViewHolder, position: Int, mainStart: Float, size: Float, vertical: Boolean) {
        val inset = rv.getItemDecorationInsetsForPosition(position)
        val viewportCross = if (vertical) rv.width else rv.height
        val crossSize = max(viewportCross, measureCross(holder))
        val x: Float; val y: Float; val w: Float; val h: Float
        if (vertical) {
            x = inset.x - crossOffset
            y = rv.height - mainStart - size + inset.y
            w = crossSize - inset.x - inset.width
            h = size - inset.y - inset.height
        } else {
            x = mainStart + inset.x
            y = inset.y - crossOffset
            w = size - inset.x - inset.width
            h = crossSize - inset.y - inset.height
        }
        for (view in holder.getItemViews()) view.setBounds(x, y, w, h)
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        val rv = recyclerView ?: return
        val itemCount = state.itemCount
        if (itemCount <= 0) {
            recycler.scrapActiveViews()
            recycler.recycleScrap()
            firstVisible = RecyclerView.NO_POSITION
            lastVisible = RecyclerView.NO_POSITION
            return
        }
        if (anchorPosition > itemCount - 1) { anchorPosition = itemCount - 1; scrollIntoAnchor = 0f }

        val vertical = orientation == RecyclerView.Orientation.VERTICAL
        val viewportMain = if (vertical) rv.height else rv.width

        // Detach the current window so items that stay visible are reused as-is, and items leaving
        // free their holders (into the pool via recycleScrap below) for items entering this pass.
        // MUST happen before clampAnchorToEnd below: that can probe positions other than the
        // currently-active anchor (see its KDoc), which can only correctly recycle the old anchor's
        // holder once scrapActiveViews() has actually freed it up - probing first would permanently
        // forfeit that reuse, binding a fresh holder instead of recycling one.
        recycler.scrapActiveViews()
        clampAnchorToEnd(recycler, itemCount, viewportMain)

        // Buffer size is a multiple of the anchor's own real measured size, not a fixed constant -
        // there's no itemSize fallback to fall back on (see measure()'s KDoc).
        val bufferPx = bufferItemCount * sizeOf(anchorPosition, recycler)
        val visible = LinkedHashSet<Int>()

        // Walk forward from the anchor, measuring and placing each item, until the viewport +
        // trailing buffer is filled. Boundary items sitting exactly on the far edge are included,
        // matching the original uniform-size formula's inclusive boundary.
        var pos = anchorPosition
        var mainStart = -scrollIntoAnchor
        while (pos < itemCount && mainStart <= viewportMain + bufferPx) {
            val holder = recycler.getHolderForPosition(pos)
            val size = measure(holder)
            placeItem(rv, holder, pos, mainStart, size, vertical)
            visible.add(pos)
            mainStart += size
            pos++
        }
        // Walk backward from the anchor for the leading buffer region.
        pos = anchorPosition - 1
        var bottom = -scrollIntoAnchor
        while (pos >= 0 && bottom > -bufferPx) {
            val holder = recycler.getHolderForPosition(pos)
            val size = measure(holder)
            val top = bottom - size
            placeItem(rv, holder, pos, top, size, vertical)
            visible.add(pos)
            bottom = top
            pos--
        }

        recycler.recycleScrap()
        firstVisible = visible.minOrNull() ?: RecyclerView.NO_POSITION
        lastVisible = visible.maxOrNull() ?: RecyclerView.NO_POSITION
        syncDerivedOffset(recycler)
    }

    override fun scrollVerticallyBy(dy: Float, recycler: RecyclerView.Recycler, state: RecyclerView.State): Float {
        if (!canScrollVertically()) return 0f
        return if (orientation == RecyclerView.Orientation.VERTICAL) scrollMainBy(dy, recycler, state)
        else scrollCrossBy(dy, recycler, state)
    }

    override fun scrollHorizontallyBy(dx: Float, recycler: RecyclerView.Recycler, state: RecyclerView.State): Float {
        if (!canScrollHorizontally()) return 0f
        return if (orientation == RecyclerView.Orientation.HORIZONTAL) scrollMainBy(dx, recycler, state)
        else scrollCrossBy(dx, recycler, state)
    }

    private fun scrollMainBy(delta: Float, recycler: RecyclerView.Recycler, state: RecyclerView.State): Float {
        val itemCount = state.itemCount
        if (itemCount <= 0) return 0f
        scrollIntoAnchor += delta
        val consumed = normalizeAnchor(recycler, itemCount, delta)
        if (consumed != 0f) onLayoutChildren(recycler, state) else syncDerivedOffset(recycler)
        return consumed
    }

    /** Only sets the target anchor state - does *not* measure/clamp/publish [scrollOffsetY]/
     *  [scrollOffsetX] itself. Doing that here would mean probing (via [sizeOf]) a position that
     *  isn't necessarily the currently-active one, *before* any [Recycler.scrapActiveViews] call has
     *  freed up the old anchor's holder for reuse - unsafe for the same reason noted on
     *  [normalizeAnchor] and [clampAnchorToEnd]'s KDoc. [RecyclerView.scrollToPosition] (the only
     *  caller) always [RecyclerView.requestLayout]s right after this returns, and [onLayoutChildren]
     *  does the real clamp and offset publish in the correct order on that next pass - so
     *  [scrollOffsetY]/[scrollOffsetX] stay at their *previous* value until then, same as any other
     *  pending-relayout state in this library. */
    override fun scrollToPosition(position: Int) {
        val itemCount = (recyclerView?.getAdapter()?.getItemCount()) ?: return
        if (itemCount <= 0) return
        anchorPosition = position.coerceIn(0, itemCount - 1)
        scrollIntoAnchor = 0f
    }

    companion object {
        /** Floor under a measured size used as a divisor (see [syncDerivedOffset]) - guards against
         *  dividing by zero for a degenerate zero-height item without needing a special case. */
        private const val MIN_MEASURED_SIZE = 0.0001f
    }
}
