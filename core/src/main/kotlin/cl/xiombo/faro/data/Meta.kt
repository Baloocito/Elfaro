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
        "Lenta y pesada: 22 de daño en 180 grados. Empuja y aturde contra las paredes.", "+10 px de empuje y +0,2 s de aturdido"),
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
    /** Marea elegida para la próxima run, la más alta desbloqueada y la Marea baja. */
    var marea = 0
    var mareaMax = 0
    var mareaBaja = false
    /** Finales vistos ("A", "B", "C"). */
    val endings = HashSet<String>()
    /** Personaje elegido: "isla" o "campanero". */
    var hero = "isla"
    /** Lotes de exvotos comprados (0-5). */
    var lots = 0

    /** Botellas con mensaje leídas (ids de [Bottles]). Todas abren el final B. */
    val bottles = HashSet<String>()
    val allBottles get() = Bottles.all.all { it.id in bottles }

    const val PESCADORA = "pescadora"
    const val ANCLA = "ancla"
    const val CARTOGRAFA = "cartografa"
    const val CAMPANERO = "campanero"
    /** Amparo, la guardesa: rescatarla en el Muelle abre la tienda del Santuario. */
    const val GUARDESA = "guardesa"
    val shopOpen get() = GUARDESA in unlocks
    /** Náufragos rescatados (uno por bioma, §15). */
    const val RESCUE_CAMPANERO = "rescate_campanero"
    const val RESCUE_CARTOGRAFA = "rescate_cartografa"
    const val HIJA = "hija"
    /** El Campanero renegado comprado: se puede elegir como personaje. */
    val campaneroPlayable get() = CAMPANERO in unlocks

    fun load() {
        runCatching {
            val p = Gdx.app.getPreferences(PREFS)
            perlas = p.getInteger("perlas", 0).coerceAtLeast(0)
            luzLevel = p.getInteger("meta.luz", 0).coerceIn(0, 2)
            unlocks.clear(); unlocks += p.getString("meta.unlocks", "").split(',').filter { it.isNotBlank() }
            bosses.clear(); bosses += p.getString("meta.bosses", "").split(',').filter { it.isNotBlank() }
            bottles.clear(); bottles += p.getString("meta.bottles", "").split(',').filter { it.isNotBlank() }
            mareaMax = p.getInteger("meta.mareaMax", 0).coerceIn(0, Mareas.MAX)
            marea = p.getInteger("meta.marea", 0).coerceIn(0, mareaMax)
            mareaBaja = p.getBoolean("meta.baja", false)
            endings.clear(); endings += p.getString("meta.endings", "").split(',').filter { it.isNotBlank() }
            hero = p.getString("meta.hero", "isla")
            lots = p.getInteger("meta.lots", 0).coerceIn(0, Exvotos.LOTS)
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
            p.putString("meta.bottles", bottles.joinToString(","))
            p.putInteger("meta.mareaMax", mareaMax)
            p.putInteger("meta.marea", marea)
            p.putBoolean("meta.baja", mareaBaja)
            p.putString("meta.endings", endings.joinToString(","))
            p.putString("meta.hero", hero)
            p.putInteger("meta.lots", lots)
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
