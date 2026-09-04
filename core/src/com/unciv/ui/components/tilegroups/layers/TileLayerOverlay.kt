package com.unciv.ui.components.tilegroups.layers

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.utils.Align
import com.unciv.UncivGame
import com.unciv.view.CivView
import com.unciv.view.CombatFlashRed
import com.unciv.view.NukeBlast
import com.unciv.view.SelectionBlink
import com.unciv.view.TileMarker
import com.unciv.view.TileSingleAnimation
import com.unciv.view.TileView
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

    /** The Actor currently rendering [TileView.playingAnimation], if any - see [applyAnimation]. */
    private var animationActor: Image? = null
    /** Which [TileView.PlayingAnimation] [animationActor] was last (re)created for - lets
     *  [applyAnimation] tell "already the right Actor for the right instance, leave it alone" apart
     *  from "just started/resumed/changed, needs (re)creating" without comparing Actors. Must be
     *  reset alongside [animationActor] on [rebind] - see that override's own comment. */
    private var animationActorFor: TileView.PlayingAnimation? = null

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
     * Resolves [TileView.playingAnimation] into [animationActor] - see that property's own doc for
     * the resume-mid-flight model this implements. Three cases:
     * - No animation set (or it's [CombatFlashRed], which tints an *existing* Actor via
     *   [TileLayerUnitSprite]/[TileLayerImprovement] instead of owning a dedicated one here - see
     *   [TileView.combatFlashUnit]'s own doc): make sure nothing's showing.
     * - Elapsed time is past the animation's own duration: it's finished - clear
     *   [TileView.playingAnimation] (nothing else does, for the animations actually reaching this
     *   point) and remove whatever's showing.
     * - Otherwise: (re)create [animationActor] if it isn't already the right kind for the right
     *   instance (a genuinely new animation, or a different one, since the last call - not just
     *   [rebind] dropping it), then delegate the actual visual seeding to
     *   [TileSingleAnimation.animateOnce] - which is itself safe to call every time [doUpdate] runs,
     *   not just once.
     */
    private fun applyAnimation() {
        val tileView = tileGroup.tileView
        val playing = tileView.playingAnimation
        if (playing == null || playing.animation == CombatFlashRed) {
            clearAnimationActor()
            return
        }
        val elapsedSeconds = (System.currentTimeMillis() - playing.startTimeMillis) / 1000f
        if (elapsedSeconds >= playing.animation.totalDurationSeconds) {
            tileView.clearAnimation()
            clearAnimationActor()
            return
        }
        if (animationActorFor != playing) {
            clearAnimationActor()
            animationActor = createAnimationActor(playing.animation).also { addOwnedActor(it) }
            animationActorFor = playing
            determineVisibility()
        }
        playing.animation.animateOnce(animationActor!!, elapsedSeconds)
    }

    private fun clearAnimationActor() {
        animationActor?.let { removeOwnedActor(it) }
        if (animationActor != null) determineVisibility()
        animationActor = null
        animationActorFor = null
    }

    /** Creates the (unseeded, unpositioned-beyond-this-tile's-origin) Actor [applyAnimation] hands to
     *  [TileSingleAnimation.animateOnce] - the specific *kind* of Actor (a plain circle for
     *  [NukeBlast], a tileset-skinned highlight hexagon for [SelectionBlink]) depends on tileset
     *  resources ([strings]) only a [TileLayer] has access to, which is why this can't just live
     *  inside the animation implementations themselves. */
    private fun createAnimationActor(animation: TileSingleAnimation): Image = when (animation) {
        NukeBlast -> ImageGetter.getCircle().apply {
            touchable = Touchable.disabled
            setSize(10f)
            setOrigin(Align.center)
            setPosition(tileX, tileY)
        }
        SelectionBlink -> getHighlight().apply {
            // Deliberately its own Image rather than toggling [highlight] itself (which
            // [applyMarkers] already manages independently, e.g. for [TileMarker.SELECTED], and
            // would otherwise fight this over the same Image's visibility).
            touchable = Touchable.disabled
        }
        else -> error("$animation doesn't own a dedicated Actor - TileLayerUnitSprite/TileLayerImprovement render it instead")
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
