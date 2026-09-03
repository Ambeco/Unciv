package com.unciv.ui.components.tilegroups.layers

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Interpolation
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.utils.Align
import com.unciv.UncivGame
import com.unciv.view.CivView
import com.unciv.view.TileMarker
import com.unciv.view.TileSingleAnimation
import com.unciv.logic.map.HexMath
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.map.toHexCoord
import com.unciv.models.translations.tr
import com.unciv.ui.components.*
import com.unciv.ui.components.extensions.*
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onDoubleClick
import com.unciv.ui.components.tilegroups.CityTileGroup
import com.unciv.ui.components.tilegroups.TileGroup
import com.unciv.ui.components.tilegroups.TileSetStrings
import com.unciv.ui.components.tilegroups.WorldTileGroup
import com.unciv.ui.components.tilegroups.YieldGroup
import com.unciv.ui.images.ImageGetter
import com.unciv.utils.DebugUtils
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

private class MapArrow(val targetTile: Tile, val arrowType: MapArrowType, val strings: TileSetStrings) {
    fun getImage(): Image = getArrowImage(arrowType, strings)
}

/**
 * Resolves the [Image] to draw for one arrow of [arrowType], from [strings]' tileset - shared by
 * [MapArrow.getImage] (an arrow parented inside its own tile's misc-layer wrapper) and
 * [com.unciv.ui.screens.worldscreen.worldmap.RecyclerWorldMapHolder]'s arrow overlay (a standalone
 * Actor - see [layoutArrowImage]'s doc for why that needs to exist as a separate case at all).
 */
internal fun getArrowImage(arrowType: MapArrowType, strings: TileSetStrings): Image {
    fun image(imageName: String) = ImageGetter.getImage(
        strings.orFallback { getString(tileSetLocation, "Arrows/", imageName) })
    return when (arrowType) {
        is UnitMovementMemoryType -> image(arrowType.name)
        is MiscArrowTypes -> image(arrowType.name)
        is TintedMapArrow -> image("Generic").apply { color = arrowType.color }
        else -> image("Generic")
    }
}

/**
 * Pure geometry for one arrow [Image]: sizes/positions/rotates it to point from [fromTile] toward
 * [toTile], anchored at ([originX], [originY]) - [fromTile]'s own tile origin, in whatever
 * coordinate space the caller's own parent Actor uses. [TileLayerMisc.updateArrows] calls this with
 * a tile-local origin (the image ends up parented inside that tile's own layer wrapper, so (0,0) is
 * that wrapper's own origin); [com.unciv.ui.screens.worldscreen.worldmap.RecyclerWorldMapHolder]'s
 * arrow overlay calls it with an absolute on-screen origin instead, since an arrow's Actor there is
 * never parented inside either endpoint tile's own wrapper at all - see that class's doc for why
 * (a tile's own wrapper can be recycled out from under it mid-scroll; the arrow Actor's lifetime is
 * deliberately independent of that). Doesn't touch [image]'s parenting/attachment, only its own
 * transform, so either caller remains responsible for adding/removing it from the scene themselves.
 */
internal fun layoutArrowImage(image: Image, fromTile: Tile, toTile: Tile, originX: Float, originY: Float) {
    val tileScale = 50f * 0.8f // See notes in updateRoadImages.
    var targetPos = Vector2(toTile.position.toVector2())
    if (fromTile.tileMap.mapParameters.worldWrap)
        targetPos = HexMath.getUnwrappedNearestTo(targetPos.toHexCoord(), fromTile.position, fromTile.tileMap.maxLongitude)
    val targetRelative = HexMath.hex2WorldCoords(targetPos.toHexCoord())
        .sub(HexMath.hex2WorldCoords(fromTile.position))

    val targetDistance = sqrt(targetRelative.x.pow(2) + targetRelative.y.pow(2))
    val targetAngle = atan2(targetRelative.y, targetRelative.x)

    image.touchable = Touchable.disabled
    // Arrows originate at tile centre (25, -5 in tile-local); offset by the given origin for absolute.
    image.setPosition(originX + 25f, originY - 5f)
    image.setSize(tileScale * targetDistance, 60f)
    image.setOrigin(0f, 30f)
    image.rotation = targetAngle / Math.PI.toFloat() * 180
}

class TileLayerYield(tileGroup: TileGroup, size: Float) : TileLayer(tileGroup, size){
    // Lazily created to avoid allocating YieldGroup for tiles that never display yields.
    private var yields: YieldGroup? = null

