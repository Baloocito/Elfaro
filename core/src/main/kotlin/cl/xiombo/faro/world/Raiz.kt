package cl.xiombo.faro.world

import cl.xiombo.faro.data.Balance
import cl.xiombo.faro.data.EnemyDef
import cl.xiombo.faro.data.Pal
import cl.xiombo.faro.data.Weapon
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.MathUtils
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Enemigos de la Raíz del Faro (§14). Aquí tener mucha luz es peligroso.

/** Radio de luz a partir del cual la Raíz te detecta (§14). */
const val RAIZ_DETECT_RADIUS = 120f

/**
 * Anguila de raíz: vive bajo el suelo. Marca un círculo bajo Isla (0,5 s) y sale; se queda 2 s
 * expuesta (solo entonces se la puede golpear) y vuelve a hundirse.
 */
class Anguila(def: EnemyDef, x: Float, y: Float, floor: Int, affix: Affix?) : Enemy(def, x, y, floor, affix) {
    enum class Phase { BURIED, WARN, EXPOSED }
    override val blockedByWater: Boolean get() = false
    var phase = Phase.BURIED
        private set
    var phaseT = MathUtils.random(0.8f, 2f)
        private set
    var markX = x
        private set
    var markY = y
        private set
    override val targetable get() = !killed && phase == Phase.EXPOSED && !hidden
    override val lightRadius: Float get() = if (phase == Phase.WARN) 20f else 0f

    override fun think(w: World, dt: Float) {
        phaseT -= dt
        when (phase) {
            Phase.BURIED -> if (phaseT <= 0f && !lost) {
                markX = tgtX(w); markY = tgtY(w) - 4f
                phase = Phase.WARN; phaseT = def.telegraph
                state = EState.TELEGRAPH
            }
            Phase.WARN -> if (phaseT <= 0f) {
                x = markX; y = markY; snapshot()
                if (w.player.hurtOverlaps(x, y, def.range)) w.hurtPlayer(dmg, x, y, this, "Emergió del suelo")
                w.fx.burst(x, y, 12, Pal.ROOT_HI, 100f, 0.4f)
                phase = Phase.EXPOSED; phaseT = 2f
                state = EState.MOVE
            }
            Phase.EXPOSED -> if (phaseT <= 0f) {
                w.fx.burst(x, y, 8, Pal.ROOT, 60f, 0.3f)
                phase = Phase.BURIED; phaseT = def.cooldown
            }
        }
    }
}

/**
 * Hijo del faro: usa tu misma arma con un 60 % de tu fuerza. Solo te persigue si tu radio supera
 * 120 px; si no, vaga.
 */
class HijoDelFaro(def: EnemyDef, x: Float, y: Float, floor: Int, affix: Affix?) : Enemy(def, x, y, floor, affix) {
    var weapon = Weapon.FAROL
        private set
    private var wanderA = MathUtils.random(MathUtils.PI2)

    override fun think(w: World, dt: Float) {
        val p = w.player
        weapon = p.weapon
        val dx = tgtX(w) - x
        val dy = tgtY(w) - y
        val d = max(0.001f, sqrt(dx * dx + dy * dy))
        val hunting = !lost && w.playerLight() > RAIZ_DETECT_RADIUS
        when (state) {
            EState.MOVE -> {
                cd -= dt
                if (hunting) {
                    val want = if (weapon == Weapon.ARPON) 90f else 18f
                    if (d > want) w.room.move(this, dx / d * spd * dt, dy / d * spd * dt)
                } else {
                    wanderA += MathUtils.random(-1f, 1f) * dt * 2f
                    if (w.room.move(this, cos(wanderA) * spd * 0.35f * dt, sin(wanderA) * spd * 0.35f * dt)) wanderA += PI.toFloat()
                }
                val reach = when (weapon) { Weapon.ARPON -> 160f; Weapon.CAMPANA -> 46f; Weapon.ANCLA -> 34f; Weapon.FAROL -> def.range }
                if (hunting && d < reach && cd <= 0f) {
                    dirX = dx / d; dirY = dy / d
                    beginTelegraph(if (weapon == Weapon.ANCLA) 0.6f else def.telegraph)
                }
            }
            EState.TELEGRAPH -> {
                stateT -= dt
                if (stateT <= 0f) {
                    strike(w)
                    state = EState.RECOVER; stateT = 0.5f
                }
            }
            EState.RECOVER -> {
                stateT -= dt
                if (stateT <= 0f) { state = EState.MOVE; cd = def.cooldown * Balance.cooldownMul }
            }
            else -> {}
        }
    }

