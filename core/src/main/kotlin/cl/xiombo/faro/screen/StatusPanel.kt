package cl.xiombo.faro.screen

import cl.xiombo.faro.audio.Audio
import cl.xiombo.faro.data.Balance
import cl.xiombo.faro.data.Bottles
import cl.xiombo.faro.data.Exvotos
import cl.xiombo.faro.data.Mareas
import cl.xiombo.faro.data.Meta
import cl.xiombo.faro.data.Pal
import cl.xiombo.faro.data.Story
import cl.xiombo.faro.data.Tag
import cl.xiombo.faro.data.Weapon
import cl.xiombo.faro.input.Controls
import cl.xiombo.faro.input.Gamepad
import cl.xiombo.faro.world.World
import com.badlogic.gdx.graphics.Color
import kotlin.math.roundToInt

/**
 * Pantalla de Estado: un menú propio, aparte de la pausa, con su botón en cada dispositivo
 * (Tab o I en PC, Select en el mando, el botón "i" en táctil). Tres pestañas:
 * ISLA (números actuales frente a los de base), EXVOTOS (lo que llevas y los conjuntos) y
 * SANTUARIO (la meta-progresión). Pausa el juego mientras está abierta.
 */
class StatusPanel {
    enum class Tab(val label: String) { ISLA("ISLA"), EXVOTOS("EXVOTOS"), SANTUARIO("SANTUARIO") }

    /**
     * Una fila. [tone]: 1 mejor que la base (verde), -1 peor (rojo), 0 igual.
     * [header] dibuja la fila como título de sección.
     */
    class Row(
        val label: String,
        val value: String = "",
        val base: String = "",
        val tone: Int = 0,
        val header: Boolean = false,
        val color: Color? = null,
    )

    var open = false
        private set
    var tab = Tab.ISLA
        private set
    var scroll = 0
        private set
    var rows: List<Row> = emptyList()
        private set
    private var world: World? = null

    val maxScroll get() = (rows.size - VISIBLE).coerceAtLeast(0)

    fun openFor(w: World) {
        world = w
        open = true
        tab = if (w.inSanctuary) Tab.SANTUARIO else Tab.ISLA
        scroll = 0
        rebuild()
        Audio.play("ui_ok")
    }

    fun close() {
        open = false
        Audio.play("ui_move")
    }

    private fun setTab(t: Tab) {
        if (t == tab) return
        tab = t
        scroll = 0
        rebuild()
        Audio.play("ui_move")
    }

    private fun scrollBy(d: Int) {
        val s = (scroll + d).coerceIn(0, maxScroll)
        if (s != scroll) { scroll = s; Audio.play("ui_move") }
    }

    fun update(pad: Gamepad, c: Controls) {
        val mi = c.menuInput
        if (mi.status || mi.back || mi.pause || pad.back || pad.selectPressed || pad.startPressed) { close(); return }
        val tabs = Tab.entries
        if (mi.left || pad.navLeft) setTab(tabs[(tab.ordinal + tabs.size - 1) % tabs.size])
        if (mi.right || pad.navRight) setTab(tabs[(tab.ordinal + 1) % tabs.size])
        if (mi.up || pad.navUp) scrollBy(-3)
        if (mi.down || pad.navDown) scrollBy(3)
        if (mi.hasTap) {
            val x = mi.tapX
            val y = mi.tapY
            when {
                x >= CLOSE_X - 12f && y >= CLOSE_Y - 12f -> close()
                y >= TAB_Y - 8f && y <= TAB_Y + 10f -> tabs.forEachIndexed { i, t ->
                    if (x >= tabX(i) - TAB_W / 2f && x <= tabX(i) + TAB_W / 2f) setTab(t)
                }
                y in LIST_BOTTOM..LIST_TOP -> scrollBy(if (y > (LIST_TOP + LIST_BOTTOM) / 2f) -VISIBLE / 2 else VISIBLE / 2)
            }
        }
    }

    // ------------------------------------------------------------ contenido

    private fun rebuild() {
        val w = world ?: return
        rows = when (tab) {
            Tab.ISLA -> islaRows(w)
            Tab.EXVOTOS -> exvotoRows(w)
            Tab.SANTUARIO -> santuarioRows()
        }
    }

    private fun f(v: Float): String {
        val r = (v * 10f).roundToInt() / 10f
        return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString().replace('.', ',')
    }

    private fun pct(mul: Float) = "${if (mul >= 1f) "+" else ""}${((mul - 1f) * 100f).roundToInt()} %"

