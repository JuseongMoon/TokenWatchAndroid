package com.ScienceFiction.TokenWatchAndroid.ui.components

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ScienceFiction.TokenWatchAndroid.domain.Agent
import com.ScienceFiction.TokenWatchAndroid.domain.UsageStyle
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.localization.monsterA11yValue
import com.ScienceFiction.TokenWatchAndroid.localization.monsterAttack
import com.ScienceFiction.TokenWatchAndroid.localization.monsterName
import com.ScienceFiction.TokenWatchAndroid.monster.MonsterConfig
import com.ScienceFiction.TokenWatchAndroid.monster.MonsterDraw
import com.ScienceFiction.TokenWatchAndroid.monster.MonsterEncounter
import com.ScienceFiction.TokenWatchAndroid.monster.MonsterSpriteData
import com.ScienceFiction.TokenWatchAndroid.monster.MonsterSpriteDecoder
import com.ScienceFiction.TokenWatchAndroid.monster.MonsterSpriteSet
import com.ScienceFiction.TokenWatchAndroid.monster.MonsterStore
import com.ScienceFiction.TokenWatchAndroid.ui.theme.Term
import java.time.Instant
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** What the gauges need for monsters. Null (the default) in previews and tests: gauges draw none. */
class MonsterUi(val store: MonsterStore, val sprites: MonsterSpriteSet)

val LocalMonsterUi = staticCompositionLocalOf<MonsterUi?> { null }

/** One gauge row's monsters: what to draw, the one a tap hits, and the attack. */
class GaugeMonsters(
    val encounters: List<MonsterEncounter>,
    val lastHitAtMs: Map<String, Long>,
    val config: MonsterConfig,
    val sprites: MonsterSpriteSet,
    private val ui: MonsterUi,
) {
    /** The living monster a tap on the row hits. */
    val target: MonsterEncounter? get() = encounters.firstOrNull { it.isAlive }

    /** A hit with haptics: a light tick for damage, a confirmation for a defeat. */
    fun attack(encounter: MonsterEncounter, haptics: HapticFeedback) {
        when (ui.store.hit(encounter.key)) {
            is MonsterStore.HitResult.Damaged -> haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            MonsterStore.HitResult.Defeated -> haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            MonsterStore.HitResult.Ignored -> Unit
        }
    }
}

/**
 * The monsters on [window]'s gauge, or null with none (no monster UI, a subscription gauge without an
 * agent, or the creatures switched off). Recomposes as the monster state changes; [now] (the row's
 * minute clock) drops expired ones.
 */
@Composable
internal fun rememberGaugeMonsters(agent: Agent?, window: UsageWindow, enabled: Boolean, now: Instant): GaugeMonsters? {
    val ui = LocalMonsterUi.current ?: return null
    if (agent == null || !enabled || window.style != UsageStyle.GAUGE) return null
    val state by ui.store.state.collectAsStateWithLifecycle()
    val encounters = remember(state, agent.id, window, now) {
        MonsterStore.visible(state, agent.id, agent.provider, window, now.toEpochMilli())
    }
    if (encounters.isEmpty()) return null
    return GaugeMonsters(encounters, state.lastHitAtMs, state.config, ui.sprites, ui)
}

/**
 * Makes a whole gauge row the monster's hit area while a living one is on it: the tap is taken before the
 * card's own (detail) tap. TalkBack gets an "attack" action and the HP left.
 */
internal fun Modifier.monsterHitTarget(monsters: GaugeMonsters?, loc: L10n, haptics: HapticFeedback): Modifier {
    val target = monsters?.target ?: return this
    val attack = { monsters.attack(target, haptics) }
    return this
        // A monster row always has a reset line, so this rarely changes the layout; it pins the 48 dp target.
        .heightIn(min = 48.dp)
        .semantics(mergeDescendants = true) {
            stateDescription = loc.monsterA11yValue(loc.monsterName(target.monsterId), target.hp, target.maxHp)
            customActions = listOf(CustomAccessibilityAction(loc.monsterAttack) { attack(); true })
        }
        .clickable(interactionSource = null, indication = null, onClickLabel = loc.monsterAttack, onClick = attack)
}

