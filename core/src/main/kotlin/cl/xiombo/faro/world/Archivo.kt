package cl.xiombo.faro.world

import cl.xiombo.faro.data.EnemyDef
import cl.xiombo.faro.data.Pal
import cl.xiombo.faro.util.dist
import com.badlogic.gdx.math.MathUtils
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// Enemigos del Archivo de los Náufragos (§14). "Solo existe lo que iluminas".

/** Trampa que estalla al acabar su cuenta atrás (páginas del Bibliotecario, raíces de la Lámpara). */
class Trap(val x: Float, val y: Float, val r: Float, var t: Float, val dmg: Float, val cause: String, val src: Enemy?, val kind: Kind) {
    enum class Kind { PAGE, ROOT }
    val maxT = t
}

/** Línea escrita en el suelo (Copista): letras grises durante 0,7 s y luego estalla a lo largo. */
class LineAttack(val x0: Float, val y0: Float, val x1: Float, val y1: Float, var t: Float, val dmg: Float, val src: Enemy?) {
    val maxT = t
}

/** Tinta: solo es visible y golpeable dentro de tu luz. Fuera de ella se reposiciona por un flanco. */
class Tinta(def: EnemyDef, x: Float, y: Float, floor: Int, affix: Affix?) : Enemy(def, x, y, floor, affix) {
    override val needsLight: Boolean get() = true
    private var flank = if (MathUtils.randomBoolean()) 1f else -1f
    private var flankT = 0f
    private var hitDone = false

    override fun think(w: World, dt: Float) {
        val p = w.player
        val dx = tgtX(w) - x
        val dy = tgtY(w) - y
        val d = max(0.001f, sqrt(dx * dx + dy * dy))
        when (state) {
            EState.MOVE -> {
                cd -= dt
                flankT -= dt
                if (flankT <= 0f) { flank = -flank; flankT = MathUtils.random(1.2f, 2.2f) }
                if (!inLight) {
                    // Fuera de la luz: rodea a Isla hasta un flanco a ~70 px.
                    val tx = tgtX(w) + (-dy / d) * 70f * flank
                    val ty = tgtY(w) + (dx / d) * 70f * flank
                    val fx = tx - x
                    val fy = ty - y
                    val fd = max(0.001f, sqrt(fx * fx + fy * fy))
                    if (fd > 6f) w.room.move(this, fx / fd * spd * 1.3f * dt, fy / fd * spd * 1.3f * dt)
                } else if (d > 20f) {
                    w.room.move(this, dx / d * spd * dt, dy / d * spd * dt)
                }
                if (!lost && inLight && d < def.range + 10f && cd <= 0f) {
                    dirX = dx / d; dirY = dy / d
                    beginTelegraph(def.telegraph)
                }
            }
            EState.TELEGRAPH -> {
                stateT -= dt
                if (stateT <= 0f) { state = EState.ATTACK; stateT = 0.2f; hitDone = false }
            }
            EState.ATTACK -> {
                w.room.move(this, dirX * 210f * dt, dirY * 210f * dt)
                if (!hitDone && p.hurtOverlaps(x, y, radius) && w.hurtPlayer(dmg, x, y, this, "Mancha de tinta")) hitDone = true
                stateT -= dt
                if (stateT <= 0f) { state = EState.RECOVER; stateT = 0.45f }
            }
            EState.RECOVER -> {
                stateT -= dt
                if (stateT <= 0f) { state = EState.MOVE; cd = def.cooldown }
            }
            EState.DYING -> {}
        }
    }
}

/**
 * Bibliotecario sin rostro: camina despacio y da un manotazo de cerca. Cada 3,5 s se desvanece
 * (aviso 0,45 s) y aparece junto a Isla dejando 3 páginas trampa que estallan a los 1,2 s.
 */
class Bibliotecario(def: EnemyDef, x: Float, y: Float, floor: Int, affix: Affix?) : Enemy(def, x, y, floor, affix) {
    private var tpT = MathUtils.random(2f, 3.5f)
    var fading = 0f
        private set
    private var slap = false

