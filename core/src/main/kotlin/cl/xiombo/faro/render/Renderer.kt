package cl.xiombo.faro.render

import cl.xiombo.faro.data.Balance
import cl.xiombo.faro.data.Biome
import cl.xiombo.faro.data.Story
import cl.xiombo.faro.data.Bottles
import cl.xiombo.faro.data.Mareas
import cl.xiombo.faro.data.Settings
import cl.xiombo.faro.data.RunLog
import cl.xiombo.faro.world.Anguila
import cl.xiombo.faro.world.Criatura
import cl.xiombo.faro.world.FareroAntiguo
import cl.xiombo.faro.world.Bibliotecario
import cl.xiombo.faro.world.Campanero
import cl.xiombo.faro.world.Cartografo
import cl.xiombo.faro.world.Copista
import cl.xiombo.faro.world.Corazon
import cl.xiombo.faro.world.HijoDelFaro
import cl.xiombo.faro.world.LamparaMadre
import cl.xiombo.faro.world.OjoFosa
import cl.xiombo.faro.world.PaperClone
import cl.xiombo.faro.world.Polilla
import cl.xiombo.faro.world.Tinta
import cl.xiombo.faro.world.Trap
import cl.xiombo.faro.world.Vidriero
import cl.xiombo.faro.world.Cofrade
import cl.xiombo.faro.world.Monaguillo
import cl.xiombo.faro.world.Obispo
import cl.xiombo.faro.world.Vela
import cl.xiombo.faro.data.Meta
import cl.xiombo.faro.data.Weapon
import cl.xiombo.faro.world.Harpoon
import cl.xiombo.faro.data.Pal
import cl.xiombo.faro.data.Tag
import cl.xiombo.faro.input.Action
import cl.xiombo.faro.input.Bindings
import cl.xiombo.faro.input.Controls
import cl.xiombo.faro.input.Gamepad
import cl.xiombo.faro.screen.PauseMenu
import cl.xiombo.faro.screen.StatusPanel
import cl.xiombo.faro.world.Affix
import cl.xiombo.faro.world.Ahogado
import cl.xiombo.faro.world.Body
import cl.xiombo.faro.world.Cangrejo
import cl.xiombo.faro.world.CellType
import cl.xiombo.faro.world.EState
import cl.xiombo.faro.world.Enemy
import cl.xiombo.faro.world.FloorMap
import cl.xiombo.faro.world.Medusa
import cl.xiombo.faro.world.PedKind
import cl.xiombo.faro.world.Pedestal
import cl.xiombo.faro.world.Player
import cl.xiombo.faro.world.Room
import cl.xiombo.faro.world.Sirena
import cl.xiombo.faro.world.World
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Disposable
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tubería de dibujo (§10):
 * 1. Escena a un FrameBuffer de 480×270.
 * 2. Buffer de luz: cada fuente suma un degradado radial.
 * 3. Shader final: 5 bandas + Bayer 4×4, escalado entero con bandas negras.
 * 4. HUD sin iluminar y controles táctiles en espacio de pantalla.
 * Hasta tener sprites, todo se dibuja con formas de colores de las paletas del documento.
 */
class Renderer : Disposable {
    private val iw = Balance.W
    private val ih = Balance.H
    private val scene = FrameBuffer(Pixmap.Format.RGBA8888, iw, ih, false)
    private val lightFb = FrameBuffer(Pixmap.Format.RGBA8888, iw, ih, false)
    private val sceneRegion = TextureRegion(scene.colorBufferTexture).apply { flip(false, true) }
    private val shapes = ShapeRenderer()
    private val batch = SpriteBatch()
    private val cam = OrthographicCamera(iw.toFloat(), ih.toFloat())
    private val internalProj = Matrix4().setToOrtho2D(0f, 0f, iw.toFloat(), ih.toFloat())
    private val screenProj = Matrix4()
    private val shader: ShaderProgram
    private val bayer: Texture
    private val radial: Texture
    private val font = BitmapFont()
    private val glyphs = GlyphLayout()

    var scale = 1
        private set
    var ox = 0
        private set
    var oy = 0
        private set
    private var camLeft = 0f
    private var camBottom = 0f

    var debug = false
    var lighting = true

    init {
        scene.colorBufferTexture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
        lightFb.colorBufferTexture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
        ShaderProgram.pedantic = false
        shader = ShaderProgram(Gdx.files.internal("shaders/final.vert"), Gdx.files.internal("shaders/final.frag"))
        require(shader.isCompiled) { "Error al compilar el shader: ${shader.log}" }
        bayer = makeBayer()
        radial = makeRadial()
        font.region.texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
        font.setUseIntegerPositions(true)
        updateLayout()
    }

    // ---------- texturas generadas ----------

