package com.unciv.view

/**
 * One-shot animations [TileView.playAnimation] can trigger on a tile - see [TileView.tileSingleAnimation]'s
 * own doc for the (type, start time) model this is paired with. Most are rendered by
 * [com.unciv.ui.components.tilegroups.layers.TileLayerOverlay] as a dedicated owned Actor/Action -
 * [COMBAT_FLASH_RED] is the exception, tinting an *existing* Actor
 * ([TileView.combatFlashUnit]'s own doc has why) via
 * [com.unciv.ui.components.tilegroups.layers.TileLayerUnitSprite]/
 * [com.unciv.ui.components.tilegroups.layers.TileLayerImprovement] instead.
 *
 * @param totalDurationSeconds How long this animation plays for, start to finish - past this, the
 * renderer clears [TileView.tileSingleAnimation] on its own; nothing external needs to schedule that.
 */
enum class TileSingleAnimation(val totalDurationSeconds: Float) {
    NUKE_BLAST(3f),
    /** The "look here" flash [com.unciv.ui.screens.worldscreen.worldmap.WorldMapHolder.setCenterPosition]
     *  plays on whatever tile it just centered the view on - three .3s-hidden/.3s-shown blinks. */
    SELECTION_BLINK(1.8f),
    /** The red tint `BattleTableHelpers.battleAnimation` plays on a combat participant's own sprite/
     *  icon - fades to red over the first half, back to its original color over the second. */
    COMBAT_FLASH_RED(0.4f),
}
