package cl.xiombo.faro.screen

import cl.xiombo.faro.data.Balance
import cl.xiombo.faro.input.Bindings
import cl.xiombo.faro.input.Controls
import cl.xiombo.faro.input.Gamepad
import cl.xiombo.faro.data.RunSave
import cl.xiombo.faro.input.VirtualController
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
    /** Si hay una run guardada, se continúa; si no, se empieza en el Santuario. */
    private var world = RunSave.load()?.let { World.resume(it) } ?: World.sanctuary()
    private var lastHurt = 0

    override fun show() {
        Bindings.load()
        Gdx.input.inputProcessor = controls
        Gdx.input.setCatchKey(Input.Keys.BACK, true)   // atrás pausa en vez de cerrar
    }

    override fun render(delta: Float) {
        if (controls.toggleVirtualPad) {
            controls.toggleVirtualPad = false
            pad.virtual = if (pad.virtual == null) VirtualController() else null
        }
        pad.poll()
        controls.menuOpen = menu.open
        menu.inRun = !world.inSanctuary && world.mode == World.Mode.PLAYING

        if (menu.open) {
            menu.update(pad, controls)
            if (menu.abandonRequested) {
                menu.abandonRequested = false
                world.abandon()
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
                    when (world.next) {
                        World.Next.NEW_RUN -> newWorld(World.newRun())
                        World.Next.SANCTUARY -> newWorld(World.sanctuary())
                        World.Next.NONE -> {}
                    }
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

    private fun newWorld(w: World) {
        world = w
        lastHurt = 0
    }

    /** Al salir de la app (llamada, notificación…) el juego queda en pausa y la run se guarda. */
    override fun pause() {
        menu.openMenu()
        world.saveRun()
    }

    override fun dispose() {
        renderer.dispose()
    }
}
