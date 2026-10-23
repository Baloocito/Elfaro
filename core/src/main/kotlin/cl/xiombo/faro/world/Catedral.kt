package cl.xiombo.faro.world

import cl.xiombo.faro.data.Balance
import cl.xiombo.faro.data.EnemyDef
import cl.xiombo.faro.data.Pal
import com.badlogic.gdx.math.MathUtils
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Enemigos de la Catedral Sumergida (§14). Todos respetan la regla de aviso de 0,35 s o más.

/** Monaguillo de sal: mantiene la distancia y lanza un abanico de 3 cristales cada 2,2 s (aviso 0,5 s). */
class Monaguillo(def: EnemyDef, x: Float, y: Float, floor: Int, affix: Affix?) : Enemy(def, x, y, floor, affix) {
    private var strafe = if (MathUtils.randomBoolean()) 1f else -1f
    private var strafeT = 1f
    var aimA = 0f
        private set

    override val lightRadius: Float
        get() = if (state == EState.TELEGRAPH) 26f else super.lightRadius

    override fun think(w: World, dt: Float) {
        val dx = tgtX(w) - x
        val dy = tgtY(w) - y
        val d = max(0.001f, sqrt(dx * dx + dy * dy))
        when (state) {
            EState.MOVE -> {
                cd -= dt
                strafeT -= dt
                if (strafeT <= 0f) { strafe = -strafe; strafeT = MathUtils.random(0.8f, 1.6f) }
                // Se queda a 80-120 px: se aleja si te acercas, se acerca si te alejas, y se mueve de lado.
                var mx = -dy / d * strafe * 0.6f
                var my = dx / d * strafe * 0.6f
                if (d < KEEP_MIN) { mx -= dx / d; my -= dy / d } else if (d > KEEP_MAX) { mx += dx / d; my += dy / d }
                val l = max(0.001f, sqrt(mx * mx + my * my))
                if (w.room.move(this, mx / l * spd * dt, my / l * spd * dt)) strafe = -strafe
                if (!lost && d < def.range && cd <= 0f) {
                    aimA = atan2(dy, dx)
                    beginTelegraph(def.telegraph)
                }
            }
            EState.TELEGRAPH -> {
                stateT -= dt
                if (stateT > 0.15f) aimA = atan2(dy, dx)   // el último instante queda fijo
                if (stateT <= 0f) {
                    for (i in -1..1) {
                        val a = aimA + i * SPREAD
                        w.shoot(x, y, cos(a) * SHARD_SPEED, sin(a) * SHARD_SPEED, dmg, "Cristales de sal · ${def.name}", Pal.SALADO)
                    }
                    state = EState.RECOVER
                    stateT = 0.35f
                }
            }
            EState.RECOVER -> {
                stateT -= dt
                if (stateT <= 0f) { state = EState.MOVE; cd = def.cooldown * Balance.cooldownMul }
            }
            else -> {}
        }
    }

    companion object {
        const val KEEP_MIN = 80f
        const val KEEP_MAX = 120f
        const val SPREAD = 0.26f
        const val SHARD_SPEED = 120f
    }
}

/**
 * Campanero: levanta la campana 0,6 s y suelta una onda de 96 px. Si te alcanza, tu luz baja un 40 %
 * durante 3 s; también apaga los candelabros a menos de 64 px de él. De cerca (48 px) además golpea.
 */
class Campanero(def: EnemyDef, x: Float, y: Float, floor: Int, affix: Affix?) : Enemy(def, x, y, floor, affix) {
    override fun think(w: World, dt: Float) {
        val dx = tgtX(w) - x
        val dy = tgtY(w) - y
        val d = max(0.001f, sqrt(dx * dx + dy * dy))
        when (state) {
            EState.MOVE -> {
                cd -= dt
                if (d > 56f) w.room.move(this, dx / d * spd * dt, dy / d * spd * dt)
                if (!lost && d < WAVE_R * 0.9f && cd <= 0f) beginTelegraph(def.telegraph)
            }
            EState.TELEGRAPH -> {
                stateT -= dt
                if (stateT <= 0f) {
                    w.bellWave(this, x, y, WAVE_R, HIT_R)
                    state = EState.RECOVER
                    stateT = 0.6f
                }
            }
            EState.RECOVER -> {
                stateT -= dt
                if (stateT <= 0f) { state = EState.MOVE; cd = def.cooldown * Balance.cooldownMul }
            }
            else -> {}
        }
    }