/** HP left as hearts (special monsters, after the first hit). Past five, `+n`. */
@Composable
internal fun MonsterHpHearts(hp: Int, maxHp: Int, modifier: Modifier = Modifier) {
    if (maxHp <= 1 || hp >= maxHp || hp <= 0) return
    Row(
        modifier = modifier.clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(min(hp, 5)) { PixelHeart(size = 8.dp) }
        if (hp > 5) {
            Text(
                text = "+${hp - 5}",
                color = Term.Foreground,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp),
            )
        }
    }
}

/**
 * The monsters on a gauge bar. They march across like the slime critter: a completion monster in from the
 * bar's left edge, a random one from the spot it appeared at. A hit drops a sword from above; the last hit holds the sword, plays the monster's
 * death animation, then a gray ghost with a halo walks at half speed — across the bar for a completion
 * monster, on from the spot of the defeat for a random one, coming back in from the left past the right edge
 * for an hour. Taps are taken by the whole row, not by this layer.
 *
 * The body, the sword and the halo are clipped to the bar's width, so they walk in and out at the ends
 * together; the sword and the halo may rise above the bar, which is why the gauge box itself does not clip.
 */
@Composable
internal fun GaugeMonsterLayer(
    monsters: GaugeMonsters,
    sessionWindow: Boolean,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = !ValueAnimator.areAnimatorsEnabled(),
) {
    var frameMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            frameMs = System.currentTimeMillis()
            delay(GaugeMonsterMotion.FRAME_MS)
        }
    }
    val looks = remember(monsters.encounters, sessionWindow, monsters.config, monsters.sprites) {
        monsters.encounters.map { MonsterLooks.of(it, monsters.config, monsters.sprites, sessionWindow) }
    }
    val sword = remember(monsters.sprites) { monsters.sprites.sword?.toPixelSprite(gray = false) }
    val halo = remember(monsters.sprites) { monsters.sprites.halo?.toPixelSprite(gray = false) }
    val tickMs = if (sessionWindow) GaugeCritterVariant.SESSION.baseTickMillis else GaugeCritterVariant.WEEKLY.baseTickMillis

    Canvas(modifier = modifier.clearAndSetSemantics { }) {
        // Read here so a new frame only redraws; it never recomposes the gauge.
        val now = frameMs
        val inset = 2.dp.toPx()
        val bottom = 1.dp.toPx()
        for ((encounter, look) in monsters.encounters.zip(looks)) {
            val cell = max(0f, size.height - inset) / look.living.rows
            val pose = GaugeMonsterMotion.pose(
                encounter = encounter,
                lastHitAtMs = monsters.lastHitAtMs[encounter.key],
                nowMs = now,
                barWidth = size.width.toDouble(),
                cell = cell.toDouble(),
                livingColumns = look.living.columns,
                deathFrames = look.death?.frames?.size ?: 0,
                tickMs = tickMs,
                reduceMotion = reduceMotion,
            ) ?: continue
            val sprite = when (pose.look) {
                GaugeMonsterMotion.Look.LIVING -> look.living
                GaugeMonsterMotion.Look.DEATH -> look.death ?: look.living
                GaugeMonsterMotion.Look.GHOST -> look.ghost
            }
            val x = pose.x.toFloat()
            val top = size.height - bottom - cell * sprite.rows
            val width = cell * sprite.columns
            clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height) {
                drawPixelSpriteFrame(sprite.frame(pose.frame), sprite.palette, cell, origin = Offset(x, top))
            }
            if (pose.halo && halo != null) {
                val bob = if (reduceMotion) 0f else ((now / 500L) % 2L).toFloat() * cell
                drawEffect(halo, cell, x + (width - cell * halo.columns) / 2f, top - cell * halo.rows + cell - bob)
            }
            if (pose.sword && sword != null) {
                drawEffect(sword, cell, x + (width - cell * sword.columns) / 2f, top - cell * sword.rows * 0.55f)
            }
        }
    }
}

