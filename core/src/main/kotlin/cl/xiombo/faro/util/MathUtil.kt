package cl.xiombo.faro.util

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

fun approach(v: Float, target: Float, step: Float): Float =
    if (v < target) min(v + step, target) else max(v - step, target)

fun len(x: Float, y: Float): Float = sqrt(x * x + y * y)

fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float = len(bx - ax, by - ay)

/** Gira el ángulo [a] hacia [target] como máximo [maxStep] radianes. */
fun turnToward(a: Float, target: Float, maxStep: Float): Float {
    var diff = target - a
    val tau = (2 * PI).toFloat()
    while (diff > PI) diff -= tau
    while (diff < -PI) diff += tau
    return a + diff.coerceIn(-maxStep, maxStep)
}