    private fun tone(v: Float, base: Float, higherIsBetter: Boolean = true): Int = when {
        kotlin.math.abs(v - base) < 0.01f -> 0
        (v > base) == higherIsBetter -> 1
        else -> -1
    }

    private fun islaRows(w: World): List<Row> {
        val st = w.stats
        val p = w.player
        val out = ArrayList<Row>()
        val hero = if (Meta.hero == "campanero") "Bruno, el campanero" else "Isla, la farera"
        out += Row(hero, if (w.inSanctuary) "Santuario" else "Piso ${w.floorNum}  ·  ${Mareas.label(w.marea, w.baja)}", header = true)

        // Luz
        out += Row("LUZ", header = true)
        val baseMax = if (w.marea >= 7) 80f else Balance.LUZ_MAX
        out += Row("Luz", "${p.luz.roundToInt()} / ${p.luzMax.roundToInt()}", "máx. base ${baseMax.toInt()}", tone(p.luzMax, baseMax))
        val baseR = Balance.lightRadius(p.luz)
        val r = w.playerLight()
        out += Row("Radio de luz", "${r.roundToInt()} px", "con esta Luz: ${baseR.roundToInt()} px", tone(r, baseR))
        if (st.orbMul != 1f || st.extraOrb > 0) out += Row("Orbes de Luz", buildString {
            if (st.orbMul != 1f) append("${pct(st.orbMul)} de Luz")
            if (st.extraOrb > 0) { if (isNotEmpty()) append("  ·  "); append("+${st.extraOrb} por enemigo") }
        }, "", 1)

        // Arma
        val wp = p.weapon
        out += Row("ARMA", header = true)
        out += Row(wp.label, "nivel ${w.weaponLevel} / 3", if (w.weaponLevel < 3) "mejora: ${wp.upgradeText}" else "al máximo", if (w.weaponLevel > 0) 1 else 0)
        val baseDmg = when (wp) {
            Weapon.FAROL -> Balance.FAROL_DMG
            Weapon.ARPON -> 14f
            Weapon.ANCLA -> 22f
            Weapon.CAMPANA -> 10f
        }
        val dmg = (baseDmg * st.weaponMul + st.flatDmg) * st.dmgMul
        out += Row("Daño por golpe", f(dmg), "base ${f(baseDmg)}", tone(dmg, baseDmg))
        when (wp) {
            Weapon.FAROL -> out += Row("Combo", "3 golpes; el 3.º quema", "")
            Weapon.ARPON -> {
                out += Row("Perforación", "${2 + w.weaponLevel} enemigos", "base 2", tone(2f + w.weaponLevel, 2f))
                out += Row("Vuelta", "${f(1.5f - 0.3f * w.weaponLevel)} s", "base 1,5 s", tone(1.5f - 0.3f * w.weaponLevel, 1.5f, false))
            }
            Weapon.ANCLA -> {
                out += Row("Empuje", "${f((240f + 28f * w.weaponLevel) * st.knockMul / 6f)} px", "base 40 px", tone((240f + 28f * w.weaponLevel) * st.knockMul, 240f))
                out += Row("Aturdido contra pared", "${f(0.8f + 0.2f * w.weaponLevel)} s", "base 0,8 s", tone(0.8f + 0.2f * w.weaponLevel, 0.8f))
            }
            Weapon.CAMPANA -> out += Row("Radio del pulso", "${(48f + 8f * w.weaponLevel).toInt()} px", "base 48 px", tone(48f + 8f * w.weaponLevel, 48f))
        }
        // Bonos que dependen del objetivo
        val cond = ArrayList<Row>()
        if (st.firstHitBonus > 0f) cond += Row("Primer golpe", "+${f(st.firstHitBonus)} de daño", "", 1)
        if (st.eliteBonus != 1f) cond += Row("Contra élites y jefes", pct(st.eliteBonus), "", 1)
        if (st.bossMul != 1f) cond += Row("Contra jefes", pct(st.bossMul), "", 1)
        if (st.burnedBonus != 1f) cond += Row("Contra quemados", pct(st.burnedBonus), "", 1)
        if (st.slowedBonus != 1f) cond += Row("Contra ralentizados", pct(st.slowedBonus), "", 1)
        if (st.lowHpBonus != 1f) cond += Row("Contra heridos (<25 %)", pct(st.lowHpBonus), "", 1)
        if (st.knockMul != 1f && wp != Weapon.ANCLA) cond += Row("Empuje", pct(st.knockMul), "", 1)
        if (st.setHueso) cond += Row("Remate (conjunto Hueso)", "por debajo del 15 %", "", 1)
        if (cond.isNotEmpty()) { out += Row("BONOS AL GOLPEAR", header = true); out += cond }

        // Movimiento
        out += Row("MOVIMIENTO", header = true)
        val spd = Balance.SPEED * st.speedBonus * p.heroSpeed
        out += Row("Velocidad", "${spd.roundToInt()} px/s", "base ${Balance.SPEED.toInt()}", tone(spd, Balance.SPEED))
        out += Row("Cargas de Destello", "${st.dashCharges}", "base 1", tone(st.dashCharges.toFloat(), 1f))
        val rech = Balance.DASH_COOLDOWN * st.dashRechargeMul
        out += Row("Recarga del Destello", "${f(rech)} s", "base ${f(Balance.DASH_COOLDOWN)} s", tone(rech, Balance.DASH_COOLDOWN, false))
        val ifr = Balance.DASH_IFRAMES + st.dashIframesBonus
        out += Row("Invulnerable al destellar", "${f(ifr)} s", "base ${f(Balance.DASH_IFRAMES)} s", tone(ifr, Balance.DASH_IFRAMES))

        // Haz y fuego
        out += Row("HAZ Y FUEGO", header = true)
        out += Row("Coste del Haz", "${f(st.hazCost)} de Luz", "base ${f(Balance.HAZ_COST)}", tone(st.hazCost, Balance.HAZ_COST, false))
        out += Row("Daño del Haz", "${f(Balance.HAZ_DPS)} por segundo", if (st.hazBurns) "y quema" else "", if (st.hazBurns) 1 else 0)
        out += Row("Quemadura", "${f(st.burnDps)}/s durante ${f(st.burnTime)} s", "base ${f(Balance.BURN_DPS)}/s, ${f(Balance.BURN_TIME)} s",
            tone(st.burnDps * st.burnTime, Balance.BURN_DPS * Balance.BURN_TIME))

        // Velar
        out += Row("VELAR", header = true)
        out += Row("Radio velando", "${Balance.VEIL_RADIUS.toInt()} px", "")
        out += Row("Velocidad velando", "${(st.veilSpeed * 100f).roundToInt()} %", "base ${(Balance.VEIL_MOVE_FACTOR * 100f).roundToInt()} %", tone(st.veilSpeed, Balance.VEIL_MOVE_FACTOR))
        if (st.flashOn) {
            val parts = ArrayList<String>()
            if (st.flashDmg > 0f) parts += "${f(st.flashDmg)} de daño"
            if (st.flashStun > 0f) parts += "aturde ${f(st.flashStun)} s"
            out += Row("Fogonazo", parts.joinToString(", ") + " a ${st.flashRadius.toInt()} px", "cada ${f(Balance.FLASH_COOLDOWN)} s", 1)
        } else out += Row("Fogonazo", "no", "con Pabilo corto o Sal en los ojos")

        if (!w.inSanctuary) {
            out += Row("ESTA RUN", header = true)
            val mm = (w.time / 60f).toInt()
            val ss = (w.time % 60f).toInt()
            out += Row("Tiempo", "$mm:${ss.toString().padStart(2, '0')}", "")
            out += Row("Bajas", "${w.kills}", "")
            out += Row("Perlas ganadas", "${w.perlasRun}", "")
            out += Row("Semilla", w.seedCode, "")
            if (w.marea > 0 || w.baja) out += Row("Marea", if (w.baja) Mareas.BAJA_TEXT else Mareas.RULES[w.marea], "")
        }
        return out
    }