    companion object {
        const val WAVE_R = 96f
        const val HIT_R = 48f
        const val CANDLE_R = 64f
    }
}

/** Vela errante: kamikaze rápido. Tiembla 0,3 s y se lanza; si te toca te roba 5 de Luz y se apaga. */
class Vela(def: EnemyDef, x: Float, y: Float, floor: Int, affix: Affix?) : Enemy(def, x, y, floor, affix) {
    override val blockedByWater: Boolean get() = false   // flota, como las velas del documento
    override val lightRadius: Float get() = if (killed) 0f else if (state == EState.TELEGRAPH) 30f else 22f

    override fun think(w: World, dt: Float) {
        val p = w.player
        val dx = tgtX(w) - x
        val dy = tgtY(w) - y
        val d = max(0.001f, sqrt(dx * dx + dy * dy))
        when (state) {
            EState.MOVE -> {
                cd -= dt
                val wob = sin(anim * 5f) * 0.5f
                w.room.move(this, (dx / d - dy / d * wob) * spd * 0.55f * dt, (dy / d + dx / d * wob) * spd * 0.55f * dt)
                if (!lost && d < def.range && cd <= 0f) {
                    dirX = dx / d; dirY = dy / d
                    beginTelegraph(def.telegraph)
                }
            }
            EState.TELEGRAPH -> {
                stateT -= dt
                if (stateT <= 0f) { state = EState.ATTACK; stateT = DASH_T }
            }
            EState.ATTACK -> {
                w.room.move(this, dirX * spd * 1.8f * dt, dirY * spd * 1.8f * dt)
                if (p.hurtOverlaps(x, y, radius) && w.hurtPlayer(dmg, x, y, this, "Robó tu luz")) {
                    // Se apaga: muere sin soltar Luz (se la llevó contigo).
                    w.fx.burst(x, y, 10, Pal.LAMP, 80f, 0.4f)
                    w.snuffVela(this)
                    return
                }
                stateT -= dt
                if (stateT <= 0f) { state = EState.RECOVER; stateT = 0.5f }
            }
            EState.RECOVER -> {
                stateT -= dt
                if (stateT <= 0f) { state = EState.MOVE; cd = def.cooldown * Balance.cooldownMul }
            }
            else -> {}
        }
    }

    companion object { const val DASH_T = 0.45f }
}

/**
 * Cofrade de coral: tanque lento con una cruz de coral. Marca una raya rosada de 64 px (0,6 s)
 * y golpea en línea recta. Al morir deja un bloque de coral que tapa su casilla 4 s.
 */
class Cofrade(def: EnemyDef, x: Float, y: Float, floor: Int, affix: Affix?) : Enemy(def, x, y, floor, affix) {
    private var hitDone = false
    private var startX = 0f
    private var startY = 0f

    override fun think(w: World, dt: Float) {
        val p = w.player
        val dx = tgtX(w) - x
        val dy = tgtY(w) - y
        val d = max(0.001f, sqrt(dx * dx + dy * dy))
        when (state) {
            EState.MOVE -> {
                cd -= dt
                if (d > 30f) w.room.move(this, dx / d * spd * dt, dy / d * spd * dt)
                if (!lost && d < def.range + 8f && cd <= 0f) {
                    dirX = dx / d; dirY = dy / d
                    beginTelegraph(def.telegraph)
                }
            }
            EState.TELEGRAPH -> {
                stateT -= dt
                if (stateT <= 0f) {
                    state = EState.ATTACK; stateT = LINE_T; hitDone = false
                    startX = x; startY = y
                }
            }
            EState.ATTACK -> {
                w.room.move(this, dirX * def.range / LINE_T * dt, dirY * def.range / LINE_T * dt)
                if (!hitDone && p.hurtOverlaps(x + dirX * 8f, y + dirY * 8f, radius) && w.hurtPlayer(dmg, x, y, this, "Golpe de cruz")) hitDone = true
                stateT -= dt
                if (stateT <= 0f) { state = EState.RECOVER; stateT = 0.8f }
            }
            EState.RECOVER -> {
                stateT -= dt
                if (stateT <= 0f) { state = EState.MOVE; cd = def.cooldown * Balance.cooldownMul }
            }
            EState.DYING -> {}
        }
    }

    override fun onKilled(w: World) {
        dead = true
        w.coralBlock(x, y, 4f)
    }

    companion object { const val LINE_T = 0.22f }
}

