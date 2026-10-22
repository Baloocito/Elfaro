package cl.xiombo.faro.world

import cl.xiombo.faro.data.Balance
import cl.xiombo.faro.data.Balance.TILE
import cl.xiombo.faro.data.EnemyDefs
import cl.xiombo.faro.data.ExvotoDef
import cl.xiombo.faro.data.Exvotos
import cl.xiombo.faro.data.Meta
import cl.xiombo.faro.data.Pal
import cl.xiombo.faro.data.Rarity
import cl.xiombo.faro.data.RoomTemplate
import cl.xiombo.faro.data.RoomTemplates
import cl.xiombo.faro.data.RunSave
import cl.xiombo.faro.data.Tag
import cl.xiombo.faro.data.Weapon
import cl.xiombo.faro.input.InputState
import cl.xiombo.faro.util.dist
import com.badlogic.gdx.math.MathUtils
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Un mundo: el Santuario entre runs, o una run del bioma 1 (3 pisos del Muelle Ahogado y la Sirena
 * de Brea). Lógica determinista a 60 Hz (§11).
 */
class World private constructor(val seed: Long, val kind: Kind) {
    enum class Kind { SANCTUARY, RUN }
    enum class Mode { PLAYING, DEAD, VICTORY }
    /** Lo que RunScreen debe crear a continuación. */
    enum class Next { NONE, NEW_RUN, SANCTUARY }

    class PendingSpawn(val id: String, val x: Float, val y: Float, var t: Float, val affix: Affix?)

    /** Resumen de la muerte para la pantalla de resultados (pilar: "si mueres debe quedar claro por qué"). */
    class DeathInfo(
        val cause: String, val floor: Int, val roomType: String, val entryLuz: Int,
        val enemiesLeft: Int, val time: Float,
    )

    companion object {
        const val SPAWN_TELEGRAPH = 0.7f
        const val FADE = 0.3f
        const val MECHA_PRICE = 30
        const val MEJORA_PRICE = 20
        const val MAPA_PRICE = 10
        const val BOSS_PERLAS = 5
        const val FLOOR_PERLAS = 1
        const val SLOWMO_TIME = 1.2f
        const val FLASH_TIME = 0.25f

        fun sanctuary(): World = World(System.nanoTime(), Kind.SANCTUARY).apply { initSanctuary() }
        fun newRun(): World = World(System.nanoTime(), Kind.RUN).apply { initRun() }
        fun resume(d: RunSave.Data): World? = runCatching { World(d.seed, Kind.RUN).apply { initFromSave(d) } }.getOrNull()
    }

    private var rng = Random(seed)
    val player = Player(0f, 0f)

    var floorNum = 1
        private set
    private var floorSeed = 0L
    lateinit var map: FloorMap
        private set
    /** Celda ancla de la sala actual (en salas grandes, la de la izquierda o la de abajo). */
    lateinit var cell: Cell
        private set
    lateinit var room: Room
        private set
    private var enteredCell: Cell? = null
    private var enteredFrom: Dir? = null
    private var entryLuz = 0f

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

    var perlasRun = 0
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
    var next = Next.NONE
        private set
    var fadeT = 0f
        private set
    var hurtCount = 0
        private set
    var interactable: Pedestal? = null
        private set
    var targetX = 0f
        private set
    var targetY = 0f
        private set
    var death: DeathInfo? = null
        private set
    private var lastCause = "La oscuridad"

    val gameOver get() = mode == Mode.DEAD
    val floor get() = floorNum
    val inSanctuary get() = kind == Kind.SANCTUARY
    val isBigRoom get() = cell.partner != null

    // ---------------------------------------------------------------- arranque

    private fun initSanctuary() {
        floorNum = 0
        map = FloorGen.sanctuary()
        enterCell(map.start, null)
        player.weapon = Meta.weapon
        showBanner("EL SANTUARIO", 2.2f)
    }

    /** Run nueva con lo elegido en el Santuario. */
    private fun initRun() {
        player.weapon = Meta.weapon
        player.luzMax = Balance.LUZ_MAX + 10f * Meta.luzLevel
        player.luz = player.luzMax
        Meta.startExvoto?.let { id -> runCatching { Exvotos.get(id) }.getOrNull()?.let { owned += it } }
        recomputeStats()
        startFloor(1)
    }

