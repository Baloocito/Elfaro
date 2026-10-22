package cl.xiombo.faro.world

import cl.xiombo.faro.data.Balance
import cl.xiombo.faro.data.ExvotoDef
import cl.xiombo.faro.data.Tag
import cl.xiombo.faro.data.Weapon
import com.badlogic.gdx.graphics.Color

enum class PedKind {
    EXVOTO, MECHA, MEJORA, MAPA, REFUGIO, ESCALERA, FIN, VIGILIA,
    // Santuario
    ARMA, INICIAL, META, DESCENSO,
    // Capilla de la ofrenda (Catedral): el mismo exvoto por dos precios distintos.
    OFRENDA_MAX, OFRENDA_MITAD,
    // Rescate: la jaula de la celda y Amparo en el Santuario.
    JAULA, AMPARO,
    /** Botella con mensaje (Sala de lectura, salas secretas). [metaId] = id de la botella. */
    BOTELLA,
}

/**
 * Objeto con el que se interactúa (E / USAR): exvotos, compras de la tienda, farol del refugio,
 * escalera, Farol de vigilia y, en el Santuario, armas, mejoras de meta y el pozo de descenso.
 * [price] es Luz en la run y Perlas en el Santuario.
 */
class Pedestal(
    val x: Float, val y: Float, val kind: PedKind,
    val exvoto: ExvotoDef? = null, val price: Int = 0,
    val weapon: Weapon? = null,
    val metaId: String? = null,
    val metaTitle: String = "",
    val metaDetail: String = "",
) {
    var taken = false
    /** Bloqueado (Santuario): se ve, pero explica qué falta. */
    var lockedText: String? = null
    /** Elegido (arma equipada o exvoto inicial). */
    var selected = false

    val title: String
        get() = when (kind) {
            PedKind.EXVOTO -> exvoto!!.name
            PedKind.MECHA -> "Mecha"
            PedKind.MEJORA -> "Mejora del ${weapon?.label ?: "arma"}"
            PedKind.MAPA -> "Carta del piso"
            PedKind.REFUGIO -> "Farol del refugio"
            PedKind.ESCALERA -> "Escalera al siguiente piso"
            PedKind.FIN -> "Luz del Muelle"
            PedKind.VIGILIA -> "Farol de vigilia"
            PedKind.ARMA -> weapon!!.label
            PedKind.INICIAL -> exvoto!!.name
            PedKind.META -> metaTitle
            PedKind.DESCENSO -> "Bajar al faro"
            PedKind.OFRENDA_MAX -> "Ofrecer 10 de Luz máxima por ${exvoto!!.name}"
            PedKind.OFRENDA_MITAD -> "Ofrecer la mitad de tu Luz por ${exvoto!!.name}"
            PedKind.JAULA -> "Abrir la celda"
            PedKind.AMPARO -> "Amparo, la guardesa"
            PedKind.BOTELLA -> "Botella con mensaje"
        }

    val detail: String
        get() = when (kind) {
            PedKind.EXVOTO -> {
                val e = exvoto!!
                val tag = if (e.tag == Tag.NINGUNA) "" else "${e.tag.label} · "
                "$tag${e.rarity.label}. ${e.desc}"
            }
            PedKind.MECHA -> "+10 de Luz máxima (tope 200)."
            PedKind.MEJORA -> "${weapon?.upgradeText ?: ""} (máx. 3)."
            PedKind.MAPA -> "Revela todas las salas del piso."
            PedKind.REFUGIO -> "+30 de Luz. Una vez por piso."
            PedKind.ESCALERA -> "Bajar más hondo en el faro."
            PedKind.FIN -> "Terminar el bioma."
            PedKind.VIGILIA -> "Recupera Luz antes del jefe: más cuanto menos te quede. Una vez."
            PedKind.ARMA -> lockedText?.let { "Bloqueada: $it." } ?: (weapon!!.desc + if (selected) " Equipada." else "")
            PedKind.INICIAL -> "Empieza la run con este exvoto. ${exvoto!!.desc}" + if (selected) " Elegido." else ""
            PedKind.META -> lockedText?.let { "$metaDetail Bloqueado: $it." } ?: metaDetail
            PedKind.DESCENSO -> "Empieza una run nueva."
            PedKind.OFRENDA_MAX, PedKind.OFRENDA_MITAD -> {
                val e = exvoto!!
                "${e.rarity.label}. ${e.desc} Rechazar no cuesta nada."
            }
            PedKind.JAULA -> if (selected) "Los guardias cayeron. Ábrela." else "Alguien sigue vivo ahí dentro. Primero, acaba con los guardias."
            PedKind.AMPARO -> "Hablar."
            PedKind.BOTELLA -> "Leerla. Cuenta para el final B."
        }
}