    override fun think(w: World, dt: Float) {
        val p = w.player
        val dx = tgtX(w) - x
        val dy = tgtY(w) - y
        val d = max(0.001f, sqrt(dx * dx + dy * dy))
        when (state) {
            EState.MOVE -> {
                cd -= dt
                tpT -= dt
                if (d > 18f) w.room.move(this, dx / d * spd * dt, dy / d * spd * dt)
                if (!lost && tpT <= 0f) { slap = false; fading = 0.45f; beginTelegraph(0.45f) }
                else if (!lost && d < def.range + 10f && cd <= 0f) { slap = true; dirX = dx / d; dirY = dy / d; beginTelegraph(def.telegraph) }
            }
            EState.TELEGRAPH -> {
                stateT -= dt
                if (!slap) fading = stateT
                if (stateT <= 0f) {
                    if (slap) {
                        if (p.hurtOverlaps(x + dirX * 12f, y + dirY * 12f, 14f)) w.hurtPlayer(dmg, x, y, this, "Manotazo")
                        state = EState.RECOVER; stateT = 0.5f
                    } else {
                        // Deja 3 páginas donde estaba y reaparece cerca de Isla.
                        for (i in 0 until 3) {
                            val a = i * MathUtils.PI2 / 3f + anim
                            w.traps += Trap(x + cos(a) * 14f, y + sin(a) * 14f, 16f, 1.2f, dmg * 0.8f, "Página trampa · ${def.name}", this, Trap.Kind.PAGE)
                        }
                        w.randomFloorNear(tgtX(w), tgtY(w), 56f, 96f)?.let { x = it[0]; y = it[1]; snapshot() }
                        w.fx.burst(x, y, 10, Pal.PAPER, 80f, 0.4f)
                        fading = 0f
                        tpT = def.cooldown
                        state = EState.RECOVER; stateT = 0.4f
                    }
                }
            }
            EState.RECOVER -> {
                stateT -= dt
                if (stateT <= 0f) { state = EState.MOVE; cd = 1.2f }
            }
            else -> {}
        }
    }
}

/**
 * Polilla de papel: llegan de a 4 y vuelan hacia la luz más intensa: el Haz, un atril o tú.
 * Si te tocan te quitan 4 de Luz y se queman. El Haz las quema de una vez.
 */
class Polilla(def: EnemyDef, x: Float, y: Float, floor: Int, affix: Affix?) : Enemy(def, x, y, floor, affix) {
    override val blockedByWater: Boolean get() = false
    override val lightRadius: Float get() = if (killed) 0f else 10f

    override fun think(w: World, dt: Float) {
        val p = w.player
        var tx = tgtX(w)
        var ty = tgtY(w)
        if (p.hazT > 0f) {
            tx = p.cx + p.aimX * p.hazLen * 0.7f
            ty = p.cy + p.aimY * p.hazLen * 0.7f
        } else {
            w.atrilLights().minByOrNull { dist(it[0], it[1], x, y) }?.let {
                if (dist(it[0], it[1], x, y) < 90f && dist(it[0], it[1], x, y) < dist(p.cx, p.cy, x, y)) {
                    // Gira alrededor del atril.
                    val a = anim * 3f + x
                    tx = it[0] + cos(a) * 14f; ty = it[1] + sin(a) * 14f
                }
            }
        }
        val dx = tx - x
        val dy = ty - y
        val d = max(0.001f, sqrt(dx * dx + dy * dy))
        val wob = sin(anim * 9f) * 0.6f
        w.room.move(this, (dx / d - dy / d * wob) * spd * dt, (dy / d + dx / d * wob) * spd * dt)
        if (p.hurtOverlaps(x, y, radius) && w.hurtPlayer(dmg, x, y, this, "Polillas")) {
            w.fx.burst(x, y, 6, Pal.LAMP, 60f, 0.3f)
            w.snuffVela(this)
        }
    }
}

/**
 * Copista: se mantiene lejos y escribe hacia ti una línea de texto en el suelo (letras grises 0,7 s);
 * luego la línea estalla a lo largo de 96 px. Huye cuando te acercas.
 */
