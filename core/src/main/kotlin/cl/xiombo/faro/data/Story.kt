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
        Biome.ARCHIVO -> "ARCHIVO DE LOS NÁUFRAGOS" to "Aquí se anotaba todo lo que la ciudad perdía. Las páginas siguen escribiéndose solas."
        Biome.RAIZ -> "RAÍZ DEL FARO" to "La lámpara toca la fosa. Algo respira al otro lado del vidrio."
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

    val CARTOGRAFO = Memory(
        "Recuerdo de Elías, el cartógrafo del puerto",
        "Dibujé la fosa durante treinta años. Cada vez que la lámpara giraba, la sombra del fondo crecía " +
            "un poco. Le llevé los mapas al Farero. No se sorprendió: me pidió que los quemara. Entonces lo " +
            "entendí. La luz no la espanta. La engorda.",
    )

    /** Tras la Lámpara Madre: la nota que dejó el Farero Antiguo (anticipa el final C). */
    val LAMPARA = Memory(
        "Lo que dejó escrito el Farero",
        "Isla: si lees esto, la encendiste. Yo la apagué para que dejara de comer, y la ciudad se hundió " +
            "con ella. Ninguna de las dos cosas era la correcta. Hay una tercera: bajar a oscuras y mirarla " +
            "de frente. Yo no me atreví.",
    )

    val FARERO = Memory(
        "El Farero Antiguo",
        "Veinte años a oscuras para que nadie la volviera a encender. Gracias por bajar sin luz, niña. " +
            "Ahora puedo descansar.",
    )

    fun memoryOf(bossId: String): Memory? = when (bossId) {
        "farero" -> FARERO
        "sirena" -> SIRENA
        "obispo" -> OBISPO
        "cartografo" -> CARTOGRAFO
        "lampara" -> LAMPARA
        else -> null
    }

    /** Finales (§9). C (dejar la oscuridad) llega con las Mareas en la F4. */
    val FINAL_A = "FINAL A  ·  REENCENDER" to
        "La lámpara vuelve a girar. Puerto Velatorio sube despacio a la superficie con sus barcas y sus " +
        "campanas. Abajo, algo abre los ojos por primera vez en veinte años."
    val FINAL_B = "FINAL B  ·  SER LA LÁMPARA" to
        "Isla ha leído todas las botellas y entiende lo que hizo el Farero. Ocupa su lugar dentro de la " +
        "lámpara. Mientras ella arda despacio, la criatura dormirá. La ciudad no sube, pero tampoco se hunde más."

    val FINAL_C = "FINAL C  ·  DEJAR LA OSCURIDAD" to
        "Isla apaga su farol y mira la fosa de frente. Sin luz que comer, la criatura vuelve a dormir de " +
        "verdad. El Farero sonríe por primera vez en veinte años. Puerto Velatorio sigue bajo el agua, pero " +
        "ya nada lo arrastra hacia abajo."

    const val LECTURA = "Reto: despeja la sala sin usar el Haz y el atril te dará un exvoto raro."
    const val CORAZON = "Una raíz enorme late en el centro. Si la golpeas, despertará algo."
    const val VICTORIA_FINAL = "Has llegado al fondo del faro. Con Marea 5 o más, vela frente a la lámpara antes de luchar."

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

    /** Náufragos rescatables (§15): uno por bioma, encerrado en una celda custodiada. */
    class Castaway(val key: String, val name: String, val biome: Biome, val rescue: Memory, val banner: String, val lines: List<String>)

    val CASTAWAYS = listOf(
        Castaway(
            Meta.GUARDESA, "Amparo, la guardesa", Biome.MUELLE, AMPARO,
            "AMPARO ES LIBRE  ·  EL SANTUARIO ABRE SU TIENDA", AMPARO_LINES,
        ),
        Castaway(
            Meta.RESCUE_CAMPANERO, "Bruno, el campanero renegado", Biome.CATEDRAL,
            Memory(
                "Bruno, el campanero renegado",
                "Cuando el obispo cerró la catedral por dentro, yo seguí tocando desde la torre hasta que el agua " +
                    "llegó a las campanas. Aprendí a oír lo que no se ve. Sácame de aquí y bajaré contigo.",
            ),
            "EL CAMPANERO ES LIBRE  ·  PUEDES CONTRATARLO EN EL SANTUARIO",
            listOf(
                "Las campanas no llamaban a misa. Le decían a lo de abajo dónde estábamos.",
                "Oigo a la Tinta antes de verla. Si me llevas, tú también.",
                "El obispo no era malo. Tenía miedo, como todos.",
            ),
        ),
        Castaway(
            Meta.RESCUE_CARTOGRAFA, "Lucía, la cartógrafa", Biome.ARCHIVO,
            Memory(
                "Lucía, la cartógrafa",
                "Era aprendiz de Elías. Él dibujaba la fosa; yo dibujaba las salidas. Ninguna llegaba arriba. " +
                    "Sube conmigo al Santuario: te marcaré en el mapa lo que importa.",
            ),
            "LA CARTÓGRAFA ES LIBRE  ·  ABRE SU PUESTO EN EL SANTUARIO",
            listOf(
                "Cada piso es distinto, pero la fosa siempre está en el mismo sitio: debajo.",
                "Elías quemó los mapas buenos. Los que quedan mienten un poco.",
                "Las salas especiales tienen otro olor. Te enseño a encontrarlas.",
            ),
        ),
        Castaway(
            Meta.HIJA, "Marina, la hija del Farero", Biome.RAIZ,
            Memory(
                "Marina, la hija del Farero",
                "Mi padre no apagó la lámpara por miedo. Descubrió que la criatura no ve en la oscuridad: solo ve la " +
                    "luz. Si bajas con la Marea alta y velas frente a la lámpara, no te verá llegar. Él no se atrevió.",
            ),
            "LA HIJA DEL FARERO ES LIBRE  ·  SABE CÓMO LLEGAR AL FINAL C",
            listOf(
                "Marea 5 o más. Llega hasta la lámpara y vela 3 segundos antes de que empiece. Entonces lo verás.",
                "Mi padre sigue ahí abajo. No es un monstruo. Es un hombre que se quedó a oscuras.",
                "Cuando lo veas, no le tengas lástima. Ten cuidado.",
            ),
        ),
    )

    fun castaway(key: String?) = CASTAWAYS.firstOrNull { it.key == key }
    fun castawayOf(b: Biome) = CASTAWAYS.first { it.biome == b }

    const val CAPILLA = "El altar pide luz. Lo que da a cambio brilla más."
}
