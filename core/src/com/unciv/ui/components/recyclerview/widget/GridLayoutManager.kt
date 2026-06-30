package com.unciv.ui.components.recyclerview.widget

import com.badlogic.gdx.scenes.scene2d.utils.Layout
import kotlin.math.max
import kotlin.math.min

/**
 * Lays items out in a fixed number of columns (VERTICAL orientation) or rows (HORIZONTAL
 * orientation), matching Android's GridLayoutManager naming, `spanCount`, its [SpanSizeLookup],
 * and its inheritance from LinearLayoutManager.
 *
 * Supports Android's [SpanSizeLookup]: an item may span multiple cells, wrapping to a new row when
 * it doesn't fit in the remaining columns of the current row - identical packing semantics to
 * Android's `DefaultSpanSizeLookup` wrapping. Each row's main-axis height is the max of
 * [LinearLayoutManager.measure] over the items actually placed in that row (each item's holder's
 * [RecyclerView.ViewHolder.layoutParams] - which itself falls back to the item Actor's own
 * [Layout] size unless explicitly overridden, or 0 if that Actor isn't a [Layout] at all - see
 * [LinearLayoutManager.measure]'s KDoc). The cross-axis cell size is `viewport / spanCount` (every
 * column/row band fits exactly, no cross-axis scrolling) unless [cellCrossSize] is given a positive
 * value, in which case cells use that fixed size instead - and if `spanCount * cellCrossSize`
 * exceeds the viewport, the cross axis becomes scrollable too - see the anchor model below and
 * [crossOverflow].
 *
 * ### Anchor model
 * API CHANGE: like [LinearLayoutManager], scroll position is an anchor - here the top/leading **row**
 * ([anchorRow]) plus [scrollIntoRow] pixels into it, both always exact - and layout walks outward row
 * by row measuring each row's height locally, so off-screen size changes never disturb visible
 * geometry and no global pixel table exists. [scrollOffsetY]/[scrollOffsetX] are published in
 * **row-and-fraction units** (`anchorRow + scrollIntoRow / anchorRowSize`), exact for the same reason
 * [LinearLayoutManager]'s item-based version is - see its class doc. End-of-list clamping
 * ([clampAnchorRowToEnd]) measures only the (at most one viewport's worth of) trailing rows actually
 * needed, discovered reactively, never a whole-grid scan or size estimate. For the
 * [DefaultSpanSizeLookup] the position<->row mapping is pure arithmetic (`row = position/spanCount`)
 * and needs no per-item storage, keeping huge uniform grids fully virtualized; a non-default
 * [spanSizeLookup] requires one O(itemCount) index-only packing scan (no pixels), cached until the
 * data or lookup changes.
 *
 * The cross axis (when [cellCrossSize] enables scrolling it at all) has its own anchor -
 * [anchorColumn] plus [scrollIntoColumn] - the column-band counterpart of [anchorRow]/[scrollIntoRow].
 * It needs none of the row model's boundary-crossing-loop or end-of-list-scan complexity: cell
 * cross size is uniform by construction (a single [cellCrossSize], not independently measured per
 * item like row height is), so a plain clamped pixel accumulator ([crossScrollPx]) is already exact,
 * with [anchorColumn]/[scrollIntoColumn] simply decomposed from it for API symmetry with the row
 * side. KNOWN GAP: unlike the row axis, off-screen *columns* are not culled from binding (every
 * column in a visible row is bound regardless of cross-scroll position) - fine for the modest
 * `spanCount`s this is meant for, but not a fully virtualized cross axis for very large ones.
 */