/**
 * El Obispo Coral (§14), 1.500 de vida, arena de 30 × 17.
 * Fase 1 (100-60 %): camina hacia ti y barre con el báculo (arco de 180°, 48 px, aviso 0,5 s).
 *   Cada 12 s invoca 2 Monaguillos (máx. 4 vivos), con círculos de sal 0,8 s antes.
 * Fase 2 (60-30 %): sube al altar. Tres columnas de luz de 32 px recorren la arena: los fieles dentro
 *   se curan 10/s y el Obispo 20/s. Una línea tenue avisa 0,8 s antes de que se muevan. Invoca 3 cada 10 s.
 * Fase 3 (30-0 %): el coral crece 1 tile por los cuatro lados cada 20 s (3 veces; aviso rosa 1 s antes).
 *   Cada barrido suelta un anillo de 12 esquirlas. Ya no invoca.
 */
class Obispo(def: EnemyDef, x: Float, y: Float) : Enemy(def, x, y, 1, null) {
    enum class Act { INTRO, WALK, SWEEP_TELE, SWEEP, ROAR, TO_ALTAR, ALTAR }

    override val isBoss: Boolean get() = true
    override val introduced: Boolean get() = !(act == Act.INTRO && actT > 1.2f)
    override val phaseMarks: FloatArray get() = MARKS
    override val targetable get() = !killed && act != Act.INTRO && act != Act.ROAR
    override val lightRadius: Float
        get() = when {
            killed -> 0f
            act == Act.SWEEP_TELE || act == Act.ROAR -> 70f
            else -> 46f
        }

    var phase = 1
        private set
    var act = Act.INTRO
        private set
    var actT = 1.8f
        private set
    var actMax = 1.8f
        private set
    var sweepA = -PI.toFloat() / 2f
        private set
    private var summonT = 6f
    private var resumeAct = Act.WALK
    private val altarX = x
    private val altarY = y

    // Fase 2: columnas de luz
    val colX = FloatArray(3)
    val colFrom = FloatArray(3)
    val colTo = FloatArray(3)
    var colWarnT = 0f
        private set
    var colMoveT = 0f
        private set
    private var colIdleT = 0f
    var columnsOn = false
        private set

    // Fase 3: coral
    var coralSteps = 0
        private set
    private var coralT = 0f
    /** > 0 mientras avisa la próxima franja de coral (s que faltan). */
    var coralWarnT = 0f
        private set

    private fun setAct(a: Act, t: Float) { act = a; actT = t; actMax = t }

    fun inColumn(px: Float): Boolean = columnsOn && colX.any { abs(px - it) <= COL_W / 2f }

    override fun think(w: World, dt: Float) {
        if (killed) return
        actT -= dt
        val p = w.player
        if (phase == 1 && hp <= maxHp * 0.6f) {
            phase = 2; resumeAct = Act.TO_ALTAR; setAct(Act.ROAR, 1.2f); w.onBossPhase(2); return
        }
        if (phase == 2 && hp <= maxHp * 0.3f) {
            phase = 3; columnsOn = false; resumeAct = Act.WALK; coralT = 4f
            setAct(Act.ROAR, 1.2f); w.onBossPhase(3); return
        }

        updateSummons(w, dt)
        updateColumns(w, dt)
        updateCoral(w, dt)

        val dx = p.cx - x
        val dy = p.cy - y
        val d = max(0.001f, sqrt(dx * dx + dy * dy))
        cd -= dt
        when (act) {
            Act.INTRO -> if (actT <= 0f) setAct(Act.WALK, 0f)
            Act.ROAR -> if (actT <= 0f) {
                setAct(resumeAct, 0f)
                if (resumeAct == Act.TO_ALTAR) startColumns(w)
            }
            Act.WALK -> {
                if (d > 30f) w.room.move(this, dx / d * spd * dt, dy / d * spd * dt)
                if (d < SWEEP_R + 4f && cd <= 0f) { sweepA = atan2(dy, dx); setAct(Act.SWEEP_TELE, def.telegraph) }
            }
            Act.TO_ALTAR -> {
                val ax = altarX - x
                val ay = altarY - y
                val ad = sqrt(ax * ax + ay * ay)
                if (ad < 3f) setAct(Act.ALTAR, 0f)
                else { x += ax / ad * min(ad, 90f * dt); y += ay / ad * min(ad, 90f * dt) }
            }
            Act.ALTAR -> {
                if (d < SWEEP_R + 4f && cd <= 0f) { sweepA = atan2(dy, dx); setAct(Act.SWEEP_TELE, def.telegraph) }
            }
            Act.SWEEP_TELE -> {
                if (actT > 0.12f) sweepA = atan2(dy, dx)
                if (actT <= 0f) {
                    setAct(Act.SWEEP, 0.15f)
                    // Barrido: 180° hacia donde apuntaba.
                    if (d - 6f <= SWEEP_R) {
                        val a = atan2(dy, dx)
                        var diff = a - sweepA
                        while (diff > PI) diff -= (2 * PI).toFloat()
                        while (diff < -PI) diff += (2 * PI).toFloat()
                        if (abs(diff) <= PI / 2 + 0.1f) w.hurtPlayer(dmg, x, y, this, "Barrido del báculo")
                    }
                    w.fx.burst(x + cos(sweepA) * 24f, y + sin(sweepA) * 24f, 10, Pal.CORAL_PINK, 110f, 0.3f)
                    if (phase == 3 || Balance.marea >= 5) {
                        for (i in 0 until 12) {
                            val a = i * MathUtils.PI2 / 12f + sweepA
                            w.shoot(x, y, cos(a) * 95f, sin(a) * 95f, dmg * 0.6f, "Esquirlas de coral · ${def.name}", Pal.CORAL_PINK)
                        }
                    }
                }
            }
            Act.SWEEP -> if (actT <= 0f) {
                cd = def.cooldown * Balance.cooldownMul
                setAct(if (phase == 2) Act.ALTAR else Act.WALK, 0f)
            }
        }
    }

