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
    val TACHADO = c("4a5f8e")

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

    // Archivo de los Náufragos
    val ARC_VOID = c("0f0d12")
    val ARC_WALL = c("2b2533")
    val ARC_WALL_TOP = c("4b4058")
    val ARC_FLOOR = c("3d3428")
    val ARC_FLOOR2 = c("362e23")
    val ARC_SEAM = c("241e18")
    val ARC_DOOR = c("6b5a3e")
    val INK = c("1c2233")
    val INK_HI = c("4a5f8e")
    val PAPER = c("e8dcc0")
    val PAPER_DIM = c("a89c80")
    val GREEN_LAMP = c("8fd694")
    val BOOK_RED = c("8e3b36")
    val BOOK_BLUE = c("3b5a7a")
    val BOOK_OCHRE = c("b08a3e")

    // Raíz del Faro
    val RAZ_VOID = c("120a08")
    val RAZ_WALL = c("2e1a14")
    val RAZ_WALL_TOP = c("5a3222")
    val RAZ_FLOOR = c("3a2a24")
    val RAZ_FLOOR2 = c("33241f")
    val RAZ_SEAM = c("21140f")
    val RAZ_DOOR = c("7a4a32")
    val MOLTEN = c("ff7a2f")
    val MOLTEN_HI = c("ffc36b")
    val ROOT = c("6b3b2a")
    val ROOT_HI = c("9c5a3c")
    val GLASSY = c("b7d8e0")
    val HUNGER = c("ff5a3c")

    val SHADOW = Color(0f, 0f, 0f, 0.35f)
}
