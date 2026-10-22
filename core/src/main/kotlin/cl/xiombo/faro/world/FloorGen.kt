package cl.xiombo.faro.world

import cl.xiombo.faro.data.Biome

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

enum class CellType(val label: String) {
    START("Inicio"),
    COMBAT("Combate"),
    ELITE("Élite"),
    SHOP("Tienda"),
    TREASURE("Tesoro"),
    REFUGE("Refugio"),
    STAIRS("Escalera"),
    BOSS("Jefe"),
    SECRET("Secreta"),
    ANTESALA("Antesala"),
    CAPILLA("Capilla"),
    SANCTUARY("Santuario"),
}

/** Estado persistente de una sala del piso (se conserva al salir y volver). */
class Cell(val gx: Int, val gy: Int, var type: CellType) {
    var dist = 0
    var visited = false
    var known = false
    var cleared = false
    var stocked = false
    var refugeUsed = false
    var templateIndex = 0
    var seed = 0L
    val pedestals = ArrayList<Pedestal>()
    /** Para la sala secreta: lados por los que ya se abrió (vistos desde la secreta). */
    val openSides = HashSet<Dir>()
    /** Sala grande: la otra celda que ocupa. El ancla es la de la izquierda o la de abajo. */
    var partner: Cell? = null
    var anchor = true
    /** La Cartógrafa (meta) deja ver el tipo de las salas especiales desde el inicio del piso. */
    var iconShown = false

    val isCombat get() = type == CellType.COMBAT || type == CellType.ELITE || type == CellType.STAIRS || type == CellType.BOSS
}

class FloorMap(val cells: Array<Array<Cell?>>, val start: Cell) {
    companion object {
        const val GW = 9
        const val GH = 7
    }

    val all: List<Cell> = cells.flatMap { col -> col.filterNotNull() }

    operator fun get(x: Int, y: Int): Cell? = if (x in 0 until GW && y in 0 until GH) cells[x][y] else null

    fun neighbor(c: Cell, d: Dir): Cell? = get(c.gx + d.dx, c.gy + d.dy)

    /** La celda que manda en una sala grande (la de la izquierda o la de abajo). */
    fun anchorOf(c: Cell): Cell = c.partner?.takeIf { !c.anchor } ?: c

    /** Celdas de una sala con su subcelda: (celda, sx, sy). */
    fun subsOf(anchor: Cell): List<Triple<Cell, Int, Int>> {
        val p = anchor.partner ?: return listOf(Triple(anchor, 0, 0))
        return if (p.gx > anchor.gx) listOf(Triple(anchor, 0, 0), Triple(p, 1, 0))
        else listOf(Triple(anchor, 0, 0), Triple(p, 0, 1))
    }

    fun cellAtSub(anchor: Cell, sx: Int, sy: Int): Cell =
        subsOf(anchor).firstOrNull { it.second == sx && it.third == sy }?.first ?: anchor

    /** Puertas: vecinos normales, o la secreta si ya se abrió desde este lado. */
    fun doorsOf(anchor: Cell): Set<DoorSpec> {
        val out = HashSet<DoorSpec>()
        for ((c, sx, sy) in subsOf(anchor)) for (d in Dir.entries) {
            val n = neighbor(c, d) ?: continue
            if (n === anchor.partner || n === anchor) continue
            when {
                c.type == CellType.SECRET -> if (d in c.openSides) out += DoorSpec(d, sx, sy)
                n.type == CellType.SECRET -> if (d.opposite in n.openSides) out += DoorSpec(d, sx, sy)
                else -> out += DoorSpec(d, sx, sy)
            }
        }
        return out
    }

    /** Pared agrietada hacia una secreta aún cerrada. */
    fun crackOf(anchor: Cell): DoorSpec? {
        if (anchor.type == CellType.SECRET) return null
        for ((c, sx, sy) in subsOf(anchor)) for (d in Dir.entries) {
            val n = neighbor(c, d) ?: continue
            if (n.type == CellType.SECRET && d.opposite !in n.openSides) return DoorSpec(d, sx, sy)
        }
        return null
    }
}

/**
 * Generación de pisos (§7): rejilla de 9 × 7, expansión en anchura desde (4, 3) con un 50 %
 * de probabilidad por vecino, descartando los que ya tocan más de una sala (ramas, no bloques).
 */
object FloorGen {
    fun roomCount(floor: Int) = min(20, 7 + (1.5f * floor).roundToInt())

    /** El Santuario: un piso de una sola sala. */
    fun sanctuary(): FloorMap {
        val grid = Array(FloorMap.GW) { arrayOfNulls<Cell>(FloorMap.GH) }
        val c = Cell(4, 3, CellType.SANCTUARY)
        grid[4][3] = c
        return FloorMap(grid, c)
    }

    fun generate(seed: Long, floor: Int, hasBoss: Boolean): FloorMap {
        val rng = Random(seed)
        val target = roomCount(floor)
        repeat(500) {
            val map = attempt(rng, target, floor, hasBoss)
            if (map != null) return map
        }
        error("No se pudo generar el piso $floor")
    }

