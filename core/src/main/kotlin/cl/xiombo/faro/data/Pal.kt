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

    val TIDE = c("2f7590")
    val FOAM = c("73b59a")
    val BREA = c("140d0a")
    val RARE = c("6fb6e8")
    val EPIC = c("c792ea")

    // Afijos de élite
    val SALADO = c("f4ecd8")
    val BRILLANTE = c("ffe39a")
    val GEMELO = c("c79ae0")
    val HAMBRIENTO = c("b23a3a")
    val BENDECIDO = c("ffd36b")

    // Catedral Sumergida (§10)
    val CAT_VOID = c("140f1f")
    val CAT_WALL = c("2b2140")
    val CAT_WALL_TOP = c("4a3a6b")
    val CAT_FLOOR = c("4b4458")
    val CAT_FLOOR2 = c("433d50")
    val CAT_SEAM = c("2b2140")
    val CAT_DOOR = c("7a5aa0")
    val VIOLET = c("7a5aa0")
    val LILAC = c("c9a6d8")
    val CORAL_PINK = c("f2a07b")
    val CORAL_RED = c("e06a4f")
    val BONE = c("d8cfb4")
    val STONE = c("9b917a")
    val GLASS_TEAL = c("3e6a78")
    val GLASS_LIGHT = c("9fd6d0")

    val SHADOW = Color(0f, 0f, 0f, 0.35f)
}