class Projectile(
    var x: Float, var y: Float, val vx: Float, val vy: Float,
    val r: Float, val dmg: Float, var life: Float, val color: Color,
    val cause: String = "Un proyectil",
) {
    var px = x
    var py = y
}

class Puddle(val x: Float, val y: Float, var t: Float) { val r = 11f }
class FireZone(val x: Float, val y: Float, var t: Float) { val r = 9f }
class Statue(val x: Float, val y: Float, var t: Float)

/** El Arpón de Isla: vuela, perfora, se clava y vuelve solo o al recogerlo. */
class Harpoon(var x: Float, var y: Float, val dx: Float, val dy: Float, val pierce: Int, val returnTime: Float) {
    enum class St { FLYING, STUCK, RETURNING }
    var st = St.FLYING
    var px = x
    var py = y
    var traveled = 0f
    var stuckT = 0f
    var hits = 0
    val hitSet = HashSet<Enemy>()
}

/**
 * Estadísticas de la run derivadas de los exvotos y mejoras.
 * Se recalcula cada vez que se consigue algo.
 */
class Stats(owned: List<ExvotoDef>, val weaponLevel: Int, val weapon: Weapon = Weapon.FAROL) {
    private val ids = owned.map { it.id }.toSet()
    private val tags = owned.groupingBy { it.tag }.eachCount()
    fun has(id: String) = id in ids
    fun tagCount(t: Tag) = tags[t] ?: 0

    val setFuego = tagCount(Tag.FUEGO) >= 3
    val setSal = tagCount(Tag.SAL) >= 3
    val setMarea = tagCount(Tag.MAREA) >= 3
    val setHueso = tagCount(Tag.HUESO) >= 3

    val burnTime = Balance.BURN_TIME + if (has("mecha_trenzada")) 1f else 0f
    val hazCost = if (has("ceniza_faro")) 6f else Balance.HAZ_COST
    val burnedDropBonus = if (has("corazon_vela")) 1 else 0
    val hazTrail = has("lengua_telmo")
    val slowedBonus = if (has("grano_sal")) 1.08f else 1f
    val thirdHitSlow = has("costra_sal")
    val statue = has("estatua_sal")
    val windSpeed = if (has("rosa_vientos")) 1.10f else 1f
    val dashCharges = if (has("caracola")) 2 else 1
    val wallSlam = has("ancla_plata")
    val firstHitBonus = if (has("diente_naufrago")) 4f else 0f
    val eliteBonus = if (has("vertebra_suelta")) 1.15f else 1f
    val rosario = has("rosario_vertebras")
    val eye = has("ojo_farero")
    val twinLamp = has("lampara_gemela")

    // Velar y su Fogonazo
    val flashDmg = if (has("pabilo_corto")) 10f else 0f
    val flashStun = if (has("sal_ojos")) 1.2f else 0f
    val flashOn = flashDmg > 0f || flashStun > 0f
    val flashRadius = if (has("marea_quieta")) 104f else 80f
    val veilSpeed = if (has("marea_quieta")) 1f else Balance.VEIL_MOVE_FACTOR

    /** La mejora del Farol es daño; las demás armas mejoran su rasgo propio (ver [Weapon.upgradeText]). */
    val weaponMul = if (weapon == Weapon.FAROL) 1f + 0.2f * weaponLevel else 1f
}
