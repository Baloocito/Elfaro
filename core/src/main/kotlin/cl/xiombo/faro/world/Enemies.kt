package cl.xiombo.faro.world

import cl.xiombo.faro.data.Balance
import cl.xiombo.faro.data.EnemyDef
import cl.xiombo.faro.data.EnemyDefs
import cl.xiombo.faro.data.Pal
import cl.xiombo.faro.util.approach
import cl.xiombo.faro.util.dist
import cl.xiombo.faro.util.turnToward
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.MathUtils
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class HitKind { MELEE, HAZ, BURN, BLAST, SLAM, LAMP }

/** Todo ataque tiene un estado de aviso propio (TELEGRAPH) de 0,35 s como mínimo (§1). */
enum class EState { MOVE, TELEGRAPH, ATTACK, RECOVER, DYING }

/** Afijos de élite (§5). Élite: vida ×2,5, daño ×1,3, suelta 6 Luz y 30 % de exvoto. */
enum class Affix(val label: String, val color: Color) {
    SALADO("Salado", Pal.SALADO),
    BRILLANTE("Brillante", Pal.BRILLANTE),
    GEMELO("Gemelo", Pal.GEMELO),
    HAMBRIENTO("Hambriento", Pal.HAMBRIENTO),
    /** Catedral: un escudo absorbe el primer golpe cada 4 s (cualquier golpe lo rompe). */
    BENDECIDO("Bendecido", Pal.BENDECIDO),
    /** Archivo: invisible fuera de tu luz (como la Tinta); su barra de vida sí se ve. La Campana lo revela 3 s. */
    TACHADO("Tachado", Pal.TACHADO),
    /** Raíz: +10 % de tamaño y de daño por cada 20 de tu Luz actual. */
    HAMBRE("Hambre de luz", Pal.HUNGER),
}

fun createEnemy(id: String, x: Float, y: Float, floor: Int, affix: Affix? = null): Enemy {
    val def = EnemyDefs[id]
    return when (id) {
        "ahogado" -> Ahogado(def, x, y, floor, affix)
        "medusa" -> Medusa(def, x, y, floor, affix)
        "cangrejo" -> Cangrejo(def, x, y, floor, affix)
        "sirena" -> Sirena(def, x, y)
        "monaguillo" -> Monaguillo(def, x, y, floor, affix)
        "campanero" -> Campanero(def, x, y, floor, affix)
        "vela" -> Vela(def, x, y, floor, affix)
        "cofrade" -> Cofrade(def, x, y, floor, affix)
        "obispo" -> Obispo(def, x, y)
        "tinta" -> Tinta(def, x, y, floor, affix)
        "bibliotecario" -> Bibliotecario(def, x, y, floor, affix)
        "polilla" -> Polilla(def, x, y, floor, affix)
        "copista" -> Copista(def, x, y, floor, affix)
        "cartografo" -> Cartografo(def, x, y)
        "anguila" -> Anguila(def, x, y, floor, affix)
        "hijo" -> HijoDelFaro(def, x, y, floor, affix)
        "vidriero" -> Vidriero(def, x, y, floor, affix)
        "ojo" -> OjoFosa(def, x, y, floor, affix)
        "lampara" -> LamparaMadre(def, x, y)
        else -> error("Enemigo desconocido: $id")
    }
}

