package cl.xiombo.faro.render

import cl.xiombo.faro.data.Balance
import cl.xiombo.faro.data.Pal
import cl.xiombo.faro.data.Tag
import cl.xiombo.faro.input.Controls
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
        val sx = if (w.shake > 0f) MathUtils.random(-w.shake, w.shake) else 0f
        val sy = if (w.shake > 0f) MathUtils.random(-w.shake, w.shake) else 0f
        cam.position.set(camLeft + iw / 2f + sx.roundToInt(), camBottom + ih / 2f + sy.roundToInt(), 0f)
        cam.update()
    }

    // ---------- fotograma ----------

    fun render(w: World, alpha: Float, controls: Controls) {
        updateLayout()
        placeCamera(w, alpha)
        drawScene(w, alpha)
        drawLights(w, alpha)
        composite()
        drawHud(w)
        drawScreenSpace(w, controls)
    }

    private fun drawScene(w: World, a: Float) {
        scene.begin()
        Gdx.gl.glClearColor(Pal.VOID.r, Pal.VOID.g, Pal.VOID.b, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)

        shapes.projectionMatrix = cam.combined
        shapes.begin(ShapeType.Filled)
        drawRoom(w)
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
        for (p in w.cell.pedestals) if (!p.taken || p.kind == PedKind.REFUGIO) drawPedestal(w, p)
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
                    col(Pal.WALL)
                    shapes.rect(x, y, t, t)
                    if (!r.isSolidTile(tx, ty + 1) || ty == r.rows - 1) {
                        col(Pal.WALL_TOP)
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
                        col(Pal.WALL_TOP, 0.6f)
                        shapes.rect(x + 10f, y + 4f, 1f, 2f)
                    }
                }
                Room.WATER -> {
                    col(Pal.WATER)
                    shapes.rect(x, y, t, t)
                    val ph = ((w.time * 2f + tx * 0.7f + ty * 1.3f).toInt() % 4)
                    col(Pal.TIDE)
                    shapes.rect(x + 2f + ph, y + 5f, 5f, 1f)
                    shapes.rect(x + 9f - ph, y + 11f, 4f, 1f)
                    // Borde de tablón sobre el agua (se lee el límite del pozo).
                    if (!r.isWater(tx, ty + 1) && r.tileAt(tx, ty + 1) == Room.FLOOR) {
                        col(Pal.SEAM)
                        shapes.rect(x, y + t - 2f, t, 2f)
                    }
                }
                Room.DOOR -> {
                    drawPlank(tx, ty, x, y, t)
                    if (!r.doorsOpen) {
                        col(Pal.SEAM)
                        shapes.rect(x + 1f, y + 1f, t - 2f, t - 2f)
                        col(Pal.DOOR)
                        val vertical = tx == 0 || tx == r.cols - 1
                        for (i in 0 until 3) {
                            if (vertical) shapes.rect(x + 3f + i * 4f, y, 2f, t) else shapes.rect(x, y + 3f + i * 4f, t, 2f)
                        }
                    }
                }
                else -> drawPlank(tx, ty, x, y, t)
            }
        }
    }

    /** Tablones del muelle: dos tablas por tile con juntas desfasadas. */
    private fun drawPlank(tx: Int, ty: Int, x: Float, y: Float, t: Float) {
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
            val body = if (p.dashT > 0f) Pal.LAMP_HI else Pal.COAT
            outlined(x - 5f, y - 3f, 10f, 12f, body)
            outlined(x - 4f, y + 9f, 8f, 7f, Pal.SKIN)
            col(Pal.HAIR)
            shapes.rect(x - 4f, y + 13f, 8f, 3f)
            col(Pal.OUTLINE)
            if (p.aimX >= 0f) shapes.rect(x + 1f, y + 11f, 1f, 1f) else shapes.rect(x - 2f, y + 11f, 1f, 1f)
        }
        // Farol (siempre visible: es la vida)
        val lx = (x + p.aimX * 8f).roundToInt().toFloat()
        val ly = (y + 5f + p.aimY * 5f).roundToInt().toFloat()
        outlined(lx - 2f, ly - 2f, 4f, 4f, if (w.gameOver) Pal.SEAM else Pal.LAMP)
    }

    private fun drawAttacks(p: Player) {
        if (p.swingActive || p.swingSmear) {
            val ang = atan2(p.atkDy, p.atkDx) * MathUtils.radiansToDegrees
            if (p.swingActive) col(Pal.LAMP_HI, 0.85f) else col(Pal.LAMP_DIM, 0.45f)
            shapes.arc(p.cx, p.cy, p.swingRange, ang - Balance.FAROL_HALF_ANGLE_DEG, Balance.FAROL_HALF_ANGLE_DEG * 2f, 10)
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
        val flash = e.flash > 0f
        val tele = e.state == EState.TELEGRAPH
        val blink = (w.time * 16f).toInt() % 2 == 0

        if (e is Sirena) { drawSirena(w, e, x, y, flash, blink); return }

        col(Pal.SHADOW)
        shapes.ellipse(x - e.hw, y - e.hh - 2f, e.hw * 2f, 5f)
        // Élite: aura del color del afijo.
        val affix = e.affix
        if (affix != null && !e.killed) {
            col(affix.color, if (blink) 0.9f else 0.55f)
            shapes.rect(x - e.hw - 3f, y - e.hh - 3f, e.hw * 2f + 6f, e.hh * 2f + 6f)
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
                else -> if (!ped.taken) light(ped.x, ped.y + 6f, 26f, 0.7f)
            }
        }
        if (p.hazT > 0f) {
            var t = 0f
            while (t <= p.hazLen) {
                light(p.cx + p.aimX * t, p.cy + p.aimY * t, 24f, 0.8f)
                t += 14f
            }
        }
        if (p.swingActive) light(p.cx + p.atkDx * 18f, p.cy + p.atkDy * 18f, 26f, 0.5f)
        for (b in w.fx.blasts) light(b.x, b.y, b.r * 2.2f * (b.t / b.maxT), 1f)

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

        // Barra de Luz (1 px por punto de Luz máxima)
        val bx = 8f
        val by = ih - 14f
        col(Pal.OUTLINE)
        shapes.rect(bx - 1f, by - 1f, p.luzMax + 2f, 8f)
        col(Pal.NIGHT)
        shapes.rect(bx, by, p.luzMax, 6f)
        col(if (p.luz < Balance.LOW_LUZ) Pal.CORAL else Pal.LAMP)
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
        // Mejoras del Farol
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
            col(Pal.OUTLINE, (w.modeT * 0.6f).coerceIn(0f, 0.7f))
            shapes.rect(0f, 0f, iw.toFloat(), ih.toFloat())
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
            val current = c === w.cell
            col(
                when {
                    current -> if (blink) Pal.LAMP else Pal.LAMP_HI
                    c.visited -> Pal.WALL_TOP
                    else -> Pal.WALL
                }
            )
            shapes.rect(x, y, cw, ch)
            if ((c.visited || w.mapRevealed) && !current) {
                val icon = when (c.type) {
                    CellType.SHOP -> Pal.LAMP
                    CellType.TREASURE -> if (c.pedestals.all { it.taken } && c.stocked) null else Pal.LAMP_HI
                    CellType.REFUGE -> Pal.CREAM
                    CellType.STAIRS -> Pal.FOAM
                    CellType.BOSS -> Pal.CORAL
                    CellType.ELITE -> if (c.cleared) null else Pal.HAMBRIENTO
                    CellType.SECRET -> Pal.MEDUSA
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
        if (b.killed || b.act == Sirena.Act.INTRO && b.actT > 1.2f) return
        val bwid = 240f
        val x = (iw - bwid) / 2f
        val y = 12f
        col(Pal.OUTLINE)
        shapes.rect(x - 1f, y - 1f, bwid + 2f, 6f)
        col(Pal.NIGHT)
        shapes.rect(x, y, bwid, 4f)
        col(Pal.CORAL)
        shapes.rect(x, y, bwid * (b.hp / b.maxHp), 4f)
        // Marcas de fase: 60 % y 25 %
        col(Pal.CREAM)
        shapes.rect(x + bwid * 0.6f, y, 1f, 4f)
        shapes.rect(x + bwid * 0.25f, y, 1f, 4f)
    }

    private fun drawScreenSpace(w: World, c: Controls) {
        val bw = Gdx.graphics.backBufferWidth
        val bh = Gdx.graphics.backBufferHeight
        Gdx.gl.glViewport(0, 0, bw, bh)
        screenProj.setToOrtho2D(0f, 0f, bw.toFloat(), bh.toFloat())
        if (c.touch && w.mode == World.Mode.PLAYING) drawTouch(c)

        batch.projectionMatrix = screenProj
        font.data.setScale(max(1, scale / 2).toFloat())
        batch.begin()
        val p = w.player
        text("LUZ ${p.luz.roundToInt()}/${p.luzMax.roundToInt()}", 8f + p.luzMax + 6f, ih - 7f, Pal.LAMP_HI)
        text("PISO ${w.floorNum}   BAJAS ${w.kills}", iw - 8f, ih - 7f, Pal.CREAM, align = 1)
        for (f in w.fx.floaters) {
            text(f.text, f.x - camLeft, f.y - camBottom, f.color, alpha = (f.t / 0.9f).coerceIn(0f, 1f), align = 0)
        }
        if (w.bannerT > 0f) {
            text(w.banner, iw / 2f, ih * 0.80f, Pal.LAMP, alpha = w.bannerT.coerceIn(0f, 1f), align = 0)
        }
        w.boss?.let { b ->
            if (!b.killed) text("LA SIRENA DE BREA", iw / 2f, 30f, Pal.CREAM, align = 0)
        }
        val it = w.interactable
        if (it != null && w.mode == World.Mode.PLAYING) {
            val key = if (c.touch) "USAR" else "E"
            val price = when {
                it.price > 0 -> "  ·  ${it.price} de Luz"
                it.kind == PedKind.EXVOTO -> "  ·  gratis"
                else -> ""
            }
            text("${it.title}$price   [$key]", iw / 2f, 46f, Pal.LAMP_HI, align = 0)
            text(it.detail, iw / 2f, 36f, Pal.CREAM, align = 0, wrap = iw * 0.8f)
        }
        when (w.mode) {
            World.Mode.DEAD -> if (w.modeT > 1.2f) {
                text("Piso ${w.floorNum}  ·  ${w.kills} bajas  ·  ${w.owned.size} exvotos", iw / 2f, ih * 0.58f, Pal.CREAM, align = 0)
                text(if (c.touch) "Toca para volver a bajar" else "Pulsa una tecla para volver a bajar", iw / 2f, ih * 0.46f, Pal.LAMP_HI, align = 0)
            }
            World.Mode.VICTORY -> {
                val mm = (w.time / 60f).toInt()
                val ss = (w.time % 60f).toInt()
                text("MUELLE AHOGADO COMPLETADO", iw / 2f, ih * 0.74f, Pal.LAMP, align = 0)
                text("Tiempo ${mm}:${ss.toString().padStart(2, '0')}  ·  ${w.kills} bajas  ·  ${w.owned.size} exvotos", iw / 2f, ih * 0.62f, Pal.CREAM, align = 0)
                text("Perlas +${w.perlasRun}   (total ${w.perlasTotal})", iw / 2f, ih * 0.54f, Pal.SHELL, align = 0)
                text("Fin de la F1. El Santuario y la Catedral llegan en la F2.", iw / 2f, ih * 0.42f, Pal.CREAM, align = 0)
                if (w.modeT > 1.5f) text(if (c.touch) "Toca para una nueva run" else "Pulsa una tecla para una nueva run", iw / 2f, ih * 0.32f, Pal.LAMP_HI, align = 0)
            }
            else -> {}
        }
        if (c.touch && w.mode == World.Mode.PLAYING) {
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
            val base = when (i) { 0 -> Pal.LAMP; 1 -> Pal.LAMP_HI; 3 -> Pal.FOAM; else -> Pal.CREAM }
            col(base, if (c.isPressed(i)) 0.55f else 0.22f)
            shapes.circle(b.x, b.y, b.r, 40)
        }
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