    private fun strike(w: World) {
        val p = w.player
        val ex = p.cx - x
        val ey = p.cy - y
        val d = max(0.001f, sqrt(ex * ex + ey * ey))
        val cosA = (ex * dirX + ey * dirY) / d
        when (weapon) {
            Weapon.FAROL -> if (d < def.range + 6f && cosA > 0.5f) w.hurtPlayer(dmg, x, y, this, "Tu farol, en otras manos")
            Weapon.ANCLA -> if (d < 40f && cosA > -0.1f) {
                if (w.hurtPlayer(dmg * 1.2f, x, y, this, "Tu ancla, en otras manos")) { p.kbX = dirX * 260f; p.kbY = dirY * 260f }
            }
            Weapon.CAMPANA -> {
                w.fx.ring(x, y, 46f, Pal.SHELL)
                if (d < 46f) w.hurtPlayer(dmg * 0.8f, x, y, this, "Tu campana, en otras manos")
            }
            Weapon.ARPON -> w.shoot(x, y, dirX * 210f, dirY * 210f, dmg, "Tu arpón, en otras manos", Pal.CREAM)
        }
        w.fx.burst(x + dirX * 14f, y + dirY * 14f, 6, Pal.LAMP, 80f, 0.25f)
    }
}

/**
 * Vidriero: gólem de vidrio que deja charcos fundidos al caminar y da un pisotón (aviso 0,6 s).
 * Al morir se agrieta 0,6 s y estalla en 8 esquirlas.
 */
class Vidriero(def: EnemyDef, x: Float, y: Float, floor: Int, affix: Affix?) : Enemy(def, x, y, floor, affix) {
    private var dropT = 0.5f
    var crackT = 0f
        private set

    override fun think(w: World, dt: Float) {
        if (killed) {
            crackT -= dt
            if (crackT <= 0f) {
                dead = true
                for (i in 0 until 8) {
                    val a = i * MathUtils.PI2 / 8f
                    w.shoot(x, y, cos(a) * 120f, sin(a) * 120f, dmg, "Esquirlas · ${def.name}", Pal.GLASSY)
                }
                w.fx.burst(x, y, 16, Pal.GLASSY, 130f, 0.5f)
                w.addShake(2f)
            }
            return
        }
        val p = w.player
        val dx = tgtX(w) - x
        val dy = tgtY(w) - y
        val d = max(0.001f, sqrt(dx * dx + dy * dy))
        when (state) {
            EState.MOVE -> {
                cd -= dt
                if (d > 22f) w.room.move(this, dx / d * spd * dt, dy / d * spd * dt)
                dropT -= dt
                if (dropT <= 0f) { dropT = 0.5f; w.moltenTrail(x, y - hh + 2f) }
                if (!lost && d < def.range + 10f && cd <= 0f) beginTelegraph(def.telegraph)
            }
            EState.TELEGRAPH -> {
                stateT -= dt
                if (stateT <= 0f) {
                    if (p.hurtOverlaps(x, y, def.range)) w.hurtPlayer(dmg, x, y, this, "Pisotón")
                    w.fx.ring(x, y, def.range, Pal.MOLTEN)
                    w.addShake(2f)
                    state = EState.RECOVER; stateT = 0.7f
                }
            }
            EState.RECOVER -> {
                stateT -= dt
                if (stateT <= 0f) { state = EState.MOVE; cd = def.cooldown * Balance.cooldownMul }
            }
            else -> {}
        }
    }

    override fun onKilled(w: World) {
        state = EState.DYING
        crackT = 0.6f
    }
}

/**
 * Ojo de la fosa: torreta fija en la pared. Si estás en su línea y tu radio supera 120 px, un hilo de
 * luz te apunta 0,8 s y dispara un rayo. Con el farol velado no te ve.
 */
class OjoFosa(def: EnemyDef, x: Float, y: Float, floor: Int, affix: Affix?) : Enemy(def, x, y, floor, affix) {
    override val blockedByWater: Boolean get() = false
    var aimA = 0f
        private set
    var rayT = 0f
        private set
    var rayLen = 0f
        private set
    override val lightRadius: Float get() = if (killed) 0f else if (state == EState.TELEGRAPH) 30f else 16f

