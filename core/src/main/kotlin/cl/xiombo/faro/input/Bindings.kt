package cl.xiombo.faro.input

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.controllers.Controller
import com.badlogic.gdx.controllers.ControllerMapping

enum class Action(val label: String) {
    ATTACK("Farol"),
    DASH("Destello"),
    HAZ("Haz"),
    USE("Usar"),
}

/**
 * Botones del mando. Por defecto sigue la tabla de §3 (disposición Xbox):
 * Farol = RT / RB / X · Destello = A · Haz = LT / LB / B · Usar = Y.
 * Si el usuario reasigna una acción desde la pausa, su elección sustituye a las de por defecto.
 *
 * Formato: "b12" = botón 12; "a4+" = eje 4 en positivo (gatillos analógicos).
 */
object Bindings {
    private const val PREFS = "faro-hundido"
    private val custom = HashMap<Action, String>()

    fun load() {
        custom.clear()
        runCatching {
            val p = Gdx.app.getPreferences(PREFS)
            for (a in Action.entries) {
                val v = p.getString("pad.${a.name}", "")
                if (v.isNotEmpty()) custom[a] = v
            }
        }
    }

    fun save() {
        runCatching {
            val p = Gdx.app.getPreferences(PREFS)
            for (a in Action.entries) {
                val v = custom[a]
                if (v != null) p.putString("pad.${a.name}", v) else p.remove("pad.${a.name}")
            }
            p.flush()
        }
    }

    fun set(a: Action, code: String) { custom[a] = code }

    fun reset() {
        custom.clear()
        save()
    }

    fun isCustom() = custom.isNotEmpty()

    private fun defaults(m: ControllerMapping, a: Action): List<String> {
        val buttons = when (a) {
            Action.ATTACK -> listOf(m.buttonR2, m.buttonR1, m.buttonX)
            Action.DASH -> listOf(m.buttonA)
            Action.HAZ -> listOf(m.buttonL2, m.buttonL1, m.buttonB)
            Action.USE -> listOf(m.buttonY)
        }
        return buttons.filter { it >= 0 }.map { "b$it" }
    }

    fun codes(c: Controller, a: Action): List<String> =
        custom[a]?.let { listOf(it) } ?: defaults(c.mapping, a)

    fun isActive(c: Controller, code: String): Boolean {
        if (code.length < 2) return false
        return when (code[0]) {
            'b' -> code.substring(1).toIntOrNull()?.let { c.getButton(it) } ?: false
            'a' -> {
                val sign = if (code.last() == '-') -1f else 1f
                val idx = code.substring(1, code.length - 1).toIntOrNull() ?: return false
                idx < c.axisCount && c.getAxis(idx) * sign > 0.5f
            }
            else -> false
        }
    }

    fun active(c: Controller, a: Action) = codes(c, a).any { isActive(c, it) }

    /** Nombre corto del botón para mostrarlo en pantalla. */
    fun label(c: Controller?, code: String): String {
        if (c == null) return "?"
        val m = c.mapping
        if (code.startsWith("a")) return "Eje ${code.substring(1)}"
        return when (code.substring(1).toIntOrNull()) {
            m.buttonA -> "A"
            m.buttonB -> "B"
            m.buttonX -> "X"
            m.buttonY -> "Y"
            m.buttonL1 -> "LB"
            m.buttonR1 -> "RB"
            m.buttonL2 -> "LT"
            m.buttonR2 -> "RT"
            m.buttonStart -> "Start"
            m.buttonBack -> "Select"
            else -> "Botón ${code.substring(1)}"
        }
    }

    fun label(c: Controller?, a: Action): String {
        if (c == null) return "?"
        return codes(c, a).firstOrNull()?.let { label(c, it) } ?: "—"
    }
}