    private fun getOrCreateYields(): YieldGroup {
        if (yields == null) {
            yields = YieldGroup().apply {
                isVisible = false
                setOrigin(Align.center)
                setScale(0.7f)
            }
            addOwnedActor(yields!!)
            // Rough initial Y; updateYieldIcon corrects it once height is known after setStats.
            yields!!.y = tileY + tileGroup.height * 0.25f
        }
        return yields!!
    }

    override fun doUpdate(viewingCiv: CivView?) {
        val showTileYields = if (tileGroup is WorldTileGroup) UncivGame.Current.settings.showTileYields else true
        updateYieldIcon(viewingCiv, showTileYields)
    }

    // JN updating display of tile yields
    private fun updateYieldIcon(
        viewingCiv: CivView?,
        show: Boolean,
    ) {
        val effectiveVisible = show &&
                !tileGroup.isForMapEditorIcon &&  // don't have a map to calc yields
                !(viewingCiv == null && tileGroup.isForceVisible) // main menu background

        if (!effectiveVisible) {
            yields?.isVisible = false
            return
        }

        val y = getOrCreateYields()
        y.isVisible = false
        y.run {
            // Update YieldGroup Icon
            if (tileGroup is CityTileGroup)
                setStats(tileGroup.tileView.getTileStats(viewingCiv, tileGroup.cityView))
            else
                setStats(tileGroup.tileView.getTileStats(viewingCiv))
            toFront()
            // Centre horizontally; recalculate Y now that height is known after setStats
            x = tileX + (tileGroup.width - width) / 2
            this.y = tileY + tileGroup.height * 0.25f - height / 2
            isVisible = true
        }
    }

    fun setYieldVisible(isVisible: Boolean) {
        yields?.isVisible = isVisible
        this.isVisible = isVisible // don't try rendering the layer if there's nothing in it
    }

    fun dimYields(dim: Boolean) { yields?.color?.a = if (dim) 0.5f else 1f }

    fun reset() {
        updateYieldIcon(null, false)
    }

    override fun determineVisibility() {
        isVisible = yields?.isVisible == true
    }
}


class TileLayerResource(tileGroup: TileGroup, size: Float) : TileLayer(tileGroup, size){

    private var resourceName: String? = null
    private var resourceAmount: Int = -1
    private var resourceIcon: Actor? = null
    private var resourceProvidedIcon: Actor? = null

    private fun updateResourceIcon(viewingCiv: CivView?, showResourceIcon: Boolean) {
        val tileView = tileGroup.tileView
        // This could change on any turn, since resources need certain techs to reveal them
        val effectiveVisible = showResourceIcon && (tileGroup.isForceVisible || tileView.getViewableResource(viewingCiv) != null)

        // If resource has changed (e.g. tech researched) - force new icon next time it's needed
        if (resourceName != tileView.resource || resourceAmount != tileView.resourceAmount) {
            resourceName = tileView.resource
            resourceAmount = tileView.resourceAmount
            resourceIcon?.let { removeOwnedActor(it) }
            resourceIcon = null
        }

        // Get a fresh Icon if and only if necessary
        if (resourceName != null && effectiveVisible && resourceIcon == null) {
            val icon = ImageGetter.getResourcePortrait(resourceName!!, 20f, resourceAmount)
            // Centre on tile, offset left and up
            icon.x = tileX + (tileGroup.width - icon.width) / 2 - 22f
            icon.y = tileY + (tileGroup.height - icon.height) / 2 + 10f
            addOwnedActor(icon)
            resourceIcon = icon
        }

        resourceIcon?.isVisible = effectiveVisible


        if (resourceIcon != null){
            val isViewable = viewingCiv == null || isViewable(viewingCiv)
            dimResource(!isViewable)

            val shouldResourceProvidedBeDisplayed =
                viewingCiv != null && tileView.getOwner()?.civName == viewingCiv.civName
                        && tileView.providesResources(viewingCiv)
            if (shouldResourceProvidedBeDisplayed && resourceProvidedIcon == null){
                val group = NonTransformGroup()
                group.setSize(12f,12f)

                val blackStar = ImageGetter.getImage("OtherIcons/Star")
                blackStar.setSize(12f)
                blackStar.color = Color.BLACK
                blackStar.center(group)
                group.addActor(blackStar)

                val goldStar = ImageGetter.getImage("OtherIcons/Star")
                goldStar.setSize(10f)
                goldStar.color = Color.GOLD
                goldStar.center(group)
                group.addActor(goldStar)

                // Slightly extruding out from the resource icon
                group.setPosition(resourceIcon!!.right + 3f, resourceIcon!!.top + 3f, Align.topRight)
                addOwnedActor(group)

                resourceProvidedIcon = group
            }

            if (!shouldResourceProvidedBeDisplayed && resourceProvidedIcon != null){
                removeOwnedActor(resourceProvidedIcon!!)
                resourceProvidedIcon = null
            }
            resourceProvidedIcon?.toFront()
            resourceProvidedIcon?.isVisible = effectiveVisible
        }
    }