    private fun updateSummons(w: World, dt: Float) {
        if (phase == 3 || act == Act.INTRO || act == Act.ROAR) return
        summonT -= dt
        if (summonT <= 0f) {
            if (phase == 1) { w.summonFaithful(2, 4); summonT = 12f } else { w.summonFaithful(3, 5); summonT = 10f }
        }
    }

    private fun startColumns(w: World) {
        columnsOn = true
        val wid = w.room.width.toFloat()
        colX[0] = wid * 0.2f; colX[1] = wid * 0.5f + 70f; colX[2] = wid * 0.8f
        colIdleT = 2.5f
    }

    private fun updateColumns(w: World, dt: Float) {
        if (!columnsOn) return
        val wid = w.room.width.toFloat()
        when {
            colWarnT > 0f -> {
                colWarnT -= dt
                if (colWarnT <= 0f) { colMoveT = 1f; for (i in 0 until 3) colFrom[i] = colX[i] }
            }
            colMoveT > 0f -> {
                colMoveT -= dt
                val k = 1f - max(0f, colMoveT)
                for (i in 0 until 3) colX[i] = colFrom[i] + (colTo[i] - colFrom[i]) * k
                if (colMoveT <= 0f) colIdleT = 3f
            }
            else -> {
                colIdleT -= dt
                if (colIdleT <= 0f) {
                    for (i in 0 until 3) colTo[i] = MathUtils.random(40f, wid - 40f)
                    colWarnT = 0.8f
                }
            }
        }
        // Curación de los que están dentro de una columna.
        for (e in w.enemies) {
            if (e.killed || !inColumn(e.x)) continue
            val rate = if (e === this) 20f else 10f
            if (e.hp < e.maxHp) e.hp = min(e.maxHp, e.hp + rate * dt)
        }
    }

    private fun updateCoral(w: World, dt: Float) {
        if (phase != 3 || coralSteps >= 3 || act == Act.ROAR) return
        coralT -= dt
        if (coralT <= 1f && coralWarnT <= 0f) coralWarnT = 1f
        if (coralWarnT > 0f) coralWarnT -= dt
        if (coralT <= 0f) {
            coralSteps++
            coralWarnT = 0f
            coralT = 20f
            w.growArenaCoral(coralSteps)
        }
    }

    companion object {
        const val SWEEP_R = 48f
        const val COL_W = 32f
        private val MARKS = floatArrayOf(0.6f, 0.3f)
    }
}

/** Candelabro de la Catedral: apagado al entrar; un golpe lo enciende (luz fija de 64 px y +2 Luz la primera vez). */
class Candle(val tx: Int, val ty: Int, val x: Float, val y: Float) {
    var lit = false
    var gave = false
    var litT = 0f
}

/** Onda del Campanero (para dibujarla). */
class Wave(val x: Float, val y: Float, val r: Float, var t: Float) { val maxT = t }
