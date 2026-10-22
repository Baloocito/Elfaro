package cl.xiombo.faro.world

import cl.xiombo.faro.data.Balance.TILE
import cl.xiombo.faro.data.RoomTemplate
import cl.xiombo.faro.data.RoomTemplates
import kotlin.math.floor

/** Cuerpo con caja de colisión centrada en (x, y). Guarda la posición anterior para interpolar al dibujar. */
open class Body(var x: Float, var y: Float, val hw: Float, val hh: Float) {
    var px = x
    var py = y
    /** Los que caminan no pueden pisar el agua; lo que flota (medusas, la Sirena) sí. */
    open val blockedByWater: Boolean get() = true
    fun snapshot() { px = x; py = y }
    fun ix(alpha: Float) = px + (x - px) * alpha
    fun iy(alpha: Float) = py + (y - py) * alpha
}

enum class Dir(val dx: Int, val dy: Int) {
    LEFT(-1, 0), RIGHT(1, 0), DOWN(0, -1), UP(0, 1);

    val opposite: Dir
        get() = when (this) { LEFT -> RIGHT; RIGHT -> LEFT; DOWN -> UP; UP -> DOWN }
}

/** Puerta en un lado de la sala, en la subcelda (sx, sy). Las salas normales solo tienen la (0, 0). */
data class DoorSpec(val side: Dir, val sx: Int = 0, val sy: Int = 0)

/**
 * Una sala construida a partir de una plantilla de Tiled. Las puertas se abren solo hacia
 * salas vecinas; hacia una sala secreta se dibuja una pared agrietada que abren el Haz y la Campana.
 * Las salas grandes ocupan 2 celdas (52 × 14 o 26 × 28) y tienen una puerta por subcelda.
 */
class Room(val template: RoomTemplate, val doors: Set<DoorSpec>, val crack: DoorSpec?) {
    companion object {
        const val FLOOR = 0
        const val WALL = 1
        const val WATER = 2
        const val DOOR = 3
        const val CRACK = 4
        /** Vitral: se pisa; al pisarlo se agrieta y 1,2 s después se rompe en un pozo (HOLE). */
        const val GLASS = 5
        /** Pozo del vitral roto: funciona como el agua (caes, los que caminan no lo cruzan). */
        const val HOLE = 6
        /** Candelabro: bloquea como un muro bajo; se enciende al golpearlo. */
        const val CANDLE = 7
        /** Coral: muro temporal (al morir un Cofrade) o el borde que crece en la arena del Obispo. */
        const val CORAL = 8
        /** Archivo: estantería sobre rieles (bloquea y se desliza). */
        const val SHELF = 9
        /** Archivo: atril con lámpara verde (bloquea; luz fija de 48 px). */
        const val ATRIL = 10
        /** Raíz: vidrio fundido (se pisa; quema 4 de Luz por segundo). */
        const val MOLTEN = 11
        const val GLASS_BREAK_TIME = 1.2f
    }

    /**
     * Grupo de estanterías: se desliza [steps] tiles en (dx, dy) hacia sus rieles y luego vuelve.
     * [offset] = tiles desplazados ahora (0 = posición inicial).
     */
    class ShelfGroup(val tiles: MutableList<IntArray>, val dx: Int, val dy: Int) {
        val steps = 2
        var offset = 0
        /** Aviso: los rieles brillan (s que faltan para moverse). */
        var warnT = 0f
        /** Pasos que faltan en el movimiento actual y su sentido (+1 hacia los rieles, -1 de vuelta). */
        var pending = 0
        var sign = 1
        var stepT = 0f
    }

    val cols = template.cols
    val rows = template.rows
    /** Subceldas en horizontal y vertical (2 en las salas grandes). */
    val nx = if (cols >= 52) 2 else 1
    val ny = if (rows >= 28) 2 else 1
    private val subCols = cols / nx
    private val subRows = rows / ny
    val crackSide: Dir? get() = crack?.side
    val width = cols * TILE
    val height = rows * TILE
    private val tiles = IntArray(cols * rows)
    /** Vitral agrietado: segundos que faltan para romperse (< 0 = intacto). */
    private val crackT = FloatArray(cols * rows) { -1f }
    /** Coral temporal: segundos hasta desaparecer (< 0 = permanente o sin coral). */
    private val coralT = FloatArray(cols * rows) { -1f }
    private val coralUnder = IntArray(cols * rows)
    /** Tiles de riel (Archivo): se dibujan y brillan antes de que se mueva una estantería. */
    private val rail = BooleanArray(cols * rows)
    val shelves = ArrayList<ShelfGroup>()
    var hasMolten = false
        private set
    var doorsOpen = true
    /** Hay vitrales en la sala (para no recorrer la rejilla si no hace falta). */
    var hasGlass = false
        private set

