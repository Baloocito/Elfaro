package cl.xiombo.faro.world

import cl.xiombo.faro.data.Balance
import cl.xiombo.faro.data.Balance.TILE
import cl.xiombo.faro.data.EnemyDefs
import cl.xiombo.faro.data.ExvotoDef
import cl.xiombo.faro.data.Exvotos
import cl.xiombo.faro.data.Pal
import cl.xiombo.faro.data.Rarity
import cl.xiombo.faro.data.RoomTemplate
import cl.xiombo.faro.data.RoomTemplates
import cl.xiombo.faro.data.Tag
import cl.xiombo.faro.input.InputState
import cl.xiombo.faro.util.dist
import com.badlogic.gdx.Gdx
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Una run del bioma 1 (F1): 3 pisos generados del Muelle Ahogado, con tienda, tesoro, refugio,
 * élites, sala secreta y la Sirena de Brea en el piso 3. Lógica determinista a 60 Hz (§11).
 */
class World(val seed: Long) {
    enum class Mode { PLAYING, DEAD, VICTORY }

    class PendingSpawn(val id: String, val x: Float, val y: Float, var t: Float, val affix: Affix?)

    private val rng = Random(seed)
    val player = Player(0f, 0f)

    var floorNum = 1
        private set
    lateinit var map: FloorMap
        private set
    lateinit var cell: Cell
        private set
    lateinit var room: Room
        private set

    val enemies = ArrayList<Enemy>()
    private val newEnemies = ArrayList<Enemy>()
    val pending = ArrayList<PendingSpawn>()
    val orbs = ArrayList<Orb>()
    val projectiles = ArrayList<Projectile>()
    val puddles = ArrayList<Puddle>()
    val fires = ArrayList<FireZone>()
    var statue: Statue? = null
        private set
    val fx = Fx()

    // Progresión de la run
    val owned = ArrayList<ExvotoDef>()
    var weaponLevel = 0
        private set
    var stats = Stats(owned, 0)
        private set
    var mapRevealed = false
        private set
    private val offered = HashSet<String>()

    // Jefe
    var boss: Sirena? = null
        private set
    var brea = false
        private set

    // Lámpara gemela
    var twinX = 0f
        private set
    var twinY = 0f
        private set
    var twinShotT = 0f
        private set
    var twinShotX = 0f
        private set
    var twinShotY = 0f
        private set
    private var twinA = 0f
    private var twinCd = 0f
    private var twinCostT = 0f

    // Perlas (meta)
    var perlasRun = 0
        private set
    var perlasTotal = 0
        private set

    private var waves = ArrayDeque<List<Pair<String, Affix?>>>()
    private var waveDelay = 0f
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
    var mode = Mode.PLAYING
        private set
    var modeT = 0f
        private set
    var restartRequested = false
        private set
    var fadeT = 0f
        private set
    var interactable: Pedestal? = null
        private set
    var targetX = 0f
        private set
    var targetY = 0f
        private set

    val gameOver get() = mode == Mode.DEAD
    val floor get() = floorNum

    init {
        perlasTotal = runCatching { Gdx.app.getPreferences(PREFS).getInteger("perlas", 0) }.getOrDefault(0)
        startFloor(1)
    }

    // ---------------------------------------------------------------- pisos y salas

    private fun templatesFor(c: Cell): List<RoomTemplate> = RoomTemplates.of(
        when (c.type) {
            CellType.START -> "inicio"
            CellType.SHOP -> "tienda"
            CellType.TREASURE -> "tesoro"
            CellType.REFUGE -> "refugio"
            CellType.SECRET -> "secreta"
            CellType.BOSS -> "jefe"
            else -> "combate"
        }
    )

    private fun startFloor(n: Int) {
        floorNum = n
        map = FloorGen.generate(rng.nextLong(), n, hasBoss = n == 3)
        mapRevealed = false
        offered.clear()
        for (c in map.all) c.templateIndex = Random(c.seed).nextInt(templatesFor(c).size)
        enterCell(map.start, null)
        showBanner("MUELLE AHOGADO  ·  PISO $n", 2.6f)
    }