    private fun attempt(rng: Random, target: Int, floor: Int, hasBoss: Boolean): FloorMap? {
        val grid = Array(FloorMap.GW) { arrayOfNulls<Cell>(FloorMap.GH) }
        fun filled(x: Int, y: Int) = x in 0 until FloorMap.GW && y in 0 until FloorMap.GH && grid[x][y] != null
        fun filledNeighbors(x: Int, y: Int) = Dir.entries.count { filled(x + it.dx, y + it.dy) }

        val start = Cell(4, 3, CellType.START)
        grid[4][3] = start
        val queue = ArrayDeque<Cell>()
        queue.addLast(start)
        var count = 1
        while (queue.isNotEmpty() && count < target) {
            val c = queue.removeFirst()
            for (d in Dir.entries.shuffled(rng)) {
                if (count >= target) break
                val nx = c.gx + d.dx
                val ny = c.gy + d.dy
                if (nx !in 0 until FloorMap.GW || ny !in 0 until FloorMap.GH || grid[nx][ny] != null) continue
                if (filledNeighbors(nx, ny) > 1) continue
                if (rng.nextFloat() < 0.5f) continue
                val n = Cell(nx, ny, CellType.COMBAT)
                grid[nx][ny] = n
                queue.addLast(n)
                count++
            }
        }
        if (count != target) return null

        val map = FloorMap(grid, start)
        // Distancias BFS desde el inicio.
        val seen = HashSet<Cell>()
        val q = ArrayDeque<Cell>()
        q.addLast(start)
        seen += start
        while (q.isNotEmpty()) {
            val c = q.removeFirst()
            for (d in Dir.entries) {
                val n = map.neighbor(c, d) ?: continue
                if (n in seen) continue
                n.dist = c.dist + 1
                seen += n
                q.addLast(n)
            }
        }
        fun neighborsOf(c: Cell) = Dir.entries.count { map.neighbor(c, it) != null }

        val deadEnds = map.all.filter { it.type != CellType.START && neighborsOf(it) == 1 }.sortedByDescending { it.dist }
        if (deadEnds.size < 3) return null
        val exit = deadEnds[0]
        if (exit.dist < 3) return null
        exit.type = if (hasBoss) CellType.BOSS else CellType.STAIRS
        // Antes del jefe, siempre una antesala tranquila con el Farol de vigilia.
        if (hasBoss) Dir.entries.mapNotNull { map.neighbor(exit, it) }.firstOrNull()?.type = CellType.ANTESALA

        val others = deadEnds.drop(1).shuffled(rng)
        others[0].type = CellType.TREASURE
        others[1].type = CellType.SHOP
        // Catedral: la Capilla de la ofrenda ocupa otro callejón sin salida si lo hay.
        if (Biome.of(floor) == Biome.CATEDRAL && others.size >= 3) others[2].type = CellType.CAPILLA

        // Refugio a media distancia.
        val half = exit.dist / 2f
        val refuge = map.all.filter { it.type == CellType.COMBAT && it.dist >= 1 }
            .minByOrNull { abs(it.dist - half) } ?: return null
        refuge.type = CellType.REFUGE

        // Élites: 1 + ⌊f/3⌋ salas a distancia 2 o más.
        val eliteCandidates = map.all.filter { it.type == CellType.COMBAT && it.dist >= 2 }.shuffled(rng)
        for (c in eliteCandidates.take(1 + floor / 3)) c.type = CellType.ELITE

        // Sala secreta: celda vacía que toque 3 salas o más (si no hay, 2), lejos del jefe.
        val empty = ArrayList<IntArray>()
        for (x in 0 until FloorMap.GW) for (y in 0 until FloorMap.GH) if (grid[x][y] == null) empty += intArrayOf(x, y)
        fun touchesExit(x: Int, y: Int) = Dir.entries.any { map[x + it.dx, y + it.dy] === exit }
        val candidates = (3 downTo 2).asSequence()
            .map { need -> empty.filter { filledNeighbors(it[0], it[1]) >= need && !touchesExit(it[0], it[1]) } }
            .firstOrNull { it.isNotEmpty() }
        // Salas grandes: une pares de celdas de combate vecinas (1 en el piso 1, hasta 2 después).
        val bigTarget = min(2, 1 + floor / 2)
        val pairs = ArrayList<Pair<Cell, Cell>>()
        fun mergeable(c: Cell) = c.type == CellType.COMBAT || c.type == CellType.ELITE
        for (c in map.all) {
            if (!mergeable(c)) continue
            for (d in listOf(Dir.RIGHT, Dir.UP)) {
                val n = map.neighbor(c, d) ?: continue
                if (mergeable(n)) pairs += c to n
            }
        }
        // Cada hueco de sala grande sale con un 65 %; luego se cubre con pares que no se pisen.
        // Una élite unida a una de combate hace élite toda la sala grande.
        val want = (0 until bigTarget).count { rng.nextFloat() >= 0.35f }
        var made = 0
        for ((a, b) in pairs.shuffled(rng)) {
            if (made >= want) break
            if (a.partner != null || b.partner != null) continue
            if (a.type == CellType.ELITE || b.type == CellType.ELITE) { a.type = CellType.ELITE; b.type = CellType.ELITE }
            a.partner = b; a.anchor = true
            b.partner = a; b.anchor = false
            made++
        }

        val result = if (candidates != null) {
            val s = candidates[rng.nextInt(candidates.size)]
            grid[s[0]][s[1]] = Cell(s[0], s[1], CellType.SECRET)
            FloorMap(grid, start)
        } else map
        result.all.forEach { it.seed = rng.nextLong() }
        return result
    }
}