/** The sword or the halo: free to rise above the bar, but cut at the bar's ends like the body. */
private fun DrawScope.drawEffect(sprite: PixelSprite, cell: Float, x: Float, y: Float) =
    clipRect(left = 0f, top = y, right = size.width, bottom = y + cell * sprite.rows) {
        drawPixelSpriteFrame(sprite.frame(0), sprite.palette, cell, origin = Offset(x, y))
    }

/** The three drawings of one monster: alive, dying (per-monster animation) and the gray ghost. */
private class MonsterLooks(val living: PixelSprite, val death: PixelSprite?, val ghost: PixelSprite) {
    companion object {
        fun of(
            encounter: MonsterEncounter,
            config: MonsterConfig,
            sprites: MonsterSpriteSet,
            sessionWindow: Boolean,
        ): MonsterLooks {
            val body = sprites.body(encounter.monsterId, config.entry(encounter.monsterId))
            val slime = if (sessionWindow) PixelSprite.SlimeSky else PixelSprite.Slime
            return MonsterLooks(
                living = body?.toPixelSprite(gray = false) ?: slime,
                death = sprites.death(encounter.monsterId, sessionWindow)?.toPixelSprite(gray = false),
                ghost = body?.toPixelSprite(gray = true) ?: slime.grayscale(),
            )
        }
    }
}

/** Decoded pixel data as a drawable sprite; [gray] applies the defeated tone. */
internal fun MonsterSpriteData.toPixelSprite(gray: Boolean): PixelSprite = PixelSprite(
    frames = frames,
    palette = palette.mapValues { (_, rgb) -> opaque(if (gray) MonsterSpriteDecoder.grayTone(rgb) else rgb) },
)

/** An app-drawn sprite (the slimes) in the defeated gray tone. */
internal fun PixelSprite.grayscale(): PixelSprite = PixelSprite(
    frames = frames,
    palette = palette.mapValues { (_, color) ->
        val rgb = ((color.red * 255f + 0.5f).toInt() shl 16) or
            ((color.green * 255f + 0.5f).toInt() shl 8) or
            (color.blue * 255f + 0.5f).toInt()
        opaque(MonsterSpriteDecoder.grayTone(rgb))
    },
)

private fun opaque(rgb: Int): Color = Color(0xFF000000.toInt() or rgb)

/**
 * Where a gauge monster is and how it looks at a given moment (pure, unit tested). Motion runs on the
 * wall clock, so the spot of a defeat is found again from the defeat time alone.
 */
object GaugeMonsterMotion {
    /** Redraw interval. */
    const val FRAME_MS = 50L

    /** How long the sword shows after a hit. The last hit holds the living sprite this long. */
    const val SWORD_MS = 300L

    /** One frame of the death animation. */
    const val DEATH_FRAME_MS = 150L

    /** A ghost walks at half the living speed. */
    const val GHOST_SPEED_FACTOR = 0.5

    /** Where a completion monster rests with motion reduced (the slime critter's 60% bar spot sits close by). */
    const val COMPLETION_REST = 0.72

    enum class Look { LIVING, DEATH, GHOST }

    data class Pose(val look: Look, val frame: Int, val x: Double, val sword: Boolean, val halo: Boolean)