    private fun initFromSave(d: RunSave.Data) {
        player.weapon = runCatching { Weapon.valueOf(d.weapon) }.getOrDefault(Weapon.FAROL)
        player.luzMax = d.luzMax
        weaponLevel = d.weaponLevel
        for (id in d.owned) runCatching { Exvotos.get(id) }.getOrNull()?.let { owned += it }
        offered += d.offered
        mapRevealed = d.mapRevealed
        kills = d.kills
        time = d.time
        perlasRun = d.perlasRun
        recomputeStats()
        floorNum = d.floorNum
        floorSeed = d.floorSeed
        rng = Random(d.seed xor d.floorSeed xor (d.cellX * 31L + d.cellY))
        map = FloorGen.generate(floorSeed, floorNum, hasBoss = floorNum == 3)
        for (c in map.all) c.templateIndex = Random(c.seed).nextInt(templatesFor(c).size)
        for (cs in d.cells) {
            val c = map[cs.gx, cs.gy] ?: continue
            c.visited = cs.visited; c.known = cs.known; c.cleared = cs.cleared; c.stocked = cs.stocked
            c.refugeUsed = cs.refugeUsed; c.iconShown = cs.iconShown
            c.openSides.clear(); cs.openSides.forEach { n -> runCatching { Dir.valueOf(n) }.getOrNull()?.let { c.openSides += it } }
            c.pedestals.clear()
            for (p in cs.peds) {
                val k = runCatching { PedKind.valueOf(p.kind) }.getOrNull() ?: continue
                val ex = p.exvoto?.let { id -> runCatching { Exvotos.get(id) }.getOrNull() }
                val wp = p.weapon?.let { runCatching { Weapon.valueOf(it) }.getOrNull() }
                c.pedestals += Pedestal(p.x, p.y, k, ex, p.price, wp).also { it.taken = p.taken }
            }
        }
        val c = map[d.cellX, d.cellY] ?: map.start
        val moved = d.moved?.let { runCatching { Dir.valueOf(it) }.getOrNull() }
        enterCell(c, moved)
        player.luz = d.luz.coerceIn(1f, player.luzMax)
        entryLuz = player.luz
        showBanner("CONTINÚA EL DESCENSO  ·  PISO $floorNum", 2.4f)
    }

    // ---------------------------------------------------------------- pisos y salas

    private fun templatesFor(c: Cell): List<RoomTemplate> {
        val partner = c.partner
        if (partner != null) {
            val anchor = if (c.anchor) c else partner
            val other = if (c.anchor) partner else c
            return RoomTemplates.of(if (other.gx != anchor.gx) "grande_h" else "grande_v")
        }
        return RoomTemplates.of(
            when (c.type) {
                CellType.START -> "inicio"
                CellType.SHOP -> "tienda"
                CellType.TREASURE -> "tesoro"
                CellType.REFUGE -> "refugio"
                CellType.SECRET -> "secreta"
                CellType.BOSS -> "jefe"
                CellType.ANTESALA -> "antesala"
                CellType.SANCTUARY -> "santuario"
                else -> "combate"
            }
        )
    }

    private fun startFloor(n: Int) {
        floorNum = n
        floorSeed = rng.nextLong()
        map = FloorGen.generate(floorSeed, n, hasBoss = n == 3)
        mapRevealed = false
        offered.clear()
        for (c in map.all) c.templateIndex = Random(c.seed).nextInt(templatesFor(c).size)
        // La Cartógrafa: las salas especiales se ven en el mapa desde el inicio.
        if (Meta.CARTOGRAFA in Meta.unlocks) {
            for (c in map.all) if (c.type != CellType.COMBAT && c.type != CellType.SECRET) { c.known = true; c.iconShown = true }
        }
        enterCell(map.start, null)
        showBanner("MUELLE AHOGADO  ·  PISO $n", 2.6f)
    }

