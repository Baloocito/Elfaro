package cl.xiombo.faro.audio

import cl.xiombo.faro.data.Settings
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.Music
import com.badlogic.gdx.audio.Sound
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.utils.TimeUtils

/**
 * Sonido (§13). Efectos en assets/sfx y una pista por bioma en assets/music, generados con
 * tools/gen_audio.py. La lógica no toca el audio: World deja nombres en World.sounds y RunScreen
 * los reproduce aquí. Si el dispositivo no tiene audio, todo esto no hace nada.
 */
object Audio {
    private val sounds = HashMap<String, Sound?>()
    private val lastPlay = HashMap<String, Long>()
    private var current: Music? = null
    private var currentName = ""
    private var fading: Music? = null
    private var fadeK = 0f
    private var inK = 1f

    /** Volumen relativo de cada efecto (algunos suenan mucho). */
    private val gain = mapOf(
        "swing" to 0.45f, "hit" to 0.6f, "orb" to 0.35f, "dash" to 0.5f, "veil" to 0.5f,
        "kill" to 0.55f, "glass" to 0.6f, "ui_move" to 0.5f, "perla" to 0.5f,
    )

    /** Efectos que varían un poco de tono para no cansar. */
    private val vary = setOf("swing", "hit", "hit_heavy", "kill", "orb", "dash", "glass")

    private fun sound(name: String): Sound? = sounds.getOrPut(name) {
        runCatching { Gdx.audio?.newSound(Gdx.files.internal("sfx/$name.ogg")) }.getOrNull()
    }

    fun play(name: String) {
        if (Settings.sfx <= 0) return
        val now = TimeUtils.millis()
        // Un mismo efecto no se repite antes de 45 ms (golpes en grupo).
        if (now - (lastPlay[name] ?: 0L) < 45L) return
        lastPlay[name] = now
        val s = sound(name) ?: return
        val pitch = if (name in vary) MathUtils.random(0.92f, 1.08f) else 1f
        runCatching { s.play(Settings.sfx / 10f * (gain[name] ?: 0.8f), pitch, 0f) }
    }

    /** Cambia de pista con un fundido de ~1 s. "" corta la música. */
    fun music(name: String) {
        if (name == currentName) return
        currentName = name
        fading?.let { runCatching { it.stop(); it.dispose() } }
        fading = current
        fadeK = 1f
        current = null
        if (name.isEmpty()) return
        current = runCatching {
            Gdx.audio?.newMusic(Gdx.files.internal("music/$name.ogg"))?.apply {
                isLooping = true
                volume = 0f
                play()
            }
        }.getOrNull()
        inK = 0f
    }

    fun update(dt: Float) {
        val vol = Settings.music / 10f * 0.7f
        fading?.let { f ->
            fadeK -= dt
            if (fadeK <= 0f) { runCatching { f.stop(); f.dispose() }; fading = null }
            else runCatching { f.volume = vol * fadeK }
        }
        current?.let { m ->
            inK = minOf(1f, inK + dt)
            runCatching { m.volume = vol * inK }
        }
    }

    fun dispose() {
        sounds.values.forEach { runCatching { it?.dispose() } }
        sounds.clear()
        runCatching { current?.dispose() }
        runCatching { fading?.dispose() }
        current = null; fading = null; currentName = ""
    }
}
