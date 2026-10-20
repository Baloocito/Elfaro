package cl.xiombo.faro.world

import cl.xiombo.faro.data.Balance
import cl.xiombo.faro.data.EnemyDef
import cl.xiombo.faro.data.EnemyDefs
import cl.xiombo.faro.data.Pal
import cl.xiombo.faro.util.approach
import cl.xiombo.faro.util.dist
import cl.xiombo.faro.util.turnToward
import com.badlogic.gdx.math.MathUtils
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

enum class HitKind { MELEE, HAZ, BURN, BLAST }

/** Todo ataque tiene un estado de aviso propio (TELEGRAPH) de 0,35 s como mínimo (§1). */
enum class EState { MOVE, TELEGRAPH, ATTACK, RECOVER, DYING }

fun createEnemy(id: String, x: Float, y: Float, floor: Int): Enemy {
    val def = EnemyDefs[id]
    return when (id) {
        "ahogado" -> Ahogado(def, x, y, floor)
        "medusa" -> Medusa(def, x, y, floor)
        "cangrejo" -> Cangrejo(def, x, y, floor)
        else -> error("Enemigo desconocido: $id")
    }
}

abstract class Enemy(val def: EnemyDef, x: Float, y: Float, floor: Int) : Body(x, y, def.hw, def.hh) {
    val maxHp = def.hp * Balance.hpMul(floor)
    var hp = maxHp
    val dmg = def.dmg * Balance.dmgMul(floor)
    val speed = def.speed * Balance.speedMul(floor)

    var state = EState.MOVE
    var stateT = 0f
    var cd = MathUtils.random(0.4f, 1.0f)
    var flash = 0f
    var burnT = 0f
    var kbX = 0f
    var kbY = 0f
    var dirX = 1f
    var dirY = 0f
    var anim = MathUtils.random(10f)

    /** killed: ya soltó Luz y cuenta como baja. dead: se retira de la lista. */
    var killed = false
    var dead = false

    val radius get() = max(hw, hh)
    open val targetable get() = !killed
    open val lightRadius: Float get() = if (state == EState.TELEGRAPH) 22f else 0f

    fun update(w: World, dt: Float) {
        anim += dt
        flash -= dt
        if (burnT > 0f && !killed) {
            burnT -= dt
            takeHit(w, Balance.BURN_DPS * dt, x, y, HitKind.BURN)
            if (MathUtils.randomBoolean(0.25f)) w.fx.ember(x, y)
        }
        if (kbX != 0f || kbY != 0f) {
            w.room.move(this, kbX * dt, kbY * dt)
            kbX = approach(kbX, 0f, 700f * dt)
            kbY = approach(kbY, 0f, 700f * dt)
        }
        if (!dead) think(w, dt)
    }

    protected abstract fun think(w: World, dt: Float)

    open fun takeHit(w: World, amount: Float, fromX: Float, fromY: Float, kind: HitKind): Boolean {
        if (!targetable) return false
        hp -= amount
        if (kind == HitKind.MELEE || kind == HitKind.BLAST) flash = 0.1f
        else if (kind == HitKind.HAZ) flash = 0.03f
        if (hp <= 0f) {
            hp = 0f
            killed = true
            w.onEnemyKilled(this)
            onKilled(w)
        }
        return true
    }

    protected open fun onKilled(w: World) { dead = true }

    fun knock(dx: Float, dy: Float, force: Float) { kbX = dx * force; kbY = dy * force }
    fun burn(t: Float) { burnT = max(burnT, t) }

    protected fun beginTelegraph(t: Float) { state = EState.TELEGRAPH; stateT = t }
}

/** Ahogado: persigue y embiste 40 px tras agacharse y brillar 0,4 s. */
class Ahogado(def: EnemyDef, x: Float, y: Float, floor: Int) : Enemy(def, x, y, floor) {
    private var hitDone = false

