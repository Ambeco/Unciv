package com.unciv.ui.components.tilegroups.layers

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Interpolation
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.unciv.UncivGame
import com.unciv.view.CivView
import com.unciv.view.ForeignMapUnitView
import com.unciv.view.TileSingleAnimation
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.ui.components.NonTransformGroup
import com.unciv.ui.components.tilegroups.TileGroup
import com.unciv.ui.images.ImageGetter

class UnitSpriteSlot {
    val spriteGroup = NonTransformGroup()
    var currentImageLocation = ""
}

class TileLayerUnitSprite(tileGroup: TileGroup, size: Float) : TileLayer(tileGroup, size) {

    // Slots are only filled if units exist, and images for those units exist
    private var civilianSlot: UnitSpriteSlot? = null
    private var militarySlot: UnitSpriteSlot? = null


    fun getSpriteSlot(unit: MapUnit) = if (unit.isCivilian()) civilianSlot else militarySlot

    private fun updateSlot(currentSlot: UnitSpriteSlot?, unitView: ForeignMapUnitView?, isShown: Boolean): UnitSpriteSlot? {

        var location = ""
        var nationName = ""

        if (unitView != null && isShown && UncivGame.Current.settings.showPixelUnits) {
            location = strings.getUnitImageLocation(unitView)
            nationName = "${unitView.civName}-"
        }

        if (currentSlot == null && location == "") return null // No-op - had none, has none
        if (currentSlot?.currentImageLocation == "$nationName$location") return currentSlot // No-op - had, has

        if (location == "" || !ImageGetter.imageExists(location)){
            currentSlot?.spriteGroup?.let { removeOwnedActor(it) }
            return null
        }

        val slot = currentSlot ?: UnitSpriteSlot().apply {
            // Position the container at the tile origin so children use tile-local coords.
            // This keeps spriteGroup visible to viewport culling and preserves the
            // tile-local child positions that WorldMapHolder's animation code relies on.
            spriteGroup.setPosition(tileX, tileY)
            spriteGroup.setSize(size, size)
            addOwnedActor(spriteGroup)
        }
        slot.currentImageLocation = "$nationName$location"
        slot.spriteGroup.clear()

        val civView = unitView!!.civ()
        val pixelUnitImages = ImageGetter.getLayeredImageColored(
            location,
            null,
            civView.getInnerColor(),
            civView.getOuterColor()
        )
        for (pixelUnitImage in pixelUnitImages) {
            slot.spriteGroup.addActor(pixelUnitImage)
            // tileLocal=true: spriteGroup is already at (tileX,tileY) so children only need
            // the hex-local offset (hexagonImagePosition), not the full absolute position.
            pixelUnitImage.setHexagonSize(tileLocal = true)
        }
        return slot
    }

    fun dim() {
        forEachOwnedActor { it.color.a = 0.5f }
    }

    /** Which [TileSingleAnimation] this layer last attached its own flash [Actions] for - lets
     *  [applyCombatFlash] tell "already flashing, its own Actions keep running" apart from "just
     *  started, needs attaching" without comparing Actors. Cleared on [rebind] for the same reason
     *  [TileLayerOverlay]'s own `animationShown` is - a freshly-bound tile's slots are brand new
     *  Actors regardless of what this field says. */
    private var combatFlashShown: TileSingleAnimation? = null

    /**
     * [TileSingleAnimation.COMBAT_FLASH_RED] doesn't build a dedicated owned Actor the way
     * [TileLayerOverlay]'s animations do - it tints whichever sprite slot [TileView.combatFlashUnit]
     * names, since that's an *existing* Actor this layer already owns, not a new one - so, unlike
     * [TileLayerOverlay], this layer is the one responsible for clearing [TileView.tileSingleAnimation]
     * once elapsed time is past [TileSingleAnimation.totalDurationSeconds], for the one animation type
     * it actually renders. No resume-mid-flight seeding (contrast [TileLayerOverlay]'s
     * `buildNukeBlastActor`): at 0.4s total, a tile scrolling out of a pooled implementation's view
     * and back in mid-flash is negligible, so this always (re)plays the flash from its current actual
     * color rather than computing where a precise resume would be.
     */
    private fun applyCombatFlash() {
        val tileView = tileGroup.tileView
        if (tileView.tileSingleAnimation != TileSingleAnimation.COMBAT_FLASH_RED) {
            combatFlashShown = null
            return
        }
        val unit = tileView.combatFlashUnit ?: return // targets TileLayerImprovement's icon instead - not us
        val elapsedSeconds = (System.currentTimeMillis() - tileView.tileSingleAnimationStartTime) / 1000f
        if (elapsedSeconds >= TileSingleAnimation.COMBAT_FLASH_RED.totalDurationSeconds) {
            tileView.clearAnimation()
            combatFlashShown = null
            return
        }
        if (combatFlashShown == TileSingleAnimation.COMBAT_FLASH_RED) return // already flashing
        val slot = getSpriteSlot(unit) ?: return
        val halfDuration = TileSingleAnimation.COMBAT_FLASH_RED.totalDurationSeconds / 2
        for (child in slot.spriteGroup.children) {
            val originalColor = child.color.cpy()
            child.addAction(Actions.sequence(
                Actions.color(Color.RED, halfDuration, Interpolation.sine),
                Actions.color(originalColor, halfDuration, Interpolation.sine)
            ))
        }
        combatFlashShown = TileSingleAnimation.COMBAT_FLASH_RED
    }

    override fun doUpdate(viewingCiv: CivView?) {

        val isPixelUnitsEnabled = UncivGame.Current.settings.showPixelUnits
        val isViewable = viewingCiv == null || tileGroup.isForceVisible || isViewable(viewingCiv)

        val isCivilianSlotShown = isPixelUnitsEnabled && isViewable
        val isMilitarySlotShown = isPixelUnitsEnabled && isViewable

        civilianSlot = updateSlot(civilianSlot, tileGroup.tileView.civilianUnit, isShown = isCivilianSlotShown)
        militarySlot = updateSlot(militarySlot, tileGroup.tileView.militaryUnit, isShown = isMilitarySlotShown)

        applyCombatFlash()
    }

    override fun determineVisibility() {
        isVisible = civilianSlot != null || militarySlot != null
    }

    fun reset() {
        civilianSlot?.spriteGroup?.let { removeOwnedActor(it) }
        militarySlot?.spriteGroup?.let { removeOwnedActor(it) }
        civilianSlot = null
        militarySlot = null
    }

    override fun rebind(newTileX: Float, newTileY: Float) {
        super.rebind(newTileX, newTileY) // drops both sprite-slot Groups as owned actors
        // Slots are also position-pinned at creation time (spriteGroup.setPosition in updateSlot).
        civilianSlot = null
        militarySlot = null
        combatFlashShown = null
    }
}