    private fun exvotoRows(w: World): List<Row> {
        val out = ArrayList<Row>()
        val st = w.stats
        out += Row("CONJUNTOS", "3 de la misma etiqueta", header = true)
        for (t in Tag.entries) {
            if (t == Tag.NINGUNA) continue
            val n = st.tagCount(t)
            val on = n >= 3
            out += Row("${t.label}  ${minOf(n, 3)}/3", if (on) "ACTIVO" else "", "", if (on) 1 else 0, color = t.color)
            wrap(Exvotos.setBonus[t] ?: "").forEach { out += Row("   $it", color = if (on) Pal.CREAM else Pal.SHELL) }
        }
        out += Row("EXVOTOS DE ESTA RUN", "${w.owned.size}", header = true)
        if (w.owned.isEmpty()) out += Row(if (w.inSanctuary) "Empiezas sin exvotos. Elige uno inicial en la tienda." else "Todavía no llevas ninguno.", color = Pal.SHELL)
        for (e in w.owned) {
            val tag = if (e.tag == Tag.NINGUNA) "" else e.tag.label
            out += Row(e.name, e.rarity.label, tag, color = e.rarity.color)
            wrap(e.desc).forEach { out += Row("   $it", color = Pal.CREAM) }
        }
        return out
    }

    private fun santuarioRows(): List<Row> {
        val out = ArrayList<Row>()
        out += Row("SANTUARIO", "${Meta.perlas} Perlas", header = true)
        out += Row("Tienda", if (Meta.shopOpen) "abierta" else "cerrada", if (Meta.shopOpen) "" else "rescata a Amparo en el Muelle", if (Meta.shopOpen) 1 else -1)
        out += Row("Luz máxima inicial", "+${Meta.luzLevel * 10}", "nivel ${Meta.luzLevel} / 2", if (Meta.luzLevel > 0) 1 else 0)
        out += Row("Lotes de exvotos", "${Meta.lots} / ${Exvotos.LOTS}", "${Exvotos.all.count { it.lot <= Meta.lots }} exvotos en el pozo", if (Meta.lots > 0) 1 else 0)
        out += Row("Exvoto inicial", Meta.startExvoto?.let { id -> Exvotos.all.firstOrNull { it.id == id }?.name } ?: "ninguno", "")

        out += Row("ARMAS", header = true)
        for (wp in Weapon.entries) {
            val ok = Meta.isUnlocked(wp)
            out += Row(wp.label, if (wp == Meta.weapon) "equipada" else if (ok) "disponible" else "bloqueada",
                if (ok) "" else Meta.unlockRequirement(wp), if (ok) 1 else 0)
        }

        out += Row("NÁUFRAGOS", header = true)
        for (c in Story.CASTAWAYS) {
            val ok = c.key in Meta.unlocks
            out += Row(c.name, if (ok) "rescatado" else "sin rescatar", c.biome.label, if (ok) 1 else 0)
        }
        out += Row("Personaje", if (Meta.hero == "campanero") "Bruno" else "Isla", if (Meta.campaneroPlayable) "Bruno disponible" else "")

        out += Row("JEFES", header = true)
        val bosses = listOf("sirena" to "La Sirena de Brea", "obispo" to "El Obispo Coral", "cartografo" to "El Cartógrafo",
            "lampara" to "La Lámpara Madre", "farero" to "El Farero Antiguo", "criatura" to "La criatura de la fosa")
        for ((id, name) in bosses) {
            val ok = id in Meta.bosses
            out += Row(name, if (ok) "vencido" else "-", "", if (ok) 1 else 0)
        }

        out += Row("HISTORIA", header = true)
        out += Row("Botellas leídas", "${Meta.bottles.size} / ${Bottles.all.size}", "todas abren el final B", if (Meta.allBottles) 1 else 0)
        for ((k, name) in listOf("A" to "Final A · Reencender", "B" to "Final B · Ser la lámpara", "C" to "Final C · Dejar la oscuridad")) {
            val ok = k in Meta.endings
            out += Row(name, if (ok) "visto" else "-", "", if (ok) 1 else 0)
        }
        out += Row("Marea", "máx. ${Meta.mareaMax} / ${Mareas.MAX}", "elegida: ${Mareas.label(Meta.marea, Meta.mareaBaja)}", if (Meta.mareaMax > 0) 1 else 0)
        return out
    }

    /** Corta un texto en líneas de [WRAP] caracteres como máximo. */
    private fun wrap(s: String): List<String> {
        val out = ArrayList<String>()
        var line = StringBuilder()
        for (word in s.split(' ')) {
            if (line.isNotEmpty() && line.length + 1 + word.length > WRAP) { out += line.toString(); line = StringBuilder() }
            if (line.isNotEmpty()) line.append(' ')
            line.append(word)
        }
        if (line.isNotEmpty()) out += line.toString()
        return out
    }

    companion object {
        // Disposición en coordenadas internas (480 × 270), compartida con Renderer.
        const val PANEL_X = 24f
        const val PANEL_W = 432f
        const val TAB_Y = 228f
        const val TAB_W = 96f
        fun tabX(i: Int) = 240f + (i - 1) * 110f
        const val CLOSE_X = 446f
        const val CLOSE_Y = 252f
        const val LIST_TOP = 210f
        const val ROW_H = 10.5f
        const val VISIBLE = 16
        val LIST_BOTTOM = LIST_TOP - VISIBLE * ROW_H
        const val COL_LABEL = 40f
        const val COL_VALUE = 230f
        const val COL_BASE = 330f
        const val WRAP = 82
    }
}