abstract class Enemy(val def: EnemyDef, x: Float, y: Float, val floor: Int, val affix: Affix?) :
    Body(x, y, def.hw, def.hh) {
    val isElite = affix != null
    /** Marea 9: las élites llevan un segundo afijo. */
    var affix2: Affix? = null
    fun has(a: Affix) = affix == a || affix2 == a
    open val isBoss: Boolean get() = false
    /** Jefes: la barra de vida aparece cuando termina la presentación. */
    open val introduced: Boolean get() = true
    /** Jefes: marcas de fase en la barra (fracción de vida). */
    open val phaseMarks: FloatArray get() = FloatArray(0)

    val maxHp = def.hp * Balance.hpMul(floor) * (if (affix != null) 2.5f else 1f)
    var hp = maxHp
    val dmg = def.dmg * Balance.dmgMul(floor) * (if (affix != null) 1.3f else 1f)
    val speed = def.speed * Balance.speedMul(floor)

    var state = EState.MOVE
    var stateT = 0f
    var cd = MathUtils.random(0.4f, 1.0f)
    var flash = 0f
    var burnT = 0f
    var slowT = 0f
    private var slowMul = 1f
    var kbX = 0f
    var kbY = 0f
    private var slamArmed = false
    private var wallStun = 0f

    /** Aturdido: no se mueve ni ataca y recibe +25 % de daño. Después, 2 s de inmunidad. */
    var stunT = 0f
        private set
    private var stunImmuneT = 0f
    val stunned get() = stunT > 0f
    var dirX = 1f
    var dirY = 0f
    var anim = MathUtils.random(10f)
    var hitByPlayer = false

    /** Te perdió el rastro mientras velabas: va a donde te vio por última vez y busca. */
    var lost = false
    var lostX = 0f
    var lostY = 0f
    /** Objetivo de este enemigo: Isla (o la estatua) o, si te perdió, el punto donde busca. */
    protected fun tgtX(w: World) = if (lost) lostX else w.targetX
    protected fun tgtY(w: World) = if (lost) lostY else w.targetY
    var split = false
    private var puddleT = 0f
    /** Bendecido: segundos hasta que vuelve el escudo (<= 0 = escudo puesto). */
    var shieldCd = 0f
    val shielded get() = has(Affix.BENDECIDO) && shieldCd <= 0f && !killed

    /** killed: ya soltó Luz y cuenta como baja. dead: se retira de la lista. */
    var killed = false
    var dead = false

    val radius get() = max(hw, hh)
    val slowed get() = slowT > 0f
    /** Multiplicador del latido de la Raíz (lo pone World cada tick). */
    var boost = 1f
    val spd get() = (if (slowed) speed * slowMul else speed) * boost

    /** Solo existe dentro de tu luz (Tinta, élites Tachados): no se ve ni se golpea fuera de ella. */
    open val needsLight: Boolean get() = has(Affix.TACHADO)
    /** Dentro de la luz de Isla este tick (lo pone World). */
    var inLight = true
    /** La Campana revela a los que solo existen en la luz durante 3 s. */
    var revealT = 0f
    val hidden get() = needsLight && !inLight && revealT <= 0f && !killed
    open val targetable get() = !killed && !hidden
    open val lightRadius: Float
        get() = when {
            has(Affix.BRILLANTE) && !killed -> 40f
            state == EState.TELEGRAPH -> 22f
            else -> 0f
        }

    fun update(w: World, dt: Float) {
        anim += dt
        flash -= dt
        slowT -= dt
        stunImmuneT -= dt
        shieldCd -= dt
        revealT -= dt
        if (stunT > 0f) {
            stunT -= dt
            if (stunT <= 0f) stunImmuneT = 2f
        }
        if (burnT > 0f && !killed) {
            burnT -= dt
            takeHit(w, w.stats.burnDps * dt, x, y, HitKind.BURN)
            if (MathUtils.randomBoolean(0.25f)) w.fx.ember(x, y)
        }
        if (kbX != 0f || kbY != 0f) {
            val hit = w.room.move(this, kbX * dt, kbY * dt)
            if (hit && wallStun > 0f && !killed) {
                stun(wallStun)
                wallStun = 0f
                w.fx.spark(x, y)
            }
            if (hit && slamArmed && w.stats.wallSlam && !killed) {
                slamArmed = false
                takeHit(w, 25f, x, y, HitKind.SLAM)
                w.fx.spark(x, y)
                w.fx.floater("25", x, y + 12f, Pal.TIDE)
                w.addShake(2f)
            }
            kbX = approach(kbX, 0f, 700f * dt)
            kbY = approach(kbY, 0f, 700f * dt)
        }
        if (has(Affix.SALADO) && !killed) {
            puddleT -= dt
            if (puddleT <= 0f) {
                puddleT = 0.6f
                w.addPuddle(x, y - hh + 2f)
            }
        }
        // Aturdido no piensa; la Medusa que ya murió sí sigue su cuenta atrás para explotar.
        if (!dead && (!stunned || killed)) think(w, dt)
    }

    protected abstract fun think(w: World, dt: Float)

    open fun takeHit(w: World, amount: Float, fromX: Float, fromY: Float, kind: HitKind): Boolean {
        if (!targetable) return false
        if (kind != HitKind.BURN) lost = false
        if (shielded) {
            // El escudo se lleva el golpe entero, sea cual sea.
            shieldCd = 4f
            flash = 0.08f
            w.fx.ring(x, y, radius + 6f, Pal.BENDECIDO)
            w.fx.burst(x, y, 8, Pal.BENDECIDO, 80f, 0.3f)
            return true
        }
        hp -= if (stunned) amount * 1.25f else amount
        when (kind) {
            HitKind.MELEE, HitKind.BLAST, HitKind.SLAM, HitKind.LAMP -> flash = 0.1f
            HitKind.HAZ -> flash = 0.03f
            HitKind.BURN -> {}
        }
        if (has(Affix.GEMELO) && !split && hp > 0f && hp <= maxHp / 2f) {
            split = true
            w.splitTwin(this)
        }
        if (hp <= 0f) {
            hp = 0f
            killed = true
            w.onEnemyKilled(this)
            onKilled(w)
        }
        return true
    }

    protected open fun onKilled(w: World) { dead = true }

    /** Empuje. Con [stunOnWall] > 0 queda aturdido si choca contra una pared (Ancla). */
    fun knock(dx: Float, dy: Float, force: Float, stunOnWall: Float = 0f) {
        if (isBoss) return
        kbX = dx * force
        kbY = dy * force
        slamArmed = true
        wallStun = stunOnWall
    }

    /** Aturde [t] segundos (los jefes, la mitad). No se encadena: 2 s de inmunidad al terminar. */
    fun stun(t: Float) {
        if (killed || stunImmuneT > 0f || !targetable) return
        val d = if (isBoss) t * 0.5f else t
        if (d > stunT) stunT = d
        if (state == EState.TELEGRAPH || state == EState.ATTACK) { state = EState.RECOVER; stateT = 0.3f }
    }

    fun burn(t: Float) { burnT = max(burnT, t) }

    fun slow(mul: Float, t: Float) {
        if (!slowed || mul < slowMul) slowMul = mul
        slowT = max(slowT, t)
    }

    protected fun beginTelegraph(t: Float) { state = EState.TELEGRAPH; stateT = Balance.telegraph(t) }
}

