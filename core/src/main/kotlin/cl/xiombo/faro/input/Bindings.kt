package cl.xiombo.faro.input

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.controllers.Controller
import com.badlogic.gdx.controllers.ControllerMapping

enum class Action(val label: String) {
    ATTACK("Farol"),
    DASH("Destello"),
    HAZ("Haz"),
    USE("Usar"),
    VELAR("Velar"),
}

/**
 * Distribución de botones de una run: hasta 2 botones por acción y cada botón en una sola acción.
 * Códigos: "b12" = botón 12; "a4+" = eje 4 en positivo (gatillos analógicos).
 *
 * Todas las operaciones mantienen las reglas: al asignar un botón que ya está en uso se
 * intercambian los huecos, y ninguna acción puede quedarse sin botón.
 */
class Layout(val slots: Array<Array<String?>>) {
    class Result(val message: String, val error: Boolean)

    fun copy() = Layout(Array(slots.size) { slots[it].copyOf() })

    fun codes(a: Int): List<String> = slots[a].filterNotNull()
    fun count(a: Int) = slots[a].count { it != null }

    fun find(code: String): Pair<Int, Int>? {
        for (a in slots.indices) for (s in slots[a].indices) if (slots[a][s] == code) return a to s
        return null
    }

    fun actionOf(code: String): Action? = find(code)?.let { Action.entries[it.first] }

    fun assign(a: Int, s: Int, code: String, label: (String) -> String): Result? {
        val cur = slots[a][s]
        if (cur == code) return null
        val at = find(code)
        var msg = "${label(code)} ahora es ${Action.entries[a].label}"
        if (at != null) {
            val (oa, os) = at
            if (oa != a && cur == null && count(oa) == 1) {
                return Result("${label(code)} es el único botón de ${Action.entries[oa].label}. Dale otro primero.", true)
            }
            slots[oa][os] = cur   // intercambio (cur puede ser vacío)
            if (oa != a) {
                msg = if (cur != null) "Intercambio: ${label(code)} ahora es ${Action.entries[a].label} y ${label(cur)} es ${Action.entries[oa].label}"
                else "${label(code)} pasa de ${Action.entries[oa].label} a ${Action.entries[a].label}"
            }
        }
        slots[a][s] = code
        normalize()
        return Result(msg, false)
    }

    fun clear(a: Int, s: Int): Result? {
        if (slots[a][s] == null) return null
        if (count(a) <= 1) return Result("Cada acción necesita al menos un botón", true)
        slots[a][s] = null
        normalize()
        return Result("Botón quitado de ${Action.entries[a].label}", false)
    }

    /** Da a cada acción vacía los botones de [def] que estén libres. */
    fun fillEmpty(def: Layout) {
        for (a in slots.indices) {
            if (count(a) > 0) continue
            val free = def.codes(a).filter { find(it) == null }
            free.take(Bindings.MAX_PER_ACTION).forEachIndexed { i, c -> slots[a][i] = c }
        }
    }

    /** El primer hueco nunca queda vacío si el segundo tiene algo. */
    private fun normalize() {
        for (row in slots) if (row[0] == null && row[1] != null) { row[0] = row[1]; row[1] = null }
    }

    /** Válida si cada acción tiene 1–2 botones y ningún botón se repite. */
    fun isValid(allowEmpty: Boolean = false): Boolean {
        val seen = HashSet<String>()
        for (a in slots.indices) {
            val c = codes(a)
            if ((c.isEmpty() && !allowEmpty) || c.size > Bindings.MAX_PER_ACTION) return false
            for (code in c) if (!seen.add(code)) return false
        }
        return true
    }
}

object Bindings {
    const val MAX_PER_ACTION = 2
    private const val PREFS = "faro-hundido"
    private var custom: Layout? = null

