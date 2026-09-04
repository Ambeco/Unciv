package com.unciv.ui.components.tilegroups.layers

import com.unciv.UncivGame
import com.unciv.view.CivView
import com.unciv.view.CombatFlashRed
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

    /**
     * [CombatFlashRed] doesn't build a dedicated owned Actor the way [TileLayerOverlay]'s animations
     * do - it tints whichever sprite slot [TileView.combatFlashUnit] names, since that's an
     * *existing* Actor this layer already owns, not a new one - so, unlike [TileLayerOverlay], this
     * layer is the one responsible for clearing [TileView.playingAnimation] once elapsed time is past
     * [TileSingleAnimation.totalDurationSeconds], for the one animation type it actually renders.
     */
    private fun applyCombatFlash() {
        val tileView = tileGroup.tileView
        val playing = tileView.playingAnimation
        if (playing == null || playing.animation != CombatFlashRed) return
        val unit = tileView.combatFlashUnit ?: return // targets TileLayerImprovement's icon instead - not us
        val elapsedSeconds = (System.currentTimeMillis() - playing.startTimeMillis) / 1000f
        if (elapsedSeconds >= playing.animation.totalDurationSeconds) {
            tileView.clearAnimation()
            return
        }
        val slot = getSpriteSlot(unit) ?: return
        for (child in slot.spriteGroup.children)
            CombatFlashRed.animateOnce(child, elapsedSeconds)
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
}
