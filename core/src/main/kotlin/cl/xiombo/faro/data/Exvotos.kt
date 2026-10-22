package cl.xiombo.faro.data

import com.badlogic.gdx.graphics.Color
import kotlin.random.Random

enum class Tag(val label: String, val color: Color) {
    FUEGO("Fuego", Pal.LAMP_DIM),
    SAL("Sal", Pal.CREAM),
    MAREA("Marea", Pal.TIDE),
    HUESO("Hueso", Pal.SHELL),
    NINGUNA("Ninguna", Pal.MEDUSA),
}

enum class Rarity(val label: String, val price: Int, val color: Color) {
    COMUN("Común", 15, Pal.CREAM),
    RARO("Raro", 25, Pal.RARE),
    EPICO("Épico", 40, Pal.EPIC),
    LEGENDARIO("Legendario", 60, Pal.LAMP),
}

class ExvotoDef(val id: String, val name: String, val tag: Tag, val rarity: Rarity, val desc: String)

/**
 * Exvotos (§8): los 12 del documento + Ceniza de faro, Costra de sal y Vértebra suelta, más los
 * tres que convierten Velar en ataque (Pabilo corto, Sal en los ojos, Marea quieta).
 * Tres de la misma etiqueta activan su conjunto.
 */
object Exvotos {
    val all = listOf(
        ExvotoDef("mecha_trenzada", "Mecha trenzada", Tag.FUEGO, Rarity.COMUN, "La Quemadura dura +1 s."),
        ExvotoDef("ceniza_faro", "Ceniza de faro", Tag.FUEGO, Rarity.COMUN, "El Haz cuesta 6 de Luz en vez de 8."),
        ExvotoDef("corazon_vela", "Corazón de vela", Tag.FUEGO, Rarity.RARO, "Los enemigos quemados sueltan +1 Luz."),
        ExvotoDef("pabilo_corto", "Pabilo corto", Tag.FUEGO, Rarity.COMUN, "Al soltar Velar tras 0,5 s, un Fogonazo hace 10 de daño a 80 px (cada 6 s)."),
        ExvotoDef("lengua_telmo", "Lengua de San Telmo", Tag.FUEGO, Rarity.EPICO, "El Haz deja un rastro de fuego durante 3 s."),
        ExvotoDef("grano_sal", "Grano de sal", Tag.SAL, Rarity.COMUN, "+8 % de daño contra enemigos ralentizados."),
        ExvotoDef("costra_sal", "Costra de sal", Tag.SAL, Rarity.COMUN, "El 3.er golpe del Farol ralentiza un 30 % durante 1,5 s."),
        ExvotoDef("sal_ojos", "Sal en los ojos", Tag.SAL, Rarity.RARO, "Al soltar Velar tras 0,5 s, un Fogonazo aturde 1,2 s a 80 px (cada 6 s)."),
        ExvotoDef("estatua_sal", "Estatua de sal", Tag.SAL, Rarity.RARO, "El Destello deja una estatua que atrae enemigos 1,5 s."),
        ExvotoDef("rosa_vientos", "Rosa de los vientos", Tag.MAREA, Rarity.COMUN, "+10 % de velocidad mientras no atacas."),
        ExvotoDef("marea_quieta", "Marea quieta", Tag.MAREA, Rarity.COMUN, "Velando te mueves a velocidad normal y el Fogonazo llega a 104 px."),
        ExvotoDef("caracola", "Caracola", Tag.MAREA, Rarity.RARO, "Segunda carga de Destello."),
        ExvotoDef("ancla_plata", "Ancla de plata", Tag.MAREA, Rarity.EPICO, "Los empujes contra una pared hacen 25 de daño."),
        ExvotoDef("diente_naufrago", "Diente de náufrago", Tag.HUESO, Rarity.COMUN, "+4 de daño en el primer golpe a cada enemigo."),
        ExvotoDef("vertebra_suelta", "Vértebra suelta", Tag.HUESO, Rarity.COMUN, "+15 % de daño contra élites y jefes."),
        ExvotoDef("rosario_vertebras", "Rosario de vértebras", Tag.HUESO, Rarity.RARO, "Cada 10 muertes, +2 de Luz máxima durante la run."),
        ExvotoDef("ojo_farero", "Ojo del farero", Tag.NINGUNA, Rarity.LEGENDARIO, "Tu radio de luz ya no depende de la Luz: siempre es 168 px."),
        ExvotoDef("lampara_gemela", "Lámpara gemela", Tag.NINGUNA, Rarity.LEGENDARIO, "Un farol flotante te sigue y ataca. Cuesta 1 Luz cada 5 s."),
    )

    val setBonus = mapOf(
        Tag.FUEGO to "La Quemadura se propaga al morir el objetivo.",
        Tag.SAL to "Tus golpes ralentizan un 20 % durante 1 s.",
        Tag.MAREA to "El Destello se recarga al matar.",
        Tag.HUESO to "Los enemigos con menos del 15 % de vida mueren de un golpe.",
    )

    /** Probabilidad por rareza en el bioma 1 (§8): 65 / 28 / 6 / 1. */
    private val weightsBiome1 = intArrayOf(65, 28, 6, 1)

    fun roll(rng: Random, exclude: Set<String>, minRarity: Rarity = Rarity.COMUN): ExvotoDef? {
        val available = all.filter { it.id !in exclude }
        if (available.isEmpty()) return null
        val total = weightsBiome1.sum()
        var r = rng.nextInt(total)
        var rarity = Rarity.COMUN
        for (i in weightsBiome1.indices) {
            if (r < weightsBiome1[i]) { rarity = Rarity.entries[i]; break }
            r -= weightsBiome1[i]
        }
        if (rarity.ordinal < minRarity.ordinal) rarity = minRarity
        // Si no quedan de esa rareza, se busca la más cercana.
        val sorted = available.sortedBy { kotlin.math.abs(it.rarity.ordinal - rarity.ordinal) }
        val best = sorted.first().rarity
        val pool = sorted.filter { it.rarity == best }
        return pool[rng.nextInt(pool.size)]
    }

    fun get(id: String) = all.first { it.id == id }
}
