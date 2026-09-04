package com.unciv.view

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Interpolation
import com.badlogic.gdx.scenes.scene2d.Action
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.utils.Align

/**
 * A one-shot animation [TileView.playAnimation] can trigger on a tile - see
 * [TileView.playingAnimation]'s own doc for the (animation, start time) pairing this is used in.
 * Each implementation is a stateless singleton `object`; all per-tile state lives on the caller-owned
 * [Actor] passed to [animateOnce], not here.
 *
 * Most animations are rendered by [com.unciv.ui.components.tilegroups.layers.TileLayerOverlay] as a
 * dedicated owned Actor it creates itself (the actor's *kind* - a plain circle for [NukeBlast], a
 * tileset-skinned highlight hexagon for [SelectionBlink] - depends on tileset resources this
 * `com.unciv.view` package has no access to, so creating it stays the caller's job).
 * [CombatFlashRed] is the exception: it tints an *existing* Actor
 * ([TileView.combatFlashUnit]'s own doc has why) via
 * [com.unciv.ui.components.tilegroups.layers.TileLayerUnitSprite]/
 * [com.unciv.ui.components.tilegroups.layers.TileLayerImprovement] instead of ever creating one.
 */
interface TileSingleAnimation {
    /** How long this animation plays for, start to finish - past this, the renderer clears
     *  [TileView.playingAnimation] on its own; nothing external needs to schedule that. */
    val totalDurationSeconds: Float

    /**
     * (Re)applies this animation's in-progress visual state to [actor], [elapsedSeconds] into
     * playback. Called on every relevant update, not just once - implementations must reseed
     * [actor]'s visual state purely from [elapsedSeconds] (never incremental/relative state) so it
     * resumes correctly if a tile scrolls out of a pooled implementation's view and back in
     * mid-flight; checking `actor.hasActions()` first is enough to skip redundant rebuilds when
     * nothing's changed since the last call (see [NukeBlast]/[CombatFlashRed] for why that check
     * matters more for one than the other).
     */
    fun animateOnce(actor: Actor, elapsedSeconds: Float)
}

/**
 * A circle that blooms outward and fades in (1s), holds (1s), then fades out (1s) - matches the
 * animation `BattleTable.simulateNuke` used to build inline as a one-off Actor. [elapsedSeconds]
 * may be anywhere in that 3s window (not just 0, if this is resuming after a
 * [com.unciv.ui.components.tilegroups.TileGroup.rebind]) - the `when` below seeds the image to the
 * exact in-progress state for whichever phase that falls in, then attaches an Action for only what's
 * left of the animation from there. Splitting the interpolation like this (rather than e.g.
 * `Actions.delay(elapsedSeconds)` then the original from-scratch sequence) is what makes a resume
 * after scrolling back in look continuous instead of restarting or jumping.
 */
object NukeBlast : TileSingleAnimation {
    override val totalDurationSeconds = 3f
    private const val fadeInDuration = 1f
    private const val holdDuration = 1f
    private const val fadeOutDuration = 1f
    private const val maxScale = 200f

    override fun animateOnce(actor: Actor, elapsedSeconds: Float) {
        if (actor.hasActions()) return
        when {
            elapsedSeconds < fadeInDuration -> {
                val progress = elapsedSeconds / fadeInDuration
                actor.color.a = Interpolation.pow2In.apply(progress)
                actor.setScale(1f + (maxScale - 1f) * progress)
                actor.addAction(Actions.sequence(
                    Actions.parallel(
                        Actions.fadeIn(fadeInDuration - elapsedSeconds, Interpolation.pow2In),
                        Actions.scaleTo(maxScale, maxScale, fadeInDuration - elapsedSeconds, Interpolation.linear)
                    ),
                    Actions.delay(holdDuration),
                    Actions.fadeOut(fadeOutDuration, Interpolation.pow2Out)
                ))
            }
            elapsedSeconds < fadeInDuration + holdDuration -> {
                actor.color.a = 1f
                actor.setScale(maxScale)
                actor.addAction(Actions.sequence(
                    Actions.delay(fadeInDuration + holdDuration - elapsedSeconds),
                    Actions.fadeOut(fadeOutDuration, Interpolation.pow2Out)
                ))
            }
            else -> {
                val progress = (elapsedSeconds - fadeInDuration - holdDuration) / fadeOutDuration
                actor.color.a = (1f - Interpolation.pow2Out.apply(progress)).coerceIn(0f, 1f)
                actor.setScale(maxScale)
                val remaining = fadeInDuration + holdDuration + fadeOutDuration - elapsedSeconds
                if (remaining > 0f) actor.addAction(Actions.fadeOut(remaining, Interpolation.pow2Out))
            }
        }
    }
}

