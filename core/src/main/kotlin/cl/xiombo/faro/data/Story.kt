package cl.xiombo.faro.data

/**
 * Textos de historia (§9). Los jefes eran habitantes de Puerto Velatorio: cada uno deja un
 * recuerdo la primera vez que cae. Cada bioma abre con un título y una línea.
 *
 * La fuente del juego no tiene "—", "→", "…" ni las comillas « »: usar comillas rectas.
 */
object Story {
    class Memory(val title: String, val text: String)

    /** Título y línea con los que abre cada bioma. */
    fun chapter(b: Biome): Pair<String, String> = when (b) {
        Biome.MUELLE -> "MUELLE AHOGADO" to "Las barcas siguen amarradas. Nadie volvió a soltarlas."
        Biome.CATEDRAL -> "CATEDRAL SUMERGIDA" to "Bajo el muelle, las campanas todavía tocan. No hay nadie para oírlas."
    }

    val SIRENA = Memory(
        "Recuerdo de Ondina, la cantora del muelle",
        "Cantaba en la lonja para que volvieran los barcos. La noche que la lámpara se apagó, " +
            "el agua subió por las escaleras y yo seguí cantando, porque alguien tenía que avisar. " +
            "Vi al Farero en la galería, mirando hacia abajo. No parecía asustado. Parecía aliviado.",
    )

    val OBISPO = Memory(
        "Recuerdo de Teodoro, obispo de Puerto Velatorio",
        "Cada invierno subía al faro a bendecir la lámpara. Nunca pregunté por qué el Farero me pedía " +
            "rezar mirando al agua y no al cielo. La última vez me dijo: \"Padre, no es a nosotros a quien " +
            "alumbra\". Lo tomé por una broma de viejo. Debí escucharlo.",
    )

    fun memoryOf(bossId: String): Memory? = when (bossId) {
        "sirena" -> SIRENA
        "obispo" -> OBISPO
        else -> null
    }

    /** Amparo, la guardesa del faro: está encerrada en una celda del Muelle. Abre la tienda del Santuario. */
    val AMPARO = Memory(
        "Amparo, la guardesa del faro",
        "Llevo veinte años en esta celda. El Farero me encerró la noche en que quise volver a encender " +
            "la lámpara; dijo que era por el bien de todos. Sube al Santuario, niña. Allí te guardaré " +
            "lo que traigas de abajo.",
    )

    /** Lo que dice Amparo en el Santuario (una frase distinta cada vez que le hablas). */
    val AMPARO_LINES = listOf(
        "Guardo lo que traes de abajo. Las Perlas compran tiempo, no milagros.",
        "El Farero no estaba loco. Sabía algo que nosotros no.",
        "Si bajas a la Catedral, no mires los vitrales mucho rato.",
        "La lámpara no se apagó sola. Eso lo sé desde hace veinte años.",
    )

    const val CAPILLA = "El altar pide luz. Lo que da a cambio brilla más."
    const val VICTORIA_F2 = "Bajo la catedral, el Archivo de los Náufragos espera. Llega en la F3."
}