    private fun makeBayer(): Texture {
        val m = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
        val pm = Pixmap(4, 4, Pixmap.Format.RGBA8888)
        for (y in 0 until 4) for (x in 0 until 4) {
            val v = m[x + y * 4] / 16f
            pm.setColor(v, v, v, 1f)
            pm.drawPixel(x, y)
        }
        val t = Texture(pm)
        pm.dispose()
        t.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
        t.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat)
        return t
    }

    private fun makeRadial(): Texture {
        val n = 128
        val pm = Pixmap(n, n, Pixmap.Format.RGBA8888)
        pm.blending = Pixmap.Blending.None
        val c = (n - 1) / 2f
        for (y in 0 until n) for (x in 0 until n) {
            val d = sqrt((x - c) * (x - c) + (y - c) * (y - c)) / (n / 2f)
            val v = ((1f - d) * 1.35f).coerceIn(0f, 1f)
            pm.setColor(v, v, v, 1f)
            pm.drawPixel(x, y)
        }
        val t = Texture(pm)
        pm.dispose()
        t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        return t
    }

    // ---------- coordenadas ----------

    /** Escalado entero (§10): ×2, ×3, ×4… con bandas negras si sobra espacio. */
    private fun updateLayout() {
        val bw = Gdx.graphics.backBufferWidth
        val bh = Gdx.graphics.backBufferHeight
        scale = max(1, min(bw / iw, bh / ih))
        ox = (bw - iw * scale) / 2
        oy = (bh - ih * scale) / 2
    }

    /** Pantalla → coordenadas internas 480×270 (para tocar el menú). */
    fun screenToInternal(sx: Float, sy: Float, out: Vector2): Vector2 {
        val bh = Gdx.graphics.backBufferHeight
        return out.set((sx - ox) / scale, (bh - sy - oy) / scale)
    }

    fun screenToWorld(sx: Float, sy: Float, out: Vector2): Vector2 {
        val bh = Gdx.graphics.backBufferHeight
        val ix = (sx - ox) / scale
        val iy = (bh - sy - oy) / scale
        return out.set(ix + camLeft, iy + camBottom)
    }

    /** Sala centrada si cabe; si es más grande, la cámara sigue a Isla sin salirse. */
    private fun placeCamera(w: World, a: Float) {
        val r = w.room
        val px = w.player.ix(a)
        val py = w.player.iy(a) + 8f
        val cx = if (r.width <= iw) r.width / 2f else px.coerceIn(iw / 2f, r.width - iw / 2f)
        val cy = if (r.height <= ih) r.height / 2f else py.coerceIn(ih / 2f, r.height - ih / 2f)
        camLeft = (cx - iw / 2f).roundToInt().toFloat()
        camBottom = (cy - ih / 2f).roundToInt().toFloat()
        val sh = if (Settings.shake) w.shake else 0f
        val sx = if (sh > 0f) MathUtils.random(-sh, sh) else 0f
        val sy = if (sh > 0f) MathUtils.random(-sh, sh) else 0f
        cam.position.set(camLeft + iw / 2f + sx.roundToInt(), camBottom + ih / 2f + sy.roundToInt(), 0f)
        cam.update()
    }

    // ---------- fotograma ----------

    private var menu: PauseMenu? = null
    private var pad: Gamepad? = null

    fun render(w: World, alpha: Float, controls: Controls, menu: PauseMenu, pad: Gamepad, status: StatusPanel) {
        this.menu = menu
        this.status = status
        this.pad = pad
        updateLayout()
        placeCamera(w, alpha)
        drawScene(w, alpha)
        drawLights(w, alpha)
        composite()
        drawHud(w)
        drawScreenSpace(w, controls)
        if (menu.open) drawMenu(menu, pad, controls)
        else if (status.open) drawStatus(status, pad, controls)
    }

    private var status: StatusPanel? = null

    /** Tecla o botón que abre el Estado, según el dispositivo. */
    private fun statusKey(c: Controls) = when {
        c.padActive -> "Select"
        c.touch -> "i"
        else -> "Tab"
    }

    /** Pantalla de Estado (ver [StatusPanel]): pestañas, filas con valor y base, y desplazamiento. */
    private fun drawStatus(s: StatusPanel, pad: Gamepad, c: Controls) {
        val bw = Gdx.graphics.backBufferWidth
        val bh = Gdx.graphics.backBufferHeight
        Gdx.gl.glViewport(ox, oy, iw * scale, ih * scale)
        Gdx.gl.glEnable(GL20.GL_BLEND)
        shapes.projectionMatrix = internalProj
        shapes.begin(ShapeType.Filled)
        col(Pal.OUTLINE, 0.86f)
        shapes.rect(0f, 0f, iw.toFloat(), ih.toFloat())
        // Marco del cuaderno
        col(Pal.NIGHT, 0.92f)
        shapes.rect(StatusPanel.PANEL_X, 26f, StatusPanel.PANEL_W, 236f)
        col(Pal.LAMP_DIM)
        shapes.rect(StatusPanel.PANEL_X, 261f, StatusPanel.PANEL_W, 1f)
        shapes.rect(StatusPanel.PANEL_X, 26f, StatusPanel.PANEL_W, 1f)
        // Pestañas
        StatusPanel.Tab.entries.forEachIndexed { i, t ->
            val x = StatusPanel.tabX(i) - StatusPanel.TAB_W / 2f
            val on = t == s.tab
            col(if (on) Pal.LAMP else Pal.SEAM, if (on) 0.95f else 0.8f)
            shapes.rect(x, StatusPanel.TAB_Y - 7f, StatusPanel.TAB_W, 15f)
        }
        col(Pal.LAMP_DIM, 0.7f)
        shapes.rect(StatusPanel.PANEL_X + 8f, StatusPanel.TAB_Y - 10f, StatusPanel.PANEL_W - 16f, 1f)
        // Bandas de sección
        val first = s.scroll
        val last = minOf(s.rows.size, first + StatusPanel.VISIBLE)
        for (i in first until last) {
            val r = s.rows[i]
            if (!r.header) continue
            val y = StatusPanel.LIST_TOP - (i - first) * StatusPanel.ROW_H
            col(Pal.SEAM, 0.75f)
            shapes.rect(StatusPanel.PANEL_X + 8f, y - 8f, StatusPanel.PANEL_W - 16f, StatusPanel.ROW_H - 1f)
        }
        // Barra de desplazamiento
        if (s.maxScroll > 0) {
            val top = StatusPanel.LIST_TOP
            val h = StatusPanel.VISIBLE * StatusPanel.ROW_H
            col(Pal.SEAM, 0.8f)
            shapes.rect(StatusPanel.PANEL_X + StatusPanel.PANEL_W - 7f, top - h, 2f, h)
            val k = StatusPanel.VISIBLE.toFloat() / s.rows.size
            val off = s.scroll.toFloat() / s.rows.size
            col(Pal.LAMP_DIM)
            shapes.rect(StatusPanel.PANEL_X + StatusPanel.PANEL_W - 7f, top - h * (off + k), 2f, h * k)
        }
        shapes.end()

        Gdx.gl.glViewport(0, 0, bw, bh)
        batch.projectionMatrix = screenProj
        batch.begin()
        text("ESTADO", iw / 2f, 250f, Pal.LAMP, align = 0)
        text("X", StatusPanel.CLOSE_X, StatusPanel.CLOSE_Y, Pal.CREAM, align = 0)
        StatusPanel.Tab.entries.forEachIndexed { i, t ->
            text(t.label, StatusPanel.tabX(i), StatusPanel.TAB_Y + 3f, if (t == s.tab) Pal.OUTLINE else Pal.CREAM, align = 0)
        }
        for (i in first until last) {
            val r = s.rows[i]
            val y = StatusPanel.LIST_TOP - (i - first) * StatusPanel.ROW_H
            if (r.header) {
                text(r.label, StatusPanel.COL_LABEL - 6f, y, Pal.LAMP_HI)
                if (r.value.isNotEmpty()) text(r.value, StatusPanel.PANEL_X + StatusPanel.PANEL_W - 14f, y, Pal.SHELL, align = 1)
                continue
            }
            text(r.label, StatusPanel.COL_LABEL, y, r.color ?: Pal.CREAM)
            if (r.value.isNotEmpty()) {
                val vc = when (r.tone) { 1 -> Pal.GREEN_LAMP; -1 -> Pal.CORAL; else -> Pal.CREAM }
                text(r.value, StatusPanel.COL_VALUE, y, vc)
            }
            if (r.base.isNotEmpty()) text(r.base, StatusPanel.COL_BASE, y, Pal.SHELL)
        }
        val hint = when {
            c.padActive || pad.controller != null -> "Izquierda/derecha: pestaña  ·  arriba/abajo: desplazar  ·  B o Select: cerrar"
            c.touch -> "Toca una pestaña  ·  toca arriba o abajo para desplazar  ·  X para cerrar"
            else -> "Izquierda/derecha: pestaña  ·  arriba/abajo: desplazar  ·  Tab o Esc: cerrar"
        }
        text(hint, iw / 2f, 14f, Pal.SHELL, align = 0)
        batch.end()
        font.color = Color.WHITE
    }

    /** Nombre del botón para una acción según el dispositivo con el que se juega. */
    private fun keyLabel(c: Controls, a: Action): String = when {
        c.padActive -> Bindings.label(pad?.controller, a)
        c.touch -> a.label.uppercase()
        else -> when (a) { Action.ATTACK -> "Clic"; Action.DASH -> "Espacio"; Action.HAZ -> "Clic der."; Action.USE -> "E"; Action.VELAR -> "C" }
    }

    private fun anyKeyText(c: Controls, what: String) = when {
        c.padActive -> "Pulsa ${Bindings.label(pad?.controller, Action.DASH)} para $what"
        c.touch -> "Toca para $what"
        else -> "Pulsa una tecla para $what"
    }

    private fun actionColor(a: Action): Color = when (a) {
        Action.ATTACK -> Pal.LAMP
        Action.DASH -> Pal.FOAM
        Action.HAZ -> Pal.CORAL
        Action.USE -> Pal.MEDUSA
        Action.VELAR -> Pal.TIDE
    }

    private fun drawMenu(m: PauseMenu, pad: Gamepad, c: Controls) {
        if (m.mode == PauseMenu.Mode.CONFIG) { drawConfig(m, pad, c); return }
        val bw = Gdx.graphics.backBufferWidth
        val bh = Gdx.graphics.backBufferHeight
        Gdx.gl.glViewport(ox, oy, iw * scale, ih * scale)
        Gdx.gl.glEnable(GL20.GL_BLEND)
        shapes.projectionMatrix = internalProj
        shapes.begin(ShapeType.Filled)
        val titlePage = m.page != PauseMenu.Page.PAUSE
        col(Pal.OUTLINE, if (titlePage) 0.62f else 0.82f)
        shapes.rect(0f, 0f, iw.toFloat(), ih.toFloat())
        val labels = m.labels
        val n = labels.size
        val x0 = (iw - PauseMenu.ITEM_W) / 2f
        if (titlePage) {
            // Haz del faro detrás del título.
            col(Pal.LAMP, 0.10f)
            shapes.triangle(iw / 2f, 236f, iw / 2f - 160f, 270f, iw / 2f + 160f, 270f)
            col(Pal.LAMP_DIM)
            shapes.rect(iw / 2f - 70f, 226f, 140f, 1f)
        }
        for (i in 0 until n) {
            val y = PauseMenu.itemYFor(m.page, i, n)
            col(if (i == m.sel) Pal.LAMP else Pal.NIGHT, if (i == m.sel) 0.9f else 0.8f)
            shapes.rect(x0, y - PauseMenu.itemH(n) / 2f, PauseMenu.ITEM_W, PauseMenu.itemH(n))
        }
        if (m.page == PauseMenu.Page.SEED) {
            for (i in 0 until m.seedChars.size) {
                val sx = PauseMenu.seedSlotX(i)
                col(if (i == m.seedSlot) Pal.LAMP else Pal.NIGHT, 0.9f)
                shapes.rect(sx + 1f, PauseMenu.SEED_Y - PauseMenu.SEED_H / 2f, PauseMenu.SEED_W - 2f, PauseMenu.SEED_H)
                col(Pal.CREAM, 0.5f)
                shapes.triangle(sx + 8f, PauseMenu.SEED_Y + 10f, sx + 16f, PauseMenu.SEED_Y + 10f, sx + 12f, PauseMenu.SEED_Y + 14f)
                shapes.triangle(sx + 8f, PauseMenu.SEED_Y - 10f, sx + 16f, PauseMenu.SEED_Y - 10f, sx + 12f, PauseMenu.SEED_Y - 14f)
            }
        }
        shapes.end()

        Gdx.gl.glViewport(0, 0, bw, bh)
        batch.projectionMatrix = screenProj
        batch.begin()
        text(m.title, iw / 2f, if (titlePage) 240f else 214f, Pal.LAMP, align = 0)
        if (m.page == PauseMenu.Page.TITLE) text("Perlas ${Meta.perlas}" + if (Meta.shopOpen) "" else "  ·  la tienda del Santuario sigue cerrada", iw / 2f, 220f, Pal.SHELL, align = 0)
        if (m.page == PauseMenu.Page.TESTS) text("Runs de prueba: no guardan ni dan Perlas", iw / 2f, 220f, Pal.SHELL, align = 0)
        labels.forEachIndexed { i, label ->
            val y = PauseMenu.itemYFor(m.page, i, n) + if (n > 6) 4f else 5f
            text(label, iw / 2f, y, if (i == m.sel) Pal.OUTLINE else Pal.CREAM, align = 0)
        }
        if (m.page == PauseMenu.Page.SEED) {
            for (i in 0 until m.seedChars.size) {
                text(m.seedChars[i].toString(), PauseMenu.seedSlotX(i) + PauseMenu.SEED_W / 2f, PauseMenu.SEED_Y + 4f, if (i == m.seedSlot) Pal.OUTLINE else Pal.CREAM, align = 0)
            }
            text("Arriba/abajo cambian la letra · izquierda/derecha, la casilla · toca arriba o abajo de una letra", iw / 2f, 140f, Pal.SHELL, align = 0, wrap = iw * 0.9f)
        }
        if (m.page == PauseMenu.Page.LOG) {
            if (RunLog.entries.isEmpty()) text("Todavía no hay runs registradas.", iw / 2f, 150f, Pal.SHELL, align = 0)
            RunLog.entries.take(9).forEachIndexed { i, e ->
                val mm = (e.time / 60f).toInt()
                val ss = (e.time % 60f).toInt()
                val bl = if (e.bossLuz.isEmpty()) "-" else e.bossLuz.joinToString("/")
                val line = "${e.result}  piso ${e.floor}  ${Mareas.label(e.marea, e.baja)}  ${mm}:${ss.toString().padStart(2, '0')}  " +
                    "${e.kills} bajas  Haz ${e.hazUses}  Luz en jefes $bl  ${e.seed}"
                text(line, iw / 2f, 206f - i * 18f, if (e.result == "VICTORIA") Pal.LAMP_HI else Pal.CREAM, align = 0)
                text(e.cause, iw / 2f, 198f - i * 18f, Pal.SHELL, align = 0)
            }
        }
        val ctl = pad.controller
        if (n <= 6) text(if (ctl == null) "Sin mando conectado" else "Mando: ${ctl.name.take(40)}", iw / 2f, 64f, Pal.SHELL, align = 0)
        if (m.note.isNotEmpty() && m.noteT > 0f) text(m.note, iw / 2f, 46f, if (m.noteError) Pal.CORAL else Pal.FOAM, align = 0)
        val hint = when {
            c.padActive || ctl != null -> "Stick o cruceta para elegir  ·  A aceptar  ·  B volver"
            c.touch -> "Toca una opción"
            else -> "Flechas para elegir  ·  Enter aceptar  ·  Esc volver"
        }
        text(hint, iw / 2f, 18f, Pal.SHELL, align = 0)
        batch.end()
        font.color = Color.WHITE
    }

    /** Una pieza del mando dibujado: círculo o rectángulo con el botón que representa. */
    private class PadEl(val round: Boolean, val x: Float, val y: Float, val w: Float, val h: Float, val label: String, val reserved: Boolean, val code: (com.badlogic.gdx.controllers.ControllerMapping) -> Int)

    private val padEls: List<PadEl> by lazy {
        val cx = 120f
        val cy = 132f
        listOf(
            PadEl(false, cx - 80f, cy + 44f, 32f, 9f, "LT", false) { it.buttonL2 },
            PadEl(false, cx - 80f, cy + 33f, 32f, 8f, "LB", false) { it.buttonL1 },
            PadEl(false, cx + 48f, cy + 44f, 32f, 9f, "RT", false) { it.buttonR2 },
            PadEl(false, cx + 48f, cy + 33f, 32f, 8f, "RB", false) { it.buttonR1 },
            PadEl(true, cx - 48f, cy + 8f, 10f, 10f, "L3", false) { it.buttonLeftStick },
            PadEl(true, cx + 22f, cy - 16f, 10f, 10f, "R3", false) { it.buttonRightStick },
            PadEl(true, cx + 50f, cy + 19f, 6f, 6f, "Y", false) { it.buttonY },
            PadEl(true, cx + 50f, cy - 3f, 6f, 6f, "A", false) { it.buttonA },
            PadEl(true, cx + 39f, cy + 8f, 6f, 6f, "X", false) { it.buttonX },
            PadEl(true, cx + 61f, cy + 8f, 6f, 6f, "B", false) { it.buttonB },
            PadEl(false, cx - 16f, cy + 14f, 10f, 5f, "", true) { it.buttonBack },
            PadEl(false, cx + 6f, cy + 14f, 10f, 5f, "", true) { it.buttonStart },
            PadEl(false, cx - 26f, cy - 13f, 8f, 6f, "", true) { it.buttonDpadUp },
            PadEl(false, cx - 26f, cy - 25f, 8f, 6f, "", true) { it.buttonDpadDown },
            PadEl(false, cx - 33f, cy - 19f, 7f, 6f, "", true) { it.buttonDpadLeft },
            PadEl(false, cx - 18f, cy - 19f, 7f, 6f, "", true) { it.buttonDpadRight },
        )
    }

    /**
     * Configurar mando: a la izquierda el mando dibujado (cada botón con el color de su acción y
     * encendido mientras se pulsa); a la derecha la tabla de acciones con dos huecos cada una.
     */
    private fun drawConfig(m: PauseMenu, pad: Gamepad, c: Controls) {
        val bw = Gdx.graphics.backBufferWidth
        val bh = Gdx.graphics.backBufferHeight
        val l = m.layout ?: return
        val ctrl = pad.controller
        val blink = (System.currentTimeMillis() / 250L) % 2L == 0L
        Gdx.gl.glViewport(ox, oy, iw * scale, ih * scale)
        Gdx.gl.glEnable(GL20.GL_BLEND)
        shapes.projectionMatrix = internalProj
        shapes.begin(ShapeType.Filled)
        col(Pal.OUTLINE, 0.92f)
        shapes.rect(0f, 0f, iw.toFloat(), ih.toFloat())

        // Cuerpo del mando
        val cx = 120f
        val cy = 132f
        col(Pal.NIGHT)
        shapes.rect(cx - 62f, cy - 22f, 124f, 52f)
        shapes.circle(cx - 56f, cy - 20f, 24f, 24)
        shapes.circle(cx + 56f, cy - 20f, 24f, 24)
        col(Pal.WALL)
        shapes.rect(cx - 60f, cy + 24f, 120f, 4f)
        // Sticks (siempre mueven / apuntan)
        val lsx = cx - 48f + pad.lx * 4f
        val lsy = cy + 8f + pad.ly * 4f
        val rsx = cx + 22f + pad.rx * 4f
        val rsy = cy - 16f + pad.ry * 4f
        col(Pal.OUTLINE)
        shapes.circle(cx - 48f, cy + 8f, 13f, 20)
        shapes.circle(cx + 22f, cy - 16f, 13f, 20)

        for (e in padEls) {
            val idx = if (ctrl != null) e.code(ctrl.mapping) else -1
            val codeStr = "b$idx"
            val action = if (idx >= 0) l.actionOf(codeStr) else null
            val pressed = ctrl != null && idx >= 0 && ctrl.getButton(idx)
            val selectedTarget = m.capturing && blink && action == null
            val fill = when {
                pressed -> Color.WHITE
                e.reserved -> Pal.WALL_TOP
                action != null -> actionColor(action)
                selectedTarget -> Pal.SEAM
                else -> Pal.WALL
            }
            val px = if (e.label == "L3") lsx else if (e.label == "R3") rsx else e.x
            val py = if (e.label == "L3") lsy else if (e.label == "R3") rsy else e.y
            if (e.round) {
                col(Pal.OUTLINE)
                shapes.circle(px, py, e.w + 1f, 16)
                col(fill)
                shapes.circle(px, py, e.w, 16)
            } else {
                outlined(px, py, e.w, e.h, fill)
            }
        }

        // Tabla
        Action.entries.forEachIndexed { r, a ->
            val y = PauseMenu.rowY(r)
            col(actionColor(a))
            shapes.rect(PauseMenu.LABEL_X, y - PauseMenu.CELL_H / 2f, 4f, PauseMenu.CELL_H)
            val active = ctrl != null && l.codes(r).any { Bindings.isActive(ctrl, it) }
            for (s in 0 until Bindings.MAX_PER_ACTION) {
                val x = PauseMenu.SLOT_X[s]
                val sel = r == m.row && s == m.col
                if (sel) {
                    col(if (m.capturing && blink) Pal.LAMP_HI else Pal.LAMP)
                    shapes.rect(x - 2f, y - PauseMenu.CELL_H / 2f - 2f, PauseMenu.SLOT_W + 4f, PauseMenu.CELL_H + 4f)
                }
                val code = l.slots[r][s]
                col(
                    when {
                        code != null && ctrl != null && Bindings.isActive(ctrl, code) -> actionColor(a)
                        code != null -> Pal.WALL
                        else -> Pal.NIGHT
                    }
                )
                shapes.rect(x, y - PauseMenu.CELL_H / 2f, PauseMenu.SLOT_W, PauseMenu.CELL_H)
                if (code != null) {
                    col(actionColor(a))
                    shapes.rect(x, y - PauseMenu.CELL_H / 2f, 3f, PauseMenu.CELL_H)
                }
            }
            if (active) {
                col(actionColor(a), 0.35f)
                shapes.rect(PauseMenu.LABEL_X + 4f, y - PauseMenu.CELL_H / 2f, PauseMenu.LABEL_W - 4f, PauseMenu.CELL_H)
            }
        }
        if (m.capturing) {
            val k = (m.captureT / PauseMenu.CAPTURE_TIME).coerceIn(0f, 1f)
            val x = PauseMenu.SLOT_X[m.col]
            col(Pal.LAMP_HI)
            shapes.rect(x, PauseMenu.rowY(m.row) - PauseMenu.CELL_H / 2f - 5f, PauseMenu.SLOT_W * k, 2f)
        }
        // Botones táctiles / de ratón
        for (i in PauseMenu.BTN_X.indices) {
            col(if (i == 1) Pal.LAMP else Pal.NIGHT, 0.9f)
            shapes.rect(PauseMenu.BTN_X[i], PauseMenu.BTN_Y - PauseMenu.BTN_H / 2f, PauseMenu.BTN_W, PauseMenu.BTN_H)
        }
        shapes.end()

        Gdx.gl.glViewport(0, 0, bw, bh)
        batch.projectionMatrix = screenProj
        batch.begin()
        text("CONFIGURAR MANDO", iw / 2f, 248f, Pal.LAMP, align = 0)
        text(if (ctrl == null) "Mando desconectado" else ctrl.name.take(44), iw / 2f, 232f, Pal.SHELL, align = 0)
        text("Acción", PauseMenu.LABEL_X + 8f, PauseMenu.ROW_Y0 + 22f, Pal.SHELL)
        text("Botón 1", PauseMenu.SLOT_X[0] + PauseMenu.SLOT_W / 2f, PauseMenu.ROW_Y0 + 22f, Pal.SHELL, align = 0)
        text("Botón 2", PauseMenu.SLOT_X[1] + PauseMenu.SLOT_W / 2f, PauseMenu.ROW_Y0 + 22f, Pal.SHELL, align = 0)
        Action.entries.forEachIndexed { r, a ->
            val y = PauseMenu.rowY(r) + 5f
            text(a.label, PauseMenu.LABEL_X + 8f, y, Pal.CREAM)
            for (s in 0 until Bindings.MAX_PER_ACTION) {
                val code = l.slots[r][s]
                val sel = r == m.row && s == m.col
                val label = when {
                    sel && m.capturing -> "Pulsa..."
                    code != null -> Bindings.label(ctrl, code)
                    else -> "-"
                }
                text(label, PauseMenu.SLOT_X[s] + PauseMenu.SLOT_W / 2f, y, if (sel) Pal.LAMP_HI else Pal.CREAM, align = 0)
            }
        }
        // Etiquetas del mando dibujado
        for (e in padEls) if (e.label.isNotEmpty()) {
            val lx = if (e.round) e.x else e.x + e.w / 2f
            val ly = if (e.round) e.y + 4f else e.y + e.h + 9f
            val labelY = if (e.label == "LT" || e.label == "RT") e.y + e.h + 10f else if (e.label == "LB" || e.label == "RB") e.y - 2f else ly
            if (e.label == "LB" || e.label == "RB") continue  // LB/RB se leen junto a LT/RT
            val idx = if (ctrl != null) e.code(ctrl.mapping) else -1
            val onColor = e.round && idx >= 0 && l.actionOf("b$idx") != null
            text(e.label, lx, labelY, if (onColor) Pal.OUTLINE else Pal.CREAM, align = 0)
        }
        text("LB", 120f - 64f, 132f + 30f, Pal.CREAM, align = 0)
        text("RB", 120f + 64f, 132f + 30f, Pal.CREAM, align = 0)
        text("Cruceta: mover · Start: pausa", 120f, 84f, Pal.SHELL, align = 0)

        text("Por defecto", PauseMenu.BTN_X[0] + PauseMenu.BTN_W / 2f, PauseMenu.BTN_Y + 5f, Pal.CREAM, align = 0)
        text("Guardar", PauseMenu.BTN_X[1] + PauseMenu.BTN_W / 2f, PauseMenu.BTN_Y + 5f, Pal.OUTLINE, align = 0)

        if (m.note.isNotEmpty() && m.noteT > 0f) {
            text(m.note, iw / 2f, 40f, if (m.noteError) Pal.CORAL else Pal.FOAM, align = 0, wrap = iw * 0.9f)
        }
        val hint = if (ctrl != null || c.padActive) "A asignar  ·  X quitar  ·  Y por defecto  ·  B guardar y salir"
        else "Enter asignar  ·  Supr quitar  ·  R por defecto  ·  Esc guardar y salir"
        text(hint, iw / 2f, 16f, Pal.SHELL, align = 0)
        batch.end()
        font.color = Color.WHITE
    }

    /** Colores de la sala según el bioma (§10). */
    private class RoomPal(
        val void: Color, val wall: Color, val wallTop: Color, val floor: Color, val floor2: Color,
        val seam: Color, val door: Color, val water: Color, val tide: Color,
    )
    private val palMuelle = RoomPal(Pal.VOID, Pal.WALL, Pal.WALL_TOP, Pal.PLANK, Pal.PLANK2, Pal.SEAM, Pal.DOOR, Pal.WATER, Pal.TIDE)
    private val palCatedral = RoomPal(Pal.CAT_VOID, Pal.CAT_WALL, Pal.CAT_WALL_TOP, Pal.CAT_FLOOR, Pal.CAT_FLOOR2, Pal.CAT_SEAM, Pal.CAT_DOOR, Pal.WATER, Pal.TIDE)
    private val palArchivo = RoomPal(Pal.ARC_VOID, Pal.ARC_WALL, Pal.ARC_WALL_TOP, Pal.ARC_FLOOR, Pal.ARC_FLOOR2, Pal.ARC_SEAM, Pal.ARC_DOOR, Pal.INK, Pal.INK_HI)
    private val palRaiz = RoomPal(Pal.RAZ_VOID, Pal.RAZ_WALL, Pal.RAZ_WALL_TOP, Pal.RAZ_FLOOR, Pal.RAZ_FLOOR2, Pal.RAZ_SEAM, Pal.RAZ_DOOR, Pal.RAZ_VOID, Pal.MOLTEN)
    private var rp = palMuelle
    private var catFloor = false
    private var biomeNow = Biome.MUELLE
    /** Rieles que avisan este fotograma (índice de tile). */
    private val warnRails = HashSet<Int>()

    private fun drawScene(w: World, a: Float) {
        biomeNow = if (w.inSanctuary) Biome.MUELLE else w.biome
        catFloor = biomeNow == Biome.CATEDRAL
        rp = when (biomeNow) {
            Biome.CATEDRAL -> palCatedral
            Biome.ARCHIVO -> palArchivo
            Biome.RAIZ -> palRaiz
            Biome.MUELLE -> palMuelle
        }
        warnRails.clear()
        for (g in w.room.shelves) if (g.warnT > 0f) {
            val s = if (g.offset == 0) 1 else -1
            for (t in g.tiles) for (k in 0..2) warnRails += (t[1] + g.dy * s * k) * w.room.cols + (t[0] + g.dx * s * k)
        }
        scene.begin()
        Gdx.gl.glClearColor(rp.void.r, rp.void.g, rp.void.b, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)

        shapes.projectionMatrix = cam.combined
        shapes.begin(ShapeType.Filled)
        drawRoom(w)
        drawCatedral(w)
        drawArchivoRaiz(w)
        if (w.brea) {
            col(Pal.BREA, 0.55f)
            shapes.rect(0f, 0f, w.room.width.toFloat(), w.room.height.toFloat())
        }
        for (p in w.puddles) {
            col(Pal.SALADO, 0.35f * min(1f, p.t))
            shapes.circle(p.x, p.y, p.r, 14)
        }
        for (f in w.fires) {
            val flick = (w.time * 14f + f.x).toInt() % 3
            col(if (flick == 0) Pal.LAMP else Pal.LAMP_DIM, min(1f, f.t))
            shapes.rect(f.x - 2f, f.y - 1f + flick, 4f, 4f)
        }
        for (p in w.cell.pedestals) if (!p.taken || p.kind == PedKind.REFUGIO || p.kind == PedKind.VIGILIA || p.kind == PedKind.META || p.kind == PedKind.JAULA) drawPedestal(w, p)
        for (s in w.pending) {
            val pulse = ((w.time * 10f).toInt() % 2 == 0)
            col(if (pulse) (s.affix?.color ?: Pal.CORAL) else Pal.WATER, 0.8f)
            shapes.rect(s.x - 3f, s.y - 3f, 6f, 6f)
        }
        for (o in w.orbs) {
            col(Pal.LAMP_HI)
            shapes.rect(o.x.roundToInt() - 1f, o.y.roundToInt() - 1f, 3f, 3f)
        }
        w.statue?.let { s ->
            outlined(s.x - 5f, s.y - 3f, 10f, 15f, Pal.SALADO)
            col(Pal.SHELL)
            shapes.rect(s.x - 4f, s.y + 8f, 8f, 3f)
        }

        // Orden por profundidad: lo más bajo en pantalla se dibuja encima.
        val bodies = ArrayList<Body>(w.enemies.size + 1)
        bodies.addAll(w.enemies)
        bodies.add(w.player)
        bodies.sortByDescending { it.y }
        for (b in bodies) {
            if (b is Player) drawPlayer(w, b, a) else if (b is Enemy) drawEnemy(w, b, a)
        }

        drawAttacks(w.player)
        if (w.stats.twinLamp && !w.gameOver) {
            outlined(w.twinX.roundToInt() - 2f, w.twinY.roundToInt() - 2f, 4f, 4f, Pal.LAMP_HI)
            if (w.twinShotT > 0f) {
                col(Pal.LAMP_HI, 0.8f)
                shapes.rectLine(w.twinX, w.twinY, w.twinShotX, w.twinShotY, 1.5f)
            }
        }

        for (p in w.projectiles) {
            val x = p.x.roundToInt().toFloat()
            val y = p.y.roundToInt().toFloat()
            col(Pal.OUTLINE)
            shapes.rect(x - 3f, y - 3f, 6f, 6f)
            col(p.color)
            shapes.rect(x - 2f, y - 2f, 4f, 4f)
            shapes.rect(x + 1f, y + 2f, 1f, 3f)   // plica de la nota
        }

        for (p in w.fx.parts) {
            col(p.color, (p.life / p.maxLife).coerceIn(0f, 1f) * if (p.w > 4f) 0.45f else 1f)
            shapes.rect(p.x.roundToInt().toFloat(), p.y.roundToInt().toFloat(), p.w, p.h)
        }
        shapes.end()

        shapes.begin(ShapeType.Line)
        for (s in w.pending) {
            col(s.affix?.color ?: Pal.CORAL, 0.7f)
            shapes.circle(s.x, s.y, 4f + (World.SPAWN_TELEGRAPH - s.t) * 12f, 16)
        }
        for (e in w.enemies) {
            if (e is Medusa) {
                if (e.state == EState.TELEGRAPH) {
                    val k = 1f - e.stateT / e.def.telegraph
                    col(Pal.MEDUSA, 0.9f)
                    shapes.circle(e.x, e.y, Medusa.PULSE_R * k, 20)
                    col(Pal.MEDUSA, 0.35f)
                    shapes.circle(e.x, e.y, Medusa.PULSE_R, 20)
                } else if (e.killed) {
                    col(Pal.CORAL, 0.8f)
                    shapes.circle(e.x, e.y, Medusa.BLAST_R, 24)
                }
            } else if (e is Sirena && e.act == Sirena.Act.BUBBLES) {
                val k = 1f - e.actT / e.actMax
                col(Pal.CORAL, 0.9f)
                shapes.circle(e.bubbleX, e.bubbleY, Sirena.EMERGE_R, 24)
                col(Pal.FOAM, 0.8f)
                shapes.circle(e.bubbleX, e.bubbleY, Sirena.EMERGE_R * k, 24)
            }
        }
        for (wv in w.waves) {
            val k = 1f - wv.t / wv.maxT
            col(Pal.LILAC, 1f - k)
            shapes.circle(wv.x, wv.y, wv.r * k, 28)
            col(Pal.VIOLET, 0.6f * (1f - k))
            shapes.circle(wv.x, wv.y, wv.r * k * 0.8f, 24)
        }
        for (r in w.fx.rings) {
            col(r.color, r.t / r.maxT)
            shapes.circle(r.x, r.y, r.r * (1.2f - 0.2f * r.t / r.maxT), 24)
        }
        if (debug) drawDebug(w)
        shapes.end()
        scene.end()
    }

    private fun drawRoom(w: World) {
        val r = w.room
        val t = Balance.TILE.toFloat()
        for (ty in 0 until r.rows) for (tx in 0 until r.cols) {
            val x = tx * t
            val y = ty * t
            when (r.tileAt(tx, ty)) {
                Room.WALL, Room.CRACK -> {
                    col(rp.wall)
                    shapes.rect(x, y, t, t)
                    if (!r.isSolidTile(tx, ty + 1) || ty == r.rows - 1) {
                        col(rp.wallTop)
                        shapes.rect(x, y + t - 3f, t, 3f)
                    }
                    if (ty > 0 && !r.isSolidTile(tx, ty - 1)) {
                        col(Pal.OUTLINE)
                        shapes.rect(x, y, t, 2f)
                    }
                    if (r.isCrack(tx, ty)) {
                        // Grieta: se nota si te fijas. El Haz la abre.
                        col(Pal.OUTLINE)
                        shapes.rect(x + 4f, y + 3f, 1f, 4f)
                        shapes.rect(x + 5f, y + 7f, 1f, 3f)
                        shapes.rect(x + 6f, y + 9f, 2f, 1f)
                        shapes.rect(x + 9f, y + 5f, 1f, 5f)
                        col(rp.wallTop, 0.6f)
                        shapes.rect(x + 10f, y + 4f, 1f, 2f)
                    }
                }
                Room.GLASS -> {
                    // Vitral: plomos oscuros y vidrio de colores; agrietado parpadea en blanco.
                    val crack = r.crackOf(tx, ty)
                    col(Pal.CAT_WALL)
                    shapes.rect(x, y, t, t)
                    val hue = (tx * 7 + ty * 3) % 4
                    val a = if (hue == 0) Pal.GLASS_TEAL else if (hue == 1) Pal.VIOLET else if (hue == 2) Pal.GLASS_TEAL else Pal.CORAL_RED
                    val b = if (hue == 2) Pal.GLASS_LIGHT else Pal.LILAC
                    col(a); shapes.rect(x + 1f, y + 1f, 6f, 6f); shapes.rect(x + 9f, y + 9f, 6f, 6f)
                    col(b); shapes.rect(x + 9f, y + 1f, 6f, 6f); shapes.rect(x + 1f, y + 9f, 6f, 6f)
                    if (crack >= 0f) {
                        val fast = crack < 0.5f
                        val on = ((w.time * if (fast) 14f else 6f).toInt() % 2 == 0)
                        col(Pal.CREAM, if (on) 0.85f else 0.45f)
                        shapes.rect(x + 3f, y + 7f, 10f, 1f)
                        shapes.rect(x + 7f, y + 2f, 1f, 12f)
                        shapes.rect(x + 4f, y + 11f, 1f, 3f)
                        shapes.rect(x + 11f, y + 3f, 1f, 3f)
                    }
                }
                Room.HOLE -> {
                    col(Pal.CAT_VOID)
                    shapes.rect(x, y, t, t)
                    if (r.tileAt(tx, ty + 1) != Room.HOLE) {
                        col(Pal.CAT_WALL_TOP)
                        shapes.rect(x, y + t - 2f, t, 2f)
                    }
                    col(Pal.GLASS_TEAL, 0.5f)
                    shapes.rect(x + 3f + (tx % 3), y + 4f, 2f, 1f)
                }
                Room.CANDLE -> {
                    drawPlank(tx, ty, x, y, t)
                }
                Room.SHELF -> {
                    // Estantería con lomos de libros; tiembla mientras avisa.
                    if (r.isRail(tx, ty)) drawPlank(tx, ty, x, y, t)
                    val jig = if (warnRails.contains(ty * r.cols + tx) && (w.time * 20f).toInt() % 2 == 0) 1f else 0f
                    outlined(x + 1f + jig, y + 1f, t - 2f, t - 2f, Pal.ARC_WALL_TOP)
                    for (i in 0 until 4) {
                        col(when ((tx * 3 + ty + i) % 3) { 0 -> Pal.BOOK_RED; 1 -> Pal.BOOK_BLUE; else -> Pal.BOOK_OCHRE })
                        shapes.rect(x + 3f + i * 3f + jig, y + 3f, 2f, 10f)
                    }
                }
                Room.ATRIL -> {
                    drawPlank(tx, ty, x, y, t)
                    outlined(x + 6f, y + 1f, 4f, 9f, Pal.ARC_DOOR)
                    outlined(x + 2f, y + 9f, 12f, 3f, Pal.PAPER_DIM)
                    val on = w.atrilesOn
                    col(if (on) Pal.GREEN_LAMP else Pal.SEAM)
                    shapes.rect(x + 7f, y + 12f, 2f, 3f)
                }
                Room.MOLTEN -> {
                    val ph = ((w.time * 3f + tx * 0.9f + ty * 1.7f).toInt() % 3)
                    col(Pal.MOLTEN)
                    shapes.rect(x, y, t, t)
                    col(Pal.MOLTEN_HI)
                    shapes.rect(x + 2f + ph * 3f, y + 3f + ph, 4f, 3f)
                    shapes.rect(x + 9f - ph, y + 10f, 3f, 2f)
                    col(Pal.LAMP_DIM, 0.6f)
                    shapes.rect(x, y, t, 1f)
                }
                Room.CORAL -> {
                    drawPlank(tx, ty, x, y, t)
                    outlined(x + 1f, y + 1f, t - 2f, t - 3f, Pal.CORAL_RED)
                    col(Pal.CORAL_PINK)
                    shapes.rect(x + 3f, y + 6f, 3f, 6f)
                    shapes.rect(x + 9f, y + 4f, 3f, 8f)
                    shapes.rect(x + 6f, y + 10f, 3f, 3f)
                }
                Room.WATER -> {
                    col(rp.water)
                    shapes.rect(x, y, t, t)
                    val ph = ((w.time * 2f + tx * 0.7f + ty * 1.3f).toInt() % 4)
                    col(rp.tide)
                    shapes.rect(x + 2f + ph, y + 5f, 5f, 1f)
                    shapes.rect(x + 9f - ph, y + 11f, 4f, 1f)
                    // Borde de tablón sobre el agua (se lee el límite del pozo).
                    if (!r.isWater(tx, ty + 1) && r.tileAt(tx, ty + 1) == Room.FLOOR) {
                        col(rp.seam)
                        shapes.rect(x, y + t - 2f, t, 2f)
                    }
                }
                Room.DOOR -> {
                    drawPlank(tx, ty, x, y, t)
                    if (!r.doorsOpen) {
                        col(rp.seam)
                        shapes.rect(x + 1f, y + 1f, t - 2f, t - 2f)
                        col(rp.door)
                        val vertical = tx == 0 || tx == r.cols - 1
                        for (i in 0 until 3) {
                            if (vertical) shapes.rect(x + 3f + i * 4f, y, 2f, t) else shapes.rect(x, y + 3f + i * 4f, t, 2f)
                        }
                    }
                }
                else -> {
                    drawPlank(tx, ty, x, y, t)
                    if (r.isRail(tx, ty)) {
                        val warn = warnRails.contains(ty * r.cols + tx)
                        val on = warn && (w.time * 10f).toInt() % 2 == 0
                        col(if (warn) Pal.INK_HI else Pal.INK, if (on) 1f else 0.8f)
                        shapes.rect(x, y + 5f, t, 1f)
                        shapes.rect(x, y + 10f, t, 1f)
                    }
                }
            }
            // Archivo: las paredes que dan al suelo son estanterías fijas.
            if (biomeNow == Biome.ARCHIVO && r.tileAt(tx, ty) == Room.WALL && ty > 0 && !r.isSolidTile(tx, ty - 1) && ty < r.rows - 1) {
                for (i in 0 until 4) {
                    col(when ((tx * 5 + ty + i) % 3) { 0 -> Pal.BOOK_RED; 1 -> Pal.BOOK_BLUE; else -> Pal.BOOK_OCHRE }, 0.75f)
                    shapes.rect(x + 2f + i * 3.5f, y + 3f, 2f, 9f)
                }
            }
        }
    }

    /** Trampas, líneas del Copista, rastro de tinta, haces y ecos de la Lámpara, avisos de la Anguila y del Ojo. */
    private fun drawArchivoRaiz(w: World) {
        val blink = (w.time * 10f).toInt() % 2 == 0
        for (z in w.saltZones) {
            col(Pal.SALADO, 0.35f * (z[2] / 2f).coerceIn(0f, 1f))
            shapes.circle(z[0], z[1], 12f, 12)
        }
        for (dot in w.ink) {
            col(Pal.INK, (dot[2] / 3f).coerceIn(0f, 1f) * 0.9f)
            shapes.rect(dot[0] - 2f, dot[1] - 1f, 4f, 3f)
        }
        for (t in w.traps) {
            val k = 1f - t.t / t.maxT
            if (t.kind == Trap.Kind.PAGE) {
                col(Pal.CORAL, 0.15f + 0.25f * k)
                shapes.circle(t.x, t.y, t.r, 16)
                outlined(t.x - 3f, t.y - 4f, 6f, 8f, if (blink && k > 0.6f) Pal.CORAL else Pal.PAPER)
                col(Pal.INK)
                shapes.rect(t.x - 2f, t.y + 1f, 4f, 1f); shapes.rect(t.x - 2f, t.y - 1f, 3f, 1f)
            } else {
                col(Pal.ROOT_HI, if (blink) 0.6f else 0.35f)
                shapes.circle(t.x, t.y, t.r * (0.5f + 0.5f * k), 16)
                col(Pal.CORAL, 0.5f)
                shapes.circle(t.x, t.y, 2f, 8)
            }
        }
        for (l in w.lines) {
            // Letras grises que se van escribiendo a lo largo de la línea.
            val k = 1f - l.t / l.maxT
            val n = 12
            for (i in 0 until n) {
                val f = i / (n - 1f)
                if (f > k + 0.1f) break
                val x = l.x0 + (l.x1 - l.x0) * f
                val y = l.y0 + (l.y1 - l.y0) * f
                col(if (k > 0.8f && blink) Pal.CORAL else Pal.PAPER_DIM, 0.85f)
                shapes.rect(x - 1.5f, y - 1f, 3f, if (i % 3 == 0) 3f else 2f)
            }
        }
        for (e in w.enemies) {
            if (e is Anguila && e.phase == Anguila.Phase.WARN) {
                col(Pal.ROOT_HI, if (blink) 0.65f else 0.35f)
                shapes.circle(e.markX, e.markY, e.def.range, 18)
            }
            if (e is OjoFosa) {
                if (e.state == EState.TELEGRAPH) {
                    col(Pal.MOLTEN_HI, if (blink) 0.7f else 0.4f)
                    shapes.rectLine(e.x, e.y, e.x + cos(e.aimA) * 400f, e.y + sin(e.aimA) * 400f, 1f)
                }
                if (e.rayT > 0f) {
                    col(Pal.MOLTEN_HI, 0.95f)
                    shapes.rectLine(e.x, e.y, e.x + cos(e.aimA) * e.rayLen, e.y + sin(e.aimA) * e.rayLen, 5f)
                    col(Color.WHITE, 0.9f)
                    shapes.rectLine(e.x, e.y, e.x + cos(e.aimA) * e.rayLen, e.y + sin(e.aimA) * e.rayLen, 2f)
                }
            }
        }
        // Lámpara apagada: una cáscara de vidrio negro.
        w.husk?.let { hk ->
            val hx = hk[0]; val hy = hk[1]
            col(Pal.SHADOW); shapes.ellipse(hx - 20f, hy - 22f, 40f, 8f)
            outlined(hx - 14f, hy - 22f, 28f, 6f, Pal.STONE)
            col(Pal.OUTLINE); shapes.rect(hx - 15f, hy - 16f, 30f, 34f)
            col(Pal.RAZ_VOID); shapes.rect(hx - 14f, hy - 15f, 28f, 32f)
            for (i in 0 until 4) { col(Pal.SEAM); shapes.rect(hx - 14f + i * 9f, hy - 15f, 2f, 32f) }
            outlined(hx - 16f, hy + 17f, 32f, 4f, Pal.STONE)
        }
        // Farero: cono del farol y aviso de carga.
        (w.boss as? FareroAntiguo)?.let { f ->
            if (f.killed) return@let
            if (f.lanternOn && f.act != FareroAntiguo.Act.INTRO) {
                val spotted = f.act == FareroAntiguo.Act.SPOTTED
                col(if (spotted && blink) Pal.CORAL else Pal.LAMP_HI, if (spotted) 0.32f else 0.16f)
                val steps = 6
                for (i in 0 until steps) {
                    val a0 = f.lanternA - FareroAntiguo.CONE_HALF + 2f * FareroAntiguo.CONE_HALF * i / steps
                    val a1 = f.lanternA - FareroAntiguo.CONE_HALF + 2f * FareroAntiguo.CONE_HALF * (i + 1) / steps
                    shapes.triangle(f.x, f.y, f.x + cos(a0) * FareroAntiguo.CONE_LEN, f.y + sin(a0) * FareroAntiguo.CONE_LEN,
                        f.x + cos(a1) * FareroAntiguo.CONE_LEN, f.y + sin(a1) * FareroAntiguo.CONE_LEN)
                }
            }
            if (f.act == FareroAntiguo.Act.CHARGE_WARN) {
                col(Pal.CORAL, if (blink) 0.7f else 0.35f)
                shapes.rectLine(f.x, f.y, w.player.cx, w.player.cy, 1f)
            }
            if (f.phase == 3 && f.breathT < 1f) {
                col(Pal.RAZ_VOID, 0.35f * (1f - f.breathT))
                shapes.rect(0f, 0f, w.room.width.toFloat(), w.room.height.toFloat())
            }
        }
        val lamp = w.boss as? LamparaMadre ?: return
        if (lamp.killed || lamp.dormant) return
        val h = w.room.height.toFloat()
        // Eco: la sala se tiñe del color del jefe copiado 1 s antes.
        lamp.echo?.let { e ->
            if (lamp.echoWarnT > 0f) { col(e.color, 0.28f); shapes.rect(0f, 0f, w.room.width.toFloat(), h) }
            if (e == LamparaMadre.Echo.OBISPO && lamp.echoT > 0f && lamp.echoWarnT <= 0f) for (cx in lamp.colX) {
                col(Pal.LAMP_HI, 0.14f); shapes.rect(cx - 16f, 0f, 32f, h)
            }
        }
        for (a in lamp.beamAngles()) {
            val ax = cos(a)
            val ay = sin(a)
            val len = LamparaMadre.BEAM_START + w.room.rayLength(lamp.x + ax * LamparaMadre.BEAM_START, lamp.y + ay * LamparaMadre.BEAM_START, ax, ay, 600f)
            if (lamp.beamWarnT > 0f) {
                col(Pal.LAMP_HI, if (blink) 0.45f else 0.2f)
                shapes.rectLine(lamp.x, lamp.y, lamp.x + ax * len, lamp.y + ay * len, 1f)
            } else {
                col(Pal.LAMP_HI, 0.35f)
                shapes.rectLine(lamp.x, lamp.y, lamp.x + ax * len, lamp.y + ay * len, 12f)
                col(Pal.CREAM, 0.85f)
                shapes.rectLine(lamp.x, lamp.y, lamp.x + ax * len, lamp.y + ay * len, 4f)
            }
        }
    }

    /** Candelabros, columnas de luz del Obispo y avisos del coral. */
    private fun drawCatedral(w: World) {
        for (c in w.candles) {
            val x = c.x
            val y = c.y - 7f
            col(Pal.SHADOW)
            shapes.ellipse(x - 6f, y - 3f, 12f, 4f)
            outlined(x - 4f, y - 2f, 8f, 3f, Pal.STONE)
            outlined(x - 1f, y + 1f, 2f, 11f, Pal.STONE)
            outlined(x - 6f, y + 12f, 12f, 2f, Pal.BONE)
            for (k in arrayOf(-4f, 0f, 4f)) {
                col(Pal.BONE)
                shapes.rect(x + k - 1f, y + 14f, 2f, 3f)
                if (c.lit) {
                    val fl = ((w.time * 12f + k + c.x).toInt() % 2).toFloat()
                    col(Pal.LAMP)
                    shapes.rect(x + k - 1f, y + 17f, 2f, 2f + fl)
                    col(Pal.LAMP_HI)
                    shapes.rect(x + k - 0.5f, y + 17f, 1f, 1f)
                } else {
                    col(Pal.SEAM)
                    shapes.rect(x + k - 0.5f, y + 17f, 1f, 1f)
                }
            }
        }
        val o = w.boss as? Obispo ?: return
        if (o.killed) return
        val h = w.room.height.toFloat()
        if (o.columnsOn) {
            for (cx in o.colX) {
                col(Pal.LAMP_HI, 0.14f)
                shapes.rect(cx - Obispo.COL_W / 2f, 0f, Obispo.COL_W, h)
                col(Pal.LAMP_HI, 0.35f)
                shapes.rect(cx - Obispo.COL_W / 2f, 0f, 1f, h)
                shapes.rect(cx + Obispo.COL_W / 2f - 1f, 0f, 1f, h)
            }
            if (o.colWarnT > 0f) {
                val on = (w.time * 10f).toInt() % 2 == 0
                for (tx in o.colTo) {
                    col(Pal.LAMP_HI, if (on) 0.6f else 0.3f)
                    shapes.rect(tx - 0.5f, 0f, 1f, h)
                }
            }
        }
        val depth = w.coralWarnDepth
        if (depth > 0) {
            val t = Balance.TILE.toFloat()
            val r = w.room
            val on = (w.time * 10f).toInt() % 2 == 0
            col(Pal.CORAL_PINK, if (on) 0.55f else 0.3f)
            val x0 = depth * t
            val y0 = depth * t
            val x1 = (r.cols - depth) * t
            val y1 = (r.rows - depth) * t
            shapes.rect(x0, y0, x1 - x0, t)
            shapes.rect(x0, y1 - t, x1 - x0, t)
            shapes.rect(x0, y0, t, y1 - y0)
            shapes.rect(x1 - t, y0, t, y1 - y0)
        }
        if (o.act == Obispo.Act.SWEEP_TELE) {
            val k = 1f - o.actT / o.actMax
            col(Pal.CORAL_PINK, 0.18f + 0.3f * k)
            val deg = o.sweepA * MathUtils.radiansToDegrees
            shapes.arc(o.x, o.y, Obispo.SWEEP_R, deg - 90f, 180f, 18)
        }
    }

    /** Tablones del muelle (o losas de la catedral): juntas desfasadas. */
    private fun drawPlank(tx: Int, ty: Int, x: Float, y: Float, t: Float) {
        if (biomeNow == Biome.ARCHIVO) {
            // Parqué de archivo: tablillas en espiga.
            col(if ((tx + ty) % 2 == 0) rp.floor else rp.floor2)
            shapes.rect(x, y, t, t)
            col(rp.seam)
            if ((tx + ty) % 2 == 0) { shapes.rect(x, y + 5f, t, 1f); shapes.rect(x, y + 11f, t, 1f) }
            else { shapes.rect(x + 5f, y, 1f, t); shapes.rect(x + 11f, y, 1f, t) }
            if ((tx * 7 + ty * 3) % 11 == 0) { col(Pal.PAPER_DIM, 0.5f); shapes.rect(x + 3f, y + 7f, 5f, 3f) }
            return
        }
        if (biomeNow == Biome.RAIZ) {
            // Roca agrietada con raíces.
            col(if ((tx + ty) % 2 == 0) rp.floor else rp.floor2)
            shapes.rect(x, y, t, t)
            col(rp.seam)
            shapes.rect(x + (tx * 3 % 7) + 3f, y + 2f, 1f, 6f)
            shapes.rect(x + 2f, y + (ty * 5 % 9) + 4f, 6f, 1f)
            if ((tx * 5 + ty * 7) % 9 == 0) { col(Pal.ROOT, 0.8f); shapes.rect(x + 1f, y + 8f, 12f, 2f) }
            return
        }
        if (catFloor) {
            col(if ((tx + ty) % 2 == 0) rp.floor else rp.floor2)
            shapes.rect(x, y, t, t)
            col(rp.seam)
            shapes.rect(x, y, t, 1f)
            shapes.rect(x, y, 1f, t)
            if ((tx * 5 + ty * 3) % 7 == 0) { col(Pal.CORAL_RED, 0.35f); shapes.rect(x + 4f, y + 5f, 3f, 2f) }
            return
        }
        col(if ((tx + ty) % 2 == 0) Pal.PLANK else Pal.PLANK2)
        shapes.rect(x, y, t, t)
        col(Pal.SEAM)
        shapes.rect(x, y, t, 1f)
        shapes.rect(x, y + 8f, t, 1f)
        shapes.rect(x + if (ty % 2 == 0) 5f else 11f, y, 1f, 8f)
        shapes.rect(x + if (ty % 2 == 0) 13f else 2f, y + 8f, 1f, 8f)
    }

    private fun drawPedestal(w: World, p: Pedestal) {
        val x = p.x.roundToInt().toFloat()
        val y = p.y.roundToInt().toFloat()
        val bob = if ((w.time * 2f).toInt() % 2 == 0) 0f else 1f
        when (p.kind) {
            PedKind.ESCALERA -> {
                outlined(x - 10f, y - 8f, 20f, 16f, Pal.VOID)
                col(Pal.WALL_TOP)
                for (i in 0 until 4) shapes.rect(x - 9f + i * 2f, y + 4f - i * 4f, 18f - i * 4f, 2f)
            }
            PedKind.REFUGIO -> {
                outlined(x - 4f, y - 4f, 8f, 4f, Pal.WALL_TOP)
                outlined(x - 1f, y, 2f, 14f, Pal.WALL)
                outlined(x - 5f, y + 14f, 10f, 10f, if (p.taken) Pal.SEAM else Pal.LAMP)
                if (!p.taken) {
                    col(Pal.LAMP_HI)
                    shapes.rect(x - 2f, y + 17f, 4f, 4f)
                }
            }
            PedKind.FIN -> {
                col(Pal.LAMP_HI, 0.35f)
                shapes.rect(x - 6f, y - 4f, 12f, 40f)
                col(Pal.CREAM, 0.6f)
                shapes.rect(x - 2f, y - 4f, 4f, 40f)
                outlined(x - 6f, y - 6f, 12f, 4f, Pal.WALL_TOP)
            }
            PedKind.VIGILIA -> {
                // Farol alto de la antesala: brilla hasta que se usa.
                outlined(x - 5f, y - 5f, 10f, 4f, Pal.WALL_TOP)
                outlined(x - 1f, y - 1f, 2f, 18f, Pal.WALL)
                outlined(x - 6f, y + 17f, 12f, 12f, if (p.taken) Pal.SEAM else Pal.LAMP)
                if (!p.taken) {
                    col(if (bob > 0f) Pal.CREAM else Pal.LAMP_HI)
                    shapes.rect(x - 3f, y + 20f, 6f, 6f)
                }
            }
            PedKind.DESCENSO -> {
                // Pozo de descenso del Santuario.
                col(Pal.OUTLINE)
                shapes.ellipse(x - 15f, y - 9f, 30f, 18f)
                col(Pal.VOID)
                shapes.ellipse(x - 13f, y - 7f, 26f, 14f)
                col(Pal.TIDE, 0.6f + 0.3f * bob)
                shapes.ellipse(x - 8f, y - 4f, 16f, 8f)
            }
            PedKind.MAREA -> {
                // Escala de marea: un poste con 10 marcas; se llenan hasta la Marea elegida.
                outlined(x - 2f, y - 6f, 4f, 30f, Pal.STONE)
                val lvl = if (Meta.mareaBaja) 0 else Meta.marea
                for (i in 0 until 10) {
                    val on = i < lvl
                    val unlocked = i < Meta.mareaMax
                    col(if (on) Pal.TIDE else if (unlocked) Pal.WALL_TOP else Pal.SEAM)
                    shapes.rect(x + 2f, y - 4f + i * 2.8f, if (i % 5 == 4) 5f else 3f, 1.5f)
                }
                col(if (Meta.mareaBaja) Pal.FOAM else Pal.TIDE, 0.6f + 0.3f * bob)
                shapes.rect(x - 6f, y - 7f, 12f, 3f)
            }
            PedKind.ARMA -> {
                val locked = p.lockedText != null
                if (p.selected) {
                    col(Pal.LAMP, 0.5f)
                    shapes.rect(x - 9f, y - 9f, 18f, 24f)
                }
                outlined(x - 6f, y - 6f, 12f, 8f, Pal.WALL_TOP)
                val wc = if (locked) Pal.SEAM else weaponColor(p.weapon!!)
                drawWeaponIcon(p.weapon!!, x, y + 7f + if (locked) 0f else bob, wc)
            }
            PedKind.META -> {
                outlined(x - 6f, y - 6f, 12f, 8f, if (p.taken) Pal.LAMP_DIM else Pal.WALL_TOP)
                val c = when {
                    p.taken -> Pal.LAMP
                    p.lockedText != null -> Pal.SEAM
                    else -> Pal.SHELL
                }
                col(Pal.OUTLINE)
                shapes.circle(x, y + 7f + bob, 4f, 10)
                col(c)
                shapes.circle(x, y + 7f + bob, 3f, 10)
                col(Pal.CREAM, 0.8f)
                shapes.rect(x - 1f, y + 8f + bob, 1f, 1f)
            }
            PedKind.INICIAL -> {
                if (p.selected) {
                    col(Pal.FOAM, 0.5f)
                    shapes.rect(x - 9f, y - 9f, 18f, 24f)
                }
                outlined(x - 6f, y - 6f, 12f, 8f, Pal.WALL_TOP)
                outlined(x - 3f, y + 4f + bob, 6f, 6f, p.exvoto!!.rarity.color)
                col(p.exvoto!!.tag.color)
                shapes.rect(x - 2f, y + 5f + bob, 4f, 4f)
            }
            PedKind.JAULA -> {
                // Barrotes de hierro; Amparo dentro hasta que la liberas.
                if (!p.taken) drawNpc(p.metaId, x, y - 2f, w.time)
                val open = p.taken
                col(Pal.OUTLINE)
                shapes.rect(x - 14f, y + 22f, 28f, 3f)
                shapes.rect(x - 14f, y - 8f, 28f, 2f)
                for (i in 0 until 7) {
                    val bx = x - 13f + i * 4.2f
                    if (open && i in 2..4) continue   // la puerta queda abierta
                    col(Pal.OUTLINE)
                    shapes.rect(bx - 0.5f, y - 8f, 2f, 31f)
                    col(Pal.STONE)
                    shapes.rect(bx, y - 7f, 1f, 29f)
                }
                if (!open) {
                    // Candado: brilla cuando ya no quedan guardias.
                    outlined(x - 2f, y + 4f, 4f, 4f, if (p.selected && bob > 0f) Pal.LAMP_HI else Pal.LAMP_DIM)
                }
            }
            PedKind.AMPARO -> drawAmparo(x, y - 2f, w.time)
            PedKind.NPC -> drawNpc(p.metaId, x, y - 2f, w.time)
            PedKind.LOTE -> {
                outlined(x - 7f, y - 6f, 14f, 8f, if (p.taken) Pal.LAMP_DIM else if (p.lockedText != null) Pal.SEAM else Pal.WALL_TOP)
                // Un pozo pequeño con exvotos flotando.
                for (i in 0 until 3) {
                    val c = when (i) { 0 -> Pal.RARE; 1 -> Pal.EPIC; else -> Pal.CREAM }
                    outlined(x - 6f + i * 5f, y + 4f + bob + (i % 2) * 2f, 3f, 3f, if (p.lockedText != null) Pal.SEAM else c)
                }
            }
            PedKind.HEROE -> {
                outlined(x - 6f, y - 6f, 12f, 6f, Pal.WALL_TOP)
                val bruno = Meta.hero == "campanero"
                outlined(x - 3f, y, 6f, 8f, if (bruno) Pal.VIOLET else Pal.COAT)
                outlined(x - 2f, y + 8f, 4f, 4f, Pal.SKIN)
                if (Meta.campaneroPlayable) { col(Pal.LAMP_HI, 0.5f + 0.4f * bob); shapes.rect(x - 7f, y + 13f, 14f, 1f) }
            }
            PedKind.BOTELLA -> {
                outlined(x - 3f, y - 4f + bob, 6f, 9f, Pal.GLASS_TEAL)
                outlined(x - 1f, y + 5f + bob, 2f, 3f, Pal.GLASS_TEAL)
                col(Pal.PAPER)
                shapes.rect(x - 1f, y - 2f + bob, 2f, 5f)
                col(Pal.ARC_DOOR)
                shapes.rect(x - 1f, y + 8f + bob, 2f, 1f)
            }
            PedKind.OFRENDA_MAX, PedKind.OFRENDA_MITAD -> {
                // Altar de piedra con el exvoto encima; la mancha de color dice qué pide.
                outlined(x - 8f, y - 6f, 16f, 9f, Pal.STONE)
                col(if (p.kind == PedKind.OFRENDA_MAX) Pal.CORAL_RED else Pal.LAMP_DIM)
                shapes.rect(x - 6f, y - 4f, 12f, 2f)
                val e = p.exvoto!!
                outlined(x - 3f, y + 6f + bob, 6f, 6f, e.rarity.color)
                col(e.tag.color)
                shapes.rect(x - 2f, y + 7f + bob, 4f, 4f)
            }
            else -> {
                outlined(x - 6f, y - 6f, 12f, 8f, Pal.WALL_TOP)
                col(Pal.WALL)
                shapes.rect(x - 6f, y - 6f, 12f, 3f)
                val c = when (p.kind) {
                    PedKind.EXVOTO -> p.exvoto!!.tag.color
                    PedKind.MECHA -> Pal.LAMP
                    PedKind.MEJORA -> Pal.CORAL
                    else -> Pal.TIDE
                }
                val frame = when (p.kind) {
                    PedKind.EXVOTO -> p.exvoto!!.rarity.color
                    else -> Pal.CREAM
                }
                outlined(x - 3f, y + 4f + bob, 6f, 6f, frame)
                col(c)
                shapes.rect(x - 2f, y + 5f + bob, 4f, 4f)
            }
        }
    }

    /** Náufragos: cada uno con su silueta. */
    private fun drawNpc(key: String?, x: Float, y: Float, t: Float) {
        when (key) {
            Meta.RESCUE_CAMPANERO -> {
                col(Pal.SHADOW); shapes.ellipse(x - 5f, y - 3f, 10f, 4f)
                outlined(x - 5f, y - 1f, 10f, 12f, Pal.VIOLET)
                outlined(x - 4f, y + 11f, 8f, 7f, Pal.SKIN)
                col(Pal.LILAC); shapes.rect(x - 4f, y + 11f, 8f, 1f)
                col(Pal.OUTLINE); shapes.rect(x - 2f, y + 14f, 1f, 1f); shapes.rect(x + 1f, y + 14f, 1f, 1f)
                val k = if ((t * 2f).toInt() % 2 == 0) 0f else 1f
                col(Pal.STONE); shapes.triangle(x + 5f, y + 2f - k, x + 11f, y + 2f - k, x + 8f, y + 8f - k)
            }
            Meta.RESCUE_CARTOGRAFA -> {
                col(Pal.SHADOW); shapes.ellipse(x - 5f, y - 3f, 10f, 4f)
                outlined(x - 5f, y - 1f, 10f, 12f, Pal.PAPER_DIM)
                col(Pal.INK); shapes.rect(x - 4f, y + 4f, 8f, 1f); shapes.rect(x, y, 1f, 9f)
                outlined(x - 4f, y + 11f, 8f, 7f, Pal.SKIN)
                col(Pal.BOOK_OCHRE); shapes.rect(x - 4f, y + 15f, 8f, 3f)
                col(Pal.OUTLINE); shapes.rect(x - 2f, y + 13f, 1f, 1f); shapes.rect(x + 1f, y + 13f, 1f, 1f)
                outlined(x + 5f, y + 1f, 2f, 9f, Pal.PAPER)
            }
            Meta.HIJA -> {
                col(Pal.SHADOW); shapes.ellipse(x - 5f, y - 3f, 10f, 4f)
                outlined(x - 5f, y - 1f, 10f, 12f, Pal.RAZ_WALL_TOP)
                outlined(x - 4f, y + 11f, 8f, 7f, Pal.SKIN)
                col(Pal.CORAL_RED); shapes.rect(x - 4f, y + 15f, 8f, 3f); shapes.rect(x - 5f, y + 11f, 1f, 5f)
                col(Pal.OUTLINE); shapes.rect(x - 2f, y + 13f, 1f, 1f); shapes.rect(x + 1f, y + 13f, 1f, 1f)
                // Un farol apagado en la mano.
                outlined(x + 5f, y + 2f, 3f, 4f, Pal.SEAM)
            }
            else -> drawAmparo(x, y, t)
        }
    }

    /** Amparo, la guardesa: abrigo gris azulado, pelo blanco y un manojo de llaves. */
    private fun drawAmparo(x: Float, y: Float, t: Float) {
        col(Pal.SHADOW)
        shapes.ellipse(x - 5f, y - 3f, 10f, 4f)
        outlined(x - 5f, y - 1f, 10f, 12f, Pal.GLASS_TEAL)
        outlined(x - 4f, y + 11f, 8f, 7f, Pal.SKIN)
        col(Pal.CREAM)
        shapes.rect(x - 4f, y + 15f, 8f, 3f)
        shapes.rect(x - 5f, y + 13f, 1f, 4f)
        col(Pal.OUTLINE)
        shapes.rect(x - 2f, y + 13f, 1f, 1f); shapes.rect(x + 1f, y + 13f, 1f, 1f)
        // Llaves que se mecen.
        val k = if ((t * 2f).toInt() % 2 == 0) 0f else 1f
        col(Pal.LAMP_DIM)
        shapes.rect(x + 5f, y + 3f - k, 2f, 3f)
        col(Pal.LAMP)
        shapes.rect(x + 5f, y + 2f - k, 1f, 1f)
    }

    private fun weaponColor(wp: Weapon): Color = when (wp) {
        Weapon.FAROL -> Pal.LAMP
        Weapon.ARPON -> Pal.CREAM
        Weapon.ANCLA -> Pal.WALL_TOP
        Weapon.CAMPANA -> Pal.SHELL
    }

    /** Icono simple de cada arma (pedestales del Santuario y mano de Isla). */
    private fun drawWeaponIcon(wp: Weapon, x: Float, y: Float, c: Color) {
        when (wp) {
            Weapon.FAROL -> {
                outlined(x - 3f, y - 3f, 6f, 6f, c)
                col(Pal.LAMP_HI)
                shapes.rect(x - 1f, y - 1f, 2f, 2f)
            }
            Weapon.ARPON -> {
                col(Pal.OUTLINE)
                shapes.rect(x - 1f, y - 6f, 3f, 12f)
                col(c)
                shapes.rect(x, y - 5f, 1f, 10f)
                shapes.triangle(x - 2f, y + 4f, x + 3f, y + 4f, x + 0.5f, y + 8f)
            }
            Weapon.ANCLA -> {
                outlined(x - 1f, y - 2f, 2f, 9f, c)
                outlined(x - 5f, y - 4f, 10f, 2f, c)
                outlined(x - 5f, y - 3f, 2f, 3f, c)
                outlined(x + 3f, y - 3f, 2f, 3f, c)
            }
            Weapon.CAMPANA -> {
                col(Pal.OUTLINE)
                shapes.triangle(x - 6f, y - 4f, x + 6f, y - 4f, x, y + 6f)
                col(c)
                shapes.triangle(x - 5f, y - 3f, x + 5f, y - 3f, x, y + 5f)
                col(Pal.LAMP_DIM)
                shapes.rect(x - 1f, y - 5f, 2f, 2f)
            }
        }
    }

    private fun drawPlayer(w: World, p: Player, a: Float) {
        val x = p.ix(a).roundToInt().toFloat()
        val y = p.iy(a).roundToInt().toFloat()
        if (p.falling) {
            // Se hunde en el agua: solo queda el farol encogiendo.
            val k = (p.fallT / Player.FALL_TIME).coerceIn(0f, 1f)
            outlined(x - 2f * k, y - 2f + 6f * k, 4f * k + 1f, 4f * k + 1f, Pal.LAMP)
            return
        }
        col(Pal.SHADOW)
        shapes.ellipse(x - 6f, y - 4f, 12f, 5f)
        val blink = p.hurtIframes > 0f && (w.time * 15f).toInt() % 2 == 0
        if (!blink && !w.gameOver) {
            // Bruno, el campanero: hábito violeta, calva y una campanita al cuello.
            val bruno = Meta.hero == "campanero" && Meta.campaneroPlayable && (!w.inSanctuary || true)
            val body = if (p.dashT > 0f) Pal.LAMP_HI else if (bruno) Pal.VIOLET else Pal.COAT
            outlined(x - 5f, y - 3f, 10f, 12f, body)
            outlined(x - 4f, y + 9f, 8f, 7f, Pal.SKIN)
            if (bruno) {
                col(Pal.LILAC); shapes.rect(x - 4f, y + 9f, 8f, 1f)
                col(Pal.STONE); shapes.rect(x - 1f, y + 5f, 2f, 2f)
            } else {
                col(Pal.HAIR)
                shapes.rect(x - 4f, y + 13f, 8f, 3f)
            }
            col(Pal.OUTLINE)
            if (p.aimX >= 0f) shapes.rect(x + 1f, y + 11f, 1f, 1f) else shapes.rect(x - 2f, y + 11f, 1f, 1f)
        }
        // Farol (siempre visible: es la vida). Con otra arma, el farol cuelga del cinto.
        val lx = (x + p.aimX * 8f).roundToInt().toFloat()
        val ly = (y + 5f + p.aimY * 5f).roundToInt().toFloat()
        if (p.veiling) {
            // Velando: Isla tapa el farol con el abrigo, pegado al cuerpo.
            outlined(x - 2f, y + 2f, 4f, 4f, Pal.LAMP_DIM)
            col(Pal.COAT)
            shapes.rect(x - 3f, y + 5f, 6f, 2f)
            // Carga del Fogonazo: puntos que se cierran alrededor; brillan cuando está listo.
            if (w.stats.flashOn) {
                val ready = p.flashCharged
                val k = (p.veilT / Balance.VEIL_CHARGE).coerceIn(0f, 1f)
                val rr = 18f - 6f * k
                val c = if (ready) Pal.LAMP_HI else if (p.flashCd > 0f) Pal.SEAM else Pal.LAMP_DIM
                for (i in 0 until 8) {
                    val ang = i * MathUtils.PI2 / 8f + w.time * (if (ready) 4f else 1.5f)
                    col(c, if (ready) 0.95f else 0.6f)
                    shapes.rect((x + cos(ang) * rr).roundToInt().toFloat(), (y + 5f + sin(ang) * rr).roundToInt().toFloat(), 1f, 1f)
                }
            }
        } else if (p.weapon == Weapon.FAROL || w.gameOver) {
            outlined(lx - 2f, ly - 2f, 4f, 4f, if (w.gameOver) Pal.SEAM else Pal.LAMP)
        } else {
            outlined(x - (if (p.aimX >= 0f) 7f else -4f), y + 1f, 3f, 3f, Pal.LAMP)
            if (p.weapon != Weapon.ARPON || p.harpoon == null) drawWeaponIcon(p.weapon, lx + (if (kotlin.math.abs(p.aimX) < 0.4f) 8f else 0f), ly + 1f, weaponColor(p.weapon))
        }
    }

    private fun drawAttacks(p: Player) {
        if ((p.swingActive || p.swingSmear) && (p.weapon == Weapon.FAROL || p.weapon == Weapon.ANCLA)) {
            val ang = atan2(p.atkDy, p.atkDx) * MathUtils.radiansToDegrees
            val half = p.swingHalfAngleDeg
            if (p.weapon == Weapon.ANCLA) {
                if (p.swingActive) col(Pal.CREAM, 0.8f) else col(Pal.WALL_TOP, 0.5f)
            } else {
                if (p.swingActive) col(Pal.LAMP_HI, 0.85f) else col(Pal.LAMP_DIM, 0.45f)
            }
            shapes.arc(p.cx, p.cy, p.swingRange, ang - half, half * 2f, if (p.weapon == Weapon.ANCLA) 16 else 10)
        }
        val h = p.harpoon
        if (h != null) {
            // Cuerda, asta y punta del Arpón.
            col(Pal.SHELL, 0.5f)
            shapes.rectLine(p.cx, p.cy, h.x, h.y, 1f)
            val tx = if (h.st == Harpoon.St.RETURNING) -h.dx else h.dx
            val ty = if (h.st == Harpoon.St.RETURNING) -h.dy else h.dy
            col(Pal.OUTLINE)
            shapes.rectLine(h.x - h.dx * 11f, h.y - h.dy * 11f, h.x + h.dx * 1f, h.y + h.dy * 1f, 3f)
            col(Pal.CREAM)
            shapes.rectLine(h.x - h.dx * 10f, h.y - h.dy * 10f, h.x, h.y, 1.5f)
            col(if (h.st == Harpoon.St.STUCK) Pal.LAMP else Pal.LAMP_HI)
            shapes.rect(h.x - 1.5f + tx, h.y - 1.5f + ty, 3f, 3f)
        }
        if (p.hazT > 0f) {
            val ex = p.cx + p.aimX * p.hazLen
            val ey = p.cy + p.aimY * p.hazLen
            col(Pal.LAMP, 0.9f)
            shapes.rectLine(p.cx, p.cy, ex, ey, Balance.HAZ_WIDTH)
            col(Pal.CREAM)
            shapes.rectLine(p.cx, p.cy, ex, ey, 2f)
        }
    }

    private fun drawEnemy(w: World, e: Enemy, a: Float) {
        val x = e.ix(a).roundToInt().toFloat()
        val y = e.iy(a).roundToInt().toFloat()
        // Destellos suaves: sin parpadeo blanco al golpear.
        val flash = e.flash > 0f && !Settings.softFlashes
        val tele = e.state == EState.TELEGRAPH
        val blink = (w.time * 16f).toInt() % 2 == 0

        // Solo existe lo que iluminas: fuera de tu luz no se dibuja (el Tachado deja ver su barra).
        if (e.hidden) {
            if (e.has(Affix.TACHADO) && e.hp < e.maxHp) {
                col(Pal.OUTLINE); shapes.rect(x - 8f, y + e.hh + 5f, 16f, 3f)
                col(Pal.TACHADO); shapes.rect(x - 7f, y + e.hh + 6f, 14f * (e.hp / e.maxHp), 1f)
            }
            return
        }
        if (e is Sirena) { drawSirena(w, e, x, y, flash, blink); return }
        if (e is Obispo) { drawObispo(w, e, x, y, flash, blink); return }
        if (e is Cartografo) { drawCartografo(w, e, x, y, flash, blink); return }
        if (e is LamparaMadre) { drawLampara(w, e, x, y, flash, blink); return }
        if (e is FareroAntiguo) { drawFarero(e, x, y, flash, blink); return }
        if (e is Criatura) { drawCriatura(e, x, y, flash, blink); return }
        if (e is Anguila && e.phase != Anguila.Phase.EXPOSED) {
            // Bajo el suelo: solo una onda en la tierra.
            col(Pal.ROOT, 0.5f)
            shapes.rect(x - 5f + sin(e.anim * 4f) * 2f, y - 3f, 10f, 1f)
            return
        }

        col(Pal.SHADOW)
        shapes.ellipse(x - e.hw, y - e.hh - 2f, e.hw * 2f, 5f)
        // Élite: aura del color del afijo.
        val affix = e.affix
        if (affix != null && !e.killed) {
            // Hambre de luz: el aura crece un 10 % por cada 20 de tu Luz.
            val grow = if (affix == Affix.HAMBRE) 2f * (w.player.luz / 20f).toInt() else 0f
            col(affix.color, if (blink) 0.9f else 0.55f)
            shapes.rect(x - e.hw - 3f - grow, y - e.hh - 3f - grow, e.hw * 2f + 6f + grow * 2f, e.hh * 2f + 6f + grow * 2f)
            e.affix2?.let { a2 ->
                col(a2.color, if (blink) 0.55f else 0.9f)
                shapes.rect(x - e.hw - 5f - grow, y - e.hh - 5f - grow, e.hw * 2f + 10f + grow * 2f, 2f)
            }
        }

        when (e) {
            is Ahogado -> {
                val squash = if (tele) 3f else 0f        // se agacha antes de embestir
                val c = when {
                    flash -> Color.WHITE
                    tele && blink -> Pal.AHOGADO_HI
                    else -> Pal.AHOGADO
                }
                outlined(x - 6f, y - 6f, 12f, 14f - squash, c)
                col(if (tele) Pal.LAMP_HI else Pal.OUTLINE)
                shapes.rect(x - 3f, y + 3f - squash, 2f, 2f)
                shapes.rect(x + 1f, y + 3f - squash, 2f, 2f)
                if (tele) {
                    col(Pal.CORAL, 0.6f)
                    shapes.rectLine(x, y, x + e.dirX * 44f, y + e.dirY * 44f, 2f)
                }
            }
            is Medusa -> {
                val bob = sin(e.anim * 3f) * 2f
                val c = when {
                    flash -> Color.WHITE
                    e.killed -> if (blink) Color.WHITE else Pal.CORAL
                    else -> Pal.MEDUSA
                }
                for (i in -1..1) {
                    col(Pal.MEDUSA_IN)
                    shapes.rect(x + i * 3f, y + bob - 10f + sin(e.anim * 6f + i) * 1f, 1f, 6f)
                }
                col(Pal.OUTLINE)
                shapes.circle(x, y + bob, 7f, 14)
                col(c)
                shapes.circle(x, y + bob, 6f, 14)
                col(Pal.MEDUSA_IN)
                shapes.circle(x, y + bob - 1f, 3f, 10)
            }
            is Cangrejo -> {
                val c = if (flash) Color.WHITE else Pal.CANGREJO
                outlined(x - 9f, y - 6f, 18f, 12f, c)
                val fx = cos(e.face)
                val fy = sin(e.face)
                val px = -fy
                val py = fx
                // Caparazón frontal: por aquí no entra el Farol.
                val sx = x + fx * 8f
                val sy = y + fy * 8f
                col(Pal.OUTLINE)
                shapes.rectLine(sx - px * 9f, sy - py * 9f, sx + px * 9f, sy + py * 9f, 5f)
                col(Pal.SHELL)
                shapes.rectLine(sx - px * 8f, sy - py * 8f, sx + px * 8f, sy + py * 8f, 3f)
                val clawCol = if (tele && blink) Pal.CORAL else if (e.state == EState.ATTACK) Pal.LAMP_HI else Pal.SHELL
                for (s in arrayOf(-1f, 1f)) {
                    val cx = x + fx * 13f + px * 6f * s
                    val cy = y + fy * 13f + py * 6f * s
                    outlined(cx.roundToInt() - 2f, cy.roundToInt() - 2f, 4f, 4f, clawCol)
                }
            }
            is Monaguillo -> {
                // Túnica de sal y cabeza; al apuntar, tres cristales brillan delante.
                val c = if (flash) Color.WHITE else Pal.SALADO
                outlined(x - 5f, y - 6f, 10f, 10f, c)
                outlined(x - 3f, y + 4f, 6f, 5f, Pal.SKIN)
                col(Pal.OUTLINE)
                shapes.rect(x - 1f, y + 6f, 1f, 1f); shapes.rect(x + 1f, y + 6f, 1f, 1f)
                col(Pal.LILAC)
                shapes.rect(x - 5f, y - 1f, 10f, 1f)
                if (tele) {
                    for (i in -1..1) {
                        val a = e.aimA + i * Monaguillo.SPREAD
                        col(if (blink) Pal.CREAM else Pal.GLASS_LIGHT)
                        shapes.rect(x + cos(a) * 10f - 1.5f, y + sin(a) * 10f - 1.5f, 3f, 3f)
                    }
                    col(Pal.CREAM, 0.25f)
                    shapes.rectLine(x, y, x + cos(e.aimA) * 60f, y + sin(e.aimA) * 60f, 1f)
                }
            }
            is Campanero -> {
                // Encorvado, con la campana: la levanta durante el aviso.
                val c = if (flash) Color.WHITE else Pal.VIOLET
                outlined(x - 7f, y - 8f, 14f, 13f, c)
                outlined(x - 4f, y + 5f, 8f, 5f, Pal.LILAC)
                col(Pal.OUTLINE)
                shapes.rect(x - 2f, y + 7f, 1f, 1f); shapes.rect(x + 1f, y + 7f, 1f, 1f)
                val lift = if (tele) 8f + (1f - e.stateT / e.def.telegraph) * 4f else 0f
                val bx = x + 7f
                val by = y - 2f + lift
                col(Pal.OUTLINE)
                shapes.triangle(bx - 6f, by - 4f, bx + 6f, by - 4f, bx, by + 6f)
                col(if (tele && blink) Pal.LAMP_HI else Pal.STONE)
                shapes.triangle(bx - 5f, by - 3f, bx + 5f, by - 3f, bx, by + 5f)
                col(Pal.LAMP_DIM)
                shapes.rect(bx - 1f, by - 5f, 2f, 2f)
                if (tele) {
                    col(Pal.LILAC, 0.25f)
                    shapes.circle(x, y, Campanero.WAVE_R * (1f - e.stateT / e.def.telegraph), 28)
                }
            }
            is Vela -> {
                val c = if (flash) Color.WHITE else Pal.BONE
                val bob = sin(e.anim * 6f) * 1.5f
                outlined(x - 3f, y - 5f + bob, 6f, 9f, c)
                val big = if (!tele) 0f else if (blink) 3f else 2f
                col(Pal.LAMP)
                shapes.rect(x - 2f, y + 4f + bob, 4f, 4f + big)
                col(Pal.LAMP_HI)
                shapes.rect(x - 1f, y + 5f + bob, 2f, 2f + big)
                if (tele) {
                    col(Pal.LAMP, 0.4f)
                    shapes.rectLine(x, y, x + e.dirX * 40f, y + e.dirY * 40f, 1f)
                }
            }
            is Cofrade -> {
                // Encapuchado de coral arrastrando una cruz; aviso: raya rosada de 64 px en el suelo.
                if (tele) {
                    col(Pal.CORAL_PINK, if (blink) 0.75f else 0.45f)
                    shapes.rectLine(x, y - 6f, x + e.dirX * e.def.range, y - 6f + e.dirY * e.def.range, 3f)
                }
                val c = if (flash) Color.WHITE else Pal.CORAL_RED
                outlined(x - 8f, y - 8f, 16f, 15f, c)
                outlined(x - 5f, y + 6f, 10f, 6f, Pal.CORAL_PINK)
                col(Pal.OUTLINE)
                shapes.rect(x - 3f, y + 8f, 6f, 2f)
                val cx = x + 10f
                outlined(cx - 1f, y - 9f, 3f, 18f, Pal.BONE)
                outlined(cx - 4f, y + 3f, 9f, 3f, Pal.BONE)
            }
        }
        when (e) {
            is Tinta -> {
                val c = if (flash) Color.WHITE else Pal.INK
                val wob = sin(e.anim * 5f)
                col(Pal.OUTLINE)
                shapes.circle(x, y + 1f, 8f, 14)
                col(c)
                shapes.circle(x, y + 1f, 7f + wob * 0.5f, 14)
                col(Pal.INK_HI)
                shapes.rect(x - 3f, y + 2f, 2f, 2f); shapes.rect(x + 1f, y + 2f, 2f, 2f)
                for (i in -1..1) { col(Pal.INK); shapes.rect(x + i * 4f, y - 8f - (e.anim * 3f + i).mod(3f), 1f, 3f) }
                if (tele) { col(Pal.INK_HI, 0.6f); shapes.rectLine(x, y, x + e.dirX * 38f, y + e.dirY * 38f, 2f) }
            }
            is Bibliotecario -> {
                val a2 = if (e.fading > 0f) (e.fading / 0.45f).coerceIn(0.15f, 1f) else 1f
                val c = if (flash) Color.WHITE else Pal.PAPER_DIM
                col(Pal.OUTLINE, a2); shapes.rect(x - 8f, y - 10f, 16f, 22f)
                col(c, a2); shapes.rect(x - 7f, y - 9f, 14f, 20f)
                col(Pal.ARC_WALL, a2); shapes.rect(x - 7f, y - 9f, 14f, 3f)
                // Cara vacía: un óvalo de papel sin rasgos.
                col(Pal.PAPER, a2); shapes.ellipse(x - 4f, y + 4f, 8f, 7f)
                if (tele && e.fading <= 0f) { col(Pal.CORAL, 0.6f); shapes.rect(x + e.dirX * 12f - 3f, y + e.dirY * 12f - 3f, 6f, 6f) }
            }
            is Polilla -> {
                val flap = if ((e.anim * 14f).toInt() % 2 == 0) 4f else 2f
                col(if (flash) Color.WHITE else Pal.PAPER)
                shapes.triangle(x, y, x - 5f, y + flap, x - 5f, y - 2f)
                shapes.triangle(x, y, x + 5f, y + flap, x + 5f, y - 2f)
                col(Pal.ARC_SEAM)
                shapes.rect(x - 0.5f, y - 2f, 1f, 4f)
            }
            is Copista -> {
                val c = if (flash) Color.WHITE else Pal.ARC_DOOR
                outlined(x - 6f, y - 7f, 12f, 12f, c)
                outlined(x - 4f, y + 5f, 8f, 6f, Pal.PAPER_DIM)
                // Pluma
                col(Pal.PAPER)
                shapes.rectLine(x + 6f, y, x + 11f, y + 9f, 1f)
                if (tele) { col(Pal.INK_HI, if (blink) 0.9f else 0.5f); shapes.rect(x + 9f, y - 1f, 3f, 3f) }
            }
            is PaperClone -> {
                // Tu silueta en papel, con el golpe que repite.
                col(Pal.PAPER, 0.85f)
                shapes.rect(x - 5f, y - 8f, 10f, 12f)
                shapes.rect(x - 4f, y + 4f, 8f, 7f)
                col(Pal.INK, 0.8f)
                shapes.rect(x - 5f, y - 8f, 10f, 1f)
                if (e.swingT > 0f) {
                    col(Pal.PAPER_DIM, 0.7f)
                    val deg = atan2(e.swingDy, e.swingDx) * MathUtils.radiansToDegrees
                    shapes.arc(x, y, 28f, deg - 30f, 60f, 10)
                }
            }
            is HijoDelFaro -> {
                // Isla en negativo: abrigo oscuro, ojos de lámpara y tu misma arma.
                val c = if (flash) Color.WHITE else Pal.RAZ_WALL_TOP
                outlined(x - 5f, y - 8f, 10f, 12f, c)
                outlined(x - 4f, y + 4f, 8f, 7f, Pal.ROOT)
                col(Pal.LAMP_HI)
                shapes.rect(x - 2f, y + 7f, 1f, 1f); shapes.rect(x + 1f, y + 7f, 1f, 1f)
                drawWeaponIcon(e.weapon, x + 8f, y + 1f, if (tele && blink) Pal.LAMP_HI else weaponColor(e.weapon))
                if (tele) { col(Pal.CORAL, 0.5f); shapes.rectLine(x, y, x + e.dirX * 34f, y + e.dirY * 34f, 2f) }
            }
            is Vidriero -> {
                val c = if (flash) Color.WHITE else Pal.GLASSY
                outlined(x - 9f, y - 9f, 18f, 18f, c)
                col(Pal.MOLTEN)
                shapes.rect(x - 3f, y - 3f, 6f, 6f)
                col(Pal.MOLTEN_HI)
                shapes.rect(x - 1f, y - 1f, 2f, 2f)
                if (e.crackT > 0f && blink) {
                    col(Color.WHITE)
                    shapes.rect(x - 7f, y + 2f, 6f, 1f); shapes.rect(x + 2f, y - 5f, 1f, 7f); shapes.rect(x - 2f, y - 7f, 1f, 4f)
                }
                if (tele) { col(Pal.MOLTEN, 0.3f); shapes.circle(x, y, e.def.range * (1f - e.stateT / e.def.telegraph), 18) }
            }
            is OjoFosa -> {
                outlined(x - 7f, y - 6f, 14f, 12f, Pal.RAZ_WALL)
                col(if (flash) Color.WHITE else Pal.BONE)
                shapes.ellipse(x - 6f, y - 4f, 12f, 8f)
                val p = w.player
                val la = atan2(p.cy - y, p.cx - x)
                col(if (e.state == EState.TELEGRAPH) Pal.MOLTEN_HI else Pal.MOLTEN)
                shapes.circle(x + cos(la) * 2f, y + sin(la) * 1.5f, 2.5f, 10)
                col(Pal.OUTLINE)
                shapes.rect(x + cos(la) * 2f - 0.5f, y + sin(la) * 1.5f - 1f, 1f, 2f)
            }
            is Anguila -> {
                // Expuesta: el cuerpo de raíz sale de la tierra.
                val c = if (flash) Color.WHITE else Pal.ROOT_HI
                for (i in 0 until 4) {
                    val sx = x + sin(e.anim * 4f + i) * 2f
                    outlined(sx - 4f + i * 0.5f, y - 6f + i * 5f, 8f - i, 6f, c)
                }
                col(Pal.MOLTEN_HI)
                shapes.rect(x - 2f, y + 14f, 1f, 1f); shapes.rect(x + 1f, y + 14f, 1f, 1f)
            }
            is Corazon -> {
                val beat = 1f + 0.1f * sin(e.anim * 5f)
                col(Pal.OUTLINE)
                shapes.circle(x, y, 13f * beat, 20)
                col(if (flash) Color.WHITE else Pal.ROOT)
                shapes.circle(x, y, 12f * beat, 20)
                col(Pal.MOLTEN, 0.85f)
                shapes.circle(x, y, 5f * beat, 14)
                for (i in 0 until 6) {
                    val a = i * MathUtils.PI2 / 6f
                    col(Pal.ROOT_HI)
                    shapes.rectLine(x + cos(a) * 10f, y + sin(a) * 10f, x + cos(a) * 22f, y + sin(a) * 22f, 3f)
                }
            }
            else -> {}
        }
        if (e.shielded) {
            col(Pal.BENDECIDO, if (blink) 0.75f else 0.5f)
            shapes.rect(x - e.hw - 4f, y + e.hh + 2f, e.hw * 2f + 8f, 1f)
            shapes.rect(x - e.hw - 4f, y - e.hh - 3f, 1f, e.hh * 2f + 6f)
            shapes.rect(x + e.hw + 3f, y - e.hh - 3f, 1f, e.hh * 2f + 6f)
        }

        if (e.stunned) {
            // Aturdido: dos estrellas que giran sobre la cabeza.
            val t = w.time * 6f
            col(Pal.LAMP_HI)
            shapes.rect(x + cos(t) * 6f - 1f, y + e.hh + 7f + sin(t) * 2f, 2f, 2f)
            shapes.rect(x - cos(t) * 6f - 1f, y + e.hh + 7f - sin(t) * 2f, 2f, 2f)
        }
        if (e.burnT > 0f && blink) {
            col(Pal.LAMP_DIM)
            shapes.rect(x - 1f, y + e.hh + 2f, 2f, 2f)
        }
        if (e.slowed) {
            col(Pal.SALADO, 0.8f)
            shapes.rect(x - e.hw, y - e.hh - 1f, e.hw * 2f, 1f)
        }
        if (!e.killed && e.hp < e.maxHp) {
            col(Pal.OUTLINE)
            shapes.rect(x - 8f, y + e.hh + 5f, 16f, 3f)
            col(if (affix != null) affix.color else Pal.CORAL)
            shapes.rect(x - 7f, y + e.hh + 6f, 14f * (e.hp / e.maxHp), 1f)
        }
    }

    /** El Cartógrafo: capa de mapas, sombrero de ala y un compás que se abre antes de disparar. */
    private fun drawCartografo(w: World, c: Cartografo, x: Float, y: Float, flash: Boolean, blink: Boolean) {
        col(Pal.SHADOW)
        shapes.ellipse(x - 12f, y - 15f, 24f, 6f)
        val cape = if (flash) Color.WHITE else Pal.PAPER_DIM
        outlined(x - 11f, y - 13f, 22f, 20f, cape)
        // Líneas de mapa en la capa.
        col(Pal.INK, 0.8f)
        shapes.rect(x - 9f, y - 6f, 18f, 1f); shapes.rect(x - 3f, y - 12f, 1f, 18f); shapes.rect(x + 4f, y - 10f, 1f, 9f)
        outlined(x - 5f, y + 7f, 10f, 7f, Pal.SKIN)
        col(Pal.OUTLINE)
        shapes.rect(x - 9f, y + 13f, 18f, 2f)
        shapes.rect(x - 5f, y + 15f, 10f, 4f)
        col(Pal.OUTLINE)
        shapes.rect(x - 2f, y + 10f, 1f, 1f); shapes.rect(x + 2f, y + 10f, 1f, 1f)
        // Compás: 4 brazos que se abren en los últimos 0,4 s.
        val open = (1f - (c.compassT / 0.4f)).coerceIn(0f, 1f)
        val armL = 6f + open * 10f
        for (k in 0 until 4) {
            val a = c.compassA + k * PI.toFloat() / 2f
            col(if (open > 0f && blink) Pal.LAMP_HI else Pal.INK_HI)
            shapes.rectLine(x, y + 2f, x + cos(a) * armL, y + 2f + sin(a) * armL, 1.5f)
        }
        if (c.exposedT > 0f) {
            val t = w.time * 6f
            col(Pal.LAMP_HI)
            shapes.rect(x + cos(t) * 9f - 1f, y + 24f + sin(t) * 2f, 2f, 2f)
            shapes.rect(x - cos(t) * 9f - 1f, y + 24f - sin(t) * 2f, 2f, 2f)
        }
    }

    /** La Lámpara Madre: jaula de cristal con la llama; desde la fase 2 se abre y se ve un ojo. */
    /** El Farero Antiguo: viejo alto con capote, bastón y farol. */
    private fun drawFarero(f: FareroAntiguo, x: Float, y: Float, flash: Boolean, blink: Boolean) {
        col(Pal.SHADOW)
        shapes.ellipse(x - 9f, y - 12f, 18f, 5f)
        val coat = if (flash) Color.WHITE else if (f.act == FareroAntiguo.Act.EXPOSED && blink) Pal.CORAL else Pal.STONE
        outlined(x - 7f, y - 10f, 14f, 16f, coat)
        outlined(x - 5f, y + 6f, 10f, 8f, Pal.BONE)
        col(Pal.OUTLINE); shapes.rect(x - 3f, y + 9f, 2f, 2f); shapes.rect(x + 1f, y + 9f, 2f, 2f)
        outlined(x - 6f, y + 13f, 12f, 3f, Pal.SEAM)
        // Bastón
        col(Pal.OUTLINE); shapes.rect(x - 11f, y - 10f, 2f, 22f)
        // Farol
        val lx = x + cos(f.lanternA) * 10f
        val ly = y + sin(f.lanternA) * 10f
        outlined(lx - 3f, ly - 3f, 6f, 6f, if (f.lanternOn) Pal.LAMP_HI else Pal.SEAM)
        if (f.state == EState.TELEGRAPH && blink) { col(Pal.CORAL); shapes.rect(x - 1f, y + 18f, 2f, 4f) }
    }

    /** La criatura de la fosa: solo se ve su respiración luminosa y las fauces cuando está expuesta. */
    private fun drawCriatura(c: Criatura, x: Float, y: Float, flash: Boolean, blink: Boolean) {
        col(Pal.RAZ_VOID, 0.9f)
        shapes.ellipse(x - 14f, y - 11f, 28f, 22f)
        col(if (flash) Color.WHITE else Pal.MOLTEN, 0.4f + 0.5f * c.breath)
        shapes.ellipse(x - 5f, y - 3f, 10f, 6f)
        if (c.exposed) { col(Pal.CORAL); shapes.rect(x - 8f, y - 7f, 16f, 2f) }
        if (c.state == EState.TELEGRAPH && blink) { col(Pal.CORAL); shapes.ellipse(x - 3f, y - 2f, 6f, 4f) }
    }

    private fun drawLampara(w: World, l: LamparaMadre, x: Float, y: Float, flash: Boolean, blink: Boolean) {
        col(Pal.SHADOW)
        shapes.ellipse(x - 20f, y - 22f, 40f, 8f)
        outlined(x - 14f, y - 22f, 28f, 6f, Pal.STONE)
        val glass = if (flash) Color.WHITE else Pal.GLASSY
        col(Pal.OUTLINE)
        shapes.rect(x - 15f, y - 16f, 30f, 34f)
        col(glass, 0.55f)
        shapes.rect(x - 14f, y - 15f, 28f, 32f)
        for (i in 0 until 4) { col(Pal.STONE); shapes.rect(x - 14f + i * 9f, y - 15f, 2f, 32f) }
        outlined(x - 16f, y + 17f, 32f, 4f, Pal.STONE)
        val open = l.phase >= 2
        if (open) {
            // Dentro: la criatura mira.
            col(Pal.RAZ_VOID)
            shapes.ellipse(x - 9f, y - 6f, 18f, 12f)
            col(if (l.drinking && blink) Pal.CORAL else Pal.MOLTEN)
            shapes.ellipse(x - 4f, y - 3f, 8f, 6f)
            col(Pal.OUTLINE)
            shapes.rect(x - 0.5f, y - 3f, 1f, 6f)
        } else {
            col(Pal.LAMP)
            shapes.rect(x - 5f, y - 6f, 10f, 12f)
            col(Pal.LAMP_HI)
            shapes.rect(x - 2f, y - 2f, 4f, 6f)
        }
    }

    /** El Obispo Coral: casulla violeta, mitra de hueso con coral y báculo. */
    private fun drawObispo(w: World, o: Obispo, x: Float, y: Float, flash: Boolean, blink: Boolean) {
        col(Pal.SHADOW)
        shapes.ellipse(x - 14f, y - 15f, 28f, 7f)
        val robe = if (flash) Color.WHITE else Pal.VIOLET
        outlined(x - 11f, y - 13f, 22f, 20f, robe)
        col(Pal.LILAC)
        shapes.rect(x - 2f, y - 13f, 4f, 20f)
        col(Pal.CORAL_RED)
        shapes.rect(x - 9f, y - 11f, 3f, 4f)
        shapes.rect(x + 6f, y - 6f, 3f, 5f)
        outlined(x - 5f, y + 7f, 10f, 7f, Pal.SKIN)
        col(Pal.OUTLINE)
        shapes.rect(x - 2f, y + 10f, 1f, 1f); shapes.rect(x + 2f, y + 10f, 1f, 1f)
        // Mitra
        col(Pal.OUTLINE)
        shapes.triangle(x - 7f, y + 13f, x + 7f, y + 13f, x, y + 27f)
        col(if (o.act == Obispo.Act.ROAR && blink) Pal.LAMP_HI else Pal.BONE)
        shapes.triangle(x - 6f, y + 14f, x + 6f, y + 14f, x, y + 25f)
        col(Pal.CORAL_PINK)
        shapes.rect(x + 2f, y + 18f, 2f, 3f)
        // Báculo: se alza durante el aviso.
        val raise = if (o.act == Obispo.Act.SWEEP_TELE) 6f else 0f
        val sx = x - 14f
        outlined(sx - 1f, y - 12f + raise, 3f, 30f, Pal.STONE)
        outlined(sx - 3f, y + 16f + raise, 7f, 4f, if (o.act == Obispo.Act.SWEEP_TELE && blink) Pal.LAMP_HI else Pal.CORAL_RED)
        if (o.act == Obispo.Act.SWEEP) {
            col(Pal.CREAM, 0.8f)
            val deg = o.sweepA * MathUtils.radiansToDegrees
            shapes.arc(x, y, Obispo.SWEEP_R, deg - 90f, 180f, 18)
        }
        if (o.stunned) {
            val t = w.time * 6f
            col(Pal.LAMP_HI)
            shapes.rect(x + cos(t) * 8f - 1f, y + 30f + sin(t) * 2f, 2f, 2f)
            shapes.rect(x - cos(t) * 8f - 1f, y + 30f - sin(t) * 2f, 2f, 2f)
        }
    }

    /** La Sirena de Brea: cola de brea, torso oscuro y melena de algas. Se hunde en la fase 3. */
    private fun drawSirena(w: World, s: Sirena, x: Float, y: Float, flash: Boolean, blink: Boolean) {
        if (s.submerged) {
            col(Pal.BREA, 0.8f)
            shapes.ellipse(x - 14f, y - 6f, 28f, 8f)
            return
        }
        val sink = if (s.act == Sirena.Act.DIVE) (1f - s.actT / s.actMax) * 14f else 0f
        val body = if (flash) Color.WHITE else Pal.NIGHT
        val tail = if (flash) Color.WHITE else Pal.BREA
        col(Pal.SHADOW)
        shapes.ellipse(x - 18f, y - 14f, 36f, 8f)
        // Cola
        outlined(x - 16f, y - 12f, 32f, 8f - min(8f, sink), tail)
        col(Pal.TIDE)
        shapes.rect(x - 14f, y - 10f, 4f, 1f)
        shapes.rect(x + 6f, y - 9f, 5f, 1f)
        // Aleta
        val fin = sin(s.anim * 4f) * 2f
        outlined(x + 14f, y - 10f + fin, 6f, 4f, tail)
        if (sink < 12f) {
            // Torso y cabeza
            outlined(x - 7f, y - 4f - sink, 14f, 14f, body)
            outlined(x - 5f, y + 10f - sink, 10f, 8f, if (flash) Color.WHITE else Pal.WALL_TOP)
            // Melena de algas
            col(Pal.MEDUSA_IN)
            for (i in 0 until 5) shapes.rect(x - 7f + i * 3f, y + 2f - sink + sin(s.anim * 3f + i) * 1.5f, 2f, 14f)
            // Ojos y boca: la boca se abre en los avisos.
            val warn = s.act == Sirena.Act.SING_TELE || s.act == Sirena.Act.AIM_TELE || s.act == Sirena.Act.ROAR
            col(if (warn && blink) Pal.CORAL else Pal.LAMP_HI)
            shapes.rect(x - 3f, y + 14f - sink, 2f, 1f)
            shapes.rect(x + 1f, y + 14f - sink, 2f, 1f)
            if (warn || s.act == Sirena.Act.SING) {
                col(Pal.CORAL)
                shapes.rect(x - 1f, y + 11f - sink, 2f, 2f)
            }
            if (s.act == Sirena.Act.AIM_TELE) {
                col(Pal.CORAL, 0.5f)
                shapes.rectLine(x, y, x + cos(s.aimA) * 60f, y + sin(s.aimA) * 60f, 1.5f)
            }
        }
    }

    private fun drawDebug(w: World) {
        val p = w.player
        col(Color.GREEN)
        shapes.rect(p.x - p.hw, p.y - p.hh, p.hw * 2f, p.hh * 2f)
        col(Color.RED)
        shapes.rect(p.cx - 5f, p.cy - 8f, 10f, 16f)
        for (e in w.enemies) {
            col(Color.YELLOW)
            shapes.circle(e.x, e.y, e.radius, 16)
        }
    }

    private fun drawLights(w: World, a: Float) {
        lightFb.begin()
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        batch.projectionMatrix = cam.combined
        batch.enableBlending()
        batch.setBlendFunction(GL20.GL_ONE, GL20.GL_ONE)
        batch.begin()

        val p = w.player
        val flicker = 1f + 0.025f * sin(w.time * 11f) + 0.015f * sin(w.time * 23f)
        val pr = if (w.gameOver) max(0f, 60f - w.modeT * 70f) else w.playerLight() * flicker
        light(p.ix(a), p.iy(a) + 5f, pr, 1f)
        for (e in w.enemies) {
            val r = e.lightRadius
            if (r > 0f) light(e.ix(a), e.iy(a), r, if (e is Medusa) 0.85f else 0.7f)
        }
        for (s in w.pending) light(s.x, s.y, 18f, 0.6f)
        for (o in w.orbs) light(o.x, o.y, 12f, 0.6f)
        for (pr2 in w.projectiles) light(pr2.x, pr2.y, 12f, 0.55f)
        for (f in w.fires) light(f.x, f.y, 16f, 0.5f)
        w.statue?.let { light(it.x, it.y + 5f, 22f, 0.6f) }
        if (w.stats.twinLamp && !w.gameOver) light(w.twinX, w.twinY, 34f, 0.7f)
        for (ped in w.cell.pedestals) {
            when (ped.kind) {
                PedKind.REFUGIO -> light(ped.x, ped.y + 18f, if (ped.taken) 40f else 70f, 0.9f)
                PedKind.FIN -> light(ped.x, ped.y + 12f, 80f, 1f)
                PedKind.ESCALERA -> light(ped.x, ped.y, 30f, 0.5f)
                PedKind.VIGILIA -> light(ped.x, ped.y + 22f, if (ped.taken) 46f else 84f, 1f)
                PedKind.DESCENSO -> light(ped.x, ped.y, 46f, 0.8f)
                PedKind.OFRENDA_MAX, PedKind.OFRENDA_MITAD -> if (!ped.taken) light(ped.x, ped.y + 8f, 34f, 0.8f)
                PedKind.JAULA -> light(ped.x, ped.y + 8f, if (ped.taken) 24f else 40f, 0.75f)
                PedKind.BOTELLA -> if (!ped.taken) light(ped.x, ped.y + 4f, 30f, 0.8f)
                PedKind.AMPARO -> light(ped.x, ped.y + 8f, 40f, 0.8f)
                else -> if (!ped.taken) light(ped.x, ped.y + 6f, 26f, 0.7f)
            }
        }
        val dark = (w.boss as? Cartografo)?.let { it.phase == 3 && !it.killed } == true
        if (p.hazT > 0f && !dark) {
            var t = 0f
            while (t <= p.hazLen) {
                light(p.cx + p.aimX * t, p.cy + p.aimY * t, 24f, 0.8f)
                t += 14f
            }
        }
        if (p.swingActive && p.weapon == Weapon.FAROL) light(p.cx + p.atkDx * 18f, p.cy + p.atkDy * 18f, 26f, 0.5f)
        p.harpoon?.let { light(it.x, it.y, 14f, 0.5f) }
        for (b in w.fx.blasts) light(b.x, b.y, b.r * 2.2f * (b.t / b.maxT), 1f)
        if (w.atrilesOn) for (a in w.atriles) light(a[0], a[1] + 4f, World.ATRIL_LIGHT, 0.85f)
        if (w.room.hasMolten) for (t in w.room.tilesOf(Room.MOLTEN)) {
            val c = w.room.tileCenter(t[0], t[1])
            light(c[0], c[1], 40f, 0.55f)
        }
        for (t in w.traps) light(t.x, t.y, 16f, 0.5f)
        for (c in w.candles) if (c.lit) {
            val fl = 1f + 0.04f * sin(w.time * 9f + c.x)
            light(c.x, c.y + 6f, World.CANDLE_LIGHT * fl * min(1f, 0.4f + c.litT * 3f), 0.95f)
        }
        (w.boss as? Obispo)?.let { o ->
            if (o.columnsOn && !o.killed) for (cx in o.colX) {
                var y = 8f
                while (y < w.room.height) { light(cx, y, 30f, 0.55f); y += 28f }
            }
        }

        batch.end()
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        batch.setColor(1f, 1f, 1f, 1f)
        lightFb.end()
    }

    private fun light(x: Float, y: Float, r: Float, i: Float) {
        if (r <= 0f) return
        batch.setColor(i, i, i, 1f)
        batch.draw(radial, x - r, y - r, r * 2f, r * 2f)
    }

    private fun composite() {
        val bw = Gdx.graphics.backBufferWidth
        val bh = Gdx.graphics.backBufferHeight
        Gdx.gl.glViewport(0, 0, bw, bh)
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        Gdx.gl.glViewport(ox, oy, iw * scale, ih * scale)

        batch.projectionMatrix = internalProj
        batch.disableBlending()
        if (lighting) {
            batch.shader = shader
            batch.begin()
            lightFb.colorBufferTexture.bind(1)
            bayer.bind(2)
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0)
            shader.setUniformi("u_light", 1)
            shader.setUniformi("u_bayer", 2)
            shader.setUniformf("u_res", iw.toFloat(), ih.toFloat())
            shader.setUniformf("u_shadow", 0.10f, 0.14f, 0.22f)
            batch.draw(sceneRegion, 0f, 0f, iw.toFloat(), ih.toFloat())
            batch.end()
            batch.shader = null
        } else {
            batch.begin()
            batch.draw(sceneRegion, 0f, 0f, iw.toFloat(), ih.toFloat())
            batch.end()
        }
        batch.enableBlending()
    }

    // ---------- HUD ----------

    /** HUD en coordenadas internas, sin iluminar. */
    private fun drawHud(w: World) {
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        val p = w.player
        shapes.projectionMatrix = internalProj
        shapes.begin(ShapeType.Filled)

        if (w.fadeT > 0f) {
            col(Color.BLACK, (w.fadeT / World.FADE).coerceIn(0f, 1f))
            shapes.rect(0f, 0f, iw.toFloat(), ih.toFloat())
        }
        if (w.chapter != null && w.chapterT > 0f) {
            val k = min(1f, w.chapterT / 0.8f) * min(1f, (World.CHAPTER_TIME - w.chapterT) / 0.6f)
            col(Pal.OUTLINE, 0.55f * k)
            shapes.rect(0f, ih * 0.6f, iw.toFloat(), ih * 0.2f)
        }
        if (w.memory != null && w.memoryT > 0f && w.mode == World.Mode.PLAYING) {
            val k = min(1f, w.memoryT / 1f) * min(1f, (World.MEMORY_TIME - w.memoryT) / 0.8f)
            // Arriba, para no tapar a Isla ni la recompensa del jefe.
            col(Pal.NIGHT, 0.88f * k)
            shapes.rect(40f, 138f, iw - 80f, 66f)
            col(Pal.LAMP_DIM, k)
            shapes.rect(40f, 203f, iw - 80f, 1f)
        }

        if (w.inSanctuary) {
            shapes.end()
            return
        }

        // Raíz: el latido tiñe los bordes; la Lámpara que bebe luz los pone rojos.
        if (w.biome == Biome.RAIZ) {
            val pulse = when {
                w.beatBoostT > 0f -> 0.22f * (w.beatBoostT / World.BEAT_BOOST_TIME)
                w.beatT < 1f -> 0.12f * (1f - w.beatT)
                else -> 0f
            }
            if (pulse > 0f) {
                col(Pal.HUNGER, pulse)
                shapes.rect(0f, 0f, iw.toFloat(), 10f); shapes.rect(0f, ih - 10f, iw.toFloat(), 10f)
                shapes.rect(0f, 0f, 10f, ih.toFloat()); shapes.rect(iw - 10f, 0f, 10f, ih.toFloat())
            }
        }
        val drinking = (w.boss as? LamparaMadre)?.drinking == true

        // Barra de Luz (1 px por punto de Luz máxima)
        val bx = 8f
        val by = ih - 14f
        col(Pal.OUTLINE)
        shapes.rect(bx - 1f, by - 1f, p.luzMax + 2f, 8f)
        col(Pal.NIGHT)
        shapes.rect(bx, by, p.luzMax, 6f)
        val redPulse = drinking && (w.time * 4f).toInt() % 2 == 0
        col(if (p.luz < Balance.LOW_LUZ || redPulse) Pal.CORAL else Pal.LAMP)
        shapes.rect(bx, by, max(0f, p.luz), 6f)
        col(Pal.LAMP_HI)
        shapes.rect(bx, by + 4f, max(0f, p.luz), 1f)
        col(Pal.CREAM, 0.8f)
        shapes.rect(bx + w.stats.hazCost, by, 1f, 6f)   // por debajo de la marca no hay Haz

        // Cargas de Destello
        val dashMax = Balance.DASH_COOLDOWN + Balance.DASH_TIME
        for (i in 0 until w.stats.dashCharges) {
            val full = i < p.dashCharges
            val k = if (full) 1f else if (i == p.dashCharges) 1f - (p.dashRecharge / dashMax).coerceIn(0f, 1f) else 0f
            col(Pal.OUTLINE)
            shapes.rect(bx - 1f + i * 24f, by - 7f, 22f, 4f)
            col(if (full) Pal.LAMP_HI else Pal.SEAM)
            shapes.rect(bx + i * 24f, by - 6f, 20f * k, 2f)
        }
        // Mejoras del arma
        for (i in 0 until w.weaponLevel) {
            col(Pal.CORAL)
            shapes.rect(bx + 52f + i * 5f, by - 7f, 3f, 4f)
        }
        // Exvotos: color de etiqueta con marco de rareza
        w.owned.forEachIndexed { i, e ->
            val ex = bx + (i % 16) * 9f
            val ey = by - 17f - (i / 16) * 9f
            col(e.rarity.color)
            shapes.rect(ex, ey, 7f, 7f)
            col(e.tag.color)
            shapes.rect(ex + 1f, ey + 1f, 5f, 5f)
        }
        drawMinimap(w)
        drawBossBar(w)

        if (w.mode != World.Mode.PLAYING) {
            val k = if (w.mode == World.Mode.DEAD) ((w.modeT - World.SLOWMO_TIME) * 0.8f).coerceIn(0f, 0.8f)
            else (w.modeT * 0.6f).coerceIn(0f, 0.7f)
            col(Pal.OUTLINE, k)
            shapes.rect(0f, 0f, iw.toFloat(), ih.toFloat())
            if (w.mode == World.Mode.DEAD && w.modeT > World.SLOWMO_TIME) {
                // Tarjeta de la muerte
                col(Pal.NIGHT, 0.9f)
                shapes.rect(70f, 70f, iw - 140f, 130f)
                col(Pal.CORAL)
                shapes.rect(70f, 198f, iw - 140f, 2f)
            }
        }
        shapes.end()
    }

    private fun drawMinimap(w: World) {
        val cw = 7f
        val ch = 5f
        val x0 = iw - 8f - FloorMap.GW * (cw + 1f)
        val y0 = ih - 20f - FloorMap.GH * (ch + 1f)
        col(Pal.OUTLINE, 0.6f)
        shapes.rect(x0 - 2f, y0 - 2f, FloorMap.GW * (cw + 1f) + 3f, FloorMap.GH * (ch + 1f) + 3f)
        val blink = (w.time * 3f).toInt() % 2 == 0
        for (c in w.map.all) {
            if (!c.known) continue
            val x = x0 + c.gx * (cw + 1f)
            val y = y0 + c.gy * (ch + 1f)
            val current = c === w.cell || c === w.cell.partner
            // Salas grandes: une las dos celdas.
            val partner = c.partner
            if (partner != null && c.anchor && partner.known) {
                col(if (current) Pal.LAMP else if (c.visited) Pal.WALL_TOP else Pal.WALL)
                if (partner.gx > c.gx) shapes.rect(x + cw, y, 1f, ch) else shapes.rect(x, y + ch, cw, 1f)
            }
            col(
                when {
                    current -> if (blink) Pal.LAMP else Pal.LAMP_HI
                    c.visited -> Pal.WALL_TOP
                    else -> Pal.WALL
                }
            )
            shapes.rect(x, y, cw, ch)
            if ((c.visited || w.mapRevealed || c.iconShown) && !current) {
                val icon = when (c.type) {
                    CellType.SHOP -> Pal.LAMP
                    CellType.TREASURE -> if (c.pedestals.all { it.taken } && c.stocked) null else Pal.LAMP_HI
                    CellType.REFUGE -> Pal.CREAM
                    CellType.STAIRS -> Pal.FOAM
                    CellType.BOSS -> Pal.CORAL
                    CellType.ELITE -> if (c.cleared) null else Pal.HAMBRIENTO
                    CellType.SECRET -> Pal.MEDUSA
                    CellType.ANTESALA -> Pal.LAMP_DIM
                    CellType.CAPILLA -> if (c.pedestals.any { !it.taken } || !c.stocked) Pal.LILAC else null
                    CellType.CELDA -> if (c.pedestals.any { !it.taken } || !c.stocked) Pal.SKIN else null
                    CellType.LECTURA -> if (c.cleared) null else Pal.PAPER
                    CellType.CORAZON -> if (c.cleared) null else Pal.ROOT_HI
                    else -> null
                }
                if (icon != null) {
                    col(icon)
                    shapes.rect(x + 2f, y + 1f, 3f, 3f)
                }
            }
        }
    }

    private fun drawBossBar(w: World) {
        val b = w.boss ?: return
        if (b.killed || !b.introduced) return
        val bwid = 240f
        val x = (iw - bwid) / 2f
        val y = 12f
        col(Pal.OUTLINE)
        shapes.rect(x - 1f, y - 1f, bwid + 2f, 6f)
        col(Pal.NIGHT)
        shapes.rect(x, y, bwid, 4f)
        col(Pal.CORAL)
        shapes.rect(x, y, bwid * (b.hp / b.maxHp), 4f)
        // Marcas de fase
        col(Pal.CREAM)
        val marks = if (b is Sirena) floatArrayOf(0.6f, 0.25f) else b.phaseMarks
        for (m in marks) shapes.rect(x + bwid * m, y, 1f, 4f)
    }

    private fun drawScreenSpace(w: World, c: Controls) {
        val bw = Gdx.graphics.backBufferWidth
        val bh = Gdx.graphics.backBufferHeight
        Gdx.gl.glViewport(0, 0, bw, bh)
        screenProj.setToOrtho2D(0f, 0f, bw.toFloat(), bh.toFloat())
        if (c.showTouchUi && w.mode == World.Mode.PLAYING && menu?.open != true) drawTouch(c)

        batch.projectionMatrix = screenProj
        font.data.setScale(max(1, scale / 2).toFloat())
        batch.begin()
        val p = w.player
        if (w.inSanctuary) {
            text("PERLAS ${Meta.perlas}", 8f, ih - 7f, Pal.SHELL)
            text("ARMA: ${p.weapon.label.uppercase()}", iw - 8f, ih - 7f, Pal.CREAM, align = 1)
            if (!c.showTouchUi) text("${statusKey(c)}: ESTADO", 8f, ih - 17f, Pal.LAMP_DIM)
        } else {
            text("LUZ ${p.luz.roundToInt()}/${p.luzMax.roundToInt()}", 8f + p.luzMax + 6f, ih - 7f, Pal.LAMP_HI)
            val mtag = if (w.baja) "   M. BAJA" else if (w.marea > 0) "   M${w.marea}" else ""
            text("PISO ${w.floorNum}   BAJAS ${w.kills}   ${p.weapon.label.uppercase()}$mtag", iw - 8f, ih - 7f, Pal.CREAM, align = 1)
            if (w.test) text("MODO PRUEBA", iw - 8f, 196f, Pal.CORAL, align = 1)
            // Recordatorio al empezar la run (en táctil se ve el botón "i").
            if (!c.showTouchUi && w.time < 20f && w.mode == World.Mode.PLAYING) text("${statusKey(c)}: ESTADO", 8f, 10f, Pal.LAMP_DIM)
        }
        for (f in w.fx.floaters) {
            text(f.text, f.x - camLeft, f.y - camBottom, f.color, alpha = (f.t / 0.9f).coerceIn(0f, 1f), align = 0)
        }
        if (w.bannerT > 0f && !(w.mode == World.Mode.DEAD && w.modeT > World.SLOWMO_TIME)) {
            text(w.banner, iw / 2f, ih * 0.80f, Pal.LAMP, alpha = w.bannerT.coerceIn(0f, 1f), align = 0)
        }
        w.boss?.let { b ->
            if (!b.killed && b.introduced) text(b.def.name.uppercase(), iw / 2f, 30f, Pal.CREAM, align = 0)
        }
        // Título de capítulo al empezar un bioma.
        w.chapter?.let { (title, line) ->
            if (w.chapterT > 0f) {
                val k = min(1f, w.chapterT / 0.8f) * min(1f, (World.CHAPTER_TIME - w.chapterT) / 0.6f)
                text(title, iw / 2f, ih * 0.74f, Pal.LAMP, alpha = k, align = 0)
                text(line, iw / 2f, ih * 0.66f, Pal.CREAM, alpha = k, align = 0, wrap = iw * 0.8f)
            }
        }
        // Recuerdo del jefe (solo la primera vez que cae).
        w.memory?.let { m ->
            if (w.memoryT > 0f && w.mode == World.Mode.PLAYING) {
                val k = min(1f, w.memoryT / 1f) * min(1f, (World.MEMORY_TIME - w.memoryT) / 0.8f)
                text(m.title, iw / 2f, 194f, Pal.LAMP_HI, alpha = k, align = 0)
                text(m.text, iw / 2f, 180f, Pal.CREAM, alpha = k, align = 0, wrap = iw * 0.78f)
            }
        }
        val it = w.interactable
        if (it != null && w.mode == World.Mode.PLAYING) {
            val key = keyLabel(c, Action.USE)
            val price = when {
                it.kind == PedKind.META && it.taken -> "  ·  ya es tuyo"
                it.price > 0 && w.inSanctuary -> "  ·  ${it.price} Perlas (tienes ${Meta.perlas})"
                it.price > 0 -> "  ·  ${it.price} de Luz"
                it.kind == PedKind.EXVOTO -> "  ·  gratis"
                else -> ""
            }
            text("${it.title}$price   [$key]", iw / 2f, 46f, Pal.LAMP_HI, align = 0)
            text(it.detail, iw / 2f, 36f, Pal.CREAM, align = 0, wrap = iw * 0.8f)
        }
        when (w.mode) {
            World.Mode.DEAD -> if (w.modeT > World.SLOWMO_TIME) {
                val d = w.death
                if (d != null) {
                    val mm = (d.time / 60f).toInt()
                    val ss = (d.time % 60f).toInt()
                    text(if (d.cause.startsWith("Abandonaste")) "RUN ABANDONADA" else "TE APAGÓ", iw / 2f, 190f, Pal.CORAL, align = 0)
                    text(d.cause, iw / 2f, 174f, Pal.LAMP_HI, align = 0)
                    text("Piso ${d.floor}  ·  sala ${d.roomType}  ·  entraste con ${d.entryLuz} de Luz", iw / 2f, 152f, Pal.CREAM, align = 0)
                    text((if (d.enemiesLeft == 1) "Quedaba 1 enemigo" else "Quedaban ${d.enemiesLeft} enemigos") + "  ·  ${mm}:${ss.toString().padStart(2, '0')}  ·  ${w.kills} bajas  ·  ${w.owned.size} exvotos", iw / 2f, 136f, Pal.CREAM, align = 0)
                    text("Perlas de esta run: ${w.perlasRun}   (total ${Meta.perlas})", iw / 2f, 114f, Pal.SHELL, align = 0)
                    text("${Mareas.label(w.marea, w.baja)}  ·  semilla ${w.seedCode}", iw / 2f, 100f, Pal.TIDE, align = 0)
                }
                if (w.modeT > 1.8f) text(anyKeyText(c, "volver al Santuario"), iw / 2f, 88f, Pal.LAMP_HI, align = 0)
            }
            World.Mode.VICTORY -> {
                val mm = (w.time / 60f).toInt()
                val ss = (w.time % 60f).toInt()
                val ending = if (w.biome == Biome.RAIZ) (if (w.finalC) Story.FINAL_C else if (Meta.allBottles) Story.FINAL_B else Story.FINAL_A) else null
                text(ending?.first ?: "${w.biome.label} COMPLETADA", iw / 2f, ih * 0.80f, Pal.LAMP, align = 0)
                ending?.let { text(it.second, iw / 2f, ih * 0.72f, Pal.CREAM, align = 0, wrap = iw * 0.8f) }
                text("Tiempo ${mm}:${ss.toString().padStart(2, '0')}  ·  ${w.kills} bajas  ·  ${w.owned.size} exvotos", iw / 2f, ih * 0.52f, Pal.CREAM, align = 0)
                text("Perlas +${w.perlasRun}   (total ${Meta.perlas})   ·   ${Mareas.label(w.marea, w.baja)}   ·   semilla ${w.seedCode}", iw / 2f, ih * 0.45f, Pal.SHELL, align = 0)
                text(if (Meta.allBottles) Story.VICTORIA_FINAL else Story.VICTORIA_FINAL + "  Botellas: ${Meta.bottles.size}/${Bottles.all.size}", iw / 2f, ih * 0.37f, Pal.CREAM, align = 0, wrap = iw * 0.85f)
                if (w.modeT > 1.8f) text(anyKeyText(c, "volver al Santuario"), iw / 2f, ih * 0.25f, Pal.LAMP_HI, align = 0)
            }
            else -> {}
        }
        if (c.showTouchUi && w.mode == World.Mode.PLAYING && menu?.open != true) {
            val pb = c.pauseButton()
            glyphs.setText(font, pb.label)
            font.color = Pal.CREAM
            font.draw(batch, pb.label, pb.x - glyphs.width / 2f, pb.y + glyphs.height / 2f)
            val sb = c.statusButton()
            glyphs.setText(font, sb.label)
            font.color = Pal.LAMP_HI
            font.draw(batch, sb.label, sb.x - glyphs.width / 2f, sb.y + glyphs.height / 2f)
            val btns = c.buttons()
            for (i in btns.indices) {
                if (i == 3 && !c.useVisible) continue
                val b = btns[i]
                glyphs.setText(font, b.label)
                font.color = Pal.CREAM
                font.draw(batch, b.label, b.x - glyphs.width / 2f, b.y + glyphs.height / 2f)
            }
        }
        if (debug) {
            font.color = Color.GREEN
            val cell = w.cell
            font.draw(
                batch,
                "FPS ${Gdx.graphics.framesPerSecond}  x$scale  sala ${cell.type.label} ${w.room.template.name}  luz:${if (lighting) "on" else "off"}",
                8f, 24f * font.data.scaleY,
            )
        }
        batch.end()
        font.color = Color.WHITE
    }

    /** align: -1 izquierda, 0 centro, 1 derecha. Coordenadas internas convertidas a pantalla. */
    private fun text(s: String, ix: Float, iy: Float, color: Color, alpha: Float = 1f, align: Int = -1, wrap: Float = 0f) {
        val x0 = ox + ix * scale
        val y = oy + iy * scale
        if (wrap > 0f) {
            val wpx = wrap * scale
            glyphs.setText(font, s, color, wpx, Align.center, true)
            font.setColor(Pal.OUTLINE.r, Pal.OUTLINE.g, Pal.OUTLINE.b, alpha)
            font.draw(batch, s, x0 - wpx / 2f + scale / 2f, y - scale / 2f, wpx, Align.center, true)
            font.setColor(color.r, color.g, color.b, alpha)
            font.draw(batch, s, x0 - wpx / 2f, y, wpx, Align.center, true)
            return
        }
        glyphs.setText(font, s)
        var x = x0
        if (align == 0) x -= glyphs.width / 2f else if (align == 1) x -= glyphs.width
        font.setColor(Pal.OUTLINE.r, Pal.OUTLINE.g, Pal.OUTLINE.b, alpha)
        font.draw(batch, s, x + scale / 2f, y - scale / 2f)
        font.setColor(color.r, color.g, color.b, alpha)
        font.draw(batch, s, x, y)
    }

    private fun drawTouch(c: Controls) {
        Gdx.gl.glEnable(GL20.GL_BLEND)
        shapes.projectionMatrix = screenProj
        shapes.begin(ShapeType.Filled)
        val btns = c.buttons()
        for (i in btns.indices) {
            if (i == 3 && !c.useVisible) continue
            val b = btns[i]
            val base = when (i) { 0 -> Pal.LAMP; 1 -> Pal.LAMP_HI; 3 -> Pal.FOAM; 4 -> Pal.TIDE; else -> Pal.CREAM }
            col(base, if (c.isPressed(i)) 0.55f else 0.22f)
            shapes.circle(b.x, b.y, b.r, 40)
        }
        val pb = c.pauseButton()
        col(Pal.CREAM, 0.18f)
        shapes.circle(pb.x, pb.y, pb.r, 30)
        // El Estado tiene su propio botón, con el color del farol para que se note.
        val sb = c.statusButton()
        col(Pal.LAMP, 0.30f)
        shapes.circle(sb.x, sb.y, sb.r, 30)
        val jr = c.joyRadius
        if (c.joyPtr != -1) {
            col(Pal.CREAM, 0.12f)
            shapes.circle(c.joyOx, c.joyOy, jr, 40)
            var dx = c.joyX - c.joyOx
            var dy = c.joyY - c.joyOy
            val d = sqrt(dx * dx + dy * dy)
            if (d > jr) { dx = dx / d * jr; dy = dy / d * jr }
            col(Pal.CREAM, 0.35f)
            shapes.circle(c.joyOx + dx, c.joyOy + dy, jr * 0.42f, 30)
        } else {
            val bh = Gdx.graphics.backBufferHeight.toFloat()
            col(Pal.CREAM, 0.06f)
            shapes.circle(0.22f * bh, 0.25f * bh, jr, 40)
        }
        shapes.end()
    }

    // ---------- utilidades ----------

    private fun col(c: Color, alpha: Float = c.a) = shapes.setColor(c.r, c.g, c.b, alpha)

    private fun outlined(x: Float, y: Float, w: Float, h: Float, c: Color) {
        col(Pal.OUTLINE)
        shapes.rect(x - 1f, y - 1f, w + 2f, h + 2f)
        col(c)
        shapes.rect(x, y, w, h)
    }

    override fun dispose() {
        scene.dispose()
        lightFb.dispose()
        shapes.dispose()
        batch.dispose()
        shader.dispose()
        bayer.dispose()
        radial.dispose()
        font.dispose()
    }
}