    fun pose(
        encounter: MonsterEncounter,
        lastHitAtMs: Long?,
        nowMs: Long,
        barWidth: Double,
        cell: Double,
        livingColumns: Int,
        deathFrames: Int,
        tickMs: Long,
        reduceMotion: Boolean,
    ): Pose? {
        val width = cell * livingColumns
        val hop = cell * GaugeCritterMath.HopCells
        val completion = encounter.source == MonsterEncounter.Source.COMPLETION
        val restX = (if (completion) COMPLETION_REST else encounter.position) * max(0.0, barWidth - width)
        // A random monster's pace differs a little by key, so two of the same kind do not walk as one.
        val period = if (completion) tickMs.toDouble() else tickMs / MonsterDraw.marchSpeed(encounter.key)
        fun tickAt(ms: Long) = floor(ms / period).toLong()
        // A completion monster marches like the slime critter; a random one sets out from its spot when it appeared.
        fun marchAt(ms: Long) = if (completion) {
            marchX(tickAt(ms), hop, width, barWidth)
        } else {
            marchX(tickAt(ms), tickAt(encounter.spawnedAtMs), restX, hop, width, barWidth)
        }

        if (encounter.isAlive) {
            val tick = tickAt(nowMs)
            val x = if (reduceMotion) restX else marchAt(nowMs)
            val sword = lastHitAtMs != null && nowMs - lastHitAtMs in 0 until SWORD_MS
            return Pose(Look.LIVING, if (reduceMotion) 0 else (tick % 2L).toInt(), x, sword, halo = false)
        }
        if (encounter.state != MonsterEncounter.State.DEFEATED) return null
        if (reduceMotion) return Pose(Look.GHOST, 0, restX, sword = false, halo = true)

        val deathAt = encounter.defeatedAtMs ?: 0L
        // The spot of the defeat: marching depends on time alone, so recompute it at the defeat time.
        val deathX = marchAt(deathAt)
        val t = nowMs - deathAt
        if (t < SWORD_MS) return Pose(Look.LIVING, 0, deathX, sword = true, halo = false)
        val deathDuration = deathFrames * DEATH_FRAME_MS
        if (deathFrames > 0 && t < SWORD_MS + deathDuration) {
            val frame = min(deathFrames - 1, ((t - SWORD_MS) / DEATH_FRAME_MS).toInt())
            return Pose(Look.DEATH, frame, deathX, sword = false, halo = false)
        }
        val tick = floor((t - SWORD_MS - deathDuration) / (period / GHOST_SPEED_FACTOR)).toLong()
        val x = if (completion) {
            marchX(tick, hop, width, barWidth)
        } else {
            // Sets out from the spot of the defeat and comes back in from the left past the right edge, for the
            // hour the store keeps it listed.
            marchX(tick, 0L, deathX, hop, width, barWidth)
        }
        return Pose(Look.GHOST, (tick % 2L).toInt(), x, sword = false, halo = true)
    }

    /** The slime critter's march ([GaugeCritterMath.offsetX]) on a wall-clock tick, which outgrows an Int. */
    internal fun marchX(tick: Long, hop: Double, spriteWidth: Double, barWidth: Double): Double {
        if (hop <= 0.0 || barWidth <= 0.0) return -spriteWidth
        val hopsPerCross = ceil((barWidth + spriteWidth) / hop).toLong().coerceAtLeast(1L)
        val hopIndex = (tick + 1L) / 2L % hopsPerCross
        return hopIndex * hop - spriteWidth
    }

    /**
     * A march that sets out at [startTick] from [startX] (snapped to the hop grid) in the same rhythm (a
     * step on odd ticks) and comes back in from the left past the right edge. Ticks before [startTick]
     * (clock skew) hold the start; counting from the start tick keeps it free of the clock's origin.
     */
    internal fun marchX(
        tick: Long,
        startTick: Long,
        startX: Double,
        hop: Double,
        spriteWidth: Double,
        barWidth: Double,
    ): Double {
        if (hop <= 0.0 || barWidth <= 0.0) return -spriteWidth
        val hopsPerCross = ceil((barWidth + spriteWidth) / hop).toLong().coerceAtLeast(1L)
        val startHop = ((startX + spriteWidth) / hop).roundToLong()
        val hops = (max(tick, startTick) + 1L) / 2L - (startTick + 1L) / 2L
        val hopIndex = ((startHop + hops) % hopsPerCross + hopsPerCross) % hopsPerCross
        return hopIndex * hop - spriteWidth
    }
}