    fun reset() {
        updateResourceIcon(null, false)
    }

    fun dimResource(dim: Boolean) { resourceIcon?.color?.a = if (dim) 0.5f else 1f }

    override fun doUpdate(viewingCiv: CivView?) {
        val showResourcesAndImprovements = if (tileGroup is WorldTileGroup)
            UncivGame.Current.settings.showResourcesAndImprovements else true

        updateResourceIcon(viewingCiv, showResourcesAndImprovements)
    }

    override fun determineVisibility() {
        isVisible = resourceIcon?.isVisible == true
    }
}

class TileLayerImprovement(tileGroup: TileGroup, size: Float) : TileLayer(tileGroup, size){
    private var improvementPlusPillagedID: String? = null
    var improvementIcon: Actor? = null
        private set  // Getter public for BattleTable to display as City Combatant


    override fun doUpdate(viewingCiv: CivView?) {
        val showResourcesAndImprovements = if (tileGroup is WorldTileGroup)
            UncivGame.Current.settings.showResourcesAndImprovements else true

        updateImprovementIcon(showResourcesAndImprovements)
        // markers is only ever set for a WorldTileGroup's tileView (see WorldMapTileUpdater) - stays
        // 0 (hasMarker always false) for any other context this layer is used in, e.g. Civilopedia/
        // the map editor/CityScreen, so this is a harmless no-op there, matching today's behavior.
        dimImprovement(tileGroup.tileView.hasMarker(TileMarker.DIM_IMPROVEMENT))
        applyCombatFlash()
    }

    fun dimImprovement(dim: Boolean) { improvementIcon?.color?.a = if (dim) 0.5f else 1f }

    /** Which [TileSingleAnimation] this layer last attached its own flash [Actions] for - see
     *  [TileLayerUnitSprite]'s own `combatFlashShown` doc, this is the same thing for
     *  [improvementIcon]. */
    private var combatFlashShown: TileSingleAnimation? = null

    /** @see TileLayerUnitSprite.applyCombatFlash */
    private fun applyCombatFlash() {
        val tileView = tileGroup.tileView
        if (tileView.tileSingleAnimation != TileSingleAnimation.COMBAT_FLASH_RED) {
            combatFlashShown = null
            return
        }
        if (tileView.combatFlashUnit != null) return // targets TileLayerUnitSprite's sprite instead - not us
        val elapsedSeconds = (System.currentTimeMillis() - tileView.tileSingleAnimationStartTime) / 1000f
        if (elapsedSeconds >= TileSingleAnimation.COMBAT_FLASH_RED.totalDurationSeconds) {
            tileView.clearAnimation()
            combatFlashShown = null
            return
        }
        if (combatFlashShown == TileSingleAnimation.COMBAT_FLASH_RED) return // already flashing
        val icon = improvementIcon ?: return
        val halfDuration = TileSingleAnimation.COMBAT_FLASH_RED.totalDurationSeconds / 2
        val originalColor = icon.color.cpy()
        icon.addAction(Actions.sequence(
            Actions.color(Color.RED, halfDuration, Interpolation.sine),
            Actions.color(originalColor, halfDuration, Interpolation.sine)
        ))
        combatFlashShown = TileSingleAnimation.COMBAT_FLASH_RED
    }

