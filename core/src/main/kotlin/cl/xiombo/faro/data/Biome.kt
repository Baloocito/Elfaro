package cl.xiombo.faro.data

/**
 * Biomas (§14): 3 pisos cada uno, el jefe en el tercero. [tag] filtra los enemigos de
 * enemies.json y [prefix] busca primero plantillas propias ("cat_combate") antes de las comunes.
 */
enum class Biome(val label: String, val firstFloor: Int, val tag: String, val prefix: String, val bossId: String) {
    MUELLE("MUELLE AHOGADO", 1, "muelle", "", "sirena"),
    CATEDRAL("CATEDRAL SUMERGIDA", 4, "catedral", "cat_", "obispo");

    val lastFloor get() = firstFloor + 2

    companion object {
        fun of(floor: Int): Biome = if (floor >= CATEDRAL.firstFloor) CATEDRAL else MUELLE
        fun isBossFloor(floor: Int) = floor == of(floor).lastFloor
        /** Último piso con contenido en esta versión. */
        val LAST = CATEDRAL
    }
}
