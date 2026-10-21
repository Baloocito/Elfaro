package cl.xiombo.faro.world

import cl.xiombo.faro.data.Balance
import cl.xiombo.faro.data.ExvotoDef
import cl.xiombo.faro.data.Tag
import com.badlogic.gdx.graphics.Color

enum class PedKind { EXVOTO, MECHA, MEJORA, MAPA, REFUGIO, ESCALERA, FIN }

/** Objeto con el que se interactúa (E / USAR): exvotos, compras de la tienda, farol del refugio, escalera. */
class Pedestal(val x: Float, val y: Float, val kind: PedKind, val exvoto: ExvotoDef? = null, val price: Int = 0) {
    var taken = false

    val title: String
        get() = when (kind) {
            PedKind.EXVOTO -> exvoto!!.name
            PedKind.MECHA -> "Mecha"
            PedKind.MEJORA -> "Mejora del Farol"
            PedKind.MAPA -> "Carta del piso"
            PedKind.REFUGIO -> "Farol del refugio"
            PedKind.ESCALERA -> "Escalera al siguiente piso"
            PedKind.FIN -> "Luz del Muelle"
        }

    val detail: String
        get() = when (kind) {
            PedKind.EXVOTO -> {
                val e = exvoto!!
                val tag = if (e.tag == Tag.NINGUNA) "" else "${e.tag.label} · "
                "$tag${e.rarity.label}. ${e.desc}"
            }
            PedKind.MECHA -> "+10 de Luz máxima (tope 200)."
            PedKind.MEJORA -> "+20 % de daño del Farol (máx. 3)."
            PedKind.MAPA -> "Revela todas las salas del piso."
            PedKind.REFUGIO -> "+30 de Luz. Una vez por piso."
            PedKind.ESCALERA -> "Bajar más hondo en el faro."
            PedKind.FIN -> "Terminar el bioma."
        }
}

class Projectile(
    var x: Float, var y: Float, val vx: Float, val vy: Float,
    val r: Float, val dmg: Float, var life: Float, val color: Color,
) {
    var px = x
    var py = y
}

class Puddle(val x: Float, val y: Float, var t: Float) { val r = 11f }
class FireZone(val x: Float, val y: Float, var t: Float) { val r = 9f }
class Statue(val x: Float, val y: Float, var t: Float)

/**
 * Estadísticas de la run derivadas de los exvotos y mejoras.
 * Se recalcula cada vez que se consigue algo.
 */
class Stats(owned: List<ExvotoDef>, val weaponLevel: Int) {
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

    val weaponMul = 1f + 0.2f * weaponLevel
}
