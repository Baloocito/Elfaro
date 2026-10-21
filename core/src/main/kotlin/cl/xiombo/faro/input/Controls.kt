package cl.xiombo.faro.input

import cl.xiombo.faro.data.Balance
import cl.xiombo.faro.render.Renderer
import cl.xiombo.faro.util.len
import cl.xiombo.faro.world.World
import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.math.Vector2
import kotlin.math.min

/**
 * Teclado + ratón en PC; joystick virtual y botones en Android (§3).
 * Las coordenadas táctiles están en píxeles del backbuffer.
 */
class Controls(private val renderer: Renderer) : InputAdapter() {
    class Btn(val x: Float, val y: Float, val r: Float, val label: String)

    val state = InputState()
    val touch = Gdx.app.type == Application.ApplicationType.Android

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
    private val btnPtr = intArrayOf(-1, -1, -1, -1)   // 0 Atacar, 1 Destello, 2 Haz, 3 Usar

    /** El botón USAR solo aparece cerca de algo con lo que interactuar. */
    var useVisible = false
    private val tmp = Vector2()

    private val bw get() = Gdx.graphics.backBufferWidth.toFloat()
    private val bh get() = Gdx.graphics.backBufferHeight.toFloat()
    val joyRadius get() = 0.12f * bh

    fun buttons(): List<Btn> {
        val u = bh
        return listOf(
            Btn(bw - 0.17f * u, 0.25f * u, 0.12f * u, "FAROL"),
            Btn(bw - 0.43f * u, 0.13f * u, 0.08f * u, "DESTELLO"),
            Btn(bw - 0.17f * u, 0.53f * u, 0.08f * u, "HAZ"),
            Btn(bw - 0.43f * u, 0.40f * u, 0.08f * u, "USAR"),
        )
    }

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
        state.anyPressed = true
        when (keycode) {
            Input.Keys.SPACE, Input.Keys.SHIFT_LEFT -> press(1)
            Input.Keys.J -> press(0)
            Input.Keys.K, Input.Keys.Q -> press(2)
            Input.Keys.E, Input.Keys.ENTER -> press(3)
            Input.Keys.F1 -> renderer.debug = !renderer.debug
            Input.Keys.F2 -> renderer.lighting = !renderer.lighting
        }
        return true
    }

    override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        state.anyPressed = true
        if (!touch) {
            when (button) {
                Input.Buttons.LEFT -> press(0)
                Input.Buttons.RIGHT -> press(2)
            }
            return true
        }
        val x = screenX.toFloat()
        val y = bh - screenY
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

    /** Se llama una vez por tick de lógica, antes de World.update. */
    fun poll(w: World) {
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
        val l = len(mx, my)
        if (l > 1f) { mx /= l; my /= l }
        state.mx = mx
        state.my = my

        if (!touch) {
            renderer.screenToWorld(k.x.toFloat(), k.y.toFloat(), tmp)
            state.aimX = tmp.x - w.player.cx
            state.aimY = tmp.y - w.player.cy
            state.hasAim = true
            // Mantener pulsado encadena el combo.
            if (k.isButtonPressed(Input.Buttons.LEFT) || k.isKeyPressed(Input.Keys.J)) press(0)
        } else {
            state.hasAim = false
            if (isPressed(0)) press(0)
        }
    }
}
