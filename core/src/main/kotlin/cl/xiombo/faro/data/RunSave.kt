package cl.xiombo.faro.data

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue

/**
 * Guardado de la run en curso (§15). Se escribe al entrar en cada sala y al pausar la app,
 * y se borra al morir, al ganar o al abandonar: sirve para no perder una run por una llamada,
 * no para repetir una sala.
 *
 * El piso no se guarda entero: se regenera desde su semilla y se le aplica el estado de cada celda.
 */
object RunSave {
    private const val PREFS = "faro-hundido"
    private const val KEY = "run.save"
    private const val VERSION = 2

    class PedState(
        val kind: String, val x: Float, val y: Float, val exvoto: String?, val price: Int,
        val taken: Boolean, val weapon: String?,
    )

    class CellState(
        val gx: Int, val gy: Int, val visited: Boolean, val known: Boolean, val cleared: Boolean,
        val stocked: Boolean, val refugeUsed: Boolean, val iconShown: Boolean,
        val openSides: List<String>, val peds: List<PedState>,
    )

    class Data(
        val seed: Long, val floorNum: Int, val floorSeed: Long,
        val cellX: Int, val cellY: Int, val moved: String?,
        val luz: Float, val luzMax: Float, val weapon: String, val weaponLevel: Int,
        val owned: List<String>, val offered: List<String>,
        val mapRevealed: Boolean, val kills: Int, val time: Float, val perlasRun: Int,
        val cells: List<CellState>,
    )

    fun save(d: Data) {
        val sb = StringBuilder()
        sb.append('{')
        kv(sb, "v", VERSION); kv(sb, "seed", d.seed.toString()); kv(sb, "floor", d.floorNum)
        kv(sb, "floorSeed", d.floorSeed.toString()); kv(sb, "cx", d.cellX); kv(sb, "cy", d.cellY)
        kv(sb, "moved", d.moved ?: ""); kv(sb, "luz", d.luz); kv(sb, "luzMax", d.luzMax)
        kv(sb, "weapon", d.weapon); kv(sb, "wl", d.weaponLevel)
        kv(sb, "map", d.mapRevealed); kv(sb, "kills", d.kills); kv(sb, "time", d.time); kv(sb, "perlas", d.perlasRun)
        sb.append("\"owned\":").append(d.owned.joinToString(",", "[", "]") { "\"$it\"" }).append(',')
        sb.append("\"offered\":").append(d.offered.joinToString(",", "[", "]") { "\"$it\"" }).append(',')
        sb.append("\"cells\":[")
        d.cells.forEachIndexed { i, c ->
            if (i > 0) sb.append(',')
            sb.append('{')
            kv(sb, "gx", c.gx); kv(sb, "gy", c.gy); kv(sb, "vi", c.visited); kv(sb, "kn", c.known)
            kv(sb, "cl", c.cleared); kv(sb, "st", c.stocked); kv(sb, "rf", c.refugeUsed); kv(sb, "ic", c.iconShown)
            sb.append("\"open\":").append(c.openSides.joinToString(",", "[", "]") { "\"$it\"" }).append(',')
            sb.append("\"peds\":[")
            c.peds.forEachIndexed { j, p ->
                if (j > 0) sb.append(',')
                sb.append('{')
                kv(sb, "k", p.kind); kv(sb, "x", p.x); kv(sb, "y", p.y); kv(sb, "e", p.exvoto ?: "")
                kv(sb, "p", p.price); kv(sb, "w", p.weapon ?: ""); kv(sb, "t", p.taken, last = true)
                sb.append('}')
            }
            sb.append("]}")
        }
        sb.append("]}")
        runCatching { Gdx.app.getPreferences(PREFS).putString(KEY, sb.toString()).flush() }
    }

    fun load(): Data? = runCatching {
        val raw = Gdx.app.getPreferences(PREFS).getString(KEY, "")
        if (raw.isBlank()) return null
        val j = JsonReader().parse(raw)
        if (j.getInt("v", 0) != VERSION) return null
        Data(
            seed = j.getString("seed").toLong(),
            floorNum = j.getInt("floor"),
            floorSeed = j.getString("floorSeed").toLong(),
            cellX = j.getInt("cx"), cellY = j.getInt("cy"),
            moved = j.getString("moved", "").ifBlank { null },
            luz = j.getFloat("luz"), luzMax = j.getFloat("luzMax"),
            weapon = j.getString("weapon"), weaponLevel = j.getInt("wl"),
            owned = strings(j.get("owned")), offered = strings(j.get("offered")),
            mapRevealed = j.getBoolean("map"), kills = j.getInt("kills"), time = j.getFloat("time"),
            perlasRun = j.getInt("perlas"),
            cells = list(j.get("cells")).map { c ->
                CellState(
                    c.getInt("gx"), c.getInt("gy"), c.getBoolean("vi"), c.getBoolean("kn"), c.getBoolean("cl"),
                    c.getBoolean("st"), c.getBoolean("rf"), c.getBoolean("ic"), strings(c.get("open")),
                    list(c.get("peds")).map { p ->
                        PedState(
                            p.getString("k"), p.getFloat("x"), p.getFloat("y"), p.getString("e", "").ifBlank { null },
                            p.getInt("p"), p.getBoolean("t"), p.getString("w", "").ifBlank { null },
                        )
                    },
                )
            },
        )
    }.getOrNull()

    fun exists() = runCatching { Gdx.app.getPreferences(PREFS).getString(KEY, "").isNotBlank() }.getOrDefault(false)

    fun clear() {
        runCatching { Gdx.app.getPreferences(PREFS).remove(KEY); Gdx.app.getPreferences(PREFS).flush() }
    }

    private fun list(v: JsonValue?): List<JsonValue> {
        val out = ArrayList<JsonValue>()
        var c = v?.child
        while (c != null) { out += c; c = c.next }
        return out
    }

    private fun strings(v: JsonValue?) = list(v).map { it.asString() }

    private fun kv(sb: StringBuilder, k: String, v: Any, last: Boolean = false) {
        sb.append('"').append(k).append("\":")
        when (v) {
            is String -> sb.append('"').append(v.replace("\\", "").replace("\"", "")).append('"')
            else -> sb.append(v.toString())
        }
        if (!last) sb.append(',')
    }
}
