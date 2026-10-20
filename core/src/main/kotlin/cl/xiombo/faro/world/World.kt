package cl.xiombo.faro.world

import cl.xiombo.faro.data.Balance
import cl.xiombo.faro.data.Balance.TILE
import cl.xiombo.faro.data.EnemyDefs
import cl.xiombo.faro.data.Pal
import cl.xiombo.faro.input.InputState
import cl.xiombo.faro.util.dist
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Lógica determinista a 60 Hz (§11). F0: una sucesión de salas del Muelle Ahogado con
 * oleadas según el presupuesto de §6; la dificultad sube un piso cada 3 salas.
 */
class World(val seed: Long) {
    class PendingSpawn(val id: String, val x: Float, val y: Float, var t: Float)

    private val rng = Random(seed)
    val player = Player(40f, Room.HEIGHT / 2f - 2f)
    var room = Room(seed)
        private set
    var roomIndex = 0
        private set
    val enemies = ArrayList<Enemy>()
    val pending = ArrayList<PendingSpawn>()
    val orbs = ArrayList<Orb>()
    val fx = Fx()

    private var waves = ArrayDeque<List<String>>()
    private var waveDelay = 0f
    var roomClear = false
        private set
    private var intact = true

    var kills = 0
        private set
    var time = 0f
        private set
    var ticks = 0L
        private set
    private var hitstopFrames = 0
    var shake = 0f
        private set
    var banner = ""
        private set
    var bannerT = 0f
        private set
    var gameOver = false
        private set
    var gameOverT = 0f
        private set
    var restartRequested = false
        private set

    val floor get() = min(12, 1 + roomIndex / 3)

    init { enterRoom() }

    private fun enterRoom() {
        room = Room(seed * 31L + roomIndex)
        enemies.clear()
        pending.clear()
        orbs.clear()
        player.x = 40f
        player.y = Room.HEIGHT / 2f - 2f
        player.vx = 0f; player.vy = 0f
        player.snapshot()
        waves = planWaves()
        waveDelay = 0.9f
        roomClear = false
        intact = true
        showBanner("MUELLE AHOGADO  -  PISO $floor  -  SALA ${roomIndex + 1}")
    }

    /** Reparte el presupuesto de la sala (8 + 3·f) en 1–3 oleadas. */
    private fun planWaves(): ArrayDeque<List<String>> {
        val budget = Balance.budget(floor)
        val n = 1 + rng.nextInt(3)
        val pool = EnemyDefs.all.values.filter { it.biome == "muelle" }
        val cheapest = pool.minBy { it.cost }
        val out = ArrayDeque<List<String>>()
        for (i in 0 until n) {
            var wb = if (i == n - 1) budget - (budget / n) * (n - 1) else budget / n
            val wave = ArrayList<String>()
            while (wave.size < Balance.maxAlive(floor)) {
                val options = pool.filter { it.cost <= wb }
                if (options.isEmpty()) break
                val pick = options[rng.nextInt(options.size)]
                wave += pick.id
                wb -= pick.cost
            }
            if (wave.isEmpty()) wave += cheapest.id
            out.addLast(wave)
        }
        return out
    }

    private fun spawnWave(ids: List<String>) {
        for (id in ids) {
            val def = EnemyDefs[id]
            for (attempt in 0 until 40) {
                val tx = 3 + rng.nextInt(Room.COLS - 5)
                val ty = 2 + rng.nextInt(Room.ROWS - 4)
                val x = tx * TILE + TILE / 2f
                val y = ty * TILE + TILE / 2f
                if (room.boxHits(x, y, def.hw + 2f, def.hh + 2f)) continue
                if (dist(x, y, player.cx, player.cy) < 90f) continue
                if (pending.any { dist(it.x, it.y, x, y) < 20f }) continue
                pending += PendingSpawn(id, x, y, SPAWN_TELEGRAPH)
                break
            }
        }
    }

    fun update(dt: Float, inp: InputState) {
        time += dt
        bannerT -= dt
        if (hitstopFrames > 0) {
            hitstopFrames--
            return
        }
        ticks++
        shake = max(0f, shake - 24f * dt)
        player.snapshot()
        for (e in enemies) e.snapshot()

        if (gameOver) {
            gameOverT += dt
            fx.update(dt)
            if (gameOverT > 1.2f && inp.anyPressed) restartRequested = true
            inp.consume(dt)
            return
        }

        player.update(this, inp, dt)

        val it = pending.iterator()
        while (it.hasNext()) {
            val s = it.next()
            s.t -= dt
            if (s.t <= 0f) {
                enemies += createEnemy(s.id, s.x, s.y, floor)
                fx.burst(s.x, s.y, 8, Pal.WATER, 70f, 0.35f)
                it.remove()
            }
        }

        for (i in 0 until enemies.size) enemies[i].update(this, dt)
        separate()
        enemies.removeAll { it.dead }

        updateOrbs(dt)
        fx.update(dt)

        if (!roomClear && enemies.isEmpty() && pending.isEmpty()) {
            if (waves.isNotEmpty()) {
                waveDelay -= dt
                if (waveDelay <= 0f) {
                    spawnWave(waves.removeFirst())
                    waveDelay = 0.6f
                }
            } else {
                clearRoom()
            }
        }

        if (roomClear && player.x > Room.WIDTH - TILE - 1f) {
            roomIndex++
            enterRoom()
        }
        inp.consume(dt)
    }