/** Ahogado: persigue y embiste 40 px tras agacharse y brillar 0,4 s. */
class Ahogado(def: EnemyDef, x: Float, y: Float, floor: Int, affix: Affix?) : Enemy(def, x, y, floor, affix) {
    private var hitDone = false

    override fun think(w: World, dt: Float) {
        val p = w.player
        val dx = tgtX(w) - x
        val dy = tgtY(w) - y
        val d = max(0.001f, sqrt(dx * dx + dy * dy))
        when (state) {
            EState.MOVE -> {
                cd -= dt
                if (d > 22f) w.room.move(this, dx / d * spd * dt, dy / d * spd * dt)
                if (!lost && d < def.range + 10f && cd <= 0f) {
                    dirX = dx / d; dirY = dy / d
                    beginTelegraph(def.telegraph)
                }
            }
            EState.TELEGRAPH -> {
                stateT -= dt
                if (stateT <= 0f) { state = EState.ATTACK; stateT = LUNGE_TIME; hitDone = false }
            }
            EState.ATTACK -> {
                w.room.move(this, dirX * LUNGE_SPEED * dt, dirY * LUNGE_SPEED * dt)
                if (!hitDone && p.hurtOverlaps(x, y, radius) && w.hurtPlayer(dmg, x, y, this, "Embestida")) hitDone = true
                stateT -= dt
                if (stateT <= 0f) { state = EState.RECOVER; stateT = 0.45f }
            }
            EState.RECOVER -> {
                stateT -= dt
                if (stateT <= 0f) { state = EState.MOVE; cd = def.cooldown * Balance.cooldownMul }
            }
            EState.DYING -> {}
        }
    }

