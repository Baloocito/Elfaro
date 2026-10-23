package cl.xiombo.faro.world

import cl.xiombo.faro.data.Balance
import cl.xiombo.faro.data.Pal
import cl.xiombo.faro.data.Weapon
import cl.xiombo.faro.input.InputState
import cl.xiombo.faro.util.approach
import cl.xiombo.faro.util.len
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Isla, la farera. Caja de colisión 8 × 6 a los pies (x, y); caja de daño 10 × 16 centrada en (cx, cy).
 */
class Player(x: Float, y: Float) : Body(x, y, 4f, 3f) {
    /** Isla no choca con el agua: cae en ella (con coyote de 0,1 s). */
    override val blockedByWater: Boolean get() = false

    var luzMax = Balance.LUZ_MAX
    var luz = Balance.LUZ_MAX
    var weapon = Weapon.FAROL
    /** El Arpón en vuelo, clavado o volviendo (null = en la mano). */
    var harpoon: Harpoon? = null
        private set

    var vx = 0f
    var vy = 0f
    var kbX = 0f
    var kbY = 0f
    var aimX = 1f
    var aimY = 0f
    private var lastMoveX = 1f
    private var lastMoveY = 0f

    // Destello (con cargas)
    var dashT = 0f
    var dashCharges = 1
    var dashRecharge = 0f
    var dashIframes = 0f
    private var dashDx = 0f
    private var dashDy = 0f
    private var dashTick = 0

    var hurtIframes = 0f

    // Pozos
    private var coyote = 0f
    var fallT = 0f
        private set
    private var safeX = x
    private var safeY = y

    // Farol: atkFrame cuenta frames de lógica dentro del golpe (-1 = sin atacar)
    var atkFrame = -1
    var comboIdx = 0
    private var comboTimer = 0f
    var atkDx = 1f
    var atkDy = 0f
    var swingIsThird = false
    private val swingHits = HashSet<Enemy>()

    // Velar: mantener baja la luz. Con exvotos, al soltar sale un Fogonazo.
    var veiling = false
        private set
    var veilT = 0f
        private set
    /** 0 = luz normal, 1 = velado del todo (se suaviza para que la luz no salte). */
    var veilK = 0f
        private set
    var flashCd = 0f
        private set
    val flashCharged get() = veiling && veilT >= Balance.VEIL_CHARGE && flashCd <= 0f

    // Haz
    var hazT = 0f
    var hazLen = 0f
    private var hazTick = 0

    val cx get() = x
    val cy get() = y + 5f
    val invulnerable get() = dashIframes > 0f || hurtIframes > 0f || fallT > 0f
    val attacking get() = atkFrame >= 0
    val falling get() = fallT > 0f
    val swingActive get() = atkFrame >= weapon.startup && atkFrame < weapon.startup + weapon.active
    val swingSmear get() = atkFrame >= weapon.startup + weapon.active && atkFrame < weapon.startup + weapon.active + 2
    /** Alcance del arma actual (para golpear y para dibujar el golpe). */
    var weaponLevel = 0
    val swingRange: Float
        get() = when (weapon) {
            Weapon.FAROL -> Balance.FAROL_RANGE + if (swingIsThird) 4f else 0f
            Weapon.ANCLA -> 34f
            Weapon.CAMPANA -> 48f + 8f * weaponLevel
            Weapon.ARPON -> 0f
        }
    val swingHalfAngleDeg get() = if (weapon == Weapon.ANCLA) 90f else Balance.FAROL_HALF_ANGLE_DEG

    fun hurtOverlaps(ox: Float, oy: Float, r: Float): Boolean {
        if (falling) return false
        val nx = ox.coerceIn(cx - 5f, cx + 5f)
        val ny = oy.coerceIn(cy - 8f, cy + 8f)
        val dx = ox - nx
        val dy = oy - ny
        return dx * dx + dy * dy <= r * r
    }

    /** Coloca a Isla al entrar en una sala. */
    fun placeAt(px: Float, py: Float) {
        x = px; y = py
        safeX = px; safeY = py
        vx = 0f; vy = 0f; kbX = 0f; kbY = 0f
        dashT = 0f; hazT = 0f; atkFrame = -1; fallT = 0f; coyote = 0f
        harpoon = null
        veiling = false; veilT = 0f
        snapshot()
    }

