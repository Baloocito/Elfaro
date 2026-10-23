package cl.xiombo.faro.data

import com.badlogic.gdx.Gdx

/**
 * Edición del juego. La demo para quienes apoyan la campaña de financiamiento se compila con
 * el archivo assets/demo.flag (lo crea el CI): se juega el Muelle hasta la cámara de la Sirena,
 * sin niveles de prueba ni teclas de depuración.
 */
object Edition {
    val demo: Boolean by lazy { runCatching { Gdx.files.internal("demo.flag").exists() }.getOrDefault(false) }

    /** Teclas de depuración: siempre en la versión completa; en la demo solo con -Dfaro.debug=true (PC). */
    val debugAllowed: Boolean by lazy { !demo || runCatching { System.getProperty("faro.debug") == "true" }.getOrDefault(false) }

    const val DEMO_THANKS = "GRACIAS POR TU APOYO"
    const val DEMO_TEXT = "La Sirena de Brea te espera al otro lado. Tu copia completa llegará con el lanzamiento del juego."
}
