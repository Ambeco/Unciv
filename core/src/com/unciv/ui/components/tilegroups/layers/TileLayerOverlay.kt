package com.unciv.ui.components.tilegroups.layers

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Interpolation
import com.badlogic.gdx.scenes.scene2d.Action
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.utils.Align
import com.unciv.UncivGame
import com.unciv.view.CivView
import com.unciv.view.TileMarker
import com.unciv.view.TileSingleAnimation
import com.unciv.ui.components.extensions.colorFromRGB
import com.unciv.ui.components.extensions.setSize
import com.unciv.ui.components.tilegroups.TileGroup
import com.unciv.ui.images.ImageGetter

class TileLayerOverlay(tileGroup: TileGroup, size: Float) : TileLayer(tileGroup, size) {

    private var highlight: Image? = null // for blue and red circles/emphasis on the tile
    private var crosshair: Image? = null // for when a unit is targeted
    private var goodCityLocationIndicator: Image? = null
    private var fog: Image? = null
    private var unexplored: Image? = null

    /** The Actor currently rendering [TileView.tileSingleAnimation], if any - see [applyAnimation]. */
    private var animationActor: Image? = null
    /** Which [TileSingleAnimation] [animationActor] was last (re)created for - lets [applyAnimation]
     *  tell "already playing correctly, leave its own Action alone" apart from "just started/resumed,
     *  needs (re)creating" without comparing Actors. Must be reset alongside [animationActor] on
     *  [rebind] - see that override's own comment. */
    private var animationShown: TileSingleAnimation? = null

    private fun getHighlight() = ImageGetter.getImage(strings.highlight).setHexagonSize()
    private fun getCrosshair() = ImageGetter.getImage(strings.crosshair).setHexagonSize()
    private fun getGoodCityLocationIndicator() = ImageGetter.getImage("OtherIcons/Cities").setHexagonSize(0.25f)
    private fun getFog() = ImageGetter.getImage(strings.crosshatchHexagon).setHexagonSize().apply {
        color = Color.WHITE.cpy().apply { a = 0.2f }
    }
    private fun getUnexplored() = ImageGetter.getImage(strings.unexploredTile).setHexagonSize()

    fun showCrosshair(alpha: Float = 1f) {
        if (crosshair == null) {
            crosshair = getCrosshair()
            addOwnedActor(crosshair!!)
            determineVisibility()
        }
        crosshair?.color?.a = alpha
    }

    fun hideCrosshair() {
        if (crosshair == null) return
        removeOwnedActor(crosshair!!)
        crosshair = null
        determineVisibility()
    }

    fun showHighlight(color: Color = Color.WHITE, alpha: Float = 0.3f) {
        if (highlight == null) {
            highlight = getHighlight()
            addOwnedActor(highlight!!)
            determineVisibility()
        }
        highlight?.color = color.cpy().apply { a = alpha }
    }

    fun hideHighlight() {
        if (highlight == null) return
        removeOwnedActor(highlight!!)
        highlight = null
        determineVisibility()
    }

    fun showGoodCityLocationIndicator() {
        if (goodCityLocationIndicator != null) return
        goodCityLocationIndicator = getGoodCityLocationIndicator()
        addOwnedActor(goodCityLocationIndicator!!)
        determineVisibility()
    }

    fun hideGoodCityLocationIndicator() {
        if (goodCityLocationIndicator == null) return
        removeOwnedActor(goodCityLocationIndicator!!)
        goodCityLocationIndicator = null
        determineVisibility()
    }

    fun reset() {
        hideHighlight()
        hideCrosshair()
        hideGoodCityLocationIndicator()
        determineVisibility()
    }

    override fun doUpdate(viewingCiv: CivView?) {
        val isViewable = viewingCiv == null || isViewable(viewingCiv)

        setFog(isViewable)

        if (viewingCiv == null) return

        setUnexplored(viewingCiv)

        applyMarkers()
        applyAnimation()

        val tileView = tileGroup.tileView
        val improvement = tileView.getRuleset().tileImprovements[tileView.getShownImprovement()]
        // Lowest priority: only shown if nothing above already claimed the highlight this pass -
        // matches the pre-marker code, where this ran as part of the *general* per-tile update pass,
        // strictly before any selection-driven highlight had a chance to override it.
        if (highlight == null && improvement?.isBarbarianCampEquivalent() == true && viewingCiv.hasExplored(tileView))
            showHighlight(Color.RED)
    }

