package cl.xiombo.faro.input

import cl.xiombo.faro.data.Balance
import cl.xiombo.faro.render.Renderer
import cl.xiombo.faro.screen.PauseMenu
import cl.xiombo.faro.util.len
import cl.xiombo.faro.world.World
import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.math.Vector2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Teclado + ratón en PC, joystick virtual y botones en Android, y mando Bluetooth/USB en ambos (§3).
 * Las coordenadas táctiles están en píxeles del backbuffer.
 */
class Controls(private val renderer: Renderer) : InputAdapter() {
    class Btn(val x: Float, val y: Float, val r: Float, val label: String)

    /** Eventos de menú de este fotograma (los consume RunScreen). */
    class MenuInput {
        var pause = false
        var up = false
        var down = false
        var confirm = false
        var back = false
        var left = false
        var right = false
        var clear = false
        var restore = false
        var hasTap = false
        var tapX = 0f
        var tapY = 0f
        fun clear() {
            pause = false; up = false; down = false; confirm = false; back = false
            left = false; right = false; clear = false; restore = false; hasTap = false
        }
    }

    val state = InputState()
    val menuInput = MenuInput()
    val touch = Gdx.app.type == Application.ApplicationType.Android

    /** true mientras se juega con mando: oculta los controles táctiles e ignora el ratón para apuntar. */
    var padActive = false
    var menuOpen = false
    /** Pedido de activar/desactivar el mando virtual (lo atiende RunScreen). */
    var toggleVirtualPad = false

    var joyPtr = -1
        private set
    var joyOx = 0f
        private set
    var joyOy = 0f
        private set
    var joyX = 0f
        private set
    var joyY = 0f
        private set
    private val btnPtr = intArrayOf(-1, -1, -1, -1, -1)   // 0 Farol, 1 Destello, 2 Haz, 3 Usar, 4 Velar

    /** El botón USAR solo aparece cerca de algo con lo que interactuar. */
    var useVisible = false
    private val tmp = Vector2()

    private val bw get() = Gdx.graphics.backBufferWidth.toFloat()
    private val bh get() = Gdx.graphics.backBufferHeight.toFloat()
    val joyRadius get() = 0.12f * bh
    val showTouchUi get() = touch && !padActive

    fun buttons(): List<Btn> {
        val u = bh
        return listOf(
            Btn(bw - 0.17f * u, 0.25f * u, 0.12f * u, "FAROL"),
            Btn(bw - 0.43f * u, 0.13f * u, 0.08f * u, "DESTELLO"),
            Btn(bw - 0.17f * u, 0.53f * u, 0.08f * u, "HAZ"),
            Btn(bw - 0.43f * u, 0.40f * u, 0.08f * u, "USAR"),
            Btn(bw - 0.66f * u, 0.13f * u, 0.07f * u, "VELAR"),
        )
    }

    /** Botón de pausa táctil, arriba al centro. */
    fun pauseButton() = Btn(bw / 2f, bh - 0.07f * bh, 0.05f * bh, "II")

    fun isPressed(i: Int) = btnPtr[i] != -1

    private fun press(i: Int) {
        when (i) {
            0 -> state.attackBuf = Balance.INPUT_BUFFER
            1 -> state.dashBuf = Balance.INPUT_BUFFER
            2 -> state.hazBuf = Balance.INPUT_BUFFER
            3 -> state.useBuf = 0.2f
        }
    }

    override fun keyDown(keycode: Int): Boolean {
        if (menuOpen) {
            when (keycode) {
                Input.Keys.UP, Input.Keys.W -> menuInput.up = true
                Input.Keys.DOWN, Input.Keys.S -> menuInput.down = true
                Input.Keys.ENTER, Input.Keys.SPACE, Input.Keys.E -> menuInput.confirm = true
                Input.Keys.LEFT, Input.Keys.A -> menuInput.left = true
                Input.Keys.RIGHT, Input.Keys.D -> menuInput.right = true
                Input.Keys.FORWARD_DEL, Input.Keys.DEL, Input.Keys.X -> menuInput.clear = true
                Input.Keys.R -> menuInput.restore = true
                Input.Keys.ESCAPE, Input.Keys.BACK, Input.Keys.P -> menuInput.back = true
            }
            return true
        }
        padActive = false
        state.anyPressed = true
        when (keycode) {
            Input.Keys.SPACE, Input.Keys.SHIFT_LEFT -> press(1)
            Input.Keys.J -> press(0)
            Input.Keys.K, Input.Keys.Q -> press(2)
            Input.Keys.E, Input.Keys.ENTER -> press(3)
            Input.Keys.ESCAPE, Input.Keys.P, Input.Keys.BACK -> menuInput.pause = true
            Input.Keys.F1 -> renderer.debug = !renderer.debug
            Input.Keys.F2 -> renderer.lighting = !renderer.lighting
            Input.Keys.F7 -> if (renderer.debug) toggleVirtualPad = true
            Input.Keys.F3, Input.Keys.F4, Input.Keys.F5, Input.Keys.F6 ->
                if (renderer.debug) state.debugCmd = keycode - Input.Keys.F3 + 3
            Input.Keys.F8 -> if (renderer.debug) state.debugCmd = 8
            Input.Keys.F9 -> if (renderer.debug) state.debugCmd = 9
            Input.Keys.F10 -> if (renderer.debug) state.debugCmd = 10
            Input.Keys.F11 -> if (renderer.debug) state.debugCmd = 11
        }
        return true
    }

