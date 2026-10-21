package cl.xiombo.faro.screen

import cl.xiombo.faro.data.Balance
import cl.xiombo.faro.input.Controls
import cl.xiombo.faro.render.Renderer
import cl.xiombo.faro.world.World
import com.badlogic.gdx.Gdx
import ktx.app.KtxScreen
import kotlin.math.min

/** Bucle con paso fijo (§11): lógica a 60 Hz, dibujo interpolado. */
class RunScreen : KtxScreen {
    private val step = Balance.STEP
    private var acc = 0f
    private val renderer = Renderer()
    private val controls = Controls(renderer)
    private var world = World(System.nanoTime())

    override fun show() {
        Gdx.input.inputProcessor = controls
    }

    override fun render(delta: Float) {
        acc += min(delta, 0.25f)              // evita la espiral de la muerte
        while (acc >= step) {
            controls.poll(world)
            world.update(step, controls.state)  // lógica determinista a 60 Hz
            if (world.restartRequested) world = World(System.nanoTime())
            acc -= step
        }
        controls.useVisible = world.interactable != null
        renderer.render(world, acc / step, controls)
    }

    override fun dispose() {
        renderer.dispose()
    }
}