    fun update(w: World, inp: InputState, dt: Float) {
        swingStarted = false
        val st = w.stats
        dashIframes -= dt
        hurtIframes -= dt
        kbX = approach(kbX, 0f, 900f * dt)
        kbY = approach(kbY, 0f, 900f * dt)
        weaponLevel = st.weaponLevel
        updateHarpoon(w, dt)
        if (dashCharges < st.dashCharges) {
            dashRecharge -= dt
            if (dashRecharge <= 0f) {
                dashCharges++
                dashRecharge = Balance.DASH_COOLDOWN * st.dashRechargeMul
            }
        }

        if (fallT > 0f) {
            fallT -= dt
            if (fallT <= 0f) {
                placeAt(safeX, safeY)
                w.onPlayerFell()
            }
            return
        }

        updateVeil(w, inp, dt)

        val moving = inp.mx != 0f || inp.my != 0f
        if (moving) {
            val l = len(inp.mx, inp.my)
            lastMoveX = inp.mx / l
            lastMoveY = inp.my / l
        }
        updateAim(w, inp)

        // Destello: cancela la recuperación del Farol y corta el Haz. Cruza el agua sin caer.
        if (inp.dashBuf > 0f && dashCharges > 0 && dashT <= 0f) {
            inp.dashBuf = 0f
            if (moving) { dashDx = lastMoveX; dashDy = lastMoveY } else { dashDx = aimX; dashDy = aimY }
            if (st.statue) w.placeStatue(x, y)
            if (st.saltDash) w.addSaltZone(x, y)
            dashT = Balance.DASH_TIME
            dashIframes = Balance.DASH_IFRAMES + st.dashIframesBonus
            dashHits.clear()
            if (dashCharges == st.dashCharges) dashRecharge = Balance.DASH_COOLDOWN * st.dashRechargeMul + Balance.DASH_TIME
            dashCharges--
            dashTick = 0
            atkFrame = -1
            hazT = 0f
            w.fx.burst(x, y, 5, Pal.CREAM, 60f, 0.25f)
        }
        if (dashT > 0f) {
            dashT -= dt
            if (inp.hazBuf > 0f) inp.hazBuf = Balance.INPUT_BUFFER   // se guarda hasta terminar el Destello
            w.room.move(this, dashDx * Balance.DASH_SPEED * dt, dashDy * Balance.DASH_SPEED * dt)
            if (dashTick++ % 3 == 0) w.fx.ghost(x, y)
            if (st.saltDash && dashTick % 4 == 0) w.addSaltZone(x, y)
            // Marea viva: el Destello hace 12 de daño a quien atraviesa.
            if (st.tideDash) for (e in w.enemies) {
                if (!e.targetable || e in dashHits) continue
                if ((e.x - x) * (e.x - x) + (e.y - y) * (e.y - y) < (e.radius + 6f) * (e.radius + 6f)) {
                    dashHits += e
                    e.takeHit(w, 12f, x, y, HitKind.MELEE)
                }
            }
            vx = dashDx * Balance.SPEED
            vy = dashDy * Balance.SPEED
            coyote = 0f
            return
        }

        // Haz: cuesta Luz; no se puede usar si deja la Luz en 0. También cancela la recuperación del Farol.
        val inRecovery = atkFrame >= weapon.startup + weapon.active
        if (veiling) inp.hazBuf = 0f
        if (inp.hazBuf > 0f && hazT <= 0f && (!attacking || inRecovery)) {
            inp.hazBuf = 0f
            atkFrame = -1
            val cost = st.hazCost
            if (luz > cost) {
                luz -= cost
                hazT = Balance.HAZ_TIME
                w.hazUsedInRoom = true
                hazTick = 0
                w.fx.floater("-${cost.toInt()}", cx, cy + 14f, Pal.LAMP)
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
            if (veiling) inp.attackBuf = 0f
            if (inp.attackBuf > 0f && canAttack()) {
                inp.attackBuf = 0f
                startSwing()
            }
        }
        if (attacking) updateSwing(w, inp)

        // Movimiento con aceleración y frenado
        var max = Balance.SPEED * st.speedBonus * heroSpeed
        if (attacking) max *= weapon.moveFactor else if (hazT <= 0f) max *= st.windSpeed
        if (hazT > 0f) max *= Balance.HAZ_MOVE_FACTOR
        if (veiling) max *= st.veilSpeed
        if (w.playerInPuddle()) max *= 0.6f
        val rate = (if (moving) Balance.ACCEL else Balance.DECEL) * dt
        vx = approach(vx, inp.mx * max, rate)
        vy = approach(vy, inp.my * max, rate)
        w.room.move(this, (vx + kbX) * dt, (vy + kbY) * dt)

        updatePits(w, dt)
    }

    /**
     * Velar (mantener): la luz baja a 56 px, te mueves al 70 % y no puedes atacar. Los enemigos
     * lejanos te pierden (ver World.updateVeilTracking). Al soltar tras 0,5 s, si tienes un exvoto
     * de Fogonazo y no está en recarga, sale el Fogonazo.
     */
    private fun updateVeil(w: World, inp: InputState, dt: Float) {
        flashCd -= dt
        val want = inp.veil && !attacking && hazT <= 0f && dashT <= 0f
        if (want) {
            veilT += dt
        } else if (veiling) {
            if (veilT >= Balance.VEIL_CHARGE && flashCd <= 0f && w.stats.flashOn) {
                flashCd = Balance.FLASH_COOLDOWN
                w.veilFlash()
            }
            veilT = 0f
        }
        veiling = want
        veilK = approach(veilK, if (veiling) 1f else 0f, dt * 5f)
    }

    private fun updatePits(w: World, dt: Float) {
        val room = w.room
        if (room.isWaterAt(x, y)) {
            coyote += dt
            if (coyote > COYOTE) {
                fallT = FALL_TIME
                atkFrame = -1
                hazT = 0f
                w.fx.burst(x, y, 10, Pal.FOAM, 70f, 0.4f)
            }
        } else {
            coyote = 0f
            // Último punto seguro: lejos del agua y de los vitrales (que pueden romperse después).
            if (!room.boxTouchesWater(x, y, hw + 4f, hh + 4f) && !room.boxTouchesGlass(x, y, hw + 4f, hh + 4f)) { safeX = x; safeY = y }
        }
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

    /** El Arpón solo se lanza si está en la mano. */
    private fun canAttack() = weapon != Weapon.ARPON || harpoon == null

    /** Empezó un golpe en este tick (lo graba World para el clon de papel del Cartógrafo). */
    var swingStarted = false
    private var hitCount = 0
    private var bellowsDone = false
    private val dashHits = HashSet<Enemy>()
    /** El Campanero renegado es un poco más lento. */
    var heroSpeed = 1f

    private fun startSwing() {
        swingStarted = true
        bellowsDone = false
        atkFrame = 0
        atkDx = aimX
        atkDy = aimY
        swingIsThird = weapon == Weapon.FAROL && comboIdx == 2
        swingHits.clear()
    }

    private fun updateSwing(w: World, inp: InputState) {
        if (atkFrame < weapon.startup && (weapon == Weapon.FAROL || weapon == Weapon.ANCLA)) {
            w.room.move(this, atkDx * 0.6f, atkDy * 0.6f)
        }
        if (swingActive) {
            val first = atkFrame == weapon.startup
            when (weapon) {
                Weapon.FAROL, Weapon.ANCLA -> hitCone(w)
                Weapon.CAMPANA -> if (first) bellPulse(w)
                Weapon.ARPON -> if (first) throwHarpoon(w)
            }
        }
        atkFrame++
        if (atkFrame >= weapon.total) {
            atkFrame = -1
            comboIdx = if (weapon == Weapon.FAROL) (comboIdx + 1) % 3 else 0
            comboTimer = Balance.COMBO_WINDOW
            if (inp.attackBuf > 0f && canAttack()) {
                inp.attackBuf = 0f
                startSwing()
            }
        }
    }

    /** Daño del arma contra un enemigo concreto, con todos los modificadores de exvotos. */
    private fun weaponDamage(w: World, e: Enemy, base: Float): Float {
        val st = w.stats
        var d = base
        if (!e.hitByPlayer) d += st.firstHitBonus
        d += st.flatDmg
        if (e.slowed) d *= st.slowedBonus
        if (e.isElite || e.isBoss) d *= st.eliteBonus
        if (e.isBoss) d *= st.bossMul
        if (e.burnT > 0f) d *= st.burnedBonus
        if (e.hp < e.maxHp * 0.25f) d *= st.lowHpBonus
        return d * st.dmgMul
    }

    private fun baseDamage(w: World): Float = when (weapon) {
        Weapon.FAROL -> Balance.FAROL_DMG * w.stats.weaponMul
        Weapon.ARPON -> 14f
        Weapon.ANCLA -> 22f
        Weapon.CAMPANA -> 10f
    }

    /** Efectos comunes al golpear: conjuntos de exvotos, remate, hit-stop y chispas. */
    private fun onHit(w: World, e: Enemy) {
        val st = w.stats
        e.hitByPlayer = true
        if (swingIsThird) e.burn(st.burnTime)
        if (swingIsThird && st.thirdHitSlow) e.slow(0.7f, 1.5f)
        if (st.setSal) e.slow(0.8f, 1f)
        if (st.coarseSalt) e.slow(0.9f, 1f)
        // Campanilla: cada 5.º golpe aturde.
        if (st.bellEvery5 && ++hitCount % 5 == 0) { e.stun(0.5f); w.fx.ring(e.x, e.y, e.radius + 6f, Pal.SHELL) }
        // Fuelle: el 3.er golpe del combo estalla en fuego (una vez por golpe).
        if (st.bellows && swingIsThird && !bellowsDone) { bellowsDone = true; w.fireBlast(e.x, e.y) }
        // Conjunto Hueso: remata a lo que queda por debajo del 15 %.
        if (st.setHueso && !e.isBoss && !e.killed && e.hp < e.maxHp * 0.15f) {
            e.takeHit(w, e.hp + 1f, cx, cy, HitKind.SLAM)
            w.fx.floater("REMATE", e.x, e.y + 14f, Pal.SHELL)
        }
        w.hitstop(if (weapon == Weapon.ANCLA) 4 else Balance.HITSTOP_HIT)
        w.fx.burst(e.x, e.y, 4, Pal.LAMP_HI, 80f, 0.2f)
    }

    /** Farol (cono de 60°) y Ancla (arco de 180°). */
    private fun hitCone(w: World) {
        val st = w.stats
        val cosHalf = cos(Math.toRadians(swingHalfAngleDeg.toDouble())).toFloat()
        val range = swingRange
        w.igniteAt(cx + atkDx * range * 0.6f, cy + atkDy * range * 0.6f, range * 0.6f)
        for (e in w.enemies) {
            if (!e.targetable || e in swingHits) continue
            val dx = e.x - cx
            val dy = e.y - cy
            val d = sqrt(dx * dx + dy * dy)
            if (d - e.radius > range) continue
            // Tolerancia según el tamaño del enemigo: favorece al jugador.
            if (d > 6f && (dx * atkDx + dy * atkDy) / d < cosHalf - e.radius / d) continue
            swingHits += e
            if (e.takeHit(w, weaponDamage(w, e, baseDamage(w)), cx, cy, HitKind.MELEE)) {
                if (weapon == Weapon.ANCLA) {
                    // Empuja ~40 px (+10 por mejora) y aturde si choca contra una pared.
                    val push = 240f + 28f * weaponLevel
                    val l = if (d > 0.5f) d else 1f
                    e.knock(dx / l, dy / l, push * st.knockMul, 0.8f + 0.2f * weaponLevel)
                    w.addShake(2f)
                } else {
                    e.knock(atkDx, atkDy, (if (swingIsThird) 150f else 80f) * st.knockMul)
                }
                onHit(w, e)
            }
        }
    }

    /** Campana: pulso alrededor de Isla que daña, aturde y abre paredes agrietadas. */
    private fun bellPulse(w: World) {
        val r = swingRange
        w.revealAt(cx, cy, r)
        w.fx.ring(cx, cy, r, Pal.SHELL)
        w.fx.ring(cx, cy, r * 0.6f, Pal.CREAM)
        for (e in w.enemies) {
            if (!e.targetable) continue
            val d = sqrt((e.x - cx) * (e.x - cx) + (e.y - cy) * (e.y - cy))
            if (d - e.radius > r) continue
            if (e.takeHit(w, weaponDamage(w, e, baseDamage(w)), cx, cy, HitKind.BLAST)) {
                e.stun(0.6f)
                onHit(w, e)
            }
        }
        w.addShake(1f)
        w.bellAt(cx, cy, r)
        w.igniteAt(cx, cy, r)
    }

    private fun throwHarpoon(w: World) {
        harpoon = Harpoon(cx + atkDx * 6f, cy + atkDy * 6f, atkDx, atkDy, 2 + weaponLevel, 1.5f - 0.3f * weaponLevel)
        w.fx.burst(cx + atkDx * 8f, cy + atkDy * 8f, 3, Pal.CREAM, 60f, 0.15f)
    }

    /** Vuela a 220 px/s y perfora; al chocar o al agotar la perforación se clava. Vuelve solo o se recoge. */
    private fun updateHarpoon(w: World, dt: Float) {
        val h = harpoon ?: return
        h.px = h.x; h.py = h.y
        when (h.st) {
            Harpoon.St.FLYING -> {
                val step = HARPOON_SPEED * dt
                h.x += h.dx * step
                h.y += h.dy * step
                h.traveled += step
                if (w.room.isSolidAt(h.x, h.y) || h.traveled > HARPOON_RANGE) {
                    if (w.room.isSolidAt(h.x, h.y)) { w.igniteAt(h.x, h.y, 6f); h.x -= h.dx * step; h.y -= h.dy * step }
                    h.st = Harpoon.St.STUCK
                    w.fx.spark(h.x, h.y)
                    return
                }
                for (e in w.enemies) {
                    if (!e.targetable || e in h.hitSet) continue
                    if ((e.x - h.x) * (e.x - h.x) + (e.y - h.y) * (e.y - h.y) > (e.radius + 4f) * (e.radius + 4f)) continue
                    h.hitSet += e
                    if (e.takeHit(w, weaponDamage(w, e, 14f), h.x - h.dx * 10f, h.y - h.dy * 10f, HitKind.MELEE)) {
                        e.knock(h.dx, h.dy, 60f * w.stats.knockMul)
                        onHit(w, e)
                        h.hits++
                        if (h.hits >= h.pierce) { h.st = Harpoon.St.STUCK; return }
                    } else {
                        h.st = Harpoon.St.STUCK   // rebotó en un caparazón
                        return
                    }
                }
            }
            Harpoon.St.STUCK -> {
                h.stuckT += dt
                if ((cx - h.x) * (cx - h.x) + (cy - h.y) * (cy - h.y) < 16f * 16f) { harpoon = null; return }
                if (h.stuckT >= h.returnTime) { h.st = Harpoon.St.RETURNING; h.hitSet.clear() }
            }
            Harpoon.St.RETURNING -> {
                val dx = cx - h.x
                val dy = cy - h.y
                val d = sqrt(dx * dx + dy * dy)
                if (d < 10f) { harpoon = null; return }
                val step = 320f * dt
                h.x += dx / d * step
                h.y += dy / d * step
                for (e in w.enemies) {
                    if (!e.targetable || e in h.hitSet) continue
                    if ((e.x - h.x) * (e.x - h.x) + (e.y - h.y) * (e.y - h.y) > (e.radius + 4f) * (e.radius + 4f)) continue
                    h.hitSet += e
                    if (e.takeHit(w, weaponDamage(w, e, 7f), h.x, h.y, HitKind.LAMP)) onHit(w, e)
                }
            }
        }
    }

    private fun updateHaz(w: World, dt: Float) {
        hazLen = w.room.rayLength(cx, cy, aimX, aimY, Balance.HAZ_MAX_LEN)
        if (w.room.isCrack(w.room.lastRayTx, w.room.lastRayTy)) w.openSecret()
        w.igniteAt(cx + aimX * hazLen, cy + aimY * hazLen, 6f)
        val half = Balance.HAZ_WIDTH / 2f
        for (e in w.enemies) {
            if (!e.targetable) continue
            val ex = e.x - cx
            val ey = e.y - cy
            val t = (ex * aimX + ey * aimY).coerceIn(0f, hazLen)
            val px = ex - aimX * t
            val py = ey - aimY * t
            val r = half + e.radius
            if (px * px + py * py <= r * r) {
                // El Haz quema a las polillas de una vez.
                if (e is Polilla) { e.takeHit(w, e.hp + 1f, cx, cy, HitKind.HAZ); continue }
                var d = Balance.HAZ_DPS * dt
                if (e.isElite || e.isBoss) d *= w.stats.eliteBonus
                e.takeHit(w, d, cx, cy, HitKind.HAZ)
                if (w.stats.hazBurns) e.burn(1f)
            }
        }
        hazTick++
        if (hazTick % 3 == 0) w.fx.spark(cx + aimX * hazLen, cy + aimY * hazLen)
        if (w.stats.hazTrail && hazTick % 6 == 0) {
            var t = 12f
            while (t < hazLen) {
                w.addFire(cx + aimX * t, cy + aimY * t)
                t += 18f
            }
        }
    }

    /** Conjunto Marea: el Destello se recarga al matar. */
    fun refillDash(charges: Int) { dashCharges = max(dashCharges, charges) }

    companion object {
        const val HARPOON_SPEED = 220f
        const val HARPOON_RANGE = 190f
        const val COYOTE = 0.1f
        const val FALL_TIME = 0.45f
        const val FALL_DAMAGE = 10f
    }
}