    var lastRayTx = -1
        private set
    var lastRayTy = -1
        private set

    init {
        for (i in tiles.indices) {
            tiles[i] = when (template.terrain[i]) {
                RoomTemplates.T_WALL -> WALL
                RoomTemplates.T_WATER -> WATER
                RoomTemplates.T_GLASS -> GLASS.also { hasGlass = true }
                RoomTemplates.T_CANDLE -> CANDLE
                RoomTemplates.T_SHELF -> SHELF.also { rail[i] = true }
                RoomTemplates.T_RAIL -> FLOOR.also { rail[i] = true }
                RoomTemplates.T_ATRIL -> ATRIL
                RoomTemplates.T_MOLTEN -> MOLTEN.also { hasMolten = true }
                else -> FLOOR
            }
        }
        buildShelves()
        for (d in doors) carve(d, DOOR)
        if (crack != null) carve(crack, CRACK)
    }

    /** Tiles del hueco de una puerta (2 tiles en el centro de la pared de su subcelda). */
    fun gapTiles(d: DoorSpec): List<IntArray> {
        val ry = listOf(d.sy * subRows + subRows / 2 - 1, d.sy * subRows + subRows / 2)
        val cx = listOf(d.sx * subCols + subCols / 2 - 1, d.sx * subCols + subCols / 2)
        return when (d.side) {
            Dir.LEFT -> ry.map { intArrayOf(0, it) }
            Dir.RIGHT -> ry.map { intArrayOf(cols - 1, it) }
            Dir.DOWN -> cx.map { intArrayOf(it, 0) }
            Dir.UP -> cx.map { intArrayOf(it, rows - 1) }
        }
    }

    /** Subcelda en la que está un punto de la sala. */
    fun subAt(x: Float, y: Float): IntArray = intArrayOf(
        (x / (subCols * TILE)).toInt().coerceIn(0, nx - 1),
        (y / (subRows * TILE)).toInt().coerceIn(0, ny - 1),
    )

    private fun carve(d: DoorSpec, gapType: Int) {
        val side = d.side
        for (g in gapTiles(d)) {
            set(g[0], g[1], gapType)
            // Pasillo de 3 tiles hacia dentro siempre libre.
            for (k in 1..3) {
                val tx = g[0] - side.dx * k
                val ty = g[1] - side.dy * k
                set(tx, ty, FLOOR)
            }
        }
    }

    private fun set(tx: Int, ty: Int, v: Int) {
        if (tx in 0 until cols && ty in 0 until rows) tiles[ty * cols + tx] = v
    }

    fun tileAt(tx: Int, ty: Int): Int =
        if (tx < 0 || ty < 0 || tx >= cols || ty >= rows) WALL else tiles[ty * cols + tx]

    fun isDoorGap(tx: Int, ty: Int) = tileAt(tx, ty) == DOOR
    fun isCrack(tx: Int, ty: Int) = tileAt(tx, ty) == CRACK
    fun isWater(tx: Int, ty: Int) = tileAt(tx, ty).let { it == WATER || it == HOLE }
    fun isGlass(tx: Int, ty: Int) = tileAt(tx, ty) == GLASS
    fun crackOf(tx: Int, ty: Int): Float = if (tx < 0 || ty < 0 || tx >= cols || ty >= rows) -1f else crackT[ty * cols + tx]

    /** Pisar un vitral intacto lo agrieta. Devuelve true si empezó a agrietarse ahora. */
    fun stepOn(x: Float, y: Float): Boolean {
        val tx = floor(x / TILE).toInt()
        val ty = floor(y / TILE).toInt()
        if (tileAt(tx, ty) != GLASS) return false
        val i = ty * cols + tx
        if (crackT[i] >= 0f) return false
        crackT[i] = GLASS_BREAK_TIME
        return true
    }

    /** Avanza grietas y coral. Llama a [onBreak] por cada vitral que se rompe y [onCoralGone] por cada coral que desaparece. */
    fun update(dt: Float, onBreak: (Int, Int) -> Unit, onCoralGone: (Int, Int) -> Unit) {
        for (i in tiles.indices) {
            if (crackT[i] >= 0f) {
                crackT[i] -= dt
                if (crackT[i] < 0f && tiles[i] == GLASS) {
                    tiles[i] = HOLE
                    crackT[i] = -1f
                    onBreak(i % cols, i / cols)
                }
            }
            if (coralT[i] >= 0f) {
                coralT[i] -= dt
                if (coralT[i] < 0f) {
                    tiles[i] = coralUnder[i]
                    onCoralGone(i % cols, i / cols)
                }
            }
        }
    }

    /** Pone coral en un tile. [seconds] < 0 = para siempre. Solo sobre suelo o vitral. */
    fun putCoral(tx: Int, ty: Int, seconds: Float): Boolean = putTemp(tx, ty, CORAL, seconds)