    private fun updateImprovementIcon(show: Boolean) {
        val tileView = tileGroup.tileView
        // If improvement has changed, force new icon next time it is needed
        val improvementToShow = tileView.getShownImprovement()
        val newImprovementPlusPillagedID = if (improvementToShow==null) null
        else if (tileView.improvementIsPillaged) "$improvementToShow-Pillaged"
        else improvementToShow

        if (improvementPlusPillagedID != newImprovementPlusPillagedID) {
            improvementPlusPillagedID = newImprovementPlusPillagedID
            improvementIcon?.let { removeOwnedActor(it) }
            improvementIcon = null
        }

        // Get new icon when needed
        if (improvementPlusPillagedID != null && show && improvementIcon == null) {
            val icon = ImageGetter.getImprovementPortrait(improvementToShow!!, isPillaged = tileView.improvementIsPillaged)
            // Centre on tile, offset left and down
            icon.x = tileX + (tileGroup.width - icon.width) / 2 - 22f
            icon.y = tileY + (tileGroup.height - icon.height) / 2 - 12f
            addOwnedActor(icon)
            improvementIcon = icon
        }

        improvementIcon?.isVisible = show
    }

    override fun determineVisibility() {
        isVisible = improvementIcon?.isVisible == true
    }

    fun reset() {
        updateImprovementIcon(false)
    }
}

class TileLayerMisc(tileGroup: TileGroup, size: Float) : TileLayer(tileGroup, size) {

    // Lazily created — only allocated when an overlay color is applied to this tile.
    private var terrainOverlay: Image? = null

    /** Array list of all arrows to draw from this tile on the next update. */
    private val arrowsToDraw = ArrayList<MapArrow>()
    private val arrows = HashMap<Tile, ArrayList<Actor>>()

    private var hexOutlineIcon: Actor? = null

    private var workedIcon: Actor? = null

    /** Optional click handler attached to the workedIcon when it is added (used by CityScreen). */
    var onWorkedIconClick: (() -> Unit)? = null
    /** Optional double-click handler attached to the workedIcon when it is added (used by CityScreen). */
    var onWorkedIconDoubleClick: (() -> Unit)? = null

    private val startingLocationIcons = mutableListOf<Actor>()

    private fun clearArrows() {
        for (actorList in arrows.values)
            for (actor in actorList)
                removeOwnedActor(actor)
        arrows.clear()
    }

    private fun updateArrows() {
        clearArrows()
        for (arrowToAdd in arrowsToDraw) {
            val targetTile = arrowToAdd.targetTile
            if (targetTile !in arrows) arrows[targetTile] = ArrayList()

            val arrowImage = arrowToAdd.getImage()
            // tileX/tileY is this arrow's own tile's origin, absolute within whatever this layer's
            // parent is (a shared TileMapLayer for an ordinary attached tile, or a per-tile
            // standaloneWrapper's own local (0,0) for a pooled/recycling caller - see TileLayer's own
            // doc) - layoutArrowImage anchors the image there either way, tile-local.
            layoutArrowImage(arrowImage, tile, targetTile, tileX, tileY)

            arrows[targetTile]!!.add(arrowImage)
            addOwnedActor(arrowImage)
            // FIXME: Culled when too large and panned away.
            // https://libgdx.badlogicgames.com/ci/nightlies/docs/api/com/badlogic/gdx/scenes/scene2d/utils/Cullable.html
            // .getCullingArea returns null for both miscLayerGroup and worldMapHolder. Don't know where it's happening. Somewhat rare, and fixing it may have a hefty performance cost.
        }
    }

