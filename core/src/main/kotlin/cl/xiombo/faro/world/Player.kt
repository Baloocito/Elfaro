package cl.xiombo.faro.world

import cl.xiombo.faro.data.Balance
import cl.xiombo.faro.data.Pal
import cl.xiombo.faro.input.InputState
import cl.xiombo.faro.util.approach
import cl.xiombo.faro.util.len
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Isla, la farera. Caja de colisión 8 × 6 a los pies (x, y); caja de daño 10 × 16 centrada en (cx, cy).
 */
class Player(x: Float, y: Float) : Body(x, y, 4f, 3f) {
    var luzMax = Balance.LUZ_MAX
    var luz = Balance.LUZ_MAX

    var vx = 0f
    var vy = 0f
    var kbX = 0f
    var kbY = 0f
    var aimX = 1f
    var aimY = 0f
    private var lastMoveX = 1f
    private var lastMoveY = 0f

    // Destello
    var dashT = 0f
    var dashCd = 0f
    var dashIframes = 0f
    private var dashDx = 0f
    private var dashDy = 0f
    private var dashTick = 0

    var hurtIframes = 0f

    // Farol: atkFrame cuenta frames de lógica dentro del golpe (-1 = sin atacar)
    var atkFrame = -1
    var comboIdx = 0
    private var comboTimer = 0f
    var atkDx = 1f
    var atkDy = 0f
    var swingIsThird = false
    private val swingHits = HashSet<Enemy>()

    // Haz
    var hazT = 0f
    var hazLen = 0f

    val cx get() = x
    val cy get() = y + 5f
    val invulnerable get() = dashIframes > 0f || hurtIframes > 0f
    val attacking get() = atkFrame >= 0
    val swingActive get() = atkFrame >= Balance.FAROL_STARTUP && atkFrame < Balance.FAROL_STARTUP + Balance.FAROL_ACTIVE
    val swingSmear get() = atkFrame >= Balance.FAROL_STARTUP + Balance.FAROL_ACTIVE && atkFrame < Balance.FAROL_STARTUP + Balance.FAROL_ACTIVE + 2
    val lightRadius get() = Balance.lightRadius(luz)
    val swingRange get() = Balance.FAROL_RANGE + if (swingIsThird) 4f else 0f

    fun hurtOverlaps(ox: Float, oy: Float, r: Float): Boolean {
        val nx = ox.coerceIn(cx - 5f, cx + 5f)
        val ny = oy.coerceIn(cy - 8f, cy + 8f)
        val dx = ox - nx
        val dy = oy - ny
        return dx * dx + dy * dy <= r * r
    }

    fun update(w: World, inp: InputState, dt: Float) {
        dashCd -= dt
        dashIframes -= dt
        hurtIframes -= dt
        kbX = approach(kbX, 0f, 900f * dt)
        kbY = approach(kbY, 0f, 900f * dt)

        val moving = inp.mx != 0f || inp.my != 0f
        if (moving) {
            val l = len(inp.mx, inp.my)
            lastMoveX = inp.mx / l
            lastMoveY = inp.my / l
        }
        updateAim(w, inp)

        // Destello: cancela la recuperación del Farol y corta el Haz.
        if (inp.dashBuf > 0f && dashCd <= 0f && dashT <= 0f) {
            inp.dashBuf = 0f
            if (moving) { dashDx = lastMoveX; dashDy = lastMoveY } else { dashDx = aimX; dashDy = aimY }
            dashT = Balance.DASH_TIME
            dashIframes = Balance.DASH_IFRAMES
            dashCd = Balance.DASH_COOLDOWN + Balance.DASH_TIME
            dashTick = 0
            atkFrame = -1
            hazT = 0f
            w.fx.burst(x, y, 5, Pal.CREAM, 60f, 0.25f)
        }
        if (dashT > 0f) {
            dashT -= dt
            w.room.move(this, dashDx * Balance.DASH_SPEED * dt, dashDy * Balance.DASH_SPEED * dt)
            if (dashTick++ % 3 == 0) w.fx.ghost(x, y)
            vx = dashDx * Balance.SPEED
            vy = dashDy * Balance.SPEED
            return
        }

        // Haz: cuesta Luz; no se puede usar con 8 de Luz o menos.
        if (inp.hazBuf > 0f && hazT <= 0f && !attacking) {
            inp.hazBuf = 0f
            if (luz > Balance.HAZ_COST) {
                luz -= Balance.HAZ_COST
                hazT = Balance.HAZ_TIME
                w.fx.floater("-8", cx, cy + 14f, Pal.LAMP)
            } else {
                w.fx.floater("SIN LUZ", cx, cy + 14f, Pal.CORAL)
            }
        }
        if (hazT > 0f) {
            hazT -= dt
            updateHaz(w, dt)
        }

        // Farol: combo de 3 golpes
        if (!attacking && hazT <= 0f) {
            comboTimer -= dt
            if (comboTimer <= 0f) comboIdx = 0
            if (inp.attackBuf > 0f) {
                inp.attackBuf = 0f
                startSwing()
            }
        }
        if (attacking) updateSwing(w, inp)

        // Movimiento con aceleración y frenado
        var max = Balance.SPEED
        if (attacking) max *= Balance.ATTACK_MOVE_FACTOR
        if (hazT > 0f) max *= Balance.HAZ_MOVE_FACTOR
        val rate = (if (moving) Balance.ACCEL else Balance.DECEL) * dt
        vx = approach(vx, inp.mx * max, rate)
        vy = approach(vy, inp.my * max, rate)
        w.room.move(this, (vx + kbX) * dt, (vy + kbY) * dt)
    }

