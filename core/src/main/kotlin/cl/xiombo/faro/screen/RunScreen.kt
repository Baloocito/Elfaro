package cl.xiombo.faro.screen

import cl.xiombo.faro.audio.Audio
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
    /** El juego abre en el menú principal, con el Santuario de fondo. */
    private var world = World.sanctuary()
    private var lastHurt = 0

    override fun show() {
        Bindings.load()
        Gdx.input.inputProcessor = controls
        Gdx.input.setCatchKey(Input.Keys.BACK, true)   // atrás pausa en vez de cerrar
        menu.openTitle(RunSave.exists())
        world.clearBanner()
    }

    /** Opciones del menú principal, de los niveles de prueba y de la pausa. */
    private fun handleRequest(r: PauseMenu.Request) {
        when (r) {
            PauseMenu.Request.Continue -> newWorld(RunSave.load()?.let { World.resume(it) } ?: World.sanctuary())
            PauseMenu.Request.Play -> if (!world.inSanctuary) newWorld(World.sanctuary())
            PauseMenu.Request.ToTitle -> {
                world.saveRun()   // una run real queda guardada para "Continuar descenso"
                newWorld(World.sanctuary())
                world.clearBanner()
                menu.openTitle(RunSave.exists())
            }
            is PauseMenu.Request.Test -> newWorld(World.testRun(r.floor, r.toBoss, r.toCell, r.marea))
            is PauseMenu.Request.PlaySeed -> newWorld(World.newRun(r.seed))
        }
    }

    override fun render(delta: Float) {
        if (controls.toggleVirtualPad) {
            controls.toggleVirtualPad = false
            pad.virtual = if (pad.virtual == null) VirtualController() else null
        }
        pad.poll()
        controls.menuOpen = menu.open
        menu.inRun = !world.inSanctuary && world.mode == World.Mode.PLAYING
        menu.seedCode = if (world.inSanctuary) "" else world.seedCode

        if (menu.open) {
            menu.update(pad, controls)
            if (menu.abandonRequested) {
                menu.abandonRequested = false
                world.abandon()
            }
            menu.request?.let { menu.request = null; handleRequest(it) }
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
            if (cl.xiombo.faro.data.Settings.rumble) pad.rumble(180, 0.7f)
        }

        // Sonido: efectos pedidos por la lógica y la pista que toca ahora.
        for (s in world.sounds) Audio.play(s)
        world.sounds.clear()
        Audio.music(trackFor(world))
        Audio.update(delta)

        controls.useVisible = world.interactable != null
        renderer.render(world, acc / step, controls, menu, pad)
    }

    private fun trackFor(w: World): String = when {
        w.inSanctuary -> "santuario"
        w.mode == World.Mode.VICTORY || w.mode == World.Mode.DEAD -> ""
        w.darkFight -> "oscuridad"
        w.boss?.let { !it.killed && it.introduced } == true -> "jefe"
        else -> w.biome.tag
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
        Audio.dispose()
    }
}