class Copista(def: EnemyDef, x: Float, y: Float, floor: Int, affix: Affix?) : Enemy(def, x, y, floor, affix) {
    override fun think(w: World, dt: Float) {
        val dx = tgtX(w) - x
        val dy = tgtY(w) - y
        val d = max(0.001f, sqrt(dx * dx + dy * dy))
        when (state) {
            EState.MOVE -> {
                cd -= dt
                when {
                    d < 80f -> w.room.move(this, -dx / d * spd * 1.2f * dt, -dy / d * spd * 1.2f * dt)
                    d > 140f -> w.room.move(this, dx / d * spd * dt, dy / d * spd * dt)
                }
                if (!lost && d < 150f && cd <= 0f) {
                    dirX = dx / d; dirY = dy / d
                    val len = w.room.rayLength(x, y, dirX, dirY, def.range)
                    w.lines += LineAttack(x + dirX * 8f, y + dirY * 8f, x + dirX * len, y + dirY * len, def.telegraph, dmg, this)
                    beginTelegraph(def.telegraph)
                }
            }
            EState.TELEGRAPH -> {
                stateT -= dt
                if (stateT <= 0f) { state = EState.RECOVER; stateT = 0.5f }
            }
            EState.RECOVER -> {
                stateT -= dt
                if (stateT <= 0f) { state = EState.MOVE; cd = def.cooldown }
            }
            else -> {}
        }
    }
}

/**
 * Clon de papel (fase 2 del Cartógrafo): repite tus últimos 3 s, posición y ataques, con el 60 % de tu
 * daño. 60 de vida. Si lo rompes, el Cartógrafo queda aturdido 3 s y recibe +50 % de daño.
 */
class PaperClone(def: EnemyDef, x: Float, y: Float, val owner: Cartografo) : Enemy(def, x, y, 1, null) {
    override val blockedByWater: Boolean get() = false
    var swingT = 0f
        private set
    var swingDx = 1f
        private set
    var swingDy = 0f
        private set

    override fun think(w: World, dt: Float) {
        swingT -= dt
        val s = w.playerHistory(CLONE_DELAY) ?: return
        x = s.x; y = s.y
        if (s.attack) {
            swingT = 0.15f
            swingDx = s.ax; swingDy = s.ay
            val p = w.player
            val ex = p.cx - x
            val ey = p.cy - y
            val d = sqrt(ex * ex + ey * ey)
            if (d < 30f && (d < 6f || (ex * s.ax + ey * s.ay) / d > 0.5f)) w.hurtPlayer(dmg, x, y, this, "Tu propio golpe (clon)")
        }
    }

    override fun onKilled(w: World) {
        dead = true
        owner.onCloneBroken(w)
    }

    companion object { const val CLONE_DELAY = 180 }   // 3 s a 60 Hz
}

/**
 * El Cartógrafo (§14), 2.100 de vida. Arena de 30 × 17 con 6 segmentos de muro sobre rieles.
 * Fase 1 (100-65 %): cada 8 s redibuja la sala (mueve 4 segmentos; tinta en los rieles 1 s antes).
 *   Cada 2,5 s un compás dispara 4 proyectiles en cruz que giran 45° en cada disparo (se abre 0,4 s antes).
 * Fase 2 (65-30 %): aparece el clon de papel. Romperlo lo aturde 3 s (+50 % de daño). Renace a los 8 s.
 * Fase 3 (30-0 %): apaga todas las luces salvo la tuya. Solo se ve dentro de tu radio, pero deja un
 *   rastro de tinta. Mueve muros cada 6 s.
 */
class Cartografo(def: EnemyDef, x: Float, y: Float) : Enemy(def, x, y, 1, null) {
    enum class Act { INTRO, DRIFT, ROAR }