    private fun updateStartingLocationIcon(show: Boolean) {
        // The starting location icons are visible in map editor only, but this method is abused for the
        // "Show coordinates on tiles" debug option as well. Calling code made sure this is only called
        // with isVisible=false for reset, or for non-WorldMap TileGroups, or with the debug option set.
        // Note that starting locations should always be empty on the normal WorldMap - they're cleared after use.
        // Also remember the main menu background is an EditorMapHolder which we can't distinguish from
        // The actual editor use here.

        startingLocationIcons.forEach { removeOwnedActor(it) }
        startingLocationIcons.clear()
        if (!show || tileGroup.isForMapEditorIcon)
            return

        if (DebugUtils.SHOW_TILE_COORDS) {
            val label = tileGroup.tileView.position().toPrettyString()
            val tileW = tileGroup.width
            val tileH = tileGroup.height
            startingLocationIcons.add(label.toLabel(ImageGetter.CHARCOAL.cpy().apply { a = 0.7f }, 14).apply {
                touchable = Touchable.disabled
                setOrigin(Align.center)
                x = tileX + (tileW - width) / 2 + 15.4f
                y = tileY + (tileH - height) / 2 - 0.6f
                tileGroup.layerMisc.addOwnedActor(this)
            })
            startingLocationIcons.add(label.toLabel(Color.FIREBRICK, 14).apply {
                touchable = Touchable.disabled
                setOrigin(Align.center)
                x = tileX + (tileW - width) / 2 + 15f
                y = tileY + (tileH - height) / 2
                tileGroup.layerMisc.addOwnedActor(this)
            })
        }

        if (DebugUtils.SHOW_SETTLER_SCORES) {
            val score = DebugUtils.SETTLER_SCORES[tileGroup.tileView.position()]
            if (score != null) {
                val label = score.roundToInt().toString()
                val tileW = tileGroup.width
                val tileH = tileGroup.height
                startingLocationIcons.add(label.toLabel(Color.GOLD, 14).apply {
                    touchable = Touchable.disabled
                    setOrigin(Align.center)
                    x = tileX + (tileW - width) / 2 + 15f
                    y = tileY + (tileH - height) / 2
                    tileGroup.layerMisc.addOwnedActor(this)
                })
            }
        }

        val tilemap = tile.tileMap

        if (tilemap.startingLocationsByNation.isEmpty())
            return

        // Allow display of up to three nations starting locations on the same tile, rest only as count.
        // Sorted so major get precedence and to make the display deterministic, otherwise you could get
        // different stacking order of the same nations in the same editing session
        val nations = tilemap.startingLocationsByNation.asSequence()
            .filter { tile in it.value }
            .filter { it.key in tilemap.ruleset!!.nations } // Ignore missing nations
            .map { it.key to tilemap.ruleset!!.nations[it.key]!! }
            .sortedWith(compareBy({ it.second.isCityState }, { it.first }))
            .toList()
        if (nations.isEmpty()) return

        val displayCount = min(nations.size, 3)
        var offsetX = (displayCount - 1) * 4f
        var offsetY = (displayCount - 1) * 2f
        for (nation in nations.take(3).asReversed()) {
            val newNationIcon =
                    ImageGetter.getNationPortrait(nation.second, 20f)
            newNationIcon.run {
                touchable = Touchable.disabled
                setSize(20f, 20f)
                x = tileX + (tileGroup.width - 20f) / 2 + offsetX
                y = tileY + (tileGroup.height - 20f) / 2 + offsetY
                color = Color.WHITE.cpy().apply { a = 0.6f }
            }
            tileGroup.layerMisc.addOwnedActor(newNationIcon)
            startingLocationIcons.add(newNationIcon)
            offsetX -= 8f
            offsetY -= 4f
        }

        // Add a Label with the total count for this tile
        if (nations.size > 3) {
            // Tons of locations for this tile - display number in red, behind the top three
            startingLocationIcons.add(nations.size.tr().toLabel(ImageGetter.CHARCOAL.cpy().apply { a = 0.7f }, 14).apply {
                touchable = Touchable.disabled
                setOrigin(Align.center)
                x = tileX + (tileGroup.width - width) / 2 + 14.4f
                y = tileY + (tileGroup.height - height) / 2 - 9f
                tileGroup.layerMisc.addOwnedActor(this)
            })
            startingLocationIcons.add(nations.size.tr().toLabel(Color.FIREBRICK, 14).apply {
                touchable = Touchable.disabled
                setOrigin(Align.center)
                x = tileX + (tileGroup.width - width) / 2 + 14f
                y = tileY + (tileGroup.height - height) / 2 - 8.4f
                tileGroup.layerMisc.addOwnedActor(this)
            })
        }
    }


    fun removeWorkedIcon() {
        workedIcon?.let { removeOwnedActor(it) }
        workedIcon = null
        determineVisibility()
    }

    fun addWorkedIcon(icon: Actor) {
        workedIcon = icon
        onWorkedIconClick?.let { handler -> icon.onClick { handler() } }
        onWorkedIconDoubleClick?.let { handler -> icon.onDoubleClick(action = { handler() }) }
        addOwnedActor(workedIcon!!)
        determineVisibility()
    }

    fun addHexOutline(color: Color) {
        hexOutlineIcon?.let { removeOwnedActor(it) }
        hexOutlineIcon = ImageGetter.getImage("OtherIcons/HexagonOutline").apply {
            touchable = Touchable.disabled
            setHexagonSize(1f)
        }
        hexOutlineIcon!!.color = color
        addOwnedActor(hexOutlineIcon!!)
        hexOutlineIcon!!.toBack()
        determineVisibility()
    }

