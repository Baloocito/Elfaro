package cl.xiombo.faro.data

import com.badlogic.gdx.Gdx

/**
 * Armas (§4). Frame data a 60 fps: arranque / activo / recuperación.
 * Cada arma tiene su propia mejora de tienda (máx. 3), descrita en [upgradeText].
 */
enum class Weapon(
    val label: String,
    val startup: Int,
    val active: Int,
    val recovery: Int,
    val moveFactor: Float,
    val desc: String,
    val upgradeText: String,
) {
    FAROL("Farol", 4, 3, 8, 0.45f,
        "Rápido. Combo de 3 golpes; el 3.º quema.", "+20 % de daño"),
    ARPON("Arpón", 8, 2, 14, 0.35f,
        "A distancia: 14 de daño y perfora 2. Recógelo o espera a que vuelva.", "+1 perforación y vuelve 0,3 s antes"),
    ANCLA("Ancla", 15, 4, 18, 0.25f,
        "Lenta y pesada: 22 de daño en 180°. Empuja y aturde contra las paredes.", "+10 px de empuje y +0,2 s de aturdido"),
    CAMPANA("Campana", 10, 6, 20, 0.3f,
        "Pulso alrededor de Isla: 10 de daño y aturde 0,6 s. Abre paredes agrietadas.", "+8 px de radio");

    val total get() = startup + active + recovery
}

/**
 * Meta-progresión guardada entre runs (§8): Perlas, desbloqueos, arma elegida y opciones del Santuario.
 * Regla de oro: abre opciones, no da poder bruto.
 */
object Meta {
    private const val PREFS = "faro-hundido"

    var perlas = 0
    var weapon = Weapon.FAROL
    /** Niveles comprados de +10 Luz máxima inicial (0–2). */
    var luzLevel = 0
    /** Exvoto común elegido en la tienda de la Pescadora para la próxima run. */
    var startExvoto: String? = null
    val unlocks = HashSet<String>()
    val bosses = HashSet<String>()

    const val PESCADORA = "pescadora"
    const val ANCLA = "ancla"
    const val CARTOGRAFA = "cartografa"
    const val CAMPANERO = "campanero"

    fun load() {
        runCatching {
            val p = Gdx.app.getPreferences(PREFS)
            perlas = p.getInteger("perlas", 0).coerceAtLeast(0)
            luzLevel = p.getInteger("meta.luz", 0).coerceIn(0, 2)
            unlocks.clear(); unlocks += p.getString("meta.unlocks", "").split(',').filter { it.isNotBlank() }
            bosses.clear(); bosses += p.getString("meta.bosses", "").split(',').filter { it.isNotBlank() }
            weapon = runCatching { Weapon.valueOf(p.getString("meta.weapon", "FAROL")) }.getOrDefault(Weapon.FAROL)
            if (!isUnlocked(weapon)) weapon = Weapon.FAROL
            startExvoto = p.getString("meta.startExvoto", "").ifBlank { null }
        }
    }

    fun save() {
        runCatching {
            val p = Gdx.app.getPreferences(PREFS)
            p.putInteger("perlas", perlas)
            p.putInteger("meta.luz", luzLevel)
            p.putString("meta.unlocks", unlocks.joinToString(","))
            p.putString("meta.bosses", bosses.joinToString(","))
            p.putString("meta.weapon", weapon.name)
            p.putString("meta.startExvoto", startExvoto ?: "")
            p.flush()
        }
    }

    fun isUnlocked(w: Weapon) = when (w) {
        Weapon.FAROL -> true
        Weapon.ARPON -> "sirena" in bosses
        Weapon.ANCLA -> ANCLA in unlocks
        Weapon.CAMPANA -> "obispo" in bosses
    }

    fun unlockRequirement(w: Weapon) = when (w) {
        Weapon.FAROL -> ""
        Weapon.ARPON -> "Vence a la Sirena de Brea"
        Weapon.ANCLA -> "60 Perlas"
        Weapon.CAMPANA -> "Vence al Obispo Coral"
    }

    fun addPerlas(n: Int) {
        perlas += n
        save()
    }
}
