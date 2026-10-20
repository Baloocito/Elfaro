package cl.xiombo.faro.world

import cl.xiombo.faro.data.Pal
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.MathUtils
import kotlin.math.cos
import kotlin.math.sin

class Particle(
    var x: Float, var y: Float, var vx: Float, var vy: Float,
    var life: Float, val maxLife: Float, val color: Color,
    val w: Float, val h: Float, val drag: Float,
)

class Floater(val text: String, var x: Float, var y: Float, var t: Float, val color: Color)
class Ring(val x: Float, val y: Float, val r: Float, var t: Float, val maxT: Float, val color: Color)
class Blast(val x: Float, val y: Float, val r: Float, var t: Float, val maxT: Float)
class Orb(var x: Float, var y: Float, var vx: Float, var vy: Float, val value: Float) { var age = 0f }

/** Efectos visuales sin efecto en la lógica. */
class Fx {
    val parts = ArrayList<Particle>()
    val floaters = ArrayList<Floater>()
    val rings = ArrayList<Ring>()
    val blasts = ArrayList<Blast>()

    fun burst(x: Float, y: Float, n: Int, color: Color, speed: Float, life: Float) {
        repeat(n) {
            val a = MathUtils.random(MathUtils.PI2)
            val s = MathUtils.random(0.3f, 1f) * speed
            val l = life * MathUtils.random(0.6f, 1f)
            parts += Particle(x, y, cos(a) * s, sin(a) * s, l, l, color, 2f, 2f, 5f)
        }
    }

    /** Silueta que deja el Destello (§10: estela de siluetas en color de luz). */
    fun ghost(x: Float, y: Float) {
        parts += Particle(x - 5f, y - 3f, 0f, 0f, 0.14f, 0.14f, Pal.LAMP_HI, 10f, 18f, 0f)
    }

    fun spark(x: Float, y: Float) = burst(x, y, 6, Pal.CREAM, 110f, 0.2f)

    fun ember(x: Float, y: Float) {
        parts += Particle(
            x + MathUtils.random(-4f, 4f), y + MathUtils.random(-2f, 6f),
            0f, 22f, 0.4f, 0.4f, Pal.LAMP_DIM, 1f, 1f, 0f,
        )
    }

    fun ring(x: Float, y: Float, r: Float, color: Color) { rings += Ring(x, y, r, 0.25f, 0.25f, color) }

    fun blast(x: Float, y: Float, r: Float) {
        blasts += Blast(x, y, r, 0.35f, 0.35f)
        ring(x, y, r, Pal.MEDUSA)
        burst(x, y, 18, Pal.MEDUSA, 150f, 0.5f)
    }

    fun floater(text: String, x: Float, y: Float, color: Color) { floaters += Floater(text, x, y, 0.9f, color) }

    fun update(dt: Float) {
        parts.removeAll { p ->
            p.life -= dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            val k = (1f - p.drag * dt).coerceAtLeast(0f)
            p.vx *= k; p.vy *= k
            p.life <= 0f
        }
        floaters.removeAll { f -> f.t -= dt; f.y += 14f * dt; f.t <= 0f }
        rings.removeAll { r -> r.t -= dt; r.t <= 0f }
        blasts.removeAll { b -> b.t -= dt; b.t <= 0f }
    }
}