    override fun think(w: World, dt: Float) {
        val p = w.player
        val dx = p.cx - x
        val dy = p.cy - y
        val d = max(0.001f, sqrt(dx * dx + dy * dy))
        when (state) {
            EState.MOVE -> {
                cd -= dt
                if (d > 22f) w.room.move(this, dx / d * speed * dt, dy / d * speed * dt)
                if (d < def.range + 10f && cd <= 0f) {
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
                if (!hitDone && p.hurtOverlaps(x, y, radius) && w.hurtPlayer(dmg, x, y)) hitDone = true
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

    companion object {
        const val LUNGE_TIME = 0.2f
        const val LUNGE_SPEED = 200f   // 0,2 s × 200 = 40 px
    }
}

/** Medusa-farol: flota y emite luz. Pulso eléctrico con aviso; al morir explota en 1 s (radio 32 px). */
class Medusa(def: EnemyDef, x: Float, y: Float, floor: Int) : Enemy(def, x, y, floor) {
    var explodeT = 0f
        private set

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
        val dx = p.cx - x
        val dy = p.cy - y
        val d = max(0.001f, sqrt(dx * dx + dy * dy))
        when (state) {
            EState.MOVE -> {
                cd -= dt
                val wob = sin(anim * 1.7f) * 0.6f
                if (d > 16f) {
                    val mx = dx / d - dy / d * wob
                    val my = dy / d + dx / d * wob
                    w.room.move(this, mx * speed * dt, my * speed * dt)
                }
                if (d < PULSE_R + 6f && cd <= 0f) beginTelegraph(def.telegraph)
            }
            EState.TELEGRAPH -> {
                stateT -= dt
                if (stateT <= 0f) {
                    if (p.hurtOverlaps(x, y, PULSE_R)) w.hurtPlayer(dmg, x, y)
                    w.fx.ring(x, y, PULSE_R, Pal.MEDUSA)
                    w.fx.burst(x, y, 8, Pal.MEDUSA, 90f, 0.25f)
                    state = EState.RECOVER
                    stateT = 0.5f
                }
            }
            EState.RECOVER -> {
                stateT -= dt
                if (stateT <= 0f) { state = EState.MOVE; cd = def.cooldown }
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
        if (p.hurtOverlaps(x, y, BLAST_R)) w.hurtPlayer(dmg * 1.5f, x, y)
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
class Cangrejo(def: EnemyDef, x: Float, y: Float, floor: Int) : Enemy(def, x, y, floor) {
    var face = MathUtils.random(MathUtils.PI2)
        private set
    private var side = if (MathUtils.randomBoolean()) 1f else -1f
    private var sideT = 1f
    private var hitDone = false

    override fun think(w: World, dt: Float) {
        val p = w.player
        val dx = p.cx - x
        val dy = p.cy - y
        val d = max(0.001f, sqrt(dx * dx + dy * dy))
        if (state == EState.MOVE || state == EState.RECOVER) face = turnToward(face, atan2(dy, dx), TURN * dt)
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
                if (w.room.move(this, mx / l * speed * dt, my / l * speed * dt)) side = -side
                if (d < def.range + 8f && cd <= 0f) beginTelegraph(def.telegraph)
            }
            EState.TELEGRAPH -> {
                stateT -= dt
                if (stateT <= 0f) { state = EState.ATTACK; stateT = 0.12f; hitDone = false }
            }
            EState.ATTACK -> {
                if (!hitDone && p.hurtOverlaps(x + fx * 14f, y + fy * 14f, 12f) && w.hurtPlayer(dmg, x, y)) hitDone = true
                stateT -= dt
                if (stateT <= 0f) { state = EState.RECOVER; stateT = 0.6f }
            }
            EState.RECOVER -> {
                stateT -= dt
                if (stateT <= 0f) { state = EState.MOVE; cd = def.cooldown }
            }
            EState.DYING -> {}
        }
    }

    override fun takeHit(w: World, amount: Float, fromX: Float, fromY: Float, kind: HitKind): Boolean {
        if (kind == HitKind.MELEE && targetable) {
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