    private fun enterCell(target: Cell, moved: Dir?) {
        val anchor = map.anchorOf(target)
        cell = anchor
        enteredCell = target
        enteredFrom = moved
        for ((c, _, _) in map.subsOf(anchor)) {
            c.visited = true
            c.known = true
            for (d in Dir.entries) {
                val n = map.neighbor(c, d) ?: continue
                if (n.type != CellType.SECRET || n.openSides.isNotEmpty()) n.known = true
            }
        }

        val tpl = templatesFor(anchor)[anchor.templateIndex % templatesFor(anchor).size]
        room = Room(tpl, map.doorsOf(anchor), map.crackOf(anchor))
        enemies.clear(); newEnemies.clear(); pending.clear(); orbs.clear()
        projectiles.clear(); puddles.clear(); fires.clear()
        statue = null
        boss = null
        brea = false
        room.doorsOpen = !(anchor.isCombat && !anchor.cleared)

        val firstVisit = !anchor.stocked
        stock(anchor, tpl)
        val entry = if (moved == null) {
            if (inSanctuary) floatArrayOf(room.width / 2f, 3.5f * TILE) else room.subCenter(0, 0)
        } else {
            val sub = map.subsOf(anchor).firstOrNull { it.first === target } ?: Triple(anchor, 0, 0)
            room.entryPoint(DoorSpec(moved.opposite, sub.second, sub.third))
        }
        player.placeAt(entry[0], entry[1])
        targetX = player.cx; targetY = player.cy
        entryLuz = player.luz

        waves = if (anchor.isCombat && !anchor.cleared && anchor.type != CellType.BOSS) planWaves(anchor) else ArrayDeque()
        waveDelay = 0.7f
        intact = true
        fadeT = FADE

        if (anchor.type == CellType.REFUGE) revealMap()
        if (anchor.type == CellType.BOSS && !anchor.cleared) {
            val b = createEnemy("sirena", room.width / 2f, room.height - 64f, 1) as Sirena
            enemies += b
            boss = b
            showBanner("LA SIRENA DE BREA", 2.6f)
        } else if (firstVisit) {
            when (anchor.type) {
                CellType.SHOP -> showBanner("LA TIENDA DE LA PESCADORA")
                CellType.TREASURE -> showBanner("UN EXVOTO OLVIDADO")
                CellType.REFUGE -> showBanner("REFUGIO DEL FARO")
                CellType.SECRET -> showBanner("SALA SECRETA")
                CellType.ELITE -> showBanner("ALGO MÁS GRANDE ACECHA")
                CellType.ANTESALA -> showBanner("ANTESALA  ·  ALGO CANTA AL OTRO LADO")
                else -> {}
            }
        }
        if (kind == Kind.RUN && mode == Mode.PLAYING) saveRun()
    }

    /** Coloca los objetos de la sala la primera vez que se visita. */
    private fun stock(c: Cell, tpl: RoomTemplate) {
        if (c.type == CellType.SANCTUARY) { stockSanctuary(); return }
        if (c.stocked) return
        c.stocked = true
        val pos = tpl.pedestals.map { room.tileCenter(it[0], it[1]) }
        when (c.type) {
            CellType.SHOP -> pos.forEachIndexed { i, p ->
                val ped = when (i) {
                    2 -> Pedestal(p[0], p[1], PedKind.MECHA, price = MECHA_PRICE)
                    3 -> if (weaponLevel < 3) Pedestal(p[0], p[1], PedKind.MEJORA, price = MEJORA_PRICE, weapon = player.weapon) else exvotoPed(p, true)
                    4 -> if (!mapRevealed) Pedestal(p[0], p[1], PedKind.MAPA, price = MAPA_PRICE) else exvotoPed(p, true)
                    else -> exvotoPed(p, true)
                }
                c.pedestals += ped
            }
            CellType.TREASURE -> pos.firstOrNull()?.let { c.pedestals += exvotoPed(it, false) }
            CellType.SECRET -> pos.firstOrNull()?.let { c.pedestals += exvotoPed(it, false, Rarity.RARO) }
            CellType.REFUGE -> pos.firstOrNull()?.let { c.pedestals += Pedestal(it[0], it[1], PedKind.REFUGIO) }
            CellType.ANTESALA -> pos.firstOrNull()?.let { c.pedestals += Pedestal(it[0], it[1], PedKind.VIGILIA) }
            else -> {}
        }
    }