    override val isBoss: Boolean get() = true
    override val blockedByWater: Boolean get() = false
    override val introduced: Boolean get() = !(act == Act.INTRO && actT > 1.2f)
    override val phaseMarks: FloatArray get() = MARKS
    override val needsLight: Boolean get() = phase == 3
    override val targetable get() = !killed && act != Act.INTRO && act != Act.ROAR && !hidden
    override val lightRadius: Float get() = if (killed || phase == 3) 0f else if (compassT in 0f..0.4f) 60f else 40f

    var phase = 1
        private set
    var act = Act.INTRO
        private set
    var actT = 1.8f
        private set
    /** Compás: s hasta el próximo disparo (se abre en los últimos 0,4 s). */
    var compassT = 2.5f
        private set
    var compassA = 0f
        private set
    private var wallT = 6f
    private var driftA = MathUtils.random(MathUtils.PI2)
    private var clone: PaperClone? = null
    private var cloneT = 0f
    /** Expuesto tras romper el clon: no actúa y recibe +50 % de daño. */
    var exposedT = 0f
        private set
    private var trailT = 0f

    private fun setAct(a: Act, t: Float) { act = a; actT = t }

    override fun takeHit(w: World, amount: Float, fromX: Float, fromY: Float, kind: HitKind): Boolean =
        super.takeHit(w, if (exposedT > 0f) amount * 1.5f else amount, fromX, fromY, kind)

    fun onCloneBroken(w: World) {
        clone = null
        cloneT = 8f
        exposedT = 3f
        w.fx.burst(x, y, 14, Pal.PAPER, 100f, 0.5f)
        w.fx.floater("¡ATURDIDO!", x, y + 24f, Pal.LAMP_HI)
    }

    override fun think(w: World, dt: Float) {
        if (killed) { clone?.let { it.killed = true; it.dead = true }; return }
        actT -= dt
        exposedT -= dt
        if (phase == 1 && hp <= maxHp * 0.65f) { phase = 2; cloneT = 1.5f; setAct(Act.ROAR, 1.2f); w.onBossPhase(2); return }
        if (phase == 2 && hp <= maxHp * 0.3f) { phase = 3; setAct(Act.ROAR, 1.2f); w.onBossPhase(3); return }
        when (act) {
            Act.INTRO -> { if (actT <= 0f) setAct(Act.DRIFT, 0f); return }
            Act.ROAR -> { if (actT <= 0f) setAct(Act.DRIFT, 0f); return }
            Act.DRIFT -> {}
        }
        if (exposedT > 0f) return

        // Se mueve flotando a 90-130 px de Isla, rodeándola.
        val p = w.player
        driftA += dt * 0.5f
        val tx = p.cx + cos(driftA) * 110f
        val ty = p.cy + sin(driftA) * 70f
        val dx = tx - x
        val dy = ty - y
        val d = max(0.001f, sqrt(dx * dx + dy * dy))
        if (d > 4f) {
            x = (x + dx / d * spd * dt).coerceIn(40f, w.room.width - 40f)
            y = (y + dy / d * spd * dt).coerceIn(40f, w.room.height - 40f)
        }

        // Rastro de tinta en la fase 3.
        if (phase == 3) {
            trailT -= dt
            if (trailT <= 0f) { trailT = 0.12f; w.inkTrail(x, y - hh + 2f) }
        }

        // Compás en cruz.
        compassT -= dt
        if (compassT <= 0f) {
            for (k in 0 until 4) {
                val a = compassA + k * PI.toFloat() / 2f
                w.shoot(x, y, cos(a) * 110f, sin(a) * 110f, dmg, "Compás · ${def.name}", Pal.INK_HI)
            }
            compassA += PI.toFloat() / 4f
            compassT = def.cooldown
        }

        // Redibuja la sala.
        wallT -= dt
        if (wallT <= 0f) {
            w.redrawWalls(4)
            wallT = if (phase == 3) 6f else 8f
        }

        // Clon de papel en la fase 2.
        if (phase >= 2 && clone == null) {
            cloneT -= dt
            if (cloneT <= 0f) clone = w.spawnClone(this)
        }
    }

    companion object { private val MARKS = floatArrayOf(0.65f, 0.3f) }
}