    private fun enterCell(c: Cell, moved: Dir?) {
        cell = c
        c.visited = true
        c.known = true
        for (d in Dir.entries) {
            val n = map.neighbor(c, d) ?: continue
            if (n.type != CellType.SECRET || n.openSides.isNotEmpty()) n.known = true
        }

        val tpl = templatesFor(c)[c.templateIndex]
        room = Room(tpl, map.doorsOf(c), map.crackOf(c))
        enemies.clear(); newEnemies.clear(); pending.clear(); orbs.clear()
        projectiles.clear(); puddles.clear(); fires.clear()
        statue = null
        boss = null
        brea = false
        room.doorsOpen = !(c.isCombat && !c.cleared)

        val firstVisit = !c.stocked
        stock(c, tpl)
        val entry = if (moved == null) floatArrayOf(room.width / 2f, room.height / 2f - 24f) else room.entryPoint(moved.opposite)
        player.placeAt(entry[0], entry[1])
        targetX = player.cx; targetY = player.cy

        waves = if (c.isCombat && !c.cleared && c.type != CellType.BOSS) planWaves(c) else ArrayDeque()
        waveDelay = 0.7f
        intact = true
        fadeT = FADE

        if (c.type == CellType.REFUGE) revealMap()
        if (c.type == CellType.BOSS && !c.cleared) {
            val b = createEnemy("sirena", room.width / 2f, room.height - 64f, 1) as Sirena
            enemies += b
            boss = b
            showBanner("LA SIRENA DE BREA", 2.6f)
        } else if (firstVisit) {
            when (c.type) {
                CellType.SHOP -> showBanner("LA TIENDA DE LA PESCADORA")
                CellType.TREASURE -> showBanner("UN EXVOTO OLVIDADO")
                CellType.REFUGE -> showBanner("REFUGIO DEL FARO")
                CellType.SECRET -> showBanner("SALA SECRETA")
                CellType.ELITE -> showBanner("ALGO MÁS GRANDE ACECHA")
                else -> {}
            }
        }
    }

    /** Coloca los objetos de la sala la primera vez que se visita. */
    private fun stock(c: Cell, tpl: RoomTemplate) {
        if (c.stocked) return
        c.stocked = true
        val pos = tpl.pedestals.map { room.tileCenter(it[0], it[1]) }
        when (c.type) {
            CellType.SHOP -> pos.forEachIndexed { i, p ->
                val ped = when (i) {
                    2 -> Pedestal(p[0], p[1], PedKind.MECHA, price = MECHA_PRICE)
                    3 -> if (weaponLevel < 3) Pedestal(p[0], p[1], PedKind.MEJORA, price = MEJORA_PRICE) else exvotoPed(p, true)
                    4 -> if (!mapRevealed) Pedestal(p[0], p[1], PedKind.MAPA, price = MAPA_PRICE) else exvotoPed(p, true)
                    else -> exvotoPed(p, true)
                }
                c.pedestals += ped
            }
            CellType.TREASURE -> pos.firstOrNull()?.let { c.pedestals += exvotoPed(it, false) }
            CellType.SECRET -> pos.firstOrNull()?.let { c.pedestals += exvotoPed(it, false, Rarity.RARO) }
            CellType.REFUGE -> pos.firstOrNull()?.let { c.pedestals += Pedestal(it[0], it[1], PedKind.REFUGIO) }
            else -> {}
        }
    }

    private fun exvotoPed(p: FloatArray, priced: Boolean, min: Rarity = Rarity.COMUN): Pedestal {
        val exclude = owned.map { it.id }.toSet() + offered
        val e = Exvotos.roll(rng, exclude, min) ?: return Pedestal(p[0], p[1], PedKind.MECHA, price = if (priced) MECHA_PRICE else 0)
        offered += e.id
        return Pedestal(p[0], p[1], PedKind.EXVOTO, e, if (priced) e.rarity.price else 0)
    }