    /** Tile temporal (coral, vidrio fundido del Vidriero). Al acabar vuelve a lo que había debajo. */
    fun putTemp(tx: Int, ty: Int, type: Int, seconds: Float): Boolean {
        if (tx !in 0 until cols || ty !in 0 until rows) return false
        val i = ty * cols + tx
        val t = tiles[i]
        if (t != FLOOR && t != GLASS && t != HOLE && !(t == MOLTEN && type == MOLTEN && coralT[i] >= 0f)) return false
        if (t != MOLTEN) coralUnder[i] = if (t == HOLE) HOLE else FLOOR
        tiles[i] = type
        coralT[i] = seconds
        crackT[i] = -1f
        if (type == MOLTEN) hasMolten = true
        return true
    }

    fun isRail(tx: Int, ty: Int) = tx in 0 until cols && ty in 0 until rows && rail[ty * cols + tx]
    fun isMoltenAt(x: Float, y: Float) = tileAt(floor(x / TILE).toInt(), floor(y / TILE).toInt()) == MOLTEN

    /** Tiles de un tipo (atriles, vidrio fundido…). */
    fun tilesOf(type: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        for (i in tiles.indices) if (tiles[i] == type) out += intArrayOf(i % cols, i / cols)
        return out
    }

    /**
     * Agrupa las estanterías conectadas y decide hacia dónde se deslizan: hacia el lado donde
     * tienen rieles libres (primero en horizontal, luego en vertical).
     */
    private fun buildShelves() {
        val seen = BooleanArray(tiles.size)
        for (i in tiles.indices) {
            if (tiles[i] != SHELF || seen[i]) continue
            val group = ArrayList<IntArray>()
            val stack = ArrayDeque<Int>()
            stack.addLast(i); seen[i] = true
            while (stack.isNotEmpty()) {
                val j = stack.removeLast()
                val x = j % cols
                val y = j / cols
                group += intArrayOf(x, y)
                for ((ax, ay) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val nx = x + ax
                    val ny = y + ay
                    if (nx !in 0 until cols || ny !in 0 until rows) continue
                    val k = ny * cols + nx
                    if (!seen[k] && tiles[k] == SHELF) { seen[k] = true; stack.addLast(k) }
                }
            }
            val minX = group.minOf { it[0] }
            val maxX = group.maxOf { it[0] }
            val minY = group.minOf { it[1] }
            val maxY = group.maxOf { it[1] }
            fun railAt(x: Int, y: Int) = isRail(x, y) && tileAt(x, y) == FLOOR
            val dir = when {
                group.all { railAt(maxX + 1, it[1]) } -> 1 to 0
                group.all { railAt(minX - 1, it[1]) } -> -1 to 0
                group.all { railAt(it[0], maxY + 1) } -> 0 to 1
                group.all { railAt(it[0], minY - 1) } -> 0 to -1
                else -> 0 to 0
            }
            if (dir.first != 0 || dir.second != 0) shelves += ShelfGroup(group, dir.first, dir.second)
        }
    }

    /** Desliza un grupo un tile en (dx·s, dy·s). Devuelve los tiles que pasó a ocupar. */
    fun shiftShelf(g: ShelfGroup, s: Int): List<IntArray> {
        val dx = g.dx * s
        val dy = g.dy * s
        for (t in g.tiles) set(t[0], t[1], FLOOR)
        val fresh = ArrayList<IntArray>()
        for (t in g.tiles) {
            t[0] += dx; t[1] += dy
            set(t[0], t[1], SHELF)
            fresh += intArrayOf(t[0], t[1])
        }
        return fresh
    }
    fun isWaterAt(x: Float, y: Float) = isWater(floor(x / TILE).toInt(), floor(y / TILE).toInt())

    fun isSolidTile(tx: Int, ty: Int, water: Boolean = false): Boolean = when (tileAt(tx, ty)) {
        WALL, CRACK, CANDLE, CORAL, SHELF, ATRIL -> true
        WATER, HOLE -> water
        DOOR -> !doorsOpen
        else -> false
    }

    fun isSolidAt(x: Float, y: Float, water: Boolean = false) =
        isSolidTile(floor(x / TILE).toInt(), floor(y / TILE).toInt(), water)

    fun boxHits(cx: Float, cy: Float, hw: Float, hh: Float, water: Boolean = false): Boolean {
        val x0 = floor((cx - hw) / TILE).toInt()
        val x1 = floor((cx + hw - 0.01f) / TILE).toInt()
        val y0 = floor((cy - hh) / TILE).toInt()
        val y1 = floor((cy + hh - 0.01f) / TILE).toInt()
        for (ty in y0..y1) for (tx in x0..x1) if (isSolidTile(tx, ty, water)) return true
        return false
    }

