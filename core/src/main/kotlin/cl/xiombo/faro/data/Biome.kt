package cl.xiombo.faro.data

/**
 * Biomas (§14): 3 pisos cada uno, el jefe en el tercero. [tag] filtra los enemigos de
 * enemies.json y [prefix] busca primero plantillas propias ("cat_combate") antes de las comunes.
 */
enum class Biome(val label: String, val firstFloor: Int, val tag: String, val prefix: String, val bossId: String) {
    MUELLE("MUELLE AHOGADO", 1, "muelle", "", "sirena"),
    CATEDRAL("CATEDRAL SUMERGIDA", 4, "catedral", "cat_", "obispo"),
    ARCHIVO("ARCHIVO DE LOS NÁUFRAGOS", 7, "archivo", "arc_", "cartografo"),
    RAIZ("RAÍZ DEL FARO", 10, "raiz", "raz_", "lampara");

    val lastFloor get() = firstFloor + 2

    companion object {
        fun of(floor: Int): Biome = when {
            floor >= RAIZ.firstFloor -> RAIZ
            floor >= ARCHIVO.firstFloor -> ARCHIVO
            floor >= CATEDRAL.firstFloor -> CATEDRAL
            else -> MUELLE
        }
        fun isBossFloor(floor: Int) = floor == of(floor).lastFloor
        /** Último piso con contenido en esta versión. */
        val LAST = RAIZ
    }
}