    fun removeHexOutline() {
        hexOutlineIcon?.let { removeOwnedActor(it) }
        hexOutlineIcon = null
        determineVisibility()
    }

    /** Activates a colored semitransparent overlay. [color] is cloned, brightened by 0.3f and an alpha of 0.4f applied. */
    fun overlayTerrain(color: Color) = overlayTerrainInner(color.brighten(0.3f).apply { a = 0.4f })

    /** Activates a colored semitransparent overlay. [color] is cloned and [alpha] applied. No brightening unlike the overload without explicit alpha! */
    fun overlayTerrain(color: Color, alpha: Float) = overlayTerrainInner(color.cpy().apply { a = alpha })

    private fun overlayTerrainInner(color: Color) {
        if (terrainOverlay == null) {
            terrainOverlay = ImageGetter.getImage(strings.hexagon).apply {
                touchable = Touchable.disabled
                setHexagonSize()
            }
            addOwnedActor(terrainOverlay!!)
        }
        terrainOverlay!!.color = color
        determineVisibility()
    }

    fun hideTerrainOverlay() {
        terrainOverlay?.let { removeOwnedActor(it) }
        terrainOverlay = null
        determineVisibility()
    }


    fun addArrow(targetTile: Tile, type: MapArrowType) {
        if (targetTile.position != tile.position)
            arrowsToDraw.add(MapArrow(targetTile, type, strings))
    }

    fun resetArrows() {
        arrowsToDraw.clear()
    }

    /** Not called from [doUpdate] - [dimPopulation] has to run *after* [WorldTileGroup.update]'s own
     *  `updateWorkedIcon` (which unconditionally recreates [workedIcon] at full alpha), or a fresh
     *  icon would immediately clobber whatever this just set - see [WorldTileGroup.update]'s own
     *  call to this. */
    fun dimPopulation(dim: Boolean) { workedIcon?.color?.a = if (dim) 0.4f else 1f }

    /**
     * Resolves [TileView.markers] into this tile's current terrain-tint overlay - the [TileMarker.MOVABLE_TO]
     * branch only applies when [com.unciv.models.metadata.GameSettings.useCirclesToIndicateMovableTiles]
     * is off (see [TileLayerOverlay.applyMarkers]'s own doc for the other half of that split), checked
     * *before* the air-range flags to match the pre-marker code's apply order: the plain movable-to
     * overlay was applied strictly after (so overrides, on any tile where both apply) the air-specific
     * one, in the original sequential `showHighlight`/`overlayTerrain` calls this replaces.
     */
    private fun applyTerrainOverlayMarkers() {
        val tileView = tileGroup.tileView
        if (!UncivGame.Current.settings.useCirclesToIndicateMovableTiles && tileView.hasMarker(TileMarker.MOVABLE_TO)) {
            val color = if (tileView.hasMarker(TileMarker.MOVABLE_TO_PARADROP)) Color.BLUE else Color.WHITE
            overlayTerrain(color, 0.4f)
            return
        }
        when {
            tileView.hasMarker(TileMarker.AIR_NUKE_BLAST) -> overlayTerrain(Color.FIREBRICK, 0.6f)
            tileView.hasMarker(TileMarker.AIR_ATTACK_RANGE) -> overlayTerrain(Color.RED)
            tileView.hasMarker(TileMarker.AIR_MOVE_RANGE_OK) -> overlayTerrain(Color.WHITE)
            tileView.hasMarker(TileMarker.AIR_MOVE_RANGE_BLOCKED) -> overlayTerrain(Color.BLUE)
        }
    }

    override fun doUpdate(viewingCiv: CivView?) {
        if (tileGroup !is WorldTileGroup || DebugUtils.SHOW_TILE_COORDS || DebugUtils.SHOW_SETTLER_SCORES)
            updateStartingLocationIcon(true)
        updateArrows()
        applyTerrainOverlayMarkers()
    }

    override fun determineVisibility() {
        isVisible = workedIcon != null
                || hexOutlineIcon != null
                || arrows.isNotEmpty()
                || startingLocationIcons.isNotEmpty()
                || terrainOverlay != null
    }

    fun reset() {
        updateStartingLocationIcon(false)
        clearArrows()
    }

}