/**
 * A standalone highlight-shaped Image that blinks hidden/shown three times - the "look here" flash
 * [com.unciv.ui.screens.worldscreen.worldmap.AbstractWorldMapHolder.setCenterPosition] plays on
 * whatever tile it just centered the view on. [elapsedSeconds] needs seeding into the right phase
 * here too, for the same reason [NukeBlast] does - rather than restarting from scratch after a
 * [com.unciv.ui.components.tilegroups.TileGroup.rebind].
 */
object SelectionBlink : TileSingleAnimation {
    override val totalDurationSeconds = 1.8f
    private const val halfCycle = 0.3f // hidden for one halfCycle, then shown for the next

    override fun animateOnce(actor: Actor, elapsedSeconds: Float) {
        if (actor.hasActions()) return
        val cycleIndex = (elapsedSeconds / (halfCycle * 2)).toInt()
        val positionInCycle = elapsedSeconds - cycleIndex * halfCycle * 2
        val remainingFullCycles = 2 - cycleIndex // 0..2 more full hidden/shown pairs after this one

        val sequence = mutableListOf<Action>()
        if (positionInCycle < halfCycle) {
            actor.isVisible = false
            sequence += Actions.delay(halfCycle - positionInCycle)
            sequence += Actions.run { actor.isVisible = true }
            sequence += Actions.delay(halfCycle)
        } else {
            actor.isVisible = true
            sequence += Actions.delay(halfCycle * 2 - positionInCycle)
        }
        repeat(remainingFullCycles) {
            sequence += Actions.run { actor.isVisible = false }
            sequence += Actions.delay(halfCycle)
            sequence += Actions.run { actor.isVisible = true }
            sequence += Actions.delay(halfCycle)
        }
        actor.addAction(Actions.sequence(*sequence.toTypedArray()))
    }
}

/**
 * The red tint `BattleTableHelpers.battleAnimation` plays on a combat participant's own sprite/icon -
 * fades to red over the first half, back to its original color over the second. Unlike
 * [NukeBlast]/[SelectionBlink], [actor] here is an *existing* Actor the caller already owns (a
 * sprite-slot child, or a city's improvement icon), tinted in place rather than a dedicated Actor of
 * this animation's own - see [TileView.combatFlashUnit]'s own doc for why. No resume-mid-flight
 * seeding: at 0.4s total, a pool eviction landing exactly inside that window is negligible, so this
 * always (re)plays the flash fresh from [actor]'s current actual color rather than computing where a
 * precise resume would be - [elapsedSeconds] is unused.
 */
object CombatFlashRed : TileSingleAnimation {
    override val totalDurationSeconds = 0.4f

    override fun animateOnce(actor: Actor, elapsedSeconds: Float) {
        if (actor.hasActions()) return // already flashing - don't restart mid-tween
        val halfDuration = totalDurationSeconds / 2
        val originalColor = actor.color.cpy()
        actor.addAction(Actions.sequence(
            Actions.color(Color.RED, halfDuration, Interpolation.sine),
            Actions.color(originalColor, halfDuration, Interpolation.sine)
        ))
    }
}
