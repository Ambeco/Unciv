package com.unciv.ui.components.tilegroups.layers

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.unciv.view.CivView
import com.unciv.logic.map.tile.Tile
import com.unciv.models.tilesets.TileSetCache
import com.unciv.ui.components.tilegroups.TileGroup
import com.unciv.ui.components.tilegroups.TileSetStrings

abstract class TileLayer(val tileGroup: TileGroup, val size: Float) {

    /** Computed, not snapshotted: [TileGroup.rebind] reassigns [TileGroup.tileView] to point at a
     *  different [Tile] without reconstructing this layer, so a stored `val` captured once at
     *  construction would silently go stale - see [rebind] for the rest of what that requires. */
    val tile: Tile get() = tileGroup.tile
    val strings: TileSetStrings = tileGroup.tileSetStrings

    /** Absolute X of the tile origin in the parent TileMapLayer. 0 until attachTo() is called. */
    internal var tileX: Float = 0f
    /** Absolute Y of the tile origin in the parent TileMapLayer. 0 until attachTo() is called. */
    internal var tileY: Float = 0f
    internal var parentMapLayer: TileMapLayer<*>? = null

    /** All Actor children currently owned by this tile-slot. 
     * Lazily initialized — most layers never have actors added during TileGroup construction */
    private var _ownedActors: ArrayList<Actor>? = null
    internal val ownedActors: ArrayList<Actor>
        get() = _ownedActors ?: ArrayList<Actor>().also { _ownedActors = it }

    var isVisible: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            forEachOwnedActor { it.isVisible = value }
        }

    // ── scene-graph helpers ──────────────────────────────────────────────────

    /** Iterates owned actors without triggering lazy initialization when the list is empty. */
    internal fun forEachOwnedActor(action: (Actor) -> Unit) {
        _ownedActors?.forEach(action)
    }

    protected fun addOwnedActor(actor: Actor) {
        ownedActors.add(actor)
        // If the layer is already registered in a TileMapLayer, forward there.
        // Otherwise, add directly to the TileGroup so standalone displays work
        // (map-editor icon previews, Civilopedia entries, etc.).
        if (parentMapLayer != null) parentMapLayer!!.addActor(actor)
        else tileGroup.addActor(actor)
    }

    protected fun removeOwnedActor(actor: Actor) {
        if (_ownedActors == null) return
        if (!_ownedActors!!.remove(actor)) return
        // parentMapLayer handles removal when attached; actor.remove() handles the
        // standalone case where the actor lives directly under tileGroup.
        if (parentMapLayer != null) parentMapLayer!!.removeActor(actor)
        else actor.remove()
    }


    // ── positioning helpers ──────────────────────────────────────────────────

    /**
     * Sets hexagon image size/origin/position/scale.
     *
     * By default the position is **absolute** (tileX + local offset) so the image can sit
     * directly in a TileMapLayer.  Pass `tileLocal = true` when the image lives inside a
     * sub-Group that is already positioned at the tile origin — in that case the image only
     * needs the local hex offset (no tileX/tileY addition).
     */
    fun Image.setHexagonSize(scale: Float? = null, tileLocal: Boolean = false): Image {
        this.setSize(tileGroup.hexagonImageWidth, this.height * tileGroup.hexagonImageWidth / this.width)
        this.setOrigin(tileGroup.hexagonImageOriginX, tileGroup.hexagonImageOriginY)
        val baseX = if (tileLocal) 0f else tileX
        val baseY = if (tileLocal) 0f else tileY
        this.x = baseX + tileGroup.hexagonImagePosition.first
        this.y = baseY + tileGroup.hexagonImagePosition.second
        this.setScale(scale ?: TileSetCache.getCurrent().config.tileScale)
        return this
    }

    fun isViewable(viewingCiv: CivView) = tileGroup.isViewable(viewingCiv)

    fun update(viewingCiv: CivView?) {
        doUpdate(viewingCiv)
        determineVisibility()
    }

    protected open fun determineVisibility() {
        isVisible = _ownedActors?.isNotEmpty() == true
    }

    protected abstract fun doUpdate(viewingCiv: CivView?)

    /** Called by TileMapLayer.add() — offsets pre-buffered images from local → absolute coords. */
    internal fun attachTo(mapLayer: TileMapLayer<*>, x: Float, y: Float) {
        tileX = x
        tileY = y
        parentMapLayer = mapLayer
        forEachOwnedActor { actor ->
            actor.x += x
            actor.y += y
            mapLayer.addActor(actor)
        }
    }

    /**
     * Called by [TileGroup.rebind] when this layer's [TileGroup] is repointed at a different
     * [Tile] - e.g. a pooled/recycling map holder reusing a fixed set of [TileGroup]s as the
     * viewport scrolls, instead of constructing one per map tile. [doUpdate] alone is *not* safe
     * for this: it's written to incrementally diff against the *previous* update of the *same*
     * tile (only touching actors/fields that actually changed), which can't tell "this is a
     * different tile now" apart from "nothing changed" - see the overrides of this method for the
     * specific per-layer caches that would otherwise go stale (wrong neighbor set, wrong screen
     * position, or simply never freed).
     *
     * The default here (unconditionally dropping every owned actor and moving to the new tile's
     * position) is necessary but not sufficient on its own: any subclass with additional identity
     * caches keyed by the *previous* tile or its neighbors (private fields tracking "current icon"
     * or "current road image", HashMaps keyed by neighbor [Tile]/[com.unciv.view.TileView]) must
     * override this to also clear those, or [doUpdate]'s diffing will treat the stale field as
     * still valid and never refresh it.
     */
    internal open fun rebind(newTileX: Float, newTileY: Float) {
        for (actor in ownedActors.toList()) removeOwnedActor(actor)
        tileX = newTileX
        tileY = newTileY
    }
}
