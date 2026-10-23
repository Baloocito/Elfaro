package cl.xiombo.faro.input

import cl.xiombo.faro.util.len
import com.badlogic.gdx.controllers.Controller
import com.badlogic.gdx.controllers.Controllers
import kotlin.math.abs
import kotlin.math.min

/**
 * Mando Bluetooth o USB (gdx-controllers). Se lee una vez por fotograma.
 * Movimiento con stick izquierdo o cruceta; apuntado con stick derecho.
 */
class Gamepad {
    var controller: Controller? = null
        private set

    var lx = 0f
        private set
    var ly = 0f
        private set
    var rx = 0f
        private set
    var ry = 0f
        private set

    val held = BooleanArray(Action.entries.size)
    val pressed = BooleanArray(Action.entries.size)

    // Navegación de menús
    var startPressed = false
        private set
    /** Select / Back: abre la pantalla de Estado. */
    var selectPressed = false
        private set
    var confirm = false
        private set
    var back = false
        private set
    var navUp = false
        private set
    var navDown = false
        private set
    var navLeft = false
        private set
    var navRight = false
        private set
    var xPressed = false
        private set
    var yPressed = false
        private set
    var anyInput = false
        private set

    private var prevStart = false
    private var prevSelect = false
    private var prevA = false
    private var prevB = false
    private var navLatch = 0
    private var navLatchX = 0
    private var prevX = false
    private var prevY = false

    // Captura para reasignar botones
    private val capturedButtons = HashSet<Int>()
    private val latchedAxes = HashSet<Int>()
    private var axisBase = FloatArray(0)

    /** Mando virtual de depuración (F7 con F1 activo); solo se usa si no hay uno real. */
    var virtual: Controller? = null

    private fun current(): Controller? = runCatching {
        Controllers.getCurrent() ?: Controllers.getControllers().firstOrNull()
    }.getOrNull() ?: virtual

    private fun axis(c: Controller, i: Int) = if (i < 0 || i >= c.axisCount) 0f else c.getAxis(i)
    private fun button(c: Controller, i: Int) = i >= 0 && c.getButton(i)

    fun poll() {
        val c = current()
        controller = c
        startPressed = false; selectPressed = false; confirm = false; back = false; navUp = false; navDown = false; anyInput = false
        navLeft = false; navRight = false; xPressed = false; yPressed = false
        pressed.fill(false)
        if (c == null || !c.isConnected) {
            lx = 0f; ly = 0f; rx = 0f; ry = 0f
            held.fill(false)
            return
        }
        val m = c.mapping

        var x = axis(c, m.axisLeftX)
        var y = -axis(c, m.axisLeftY)
        if (button(c, m.buttonDpadLeft)) x = -1f
        if (button(c, m.buttonDpadRight)) x = 1f
        if (button(c, m.buttonDpadUp)) y = 1f
        if (button(c, m.buttonDpadDown)) y = -1f
        deadzone(x, y, 0.22f).let { lx = it[0]; ly = it[1] }
        deadzone(axis(c, m.axisRightX), -axis(c, m.axisRightY), 0.30f).let { rx = it[0]; ry = it[1] }

        for (a in Action.entries) {
            val h = Bindings.active(c, a)
            pressed[a.ordinal] = h && !held[a.ordinal]
            held[a.ordinal] = h
        }

        val st = button(c, m.buttonStart)
        startPressed = st && !prevStart
        prevStart = st
        val sl = button(c, m.buttonBack)
        selectPressed = sl && !prevSelect
        prevSelect = sl
        val a = button(c, m.buttonA)
        confirm = a && !prevA
        prevA = a
        val b = button(c, m.buttonB)
        back = b && !prevB
        prevB = b

        val xb = button(c, m.buttonX)
        xPressed = xb && !prevX
        prevX = xb
        val yb = button(c, m.buttonY)
        yPressed = yb && !prevY
        prevY = yb
        when {
            lx > 0.6f && navLatchX != 1 -> { navRight = true; navLatchX = 1 }
            lx < -0.6f && navLatchX != -1 -> { navLeft = true; navLatchX = -1 }
            abs(lx) < 0.3f -> navLatchX = 0
        }
        when {
            ly > 0.6f && navLatch != 1 -> { navUp = true; navLatch = 1 }
            ly < -0.6f && navLatch != -1 -> { navDown = true; navLatch = -1 }
            abs(ly) < 0.3f -> navLatch = 0
        }

        anyInput = pressed.any { it } || startPressed || selectPressed || confirm || back || len(lx, ly) > 0.3f || len(rx, ry) > 0.3f
    }

    /** Zona muerta radial con reescalado: sin deriva y sin perder recorrido. */
    private fun deadzone(x: Float, y: Float, dz: Float): FloatArray {
        val l = len(x, y)
        if (l < dz) return floatArrayOf(0f, 0f)
        val k = min(1f, (l - dz) / (1f - dz)) / l
        return floatArrayOf(x * k, y * k)
    }

    fun rumble(ms: Int, strength: Float) {
        runCatching {
            val c = controller ?: return
            if (c.canVibrate()) c.startVibration(ms, strength)
        }
    }

    // ---------- reasignación ----------

    /** Toma como referencia lo que está pulsado ahora, para no capturar el botón que abrió el menú. */
    fun beginCapture() {
        val c = controller ?: return
        capturedButtons.clear()
        latchedAxes.clear()
        for (i in c.minButtonIndex..c.maxButtonIndex) if (c.getButton(i)) capturedButtons += i
        axisBase = FloatArray(c.axisCount) { c.getAxis(it) }
    }

    /**
     * Devuelve el siguiente botón o eje pulsado ("b12", "a4+"), o null si no hay ninguno nuevo.
     * Si se pulsa un botón reservado devuelve un aviso que empieza por "!".
     */
    fun capture(): String? {
        val c = controller ?: return null
        val m = c.mapping
        val dpad = setOf(m.buttonDpadUp, m.buttonDpadDown, m.buttonDpadLeft, m.buttonDpadRight)
        for (i in c.minButtonIndex..c.maxButtonIndex) {
            val down = c.getButton(i)
            if (!down) { capturedButtons -= i; continue }
            if (i in capturedButtons) continue
            capturedButtons += i
            if (i == m.buttonStart) continue   // Start cancela (lo gestiona el menú)
            if (i == m.buttonBack) return "!Select está reservado para el Estado"
            if (i in dpad) return "!La cruceta está reservada para mover a Isla"
            return "b$i"
        }
        val sticks = setOf(m.axisLeftX, m.axisLeftY, m.axisRightX, m.axisRightY)
        for (i in 0 until min(c.axisCount, axisBase.size)) {
            if (i in sticks) continue
            val dv = c.getAxis(i) - axisBase[i]
            if (i in latchedAxes) {
                if (abs(dv) < 0.3f) latchedAxes -= i
                continue
            }
            if (abs(dv) > 0.9f) {
                latchedAxes += i
                return "a$i${if (dv > 0f) "+" else "-"}"
            }
        }
        return null
    }
}