    /**
     * Resolves [TileView.markers] (see [WorldMapTileUpdater][com.unciv.ui.screens.worldscreen.worldmap.WorldMapTileUpdater]
     * for where they're set) into this tile's current highlight/crosshair/good-city-location-indicator.
     * The `when` branches are checked in descending priority - matching the exact order the
     * pre-marker code applied its equivalent sequence of `showHighlight`/`showCrosshair` calls in
     * (each one clobbering the last on the same tile), from [TileMarker.SELECTED] (applied
     * unconditionally last, so highest priority here) down to the swap/road-connect/spy/bombard
     * group (each only ever set by a mutually exclusive selection mode, so their relative order here
     * doesn't actually matter in practice).
     */
    private fun applyMarkers() {
        val tileView = tileGroup.tileView
        val tapAlpha = if (UncivGame.Current.settings.singleTapMove) 0.7f else 0.3f
        val attackColor = colorFromRGB(237, 41, 57)

        when {
            tileView.hasMarker(TileMarker.SELECTED) -> showHighlight(Color.WHITE)
            tileView.hasMarker(TileMarker.ATTACK_SOURCE) -> showHighlight(Color.SKY, 0.7f)
            tileView.hasMarker(TileMarker.ATTACKABLE) -> showHighlight(attackColor)
            tileView.hasMarker(TileMarker.MOVEMENT_DESTINATION) -> showHighlight(Color.WHITE, 0.7f)
            tileView.hasMarker(TileMarker.ROAD_AUTOMATION_FUTURE) -> showHighlight(Color.ORANGE, tapAlpha)
            tileView.hasMarker(TileMarker.MOVEMENT_PATH) -> showHighlight(Color.SKY, 0.8f)
            tileView.hasMarker(TileMarker.AIR_ATTACK_ONLY) -> showHighlight(Color.RED, 0.3f)
            // Only the circle-style indicator lives here - when the setting prefers a terrain tint
            // instead, TileLayerMisc's own applyTerrainOverlayMarkers() handles MOVABLE_TO instead
            // (matching the pre-marker code's own if (useCircles) showHighlight(...) else
            // overlayTerrain(...) branch).
            tileView.hasMarker(TileMarker.MOVABLE_TO) && UncivGame.Current.settings.useCirclesToIndicateMovableTiles ->
                showHighlight(if (tileView.hasMarker(TileMarker.MOVABLE_TO_PARADROP)) Color.BLUE else Color.WHITE, tapAlpha)
            tileView.hasMarker(TileMarker.ROAD_CONNECT_PATH) -> showHighlight(Color.ORANGE, 0.8f)
            tileView.hasMarker(TileMarker.ROAD_CONNECT_VALID) -> showHighlight(Color.RED, 0.3f)
            tileView.hasMarker(TileMarker.SWAP_TARGET) -> showHighlight(Color.PURPLE, tapAlpha)
            tileView.hasMarker(TileMarker.SPY_TARGET_CITY) -> showHighlight(Color.CYAN, 0.7f)
            tileView.hasMarker(TileMarker.BOMBARDABLE) -> showHighlight(attackColor)
        }

        if (tileView.hasMarker(TileMarker.ATTACKABLE))
            showCrosshair(if (tileView.hasMarker(TileMarker.ATTACKABLE_NEEDS_MOVE)) 0.5f else 1f)
        else if (tileView.hasMarker(TileMarker.BOMBARDABLE))
            showCrosshair()

        if (tileView.hasMarker(TileMarker.SUGGESTED_CITY_SITE))
            showGoodCityLocationIndicator()
    }

