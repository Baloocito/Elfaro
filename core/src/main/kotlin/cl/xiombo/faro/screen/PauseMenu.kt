package cl.xiombo.faro.screen

import cl.xiombo.faro.input.Action
import cl.xiombo.faro.input.Bindings
import cl.xiombo.faro.input.Controls
import cl.xiombo.faro.input.Gamepad
import cl.xiombo.faro.input.Layout
import com.badlogic.gdx.Gdx

/**
 * Pausa: Start/Select en el mando, Esc o P en teclado, atrás o el botón II en Android.
 *
 * "Configurar mando" abre una pantalla con el mando dibujado y una tabla de 4 acciones × 2 huecos.
 * Reglas (las hace cumplir [Layout]): máximo 2 botones por acción, un botón solo puede tener
 * una acción (si ya está en uso, se intercambia) y ninguna acción se queda sin botón.
 * Start, Select y la cruceta están reservados para no dejar al jugador sin pausa ni movimiento.
 */
class PauseMenu {
    enum class Mode { MAIN, CONFIG }

    companion object {
        val ITEMS = listOf("Continuar", "Configurar mando", "Abandonar run")

        // Disposición en coordenadas internas (480 × 270), compartida con Renderer y Controls.
        const val ITEM_Y0 = 160f
        const val ITEM_STEP = 24f
        const val ITEM_W = 200f
        const val ITEM_H = 18f

        // Pantalla de configuración
        const val ROW_Y0 = 184f
        const val ROW_STEP = 26f
        const val CELL_H = 20f
        const val LABEL_X = 258f
        const val LABEL_W = 70f
        val SLOT_X = floatArrayOf(332f, 402f)
        const val SLOT_W = 66f
        const val BTN_Y = 64f
        const val BTN_H = 18f
        val BTN_X = floatArrayOf(258f, 370f)
        const val BTN_W = 100f
        const val CAPTURE_TIME = 6f

        fun itemAt(ix: Float, iy: Float): Int {
            val x0 = (480f - ITEM_W) / 2f
            if (ix < x0 || ix > x0 + ITEM_W) return -1
            for (i in ITEMS.indices) {
                val y = ITEM_Y0 - i * ITEM_STEP
                if (iy >= y - ITEM_H / 2f && iy <= y + ITEM_H / 2f) return i
            }
            return -1
        }

        fun rowY(r: Int) = ROW_Y0 - r * ROW_STEP

        /** Hueco (acción, columna) bajo el punto, o null. */
        fun cellAt(ix: Float, iy: Float): Pair<Int, Int>? {
            for (r in Action.entries.indices) {
                val y = rowY(r)
                if (iy < y - CELL_H / 2f || iy > y + CELL_H / 2f) continue
                for (c in SLOT_X.indices) if (ix >= SLOT_X[c] && ix <= SLOT_X[c] + SLOT_W) return r to c
            }
            return null
        }

        /** 0 = por defecto, 1 = guardar, -1 = nada. */
        fun configButtonAt(ix: Float, iy: Float): Int {
            if (iy < BTN_Y - BTN_H / 2f || iy > BTN_Y + BTN_H / 2f) return -1
            for (i in BTN_X.indices) if (ix >= BTN_X[i] && ix <= BTN_X[i] + BTN_W) return i
            return -1
        }
    }

    var open = false
        private set
    var mode = Mode.MAIN
        private set
    var sel = 0
        private set
    /** Pedido de abandonar la run (cuenta como muerte y vuelve al Santuario). */
    var abandonRequested = false
    /** false en el Santuario: ahí no hay run que abandonar. */
    var inRun = true

    // Configuración
    var layout: Layout? = null
        private set
    var row = 0
        private set
    var col = 0
        private set
    var capturing = false
        private set
    var captureT = 0f
        private set
    var note = ""
        private set
    var noteError = false
        private set
    var noteT = 0f
        private set

    fun openMenu() {
        if (open) return
        open = true
        mode = Mode.MAIN
        sel = 0
        capturing = false
        note = ""
    }

    private fun say(text: String, error: Boolean = false) {
        note = text
        noteError = error
        noteT = 4f
    }

    fun update(pad: Gamepad, c: Controls) {
        val dt = Gdx.graphics.deltaTime
        noteT -= dt
        when (mode) {
            Mode.MAIN -> updateMain(pad, c)
            Mode.CONFIG -> updateConfig(pad, c, dt)
        }
    }