    override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        if (menuOpen) {
            renderer.screenToInternal(screenX.toFloat(), screenY.toFloat(), tmp)
            menuInput.hasTap = true
            menuInput.tapX = tmp.x
            menuInput.tapY = tmp.y
            return true
        }
        state.anyPressed = true
        if (!touch) {
            padActive = false
            when (button) {
                Input.Buttons.LEFT -> press(0)
                Input.Buttons.RIGHT -> press(2)
            }
            return true
        }
        padActive = false
        val x = screenX.toFloat()
        val y = bh - screenY
        val pb = pauseButton()
        val pr = pb.r * 1.4f
        if ((x - pb.x) * (x - pb.x) + (y - pb.y) * (y - pb.y) <= pr * pr) {
            menuInput.pause = true
            return true
        }
        val btns = buttons()
        for (i in btns.indices) {
            if (i == 3 && !useVisible) continue
            val b = btns[i]
            val r = b.r * 1.25f
            if ((x - b.x) * (x - b.x) + (y - b.y) * (y - b.y) <= r * r) {
                btnPtr[i] = pointer
                press(i)
                return true
            }
        }
        if (x < bw * 0.55f && joyPtr == -1) {
            joyPtr = pointer
            joyOx = x; joyOy = y
            joyX = x; joyY = y
        }
        return true
    }

    override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
        if (pointer == joyPtr) {
            joyX = screenX.toFloat()
            joyY = bh - screenY
        }
        return true
    }

    override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        if (pointer == joyPtr) joyPtr = -1
        for (i in btnPtr.indices) if (btnPtr[i] == pointer) btnPtr[i] = -1
        return true
    }

    /** Botones del mando: se aplican una vez por fotograma, como pulsaciones con búfer. */
    fun applyPadButtons(pad: Gamepad) {
        if (pad.controller == null) return
        if (pad.anyInput) {
            padActive = true
            joyPtr = -1
            btnPtr.fill(-1)
        }
        if (pad.pressed[Action.ATTACK.ordinal] || pad.held[Action.ATTACK.ordinal]) press(0)  // mantener = combo
        if (pad.pressed[Action.DASH.ordinal]) press(1)
        if (pad.pressed[Action.HAZ.ordinal]) press(2)
        if (pad.pressed[Action.USE.ordinal]) press(3)
        if (pad.pressed.any { it } || pad.confirm) state.anyPressed = true
        if (pad.startPressed) menuInput.pause = true
    }

    /** Se llama una vez por tick de lógica, antes de World.update. */
    fun poll(w: World, pad: Gamepad) {
        var mx = 0f
        var my = 0f
        val k = Gdx.input
        if (k.isKeyPressed(Input.Keys.A) || k.isKeyPressed(Input.Keys.LEFT)) mx -= 1f
        if (k.isKeyPressed(Input.Keys.D) || k.isKeyPressed(Input.Keys.RIGHT)) mx += 1f
        if (k.isKeyPressed(Input.Keys.S) || k.isKeyPressed(Input.Keys.DOWN)) my -= 1f
        if (k.isKeyPressed(Input.Keys.W) || k.isKeyPressed(Input.Keys.UP)) my += 1f

        if (touch && joyPtr != -1) {
            val dx = joyX - joyOx
            val dy = joyY - joyOy
            val d = len(dx, dy)
            if (d > joyRadius * 0.15f) {
                val m = min(1f, d / joyRadius)
                mx = dx / d * m
                my = dy / d * m
            }
        }
        if (padActive && (pad.lx != 0f || pad.ly != 0f)) {
            mx = pad.lx
            my = pad.ly
        }
        val l = len(mx, my)
        if (l > 1f) { mx /= l; my /= l }
        state.mx = mx
        state.my = my
        state.veil = k.isKeyPressed(Input.Keys.C) || k.isKeyPressed(Input.Keys.CONTROL_LEFT) ||
            (touch && isPressed(4)) || (pad.controller != null && pad.held[Action.VELAR.ordinal])

        when {
            padActive -> aimWithStick(w, pad)
            !touch -> {
                renderer.screenToWorld(k.x.toFloat(), k.y.toFloat(), tmp)
                state.aimX = tmp.x - w.player.cx
                state.aimY = tmp.y - w.player.cy
                state.hasAim = true
                // Mantener pulsado encadena el combo.
                if (k.isButtonPressed(Input.Buttons.LEFT) || k.isKeyPressed(Input.Keys.J)) press(0)
            }
            else -> {
                state.hasAim = false
                if (isPressed(0)) press(0)
            }
        }
    }

    /**
     * Stick derecho con auto-apuntado suave de 12° (§3). Sin stick derecho, el juego
     * apunta solo al enemigo más cercano en la dirección de movimiento, igual que en táctil.
     */
    private fun aimWithStick(w: World, pad: Gamepad) {
        val sl = len(pad.rx, pad.ry)
        if (sl <= 0f) {
            state.hasAim = false
            return
        }
        var dx = pad.rx / sl
        var dy = pad.ry / sl
        val p = w.player
        var bestCos = COS_12
        for (e in w.enemies) {
            if (!e.targetable) continue
            val ex = e.x - p.cx
            val ey = e.y - p.cy
            val d = sqrt(ex * ex + ey * ey)
            if (d < 1f || d > 220f) continue
            val c = (ex * pad.rx + ey * pad.ry) / (d * sl)
            if (c > bestCos) {
                bestCos = c
                dx = ex / d
                dy = ey / d
            }
        }
        state.aimX = dx
        state.aimY = dy
        state.hasAim = true
    }

    companion object {
        private val COS_12 = cos(Math.toRadians(12.0)).toFloat()
    }
}