    /**
     * El Santuario: pozo de descenso, las 4 armas, el exvoto inicial de la Pescadora y las mejoras de meta.
     * El orden de los pedestales sigue la plantilla "santuario" (de abajo hacia arriba).
     */
    private fun stockSanctuary() {
        val c = cell
        c.pedestals.clear()
        val pos = room.template.pedestals.map { room.tileCenter(it[0], it[1]) }
        if (pos.size < 12) return
        c.pedestals += Pedestal(pos[0][0], pos[0][1], PedKind.DESCENSO)
        Weapon.entries.forEachIndexed { i, w ->
            val p = pos[1 + i]
            val unlocked = Meta.isUnlocked(w)
            val price = if (w == Weapon.ANCLA && !unlocked) 60 else 0
            c.pedestals += Pedestal(p[0], p[1], PedKind.ARMA, price = price, weapon = w).also {
                it.selected = Meta.weapon == w
                if (!unlocked) it.lockedText = Meta.unlockRequirement(w)
            }
        }
        if (Meta.PESCADORA in Meta.unlocks) {
            val commons = Exvotos.all.filter { it.rarity == Rarity.COMUN }
            val r = Random(Meta.perlas * 7919L + Meta.unlocks.size)
            val pick = commons.shuffled(r).take(3).toMutableList()
            val chosen = Meta.startExvoto?.let { id -> commons.firstOrNull { it.id == id } }
            if (chosen != null && chosen !in pick) pick[0] = chosen
            pick.forEachIndexed { i, e ->
                val p = pos[5 + i]
                c.pedestals += Pedestal(p[0], p[1], PedKind.INICIAL, e).also { it.selected = Meta.startExvoto == e.id }
            }
        }
        val luzPrice = if (Meta.luzLevel == 0) 40 else 80
        val metas = listOf(
            Triple(Meta.PESCADORA, "La Pescadora", "Abre su puesto: elige 1 exvoto común para empezar cada run.") to 20,
            Triple("luz", "Mecha del Santuario", "+10 de Luz máxima inicial (nivel ${Meta.luzLevel}/2).") to luzPrice,
            Triple(Meta.CARTOGRAFA, "La Cartógrafa", "Ves las salas especiales en el mapa desde el inicio de cada piso.") to 90,
            Triple(Meta.CAMPANERO, "El Campanero renegado", "Segundo personaje jugable.") to 150,
        )
        metas.forEachIndexed { i, (m, price) ->
            val p = pos[8 + i]
            val have = when (m.first) {
                "luz" -> Meta.luzLevel >= 2
                else -> m.first in Meta.unlocks
            }
            c.pedestals += Pedestal(p[0], p[1], PedKind.META, price = if (have) 0 else price, metaId = m.first, metaTitle = m.second, metaDetail = m.third).also {
                it.taken = have
                if (m.first == Meta.CAMPANERO) it.lockedText = "rescátalo en la Catedral Sumergida"
            }
        }
    }

    private fun exvotoPed(p: FloatArray, priced: Boolean, min: Rarity = Rarity.COMUN): Pedestal {
        val exclude = owned.map { it.id }.toSet() + offered
        val e = Exvotos.roll(rng, exclude, min) ?: return Pedestal(p[0], p[1], PedKind.MECHA, price = if (priced) MECHA_PRICE else 0)
        offered += e.id
        return Pedestal(p[0], p[1], PedKind.EXVOTO, e, if (priced) e.rarity.price else 0)
    }