    private fun clearRoom() {
        roomClear = true
        room.doorsOpen = true
        showBanner("SALA DESPEJADA")
        if (intact) {
            gainLuz(Balance.ROOM_INTACT_BONUS)
            fx.floater("SALA INTACTA +5", player.cx, player.cy + 22f, Pal.LAMP_HI)
        }
    }

    private fun separate() {
        for (i in 0 until enemies.size) for (j in i + 1 until enemies.size) {
            val a = enemies[i]
            val b = enemies[j]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val minD = a.radius + b.radius - 2f
            val d2 = dx * dx + dy * dy
            if (d2 < minD * minD && d2 > 0.0001f) {
                val d = sqrt(d2)
                val push = (minD - d) / 2f
                val nx = dx / d
                val ny = dy / d
                room.move(a, -nx * push, -ny * push)
                room.move(b, nx * push, ny * push)
            }
        }
    }

    private fun updateOrbs(dt: Float) {
        val it = orbs.iterator()
        while (it.hasNext()) {
            val o = it.next()
            o.age += dt
            val dx = player.cx - o.x
            val dy = player.cy - o.y
            val d = max(0.001f, sqrt(dx * dx + dy * dy))
            if (o.age > 0.35f && (d < 64f || roomClear)) {
                val pull = if (roomClear) 320f else 240f
                o.vx = dx / d * pull
                o.vy = dy / d * pull
            } else {
                val k = (1f - 5f * dt).coerceAtLeast(0f)
                o.vx *= k; o.vy *= k
            }
            o.x += o.vx * dt
            o.y += o.vy * dt
            if (d < 8f && o.age > 0.2f) {
                gainLuz(o.value)
                it.remove()
            }
        }
    }

    fun onEnemyKilled(e: Enemy) {
        kills++
        var n = e.def.luzMin + rng.nextInt(e.def.luzMax - e.def.luzMin + 1)
        if (player.luz < Balance.LOW_LUZ) n = ceil(n * 1.5f).toInt()
        repeat(n) {
            orbs += Orb(e.x, e.y, rng.nextFloat() * 140f - 70f, rng.nextFloat() * 140f - 70f, 1f)
        }
        val color = when (e) {
            is Ahogado -> Pal.AHOGADO_HI
            is Medusa -> Pal.MEDUSA
            else -> Pal.SHELL
        }
        fx.burst(e.x, e.y, 12, color, 90f, 0.45f)
        addShake(1.5f)
    }

    /** Toda curación es Luz (§4). */
    fun gainLuz(v: Float) { player.luz = min(player.luzMax, player.luz + v) }

    /** Daño al jugador: resta Luz, 0,8 s de invulnerabilidad y 3 frames de hit-stop. */
    fun hurtPlayer(amount: Float, sx: Float, sy: Float): Boolean {
        val p = player
        if (gameOver || p.invulnerable) return false
        p.luz -= amount
        intact = false
        p.hurtIframes = Balance.HIT_IFRAMES
        val dx = p.cx - sx
        val dy = p.cy - sy
        val d = max(0.001f, sqrt(dx * dx + dy * dy))
        p.kbX = dx / d * 170f
        p.kbY = dy / d * 170f
        hitstop(Balance.HITSTOP_HURT)
        addShake(4f)
        fx.burst(p.cx, p.cy, 8, Pal.LAMP, 90f, 0.35f)
        fx.floater("-${amount.roundToInt()}", p.cx, p.cy + 14f, Pal.CORAL)
        if (p.luz <= 0f) {
            p.luz = 0f
            gameOver = true
            fx.burst(p.cx, p.cy, 30, Pal.LAMP_DIM, 120f, 0.9f)
            showBanner("LA LUZ SE APAGO", 99f)
        }
        return true
    }

    fun hitstop(frames: Int) { hitstopFrames = max(hitstopFrames, frames) }
    fun addShake(amount: Float) { shake = max(shake, amount) }

    private fun showBanner(text: String, seconds: Float = 2.2f) {
        banner = text
        bannerT = seconds
    }

    companion object {
        const val SPAWN_TELEGRAPH = 0.7f
    }
}
