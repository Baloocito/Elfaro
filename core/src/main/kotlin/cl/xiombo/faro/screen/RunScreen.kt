package cl.xiombo.faro.screen

import cl.xiombo.faro.data.Balance
import cl.xiombo.faro.input.Bindings
import cl.xiombo.faro.input.Controls
import cl.xiombo.faro.input.Gamepad
import cl.xiombo.faro.render.Renderer
import cl.xiombo.faro.world.World
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import ktx.app.KtxScreen
import kotlin.math.min

/** Bucle con paso fijo (§11): lógica a 60 Hz, dibujo interpolado. */
class RunScreen : KtxScreen {
    private val step = Balance.STEP
    private var acc = 0f
    private val renderer = Renderer()
    private val controls = Controls(renderer)
    private val pad = Gamepad()
    private val menu = PauseMenu()
    private var world = World(System.nanoTime())
    private var lastHurt = 0

    override fun show() {
        Bindings.load()
        Gdx.input.inputProcessor = controls
        Gdx.input.setCatchKey(Input.Keys.BACK, true)   // atrás pausa en vez de cerrar
    }

    override fun render(delta: Float) {
        pad.poll()
        controls.menuOpen = menu.open

        if (menu.open) {
            menu.update(pad, controls)
            if (menu.restartRequested) {
                menu.restartRequested = false
                newWorld()
            }
            acc = 0f
        } else {
            controls.applyPadButtons(pad)
            if (controls.menuInput.pause) {
                menu.openMenu()
                acc = 0f
            } else {
                acc += min(delta, 0.25f)              // evita la espiral de la muerte
                while (acc >= step) {
                    controls.poll(world, pad)
                    world.update(step, controls.state)  // lógica determinista a 60 Hz
                    if (world.restartRequested) newWorld()
                    acc -= step
                }
            }
        }
        controls.menuInput.clear()

        if (world.hurtCount != lastHurt) {
            lastHurt = world.hurtCount
            pad.rumble(180, 0.7f)
        }

        controls.useVisible = world.interactable != null
        renderer.render(world, acc / step, controls, menu, pad)
    }

    private fun newWorld() {
        world = World(System.nanoTime())
        lastHurt = 0
    }

    /** Al salir de la app (llamada, notificación…) el juego queda en pausa. */
    override fun pause() {
        menu.openMenu()
    }

    override fun dispose() {
        renderer.dispose()
    }
}