    private fun updateMain(pad: Gamepad, c: Controls) {
        val mi = c.menuInput
        if (mi.up || pad.navUp) sel = (sel + ITEMS.size - 1) % ITEMS.size
        if (mi.down || pad.navDown) sel = (sel + 1) % ITEMS.size
        var choose = -1
        if (mi.confirm || pad.confirm) choose = sel
        if (mi.hasTap) {
            val t = itemAt(mi.tapX, mi.tapY)
            if (t >= 0) { sel = t; choose = t }
        }
        if (choose < 0 && (mi.back || mi.pause || pad.back || pad.startPressed)) {
            open = false
            return
        }
        when (choose) {
            0 -> open = false
            1 -> {
                val ctrl = pad.controller
                if (ctrl == null) say("Conecta un mando Bluetooth y vuelve a intentarlo", true)
                else {
                    mode = Mode.CONFIG
                    layout = Bindings.layout(ctrl).copy()
                    row = 0; col = 0
                    capturing = false
                    say("Elige un hueco y pulsa A para asignarle un botón")
                }
            }
            2 -> if (inRun) { abandonRequested = true; open = false } else say("Ya estás en el Santuario")
        }
    }

    private fun updateConfig(pad: Gamepad, c: Controls, dt: Float) {
        val mi = c.menuInput
        val ctrl = pad.controller
        val l = layout ?: run { mode = Mode.MAIN; return }

        if (capturing) {
            captureT -= dt
            when {
                ctrl == null -> { capturing = false; say("Mando desconectado", true) }
                pad.startPressed || mi.back || mi.pause -> { capturing = false; say("Asignación cancelada") }
                captureT <= 0f -> { capturing = false; say("Se acabó el tiempo. Pulsa A para intentarlo de nuevo", true) }
                else -> {
                    val code = pad.capture() ?: return
                    if (code.startsWith("!")) { say(code.substring(1), true); return }
                    capturing = false
                    l.assign(row, col, code) { Bindings.label(ctrl, it) }?.let { say(it.message, it.error) }
                }
            }
            return
        }

        val rows = Action.entries.size
        if (mi.up || pad.navUp) row = (row + rows - 1) % rows
        if (mi.down || pad.navDown) row = (row + 1) % rows
        if (mi.left || pad.navLeft) col = 0
        if (mi.right || pad.navRight) col = 1

        var startCapture = mi.confirm || pad.confirm
        if (mi.hasTap) {
            val cell = cellAt(mi.tapX, mi.tapY)
            if (cell != null) {
                row = cell.first; col = cell.second
                startCapture = true
            }
            when (configButtonAt(mi.tapX, mi.tapY)) {
                0 -> restoreDefaults(pad)
                1 -> { saveAndExit(pad); return }
            }
        }
        if (mi.clear || pad.xPressed) l.clear(row, col)?.let { say(it.message, it.error) }
        if (mi.restore || pad.yPressed) restoreDefaults(pad)
        if (mi.back || mi.pause || pad.back || pad.startPressed) { saveAndExit(pad); return }

        if (startCapture) {
            if (ctrl == null) { say("Conecta el mando para asignar botones", true); return }
            // El segundo hueco solo se usa cuando el primero ya tiene botón.
            if (col == 1 && l.slots[row][0] == null) col = 0
            capturing = true
            captureT = CAPTURE_TIME
            pad.beginCapture()
            say("Pulsa el botón para ${Action.entries[row].label.uppercase()}  ·  Start cancela")
        }
    }

    private fun restoreDefaults(pad: Gamepad) {
        val ctrl = pad.controller ?: return
        layout = Bindings.defaults(ctrl.mapping)
        say("Distribución por defecto. Sal con B para guardarla")
    }

    private fun saveAndExit(pad: Gamepad) {
        val l = layout
        val ctrl = pad.controller
        if (l != null && l.isValid()) {
            val def = ctrl?.let { Bindings.defaults(it.mapping) }
            val same = def != null && Action.entries.all { a -> def.codes(a.ordinal) == l.codes(a.ordinal) }
            if (same) Bindings.reset() else Bindings.commit(l)
            say("Controles guardados")
        }
        mode = Mode.MAIN
        sel = 1
        capturing = false
    }
}