    override fun think(w: World, dt: Float) {
        val p = w.player
        rayT -= dt
        val dx = p.cx - x
        val dy = p.cy - y
        val d = max(0.001f, sqrt(dx * dx + dy * dy))
        val sees = !p.veiling && w.playerLight() > RAIZ_DETECT_RADIUS && w.room.rayLength(x, y, dx / d, dy / d, d) >= d - 6f
        when (state) {
            EState.MOVE -> {
                cd -= dt
                if (sees && cd <= 0f) { aimA = atan2(dy, dx); beginTelegraph(def.telegraph) }
            }
            EState.TELEGRAPH -> {
                stateT -= dt
                if (stateT > 0.2f && sees) aimA = atan2(dy, dx)
                if (stateT <= 0f) {
                    val ax = cos(aimA)
                    val ay = sin(aimA)
                    rayLen = w.room.rayLength(x, y, ax, ay, def.range)
                    rayT = 0.18f
                    // ¿Isla está sobre el rayo?
                    val t = (dx * ax + dy * ay).coerceIn(0f, rayLen)
                    val px = x + ax * t
                    val py = y + ay * t
                    if (sqrt((p.cx - px) * (p.cx - px) + (p.cy - py) * (p.cy - py)) < 9f) w.hurtPlayer(dmg, x, y, this, "Rayo")
                    state = EState.RECOVER; stateT = 0.5f
                }
            }
            EState.RECOVER -> {
                stateT -= dt
                if (stateT <= 0f) { state = EState.MOVE; cd = def.cooldown * Balance.cooldownMul }
            }
            else -> {}
        }
    }
}

/** Corazón de piedra: si lo golpeas suelta 30 de Luz en orbes, pero despierta una oleada élite. */
class Corazon(def: EnemyDef, x: Float, y: Float) : Enemy(def, x, y, 1, null) {
    override val blockedByWater: Boolean get() = false
    override val lightRadius: Float get() = if (killed) 0f else 36f + 6f * sin(anim * 3f)
    override fun think(w: World, dt: Float) {}
    override fun onKilled(w: World) {
        dead = true
        w.awakenHeart(this)
    }
}

/**
 * La Lámpara Madre (§14), 3.200 de vida, fija en el centro de la arena.
 * Fase 1 (100-75 %): dos haces giratorios (vuelta en 6 s, tocarlos cuesta 12); cada 4 s brota una raíz bajo ti.
 * Fase 2 (75-50 %): se abre; tres haces que cambian de sentido cada 8 s; escupe Vidrieros (máx. 2).
 * Fase 3 (50-20 %): ecos de los jefes anteriores cada 15 s (Sirena, Obispo, Cartógrafo), con la sala
 *   teñida de su color 1 s antes.
 * Fase 4 (20-0 %): bebe luz: pierdes 1 de Luz por segundo, pero cada golpe que le das te devuelve 2.
 */
class LamparaMadre(def: EnemyDef, x: Float, y: Float) : Enemy(def, x, y, 1, null) {
    enum class Echo(val color: Color, val label: String) {
        SIRENA(Pal.BREA, "ECO: LA SIRENA"),
        OBISPO(Pal.VIOLET, "ECO: EL OBISPO"),
        CARTOGRAFO(Pal.INK_HI, "ECO: EL CARTÓGRAFO"),
    }

    override val isBoss: Boolean get() = true
    override val blockedByWater: Boolean get() = false
    override val introduced: Boolean get() = introT <= 1.2f && !dormant
    /** Marea 5+: la lámpara duerme hasta que te acercas sin velar. Velar 3 s cerca abre el final C. */
    var dormant = Balance.marea >= 5
    var veilNearT = 0f
    override val phaseMarks: FloatArray get() = MARKS
    override val targetable get() = !killed && introT <= 0f && roarT <= 0f && !dormant
    override val lightRadius: Float get() = if (killed) 0f else 70f + 10f * sin(anim * 2f)

    var phase = 1
        private set
    private var introT = 2f
    private var roarT = 0f
    /** Ángulo base de los haces y su velocidad (rad/s). */
    var beamA = 0f
        private set
    private var beamDir = 1f
    val beamCount get() = when (phase) { 1 -> 2; 2 -> 3; else -> 0 }
    /** Aviso antes de encender los haces o de cambiar de sentido (s). */
    var beamWarnT = 0.5f
        private set
    private var flipT = 8f
    private var rootT = 4f
    private var spitT = 3f

