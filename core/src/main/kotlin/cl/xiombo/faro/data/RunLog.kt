package cl.xiombo.faro.data

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.JsonReader

/**
 * Registro local de runs (§12): resultado, piso, causa de muerte, Luz al entrar a cada jefe y uso
 * del Haz. Sirve para ajustar el equilibrio con datos reales. Se guardan las últimas 12.
 */
object RunLog {
    private const val PREFS = "faro-hundido"
    private const val KEY = "runlog"
    private const val MAX = 12

    class Entry(
        val result: String, val floor: Int, val cause: String, val marea: Int, val baja: Boolean,
        val time: Float, val kills: Int, val hazUses: Int, val bossLuz: List<Int>, val seed: String,
    )

    val entries = ArrayList<Entry>()

    fun load() {
        entries.clear()
        runCatching {
            val raw = Gdx.app.getPreferences(PREFS).getString(KEY, "")
            if (raw.isBlank()) return
            var e = JsonReader().parse(raw).child
            while (e != null) {
                val bl = ArrayList<Int>()
                var b = e.get("bl")?.child
                while (b != null) { bl += b.asInt(); b = b.next }
                entries += Entry(
                    e.getString("r"), e.getInt("f"), e.getString("c"), e.getInt("m"), e.getBoolean("b"),
                    e.getFloat("t"), e.getInt("k"), e.getInt("h"), bl, e.getString("s", ""),
                )
                e = e.next
            }
        }
    }

    fun add(entry: Entry) {
        entries.add(0, entry)
        while (entries.size > MAX) entries.removeAt(entries.size - 1)
        runCatching {
            fun q(s: String) = "\"" + s.replace("\\", "").replace("\"", "'") + "\""
            val json = entries.joinToString(",", "[", "]") {
                "{\"r\":${q(it.result)},\"f\":${it.floor},\"c\":${q(it.cause)},\"m\":${it.marea},\"b\":${it.baja}," +
                    "\"t\":${it.time},\"k\":${it.kills},\"h\":${it.hazUses},\"bl\":${it.bossLuz.joinToString(",", "[", "]")},\"s\":${q(it.seed)}}"
            }
            Gdx.app.getPreferences(PREFS).putString(KEY, json).flush()
        }
    }
}
