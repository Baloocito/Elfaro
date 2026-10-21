package cl.xiombo.faro.screen

import cl.xiombo.faro.input.Action
import cl.xiombo.faro.input.Bindings
import cl.xiombo.faro.input.Controls
import cl.xiombo.faro.input.Gamepad

/**
 * Pausa: Start/Select en el mando, Esc o P en teclado, atrás o el botón II en Android.
 * Desde aquí se reasignan los botones del mando, acción por acción.
 */
class PauseMenu {
    companion object {
        val ITEMS = listOf("Continuar", "Configurar mando", "Botones por defecto", "Nueva run")
        // Disposición en coordenadas internas (480 × 270), compartida con Renderer y Controls.
        const val ITEM_Y0 = 170f
        const val ITEM_STEP = 22f
        const val ITEM_W = 200f
        const val ITEM_H = 18f

        fun itemAt(ix: Float, iy: Float): Int {
            val x0 = (480f - ITEM_W) / 2f
            if (ix < x0 || ix > x0 + ITEM_W) return -1
            for (i in ITEMS.indices) {
                val y = ITEM_Y0 - i * ITEM_STEP
                if (iy >= y - ITEM_H / 2f && iy <= y + ITEM_H / 2f) return i
            }
            return -1
        }
    }

    var open = false
        private set
    var sel = 0
        private set
    /** Acción que se está reasignando (-1 = ninguna). */
    var remap = -1
        private set
    var note = ""
        private set
    var restartRequested = false

    fun openMenu() {
        if (open) return
        open = true
        sel = 0
        remap = -1
        note = ""
    }

    fun update(pad: Gamepad, c: Controls) {
        val mi = c.menuInput
        if (remap >= 0) {
            if (mi.back || mi.pause || pad.startPressed) {
                remap = -1
                Bindings.load()
                note = "Configuración cancelada"
                return
            }
            val code = pad.capture() ?: return
            Bindings.set(Action.entries[remap], code)
            remap++
            if (remap >= Action.entries.size) {
                remap = -1
                Bindings.save()
                note = "Mando configurado"
            }
            return
        }

        if (mi.up || pad.navUp) sel = (sel + ITEMS.size - 1) % ITEMS.size
        if (mi.down || pad.navDown) sel = (sel + 1) % ITEMS.size
        var choose = -1
        if (mi.confirm || pad.confirm) choose = sel
        if (mi.tapItem >= 0) { sel = mi.tapItem; choose = mi.tapItem }
        if (choose < 0 && (mi.back || mi.pause || pad.back || pad.startPressed)) {
            open = false
            return
        }
        when (choose) {
            0 -> open = false
            1 -> if (pad.controller == null) {
                note = "Conecta un mando Bluetooth y vuelve a intentarlo"
            } else {
                remap = 0
                note = ""
                pad.beginCapture()
            }
            2 -> { Bindings.reset(); note = "Botones por defecto restaurados" }
            3 -> { restartRequested = true; open = false }
        }
    }
}