    // Fase 3: ecos
    var echo: Echo? = null
        private set
    var echoWarnT = 0f
        private set
    var echoT = 0f
        private set
    private var echoIdx = 0
    private var nextEchoT = 2f
    private var emitT = 0f
    private var spiralA = 0f
    val colX = FloatArray(2)

    /** Fase 4: bebe luz. */
    val drinking get() = phase == 4 && !killed

    override fun takeHit(w: World, amount: Float, fromX: Float, fromY: Float, kind: HitKind): Boolean {
        val hit = super.takeHit(w, amount, fromX, fromY, kind)
        if (hit && drinking && kind != HitKind.BURN) {
            w.gainLuz(2f)
            w.fx.floater("+2", w.player.cx, w.player.cy + 18f, Pal.LAMP_HI)
        }
        return hit
    }

    fun beamAngles(): FloatArray = FloatArray(beamCount) { beamA + it * MathUtils.PI2 / beamCount }
    fun inColumn(px: Float) = echo == Echo.OBISPO && echoT > 0f && colX.any { abs(px - it) <= 16f }

    override fun think(w: World, dt: Float) {
        if (killed) return
        if (dormant) {
            val p = w.player
            val d = sqrt((p.cx - x) * (p.cx - x) + (p.cy - y) * (p.cy - y))
            if (p.veiling && d < 120f) {
                veilNearT += dt
                if (veilNearT >= 3f) { w.startFinalC(this); return }
            } else if (!p.veiling) veilNearT = 0f
            if (d < 100f && !p.veiling) dormant = false
            return
        }
        if (introT > 0f) { introT -= dt; return }
        if (roarT > 0f) { roarT -= dt; return }
        if (phase == 1 && hp <= maxHp * 0.75f) { phase = 2; roarT = 1.2f; beamWarnT = 0.6f; flipT = 8f; w.onBossPhase(2); return }
        if (phase == 2 && hp <= maxHp * 0.5f) { phase = 3; roarT = 1.2f; nextEchoT = 2f; w.onBossPhase(3); return }
        if (phase == 3 && hp <= maxHp * 0.2f) { phase = 4; roarT = 1.2f; echo = null; echoT = 0f; w.onBossPhase(4); return }

        updateBeams(w, dt)
        // Raíces bajo Isla (fases 1, 2 y 3).
        if (phase <= 3) {
            rootT -= dt
            if (rootT <= 0f) {
                rootT = if (phase == 1) 4f else 3.2f
                val p = w.player
                w.traps += Trap(p.cx, p.y, 14f, 0.5f, dmg, "Raíz · ${def.name}", this, Trap.Kind.ROOT)
                // Marea 5: las raíces salen de a dos.
                if (Balance.marea >= 5) w.traps += Trap(p.cx + MathUtils.random(-40f, 40f), p.y + MathUtils.random(-30f, 30f), 14f, 0.7f, dmg, "Raíz · ${def.name}", this, Trap.Kind.ROOT)
            }
        }
        if (phase == 2) {
            spitT -= dt
            if (spitT <= 0f) {
                spitT = 9f
                if (w.enemies.count { it is Vidriero && !it.killed } + w.pending.count { it.id == "vidriero" } < 2) w.spitVidriero(x, y)
            }
        }
        if (phase == 3) updateEchoes(w, dt)
    }

    private fun updateBeams(w: World, dt: Float) {
        if (beamCount == 0) return
        if (beamWarnT > 0f) { beamWarnT -= dt; return }
        if (phase == 2) {
            flipT -= dt
            if (flipT <= 0f) { flipT = 8f; beamDir = -beamDir; beamWarnT = 0.6f; w.fx.floater("¡CHIRRIDO!", x, y + 40f, Pal.MOLTEN_HI); return }
        }
        beamA += beamDir * MathUtils.PI2 / 6f * dt
        val p = w.player
        for (a in beamAngles()) {
            val ax = cos(a)
            val ay = sin(a)
            val len = BEAM_START + w.room.rayLength(x + ax * BEAM_START, y + ay * BEAM_START, ax, ay, 600f)
            val ex = p.cx - x
            val ey = p.cy - y
            val t = (ex * ax + ey * ay)
            if (t < 0f || t > len) continue
            val px = x + ax * t
            val py = y + ay * t
            if (sqrt((p.cx - px) * (p.cx - px) + (p.cy - py) * (p.cy - py)) < 8f) w.hurtPlayer(12f, px, py, this, "Haz de la lámpara")
        }
    }