    /** ¿Toca la caja algún vitral (intacto o agrietado)? */
    fun boxTouchesGlass(cx: Float, cy: Float, hw: Float, hh: Float): Boolean {
        if (!hasGlass) return false
        val x0 = floor((cx - hw) / TILE).toInt()
        val x1 = floor((cx + hw - 0.01f) / TILE).toInt()
        val y0 = floor((cy - hh) / TILE).toInt()
        val y1 = floor((cy + hh - 0.01f) / TILE).toInt()
        for (ty in y0..y1) for (tx in x0..x1) if (isGlass(tx, ty)) return true
        return false
    }

    /** Tiles de candelabro (para crear sus objetos al entrar). */
    fun candleTiles(): List<IntArray> {
        val out = ArrayList<IntArray>()
        for (i in tiles.indices) if (tiles[i] == CANDLE) out += intArrayOf(i % cols, i / cols)
        return out
    }

    /** ¿Toca la caja algún tile de agua? */
    fun boxTouchesWater(cx: Float, cy: Float, hw: Float, hh: Float): Boolean {
        val x0 = floor((cx - hw) / TILE).toInt()
        val x1 = floor((cx + hw - 0.01f) / TILE).toInt()
        val y0 = floor((cy - hh) / TILE).toInt()
        val y1 = floor((cy + hh - 0.01f) / TILE).toInt()
        for (ty in y0..y1) for (tx in x0..x1) if (isWater(tx, ty)) return true
        return false
    }

    /** Física AABB sobre rejilla (§11, sin Box2D). Mueve eje por eje; devuelve true si chocó. */
    fun move(b: Body, dx: Float, dy: Float): Boolean {
        val water = b.blockedByWater
        var hit = false
        if (dx != 0f) {
            b.x += dx
            if (boxHits(b.x, b.y, b.hw, b.hh, water)) {
                hit = true
                b.x = if (dx > 0f) floor((b.x + b.hw) / TILE) * TILE - b.hw - 0.01f
                else (floor((b.x - b.hw) / TILE) + 1f) * TILE + b.hw + 0.01f
            }
        }
        if (dy != 0f) {
            b.y += dy
            if (boxHits(b.x, b.y, b.hw, b.hh, water)) {
                hit = true
                b.y = if (dy > 0f) floor((b.y + b.hh) / TILE) * TILE - b.hh - 0.01f
                else (floor((b.y - b.hh) / TILE) + 1f) * TILE + b.hh + 0.01f
            }
        }
        return hit
    }

    /** Distancia hasta el primer muro (el agua no frena el Haz). Guarda el tile golpeado. */
    fun rayLength(x: Float, y: Float, dx: Float, dy: Float, max: Float): Float {
        var t = 0f
        lastRayTx = -1
        lastRayTy = -1
        while (t < max) {
            val tx = floor((x + dx * t) / TILE).toInt()
            val ty = floor((y + dy * t) / TILE).toInt()
            if (isSolidTile(tx, ty)) {
                lastRayTx = tx
                lastRayTy = ty
                return t
            }
            t += 2f
        }
        return max
    }

    fun openCrack() {
        for (i in tiles.indices) if (tiles[i] == CRACK) tiles[i] = DOOR
    }

    fun tileCenter(tx: Int, ty: Int) = floatArrayOf(tx * TILE + TILE / 2f, ty * TILE + TILE / 2f)

    /** Punto de entrada al llegar por una puerta (dentro de la sala, frente a ella). */
    fun entryPoint(d: DoorSpec): FloatArray {
        val midY = (d.sy * subRows + subRows / 2) * TILE.toFloat() - 2f
        val midX = (d.sx * subCols + subCols / 2) * TILE.toFloat()
        return when (d.side) {
            Dir.LEFT -> floatArrayOf(TILE * 1.6f, midY)
            Dir.RIGHT -> floatArrayOf(width - TILE * 1.6f, midY)
            Dir.DOWN -> floatArrayOf(midX, TILE * 1.4f)
            Dir.UP -> floatArrayOf(midX, height - TILE * 2.2f)
        }
    }

    /** Centro de la subcelda (sx, sy), a la altura de las puertas laterales. */
    fun subCenter(sx: Int, sy: Int) = floatArrayOf(
        (sx * subCols + subCols / 2) * TILE.toFloat(),
        (sy * subRows + subRows / 2) * TILE.toFloat() - 2f,
    )

    /** Tile libre más cercano al punto (para colocar objetos). */
    fun nearestFloor(x: Float, y: Float): FloatArray {
        val cx = floor(x / TILE).toInt()
        val cy = floor(y / TILE).toInt()
        for (r in 0..8) for (dy in -r..r) for (dx in -r..r) {
            val tx = cx + dx
            val ty = cy + dy
            if (tileAt(tx, ty) == FLOOR) return tileCenter(tx, ty)
        }
        return floatArrayOf(x, y)
    }
}
