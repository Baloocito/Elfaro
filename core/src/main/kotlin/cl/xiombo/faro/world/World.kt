package cl.xiombo.faro.world

import cl.xiombo.faro.data.Balance
import cl.xiombo.faro.data.Balance.TILE
import cl.xiombo.faro.data.Biome
import cl.xiombo.faro.data.Bottles
import cl.xiombo.faro.data.Mareas
import cl.xiombo.faro.data.RunLog
import cl.xiombo.faro.data.Seed
import cl.xiombo.faro.data.Story
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
 * Un mundo: el Santuario entre runs, o una run por los 4 biomas: Muelle Ahogado (1-3, la Sirena),
 * Catedral Sumergida (4-6, el Obispo), Archivo de los Náufragos (7-9, el Cartógrafo) y Raíz del Faro
 * (10-12, la Lámpara Madre). Lógica determinista a 60 Hz (§11).
 */
class World private constructor(val seed: Long, val kind: Kind, val test: Boolean = false) {
    enum class Kind { SANCTUARY, RUN }
    enum class Mode { PLAYING, DEAD, VICTORY, DEMO_END }
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
        const val BOSS_PERLAS = 5          // Sirena; el Obispo da 12 (§14)
        const val OBISPO_PERLAS = 12
        const val CARTOGRAFO_PERLAS = 20
        const val LAMPARA_PERLAS = 35
        const val FARERO_PERLAS = 50
        const val DEMO_TEASE = 3f
        const val CRIATURA_PERLAS = 20
        const val ATRIL_LIGHT = 48f
        const val MOLTEN_BURN = 4f         // Luz por segundo sobre vidrio fundido
        const val BEAT_PERIOD = 10f        // latido de la Raíz
        const val BEAT_BOOST_TIME = 2f
        const val SHELF_PERIOD = 6f
        const val HISTORY = 240            // ticks de historia de Isla para el clon (4 s)
        const val DIM_TIME = 3f            // onda del Campanero
        const val CANDLE_LIGHT = 64f
        const val SUMMON_TELEGRAPH = 0.8f   // círculos de sal antes de cada invocación
        const val CHAPTER_TIME = 4.5f
        const val MEMORY_TIME = 12f
        const val CLOSED_SHOP = "rescata a Amparo, la guardesa, en el Muelle Ahogado"
        const val FLOOR_PERLAS = 1
        const val SLOWMO_TIME = 1.2f
        const val FLASH_TIME = 0.25f

        fun sanctuary(): World = World(System.nanoTime(), Kind.SANCTUARY).apply { initSanctuary() }
        /** Run nueva; con [seed] (semilla compartida) los pisos salen iguales. */
        fun newRun(seed: Long = Seed.random()): World = World(seed, Kind.RUN).apply { initRun() }
        fun resume(d: RunSave.Data): World? = runCatching { World(d.seed, Kind.RUN).apply { initFromSave(d) } }.getOrNull()

