package cl.xiombo.faro.world

import cl.xiombo.faro.data.Balance.TILE
import kotlin.math.floor
import kotlin.random.Random

/** Cuerpo con caja de colisión centrada en (x, y). Guarda la posición anterior para interpolar al dibujar. */
open class Body(var x: Float, var y: Float, val hw: Float, val hh: Float) {
    var px = x
    var py = y
    fun snapshot() { px = x; py = y }
    fun ix(alpha: Float) = px + (x - px) * alpha
    fun iy(alpha: Float) = py + (y - py) * alpha
}

/**
 * Sala normal de 26 × 14 tiles (§7). En F0 la distribución es procedural y simple:
 * muros de borde, algunos pilares y una puerta a la derecha que se abre al limpiar la sala.
 * En F1 esto se reemplaza por plantillas hechas en Tiled.
 */
class Room(seed: Long) {
    companion object {
        const val COLS = 26
        const val ROWS = 14
        const val WIDTH = COLS * TILE
        const val HEIGHT = ROWS * TILE
        val DOOR_ROWS = 6..7
    }

    private val solid = BooleanArray(COLS * ROWS)
    var doorsOpen = false

    init {
        for (x in 0 until COLS) { set(x, 0); set(x, ROWS - 1) }
        for (y in 0 until ROWS) { set(0, y); set(COLS - 1, y) }

        val rng = Random(seed)
        val target = 3 + rng.nextInt(4)
        var placed = 0
        var tries = 0
        while (placed < target && tries < 80) {
            tries++
            val w = 1 + rng.nextInt(2)
            val h = 1 + rng.nextInt(2)
            val x = 3 + rng.nextInt(COLS - 6 - w)
            val y = 2 + rng.nextInt(ROWS - 4 - h)
            if (x < 5) continue                                        // zona de entrada libre
            if (x + w > COLS - 5 && y + h > 5 && y < 9) continue        // pasillo hacia la puerta libre
            var ok = true
            for (yy in y - 1..y + h) for (xx in x - 1..x + w) {
                if (xx in 1 until COLS - 1 && yy in 1 until ROWS - 1 && solid[yy * COLS + xx]) ok = false
            }
            if (!ok) continue
            for (yy in y until y + h) for (xx in x until x + w) set(xx, yy)
            placed++
        }
    }

    private fun set(x: Int, y: Int) { solid[y * COLS + x] = true }

    fun isDoor(tx: Int, ty: Int) = tx == COLS - 1 && ty in DOOR_ROWS
    fun isEntrance(tx: Int, ty: Int) = tx == 0 && ty in DOOR_ROWS

    fun isSolidTile(tx: Int, ty: Int): Boolean {
        if (tx < 0 || ty < 0 || tx >= COLS || ty >= ROWS) return true
        if (doorsOpen && isDoor(tx, ty)) return false
        return solid[ty * COLS + tx]
    }

    fun isSolidAt(x: Float, y: Float) = isSolidTile(floor(x / TILE).toInt(), floor(y / TILE).toInt())

    fun boxHits(cx: Float, cy: Float, hw: Float, hh: Float): Boolean {
        val x0 = floor((cx - hw) / TILE).toInt()
        val x1 = floor((cx + hw - 0.01f) / TILE).toInt()
        val y0 = floor((cy - hh) / TILE).toInt()
        val y1 = floor((cy + hh - 0.01f) / TILE).toInt()
        for (ty in y0..y1) for (tx in x0..x1) if (isSolidTile(tx, ty)) return true
        return false
    }

    /** Física AABB sobre rejilla (§11, sin Box2D). Mueve eje por eje; devuelve true si chocó. */
    fun move(b: Body, dx: Float, dy: Float): Boolean {
        var hit = false
        if (dx != 0f) {
            b.x += dx
            if (boxHits(b.x, b.y, b.hw, b.hh)) {
                hit = true
                b.x = if (dx > 0f) floor((b.x + b.hw) / TILE) * TILE - b.hw - 0.01f
                else (floor((b.x - b.hw) / TILE) + 1f) * TILE + b.hw + 0.01f
            }
        }
        if (dy != 0f) {
            b.y += dy
            if (boxHits(b.x, b.y, b.hw, b.hh)) {
                hit = true
                b.y = if (dy > 0f) floor((b.y + b.hh) / TILE) * TILE - b.hh - 0.01f
                else (floor((b.y - b.hh) / TILE) + 1f) * TILE + b.hh + 0.01f
            }
        }
        return hit
    }

    /** Distancia hasta el primer muro siguiendo una dirección (para el Haz). */
    fun rayLength(x: Float, y: Float, dx: Float, dy: Float, max: Float): Float {
        var t = 0f
        while (t < max) {
            if (isSolidAt(x + dx * t, y + dy * t)) return t
            t += 2f
        }
        return max
    }
}