    /** Reparte el presupuesto de la sala (8 + 3·f) en 1–3 oleadas (§6). Salas grandes: ×1,6. */
    private fun planWaves(c: Cell): ArrayDeque<List<Pair<String, Affix?>>> {
        val budget = (Balance.budget(floorNum) * if (c.partner != null) 1.6f else 1f).roundToInt()
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
            val cap = Balance.maxAlive(floorNum) + if (c.partner != null) 2 else 0
            while (wave.size < cap) {
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
        flashT = max(0f, flashT - dt)
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
            // Cámara lenta del último golpe: el mundo sigue un instante a un 30 % de velocidad.
            if (mode == Mode.DEAD && modeT < SLOWMO_TIME) {
                val sdt = dt * 0.3f
                for (i in 0 until enemies.size) enemies[i].update(this, sdt)
                enemies.removeAll { it.dead }
                updateProjectiles(sdt)
                fx.update(sdt)
            } else {
                fx.update(dt)
            }
            if (modeT > 1.8f && inp.anyPressed) next = Next.SANCTUARY
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

        updateVeilTracking()
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
            .filter { (!it.taken || it.kind == PedKind.META) && dist(it.x, it.y, player.cx, player.y) < 20f }
            .minByOrNull { dist(it.x, it.y, player.cx, player.y) }
        val target = interactable
        // Recién llegado (al Santuario o a una run nueva) se ignora el USAR que venía de la pantalla
        // anterior: si no, salir de la muerte con E te tiraba directo al pozo.
        if (time < 0.6f) inp.useBuf = 0f
        if (inp.useBuf > 0f && target != null) {
            inp.useBuf = 0f
            interact(target)
        }

        if (mode == Mode.PLAYING) checkDoors()
        inp.consume(dt)
    }

    private fun checkDoors() {
        if (!room.doorsOpen || fadeT > FADE * 0.4f || player.falling || inSanctuary) return
        val d = when {
            player.x < 6f -> Dir.LEFT
            player.x > room.width - 6f -> Dir.RIGHT
            player.y < 5f -> Dir.DOWN
            player.y > room.height - 5f -> Dir.UP
            else -> null
        } ?: return
        val sub = room.subAt(player.x, player.y)
        val from = map.cellAtSub(cell, sub[0], sub[1])
        val n = map.neighbor(from, d) ?: return
        if (n === cell || n === cell.partner) return
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
        if (kind == Kind.RUN) saveRun()
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
            if (player.hurtOverlaps(p.x, p.y, p.r) && hurtPlayer(p.dmg, p.x, p.y, null, p.cause)) it.remove()
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

    private fun payPerlas(price: Int): Boolean {
        if (price <= 0) return true
        if (Meta.perlas < price) {
            fx.floater("FALTAN PERLAS", player.cx, player.cy + 20f, Pal.CORAL)
            return false
        }
        Meta.perlas -= price
        Meta.save()
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
                showBanner("${player.weapon.label.uppercase()} +$weaponLevel", 1.8f)
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
            PedKind.VIGILIA -> {
                // Cura más cuanto menos Luz te quede: así la pelea con el jefe empieza más pareja.
                p.taken = true
                val k = player.luz / player.luzMax
                val heal = when {
                    k < 0.5f -> 30f
                    k < 0.8f -> 20f
                    else -> 10f
                }
                gainLuz(heal)
                fx.burst(p.x, p.y + 8f, 24, Pal.LAMP_HI, 110f, 0.7f)
                fx.floater("+${heal.toInt()} LUZ", player.cx, player.cy + 20f, Pal.LAMP_HI)
                showBanner("EL FAROL DE VIGILIA TE ACOMPAÑA", 2f)
                saveRun()
            }
            PedKind.ESCALERA -> {
                perlasRun += FLOOR_PERLAS
                Meta.addPerlas(FLOOR_PERLAS)
                startFloor(floorNum + 1)
            }
            PedKind.FIN -> {
                mode = Mode.VICTORY
                modeT = 0f
                RunSave.clear()
            }
            PedKind.ARMA -> {
                val w = p.weapon ?: return
                if (!Meta.isUnlocked(w)) {
                    if (w == Weapon.ANCLA && p.price > 0) {
                        if (!payPerlas(p.price)) return
                        Meta.unlocks += Meta.ANCLA
                        showBanner("ANCLA DESBLOQUEADA", 2f)
                    } else {
                        fx.floater("BLOQUEADA", p.x, p.y + 16f, Pal.CORAL)
                        return
                    }
                }
                Meta.weapon = w
                Meta.save()
                player.weapon = w
                stockSanctuary()
                fx.burst(p.x, p.y + 6f, 12, Pal.LAMP_HI, 80f, 0.4f)
                showBanner("ARMA EQUIPADA: ${w.label.uppercase()}", 1.6f)
            }
            PedKind.INICIAL -> {
                val e = p.exvoto ?: return
                Meta.startExvoto = if (Meta.startExvoto == e.id) null else e.id
                Meta.save()
                stockSanctuary()
                showBanner(if (Meta.startExvoto != null) "EMPIEZAS CON ${e.name.uppercase()}" else "SIN EXVOTO INICIAL", 1.8f)
            }
            PedKind.META -> {
                if (p.taken) { fx.floater("YA ES TUYO", p.x, p.y + 16f, Pal.FOAM); return }
                if (p.lockedText != null) { fx.floater("BLOQUEADO", p.x, p.y + 16f, Pal.CORAL); return }
                if (!payPerlas(p.price)) return
                when (p.metaId) {
                    "luz" -> Meta.luzLevel = min(2, Meta.luzLevel + 1)
                    else -> p.metaId?.let { Meta.unlocks += it }
                }
                Meta.save()
                stockSanctuary()
                fx.burst(p.x, p.y + 6f, 16, Pal.SHELL, 90f, 0.5f)
                showBanner(p.metaTitle.uppercase(), 1.8f)
            }
            PedKind.DESCENSO -> next = Next.NEW_RUN
        }
    }

    private fun recomputeStats() {
        val hadCharges = stats.dashCharges
        stats = Stats(owned, weaponLevel, player.weapon)
        if (stats.dashCharges > hadCharges) player.dashCharges = stats.dashCharges
    }

    private fun revealMap() {
        mapRevealed = true
        for (c in map.all) if (c.type != CellType.SECRET) c.known = true
    }

    fun openSecret() {
        val crack = room.crack ?: return
        val from = map.cellAtSub(cell, crack.sx, crack.sy)
        val s = map.neighbor(from, crack.side) ?: return
        if (crack.side.opposite in s.openSides) return
        s.openSides += crack.side.opposite
        s.known = true
        room.openCrack()
        val g = room.gapTiles(crack).first()
        val c = room.tileCenter(g[0], g[1])
        fx.burst(c[0], c[1], 24, Pal.WALL_TOP, 120f, 0.6f)
        fx.floater("PASAJE SECRETO", c[0], c[1] + 10f, Pal.LAMP_HI)
        addShake(4f)
    }

    /** La Campana abre una pared agrietada si su pulso la alcanza. */
    fun bellAt(x: Float, y: Float, r: Float) {
        val crack = room.crack ?: return
        for (g in room.gapTiles(crack)) {
            val c = room.tileCenter(g[0], g[1])
            if (dist(x, y, c[0], c[1]) < r + 12f) { openSecret(); return }
        }
    }

    // ---------------------------------------------------------------- llamadas desde entidades

    fun shoot(x: Float, y: Float, vx: Float, vy: Float, dmg: Float, cause: String = "Notas · La Sirena de Brea") {
        projectiles += Projectile(x, y, vx, vy, 3f, dmg, 9f, Pal.CREAM, cause)
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
        val firstTime = "sirena" !in Meta.bosses
        Meta.bosses += "sirena"
        perlasRun += BOSS_PERLAS
        Meta.addPerlas(BOSS_PERLAS)
        showBanner(if (firstTime) "LA SIRENA SE HUNDE  ·  ARPÓN DESBLOQUEADO" else "LA SIRENA DE BREA SE HUNDE", 3.5f)
        val center = room.template.pedestals.firstOrNull()?.let { room.tileCenter(it[0], it[1]) }
            ?: floatArrayOf(room.width / 2f, room.height / 2f)
        cell.pedestals += exvotoPed(center, false, Rarity.EPICO)
        val end = room.nearestFloor(center[0], center[1] - 48f)
        cell.pedestals += Pedestal(end[0], end[1], PedKind.FIN)
    }

    fun gainLuz(v: Float) { player.luz = min(player.luzMax, player.luz + v) }

    /**
     * Daño al jugador: resta Luz, 0,8 s de invulnerabilidad y 3 frames de hit-stop.
     * [attack] nombra el ataque para la pantalla de muerte ("Embestida", "Pinza"…).
     */
    fun hurtPlayer(amount: Float, sx: Float, sy: Float, src: Enemy?, attack: String): Boolean {
        val p = player
        if (mode != Mode.PLAYING || p.invulnerable) return false
        var total = amount
        if (src?.affix == Affix.HAMBRIENTO) total += 3f
        lastCause = if (src != null) {
            val elite = src.affix?.let { " (élite ${it.label})" } ?: ""
            "$attack · ${src.def.name}$elite"
        } else attack
        p.luz -= total
        hurtCount++
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
        lastCause = "Caída al agua"
        hurtCount++
        player.hurtIframes = Balance.HIT_IFRAMES
        intact = false
        fx.floater("-${Player.FALL_DAMAGE.toInt()}", player.cx, player.cy + 14f, Pal.CORAL)
        addShake(2f)
        if (player.luz <= 0f) die()
    }

    /** Abandonar desde la pausa cuenta como una muerte (las Perlas ganadas se conservan). */
    fun abandon() {
        if (kind != Kind.RUN || mode != Mode.PLAYING) return
        lastCause = "Abandonaste el descenso"
        die()
    }

    private fun die() {
        player.luz = 0f
        mode = Mode.DEAD
        modeT = 0f
        death = DeathInfo(
            cause = lastCause,
            floor = floorNum,
            roomType = cell.type.label + if (cell.partner != null) " (grande)" else "",
            entryLuz = entryLuz.roundToInt(),
            enemiesLeft = enemies.count { !it.killed } + pending.size,
            time = time,
        )
        fx.burst(player.cx, player.cy, 30, Pal.LAMP_DIM, 120f, 0.9f)
        showBanner("LA LUZ SE APAGÓ", 99f)
        RunSave.clear()
    }

    /** Radio de luz de Isla (§3): 48 + 1,2·Luz, con los modificadores del bioma y los exvotos. */
    /**
     * Velar: tras 0,4 s velando, los enemigos (no jefes) a más de 64 px te pierden el rastro y van
     * a buscarte donde te vieron. Te reencuentran si se acercan mucho, si los golpeas o, al dejar
     * de velar, si quedan dentro de tu luz.
     */
    private fun updateVeilTracking() {
        val p = player
        val hiding = p.veiling && p.veilT >= Balance.VEIL_LOSE_TIME
        val light = playerLight()
        for (e in enemies) {
            if (e.isBoss || !e.targetable) { e.lost = false; continue }
            val d = dist(e.x, e.y, p.cx, p.cy)
            if (!e.lost) {
                if (hiding && d > Balance.VEIL_LOSE_DIST && e.state == EState.MOVE) {
                    e.lost = true
                    e.lostX = targetX; e.lostY = targetY
                    fx.floater("?", e.x, e.y + e.hh + 8f, Pal.SHELL)
                }
                continue
            }
            val found = if (p.veiling) d < Balance.VEIL_FIND_DIST else d < light * 0.85f
            if (found) {
                e.lost = false
                fx.floater("!", e.x, e.y + e.hh + 8f, Pal.CORAL)
            } else if (dist(e.x, e.y, e.lostX, e.lostY) < 12f) {
                // Busca: elige otro punto cercano.
                e.lostX = (e.x + MathUtils.random(-48f, 48f)).coerceIn(24f, room.width - 24f)
                e.lostY = (e.y + MathUtils.random(-48f, 48f)).coerceIn(24f, room.height - 24f)
            }
        }
    }

    /** Fogonazo al soltar Velar (exvotos Pabilo corto y Sal en los ojos). */
    fun veilFlash() {
        val p = player
        val r = stats.flashRadius
        flashT = FLASH_TIME
        fx.ring(p.cx, p.cy, r, Pal.LAMP_HI)
        fx.ring(p.cx, p.cy, r * 0.55f, Pal.CREAM)
        fx.burst(p.cx, p.cy, 14, Pal.LAMP_HI, 160f, 0.35f)
        addShake(2f)
        fx.floater("FOGONAZO", p.cx, p.cy + 18f, Pal.LAMP_HI)
        var hits = 0
        for (e in enemies) {
            if (!e.targetable) continue
            if (dist(e.x, e.y, p.cx, p.cy) - e.radius > r) continue
            val wasLost = e.lost
            if (stats.flashDmg > 0f) e.takeHit(this, stats.flashDmg, p.cx, p.cy, HitKind.LAMP) else e.lost = false
            if (stats.flashStun > 0f) e.stun(stats.flashStun)
            if (wasLost) fx.floater("!", e.x, e.y + e.hh + 8f, Pal.CORAL)
            hits++
        }
        if (hits > 0) hitstop(3)
    }

    /** Brillo del Fogonazo (s restantes). */
    var flashT = 0f
        private set

    fun playerLight(): Float {
        if (inSanctuary) return 210f
        var r = if (stats.eye) 168f else Balance.lightRadius(player.luz)
        val k = player.veilK
        if (k > 0f && r > Balance.VEIL_RADIUS) r += (Balance.VEIL_RADIUS - r) * k
        if (flashT > 0f) r += 90f * (flashT / FLASH_TIME)
        if (brea) r *= 0.7f
        for (e in enemies) {
            if (e.affix == Affix.BRILLANTE && !e.killed) {
                val d = dist(e.x, e.y, player.cx, player.cy)
                if (d < 80f) r *= 0.6f + 0.4f * (d / 80f)
            }
        }
        return r
    }

    // ---------------------------------------------------------------- guardado

    /** Guarda la run: se llama al entrar en cada sala, al usar el Farol de vigilia y al pausar la app. */
    fun saveRun() {
        if (kind != Kind.RUN || mode != Mode.PLAYING) return
        val entered = enteredCell ?: cell
        RunSave.save(
            RunSave.Data(
                seed = seed, floorNum = floorNum, floorSeed = floorSeed,
                cellX = entered.gx, cellY = entered.gy, moved = enteredFrom?.name,
                luz = player.luz, luzMax = player.luzMax,
                weapon = player.weapon.name, weaponLevel = weaponLevel,
                owned = owned.map { it.id }, offered = offered.toList(),
                mapRevealed = mapRevealed, kills = kills, time = time, perlasRun = perlasRun,
                cells = map.all.map { c ->
                    RunSave.CellState(
                        c.gx, c.gy, c.visited, c.known, c.cleared, c.stocked, c.refugeUsed, c.iconShown,
                        c.openSides.map { it.name },
                        c.pedestals.map { p ->
                            RunSave.PedState(p.kind.name, p.x, p.y, p.exvoto?.id, p.price, p.taken, p.weapon?.name)
                        },
                    )
                },
            )
        )
    }

    /** F3: carta + Luz llena · F4: ir al jefe · F5: exvoto (en el Santuario: +50 Perlas y todas las armas) · F6: 150 de daño · F8: Isla recibe 40 · F9: siguiente sala grande o antesala · F10: exvotos de Velar. */
    private fun debugCommand(cmd: Int) {
        when (cmd) {
            3 -> { if (!inSanctuary) { revealMap(); player.luz = player.luzMax } }
            4 -> if (!inSanctuary) {
                if (floorNum != 3) startFloor(3)
                val b = map.all.first { it.type == CellType.BOSS }
                enterCell(b, null)
            }
            5 -> if (inSanctuary) {
                Meta.perlas += 50
                Meta.bosses += "sirena"; Meta.bosses += "obispo"; Meta.unlocks += Meta.ANCLA
                Meta.save()
                stockSanctuary()
                showBanner("DEBUG: +50 PERLAS Y TODAS LAS ARMAS", 1.8f)
            } else {
                Exvotos.roll(rng, owned.map { it.id }.toSet())?.let { owned += it; recomputeStats(); showBanner(it.name.uppercase(), 1.5f) }
            }
            6 -> for (e in enemies) e.takeHit(this, 150f, e.x, e.y, HitKind.BLAST)
            10 -> if (!inSanctuary) {
                for (id in listOf("pabilo_corto", "sal_ojos", "marea_quieta")) if (owned.none { it.id == id }) owned += Exvotos.get(id)
                recomputeStats()
                showBanner("DEBUG: EXVOTOS DE VELAR", 1.5f)
            }
            9 -> if (!inSanctuary) {
                // Salta a la siguiente sala grande o antesala del piso (para probarlas).
                val list = map.all.filter { (it.partner != null && it.anchor) || it.type == CellType.ANTESALA }
                if (list.isEmpty()) showBanner("DEBUG: NO HAY SALAS GRANDES EN ESTE PISO", 1.5f)
                else enterCell(list[(list.indexOf(cell) + 1) % list.size], null)
            }
            8 -> if (!inSanctuary) hurtPlayer(40f, player.cx + 10f, player.cy, enemies.firstOrNull(), "Depuración (F8)")
        }
    }

    fun hitstop(frames: Int) { hitstopFrames = max(hitstopFrames, frames) }
    fun addShake(amount: Float) { shake = max(shake, amount) }

    private fun showBanner(text: String, seconds: Float = 2.2f) {
        banner = text
        bannerT = seconds
    }
}
