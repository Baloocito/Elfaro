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

/** [lot] = 0: siempre en el pozo; 1-5: lote que se compra en el Santuario (6 exvotos cada uno). */
class ExvotoDef(val id: String, val name: String, val tag: Tag, val rarity: Rarity, val desc: String, val lot: Int = 0)

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

        // Lote 1
        ExvotoDef("brasa_bolsillo", "Brasa en el bolsillo", Tag.FUEGO, Rarity.COMUN, "+15 % de daño contra enemigos quemados.", 1),
        ExvotoDef("pavesa", "Pavesa", Tag.FUEGO, Rarity.RARO, "Un enemigo quemado que muere quema al más cercano.", 1),
        ExvotoDef("salmuera", "Salmuera", Tag.SAL, Rarity.COMUN, "Los enemigos ralentizados te hacen un 15 % menos de daño.", 1),
        ExvotoDef("cristal_roca", "Cristal de roca", Tag.SAL, Rarity.RARO, "El Destello deja sal que ralentiza un 50 % a los enemigos 2 s.", 1),
        ExvotoDef("remo_roto", "Remo roto", Tag.MAREA, Rarity.COMUN, "+6 % de velocidad.", 1),
        ExvotoDef("vertebra_ballena", "Vértebra de ballena", Tag.HUESO, Rarity.RARO, "+10 de Luz máxima al empezar cada bioma.", 1),
        // Lote 2
        ExvotoDef("cera_virgen", "Cera virgen", Tag.FUEGO, Rarity.COMUN, "La Quemadura hace +1 de daño por segundo.", 2),
        ExvotoDef("ojo_buey", "Ojo de buey", Tag.NINGUNA, Rarity.RARO, "+16 px de radio de luz.", 2),
        ExvotoDef("escamas", "Escamas", Tag.MAREA, Rarity.COMUN, "El Destello te deja invulnerable 0,25 s más.", 2),
        ExvotoDef("diente_tiburon", "Diente de tiburón", Tag.HUESO, Rarity.COMUN, "+8 % de daño.", 2),
        ExvotoDef("rosario_sal", "Rosario de sal", Tag.SAL, Rarity.RARO, "Cada sala intacta da +2 de Luz máxima (tope 200).", 2),
        ExvotoDef("mecha_reserva", "Mecha de reserva", Tag.FUEGO, Rarity.EPICO, "Una vez por piso, si tu Luz llegara a 0 te quedas con 1 y quemas todo a tu alrededor.", 2),
        // Lote 3
        ExvotoDef("catalejo", "Catalejo", Tag.NINGUNA, Rarity.COMUN, "Ves las salas especiales en el mapa al entrar a cada piso.", 3),
        ExvotoDef("concha_nacar", "Concha de nácar", Tag.MAREA, Rarity.RARO, "+1 Perla por cada élite que mates.", 3),
        ExvotoDef("hueso_santo", "Hueso de santo", Tag.HUESO, Rarity.RARO, "Los jefes reciben un 10 % más de daño.", 3),
        ExvotoDef("lagrima_brea", "Lágrima de brea", Tag.FUEGO, Rarity.COMUN, "El Haz cuesta 7 de Luz y quema.", 3),
        ExvotoDef("sal_gruesa", "Sal gruesa", Tag.SAL, Rarity.COMUN, "Tus golpes ralentizan un 10 % durante 1 s.", 3),
        ExvotoDef("red_pesca", "Red de pesca", Tag.MAREA, Rarity.EPICO, "Al recibir daño, los enemigos a 64 px quedan ralentizados al 50 % 2 s.", 3),
        // Lote 4
        ExvotoDef("brujula_muerta", "Brújula muerta", Tag.NINGUNA, Rarity.COMUN, "Los orbes dan un 15 % más de Luz.", 4),
        ExvotoDef("pez_linterna", "Pez linterna", Tag.NINGUNA, Rarity.RARO, "Cada enemigo suelta un orbe de Luz más.", 4),
        ExvotoDef("calavera_cera", "Calavera de cera", Tag.HUESO, Rarity.COMUN, "+20 % de daño contra enemigos con menos del 25 % de vida.", 4),
        ExvotoDef("campanilla", "Campanilla", Tag.SAL, Rarity.RARO, "Cada quinto golpe aturde 0,5 s.", 4),
        ExvotoDef("corriente", "Corriente", Tag.MAREA, Rarity.COMUN, "El Destello se recarga un 25 % más rápido.", 4),
        ExvotoDef("fuelle", "Fuelle", Tag.FUEGO, Rarity.EPICO, "El 3.er golpe del combo estalla en fuego (32 px, 8 de daño y Quemadura).", 4),
        // Lote 5
        ExvotoDef("ancla_oxidada", "Ancla oxidada", Tag.MAREA, Rarity.COMUN, "Tus empujes son un 30 % más fuertes.", 5),
        ExvotoDef("ojo_vidrio", "Ojo de vidrio", Tag.NINGUNA, Rarity.RARO, "Ves a la Tinta y a los Tachados a 48 px aunque estén fuera de tu luz.", 5),
        ExvotoDef("diente_leche", "Diente de leche", Tag.HUESO, Rarity.COMUN, "+2 de daño en todos tus golpes.", 5),
        ExvotoDef("espina_erizo", "Espina de erizo", Tag.SAL, Rarity.RARO, "Al recibir un golpe, el atacante recibe 10 de daño.", 5),
        ExvotoDef("brasero", "Brasero", Tag.FUEGO, Rarity.RARO, "Matar 3 enemigos en 3 s te devuelve 4 de Luz.", 5),
        ExvotoDef("marea_viva", "Marea viva", Tag.MAREA, Rarity.EPICO, "El Destello hace 12 de daño a los enemigos que atraviesa.", 5),
    )

    /** Lotes de 6 exvotos que se compran en el Santuario (§8). */
    const val LOTS = 5
    const val LOT_PRICE = 30

    val setBonus = mapOf(
        Tag.FUEGO to "La Quemadura se propaga al morir el objetivo.",
        Tag.SAL to "Tus golpes ralentizan un 20 % durante 1 s.",
        Tag.MAREA to "El Destello se recarga al matar.",
        Tag.HUESO to "Los enemigos con menos del 15 % de vida mueren de un golpe.",
    )

    /** Probabilidad por rareza en cada bioma (§8): común / raro / épico / legendario. */
    private val weights = arrayOf(
        intArrayOf(65, 28, 6, 1),
        intArrayOf(55, 33, 10, 2),
        intArrayOf(45, 37, 15, 3),
        intArrayOf(38, 38, 19, 5),
    )

    /** [lots] = lotes comprados; [biome] = 0-3. */
    fun roll(rng: Random, exclude: Set<String>, minRarity: Rarity = Rarity.COMUN, lots: Int = Meta.lots, biome: Int = 0): ExvotoDef? {
        val available = all.filter { it.id !in exclude && it.lot <= lots }
        if (available.isEmpty()) return null
        val w = weights[biome.coerceIn(0, 3)]
        var r = rng.nextInt(w.sum())
        var rarity = Rarity.COMUN
        for (i in w.indices) {
            if (r < w[i]) { rarity = Rarity.entries[i]; break }
            r -= w[i]
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
