package com.unciv.ui.components.tilegroups.layers

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
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

    /**
     * Stable wrapper this layer's owned actors live in when not registered into a shared
     * [TileMapLayer] (the "standalone" case - map-editor icon previews, Civilopedia entries, and
     * [com.unciv.ui.screens.worldscreen.worldmap.RecyclerWorldMapHolder]'s pooled tiles). Unlike
     * [ownedActors] itself (whose *contents* can change on every [update]), this Group's own
     * identity never changes for this layer's lifetime - which is what lets a pooled/recycling
     * caller track and reposition it as one stable item (see
     * [com.unciv.ui.screens.worldscreen.worldmap.HexTileAdapter.ViewHolder.getItemViews]) without
     * needing to know or care that the *content* underneath keeps changing - exactly how
     * [TileGroup] itself already behaves as a whole for callers that don't pool it.
     *
     * Lazily created *per layer* - not all 11 at once the first time any one is needed - and only
     * actually attached as a [tileGroup] child via [TileGroup.ensureStandaloneWrapperAttached] (see
     * its own doc for why per-layer, index-aware insertion, not just appending, is what keeps draw
     * order correct despite that laziness).
     *
     * A layer that ends up registered into a [TileMapLayer] instead - every tile
     * [EagerWorldMapHolder] constructs, by far the common case, up to ~8000 tiles a game - still
     * momentarily creates this if it's [layerTerrain]: [TileGroup]'s own `init` always calls
     * `layerTerrain.update(null)` *before* [TileGroupMap] (constructed afterward, from the finished
     * [TileGroup] list) ever gets a chance to call [attachTo] on anything. [attachTo] cleans this
     * back up once that registration actually happens - see its own doc - so the net cost for a
     * [TileMapLayer]-registered tile is zero once construction settles (and only ever one wrapper -
     * terrain's - not eleven, even transiently). An *unconditionally* eager wrapper for all 11
     * layers measurably quadrupled per-tile memory in practice, right where
     * `EditorMapHolderMemoryTest` checks for exactly this on Android's OOM-prone 512MB heap.
     */
    private val standaloneWrapperLazy = lazy { Group().apply { isTransform = false } }
    internal val standaloneWrapper: Group by standaloneWrapperLazy

    /** True if [standaloneWrapper] has been created *and* already has *some* parent - not
     *  necessarily [tileGroup] itself. [HexTileAdapter.ViewHolder.getItemViews] exposes these
     *  wrappers directly to a [com.unciv.ui.components.recyclerview.widget.RecyclerView], which
     *  re-parents each one straight from [tileGroup] onto itself the moment it's attached (a plain
     *  [com.badlogic.gdx.scenes.scene2d.Group.addActor] always steals an actor from its previous
     *  parent) - after that, this wrapper's parent is the RecyclerView, permanently, for as long as
     *  that binding lasts. Checking specifically for `=== tileGroup` here would go blind to that:
     *  the *next* unrelated content update ([com.unciv.ui.components.tilegroups.WorldMapTileUpdater]
     *  runs these regularly, entirely independent of RecyclerView's own attach/detach calls) would
     *  see "not attached to tileGroup" and steal the wrapper *back* out of the RecyclerView and onto
     *  the (unattached, invisible) [tileGroup] instead - which is exactly what happened before this
     *  became `!= null`: every recycler-pooled tile went blank on the very next update pass. What
     *  [TileGroup.ensureStandaloneWrapperAttached] actually needs from this is just "does something
     *  already own me, so leave me alone" - who that owner is doesn't matter here. */
    internal fun isStandaloneWrapperAttached(): Boolean =
        standaloneWrapperLazy.isInitialized() && standaloneWrapper.parent != null

    /** Iterates owned actors without triggering lazy initialization when the list is empty. */
    internal fun forEachOwnedActor(action: (Actor) -> Unit) {
        _ownedActors?.forEach(action)
    }

    protected fun addOwnedActor(actor: Actor) {
        ownedActors.add(actor)
        // If the layer is already registered in a TileMapLayer, forward there. Otherwise, add to
        // this layer's own standaloneWrapper rather than tileGroup directly, so a pooled/recycling
        // caller can track and reposition each layer's content independently (see that property's
        // doc) - ensureStandaloneWrapperAttached() is what actually makes it a tileGroup child.
        if (parentMapLayer != null) parentMapLayer!!.addActor(actor)
        else {
            tileGroup.ensureStandaloneWrapperAttached(this)
            standaloneWrapper.addActor(actor)
        }
    }

    protected fun removeOwnedActor(actor: Actor) {
        if (_ownedActors == null) return
        if (!_ownedActors!!.remove(actor)) return
        // parentMapLayer handles removal when attached; actor.remove() handles the standalone case
        // (removes from whichever parent it's currently under - standaloneWrapper - regardless).
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
        // If standaloneWrapper was already created (see its own doc for why that's likely even for
        // a layer ending up here), every owned actor was just re-parented into mapLayer above,
        // leaving it permanently empty - detach it from tileGroup instead of letting it sit around
        // as dead weight (addOwnedActor never takes the standalone branch for this layer again, now
        // that parentMapLayer is set, so nothing will ever go back into it).
        if (standaloneWrapperLazy.isInitialized()) standaloneWrapper.remove()
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