    companion object {
        const val LUNGE_TIME = 0.2f
        const val LUNGE_SPEED = 200f   // 0,2 s × 200 = 40 px
    }
}

/** Medusa-farol: flota y emite luz. Pulso con aviso; al morir explota en 1 s (radio 32 px). */
class Medusa(def: EnemyDef, x: Float, y: Float, floor: Int, affix: Affix?) : Enemy(def, x, y, floor, affix) {
    var explodeT = 0f
        private set

    override val blockedByWater: Boolean get() = false

    override val lightRadius: Float
        get() = when {
            killed -> 30f + 36f * (1f - explodeT)
            state == EState.TELEGRAPH -> 54f
            else -> 44f
        }

    override fun think(w: World, dt: Float) {
        if (killed) {
            explodeT -= dt
            if (explodeT <= 0f) explode(w)
            return
        }
        val p = w.player
        val dx = tgtX(w) - x
        val dy = tgtY(w) - y
        val d = max(0.001f, sqrt(dx * dx + dy * dy))
        when (state) {
            EState.MOVE -> {
                cd -= dt
                val wob = sin(anim * 1.7f) * 0.6f
                if (d > 16f) {
                    val mx = dx / d - dy / d * wob
                    val my = dy / d + dx / d * wob
                    w.room.move(this, mx * spd * dt, my * spd * dt)
                }
                if (!lost && d < PULSE_R + 6f && cd <= 0f) beginTelegraph(def.telegraph)
            }
            EState.TELEGRAPH -> {
                stateT -= dt
                if (stateT <= 0f) {
                    if (p.hurtOverlaps(x, y, PULSE_R)) w.hurtPlayer(dmg, x, y, this, "Pulso")
                    w.fx.ring(x, y, PULSE_R, Pal.MEDUSA)
                    w.fx.burst(x, y, 8, Pal.MEDUSA, 90f, 0.25f)
                    state = EState.RECOVER
                    stateT = 0.5f
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
        explodeT = 1f
    }

    private fun explode(w: World) {
        dead = true
        val p = w.player
        if (p.hurtOverlaps(x, y, BLAST_R)) w.hurtPlayer(dmg * 1.5f, x, y, this, "Explosión al morir")
        for (o in w.enemies) {
            if (o === this || !o.targetable) continue
            if (dist(x, y, o.x, o.y) <= BLAST_R + o.radius) o.takeHit(w, BLAST_DMG, x, y, HitKind.BLAST)
        }
        w.fx.blast(x, y, BLAST_R)
        w.addShake(3f)
    }

    companion object {
        const val PULSE_R = 24f
        const val BLAST_R = 32f
        const val BLAST_DMG = 20f
    }
}

/**
 * Cangrejo-relicario: se mueve de lado y gira despacio. Inmune de frente al Farol:
 * hay que rodearlo (el Destello sirve para eso) o usar el Haz.
 */
class Cangrejo(def: EnemyDef, x: Float, y: Float, floor: Int, affix: Affix?) : Enemy(def, x, y, floor, affix) {
    var face = MathUtils.random(MathUtils.PI2)
        private set
    private var side = if (MathUtils.randomBoolean()) 1f else -1f
    private var sideT = 1f
    private var hitDone = false

    override fun think(w: World, dt: Float) {
        val p = w.player
        val dx = tgtX(w) - x
        val dy = tgtY(w) - y
        val d = max(0.001f, sqrt(dx * dx + dy * dy))
        val turn = if (slowed) TURN * 0.6f else TURN
        if (state == EState.MOVE || state == EState.RECOVER) face = turnToward(face, atan2(dy, dx), turn * dt)
        val fx = cos(face)
        val fy = sin(face)
        when (state) {
            EState.MOVE -> {
                cd -= dt
                sideT -= dt
                if (sideT <= 0f) { side = -side; sideT = MathUtils.random(0.9f, 1.7f) }
                var mx = -fy * side
                var my = fx * side
                if (d > 44f) { mx += fx * 0.8f; my += fy * 0.8f } else if (d < 18f) { mx -= fx * 0.6f; my -= fy * 0.6f }
                val l = max(0.001f, sqrt(mx * mx + my * my))
                if (w.room.move(this, mx / l * spd * dt, my / l * spd * dt)) side = -side
                if (!lost && d < def.range + 8f && cd <= 0f) beginTelegraph(def.telegraph)
            }
            EState.TELEGRAPH -> {
                stateT -= dt
                if (stateT <= 0f) { state = EState.ATTACK; stateT = 0.12f; hitDone = false }
            }
            EState.ATTACK -> {
                if (!hitDone && p.hurtOverlaps(x + fx * 14f, y + fy * 14f, 12f) && w.hurtPlayer(dmg, x, y, this, "Pinza")) hitDone = true
                stateT -= dt
                if (stateT <= 0f) { state = EState.RECOVER; stateT = 0.6f }
            }
            EState.RECOVER -> {
                stateT -= dt
                if (stateT <= 0f) { state = EState.MOVE; cd = def.cooldown * Balance.cooldownMul }
            }
            EState.DYING -> {}
        }
    }

    override fun takeHit(w: World, amount: Float, fromX: Float, fromY: Float, kind: HitKind): Boolean {
        if (kind == HitKind.MELEE && targetable && !stunned) {
            val dx = fromX - x
            val dy = fromY - y
            val d = max(0.001f, sqrt(dx * dx + dy * dy))
            if ((dx * cos(face) + dy * sin(face)) / d > FRONT_COS) {
                w.fx.spark(x + cos(face) * 9f, y + sin(face) * 9f)
                w.fx.floater("CLANC", x, y + 12f, Pal.SHELL)
                return false
            }
        }
        return super.takeHit(w, amount, fromX, fromY, kind)
    }

    companion object {
        const val TURN = 2.2f          // rad/s
        const val FRONT_COS = 0.34f    // ±70° se considera "de frente"
    }
}

/**
 * La Sirena de Brea (§5), 900 de vida.
 * Fase 1: canta; las notas son proyectiles lentos en espiral, alternando con abanicos dirigidos.
 * Fase 2 (60 %): la brea inunda la sala y apaga tu luz un 30 %; doble espiral y abanicos más anchos.
 * Fase 3 (25 %): se sumerge y emerge bajo ti con un aviso de burbujas de 0,6 s, tres veces seguidas.
 */
class Sirena(def: EnemyDef, x: Float, y: Float) : Enemy(def, x, y, 1, null) {
    enum class Act { INTRO, IDLE, SING_TELE, SING, AIM_TELE, AIM, ROAR, DIVE, BUBBLES, EXPOSED }

    override val isBoss: Boolean get() = true
    override val blockedByWater: Boolean get() = false

    var phase = 1
        private set
    var act = Act.INTRO
        private set
    var actT = 1.8f
        private set
    var actMax = 1.8f
        private set
    private val homeX = x
    private val homeY = y
    var bubbleX = 0f
        private set
    var bubbleY = 0f
        private set
    private var spiralA = 0f
    private var emitT = 0f
    private var dives = 0
    private var pattern = 0
    var aimA = -PI.toFloat() / 2f
        private set

    val submerged get() = (act == Act.DIVE && actT < 0.25f) || act == Act.BUBBLES
    override val targetable get() = !killed && act != Act.INTRO && act != Act.ROAR && !submerged
    override val lightRadius: Float
        get() = when {
            killed || submerged -> 0f
            act == Act.SING_TELE || act == Act.AIM_TELE || act == Act.ROAR -> 80f
            else -> 58f
        }

    private fun setAct(a: Act, t: Float) { act = a; actT = t; actMax = t }

    override fun think(w: World, dt: Float) {
        if (killed) return
        actT -= dt
        if (phase == 1 && hp <= maxHp * 0.6f) {
            phase = 2; setAct(Act.ROAR, 1.2f); w.onBossPhase(2); return
        }
        if (phase == 2 && hp <= maxHp * 0.25f) {
            phase = 3; dives = 0; setAct(Act.ROAR, 1.2f); w.onBossPhase(3); return
        }
        val p = w.player
        when (act) {
            Act.INTRO -> if (actT <= 0f) setAct(Act.IDLE, 0.6f)
            Act.IDLE -> {
                val tx = homeX + sin(anim * 0.6f) * 70f
                x += (tx - x) * min(1f, dt * 2f)
                y += (homeY - y) * min(1f, dt * 2f)
                if (actT <= 0f) next()
            }
            Act.ROAR -> if (actT <= 0f) { if (phase == 3) setAct(Act.DIVE, 0.5f) else setAct(Act.IDLE, 0.6f) }
            Act.SING_TELE -> if (actT <= 0f) { setAct(Act.SING, if (phase == 1) 2.4f else 2.8f); emitT = 0f }
            Act.SING -> {
                emitT -= dt
                if (emitT <= 0f) {
                    emitT = if (phase == 1) 0.10f else 0.08f
                    val arms = if (phase == 1) 1 else 2
                    val sp = if (phase == 1) 60f else 72f
                    for (k in 0 until arms) {
                        val a = spiralA + k * PI.toFloat()
                        w.shoot(x, y, cos(a) * sp, sin(a) * sp, dmg)
                    }
                    spiralA += 0.37f
                }
                if (actT <= 0f) { if (phase == 3) setAct(Act.DIVE, 0.5f) else setAct(Act.IDLE, 1.0f) }
            }
            Act.AIM_TELE -> {
                if (actT > 0.15f) aimA = atan2(p.cy - y, p.cx - x)   // el último instante queda fijo
                if (actT <= 0f) {
                    val n = if (phase == 1) 5 else 7
                    for (i in 0 until n) {
                        val a = aimA + (i - (n - 1) / 2f) * 0.2f
                        w.shoot(x, y, cos(a) * 115f, sin(a) * 115f, dmg)
                    }
                    setAct(Act.AIM, 0.3f)
                }
            }
            Act.AIM -> if (actT <= 0f) setAct(Act.IDLE, 1.0f)
            Act.DIVE -> if (actT <= 0f) {
                bubbleX = p.cx
                bubbleY = p.y
                setAct(Act.BUBBLES, 0.6f)
            }
            Act.BUBBLES -> if (actT <= 0f) {
                x = bubbleX
                y = bubbleY
                snapshot()
                if (p.hurtOverlaps(x, y, EMERGE_R)) w.hurtPlayer(dmg * 1.6f, x, y, this, "Emergió bajo ti")
                w.fx.ring(x, y, EMERGE_R, Pal.CORAL)
                w.fx.burst(x, y, 16, Pal.BREA, 130f, 0.5f)
                w.fx.burst(x, y, 8, Pal.FOAM, 100f, 0.4f)
                w.addShake(3f)
                setAct(Act.EXPOSED, 1.1f)
            }
            Act.EXPOSED -> if (actT <= 0f) {
                dives++
                if (dives < 3) setAct(Act.DIVE, 0.5f)
                else {
                    dives = 0
                    x = homeX; y = homeY; snapshot()
                    setAct(Act.SING_TELE, 0.6f)
                }
            }
        }
    }

    private fun next() {
        pattern++
        if (pattern % 2 == 1) setAct(Act.SING_TELE, def.telegraph) else setAct(Act.AIM_TELE, 0.5f)
    }

    companion object {
        const val EMERGE_R = 26f
    }
}