    private fun updateEchoes(w: World, dt: Float) {
        val e = echo
        if (e == null) {
            nextEchoT -= dt
            if (nextEchoT <= 0f) {
                val next = Echo.entries[echoIdx % 3]
                echoIdx++
                echo = next
                echoWarnT = 1f
                echoT = 8f
                w.showEcho(next.label)
            }
            return
        }
        if (echoWarnT > 0f) {
            echoWarnT -= dt
            if (echoWarnT <= 0f) startEcho(w, e)
            return
        }
        echoT -= dt
        when (e) {
            Echo.SIRENA -> {
                emitT -= dt
                if (emitT <= 0f) {
                    emitT = 0.09f
                    for (k in 0 until 2) {
                        val a = spiralA + k * PI.toFloat()
                        w.shoot(x, y, cos(a) * 70f, sin(a) * 70f, dmg, "Eco de la Sirena · ${def.name}", Pal.CREAM)
                    }
                    spiralA += 0.37f
                }
            }
            Echo.OBISPO -> {
                if (inColumn(x) && hp < maxHp * 0.5f) hp = min(maxHp * 0.5f, hp + 20f * dt)
                for (en in w.enemies) if (en !== this && !en.killed && inColumn(en.x)) en.hp = min(en.maxHp, en.hp + 10f * dt)
            }
            Echo.CARTOGRAFO -> {
                emitT -= dt
                if (emitT <= 0f) {
                    emitT = 1.1f
                    for (k in 0 until 4) {
                        val a = spiralA + k * PI.toFloat() / 2f
                        w.shoot(x, y, cos(a) * 110f, sin(a) * 110f, dmg, "Eco del Cartógrafo · ${def.name}", Pal.INK_HI)
                    }
                    spiralA += PI.toFloat() / 4f
                }
            }
        }
        if (echoT <= 0f) { echo = null; nextEchoT = 15f - 8f }
    }

    private fun startEcho(w: World, e: Echo) {
        emitT = 0f
        when (e) {
            Echo.OBISPO -> {
                val wid = w.room.width.toFloat()
                colX[0] = MathUtils.random(48f, wid * 0.45f)
                colX[1] = MathUtils.random(wid * 0.55f, wid - 48f)
                w.summonFaithful(3, 4)
            }
            else -> {}
        }
    }

    companion object {
        private val MARKS = floatArrayOf(0.75f, 0.5f, 0.2f)
        /** Los haces salen del borde de la base (que es sólida). */
        const val BEAM_START = 22f
    }
}

/**
 * El Farero Antiguo (final C, §9). Pelea a oscuras: tu radio no pasa de 80 px.
 * Fase 1 (100-60 %): busca con su farol (un cono que barre la sala). Si te ve sin velar, avisa 0,6 s
 *   y lanza 3 garfios. De cerca da un bastonazo.
 * Fase 2 (60-25 %): apaga su farol y carga hacia tu luz (huellas 0,5 s antes). Si velas, te pierde
 *   y queda expuesto 1,5 s (+50 % de daño).
 * Fase 3 (25-0 %): la fosa respira cada 8 s: si no estás velando, te quita 15 de Luz.
 */
class FareroAntiguo(def: EnemyDef, x: Float, y: Float) : Enemy(def, x, y, 1, null) {
    enum class Act { INTRO, SEARCH, SPOTTED, WALK_STRIKE, CHARGE_WARN, CHARGE, EXPOSED, ROAR }

    override val isBoss: Boolean get() = true
    override val blockedByWater: Boolean get() = false
    override val introduced: Boolean get() = !(act == Act.INTRO && actT > 1.5f)
    override val phaseMarks: FloatArray get() = MARKS
    override val needsLight: Boolean get() = !lanternOn
    override val targetable get() = !killed && act != Act.INTRO && act != Act.ROAR && !hidden
    override val lightRadius: Float get() = if (killed || !lanternOn) 0f else 26f