    /**
     * Resolves [TileView.tileSingleAnimation]/[TileView.tileSingleAnimationStartTime] into
     * [animationActor] - see [TileView.tileSingleAnimation]'s own doc for the resume-mid-flight
     * model this implements. Three cases:
     * - No animation set (or it's [TileSingleAnimation.COMBAT_FLASH_RED], which tints an *existing*
     *   Actor via [TileLayerUnitSprite]/[TileLayerImprovement] instead of owning a dedicated one
     *   here - see [TileView.combatFlashUnit]'s own doc): make sure nothing's showing.
     * - Elapsed time is past the animation's own duration: it's finished - clear
     *   [TileView.tileSingleAnimation] (nothing else does, for the animations actually reaching this
     *   point) and remove whatever's showing.
     * - Otherwise: if [animationActor] is already showing *this* animation, leave it alone - its own
     *   [Actions] keep it animating every frame regardless of how many more times [doUpdate] runs
     *   before it finishes. Only (re)create it - seeded to the correct in-progress state for the
     *   current elapsed time, with an Action covering only the remaining duration - when it's
     *   genuinely new or was just dropped by a [rebind] mid-flight.
     */
    private fun applyAnimation() {
        val tileView = tileGroup.tileView
        val animation = tileView.tileSingleAnimation
        if (animation == null || animation == TileSingleAnimation.COMBAT_FLASH_RED) {
            clearAnimationActor()
            return
        }
        val elapsedSeconds = (System.currentTimeMillis() - tileView.tileSingleAnimationStartTime) / 1000f
        if (elapsedSeconds >= animation.totalDurationSeconds) {
            tileView.clearAnimation()
            clearAnimationActor()
            return
        }
        if (animationShown == animation) return
        clearAnimationActor()
        animationActor = buildAnimationActor(animation, elapsedSeconds).also { addOwnedActor(it) }
        animationShown = animation
        determineVisibility()
    }

    private fun clearAnimationActor() {
        animationActor?.let { removeOwnedActor(it) }
        if (animationActor != null || animationShown != null) determineVisibility()
        animationActor = null
        animationShown = null
    }

    private fun buildAnimationActor(animation: TileSingleAnimation, elapsedSeconds: Float): Image = when (animation) {
        TileSingleAnimation.NUKE_BLAST -> buildNukeBlastActor(elapsedSeconds)
        TileSingleAnimation.SELECTION_BLINK -> buildSelectionBlinkActor(elapsedSeconds)
        TileSingleAnimation.COMBAT_FLASH_RED -> error(
            "COMBAT_FLASH_RED is rendered by TileLayerUnitSprite/TileLayerImprovement, not " +
                "TileLayerOverlay - applyAnimation() should never reach here for it")
    }

    /**
     * A circle that blooms outward and fades in (1s), holds (1s), then fades out (1s) - matches the
     * animation `BattleTable.simulateNuke` used to build inline as a one-off Actor. [elapsedSeconds]
     * may be anywhere in that 3s window (not just 0, if this is resuming after a [rebind]) - the
     * `when` below seeds the image to the exact in-progress state for whichever phase that falls in,
     * then attaches an Action for only what's left of the animation from there. Splitting the
     * interpolation like this (rather than e.g. `Actions.delay(elapsedSeconds)` then the original
     * from-scratch sequence) is what makes a resume after scrolling back in look continuous instead
     * of restarting or jumping.
     */
    private fun buildNukeBlastActor(elapsedSeconds: Float): Image {
        val fadeInDuration = 1f
        val holdDuration = 1f
        val fadeOutDuration = 1f
        val maxScale = 200f

        val image = ImageGetter.getCircle()
        image.touchable = Touchable.disabled
        image.setSize(10f)
        image.setOrigin(Align.center)
        image.setPosition(tileX, tileY)

        when {
            elapsedSeconds < fadeInDuration -> {
                val progress = elapsedSeconds / fadeInDuration
                image.color.a = Interpolation.pow2In.apply(progress)
                image.setScale(1f + (maxScale - 1f) * progress)
                image.addAction(Actions.sequence(
                    Actions.parallel(
                        Actions.fadeIn(fadeInDuration - elapsedSeconds, Interpolation.pow2In),
                        Actions.scaleTo(maxScale, maxScale, fadeInDuration - elapsedSeconds, Interpolation.linear)
                    ),
                    Actions.delay(holdDuration),
                    Actions.fadeOut(fadeOutDuration, Interpolation.pow2Out)
                ))
            }
            elapsedSeconds < fadeInDuration + holdDuration -> {
                image.color.a = 1f
                image.setScale(maxScale)
                image.addAction(Actions.sequence(
                    Actions.delay(fadeInDuration + holdDuration - elapsedSeconds),
                    Actions.fadeOut(fadeOutDuration, Interpolation.pow2Out)
                ))
            }
            else -> {
                val progress = (elapsedSeconds - fadeInDuration - holdDuration) / fadeOutDuration
                image.color.a = (1f - Interpolation.pow2Out.apply(progress)).coerceIn(0f, 1f)
                image.setScale(maxScale)
                val remaining = fadeInDuration + holdDuration + fadeOutDuration - elapsedSeconds
                if (remaining > 0f) image.addAction(Actions.fadeOut(remaining, Interpolation.pow2Out))
            }
        }
        return image
    }

