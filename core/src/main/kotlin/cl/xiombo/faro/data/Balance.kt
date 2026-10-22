package cl.xiombo.faro.data

import kotlin.math.min

/**
 * Números del documento de diseño (§3 Jugador, §4 Combate, §6 Dificultad).
 * Regla del documento: ajustar en pruebas, pero nunca más de ±20 % de golpe.
 */
object Balance {
    // Resolución interna y mundo
    const val W = 480
    const val H = 270
    const val TILE = 16
    const val STEP = 1f / 60f

    // §3 Jugador
    const val LUZ_MAX = 100f
    const val SPEED = 96f
    const val ACCEL = SPEED / 0.08f      // 0 → máx en 0,08 s
    const val DECEL = SPEED / 0.06f      // frenado en 0,06 s
    const val DASH_DIST = 56f
    const val DASH_TIME = 0.16f
    const val DASH_SPEED = DASH_DIST / DASH_TIME
    const val DASH_IFRAMES = 0.12f
    const val DASH_COOLDOWN = 0.55f
    const val HIT_IFRAMES = 0.8f
    const val INPUT_BUFFER = 0.12f
    fun lightRadius(luz: Float) = 48f + 1.2f * luz

    // §4 Farol de mano · frame data a 60 fps: arranque / activo / recuperación
    const val FAROL_DMG = 8f
    const val FAROL_STARTUP = 4
    const val FAROL_ACTIVE = 3
    const val FAROL_RECOVERY = 8
    const val FAROL_TOTAL = FAROL_STARTUP + FAROL_ACTIVE + FAROL_RECOVERY
    const val FAROL_RANGE = 28f
    const val FAROL_HALF_ANGLE_DEG = 30f // cono de 60°

    // Velar (§14): la luz baja a 56 px y los enemigos lejanos pierden el rastro.
    const val VEIL_RADIUS = 56f
    const val VEIL_MOVE_FACTOR = 0.7f
    const val VEIL_LOSE_TIME = 0.4f      // tiempo velando antes de que te pierdan
    const val VEIL_LOSE_DIST = 64f       // los más cerca que esto siguen viéndote
    const val VEIL_FIND_DIST = 36f       // velando, te encuentran si se acercan tanto
    const val VEIL_CHARGE = 0.5f         // velando este tiempo, al soltar sale el Fogonazo
    const val FLASH_COOLDOWN = 6f
    const val COMBO_WINDOW = 0.30f
    const val ATTACK_MOVE_FACTOR = 0.45f
    const val BURN_DPS = 3f
    const val BURN_TIME = 2f

    // §4 El Haz
    const val HAZ_COST = 8f
    const val HAZ_TIME = 2f
    const val HAZ_DPS = 30f
    const val HAZ_WIDTH = 6f
    const val HAZ_MAX_LEN = 320f
    const val HAZ_MOVE_FACTOR = 0.5f

    // §4 Economía de Luz
    const val LOW_LUZ = 25f              // por debajo, +50 % de Luz soltada
    const val ROOM_INTACT_BONUS = 5f

    // §10 Hit-stop (frames de lógica congelados)
    const val HITSTOP_HURT = 3
    const val HITSTOP_HIT = 2

    // §6 Escalado por piso f y Marea m
    fun hpMul(f: Int, m: Int = 0) = (1f + 0.15f * (f - 1)) * (1f + 0.05f * m)
    fun dmgMul(f: Int, m: Int = 0) = (1f + 0.08f * (f - 1)) * (1f + 0.06f * m)
    fun speedMul(f: Int) = 1f + min(0.02f * (f - 1), 0.25f)
    fun budget(f: Int, m: Int = 0) = 8 + 3 * f + 2 * m
    fun maxAlive(f: Int) = 5 + f / 4
}
