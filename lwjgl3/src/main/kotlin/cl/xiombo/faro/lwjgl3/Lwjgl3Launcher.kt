package cl.xiombo.faro.lwjgl3

import cl.xiombo.faro.FaroGame
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.glutils.HdpiMode

fun main() {
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("Faro Hundido")
        setWindowedMode(1440, 810) // 480×270 a escala ×3
        setResizable(true)
        useVsync(true)
        setForegroundFPS(Lwjgl3ApplicationConfiguration.getDisplayMode().refreshRate + 1)
        setHdpiMode(HdpiMode.Pixels)
    }
    Lwjgl3Application(FaroGame(), config)
}
