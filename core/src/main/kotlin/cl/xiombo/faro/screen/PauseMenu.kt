package cl.xiombo.faro.screen

import cl.xiombo.faro.input.Action
import cl.xiombo.faro.input.Bindings
import cl.xiombo.faro.input.Controls
import cl.xiombo.faro.input.Gamepad
import cl.xiombo.faro.input.Layout
import com.badlogic.gdx.Gdx

/**
 * Menú del juego con tres páginas:
 * - Principal (al abrir el juego): Continuar descenso, Jugar, Niveles de prueba, Configurar mando.
 * - Niveles de prueba: entra directo a cada bioma, a cada jefe o a la celda de Amparo.
 * - Pausa: Start/Select en el mando, Esc o P en teclado, atrás o el botón II en Android.
 *
 * "Configurar mando" abre una pantalla con el mando dibujado y una tabla de 4 acciones × 2 huecos.
 * Reglas (las hace cumplir [Layout]): máximo 2 botones por acción, un botón solo puede tener
 * una acción (si ya está en uso, se intercambia) y ninguna acción se queda sin botón.
 * Start, Select y la cruceta están reservados para no dejar al jugador sin pausa ni movimiento.
 */
class PauseMenu {
    enum class Mode { MAIN, CONFIG }
    enum class Page { TITLE, TESTS, PAUSE }

    /** Lo que RunScreen debe hacer tras elegir una opción. */
    sealed class Request {
        object Continue : Request()
        object Play : Request()
        object ToTitle : Request()
        class Test(val floor: Int, val toBoss: Boolean = false, val toCell: Boolean = false) : Request()
    }

    private class Item(val label: String, val action: () -> Unit)

    companion object {
        const val CONFIG_LABEL = "Configurar mando"

        // Disposición en coordenadas internas (480 × 270), compartida con Renderer y Controls.
        const val ITEM_STEP = 22f
        const val ITEM_W = 220f
        const val ITEM_H = 17f

        /** Separación entre opciones: más juntas si hay muchas. */
        fun step(n: Int) = if (n > 6) 15f else ITEM_STEP
        fun itemH(n: Int) = if (n > 6) 13f else ITEM_H

        /** Y de la opción i cuando hay n (centradas un poco por encima de la mitad). */
        fun itemY(i: Int, n: Int) = 128f + (n - 1) * step(n) / 2f - i * step(n)

        // Pantalla de configuración
        const val ROW_Y0 = 184f
        const val ROW_STEP = 23f
        const val CELL_H = 18f
        const val LABEL_X = 258f
        const val LABEL_W = 70f
        val SLOT_X = floatArrayOf(332f, 402f)
        const val SLOT_W = 66f
        const val BTN_Y = 64f
        const val BTN_H = 18f
        val BTN_X = floatArrayOf(258f, 370f)
        const val BTN_W = 100f
        const val CAPTURE_TIME = 6f

        fun itemAt(ix: Float, iy: Float, n: Int): Int {
            val x0 = (480f - ITEM_W) / 2f
            if (ix < x0 || ix > x0 + ITEM_W) return -1
            for (i in 0 until n) {
                val y = itemY(i, n)
                if (iy >= y - itemH(n) / 2f && iy <= y + itemH(n) / 2f) return i
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
    var page = Page.PAUSE
        private set
    /** Hay una run guardada (para mostrar "Continuar descenso"). */
    var hasSave = false
    var request: Request? = null
    private var items: List<Item> = emptyList()
    val labels: List<String> get() = items.map { it.label }
    val title: String
        get() = when (page) {
            Page.TITLE -> "FARO HUNDIDO"
            Page.TESTS -> "NIVELES DE PRUEBA"
            Page.PAUSE -> "PAUSA"
        }
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
        capturing = false
        note = ""
        setPage(Page.PAUSE)
    }

    /** Menú principal (al abrir el juego o desde la pausa). */
    fun openTitle(save: Boolean) {
        hasSave = save
        open = true
        mode = Mode.MAIN
        capturing = false
        note = ""
        setPage(Page.TITLE)
    }

    private fun setPage(p: Page) {
        page = p
        sel = 0
        items = when (p) {
            Page.TITLE -> buildList {
                if (hasSave) add(Item("Continuar descenso") { finish(Request.Continue) })
                add(Item("Jugar  ·  ir al Santuario") { finish(Request.Play) })
                add(Item("Niveles de prueba") { setPage(Page.TESTS) })
                add(Item(CONFIG_LABEL) { openConfig() })
            }
            Page.TESTS -> listOf(
                Item("Muelle Ahogado  ·  piso 1") { finish(Request.Test(1)) },
                Item("Jefe: La Sirena de Brea") { finish(Request.Test(3, toBoss = true)) },
                Item("Catedral Sumergida  ·  piso 4") { finish(Request.Test(4)) },
                Item("Jefe: El Obispo Coral") { finish(Request.Test(6, toBoss = true)) },
                Item("Archivo de los Náufragos  ·  piso 7") { finish(Request.Test(7)) },
                Item("Jefe: El Cartógrafo") { finish(Request.Test(9, toBoss = true)) },
                Item("Raíz del Faro  ·  piso 10") { finish(Request.Test(10)) },
                Item("Jefe: La Lámpara Madre") { finish(Request.Test(12, toBoss = true)) },
                Item("La celda de Amparo") { finish(Request.Test(1, toCell = true)) },
                Item("Volver") { setPage(Page.TITLE) },
            )
            Page.PAUSE -> listOf(
                Item("Continuar") { open = false },
                Item(CONFIG_LABEL) { openConfig() },
                Item("Abandonar run") { if (inRun) { abandonRequested = true; open = false } else say("Ya estás en el Santuario") },
                Item("Menú principal") { finish(Request.ToTitle) },
            )
        }
    }

    private fun finish(r: Request) {
        request = r
        open = false
    }

    private var pendingPad: Gamepad? = null

    private fun openConfig() {
        val ctrl = pendingPad?.controller
        if (ctrl == null) say("Conecta un mando Bluetooth y vuelve a intentarlo", true)
        else {
            mode = Mode.CONFIG
            layout = Bindings.layout(ctrl).copy()
            row = 0; col = 0
            capturing = false
            say("Elige un hueco y pulsa A para asignarle un botón")
        }
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
        pendingPad = pad
        val mi = c.menuInput
        val n = items.size
        if (mi.up || pad.navUp) sel = (sel + n - 1) % n
        if (mi.down || pad.navDown) sel = (sel + 1) % n
        var choose = -1
        if (mi.confirm || pad.confirm) choose = sel
        if (mi.hasTap) {
            val t = itemAt(mi.tapX, mi.tapY, n)
            if (t >= 0) { sel = t; choose = t }
        }
        if (choose < 0 && (mi.back || mi.pause || pad.back || pad.startPressed)) {
            when (page) {
                Page.PAUSE -> open = false
                Page.TESTS -> setPage(Page.TITLE)
                Page.TITLE -> {}   // el menú principal no se cierra sin elegir
            }
            return
        }
        if (choose in items.indices) items[choose].action()
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
        sel = labels.indexOf(CONFIG_LABEL).coerceAtLeast(0)
        capturing = false
    }
}