    /** Reparte el presupuesto de la sala (8 + 3·f) en 1–3 oleadas (§6). Las salas élite abren con un élite. */
    private fun planWaves(c: Cell): ArrayDeque<List<Pair<String, Affix?>>> {
        val budget = Balance.budget(floorNum)
        val n = 1 + rng.nextInt(3)
        val pool = EnemyDefs.all.values.filter { it.biome == "muelle" }
        val cheapest = pool.minBy { it.cost }
        val out = ArrayDeque<List<Pair<String, Affix?>>>()
        for (i in 0 until n) {
            var wb = if (i == n - 1) budget - (budget / n) * (n - 1) else budget / n
            val wave = ArrayList<Pair<String, Affix?>>()
            if (i == 0 && c.type == CellType.ELITE) {
                val e = pool[rng.nextInt(pool.size)]
                wave += e.id to Affix.entries[rng.nextInt(Affix.entries.size)]
                wb -= e.cost * 2
            }
            while (wave.size < Balance.maxAlive(floorNum)) {
                val options = pool.filter { it.cost <= wb }
                if (options.isEmpty()) break
                val pick = options[rng.nextInt(options.size)]
                wave += pick.id to null
                wb -= pick.cost
            }
            if (wave.isEmpty()) wave += cheapest.id to null
            out.addLast(wave)
        }
        return out
    }

    private fun spawnWave(list: List<Pair<String, Affix?>>) {
        val points = room.template.spawns.map { room.tileCenter(it[0], it[1]) }.shuffled(rng)
        for ((id, affix) in list) {
            val def = EnemyDefs[id]
            var placed = false
            for (p in points) {
                if (dist(p[0], p[1], player.cx, player.cy) < 80f) continue
                if (pending.any { dist(it.x, it.y, p[0], p[1]) < 14f }) continue
                if (room.boxHits(p[0], p[1], def.hw, def.hh, true)) continue
                pending += PendingSpawn(id, p[0], p[1], SPAWN_TELEGRAPH, affix)
                placed = true
                break
            }
            if (placed) continue
            for (attempt in 0 until 60) {
                val x = (2 + rng.nextInt(room.cols - 4)) * TILE + TILE / 2f
                val y = (2 + rng.nextInt(room.rows - 4)) * TILE + TILE / 2f
                if (room.boxHits(x, y, def.hw + 2f, def.hh + 2f, true)) continue
                if (dist(x, y, player.cx, player.cy) < 80f) continue
                if (pending.any { dist(it.x, it.y, x, y) < 14f }) continue
                pending += PendingSpawn(id, x, y, SPAWN_TELEGRAPH, affix)
                break
            }
        }
    }

    // ---------------------------------------------------------------- bucle

