package cl.xiombo.faro.data

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.JsonReader

/** Enemigo definido por datos (§11). Los valores salen de assets/data/enemies.json. */
class EnemyDef(
    val id: String,
    val name: String,
    val biome: String,
    val hp: Float,
    val dmg: Float,
    val speed: Float,
    val cost: Int,
    val hw: Float,
    val hh: Float,
    val luzMin: Int,
    val luzMax: Int,
    val range: Float,
    val telegraph: Float,
    val cooldown: Float,
)

object EnemyDefs {
    var all: Map<String, EnemyDef> = emptyMap()
        private set

    fun load() {
        val root = JsonReader().parse(Gdx.files.internal("data/enemies.json"))
        val map = LinkedHashMap<String, EnemyDef>()
        var e = root.child
        while (e != null) {
            val body = e.get("body")
            val drop = e.get("luzDrop")
            val atk = e.get("attack")
            val def = EnemyDef(
                id = e.getString("id"),
                name = e.getString("name"),
                biome = e.getString("biome"),
                hp = e.getFloat("hp"),
                dmg = e.getFloat("dmg"),
                speed = e.getFloat("speed"),
                cost = e.getInt("cost"),
                hw = body.getFloat(0) / 2f,
                hh = body.getFloat(1) / 2f,
                luzMin = drop.getInt(0),
                luzMax = drop.getInt(1),
                range = atk.getFloat("range"),
                telegraph = atk.getFloat("telegraph"),
                cooldown = atk.getFloat("cooldown"),
            )
            map[def.id] = def
            e = e.next
        }
        all = map
    }

    operator fun get(id: String): EnemyDef = all.getValue(id)
}
