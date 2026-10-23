package cl.xiombo.faro.data

/**
 * Mareas (§6): modo de dificultad voluntario, acumulativo. Se elige en el Santuario.
 * Ganar con la Marea M desbloquea la M+1. La Marea baja es la opción fácil (queda marcada).
 */
object Mareas {
    const val MAX = 10

    /** Lo que añade cada nivel (índice = Marea). */
    val RULES = listOf(
        "Las reglas normales.",
        "Las élites aparecen desde el piso 1 también en salas normales.",
        "El refugio da 20 de Luz en vez de 30.",
        "Los enemigos atacan un 10 % más seguido.",
        "La tienda es un 20 % más cara.",
        "Los jefes ganan un patrón extra. Desbloquea el final C.",
        "Los avisos de ataque se acortan (mínimo 0,3 s).",
        "Empiezas con 80 de Luz máxima.",
        "Pisos con niebla: el refugio no revela el mapa.",
        "Las élites llevan 2 afijos.",
        "La criatura se suelta de la lámpara.",
    )

    const val BAJA_TEXT = "Marea baja: los enemigos hacen un 30 % menos de daño y sus avisos duran un 30 % más. Queda marcada en los resultados."

    fun label(m: Int, baja: Boolean) = if (baja) "MAREA BAJA" else "MAREA $m"
}
