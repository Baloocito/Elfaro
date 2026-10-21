package cl.xiombo.faro.world

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

    /** Lados con puerta: vecinos normales, o la secreta si ya se abrió desde este lado. */
    fun doorsOf(c: Cell): Set<Dir> {
        val out = HashSet<Dir>()
        for (d in Dir.entries) {
            val n = neighbor(c, d) ?: continue
            when {
                c.type == CellType.SECRET -> if (d in c.openSides) out += d
                n.type == CellType.SECRET -> if (d.opposite in n.openSides) out += d
                else -> out += d
            }
        }
        return out
    }

    /** Lado con pared agrietada (hacia una secreta aún cerrada). */
    fun crackOf(c: Cell): Dir? {
        if (c.type == CellType.SECRET) return null
        for (d in Dir.entries) {
            val n = neighbor(c, d) ?: continue
            if (n.type == CellType.SECRET && d.opposite !in n.openSides) return d
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

        val others = deadEnds.drop(1).shuffled(rng)
        others[0].type = CellType.TREASURE
        others[1].type = CellType.SHOP

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
        if (candidates != null) {
            val s = candidates[rng.nextInt(candidates.size)]
            val secret = Cell(s[0], s[1], CellType.SECRET)
            grid[s[0]][s[1]] = secret
            return FloorMap(grid, start).also { m -> m.all.forEach { it.seed = rng.nextLong() } }
        }
        return map.also { m -> m.all.forEach { it.seed = rng.nextLong() } }
    }
}