    var phase = 1
        private set
    var act = Act.INTRO
        private set
    var actT = 3f
        private set
    /** Ángulo del farol (cono de búsqueda). */
    var lanternA = -PI.toFloat() / 2f
        private set
    val lanternOn get() = phase == 1 || (phase == 3 && act != Act.CHARGE && act != Act.CHARGE_WARN)
    private var chargeX = 0f
    private var chargeY = 0f
    private var nextChargeT = 3f
    var breathT = 8f
        private set
    private var exposedDmg = false

    private fun setAct(a: Act, t: Float) { act = a; actT = t }

    override fun takeHit(w: World, amount: Float, fromX: Float, fromY: Float, kind: HitKind): Boolean =
        super.takeHit(w, if (act == Act.EXPOSED) amount * 1.5f else amount, fromX, fromY, kind)

    /** ¿Isla está dentro del cono del farol? */
    fun sees(w: World): Boolean {
        val p = w.player
        if (!lanternOn || p.veiling) return false
        val dx = p.cx - x
        val dy = p.cy - y
        val d = sqrt(dx * dx + dy * dy)
        if (d > CONE_LEN || d < 1f) return d < 1f
        val a = atan2(dy, dx)
        var diff = a - lanternA
        while (diff > PI) diff -= (2 * PI).toFloat()
        while (diff < -PI) diff += (2 * PI).toFloat()
        return abs(diff) < CONE_HALF
    }

    override fun think(w: World, dt: Float) {
        if (killed) return
        actT -= dt
        val p = w.player
        if (phase == 1 && hp <= maxHp * 0.6f) { phase = 2; setAct(Act.ROAR, 1.4f); w.onBossPhase(2); return }
        if (phase == 2 && hp <= maxHp * 0.25f) { phase = 3; breathT = 4f; setAct(Act.ROAR, 1.4f); w.onBossPhase(3); return }
        val dx = p.cx - x
        val dy = p.cy - y
        val d = max(0.001f, sqrt(dx * dx + dy * dy))

        // Fase 3: la fosa respira.
        if (phase == 3) {
            breathT -= dt
            if (breathT <= 0f) {
                breathT = 8f
                w.sfx("breath")
                if (!p.veiling) w.hurtPlayer(15f, p.cx, p.cy - 10f, this, "La fosa respiró")
                w.addShake(3f)
            }
        }

        when (act) {
            Act.INTRO, Act.ROAR -> if (actT <= 0f) setAct(if (phase == 2) Act.WALK_STRIKE else Act.SEARCH, 0f)
            Act.SEARCH -> {
                // Camina despacio y barre con el farol hacia donde cree que estás.
                val want = if (p.veiling) lanternA + dt * 1.2f else atan2(dy, dx)
                var diff = want - lanternA
                while (diff > PI) diff -= (2 * PI).toFloat()
                while (diff < -PI) diff += (2 * PI).toFloat()
                lanternA += diff.coerceIn(-1.4f * dt, 1.4f * dt)
                if (!p.veiling && d > 40f) w.room.move(this, dx / d * spd * 0.5f * dt, dy / d * spd * 0.5f * dt)
                if (sees(w)) setAct(Act.SPOTTED, Balance.telegraph(0.6f))
                else if (d < 34f && cd <= 0f) { dirX = dx / d; dirY = dy / d; setAct(Act.WALK_STRIKE, 0f); beginTelegraph(0.5f) }
                cd -= dt
                if (phase == 3) {
                    nextChargeT -= dt
                    if (nextChargeT <= 0f && !p.veiling) startCharge(p)
                }
            }
            Act.SPOTTED -> if (actT <= 0f) {
                for (i in -1..1) {
                    val a = atan2(dy, dx) + i * 0.22f
                    w.shoot(x, y, cos(a) * 150f, sin(a) * 150f, dmg, "Garfio · ${def.name}", Pal.STONE)
                }
                cd = 0.8f
                setAct(Act.SEARCH, 0f)
            }
            Act.WALK_STRIKE -> {
                if (state == EState.TELEGRAPH) {
                    stateT -= dt
                    if (stateT <= 0f) {
                        if (p.hurtOverlaps(x + dirX * 14f, y + dirY * 14f, 20f)) w.hurtPlayer(dmg, x, y, this, "Bastonazo")
                        state = EState.MOVE
                        cd = def.cooldown
                        setAct(if (phase == 2) Act.WALK_STRIKE else Act.SEARCH, 0f)
                    }
                    return
                }
                // Fase 2: sin farol, va hacia tu luz y carga.
                if (phase == 2) {
                    cd -= dt
                    nextChargeT -= dt
                    if (!p.veiling && d > 30f) w.room.move(this, dx / d * spd * 0.6f * dt, dy / d * spd * 0.6f * dt)
                    if (nextChargeT <= 0f && !p.veiling) startCharge(p)
                    else if (d < 34f && cd <= 0f) { dirX = dx / d; dirY = dy / d; beginTelegraph(0.5f) }
                }
            }
            Act.CHARGE_WARN -> if (actT <= 0f) setAct(Act.CHARGE, 0.6f)
            Act.CHARGE -> {
                // Si velas durante la carga, te pierde y queda expuesto.
                if (p.veiling) { setAct(Act.EXPOSED, 1.5f); w.fx.floater("¡TE PERDIÓ!", x, y + 24f, Pal.LAMP_HI); return }
                val cx = chargeX - x
                val cy = chargeY - y
                val cdist = sqrt(cx * cx + cy * cy)
                if (cdist < 6f || actT <= 0f || w.room.move(this, cx / cdist * 230f * dt, cy / cdist * 230f * dt)) {
                    setAct(Act.EXPOSED, 1.0f)
                } else if (p.hurtOverlaps(x, y, radius + 2f)) {
                    w.hurtPlayer(dmg * 1.3f, x, y, this, "Embestida a oscuras")
                }
            }
            Act.EXPOSED -> if (actT <= 0f) setAct(if (phase == 2) Act.WALK_STRIKE else Act.SEARCH, 0f)
        }
    }

