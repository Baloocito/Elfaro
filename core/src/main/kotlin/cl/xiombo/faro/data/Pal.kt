package cl.xiombo.faro.data

import com.badlogic.gdx.graphics.Color

/** Paletas del documento (§10): compartida + Muelle Ahogado. */
object Pal {
    private fun c(hex: String): Color = Color.valueOf(hex)

    // Compartida
    val OUTLINE = c("0b1420")       // contorno azul casi negro, nunca negro puro
    val NIGHT = c("1b2a3a")
    val COAT = c("4d7186")
    val HAIR = c("3a2a22")
    val SKIN = c("e0b48a")
    val LAMP_DIM = c("d9731f")
    val LAMP = c("ffb547")
    val LAMP_HI = c("ffe39a")
    val CREAM = c("f4ecd8")
    val CORAL = c("e57a5c")

    // Muelle Ahogado
    val VOID = c("0d1c24")
    val WATER = c("1b4a60")
    val WALL = c("2c3946")
    val WALL_TOP = c("4a5b6a")
    val PLANK = c("6a5038")
    val PLANK2 = c("5f4733")
    val SEAM = c("3a2a22")
    val DOOR = c("9b7a52")
    val AHOGADO = c("3f7f6e")
    val AHOGADO_HI = c("73b59a")
    val MEDUSA = c("c79ae0")
    val MEDUSA_IN = c("6e4a96")
    val CANGREJO = c("9b7a52")
    val SHELL = c("d8cfb4")

    val SHADOW = Color(0f, 0f, 0f, 0.35f)
}