    fun load() {
        custom = null
        runCatching {
            val p = Gdx.app.getPreferences(PREFS)
            val slots = Array(Action.entries.size) { arrayOfNulls<String>(MAX_PER_ACTION) }
            var any = false
            for (a in Action.entries) {
                val raw = p.getString("pad.${a.name}", "")
                val codes = raw.split(',').map { it.trim() }.filter { isWellFormed(it) }.take(MAX_PER_ACTION)
                codes.forEachIndexed { i, c -> slots[a.ordinal][i] = c }
                if (codes.isNotEmpty()) any = true
            }
            val l = Layout(slots)
            // Datos dañados o editados a mano: se descartan y se usan los de por defecto.
            // Una acción nueva sin botón guardado (Velar, en configuraciones anteriores) no invalida
            // el resto: se rellena con su botón por defecto al leer la distribución.
            if (any && l.isValid(allowEmpty = true)) custom = l
        }
    }

    private fun isWellFormed(code: String): Boolean = when {
        code.length < 2 -> false
        code[0] == 'b' -> code.substring(1).toIntOrNull() != null
        code[0] == 'a' -> (code.last() == '+' || code.last() == '-') && code.substring(1, code.length - 1).toIntOrNull() != null
        else -> false
    }

    fun commit(l: Layout) {
        if (!l.isValid()) return
        custom = l.copy()
        runCatching {
            val p = Gdx.app.getPreferences(PREFS)
            for (a in Action.entries) p.putString("pad.${a.name}", l.codes(a.ordinal).joinToString(","))
            p.flush()
        }
    }

    fun reset() {
        custom = null
        runCatching {
            val p = Gdx.app.getPreferences(PREFS)
            for (a in Action.entries) p.remove("pad.${a.name}")
            p.flush()
        }
    }

    val isCustom get() = custom != null

    /**
     * Por defecto (disposición Xbox, §3): Farol RT + X · Destello A + RB · Haz LT + B · Usar Y · Velar LB.
     */
    fun defaults(m: ControllerMapping): Layout {
        val wanted = listOf(
            listOf(m.buttonR2, m.buttonX),
            listOf(m.buttonA, m.buttonR1),
            listOf(m.buttonL2, m.buttonB),
            listOf(m.buttonY),
            listOf(m.buttonL1),
        )
        val used = HashSet<Int>()
        val slots = Array(Action.entries.size) { a ->
            val codes = wanted[a].filter { it >= 0 && used.add(it) }.take(MAX_PER_ACTION).map { "b$it" }
            Array(MAX_PER_ACTION) { codes.getOrNull(it) }
        }
        return Layout(slots)
    }

    fun layout(c: Controller): Layout {
        val l = custom ?: return defaults(c.mapping)
        if (l.isValid()) return l
        val f = l.copy().also { it.fillEmpty(defaults(c.mapping)) }
        return if (f.isValid()) f else defaults(c.mapping)
    }

    fun codes(c: Controller, a: Action): List<String> = layout(c).codes(a.ordinal)

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
        if (code.startsWith("a")) {
            val idx = code.substring(1, code.length - 1)
            return "Eje $idx${code.last()}"
        }
        if (c == null) return "Botón ${code.substring(1)}"
        val m = c.mapping
        return when (code.substring(1).toIntOrNull()) {
            m.buttonA -> "A"
            m.buttonB -> "B"
            m.buttonX -> "X"
            m.buttonY -> "Y"
            m.buttonL1 -> "LB"
            m.buttonR1 -> "RB"
            m.buttonL2 -> "LT"
            m.buttonR2 -> "RT"
            m.buttonLeftStick -> "L3"
            m.buttonRightStick -> "R3"
            m.buttonDpadUp -> "Cruceta arriba"
            m.buttonDpadDown -> "Cruceta abajo"
            m.buttonDpadLeft -> "Cruceta izq."
            m.buttonDpadRight -> "Cruceta der."
            m.buttonStart -> "Start"
            m.buttonBack -> "Select"
            else -> "Botón ${code.substring(1)}"
        }
    }

    fun label(c: Controller?, a: Action): String {
        if (c == null) return "?"
        return codes(c, a).firstOrNull()?.let { label(c, it) } ?: "-"
    }
}