    fun update(dt: Float, inp: InputState) {
        time += dt
        bannerT -= dt
        fadeT -= dt
        if (hitstopFrames > 0) {
            hitstopFrames--
            return
        }
        ticks++
        shake = max(0f, shake - 24f * dt)
        player.snapshot()
        for (e in enemies) e.snapshot()
        for (p in projectiles) { p.px = p.x; p.py = p.y }

        if (mode != Mode.PLAYING) {
            modeT += dt
            fx.update(dt)
            if (modeT > 1.5f && inp.anyPressed) restartRequested = true
            inp.consume(dt)
            return
        }

        if (inp.debugCmd != 0) debugCommand(inp.debugCmd)
        val s = statue
        if (s != null) { targetX = s.x; targetY = s.y } else { targetX = player.cx; targetY = player.cy }

        player.update(this, inp, dt)

        val it = pending.iterator()
        while (it.hasNext()) {
            val s2 = it.next()
            s2.t -= dt
            if (s2.t <= 0f) {
                enemies += createEnemy(s2.id, s2.x, s2.y, floorNum, s2.affix)
                fx.burst(s2.x, s2.y, 8, if (s2.affix != null) s2.affix.color else Pal.WATER, 70f, 0.35f)
                it.remove()
            }
        }

        for (i in 0 until enemies.size) enemies[i].update(this, dt)
        if (newEnemies.isNotEmpty()) { enemies += newEnemies; newEnemies.clear() }
        separate()
        enemies.removeAll { it.dead }

        updateProjectiles(dt)
        updateOrbs(dt)
        updateHazards(dt)
        updateTwin(dt)
        fx.update(dt)

        if (cell.isCombat && !cell.cleared && enemies.isEmpty() && pending.isEmpty()) {
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

        interactable = cell.pedestals
            .filter { !it.taken && dist(it.x, it.y, player.cx, player.y) < 20f }
            .minByOrNull { dist(it.x, it.y, player.cx, player.y) }
        val target = interactable
        if (inp.useBuf > 0f && target != null) {
            inp.useBuf = 0f
            interact(target)
        }

        if (mode == Mode.PLAYING) checkDoors()
        inp.consume(dt)
    }

    private fun checkDoors() {
        if (!room.doorsOpen || fadeT > FADE * 0.4f || player.falling) return
        val d = when {
            player.x < 6f -> Dir.LEFT
            player.x > room.width - 6f -> Dir.RIGHT
            player.y < 5f -> Dir.DOWN
            player.y > room.height - 5f -> Dir.UP
            else -> null
        } ?: return
        val n = map.neighbor(cell, d) ?: return
        enterCell(n, d)
    }

    private fun clearRoom() {
        cell.cleared = true
        room.doorsOpen = true
        if (cell.type == CellType.STAIRS) {
            val p = room.nearestFloor(room.width / 2f, room.height / 2f)
            cell.pedestals += Pedestal(p[0], p[1], PedKind.ESCALERA)
            showBanner("UNA ESCALERA BAJA AL ABISMO")
        } else if (cell.type != CellType.BOSS) {
            showBanner("SALA DESPEJADA")
        }
        if (intact && cell.type != CellType.BOSS) {
            gainLuz(Balance.ROOM_INTACT_BONUS)
            fx.floater("SALA INTACTA +5", player.cx, player.cy + 22f, Pal.LAMP_HI)
        }
    }

    private fun separate() {
        for (i in 0 until enemies.size) for (j in i + 1 until enemies.size) {
            val a = enemies[i]
            val b = enemies[j]
            if (a.isBoss || b.isBoss) continue
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

    private fun updateProjectiles(dt: Float) {
        val it = projectiles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.life -= dt
            if (p.life <= 0f || p.x < -20f || p.y < -20f || p.x > room.width + 20f || p.y > room.height + 20f) { it.remove(); continue }
            if (room.isSolidAt(p.x, p.y)) {
                fx.burst(p.x, p.y, 3, p.color, 40f, 0.2f)
                it.remove()
                continue
            }
            if (player.hurtOverlaps(p.x, p.y, p.r) && hurtPlayer(p.dmg, p.x, p.y, null)) it.remove()
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
            val done = cell.cleared || !cell.isCombat
            if (o.age > 0.35f && (d < 64f || done)) {
                val pull = if (done) 320f else 240f
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

    private fun updateHazards(dt: Float) {
        puddles.removeAll { p -> p.t -= dt; p.t <= 0f }
        fires.removeAll { f ->
            f.t -= dt
            for (e in enemies) if (e.targetable && dist(f.x, f.y, e.x, e.y) < f.r + e.radius) e.burn(1f)
            f.t <= 0f
        }
        val s = statue
        if (s != null) {
            s.t -= dt
            if (s.t <= 0f) statue = null
        }
    }

    private fun updateTwin(dt: Float) {
        if (!stats.twinLamp) return
        twinA += dt * 2.2f
        twinX = player.cx + cos(twinA) * 14f
        twinY = player.cy + 8f + sin(twinA) * 5f
        twinShotT -= dt
        twinCostT += dt
        if (twinCostT >= 5f) {
            twinCostT = 0f
            if (player.luz > 1f) player.luz -= 1f
        }
        twinCd -= dt
        if (twinCd <= 0f) {
            val t = enemies.filter { it.targetable && dist(twinX, twinY, it.x, it.y) < 110f }
                .minByOrNull { dist(twinX, twinY, it.x, it.y) }
            if (t != null) {
                t.takeHit(this, 6f * stats.weaponMul, twinX, twinY, HitKind.LAMP)
                twinShotT = 0.12f
                twinShotX = t.x
                twinShotY = t.y
                twinCd = 0.8f
            }
        }
    }

    // ---------------------------------------------------------------- interacción y economía

    /** Una compra nunca puede dejarte por debajo de 1 de Luz (§4). */
    private fun pay(price: Int): Boolean {
        if (price <= 0) return true
        if (player.luz - price < 1f) {
            fx.floater("FALTA LUZ", player.cx, player.cy + 20f, Pal.CORAL)
            return false
        }
        player.luz -= price
        fx.floater("-$price", player.cx, player.cy + 20f, Pal.LAMP)
        return true
    }

    private fun interact(p: Pedestal) {
        when (p.kind) {
            PedKind.EXVOTO -> {
                if (!pay(p.price)) return
                val e = p.exvoto!!
                owned += e
                p.taken = true
                val before = stats
                recomputeStats()
                fx.burst(p.x, p.y, 16, e.rarity.color, 90f, 0.5f)
                val completed = (e.tag != Tag.NINGUNA) && stats.tagCount(e.tag) == 3 && before.tagCount(e.tag) == 2
                if (completed) showBanner("CONJUNTO ${e.tag.label.uppercase()}: ${Exvotos.setBonus[e.tag]}", 3.5f)
                else showBanner(e.name.uppercase(), 1.8f)
            }
            PedKind.MECHA -> {
                if (!pay(p.price)) return
                p.taken = true
                player.luzMax = min(200f, player.luzMax + 10f)
                gainLuz(10f)
                showBanner("+10 DE LUZ MÁXIMA", 1.8f)
            }
            PedKind.MEJORA -> {
                if (!pay(p.price)) return
                p.taken = true
                weaponLevel = min(3, weaponLevel + 1)
                recomputeStats()
                showBanner("FAROL +$weaponLevel", 1.8f)
            }
            PedKind.MAPA -> {
                if (!pay(p.price)) return
                p.taken = true
                revealMap()
                showBanner("EL PISO SE REVELA", 1.8f)
            }
            PedKind.REFUGIO -> {
                p.taken = true
                cell.refugeUsed = true
                gainLuz(30f)
                fx.burst(p.x, p.y + 8f, 20, Pal.LAMP, 100f, 0.6f)
                fx.floater("+30 LUZ", player.cx, player.cy + 20f, Pal.LAMP_HI)
            }
            PedKind.ESCALERA -> startFloor(floorNum + 1)
            PedKind.FIN -> {
                mode = Mode.VICTORY
                modeT = 0f
            }
        }
    }

    private fun recomputeStats() {
        val hadCharges = stats.dashCharges
        stats = Stats(owned, weaponLevel)
        if (stats.dashCharges > hadCharges) player.dashCharges = stats.dashCharges
    }

    private fun revealMap() {
        mapRevealed = true
        for (c in map.all) if (c.type != CellType.SECRET) c.known = true
    }

    fun openSecret() {
        val side = room.crackSide ?: return
        val s = map.neighbor(cell, side) ?: return
        if (side.opposite in s.openSides) return
        s.openSides += side.opposite
        s.known = true
        room.openCrack()
        val g = room.gapTiles(side).first()
        val c = room.tileCenter(g[0], g[1])
        fx.burst(c[0], c[1], 24, Pal.WALL_TOP, 120f, 0.6f)
        fx.floater("PASAJE SECRETO", c[0], c[1] + 10f, Pal.LAMP_HI)
        addShake(4f)
    }

    // ---------------------------------------------------------------- llamadas desde entidades

    fun shoot(x: Float, y: Float, vx: Float, vy: Float, dmg: Float) {
        projectiles += Projectile(x, y, vx, vy, 3f, dmg, 9f, Pal.CREAM)
    }

    fun addPuddle(x: Float, y: Float) { if (puddles.size < 40) puddles += Puddle(x, y, 4f) }
    fun addFire(x: Float, y: Float) { if (fires.size < 90) fires += FireZone(x, y, 3f) }
    fun placeStatue(x: Float, y: Float) { statue = Statue(x, y, 1.5f) }

    fun playerInPuddle() = puddles.any { dist(it.x, it.y, player.x, player.y) < it.r }

    fun splitTwin(e: Enemy) {
        val t = createEnemy(e.def.id, e.x + 10f, e.y, e.floor, null)
        t.hp = min(t.maxHp, e.hp)
        newEnemies += t
        fx.burst(e.x, e.y, 12, Pal.GEMELO, 90f, 0.4f)
        fx.floater("SE DIVIDE", e.x, e.y + 14f, Pal.GEMELO)
    }

    fun onBossPhase(n: Int) {
        projectiles.clear()
        addShake(5f)
        if (n >= 2) brea = true
        when (n) {
            2 -> showBanner("LA BREA INUNDA LA SALA", 2f)
            3 -> showBanner("LA SIRENA SE SUMERGE", 2f)
        }
    }

    fun onEnemyKilled(e: Enemy) {
        kills++
        if (e.isBoss) { onBossKilled(); return }
        var n = if (e.isElite) 6 else e.def.luzMin + rng.nextInt(e.def.luzMax - e.def.luzMin + 1)
        if (e.burnT > 0f) n += stats.burnedDropBonus
        if (player.luz < Balance.LOW_LUZ) n = ceil(n * 1.5f).toInt()
        repeat(n) {
            orbs += Orb(e.x, e.y, rng.nextFloat() * 140f - 70f, rng.nextFloat() * 140f - 70f, 1f)
        }
        if (stats.setFuego && e.burnT > 0f) {
            for (o in enemies) if (o !== e && !o.killed && dist(o.x, o.y, e.x, e.y) < 36f) o.burn(stats.burnTime)
        }
        if (stats.setMarea) player.refillDash(stats.dashCharges)
        if (stats.rosario && kills % 10 == 0 && player.luzMax < 200f) {
            player.luzMax += 2f
            player.luz += 2f
            fx.floater("+2 LUZ MÁX.", player.cx, player.cy + 24f, Pal.SHELL)
        }
        if (e.isElite && rng.nextFloat() < 0.3f) {
            val p = room.nearestFloor(e.x, e.y)
            cell.pedestals += exvotoPed(p, false)
            fx.floater("¡EXVOTO!", p[0], p[1] + 12f, Pal.LAMP_HI)
        }
        val color = when (e) {
            is Ahogado -> Pal.AHOGADO_HI
            is Medusa -> Pal.MEDUSA
            else -> Pal.SHELL
        }
        fx.burst(e.x, e.y, 12, color, 90f, 0.45f)
        addShake(1.5f)
    }

    private fun onBossKilled() {
        val b = boss ?: return
        projectiles.clear()
        brea = false
        player.luz = player.luzMax          // el jefe rellena la Luz al máximo
        fx.burst(b.x, b.y, 40, Pal.BREA, 160f, 1.0f)
        fx.burst(b.x, b.y, 30, Pal.LAMP_HI, 140f, 0.9f)
        fx.blast(b.x, b.y, 60f)
        addShake(6f)
        hitstop(10)
        showBanner("LA SIRENA DE BREA SE HUNDE", 3f)
        val center = room.template.pedestals.firstOrNull()?.let { room.tileCenter(it[0], it[1]) }
            ?: floatArrayOf(room.width / 2f, room.height / 2f)
        cell.pedestals += exvotoPed(center, false, Rarity.EPICO)
        val end = room.nearestFloor(center[0], center[1] - 48f)
        cell.pedestals += Pedestal(end[0], end[1], PedKind.FIN)
        perlasRun += BOSS_PERLAS
        perlasTotal += BOSS_PERLAS
        runCatching { Gdx.app.getPreferences(PREFS).putInteger("perlas", perlasTotal).flush() }
    }

    fun gainLuz(v: Float) { player.luz = min(player.luzMax, player.luz + v) }

    /** Daño al jugador: resta Luz, 0,8 s de invulnerabilidad y 3 frames de hit-stop. */
    fun hurtPlayer(amount: Float, sx: Float, sy: Float, src: Enemy?): Boolean {
        val p = player
        if (mode != Mode.PLAYING || p.invulnerable) return false
        var total = amount
        if (src?.affix == Affix.HAMBRIENTO) total += 3f
        p.luz -= total
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
        fx.floater("-${total.roundToInt()}", p.cx, p.cy + 14f, Pal.CORAL)
        if (p.luz <= 0f) die()
        return true
    }

    fun onPlayerFell() {
        player.luz -= Player.FALL_DAMAGE
        player.hurtIframes = Balance.HIT_IFRAMES
        intact = false
        fx.floater("-${Player.FALL_DAMAGE.toInt()}", player.cx, player.cy + 14f, Pal.CORAL)
        addShake(2f)
        if (player.luz <= 0f) die()
    }

    private fun die() {
        player.luz = 0f
        mode = Mode.DEAD
        modeT = 0f
        fx.burst(player.cx, player.cy, 30, Pal.LAMP_DIM, 120f, 0.9f)
        showBanner("LA LUZ SE APAGÓ", 99f)
    }

    /** Radio de luz de Isla (§3): 48 + 1,2·Luz, con los modificadores del bioma y los exvotos. */
    fun playerLight(): Float {
        var r = if (stats.eye) 168f else Balance.lightRadius(player.luz)
        if (brea) r *= 0.7f
        for (e in enemies) {
            if (e.affix == Affix.BRILLANTE && !e.killed) {
                val d = dist(e.x, e.y, player.cx, player.cy)
                if (d < 80f) r *= 0.6f + 0.4f * (d / 80f)
            }
        }
        return r
    }

    /** F3: carta + Luz llena · F4: ir al jefe · F5: exvoto al azar · F6: 150 de daño a todo. */
    private fun debugCommand(cmd: Int) {
        when (cmd) {
            3 -> { revealMap(); player.luz = player.luzMax }
            4 -> {
                if (floorNum != 3) startFloor(3)
                val b = map.all.first { it.type == CellType.BOSS }
                enterCell(b, null)
            }
            5 -> Exvotos.roll(rng, owned.map { it.id }.toSet())?.let { owned += it; recomputeStats(); showBanner(it.name.uppercase(), 1.5f) }
            6 -> for (e in enemies) e.takeHit(this, 150f, e.x, e.y, HitKind.BLAST)
        }
    }

    fun hitstop(frames: Int) { hitstopFrames = max(hitstopFrames, frames) }
    fun addShake(amount: Float) { shake = max(shake, amount) }

    private fun showBanner(text: String, seconds: Float = 2.2f) {
        banner = text
        bannerT = seconds
    }

    companion object {
        const val SPAWN_TELEGRAPH = 0.7f
        const val FADE = 0.3f
        const val MECHA_PRICE = 30
        const val MEJORA_PRICE = 20
        const val MAPA_PRICE = 10
        const val BOSS_PERLAS = 5
        const val PREFS = "faro-hundido"
    }
}