        /**
         * Run de prueba (menú principal → Niveles de prueba): empieza en [floor], opcionalmente directo
         * al jefe o a la celda del rescate. No guarda la run (no pisa una run real) ni da Perlas.
         */
        fun testRun(floor: Int, toBoss: Boolean = false, toCell: Boolean = false, marea: Int = 0): World =
            World(Seed.random(), Kind.RUN, test = true).apply { testMarea = marea; initTest(floor, toBoss, toCell) }
    }

    private var rng = Random(seed)
    val player = Player(0f, 0f)

    /** Marea de esta run (§6) y si es la Marea baja. Fijas desde que empieza. */
    var marea = 0
        private set
    var baja = false
        private set
    val seedCode get() = Seed.encode(seed)
    private var hazUses = 0
    private val bossLuz = ArrayList<Int>()
    private var testMarea = 0
    private fun applyMarea() { Balance.marea = marea; Balance.baja = baja }

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
    var boss: Enemy? = null
        private set
    var brea = false
        private set

    // Catedral
    val candles = ArrayList<Candle>()
    val waves = ArrayList<Wave>()
    /** Onda del Campanero: segundos con la luz al 60 %. */
    var dimT = 0f
        private set

    // Archivo y Raíz
    val traps = ArrayList<Trap>()
    val lines = ArrayList<LineAttack>()
    /** Rastro de tinta del Cartógrafo en la fase 3: (x, y, s restantes). */
    val ink = ArrayList<FloatArray>()
    /** Atriles de la sala (x, y). Se apagan en la fase 3 del Cartógrafo. */
    val atriles = ArrayList<FloatArray>()
    var atrilesOn = true
        private set
    /** Sonidos pedidos en este paso; RunScreen los reproduce (la lógica no toca el audio). */
    val sounds = ArrayList<String>()
    fun sfx(name: String) { if (sounds.size < 16) sounds += name }
    /** Final C: se abrió el camino del Farero Antiguo en esta run. */
    var finalC = false
        private set
    /** Demo: segundos que se ve a la Sirena antes de fundir a negro. */
    var demoEndT = 0f
        private set
    /** Pelea a oscuras (Farero o criatura de Marea 10): tu radio no pasa de 80 px. */
    var darkFight = false
        private set
    /** Dónde quedó la lámpara apagada (cáscara), o null. */
    var husk: FloatArray? = null
        private set
    private var shelfT = 0f
    /** Latido de la Raíz: s hasta el próximo; durante [beatBoostT] los enemigos van un 30 % más rápido. */
    var beatT = BEAT_PERIOD
        private set
    var beatBoostT = 0f
        private set
    private var moltenTick = 0f
    /** Corazón de piedra golpeado: la sala pasa a ser de combate. */
    private var heartAwake = false
    /** Se usó el Haz en esta sala (reto de la Sala de lectura). */
    var hasHazUsed = false
        private set
    var hazUsedInRoom: Boolean
        get() = hasHazUsed
        set(v) { if (v) hazUses++; hasHazUsed = v }
    /** Ya salió una botella fuera de la Sala de lectura en esta run. */
    private var bottleGiven = false
    /** Historia de Isla para el clon de papel. */
    class Snap(var x: Float = 0f, var y: Float = 0f, var attack: Boolean = false, var ax: Float = 1f, var ay: Float = 0f)
    private val history = Array(HISTORY) { Snap() }
    private var historyN = 0L
    /** Eco de la Lámpara Madre (para teñir la sala). */
    var echoBanner = ""
        private set

    // Historia: recuerdo del jefe y título de capítulo
    var memory: Story.Memory? = null
        private set
    var memoryT = 0f
        private set
    var chapter: Pair<String, String>? = null
        private set
    var chapterT = 0f
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

    private var plan = ArrayDeque<List<Pair<String, Affix?>>>()
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
    val biome get() = Biome.of(max(1, floorNum))

    // ---------------------------------------------------------------- arranque

    private fun initSanctuary() {
        marea = 0; baja = false; applyMarea()
        floorNum = 0
        map = FloorGen.sanctuary()
        enterCell(map.start, null)
        player.weapon = Meta.weapon
        showBanner("EL SANTUARIO", 2.2f)
    }

    private fun initTest(floor: Int, toBoss: Boolean, toCell: Boolean) {
        forceRescueFloor = if (toCell) floor else -1
        initRun()
        if (floor != 1) startFloor(floor)
        val target = when {
            toBoss -> map.all.firstOrNull { it.type == CellType.BOSS }
            toCell -> map.all.firstOrNull { it.type == CellType.CELDA }
            else -> null
        }
        target?.let {
            enterCell(it, null)
            // Entrar por una puerta, no dentro de la jaula ni encima del jefe.
            room.doors.minByOrNull { d -> d.side.ordinal }?.let { d ->
                val e = room.entryPoint(d)
                player.placeAt(e[0], e[1])
            }
        }
    }

    /**
     * Náufragos (§15): uno por bioma, en uno de sus 3 pisos (sale de la semilla: no hace falta guardarlo).
     * Mientras no lo rescates, su celda aparece en cada run.
     */
    private fun rescueFloor(b: Biome) = b.firstFloor + ((seed ushr (11 + 5 * b.ordinal)) and 0x7fffffffL).toInt() % 3
    private var forceRescueFloor = -1
    private fun hasRescue(floor: Int): Boolean {
        if (floor == forceRescueFloor) return true
        val b = Biome.of(floor)
        return !test && floor == rescueFloor(b) && Story.castawayOf(b).key !in Meta.unlocks
    }

    /** Run nueva con lo elegido en el Santuario. */
    private fun initRun() {
        baja = Meta.mareaBaja && !test
        marea = if (test) testMarea else if (baja) 0 else Meta.marea
        applyMarea()
        player.weapon = Meta.weapon
        // Marea 7: se empieza con 80 de Luz máxima.
        player.luzMax = (if (marea >= 7) 80f else Balance.LUZ_MAX) + 10f * Meta.luzLevel
        // Bruno, el campanero: +20 de Luz máxima, Campana +1 y algo más lento.
        if (Meta.hero == "campanero" && Meta.campaneroPlayable) {
            player.luzMax += 20f
            player.heroSpeed = 0.92f
            if (player.weapon == Weapon.CAMPANA) weaponLevel = 1
        }
        player.luz = player.luzMax
        Meta.startExvoto?.let { id -> runCatching { Exvotos.get(id) }.getOrNull()?.let { owned += it } }
        recomputeStats()
        startFloor(1)
    }

    private fun initFromSave(d: RunSave.Data) {
        marea = d.marea; baja = d.baja; applyMarea()
        hazUses = d.hazUses
        bossLuz += d.bossLuz
        player.weapon = runCatching { Weapon.valueOf(d.weapon) }.getOrDefault(Weapon.FAROL)
        if (d.hero == "campanero") player.heroSpeed = 0.92f
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
        map = FloorGen.generate(floorSeed, floorNum, hasBoss = Biome.isBossFloor(floorNum))
        if (hasRescue(floorNum)) FloorGen.placeRescue(map)
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
                c.pedestals += Pedestal(p.x, p.y, k, ex, p.price, wp, metaId = p.meta).also { it.taken = p.taken }
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
        val base = when (c.type) {
                CellType.START -> "inicio"
                CellType.SHOP -> "tienda"
                CellType.TREASURE -> "tesoro"
                CellType.REFUGE -> "refugio"
                CellType.SECRET -> "secreta"
                CellType.BOSS -> "jefe"
                CellType.ANTESALA -> "antesala"
                CellType.SANCTUARY -> "santuario"
                CellType.CAPILLA -> "capilla"
                CellType.CELDA -> "celda"
                CellType.LECTURA -> "lectura"
                CellType.CORAZON -> "corazon"
                else -> "combate"
            }
        // Cada bioma usa sus plantillas propias si las tiene ("cat_combate"); si no, las comunes.
        val own = biome.prefix + base
        return RoomTemplates.of(if (biome.prefix.isNotEmpty() && RoomTemplates.has(own)) own else base)
    }

    private fun startFloor(n: Int) {
        floorNum = n
        // La semilla del piso sale solo de la semilla de la run: con la misma semilla, los mismos pisos.
        floorSeed = Seed.floor(seed, n)
        map = FloorGen.generate(floorSeed, n, hasBoss = Biome.isBossFloor(n))
        if (hasRescue(n)) FloorGen.placeRescue(map)
        mapRevealed = false
        wickUsed = false
        offered.clear()
        for (c in map.all) c.templateIndex = Random(c.seed).nextInt(templatesFor(c).size)
        // Vértebra de ballena: +10 de Luz máxima al empezar cada bioma.
        if (stats.whaleBone && n == Biome.of(n).firstFloor && player.luzMax < 200f) { player.luzMax += 10f; player.luz += 10f }
        // La Cartógrafa (y el Catalejo): las salas especiales se ven en el mapa desde el inicio.
        if (Meta.CARTOGRAFA in Meta.unlocks || stats.spyglass) {
            for (c in map.all) if (c.type != CellType.COMBAT && c.type != CellType.SECRET) { c.known = true; c.iconShown = true }
        }
        enterCell(map.start, null)
        memory = null
        val b = biome
        if (n == b.firstFloor) {
            chapter = Story.chapter(b)
            chapterT = CHAPTER_TIME
        } else {
            chapterT = 0f
            showBanner("${b.label}  ·  PISO $n", 2.6f)
        }
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
        waves.clear()
        traps.clear(); lines.clear(); ink.clear()
        atrilesOn = true
        darkFight = false
        husk = null
        atriles.clear()
        for (t in room.tilesOf(Room.ATRIL)) atriles += room.tileCenter(t[0], t[1])
        shelfT = 0f
        heartAwake = false
        hasHazUsed = false
        hasCoral = false
        dimT = 0f
        candles.clear()
        for (t in room.candleTiles()) {
            val c = room.tileCenter(t[0], t[1])
            candles += Candle(t[0], t[1], c[0], c[1])
        }
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

        plan = if (anchor.isCombat && !anchor.cleared && anchor.type != CellType.BOSS) planWaves(anchor) else ArrayDeque()
        waveDelay = 0.7f
        intact = true
        fadeT = FADE

        // Marea 8: niebla, el refugio ya no revela el mapa.
        if (anchor.type == CellType.REFUGE && marea < 8) revealMap()
        if (anchor.type == CellType.BOSS && !anchor.cleared && kind == Kind.RUN) bossLuz += player.luz.roundToInt()
        if (anchor.type == CellType.BOSS && !anchor.cleared) {
            val id = biome.bossId
            // La Lámpara Madre está fija en el centro de la arena.
            val by = if (id == "lampara") room.height / 2f + 8f else room.height - 64f
            val b = createEnemy(id, room.width / 2f, by, 1)
            if (id == "lampara") {
                // La base de la lámpara es sólida: Isla la golpea desde fuera.
                val tx = (b.x / TILE).toInt()
                val ty = (b.y / TILE).toInt()
                for (dx in -1..0) for (dy in -1..0) room.block(tx + dx, ty + dy)
            }
            enemies += b
            boss = b
            // Demo: se ve a la Sirena un momento y la pantalla funde a negro.
            if (cl.xiombo.faro.data.Edition.demo) demoEndT = DEMO_TEASE
            if (b is LamparaMadre && b.dormant) {
                showBanner(if (Meta.HIJA in Meta.unlocks) "LA LÁMPARA DUERME  ·  VELA CERCA DE ELLA 3 SEGUNDOS" else "LA LÁMPARA DUERME", 3f)
            } else showBanner(b.def.name.uppercase(), 2.6f)
        } else if (firstVisit) {
            when (anchor.type) {
                CellType.SHOP -> showBanner("LA TIENDA DE LA PESCADORA")
                CellType.TREASURE -> showBanner("UN EXVOTO OLVIDADO")
                CellType.REFUGE -> showBanner("REFUGIO DEL FARO")
                CellType.SECRET -> showBanner("SALA SECRETA")
                CellType.ELITE -> showBanner("ALGO MÁS GRANDE ACECHA")
                CellType.ANTESALA -> showBanner(if (biome == Biome.CATEDRAL) "ANTESALA  ·  UN CORO REZA AL OTRO LADO" else "ANTESALA  ·  ALGO CANTA AL OTRO LADO")
                CellType.CAPILLA -> showBanner("CAPILLA DE LA OFRENDA  ·  ${Story.CAPILLA.uppercase()}", 3f)
                CellType.CELDA -> showBanner("UNA CELDA CUSTODIADA  ·  ALGUIEN PIDE AYUDA", 3f)
                CellType.LECTURA -> showBanner("SALA DE LECTURA  ·  ${Story.LECTURA.uppercase()}", 3.2f)
                CellType.CORAZON -> showBanner("CORAZÓN DE PIEDRA  ·  ${Story.CORAZON.uppercase()}", 3.2f)
                else -> {}
            }
        }
        if (anchor.type == CellType.CELDA && anchor.cleared) anchor.pedestals.forEach { if (it.kind == PedKind.JAULA) it.selected = true }
        // Ojos de la fosa: son parte de la sala (no del presupuesto) y hay que destruirlos para salir.
        if (anchor.isCombat && !anchor.cleared && anchor.type != CellType.BOSS) {
            for (e in tpl.eyes) {
                val c = room.tileCenter(e[0], e[1])
                enemies += createEnemy("ojo", c[0], c[1], floorNum, null)
            }
        }
        // Corazón de piedra: late en el centro hasta que lo golpeas.
        if (anchor.type == CellType.CORAZON && !anchor.cleared) {
            val c = tpl.pedestals.firstOrNull()?.let { room.tileCenter(it[0], it[1]) } ?: floatArrayOf(room.width / 2f, room.height / 2f)
            enemies += Corazon(EnemyDefs["corazon"], c[0], c[1])
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
                    2 -> Pedestal(p[0], p[1], PedKind.MECHA, price = shopPrice(MECHA_PRICE))
                    3 -> if (weaponLevel < 3) Pedestal(p[0], p[1], PedKind.MEJORA, price = shopPrice(MEJORA_PRICE), weapon = player.weapon) else exvotoPed(p, true)
                    4 -> if (!mapRevealed) Pedestal(p[0], p[1], PedKind.MAPA, price = shopPrice(MAPA_PRICE)) else exvotoPed(p, true)
                    else -> exvotoPed(p, true)
                }
                c.pedestals += ped
            }
            CellType.TREASURE -> pos.firstOrNull()?.let { c.pedestals += exvotoPed(it, false) }
            CellType.SECRET -> pos.firstOrNull()?.let {
                c.pedestals += exvotoPed(it, false, Rarity.RARO)
                // Una botella como máximo por run fuera de la Sala de lectura.
                if (!bottleGiven && rng.nextFloat() < 0.5f) {
                    val q = room.nearestFloor(it[0] + 24f, it[1])
                    bottlePed(q, guaranteed = false)?.let { b -> c.pedestals += b }
                }
            }
            CellType.REFUGE -> pos.firstOrNull()?.let { c.pedestals += Pedestal(it[0], it[1], PedKind.REFUGIO) }
            CellType.ANTESALA -> pos.firstOrNull()?.let { c.pedestals += Pedestal(it[0], it[1], PedKind.VIGILIA) }
            CellType.CELDA -> pos.firstOrNull()?.let { c.pedestals += Pedestal(it[0], it[1], PedKind.JAULA, metaId = Story.castawayOf(biome).key) }
            CellType.LECTURA -> pos.firstOrNull()?.let { p -> bottlePed(p, guaranteed = true)?.let { c.pedestals += it } }
            CellType.CAPILLA -> if (pos.size >= 2) {
                // El mismo exvoto (raro o mejor) por dos precios: 10 de Luz máxima o la mitad de tu Luz.
                val exclude = owned.map { it.id }.toSet() + offered
                val e = Exvotos.roll(rng, exclude, Rarity.RARO, biome = biome.ordinal)
                if (e != null) {
                    offered += e.id
                    c.pedestals += Pedestal(pos[0][0], pos[0][1], PedKind.OFRENDA_MAX, e)
                    c.pedestals += Pedestal(pos[1][0], pos[1][1], PedKind.OFRENDA_MITAD, e)
                }
            }
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
        // La Marea se elige junto al pozo.
        room.nearestFloor(pos[0][0] - 64f, pos[0][1]).let { c.pedestals += Pedestal(it[0], it[1], PedKind.MAREA) }
        val shop = Meta.shopOpen
        Weapon.entries.forEachIndexed { i, w ->
            val p = pos[1 + i]
            val unlocked = Meta.isUnlocked(w)
            val price = if (w == Weapon.ANCLA && !unlocked) 60 else 0
            c.pedestals += Pedestal(p[0], p[1], PedKind.ARMA, price = price, weapon = w).also {
                it.selected = Meta.weapon == w
                if (!unlocked) it.lockedText = if (price > 0 && !shop) CLOSED_SHOP else Meta.unlockRequirement(w)
            }
        }
        // Náufragos rescatados: viven aquí. Amparo atiende la tienda.
        val spots = listOf(
            floatArrayOf(pos[7][0] + 96f, pos[7][1]), floatArrayOf(pos[5][0] - 96f, pos[5][1]),
            floatArrayOf(pos[4][0] + 72f, pos[4][1]), floatArrayOf(pos[1][0] - 72f, pos[1][1]),
        )
        Story.CASTAWAYS.forEachIndexed { i, who ->
            if (who.key in Meta.unlocks) {
                val a = room.nearestFloor(spots[i][0], spots[i][1])
                c.pedestals += Pedestal(a[0], a[1], PedKind.NPC, metaId = who.key)
            }
        }
        // Lotes de exvotos (arriba, al centro) y elección de personaje (junto al pozo).
        val lot = room.nearestFloor((pos[9][0] + pos[10][0]) / 2f, pos[9][1])
        c.pedestals += Pedestal(lot[0], lot[1], PedKind.LOTE, price = if (Meta.lots >= Exvotos.LOTS) 0 else Exvotos.LOT_PRICE).also {
            it.taken = Meta.lots >= Exvotos.LOTS
            if (!shop) it.lockedText = CLOSED_SHOP
        }
        val hero = room.nearestFloor(pos[0][0] + 64f, pos[0][1])
        c.pedestals += Pedestal(hero[0], hero[1], PedKind.HEROE)
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
            Triple(Meta.CAMPANERO, "Bruno, el campanero renegado", "Segundo personaje jugable (elígelo junto al pozo).") to 150,
        )
        metas.forEachIndexed { i, (m, price) ->
            val p = pos[8 + i]
            val have = when (m.first) {
                "luz" -> Meta.luzLevel >= 2
                else -> m.first in Meta.unlocks
            }
            c.pedestals += Pedestal(p[0], p[1], PedKind.META, price = if (have) 0 else price, metaId = m.first, metaTitle = m.second, metaDetail = m.third).also {
                it.taken = have
                if (!have && !shop) it.lockedText = CLOSED_SHOP
                else if (!have && m.first == Meta.CAMPANERO && Meta.RESCUE_CAMPANERO !in Meta.unlocks) it.lockedText = "rescátalo de su celda en la Catedral Sumergida"
                else if (!have && m.first == Meta.CARTOGRAFA && Meta.RESCUE_CARTOGRAFA !in Meta.unlocks) it.lockedText = "rescátala de su celda en el Archivo de los Náufragos"
            }
        }
    }

    private var amparoLine = 0

    /** Marea 4: la tienda es un 20 % más cara. */
    private fun shopPrice(p: Int) = if (marea >= 4) (p * 1.2f).roundToInt() else p

    /** Perlas ganadas: +20 % por nivel de Marea. Las runs de prueba no dan Perlas. */
    private fun earnPerlas(n: Int) {
        val v = (n * (1f + 0.2f * marea)).roundToInt()
        perlasRun += v
        if (!test) Meta.addPerlas(v)
    }

    /** Una botella sin leer (o null si ya están todas leídas). */
    private fun bottlePed(p: FloatArray, guaranteed: Boolean): Pedestal? {
        val unread = Bottles.all.filter { it.id !in Meta.bottles && it.id !in offered }
        if (unread.isEmpty()) return null
        val b = unread[rng.nextInt(unread.size)]
        offered += b.id
        if (!guaranteed) bottleGiven = true
        return Pedestal(p[0], p[1], PedKind.BOTELLA, metaId = b.id)
    }

    private fun exvotoPed(p: FloatArray, priced: Boolean, min: Rarity = Rarity.COMUN): Pedestal {
        val exclude = owned.map { it.id }.toSet() + offered
        val e = Exvotos.roll(rng, exclude, min, biome = biome.ordinal) ?: return Pedestal(p[0], p[1], PedKind.MECHA, price = if (priced) MECHA_PRICE else 0)
        offered += e.id
        return Pedestal(p[0], p[1], PedKind.EXVOTO, e, if (priced) shopPrice(e.rarity.price) else 0)
    }

    /** Reparte el presupuesto de la sala (8 + 3·f) en 1–3 oleadas (§6). Salas grandes: ×1,6. */
    private fun planWaves(c: Cell): ArrayDeque<List<Pair<String, Affix?>>> {
        val budget = (Balance.budget(floorNum) * if (c.partner != null) 1.6f else 1f).roundToInt()
        val n = 1 + rng.nextInt(3)
        val pool = EnemyDefs.all.values.filter { it.biome == biome.tag }
        // Los afijos nuevos se suman a los del Muelle (§14): cada bioma añade el suyo.
        val affixes = Affix.entries.filter {
            when (it) {
                Affix.BENDECIDO -> biome.ordinal >= Biome.CATEDRAL.ordinal
                Affix.TACHADO -> biome.ordinal >= Biome.ARCHIVO.ordinal
                Affix.HAMBRE -> biome == Biome.RAIZ
                else -> true
            }
        }
        val cheapest = pool.minBy { it.cost }
        val out = ArrayDeque<List<Pair<String, Affix?>>>()
        for (i in 0 until n) {
            var wb = if (i == n - 1) budget - (budget / n) * (n - 1) else budget / n
            val wave = ArrayList<Pair<String, Affix?>>()
            // La celda tiene guardia: un élite como en las salas de élite.
            // Marea 1: también puede haber un élite en las salas normales (20 % por oleada).
            val eliteHere = c.type == CellType.ELITE || c.type == CellType.CELDA || heartAwake ||
                (marea >= 1 && c.type == CellType.COMBAT && rng.nextFloat() < 0.2f)
            if (i == 0 && eliteHere) {
                val e = pool.filter { it.id != "polilla" }.let { it[rng.nextInt(it.size)] }
                wave += e.id to affixes[rng.nextInt(affixes.size)]
                wb -= e.cost * 2
            }
            val cap = Balance.maxAlive(floorNum) + if (c.partner != null) 2 else 0
            while (wave.size < cap) {
                val options = pool.filter { it.cost <= wb }
                if (options.isEmpty()) break
                val pick = options[rng.nextInt(options.size)]
                // Las polillas llegan en grupos de 4.
                val n = if (pick.id == "polilla") min(4, max(1, wb / pick.cost)) else 1
                repeat(n) { wave += pick.id to null }
                wb -= pick.cost * n
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
        memoryT -= dt
        chapterT -= dt
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
            if (modeT > (if (mode == Mode.DEMO_END) 3.5f else 1.8f) && inp.anyPressed) next = Next.SANCTUARY
            inp.consume(dt)
            return
        }

        if (inp.debugCmd != 0 && !cl.xiombo.faro.data.Edition.demo) debugCommand(inp.debugCmd)
        if (demoEndT > 0f) {
            demoEndT -= dt
            if (demoEndT <= 0f) endDemo()
        }
        val s = statue
        if (s != null) { targetX = s.x; targetY = s.y } else { targetX = player.cx; targetY = player.cy }

        player.update(this, inp, dt)
        recordHistory()
        val light = playerLight()
        // Ojo de vidrio (48 px) y el oído del Campanero (64 px) dejan ver a la Tinta y a los Tachados de cerca.
        val near = max(if (stats.glassEye) 48f else 0f, if (Meta.hero == "campanero" && kind == Kind.RUN) 64f else 0f)
        for (e in enemies) {
            val de = dist(e.x, e.y, player.cx, player.cy)
            e.inLight = de <= light + e.radius || de <= near
            e.boost = if (beatBoostT > 0f) 1.3f else 1f
        }

        val it = pending.iterator()
        while (it.hasNext()) {
            val s2 = it.next()
            s2.t -= dt
            if (s2.t <= 0f) {
                val ne = createEnemy(s2.id, s2.x, s2.y, floorNum, s2.affix)
                // Marea 9: las élites llevan 2 afijos.
                if (marea >= 9 && s2.affix != null) {
                    val others = Affix.entries.filter { it != s2.affix && it != Affix.GEMELO }
                    ne.affix2 = others[rng.nextInt(others.size)]
                }
                enemies += ne
                fx.burst(s2.x, s2.y, 8, if (s2.affix != null) s2.affix.color else Pal.WATER, 70f, 0.35f)
                it.remove()
            }
        }

        updateTerrain(dt)
        updateArchivoRaiz(dt)
        saltZones.removeAll { z ->
            z[2] -= dt
            for (e in enemies) if (!e.killed && dist(e.x, e.y, z[0], z[1]) < 14f + e.radius) e.slow(0.5f, 0.3f)
            z[2] <= 0f
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

        if ((cell.isCombat || heartAwake) && !cell.cleared && enemies.isEmpty() && pending.isEmpty()) {
            if (plan.isNotEmpty()) {
                waveDelay -= dt
                if (waveDelay <= 0f) {
                    spawnWave(plan.removeFirst())
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
        sfx("door")
        if (cell.type == CellType.STAIRS) {
            val p = room.nearestFloor(room.width / 2f, room.height / 2f)
            cell.pedestals += Pedestal(p[0], p[1], PedKind.ESCALERA)
            showBanner("UNA ESCALERA BAJA AL ABISMO")
        } else if (cell.type == CellType.LECTURA) {
            if (!hasHazUsed) {
                val p = room.nearestFloor(room.width / 2f, room.height / 2f - 24f)
                cell.pedestals += exvotoPed(p, false, Rarity.RARO)
                showBanner("RETO CUMPLIDO  ·  SIN HAZ  ·  UN EXVOTO RARO", 2.8f)
            } else {
                showBanner("SALA DESPEJADA  ·  USASTE EL HAZ: SIN PREMIO", 2.4f)
            }
        } else if (cell.type == CellType.CORAZON) {
            showBanner("EL CORAZÓN VUELVE A DORMIR", 2.2f)
        } else if (cell.type == CellType.CELDA) {
            cell.pedestals.forEach { if (it.kind == PedKind.JAULA) it.selected = true }
            showBanner("LOS GUARDIAS HAN CAÍDO  ·  ABRE LA CELDA", 2.6f)
        } else if (cell.type != CellType.BOSS) {
            showBanner("SALA DESPEJADA")
        }
        if (intact && cell.type != CellType.BOSS && stats.saltRosary && player.luzMax < 200f) {
            player.luzMax += 2f
            fx.floater("+2 LUZ MÁX.", player.cx, player.cy + 30f, Pal.SALADO)
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
                gainLuz(o.value * stats.orbMul)
                sfx("orb")
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
        sfx(if (p.kind == PedKind.ESCALERA || p.kind == PedKind.DESCENSO) "door" else "pickup")
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
                // Marea 2: el refugio da 20 de Luz en vez de 30.
                val heal = if (marea >= 2) 20f else 30f
                gainLuz(heal)
                fx.burst(p.x, p.y + 8f, 20, Pal.LAMP, 100f, 0.6f)
                fx.floater("+${heal.toInt()} LUZ", player.cx, player.cy + 20f, Pal.LAMP_HI)
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
                earnPerlas(FLOOR_PERLAS)
                startFloor(floorNum + 1)
            }
            PedKind.FIN -> {
                sfx("victory")
                mode = Mode.VICTORY
                modeT = 0f
                if (!test) {
                    RunSave.clear()
                    if (biome == Biome.RAIZ) {
                        Meta.endings += if (finalC) "C" else if (Meta.allBottles) "B" else "A"
                        // Ganar con la Marea M desbloquea la M+1 (la Marea baja no cuenta).
                        if (!baja) Meta.mareaMax = min(Mareas.MAX, max(Meta.mareaMax, marea + 1))
                        Meta.save()
                    }
                    logRun("VICTORIA")
                }
            }
            PedKind.MAREA -> {
                // Recorre 0..máx y luego la Marea baja.
                when {
                    Meta.mareaBaja -> { Meta.mareaBaja = false; Meta.marea = 0 }
                    Meta.marea < Meta.mareaMax -> Meta.marea++
                    else -> Meta.mareaBaja = true
                }
                Meta.save()
                showBanner(Mareas.label(Meta.marea, Meta.mareaBaja), 1.4f)
                fx.burst(p.x, p.y + 6f, 10, Pal.TIDE, 70f, 0.4f)
            }
            PedKind.ARMA -> {
                val w = p.weapon ?: return
                if (!Meta.isUnlocked(w)) {
                    if (w == Weapon.ANCLA && p.price > 0) {
                        if (!Meta.shopOpen) { fx.floater("TIENDA CERRADA", p.x, p.y + 16f, Pal.CORAL); return }
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
            PedKind.BOTELLA -> {
                val b = p.metaId?.let { Bottles.get(it) } ?: return
                p.taken = true
                Meta.bottles += b.id
                Meta.save()
                memory = Story.Memory("${b.from}  ·  botella ${Meta.bottles.size}/${Bottles.all.size}", b.text)
                memoryT = MEMORY_TIME
                fx.burst(p.x, p.y + 6f, 12, Pal.PAPER, 70f, 0.4f)
                if (Meta.allBottles) showBanner("TODAS LAS BOTELLAS  ·  EL FINAL B ESTÁ ABIERTO", 3.5f)
                saveRun()
            }
            PedKind.JAULA -> {
                if (!cell.cleared) { fx.floater("AÚN HAY GUARDIAS", p.x, p.y + 20f, Pal.CORAL); return }
                val who = Story.castaway(p.metaId) ?: Story.castawayOf(biome)
                p.taken = true
                Meta.unlocks += who.key
                Meta.save()
                fx.burst(p.x, p.y + 8f, 24, Pal.LAMP_HI, 110f, 0.7f)
                showBanner(who.banner, 3.5f)
                memory = who.rescue
                memoryT = MEMORY_TIME
                saveRun()
            }
            PedKind.AMPARO, PedKind.NPC -> {
                val who = Story.castaway(p.metaId) ?: Story.CASTAWAYS[0]
                val line = who.lines[amparoLine % who.lines.size]
                memory = Story.Memory(who.name, line)
                memoryT = 6f
                amparoLine++
            }
            PedKind.LOTE -> {
                if (Meta.lots >= Exvotos.LOTS) { fx.floater("COMPLETO", p.x, p.y + 16f, Pal.FOAM); return }
                if (p.lockedText != null) { fx.floater("TIENDA CERRADA", p.x, p.y + 16f, Pal.CORAL); return }
                if (!payPerlas(p.price)) return
                Meta.lots++
                Meta.save()
                stockSanctuary()
                fx.burst(p.x, p.y + 6f, 16, Pal.SHELL, 90f, 0.5f)
                showBanner("6 EXVOTOS NUEVOS EN EL POZO", 1.8f)
            }
            PedKind.HEROE -> {
                if (!Meta.campaneroPlayable) { fx.floater("CONTRATA A BRUNO PRIMERO", p.x, p.y + 16f, Pal.CORAL); return }
                Meta.hero = if (Meta.hero == "campanero") "isla" else "campanero"
                Meta.save()
                fx.burst(p.x, p.y + 6f, 12, Pal.LILAC, 80f, 0.4f)
                showBanner(if (Meta.hero == "campanero") "BAJA BRUNO, EL CAMPANERO" else "BAJA ISLA, LA FARERA", 1.6f)
            }
            PedKind.OFRENDA_MAX, PedKind.OFRENDA_MITAD -> {
                val e = p.exvoto ?: return
                if (p.kind == PedKind.OFRENDA_MAX) {
                    if (player.luzMax <= 20f) { fx.floater("NO TE QUEDA BASTANTE", player.cx, player.cy + 20f, Pal.CORAL); return }
                    player.luzMax -= 10f
                    player.luz = min(player.luz, player.luzMax)
                    fx.floater("-10 LUZ MÁX.", player.cx, player.cy + 20f, Pal.CORAL)
                } else {
                    val half = player.luz / 2f
                    if (half < 1f) { fx.floater("NO TE QUEDA BASTANTE", player.cx, player.cy + 20f, Pal.CORAL); return }
                    player.luz -= half
                    fx.floater("-${half.roundToInt()}", player.cx, player.cy + 20f, Pal.CORAL)
                }
                for (o in cell.pedestals) if (o.kind == PedKind.OFRENDA_MAX || o.kind == PedKind.OFRENDA_MITAD) o.taken = true
                owned += e
                val before = stats
                recomputeStats()
                fx.burst(p.x, p.y, 20, e.rarity.color, 100f, 0.6f)
                val completed = (e.tag != Tag.NINGUNA) && stats.tagCount(e.tag) == 3 && before.tagCount(e.tag) == 2
                if (completed) showBanner("CONJUNTO ${e.tag.label.uppercase()}: ${Exvotos.setBonus[e.tag]}", 3.5f)
                else showBanner("EL ALTAR ACEPTA  ·  ${e.name.uppercase()}", 2f)
                saveRun()
            }
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

    fun shoot(x: Float, y: Float, vx: Float, vy: Float, dmg: Float, cause: String = "Notas · La Sirena de Brea", color: com.badlogic.gdx.graphics.Color = Pal.CREAM) {
        projectiles += Projectile(x, y, vx, vy, 3f, dmg, 9f, color, cause)
    }

    // ---------------------------------------------------------------- Catedral

    /**
     * Vitrales, candelabros y ondas. Pisar un vitral lo agrieta (el Destello no); 1,2 s después se
     * rompe en un pozo. Un enemigo que camina y queda encima cae: pierde un 30 % de vida y reaparece al lado.
     */
    private fun updateTerrain(dt: Float) {
        dimT = max(0f, dimT - dt)
        waves.removeAll { it.t -= dt; it.t <= 0f }
        for (c in candles) if (c.lit) c.litT += dt
        if (!room.hasGlass && !hasCoral) return
        if (room.hasGlass) {
            if (player.dashT <= 0f && !player.falling && room.stepOn(player.x, player.y)) {
                fx.burst(player.x, player.y, 4, Pal.GLASS_LIGHT, 40f, 0.25f)
            }
            for (e in enemies) {
                if (e.isBoss || e.killed || !e.blockedByWater) continue
                room.stepOn(e.x, e.y - e.hh + 2f)
            }
        }
        room.update(dt, { tx, ty ->
            sfx("glass")
            val c = room.tileCenter(tx, ty)
            fx.burst(c[0], c[1], 10, Pal.GLASS_LIGHT, 90f, 0.4f)
            fx.burst(c[0], c[1], 5, Pal.VIOLET, 70f, 0.35f)
            for (e in enemies) {
                if (e.isBoss || e.killed || !e.blockedByWater) continue
                if ((e.x / TILE).toInt() == tx && ((e.y - e.hh + 2f) / TILE).toInt() == ty) {
                    e.takeHit(this, e.maxHp * 0.3f, e.x, e.y, HitKind.SLAM)
                    fx.floater("¡CAE!", e.x, e.y + 12f, Pal.GLASS_LIGHT)
                    val p = room.nearestFloor(e.x, e.y)
                    e.x = p[0]; e.y = p[1]; e.snapshot()
                }
            }
        }, { _, _ -> })
    }

    private var hasCoral = false

    /** Sal del Destello (Cristal de roca): (x, y, s). */
    val saltZones = ArrayList<FloatArray>()
    private val killTimes = ArrayDeque<Float>()
    /** Mecha de reserva ya usada en este piso. */
    private var wickUsed = false

    fun addSaltZone(x: Float, y: Float) { if (saltZones.size < 30) saltZones += floatArrayOf(x, y, 2f) }

    /** Estallido de fuego: 8 de daño y Quemadura a [r] px (Fuelle, Mecha de reserva). */
    fun fireBlast(x: Float, y: Float, r: Float = 32f) {
        fx.blast(x, y, r)
        fx.burst(x, y, 12, Pal.LAMP, 110f, 0.4f)
        for (e in enemies) {
            if (!e.targetable || dist(e.x, e.y, x, y) - e.radius > r) continue
            e.takeHit(this, 8f, x, y, HitKind.BLAST)
            e.burn(stats.burnTime)
        }
    }

    // ---------------------------------------------------------------- Archivo y Raíz

    private fun updateArchivoRaiz(dt: Float) {
        // Trampas (páginas, raíces) y líneas del Copista.
        traps.removeAll { t ->
            t.t -= dt
            if (t.t <= 0f) {
                if (player.hurtOverlaps(t.x, t.y, t.r)) hurtPlayer(t.dmg, t.x, t.y, t.src, t.cause)
                fx.burst(t.x, t.y, 8, if (t.kind == Trap.Kind.PAGE) Pal.PAPER else Pal.ROOT_HI, 90f, 0.35f)
                true
            } else false
        }
        lines.removeAll { l ->
            l.t -= dt
            if (l.t <= 0f) {
                val ax = l.x1 - l.x0
                val ay = l.y1 - l.y0
                val len2 = max(0.001f, ax * ax + ay * ay)
                val k = (((player.cx - l.x0) * ax + (player.cy - l.y0) * ay) / len2).coerceIn(0f, 1f)
                val px = l.x0 + ax * k
                val py = l.y0 + ay * k
                if (dist(px, py, player.cx, player.cy) < 9f) hurtPlayer(l.dmg, px, py, l.src, "Línea escrita · Copista")
                var t = 0f
                while (t <= 1f) { fx.burst(l.x0 + ax * t, l.y0 + ay * t, 2, Pal.INK_HI, 60f, 0.3f); t += 0.2f }
                true
            } else false
        }
        ink.removeAll { it[2] -= dt; it[2] <= 0f }

        // Estanterías: cada 6 s se deslizan (aviso de 1 s). En la arena del Cartógrafo las mueve él.
        if (room.shelves.isNotEmpty()) {
            if (boss !is Cartografo) {
                shelfT += dt
                if (shelfT >= SHELF_PERIOD - 1f && room.shelves.all { it.warnT <= 0f && it.pending == 0 }) {
                    for (g in room.shelves) g.warnT = 1f
                    shelfT = -1f
                }
            }
            for (g in room.shelves) {
                if (g.warnT > 0f) {
                    g.warnT -= dt
                    if (g.warnT <= 0f) {
                        g.sign = if (g.offset == 0) 1 else -1
                        g.pending = g.steps
                        g.stepT = 0f
                        if (boss !is Cartografo) shelfT = 0f
                    }
                }
                if (g.pending > 0) {
                    g.stepT -= dt
                    if (g.stepT <= 0f) {
                        g.stepT = 0.18f
                        g.pending--
                        g.offset += g.sign
                        room.shiftShelf(g, g.sign)
                        shelfCrush(g.dx * g.sign, g.dy * g.sign)
                    }
                }
            }
        }

        // Raíz: latido cada 10 s (aviso de 1 s) y vidrio fundido.
        if (biome == Biome.RAIZ && !inSanctuary) {
            beatBoostT = max(0f, beatBoostT - dt)
            beatT -= dt
            if (beatT <= 1f && beatT + dt > 1f) { addShake(1.5f); fx.floater("BUM", player.cx, player.cy + 26f, Pal.MOLTEN) }
            if (beatT <= 0f) {
                beatT = BEAT_PERIOD
                beatBoostT = BEAT_BOOST_TIME
                addShake(3f)
            }
        }
        if (room.hasMolten && player.dashT <= 0f && !player.falling && room.isMoltenAt(player.x, player.y)) {
            player.luz -= MOLTEN_BURN * dt
            moltenTick += dt
            if (moltenTick > 0.5f) { moltenTick = 0f; fx.floater("-2", player.cx, player.cy + 14f, Pal.MOLTEN) }
            if (player.luz <= 0f) { lastCause = "Vidrio fundido"; die() }
        }

        // La Lámpara Madre bebe luz (fase 4).
        val lamp = boss as? LamparaMadre
        if (lamp != null && lamp.drinking && mode == Mode.PLAYING) {
            player.luz -= dt
            if (player.luz <= 0f) { lastCause = "La Lámpara Madre bebió tu luz"; die() }
        }
        if (lamp?.echoWarnT ?: 0f <= 0f) echoBanner = ""
    }

    /** Una estantería se movió un tile en (dx, dy): empuja o aplasta a quien estaba en medio. */
    private fun shelfCrush(dx: Int, dy: Int) {
        val p = player
        if (room.boxHits(p.x, p.y, p.hw, p.hh)) {
            val nx = p.x + dx * TILE
            val ny = p.y + dy * TILE
            if (!room.boxHits(nx, ny, p.hw, p.hh)) { p.x = nx; p.y = ny }
            else {
                val f = room.nearestFloor(p.x, p.y)
                p.placeAt(f[0], f[1])
                hurtPlayer(8f, p.cx - dx, p.cy - dy, null, "Aplastada por una estantería")
            }
        }
        for (e in enemies) {
            if (e.isBoss || e.killed || !room.boxHits(e.x, e.y, e.hw, e.hh)) continue
            val nx = e.x + dx * TILE
            val ny = e.y + dy * TILE
            if (!room.boxHits(nx, ny, e.hw, e.hh)) { e.x = nx; e.y = ny }
            else {
                e.takeHit(this, 20f, e.x - dx, e.y - dy, HitKind.SLAM)
                val f = room.nearestFloor(e.x, e.y)
                e.x = f[0]; e.y = f[1]
                fx.floater("¡CRAC!", e.x, e.y + 12f, Pal.PAPER)
            }
        }
    }

    private fun recordHistory() {
        val s = history[(historyN % HISTORY).toInt()]
        s.x = player.cx; s.y = player.cy
        s.attack = player.swingStarted
        s.ax = player.atkDx; s.ay = player.atkDy
        historyN++
    }

    /** Estado de Isla hace [ticks] ticks (o null si aún no hay tanta historia). */
    fun playerHistory(ticks: Int): Snap? {
        if (historyN <= ticks || ticks >= HISTORY) return null
        return history[((historyN - 1 - ticks) % HISTORY).toInt()]
    }

    fun spawnClone(owner: Cartografo): PaperClone {
        val s = playerHistory(PaperClone.CLONE_DELAY)
        val c = PaperClone(EnemyDefs["clon"], s?.x ?: owner.x, s?.y ?: owner.y, owner)
        newEnemies += c
        fx.burst(c.x, c.y, 14, Pal.PAPER, 90f, 0.5f)
        fx.floater("TU COPIA", c.x, c.y + 20f, Pal.PAPER)
        return c
    }

    /** Punto de suelo libre a [min]-[max] px de (x, y), o null. */
    fun randomFloorNear(x: Float, y: Float, min: Float, max: Float): FloatArray? {
        repeat(40) {
            val a = rng.nextFloat() * MathUtils.PI2
            val d = min + rng.nextFloat() * (max - min)
            val px = x + cos(a) * d
            val py = y + sin(a) * d
            if (px < 20f || py < 20f || px > room.width - 20f || py > room.height - 20f) return@repeat
            if (!room.boxHits(px, py, 8f, 8f, true)) return floatArrayOf(px, py)
        }
        return null
    }

    fun atrilLights(): List<FloatArray> = if (atrilesOn) atriles else emptyList()

    fun inkTrail(x: Float, y: Float) { if (ink.size < 120) ink += floatArrayOf(x, y, 3f) }

    /** El Cartógrafo redibuja la sala: [n] segmentos avisan 1 s y se deslizan. */
    fun redrawWalls(n: Int) {
        val free = room.shelves.filter { it.warnT <= 0f && it.pending == 0 }.shuffled(rng)
        for (g in free.take(n)) g.warnT = 1f
    }

    /** Vidriero: deja vidrio fundido a sus pies 6 s. */
    fun moltenTrail(x: Float, y: Float) {
        val tx = (x / TILE).toInt()
        val ty = (y / TILE).toInt()
        val c = room.tileCenter(tx, ty)
        if (dist(c[0], c[1], player.x, player.y) < 10f) return
        room.putTemp(tx, ty, Room.MOLTEN, 6f)
    }

    /** Corazón de piedra golpeado: 30 de Luz en orbes y una oleada élite. */
    fun awakenHeart(h: Enemy) {
        heartAwake = true
        cell.pedestals.clear()
        repeat(30) { orbs += Orb(h.x, h.y, rng.nextFloat() * 180f - 90f, rng.nextFloat() * 180f - 90f, 1f) }
        fx.burst(h.x, h.y, 30, Pal.ROOT_HI, 150f, 0.7f)
        fx.blast(h.x, h.y, 40f)
        addShake(5f)
        room.doorsOpen = false
        intact = true
        plan = planWaves(cell)
        waveDelay = 1.2f
        showBanner("EL CORAZÓN DESPIERTA  ·  ALGO GRANDE VIENE", 2.6f)
    }

    /** Fase 2 de la Lámpara Madre: escupe un Vidriero cerca de ella. */
    fun spitVidriero(x: Float, y: Float) {
        randomFloorNear(x, y, 50f, 90f)?.let { pending += PendingSpawn("vidriero", it[0], it[1], SPAWN_TELEGRAPH, null) }
    }

    fun showEcho(label: String) {
        echoBanner = label
        showBanner(label, 1.6f)
    }

    /** La Campana revela 3 s a los que solo existen en tu luz. */
    fun revealAt(x: Float, y: Float, r: Float) {
        for (e in enemies) if (e.needsLight && dist(x, y, e.x, e.y) - e.radius <= r) e.revealT = 3f
    }

    /** Enciende los candelabros apagados que toque un golpe en (x, y) con radio r. */
    fun igniteAt(x: Float, y: Float, r: Float) {
        for (c in candles) {
            if (c.lit || dist(x, y, c.x, c.y) > r + 8f) continue
            c.lit = true
            c.litT = 0f
            fx.burst(c.x, c.y + 10f, 10, Pal.LAMP_HI, 70f, 0.4f)
            if (!c.gave) {
                c.gave = true
                gainLuz(2f)
                fx.floater("+2", c.x, c.y + 18f, Pal.LAMP_HI)
            }
        }
    }

    /** Onda del Campanero: apaga tu luz un 40 % durante 3 s si te alcanza, y los candelabros cercanos. */
    fun bellWave(e: Enemy, x: Float, y: Float, r: Float, hitR: Float) {
        waves += Wave(x, y, r, 0.35f)
        addShake(1.5f)
        val d = dist(x, y, player.cx, player.cy)
        if (d <= r && !player.falling) {
            dimT = DIM_TIME
            fx.floater("TU LUZ TIEMBLA", player.cx, player.cy + 20f, Pal.LILAC)
        }
        if (d <= hitR) hurtPlayer(e.dmg, x, y, e, "Onda de campana")
        for (c in candles) {
            if (c.lit && dist(x, y, c.x, c.y) <= Campanero.CANDLE_R) {
                c.lit = false
                fx.burst(c.x, c.y + 10f, 6, Pal.SEAM, 50f, 0.4f)
            }
        }
    }

    /** Coral que tapa una casilla [seconds] (Cofrade al morir). No se cierra encima de Isla. */
    fun coralBlock(x: Float, y: Float, seconds: Float) {
        val tx = (x / TILE).toInt()
        val ty = (y / TILE).toInt()
        val c = room.tileCenter(tx, ty)
        if (dist(c[0], c[1], player.x, player.y) < 14f) return
        if (room.putCoral(tx, ty, seconds)) {
            hasCoral = true
            fx.burst(c[0], c[1], 10, Pal.CORAL_PINK, 70f, 0.4f)
        }
    }

    /** La Vela errante se apaga al robarte Luz: muere sin soltar nada. */
    fun snuffVela(e: Enemy) {
        e.hp = 0f
        e.killed = true
        e.dead = true
        kills++
    }

    /** El Obispo invoca monaguillos con círculos de sal (0,8 s de aviso), hasta [max] vivos. */
    fun summonFaithful(n: Int, max: Int) {
        val alive = enemies.count { it is Monaguillo && !it.killed } + pending.count { it.id == "monaguillo" }
        val k = min(n, max - alive)
        if (k <= 0) return
        repeat(k) {
            for (attempt in 0 until 40) {
                val x = (3 + rng.nextInt(room.cols - 6)) * TILE + TILE / 2f
                val y = (3 + rng.nextInt(room.rows - 6)) * TILE + TILE / 2f
                if (room.boxHits(x, y, 6f, 7f, true)) continue
                if (dist(x, y, player.cx, player.cy) < 70f) continue
                if (pending.any { dist(it.x, it.y, x, y) < 16f }) continue
                pending += PendingSpawn("monaguillo", x, y, SUMMON_TELEGRAPH, null)
                break
            }
        }
    }

    /** Fase 3 del Obispo: el coral crece una fila por los cuatro lados (paso 1-3). */
    fun growArenaCoral(step: Int) {
        val cols = room.cols
        val rows = room.rows
        for (ty in step until rows - step) for (tx in step until cols - step) {
            val edge = tx == step || tx == cols - 1 - step || ty == step || ty == rows - 1 - step
            if (edge) room.putCoral(tx, ty, -1f)
        }
        hasCoral = true
        addShake(4f)
        fx.floater("EL CORAL CRECE", player.cx, player.cy + 24f, Pal.CORAL_PINK)
        // Nadie queda atrapado dentro del coral.
        if (room.boxHits(player.x, player.y, player.hw, player.hh)) {
            val p = room.nearestFloor(room.width / 2f + (player.x - room.width / 2f) * 0.8f, room.height / 2f + (player.y - room.height / 2f) * 0.8f)
            player.placeAt(p[0], p[1])
        }
        for (e in enemies) if (room.boxHits(e.x, e.y, e.hw, e.hh, false)) {
            val p = room.nearestFloor(room.width / 2f, room.height / 2f)
            e.x = p[0]; e.y = p[1]; e.snapshot()
        }
    }

    /** Franja que va a crecer (para el aviso rosa): profundidad 1-3, o 0 si no hay aviso. */
    val coralWarnDepth: Int
        get() {
            val o = boss as? Obispo ?: return 0
            return if (o.coralWarnT > 0f) o.coralSteps + 1 else 0
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

    /** Final C (§9): velaste junto a la lámpara dormida. Se apaga sola y baja el Farero. */
    fun startFinalC(lamp: LamparaMadre) {
        lamp.killed = true
        lamp.dead = true
        husk = floatArrayOf(lamp.x, lamp.y)
        finalC = true
        darkFight = true
        val f = createEnemy("farero", room.width / 2f, room.height - 56f, 1)
        newEnemies += f
        boss = f
        fx.burst(lamp.x, lamp.y, 20, Pal.STONE, 60f, 0.8f)
        showBanner("LA LÁMPARA NO TE VE. ALGUIEN SÍ.", 3.2f)
        sfx("roar")
        addShake(3f)
    }

    fun onBossPhase(n: Int) {
        sfx("roar")
        projectiles.clear()
        addShake(5f)
        if (boss is FareroAntiguo) {
            when (n) {
                2 -> showBanner("EL FARERO APAGA SU FAROL  ·  NO DEJES QUE TE ENCUENTRE", 2.6f)
                3 -> showBanner("LA FOSA RESPIRA  ·  VELA CUANDO TIEMBLE", 2.6f)
            }
            return
        }
        if (boss is Cartografo) {
            when (n) {
                2 -> showBanner("EL CARTÓGRAFO TE DIBUJA  ·  ROMPE EL CLON", 2.4f)
                3 -> { atrilesOn = false; showBanner("SE APAGAN TODAS LAS LUCES MENOS LA TUYA", 2.4f) }
            }
            return
        }
        if (boss is LamparaMadre) {
            when (n) {
                2 -> showBanner("LA LÁMPARA SE ABRE  ·  ALGO MIRA DESDE DENTRO", 2.4f)
                3 -> showBanner("LA LÁMPARA RECUERDA A LOS QUE VENCISTE", 2.4f)
                4 -> showBanner("LA LÁMPARA BEBE TU LUZ  ·  GOLPÉALA PARA RECUPERARLA", 2.6f)
            }
            return
        }
        if (boss is Obispo) {
            when (n) {
                2 -> showBanner("EL OBISPO SUBE AL ALTAR  ·  LOS VITRALES BRILLAN", 2.4f)
                3 -> showBanner("EL CORAL DESPIERTA", 2.2f)
            }
            return
        }
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
        if (n > 0 || e.def.luzMax > 0) n += stats.extraOrb
        repeat(n) {
            orbs += Orb(e.x, e.y, rng.nextFloat() * 140f - 70f, rng.nextFloat() * 140f - 70f, 1f)
        }
        if (stats.setFuego && e.burnT > 0f) {
            for (o in enemies) if (o !== e && !o.killed && dist(o.x, o.y, e.x, e.y) < 36f) o.burn(stats.burnTime)
        }
        if (stats.setMarea) player.refillDash(stats.dashCharges)
        // Pavesa: el quemado que muere quema al más cercano.
        if (stats.pavesa && e.burnT > 0f) {
            enemies.filter { it !== e && !it.killed && dist(it.x, it.y, e.x, e.y) < 64f }
                .minByOrNull { dist(it.x, it.y, e.x, e.y) }?.let { it.burn(stats.burnTime); fx.ember(it.x, it.y) }
        }
        // Concha de nácar: +1 Perla por élite.
        if (stats.nacre && e.isElite) { earnPerlas(1); fx.floater("+1 PERLA", e.x, e.y + 16f, Pal.SHELL) }
        // Brasero: 3 muertes en 3 s devuelven 4 de Luz.
        if (stats.brazier) {
            killTimes.addLast(time)
            while (killTimes.isNotEmpty() && time - killTimes.first() > 3f) killTimes.removeFirst()
            if (killTimes.size >= 3) { killTimes.clear(); gainLuz(4f); fx.floater("+4 BRASERO", player.cx, player.cy + 22f, Pal.LAMP) }
        }
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
            is Monaguillo -> Pal.SALADO
            is Campanero -> Pal.LILAC
            is Vela -> Pal.LAMP
            is Cofrade -> Pal.CORAL_PINK
            is Tinta -> Pal.INK_HI
            is Bibliotecario, is Copista, is Polilla -> Pal.PAPER
            is Anguila, is HijoDelFaro -> Pal.ROOT_HI
            is Vidriero -> Pal.GLASSY
            is OjoFosa -> Pal.MOLTEN
            else -> Pal.SHELL
        }
        fx.burst(e.x, e.y, 12, color, 90f, 0.45f)
        sfx("kill")
        addShake(1.5f)
    }

    private fun onBossKilled() {
        val b = boss ?: return
        sfx("boss_kill")
        projectiles.clear()
        pending.clear()
        brea = false
        dimT = 0f
        player.luz = player.luzMax          // el jefe rellena la Luz al máximo
        // Los fieles se apagan con su obispo.
        for (e in enemies) if (e !== b && !e.killed) { e.killed = true; e.dead = true; fx.burst(e.x, e.y, 8, Pal.SALADO, 70f, 0.4f) }
        val id = b.def.id
        fx.burst(b.x, b.y, 40, if (id == "obispo") Pal.CORAL_PINK else Pal.BREA, 160f, 1.0f)
        fx.burst(b.x, b.y, 30, Pal.LAMP_HI, 140f, 0.9f)
        fx.blast(b.x, b.y, 60f)
        addShake(6f)
        hitstop(10)
        val firstTime = id !in Meta.bosses
        Meta.bosses += id
        // Marea 10: al apagarse la lámpara, la criatura se suelta.
        if (id == "lampara" && marea >= 10 && !finalC) {
            earnPerlas(LAMPARA_PERLAS)
            if (firstTime) { memory = Story.memoryOf(id); memoryT = MEMORY_TIME }
            husk = floatArrayOf(b.x, b.y)
            darkFight = true
            val c = createEnemy("criatura", room.width / 2f, 48f, 1)
            newEnemies += c
            boss = c
            showBanner("LA LÁMPARA SE APAGA  ·  ALGO SE SUELTA DE ELLA", 3.2f)
            return
        }
        darkFight = false
        val perlas = when (id) {
            "farero" -> FARERO_PERLAS
            "criatura" -> CRIATURA_PERLAS
            "obispo" -> OBISPO_PERLAS
            "cartografo" -> CARTOGRAFO_PERLAS
            "lampara" -> LAMPARA_PERLAS
            else -> BOSS_PERLAS
        }
        earnPerlas(perlas)
        if (firstTime) {
            memory = Story.memoryOf(id)
            memoryT = MEMORY_TIME
        }
        val center = room.template.pedestals.firstOrNull()?.let { room.tileCenter(it[0], it[1]) }
            ?: floatArrayOf(room.width / 2f, room.height / 2f)
        cell.pedestals += exvotoPed(center, false, Rarity.EPICO)
        val end = room.nearestFloor(center[0], center[1] - 48f)
        showBanner(
            when (id) {
                "sirena" -> if (firstTime) "LA SIRENA SE HUNDE  ·  ARPÓN DESBLOQUEADO" else "LA SIRENA DE BREA SE HUNDE"
                "obispo" -> if (firstTime) "EL OBISPO CAE  ·  CAMPANA DESBLOQUEADA" else "EL OBISPO CORAL CAE"
                "cartografo" -> if (firstTime) "EL CARTÓGRAFO SE BORRA  ·  LA CARTÓGRAFA ABRE SU PUESTO" else "EL CARTÓGRAFO SE BORRA"
                "farero" -> "EL FARERO ANTIGUO DESCANSA"
                "criatura" -> "LA CRIATURA VUELVE A LA FOSA"
                else -> "LA LÁMPARA MADRE SE APAGA"
            }, 3.5f,
        )
        atrilesOn = true
        // La run sigue hasta la Raíz; la Lámpara Madre cierra el descenso.
        cell.pedestals += Pedestal(end[0], end[1], if (id == "lampara" || id == "farero" || id == "criatura") PedKind.FIN else PedKind.ESCALERA)
        saveRun()
    }

    /** Fin de la demo: la run termina en la puerta de la Sirena. */
    private fun endDemo() {
        mode = Mode.DEMO_END
        modeT = 0f
        projectiles.clear()
        sfx("victory")
        if (!test) {
            RunSave.clear()
            Meta.save()
            logRun("DEMO")
        }
    }

    fun gainLuz(v: Float) { player.luz = min(player.luzMax, player.luz + v) }

    /**
     * Daño al jugador: resta Luz, 0,8 s de invulnerabilidad y 3 frames de hit-stop.
     * [attack] nombra el ataque para la pantalla de muerte ("Embestida", "Pinza"…).
     */
    fun hurtPlayer(amount: Float, sx: Float, sy: Float, src: Enemy?, attack: String): Boolean {
        val p = player
        if (mode != Mode.PLAYING || p.invulnerable || demoEndT > 0f) return false
        var total = amount
        if (src?.has(Affix.HAMBRIENTO) == true) total += 3f
        if (stats.salmuera && src?.slowed == true) total *= 0.85f
        // Hambre de luz: +10 % de daño por cada 20 de tu Luz actual.
        if (src?.has(Affix.HAMBRE) == true) total *= 1f + 0.1f * (p.luz / 20f).toInt()
        lastCause = if (src != null) {
            val elite = src.affix?.let { a -> " (élite ${a.label}" + (src.affix2?.let { " y ${it.label}" } ?: "") + ")" } ?: ""
            "$attack · ${src.def.name}$elite"
        } else attack
        // Mecha de reserva: una vez por piso, no te apagas.
        if (p.luz - total <= 0f && stats.reserveWick && !wickUsed) {
            wickUsed = true
            total = p.luz - 1f
            fireBlast(p.cx, p.cy, 80f)
            showBanner("LA MECHA DE RESERVA PRENDE", 1.8f)
        }
        p.luz -= total
        hurtCount++; sfx("hurt")
        if (stats.net) for (e in enemies) if (!e.killed && dist(e.x, e.y, p.cx, p.cy) < 64f) e.slow(0.5f, 2f)
        if (stats.thorns && src != null && !src.killed && !src.isBoss) src.takeHit(this, 10f, p.cx, p.cy, HitKind.SLAM)
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
        lastCause = if (biome == Biome.CATEDRAL) "Caída por un vitral roto" else "Caída al agua"
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
        sfx("death")
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
        if (!test) {
            RunSave.clear()
            logRun(if (lastCause.startsWith("Abandonaste")) "ABANDONO" else "MUERTE")
        }
    }

    private fun logRun(result: String) {
        RunLog.add(
            RunLog.Entry(
                result, floorNum, if (result == "VICTORIA") (if (Meta.allBottles) "Final B" else "Final A") else lastCause,
                marea, baja, time, kills, hazUses, bossLuz.toList(), seedCode,
            )
        )
    }

    /** Radio de luz de Isla (§3): 48 + 1,2·Luz, con los modificadores del bioma y los exvotos. */
    /**
     * Velar: tras 0,4 s velando, los enemigos (no jefes) a más de 64 px te pierden el rastro y van
     * a buscarte donde te vieron. Te reencuentran si se acercan mucho, si los golpeas o, al dejar
     * de velar, si quedan dentro de tu luz.
     */
    private fun updateVeilTracking() {
        val p = player
        val light = playerLight()
        // Raíz: con un radio de 120 px o menos, solo te ven los que están dentro de tu luz.
        val raiz = biome == Biome.RAIZ && !inSanctuary
        if (raiz && !p.veiling && light > RAIZ_DETECT_RADIUS) {
            for (e in enemies) if (e.lost) { e.lost = false; fx.floater("!", e.x, e.y + e.hh + 8f, Pal.CORAL) }
            return
        }
        val hiding = (p.veiling && p.veilT >= Balance.VEIL_LOSE_TIME) || (raiz && light <= RAIZ_DETECT_RADIUS)
        for (e in enemies) {
            if (e.isBoss || !e.targetable) { e.lost = false; continue }
            val d = dist(e.x, e.y, p.cx, p.cy)
            if (!e.lost) {
                if (hiding && d > max(Balance.VEIL_LOSE_DIST, if (p.veiling) 0f else light) && e.state == EState.MOVE) {
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
        sfx("flash")
        fx.ring(p.cx, p.cy, r, Pal.LAMP_HI)
        fx.ring(p.cx, p.cy, r * 0.55f, Pal.CREAM)
        fx.burst(p.cx, p.cy, 14, Pal.LAMP_HI, 160f, 0.35f)
        addShake(2f)
        fx.floater("FOGONAZO", p.cx, p.cy + 18f, Pal.LAMP_HI)
        igniteAt(p.cx, p.cy, r)
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
        var r = (if (stats.eye) 168f else Balance.lightRadius(player.luz)) + stats.lightBonus
        val k = player.veilK
        if (k > 0f && r > Balance.VEIL_RADIUS) r += (Balance.VEIL_RADIUS - r) * k
        if (flashT > 0f) r += (if (cl.xiombo.faro.data.Settings.softFlashes) 30f else 90f) * (flashT / FLASH_TIME)
        if (brea) r *= 0.7f
        if (dimT > 0f) r *= 0.6f
        if (darkFight) r = min(r, 80f)
        if ((boss as? Obispo)?.inColumn(player.cx) == true) r += 30f
        if ((boss as? LamparaMadre)?.inColumn(player.cx) == true) r += 30f
        for (e in enemies) {
            if (e.has(Affix.BRILLANTE) && !e.killed) {
                val d = dist(e.x, e.y, player.cx, player.cy)
                if (d < 80f) r *= 0.6f + 0.4f * (d / 80f)
            }
        }
        return r
    }

    // ---------------------------------------------------------------- guardado

    /** Guarda la run: se llama al entrar en cada sala, al usar el Farol de vigilia y al pausar la app. */
    fun saveRun() {
        if (kind != Kind.RUN || mode != Mode.PLAYING || test) return
        val entered = enteredCell ?: cell
        RunSave.save(
            RunSave.Data(
                seed = seed, floorNum = floorNum, floorSeed = floorSeed,
                cellX = entered.gx, cellY = entered.gy, moved = enteredFrom?.name,
                luz = player.luz, luzMax = player.luzMax,
                weapon = player.weapon.name, weaponLevel = weaponLevel,
                owned = owned.map { it.id }, offered = offered.toList(),
                mapRevealed = mapRevealed, kills = kills, time = time, perlasRun = perlasRun,
                marea = marea, baja = baja, hazUses = hazUses, bossLuz = bossLuz.toList(), hero = Meta.hero,
                cells = map.all.map { c ->
                    RunSave.CellState(
                        c.gx, c.gy, c.visited, c.known, c.cleared, c.stocked, c.refugeUsed, c.iconShown,
                        c.openSides.map { it.name },
                        c.pedestals.map { p ->
                            RunSave.PedState(p.kind.name, p.x, p.y, p.exvoto?.id, p.price, p.taken, p.weapon?.name, p.metaId)
                        },
                    )
                },
            )
        )
    }

    /** F3: carta + Luz llena · F4: ir al jefe · F5: exvoto (en el Santuario: +50 Perlas y todas las armas) · F6: 150 de daño · F8: Isla recibe 40 · F9: siguiente sala (grandes, antesala y capilla primero) · F10: exvotos de Velar · F11: saltar al bioma siguiente. */
    private fun debugCommand(cmd: Int) {
        when (cmd) {
            3 -> { if (!inSanctuary) { revealMap(); player.luz = player.luzMax } }
            11 -> if (!inSanctuary) {
                // Saltar al primer piso del bioma siguiente (y del último, al Muelle).
                val next = Biome.entries[(biome.ordinal + 1) % Biome.entries.size]
                startFloor(next.firstFloor)
            }
            4 -> if (!inSanctuary) {
                if (floorNum != biome.lastFloor) startFloor(biome.lastFloor)
                val b = map.all.first { it.type == CellType.BOSS }
                enterCell(b, null)
            }
            5 -> if (inSanctuary) {
                Meta.perlas += 50
                Meta.bosses += "sirena"; Meta.bosses += "obispo"; Meta.unlocks += Meta.ANCLA; Meta.unlocks += Meta.GUARDESA
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
                // Salta a la siguiente sala del piso: primero grandes, antesala y capilla; luego el resto.
                val first = map.all.filter { it.anchor && ((it.partner != null) || it.type == CellType.ANTESALA || it.type == CellType.CAPILLA) }
                val rest = map.all.filter { it.anchor && it !in first && it.type != CellType.BOSS && it.type != CellType.START }
                val list = first + rest
                if (list.isNotEmpty()) enterCell(list[(list.indexOf(cell) + 1) % list.size], null)
            }
            8 -> if (!inSanctuary) hurtPlayer(40f, player.cx + 10f, player.cy, enemies.firstOrNull(), "Depuración (F8)")
        }
    }

    fun hitstop(frames: Int) { hitstopFrames = max(hitstopFrames, frames) }
    fun addShake(amount: Float) { shake = max(shake, amount) }

    fun clearBanner() { bannerT = 0f }

    private fun showBanner(text: String, seconds: Float = 2.2f) {
        banner = text
        bannerT = seconds
    }
}
