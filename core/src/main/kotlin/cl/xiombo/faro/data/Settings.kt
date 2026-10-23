package cl.xiombo.faro.data

import com.badlogic.gdx.Gdx

/** Opciones del jugador (accesibilidad y sonido). Se guardan en las preferencias. */
object Settings {
    private const val PREFS = "faro-hundido"

    /** Temblor de pantalla al golpear y recibir golpes. */
    var shake = true
    /** Vibración del mando al recibir daño. */
    var rumble = true
    /** Destellos suaves: sin parpadeos blancos ni fogonazos de luz bruscos. */
    var softFlashes = false
    /** 0-10 */
    var music = 6
    var sfx = 8

    fun load() {
        runCatching {
            val p = Gdx.app.getPreferences(PREFS)
            shake = p.getBoolean("opt.shake", true)
            rumble = p.getBoolean("opt.rumble", true)
            softFlashes = p.getBoolean("opt.soft", false)
            music = p.getInteger("opt.music", 6).coerceIn(0, 10)
            sfx = p.getInteger("opt.sfx", 8).coerceIn(0, 10)
        }
    }

    fun save() {
        runCatching {
            val p = Gdx.app.getPreferences(PREFS)
            p.putBoolean("opt.shake", shake)
            p.putBoolean("opt.rumble", rumble)
            p.putBoolean("opt.soft", softFlashes)
            p.putInteger("opt.music", music)
            p.putInteger("opt.sfx", sfx)
            p.flush()
        }
    }
}