    private fun updateAim(w: World, inp: InputState) {
        if (inp.hasAim) {
            val l = len(inp.aimX, inp.aimY)
            if (l > 0.5f) { aimX = inp.aimX / l; aimY = inp.aimY / l }
            return
        }
        // Táctil: auto-apuntado al enemigo más cercano dentro del cono de movimiento.
        var best: Enemy? = null
        var bestD = 170f
        for (e in w.enemies) {
            if (!e.targetable) continue
            val dx = e.x - cx
            val dy = e.y - cy
            val d = sqrt(dx * dx + dy * dy)
            if (d >= bestD) continue
            val inCone = d < 1f || (dx * lastMoveX + dy * lastMoveY) / d > 0.5f
            if (!inCone && d > 48f) continue
            best = e
            bestD = d
        }
        if (best != null) {
            val dx = best.x - cx
            val dy = best.y - cy
            val l = len(dx, dy)
            if (l > 0.5f) { aimX = dx / l; aimY = dy / l }
        } else {
            aimX = lastMoveX
            aimY = lastMoveY
        }
    }

    private fun startSwing() {
        atkFrame = 0
        atkDx = aimX
        atkDy = aimY
        swingIsThird = comboIdx == 2
        swingHits.clear()
    }

    private fun updateSwing(w: World, inp: InputState) {
        if (atkFrame < Balance.FAROL_STARTUP) w.room.move(this, atkDx * 0.6f, atkDy * 0.6f)
        if (swingActive) hitCone(w)
        atkFrame++
        if (atkFrame >= Balance.FAROL_TOTAL) {
            atkFrame = -1
            comboIdx = (comboIdx + 1) % 3
            comboTimer = Balance.COMBO_WINDOW
            if (inp.attackBuf > 0f) {
                inp.attackBuf = 0f
                startSwing()
            }
        }
    }

    private fun hitCone(w: World) {
        val cosHalf = cos(Math.toRadians(Balance.FAROL_HALF_ANGLE_DEG.toDouble())).toFloat()
        val range = swingRange
        for (e in w.enemies) {
            if (!e.targetable || e in swingHits) continue
            val dx = e.x - cx
            val dy = e.y - cy
            val d = sqrt(dx * dx + dy * dy)
            if (d - e.radius > range) continue
            // Tolerancia según el tamaño del enemigo: favorece al jugador.
            if (d > 6f && (dx * atkDx + dy * atkDy) / d < cosHalf - e.radius / d) continue
            swingHits += e
            if (e.takeHit(w, Balance.FAROL_DMG, cx, cy, HitKind.MELEE)) {
                e.knock(atkDx, atkDy, if (swingIsThird) 150f else 80f)
                if (swingIsThird) e.burn(Balance.BURN_TIME)
                w.hitstop(Balance.HITSTOP_HIT)
                w.fx.burst(e.x, e.y, 4, Pal.LAMP_HI, 80f, 0.2f)
            }
        }
    }

    private fun updateHaz(w: World, dt: Float) {
        hazLen = w.room.rayLength(cx, cy, aimX, aimY, Balance.HAZ_MAX_LEN)
        val half = Balance.HAZ_WIDTH / 2f
        for (e in w.enemies) {
            if (!e.targetable) continue
            val ex = e.x - cx
            val ey = e.y - cy
            val t = (ex * aimX + ey * aimY).coerceIn(0f, hazLen)
            val px = ex - aimX * t
            val py = ey - aimY * t
            val r = half + e.radius
            if (px * px + py * py <= r * r) e.takeHit(w, Balance.HAZ_DPS * dt, cx, cy, HitKind.HAZ)
        }
        if (w.ticks % 3L == 0L) w.fx.spark(cx + aimX * hazLen, cy + aimY * hazLen)
    }
}
