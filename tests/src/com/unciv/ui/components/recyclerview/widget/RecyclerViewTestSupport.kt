package com.unciv.ui.components.recyclerview.widget

import com.badlogic.gdx.scenes.scene2d.ui.Widget

/** Shared test fixtures for the RecyclerView widget test suite. */
object RecyclerViewTestSupport {

    /** A scene2d [Widget] (which implements [com.badlogic.gdx.scenes.scene2d.utils.Layout]) that
     * reports a settable pref size, so per-item measurement can be exercised with known heights. */
    class SizedActor(var pref: Float) : Widget() {
        override fun getPrefWidth(): Float = pref
        override fun getPrefHeight(): Float = pref
    }

    /** A trivial ViewHolder wrapping a [SizedActor] - a real, controllable size, not a bare
     *  zero-pref [Widget]: since [LinearLayoutManager] has no `itemSize` fallback of its own (every
     *  item view is a [Widget]/[com.badlogic.gdx.scenes.scene2d.utils.Layout] and is expected to
     *  report its own real size), tests that want uniform-sized items set [SimpleViewHolder.widget]'s
     *  `pref` explicitly - see [StringAdapter]'s `itemSize` parameter. */
    class SimpleViewHolder(val widget: SizedActor = SizedActor(0f)) : RecyclerView.ViewHolder() {
        override fun getItemViews(): List<Widget> = listOf(widget)
    }

    class SizedViewHolder(val sizedView: SizedActor = SizedActor(0f)) : RecyclerView.ViewHolder() {
        override fun getItemViews(): List<Widget> = listOf(sizedView)
    }

    /**
     * Adapter whose items each report a per-position pref size (via [sizeFor]), for testing the
     * anchor-model per-item measurement in [LinearLayoutManager]/[GridLayoutManager].
     */
    class SizedAdapter(
        var count: Int,
        private val sizeFor: (Int) -> Float
    ) : RecyclerView.Adapter<SizedViewHolder>() {
        override fun onCreateViewHolder(parent: RecyclerView, viewType: Int) = SizedViewHolder()
        override fun onBindViewHolder(holder: SizedViewHolder, position: Int) {
            holder.sizedView.pref = sizeFor(position)
        }
        override fun getItemCount(): Int = count
    }

    /**
     * A simple [RecyclerView.Adapter] over a mutable list of strings, with call counters so
     * tests can assert on create/bind/recycle behavior. [itemSize] (applied to every holder's
     * [SimpleViewHolder.widget] on every bind) stands in for the uniform per-item size a real
     * adapter's items would report themselves - see [SimpleViewHolder]'s KDoc for why that's needed
     * now that [LinearLayoutManager] has no `itemSize` fallback of its own.
     */
    class StringAdapter(
        initialItems: List<String> = emptyList(),
        private val itemSize: Float = 0f,
        private val viewTypeSelector: (String) -> Int = { 0 }
    ) : RecyclerView.Adapter<SimpleViewHolder>() {
        var items: List<String> = initialItems
        var createCount = 0
            private set
        var bindCount = 0
            private set
        val boundPositions = mutableListOf<Int>()
        val createdHolders = mutableListOf<SimpleViewHolder>()

        override fun onCreateViewHolder(parent: RecyclerView, viewType: Int): SimpleViewHolder {
            createCount++
            val holder = SimpleViewHolder()
            createdHolders.add(holder)
            return holder
        }

        override fun onBindViewHolder(holder: SimpleViewHolder, position: Int) {
            bindCount++
            boundPositions.add(position)
            holder.widget.pref = itemSize
        }

        override fun getItemCount(): Int = items.size

        override fun getItemViewType(position: Int): Int = viewTypeSelector(items[position])
    }
}
