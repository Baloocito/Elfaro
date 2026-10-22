package cl.xiombo.faro.data

/**
 * Textos de historia (§9). Los jefes eran habitantes de Puerto Velatorio: cada uno deja un
 * recuerdo la primera vez que cae. Cada bioma abre con un título y una línea.
 *
 * La fuente del juego no tiene "—", "→" ni "…": usar solo Latin-1.
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
            "rezar mirando al agua y no al cielo. La última vez me dijo: «Padre, no es a nosotros a quien " +
            "alumbra». Lo tomé por una broma de viejo. Debí escucharlo.",
    )

    fun memoryOf(bossId: String): Memory? = when (bossId) {
        "sirena" -> SIRENA
        "obispo" -> OBISPO
        else -> null
    }

    const val CAPILLA = "El altar pide luz. Lo que da a cambio brilla más."
    const val VICTORIA_F2 = "Bajo la catedral, el Archivo de los Náufragos espera. Llega en la F3."
}
