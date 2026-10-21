package cl.xiombo.faro.data

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.XmlReader

/** Sala hecha a mano en Tiled (§7). Origen de la rejilla abajo a la izquierda. */
class RoomTemplate(
    val name: String,
    val kind: String,
    val cols: Int,
    val rows: Int,
    val terrain: IntArray,
    val spawns: List<IntArray>,     // [tx, ty]
    val pedestals: List<IntArray>,  // [tx, ty]
) {
    fun terrainAt(tx: Int, ty: Int) = terrain[ty * cols + tx]
}

/**
 * Lee assets/rooms/index.json y los .tmx que lista. Se parsea el XML directamente
 * (capas "terreno" y "marcadores" en CSV), así no hace falta cargar el tileset como textura.
 */
object RoomTemplates {
    const val T_WALL = 1
    const val T_WATER = 2
    const val M_SPAWN = 3
    const val M_PEDESTAL = 4

    private val byKind = HashMap<String, List<RoomTemplate>>()

    fun load() {
        val index = JsonReader().parse(Gdx.files.internal("rooms/index.json"))
        var k = index.child
        while (k != null) {
            val list = ArrayList<RoomTemplate>()
            var f = k.child
            while (f != null) {
                list += parse(f.asString())
                f = f.next
            }
            byKind[k.name] = list
            k = k.next
        }
    }

    fun of(kind: String): List<RoomTemplate> = byKind[kind] ?: error("No hay plantillas de tipo $kind")

    private fun parse(file: String): RoomTemplate {
        val root = XmlReader().parse(Gdx.files.internal("rooms/$file"))
        val cols = root.getIntAttribute("width")
        val rows = root.getIntAttribute("height")
        var kind = ""
        var name = file
        root.getChildByName("properties")?.getChildrenByName("property")?.forEach { p ->
            when (p.getAttribute("name")) {
                "tipo" -> kind = p.getAttribute("value")
                "nombre" -> name = p.getAttribute("value")
            }
        }
        val terrain = IntArray(cols * rows)
        val marks = IntArray(cols * rows)
        for (layer in root.getChildrenByName("layer")) {
            val target = if (layer.getAttribute("name") == "terreno") terrain else marks
            val values = layer.getChildByName("data").text
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { (it.toLong() and 0x1FFFFFFFL).toInt() } // quita las banderas de giro de Tiled
            for (i in values.indices) {
                if (i >= cols * rows) break
                val x = i % cols
                val y = rows - 1 - i / cols
                target[y * cols + x] = values[i]
            }
        }
        val spawns = ArrayList<IntArray>()
        val pedestals = ArrayList<IntArray>()
        for (y in 0 until rows) for (x in 0 until cols) {
            when (marks[y * cols + x]) {
                M_SPAWN -> spawns += intArrayOf(x, y)
                M_PEDESTAL -> pedestals += intArrayOf(x, y)
            }
        }
        return RoomTemplate(name, kind, cols, rows, terrain, spawns, pedestals)
    }
}