    private fun startCharge(p: Player) {
        chargeX = p.cx; chargeY = p.cy
        nextChargeT = if (phase == 3) 6f else 4f
        setAct(Act.CHARGE_WARN, Balance.telegraph(0.5f))
    }

    companion object {
        private val MARKS = floatArrayOf(0.6f, 0.25f)
        const val CONE_LEN = 170f
        const val CONE_HALF = 0.42f
    }
}

/**
 * Marea 10: la criatura se suelta de la lámpara y te persigue por la arena a oscuras. Solo se ve
 * su respiración luminosa. Velar la despista; tras cada embestida queda expuesta.
 */
class Criatura(def: EnemyDef, x: Float, y: Float) : Enemy(def, x, y, 1, null) {
    override val isBoss: Boolean get() = true
    override val blockedByWater: Boolean get() = false
    override val needsLight: Boolean get() = !exposed
    var breath = 0f
        private set
    override val lightRadius: Float get() = if (killed) 0f else 10f + 30f * breath
    var exposedT = 0f
        private set
    val exposed get() = exposedT > 0f
    private var wanderA = 0f
    private var hitDone = false

    override fun think(w: World, dt: Float) {
        if (killed) return
        breath = (sin(anim * 1.6f) * 0.5f + 0.5f)
        exposedT -= dt
        if (exposed) return
        val p = w.player
        val dx = p.cx - x
        val dy = p.cy - y
        val d = max(0.001f, sqrt(dx * dx + dy * dy))
        when (state) {
            EState.MOVE -> {
                cd -= dt
                if (p.veiling) {
                    wanderA += MathUtils.random(-2f, 2f) * dt
                    if (w.room.move(this, cos(wanderA) * spd * 0.4f * dt, sin(wanderA) * spd * 0.4f * dt)) wanderA += PI.toFloat()
                } else {
                    w.room.move(this, dx / d * spd * dt, dy / d * spd * dt)
                    if (d < def.range && cd <= 0f) { dirX = dx / d; dirY = dy / d; beginTelegraph(def.telegraph) }
                }
            }
            EState.TELEGRAPH -> {
                stateT -= dt
                if (stateT <= 0f) { state = EState.ATTACK; stateT = 0.35f; hitDone = false }
            }
            EState.ATTACK -> {
                w.room.move(this, dirX * 240f * dt, dirY * 240f * dt)
                if (!hitDone && p.hurtOverlaps(x, y, radius) && w.hurtPlayer(dmg, x, y, this, "La criatura")) hitDone = true
                stateT -= dt
                if (stateT <= 0f) { state = EState.MOVE; cd = def.cooldown; exposedT = 1.6f }
            }
            else -> {}
        }
    }
}