open class GridLayoutManager(
    orientation: RecyclerView.Orientation = RecyclerView.Orientation.VERTICAL,
    var spanCount: Int = 2,
    /** Fixed cross-axis cell size. `<= 0` (the default) means "auto-fit": cells are exactly
     *  `viewport / spanCount` and the cross axis never scrolls, matching this class's behavior
     *  before cross-axis scrolling existed. A positive value fixes cell size instead, and enables
     *  cross-axis scrolling once `spanCount * cellCrossSize` exceeds the viewport - see the class doc. */
    var cellCrossSize: Float = -1f
) : LinearLayoutManager(orientation) {

    /**
     * Provides the number of spans (cells) each item occupies, matching Android's
     * `GridLayoutManager.SpanSizeLookup`. The default returns 1 for every position.
     */
    abstract class SpanSizeLookup {
        abstract fun getSpanSize(position: Int): Int
    }

    /** Android's `DefaultSpanSizeLookup`: every item occupies exactly one span. */
    class DefaultSpanSizeLookup : SpanSizeLookup() {
        override fun getSpanSize(position: Int): Int = 1
    }

    var spanSizeLookup: SpanSizeLookup = DefaultSpanSizeLookup()
        set(value) { field = value; packingDirty = true }

    /** Top/leading row pinned to the viewport edge. */
    var anchorRow = 0
        private set
    private var scrollIntoRow = 0f

    /** Pixels scrolled into the cross axis from column 0's leading edge, `0..crossOverflow()` - the
     *  single source of truth [anchorColumn]/[scrollIntoColumn] are decomposed from (see the class
     *  doc for why a plain accumulator suffices here, unlike the row axis). */
    private var crossScrollPx = 0f

    /** Leading (left for VERTICAL, top for HORIZONTAL) column band pinned to the cross-axis viewport
     *  edge - the column counterpart of [anchorRow]. Always 0 when cross-axis scrolling isn't
     *  enabled (see [cellCrossSize]). */
    val anchorColumn: Int get() = if (cellCrossSize <= 0f) 0 else (crossScrollPx / cellCrossSize).toInt().coerceIn(0, spanCount - 1)
    /** Pixels scrolled into [anchorColumn] past its own leading edge - the column counterpart of
     *  [scrollIntoRow]. */
    val scrollIntoColumn: Float get() = crossScrollPx - anchorColumn * cellCrossSize

    /** Pixels the cross axis's full extent (`spanCount * cellCrossSize`) currently overflows the
     *  viewport's cross-axis size by, or 0 if [cellCrossSize] disables cross-scrolling (the default)
     *  or the content simply fits. */
    private fun crossOverflow(): Float {
        if (cellCrossSize <= 0f) return 0f
        val rv = recyclerView ?: return 0f
        val vertical = orientation == RecyclerView.Orientation.VERTICAL
        val viewportCross = if (vertical) rv.width else rv.height
        return (spanCount * cellCrossSize - viewportCross).coerceAtLeast(0f)
    }

    override fun canScrollVertically(): Boolean =
        orientation == RecyclerView.Orientation.VERTICAL || crossOverflow() > 0f
    override fun canScrollHorizontally(): Boolean =
        orientation == RecyclerView.Orientation.HORIZONTAL || crossOverflow() > 0f

    /** Scrolls the cross axis by [delta], clamped to `[0, crossOverflow()]` - see [crossScrollPx]. */
    private fun scrollColumnsBy(delta: Float, recycler: RecyclerView.Recycler, state: RecyclerView.State): Float {
        val overflow = crossOverflow()
        if (overflow <= 0f) { crossScrollPx = 0f; return 0f }
        val before = crossScrollPx
        crossScrollPx = (crossScrollPx + delta).coerceIn(0f, overflow)
        val consumed = crossScrollPx - before
        if (consumed != 0f) onLayoutChildren(recycler, state)
        return consumed
    }

    // region position <-> row mapping (arithmetic for default lookup, cached scan for custom)

    /** `rowStart[r]` = first adapter position of row `r`; `rowStart[rowCount] == itemCount`. Only
     * built for a non-default [spanSizeLookup]; the default uses arithmetic and leaves this empty. */
    private var rowStart = IntArray(0)
    private var rowCount = 0
    private var packingDirty = true

    private val usingDefaultSpans get() = spanSizeLookup is DefaultSpanSizeLookup

    private fun clampedSpan(position: Int): Int = min(spanCount, max(1, spanSizeLookup.getSpanSize(position)))

    private fun ensurePacking(itemCount: Int) {
        if (usingDefaultSpans) {
            rowCount = if (itemCount <= 0 || spanCount <= 0) 0 else (itemCount + spanCount - 1) / spanCount
            return
        }
        if (!packingDirty && rowStart.isNotEmpty() && rowStart[rowCount] == itemCount) return
        val starts = ArrayList<Int>(itemCount / max(1, spanCount) + 2)
        starts.add(0)
        var col = 0
        for (pos in 0 until itemCount) {
            val span = clampedSpan(pos)
            if (col + span > spanCount) { starts.add(pos); col = 0 }
            col += span
        }
        rowCount = starts.size
        starts.add(itemCount)
        rowStart = starts.toIntArray()
        packingDirty = false
    }

    private fun rowStartPos(row: Int): Int = if (usingDefaultSpans) row * spanCount else rowStart[row]
    private fun rowEndPos(row: Int, itemCount: Int): Int =
        if (usingDefaultSpans) min(itemCount, (row + 1) * spanCount) else rowStart[row + 1]

    private fun rowOf(position: Int): Int {
        if (usingDefaultSpans) return position / spanCount
        var lo = 0; var hi = rowCount - 1; var result = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (rowStart[mid] <= position) { result = mid; lo = mid + 1 } else hi = mid - 1
        }
        return result
    }

    override fun currentAnchorPosition(): Int = rowStartPos(anchorRow)
    override fun compareAnchorTo(position: Int): Int = rowOf(position).compareTo(anchorRow)
    override fun invalidateSizeCache() { packingDirty = true }

    /** The anchor row's own real measured height (exact), or 0f if there are no rows yet - see
     *  [LinearLayoutManager.mainStepSize]'s KDoc for why this is the anchor's actual size, not an
     *  average. */
    override fun mainStepSize(): Float {
        val rv = recyclerView ?: return 0f
        val itemCount = rv.getAdapter()?.getItemCount() ?: return 0f
        if (itemCount <= 0 || spanCount <= 0) return 0f
        ensurePacking(itemCount)
        if (rowCount <= 0) return 0f
        return sizeOfRow(anchorRow.coerceIn(0, rowCount - 1), rv.recycler, itemCount)
    }

    // endregion

    /** Measured main-axis height of [row]: the max of [measure] over every item placed in it (0f if
     *  none report any positive size - see [LinearLayoutManager.measure]'s KDoc on there being no
     *  further fallback constant). */
    private fun sizeOfRow(row: Int, recycler: RecyclerView.Recycler, itemCount: Int): Float {
        var maxSize = 0f
        for (pos in rowStartPos(row) until rowEndPos(row, itemCount)) {
            val holder = recycler.getHolderForPosition(pos)
            val size = measure(holder)
            if (size > maxSize) maxSize = size
        }
        return maxSize
    }

    /** Publishes the anchor row state as an exact **row-and-fraction** value - see the class doc. */
    private fun syncDerivedRowOffset(recycler: RecyclerView.Recycler, itemCount: Int) {
        val anchorSize = sizeOfRow(anchorRow, recycler, itemCount).coerceAtLeast(MIN_MEASURED_ROW_SIZE)
        val estimate = anchorRow + scrollIntoRow / anchorSize
        if (orientation == RecyclerView.Orientation.VERTICAL) scrollOffsetY = estimate else scrollOffsetX = estimate
    }

    /** Row-based counterpart of [LinearLayoutManager]'s `endOfListAnchorState` - see its KDoc. */
    private fun endOfGridAnchorState(recycler: RecyclerView.Recycler, itemCount: Int, viewportMain: Float): Pair<Int, Float> {
        var row = rowCount - 1
        var total = 0f
        while (true) {
            total += sizeOfRow(row, recycler, itemCount)
            if (total >= viewportMain || row == 0) return row to (total - viewportMain).coerceAtLeast(0f)
            row--
        }
    }

    /** Row-based counterpart of [LinearLayoutManager]'s `clampAnchorToEnd` - see its KDoc for the
     *  cost profile (bounded by the viewport, never a whole-grid scan). */
    private fun clampAnchorRowToEnd(recycler: RecyclerView.Recycler, itemCount: Int, viewportMain: Float): Float {
        var row = anchorRow
        var filled = -scrollIntoRow
        while (row < rowCount && filled < viewportMain) {
            filled += sizeOfRow(row, recycler, itemCount)
            row++
        }
        if (row < rowCount) return 0f
        val shortfall = viewportMain - filled
        if (shortfall <= 0f) return 0f
        val (endRow, endScrollInto) = endOfGridAnchorState(recycler, itemCount, viewportMain)
        anchorRow = endRow
        scrollIntoRow = endScrollInto
        return shortfall
    }

    /** Row-based counterpart of [LinearLayoutManager]'s `normalizeAnchor` - see its KDoc, including
     *  for why the end-of-list clamp is deliberately *not* done here. */
    private fun normalizeAnchorRow(recycler: RecyclerView.Recycler, itemCount: Int, requestedDelta: Float): Float {
        var consumed = requestedDelta
        while (anchorRow < rowCount - 1) {
            val size = sizeOfRow(anchorRow, recycler, itemCount)
            if (scrollIntoRow < size) break
            scrollIntoRow -= size
            anchorRow++
        }
        while (scrollIntoRow < 0f && anchorRow > 0) {
            anchorRow--
            scrollIntoRow += sizeOfRow(anchorRow, recycler, itemCount)
        }
        if (anchorRow <= 0 && scrollIntoRow < 0f) {
            consumed += scrollIntoRow
            anchorRow = 0
            scrollIntoRow = 0f
        }
        return consumed
    }

    private fun placeRow(rv: RecyclerView, recycler: RecyclerView.Recycler, row: Int, itemCount: Int,
                         mainStart: Float, rowHeight: Float, crossSize: Float, vertical: Boolean, visible: MutableSet<Int>) {
        var col = 0
        for (pos in rowStartPos(row) until rowEndPos(row, itemCount)) {
            val span = clampedSpan(pos)
            val holder = recycler.getHolderForPosition(pos)
            val crossStart = col * crossSize - crossScrollPx
            val crossExtent = span * crossSize
            val inset = rv.getItemDecorationInsetsForPosition(pos)
            val x: Float; val y: Float; val w: Float; val h: Float
            if (vertical) {
                x = crossStart + inset.x
                y = rv.height - mainStart - rowHeight + inset.y
                w = crossExtent - inset.x - inset.width
                h = rowHeight - inset.y - inset.height
            } else {
                x = mainStart + inset.x
                y = crossStart + inset.y
                w = rowHeight - inset.x - inset.width
                h = crossExtent - inset.y - inset.height
            }
            // Same "all views share one item slot" treatment as LinearLayoutManager.placeItem.
            for (view in holder.getItemViews()) view.setBounds(x, y, w, h)
            visible.add(pos)
            col += span
        }
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        val rv = recyclerView ?: return
        val itemCount = state.itemCount
        if (itemCount <= 0 || spanCount <= 0) {
            recycler.scrapActiveViews()
            recycler.recycleScrap()
            return
        }
        ensurePacking(itemCount)
        if (anchorRow > rowCount - 1) { anchorRow = max(0, rowCount - 1); scrollIntoRow = 0f }

        val vertical = orientation == RecyclerView.Orientation.VERTICAL
        val viewportMain = if (vertical) rv.height else rv.width

        // Order matters: scrapActiveViews() before clampAnchorRowToEnd - see LinearLayoutManager's
        // onLayoutChildren KDoc for why (the clamp can probe rows other than the currently-active
        // anchor, which can only correctly recycle the old anchor's holder once scrapActiveViews()
        // has actually freed it up).
        recycler.scrapActiveViews()
        clampAnchorRowToEnd(recycler, itemCount, viewportMain)

        val crossSize = if (cellCrossSize > 0f) cellCrossSize else (if (vertical) rv.width else rv.height) / spanCount
        // Buffer size is a multiple of the anchor row's own real measured height, not a fixed
        // constant - there's no itemSize fallback to fall back on (see measure()'s KDoc).
        val bufferPx = bufferItemCount * sizeOfRow(anchorRow, recycler, itemCount)
        val visible = LinkedHashSet<Int>()

        // Walk forward from the anchor row filling the viewport + trailing buffer.
        var row = anchorRow
        var mainStart = -scrollIntoRow
        while (row < rowCount && mainStart <= viewportMain + bufferPx) {
            val rowHeight = sizeOfRow(row, recycler, itemCount)
            placeRow(rv, recycler, row, itemCount, mainStart, rowHeight, crossSize, vertical, visible)
            mainStart += rowHeight
            row++
        }
        // Walk backward from the anchor row for the leading buffer region.
        row = anchorRow - 1
        var bottom = -scrollIntoRow
        while (row >= 0 && bottom > -bufferPx) {
            val rowHeight = sizeOfRow(row, recycler, itemCount)
            val top = bottom - rowHeight
            placeRow(rv, recycler, row, itemCount, top, rowHeight, crossSize, vertical, visible)
            bottom = top
            row--
        }

        recycler.recycleScrap()
        syncDerivedRowOffset(recycler, itemCount)
    }

    override fun scrollVerticallyBy(dy: Float, recycler: RecyclerView.Recycler, state: RecyclerView.State): Float {
        if (!canScrollVertically()) return 0f
        return if (orientation == RecyclerView.Orientation.VERTICAL) scrollRowsBy(dy, recycler, state)
        else scrollColumnsBy(dy, recycler, state)
    }

    override fun scrollHorizontallyBy(dx: Float, recycler: RecyclerView.Recycler, state: RecyclerView.State): Float {
        if (!canScrollHorizontally()) return 0f
        return if (orientation == RecyclerView.Orientation.HORIZONTAL) scrollRowsBy(dx, recycler, state)
        else scrollColumnsBy(dx, recycler, state)
    }

    private fun scrollRowsBy(delta: Float, recycler: RecyclerView.Recycler, state: RecyclerView.State): Float {
        val itemCount = state.itemCount
        if (itemCount <= 0 || spanCount <= 0) return 0f
        ensurePacking(itemCount)
        scrollIntoRow += delta
        val consumed = normalizeAnchorRow(recycler, itemCount, delta)
        if (consumed != 0f) onLayoutChildren(recycler, state) else syncDerivedRowOffset(recycler, itemCount)
        return consumed
    }

    /** Only sets the target anchor row - does *not* measure/clamp/publish [scrollOffsetY]/
     *  [scrollOffsetX] itself. See [LinearLayoutManager.scrollToPosition]'s KDoc for why: the
     *  subsequent [onLayoutChildren] pass (triggered by [RecyclerView.scrollToPosition]'s own
     *  [RecyclerView.requestLayout] right after this returns) does the real clamp and offset publish,
     *  in the only order that's safe for recycling. */
    override fun scrollToPosition(position: Int) {
        val itemCount = (recyclerView?.getAdapter()?.getItemCount()) ?: return
        if (itemCount <= 0 || spanCount <= 0) return
        ensurePacking(itemCount)
        anchorRow = rowOf(position.coerceIn(0, itemCount - 1)).coerceIn(0, max(0, rowCount - 1))
        scrollIntoRow = 0f
    }

    companion object {
        /** Floor under a measured row size used as a divisor (see [syncDerivedRowOffset]) - guards
         *  against dividing by zero for a degenerate zero-height row without needing a special case. */
        private const val MIN_MEASURED_ROW_SIZE = 0.0001f
    }
}