    /**
     * A standalone highlight-shaped Image that blinks hidden/shown three times - the "look here"
     * flash [com.unciv.ui.screens.worldscreen.worldmap.WorldMapHolder.setCenterPosition] plays on
     * whatever tile it just centered the view on. Deliberately its own Image rather than toggling
     * [highlight] itself (which [applyMarkers] already manages independently, e.g. for
     * [TileMarker.SELECTED], and would otherwise fight this over the same Image's visibility) - see
     * [buildNukeBlastActor]'s own doc for why [elapsedSeconds] needs seeding into the right phase
     * here too, rather than restarting from scratch after a [rebind].
     */
    private fun buildSelectionBlinkActor(elapsedSeconds: Float): Image {
        val halfCycle = 0.3f // hidden for one halfCycle, then shown for the next - matches the
                              // pre-TileSingleAnimation Actions.repeat(3, sequence(delay(.3f)...)) this replaces

        val image = getHighlight()
        image.touchable = Touchable.disabled

        val cycleIndex = (elapsedSeconds / (halfCycle * 2)).toInt()
        val positionInCycle = elapsedSeconds - cycleIndex * halfCycle * 2
        val remainingFullCycles = 2 - cycleIndex // 0..2 more full hidden/shown pairs after this one

        val sequence = mutableListOf<Action>()
        if (positionInCycle < halfCycle) {
            image.isVisible = false
            sequence += Actions.delay(halfCycle - positionInCycle)
            sequence += Actions.run { image.isVisible = true }
            sequence += Actions.delay(halfCycle)
        } else {
            image.isVisible = true
            sequence += Actions.delay(halfCycle * 2 - positionInCycle)
        }
        repeat(remainingFullCycles) {
            sequence += Actions.run { image.isVisible = false }
            sequence += Actions.delay(halfCycle)
            sequence += Actions.run { image.isVisible = true }
            sequence += Actions.delay(halfCycle)
        }
        image.addAction(Actions.sequence(*sequence.toTypedArray()))
        return image
    }

    fun setUnexplored(viewingCiv: CivView) {
        val unexploredShouldBeVisible = !viewingCiv.hasExplored(tileGroup.tileView)
        val unexploredIsVisible = unexplored != null
        if (unexploredIsVisible && !unexploredShouldBeVisible) {
            removeOwnedActor(unexplored!!)
            unexplored = null
            determineVisibility()
        } else if (!unexploredIsVisible && unexploredShouldBeVisible
                && ImageGetter.imageExists(strings.unexploredTile)) {
            unexplored = getUnexplored()
            addOwnedActor(unexplored!!)
            determineVisibility()
        }
    }

    private fun setFog(isViewable: Boolean) {
        val fogShouldBeVisible = !isViewable && !tileGroup.isForceVisible
        val fogIsVisible = fog != null
        if (fogIsVisible && !fogShouldBeVisible) {
            removeOwnedActor(fog!!)
            fog = null
            determineVisibility()
        } else if (!fogIsVisible && fogShouldBeVisible) {
            fog = getFog()
            addOwnedActor(fog!!)
            determineVisibility()
        }
    }

    override fun determineVisibility() {
        isVisible = fog != null || unexplored != null || highlight != null || crosshair != null ||
            goodCityLocationIndicator != null || animationActor != null
    }
}
