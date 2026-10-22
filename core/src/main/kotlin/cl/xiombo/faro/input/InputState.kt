package cl.xiombo.faro.input

import kotlin.math.max

/**
 * Estado de entrada leído en cada tick de lógica.
 * Los botones usan un búfer de 0,12 s (§3): si se pulsan durante una recuperación,
 * la acción se ejecuta en cuanto sea posible.
 */
class InputState {
    var mx = 0f
    var my = 0f
    var aimX = 1f
    var aimY = 0f
    var hasAim = false   // true con ratón; en táctil se usa auto-apuntado

    var attackBuf = 0f
    var dashBuf = 0f
    var hazBuf = 0f
    var useBuf = 0f
    /** Velar: se mantiene pulsado. */
    var veil = false
    var anyPressed = false
    /** Comandos de depuración (F3–F6, solo con F1 activo). */
    var debugCmd = 0

    fun consume(dt: Float) {
        attackBuf = max(0f, attackBuf - dt)
        dashBuf = max(0f, dashBuf - dt)
        hazBuf = max(0f, hazBuf - dt)
        useBuf = max(0f, useBuf - dt)
        anyPressed = false
        debugCmd = 0
    }
}
