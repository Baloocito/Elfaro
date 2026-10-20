package cl.xiombo.faro.render

import cl.xiombo.faro.data.Balance
import cl.xiombo.faro.data.Pal
import cl.xiombo.faro.input.Controls
import cl.xiombo.faro.world.Ahogado
import cl.xiombo.faro.world.Body
import cl.xiombo.faro.world.Cangrejo
import cl.xiombo.faro.world.EState
import cl.xiombo.faro.world.Enemy
import cl.xiombo.faro.world.Medusa
import cl.xiombo.faro.world.Player
import cl.xiombo.faro.world.Room
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
 * F0 usa formas de colores en lugar de sprites, como pide la hoja de ruta.
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
    private val night = Pal.NIGHT

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
        camLeft = Room.WIDTH / 2f - iw / 2f
        camBottom = Room.HEIGHT / 2f - ih / 2f
    }

    fun screenToWorld(sx: Float, sy: Float, out: Vector2): Vector2 {
        val bh = Gdx.graphics.backBufferHeight
        val ix = (sx - ox) / scale
        val iy = (bh - sy - oy) / scale
        return out.set(ix + camLeft, iy + camBottom)
    }

    // ---------- fotograma ----------

    fun render(w: World, alpha: Float, controls: Controls) {
        updateLayout()
        val sx = if (w.shake > 0f) MathUtils.random(-w.shake, w.shake) else 0f
        val sy = if (w.shake > 0f) MathUtils.random(-w.shake, w.shake) else 0f
        // Posición lógica en float, dibujo redondeado a píxel entero.
        cam.position.set((Room.WIDTH / 2f + sx).roundToInt().toFloat(), (Room.HEIGHT / 2f + sy).roundToInt().toFloat(), 0f)
        cam.update()

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
        for (s in w.pending) {
            val pulse = ((w.time * 10f).toInt() % 2 == 0)
            col(if (pulse) Pal.CORAL else Pal.WATER, 0.8f)
            shapes.rect(s.x - 3f, s.y - 3f, 6f, 6f)
        }
        for (o in w.orbs) {
            col(Pal.LAMP_HI)
            shapes.rect(o.x.roundToInt() - 1f, o.y.roundToInt() - 1f, 3f, 3f)
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

        for (p in w.fx.parts) {
            col(p.color, (p.life / p.maxLife).coerceIn(0f, 1f) * if (p.w > 4f) 0.45f else 1f)
            shapes.rect(p.x.roundToInt().toFloat(), p.y.roundToInt().toFloat(), p.w, p.h)
        }
        shapes.end()

        shapes.begin(ShapeType.Line)
        for (s in w.pending) {
            col(Pal.CORAL, 0.7f)
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
        for (ty in 0 until Room.ROWS) for (tx in 0 until Room.COLS) {
            val x = tx * t
            val y = ty * t
            if (r.isSolidTile(tx, ty)) {
                col(Pal.WALL)
                shapes.rect(x, y, t, t)
                if (!r.isSolidTile(tx, ty + 1) || ty == Room.ROWS - 1) {
                    col(Pal.WALL_TOP)
                    shapes.rect(x, y + t - 3f, t, 3f)
                }
                if (ty > 0 && !r.isSolidTile(tx, ty - 1)) {
                    col(Pal.OUTLINE)
                    shapes.rect(x, y, t, 2f)
                }
            } else {
                // Tablones del muelle: dos tablas por tile con juntas desfasadas.
                col(if ((tx + ty) % 2 == 0) Pal.PLANK else Pal.PLANK2)
                shapes.rect(x, y, t, t)
                col(Pal.SEAM)
                shapes.rect(x, y, t, 1f)
                shapes.rect(x, y + 8f, t, 1f)
                shapes.rect(x + if (ty % 2 == 0) 5f else 11f, y, 1f, 8f)
                shapes.rect(x + if (ty % 2 == 0) 13f else 2f, y + 8f, 1f, 8f)
            }
            if (r.isEntrance(tx, ty) || (r.isDoor(tx, ty) && !r.doorsOpen)) {
                col(Pal.SEAM)
                shapes.rect(x + 2f, y, t - 4f, t)
                col(Pal.DOOR)
                for (i in 0 until 3) shapes.rect(x + 3f + i * 4f, y, 2f, t)
            }
        }
        if (r.doorsOpen && (w.time * 3f).toInt() % 2 == 0) {
            // Flecha que indica la salida.
            val dx = (Room.COLS - 2) * t + 4f
            val dy = Room.DOOR_ROWS.first * t + t - 3f
            col(Pal.LAMP_HI, 0.9f)
            shapes.triangle(dx, dy - 5f, dx, dy + 5f, dx + 7f, dy)
        }
    }

    private fun drawPlayer(w: World, p: Player, a: Float) {
        val x = p.ix(a).roundToInt().toFloat()
        val y = p.iy(a).roundToInt().toFloat()
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
        col(Pal.SHADOW)
        shapes.ellipse(x - e.hw, y - e.hh - 2f, e.hw * 2f, 5f)

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
        if (!e.killed && e.hp < e.maxHp) {
            col(Pal.OUTLINE)
            shapes.rect(x - 8f, y + e.hh + 5f, 16f, 3f)
            col(Pal.CORAL)
            shapes.rect(x - 7f, y + e.hh + 6f, 14f * (e.hp / e.maxHp), 1f)
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
        val pr = if (w.gameOver) max(0f, 60f - w.gameOverT * 70f) else p.lightRadius * flicker
        light(p.ix(a), p.iy(a) + 5f, pr, 1f)
        for (e in w.enemies) {
            val r = e.lightRadius
            if (r > 0f) light(e.ix(a), e.iy(a), r, if (e is Medusa) 0.85f else 0.7f)
        }
        for (s in w.pending) light(s.x, s.y, 18f, 0.6f)
        for (o in w.orbs) light(o.x, o.y, 12f, 0.6f)
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

    /** HUD en coordenadas internas, sin iluminar. */
    private fun drawHud(w: World) {
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        val p = w.player
        shapes.projectionMatrix = internalProj
        shapes.begin(ShapeType.Filled)
        val bx = 8f
        val by = ih - 14f
        col(Pal.OUTLINE)
        shapes.rect(bx - 1f, by - 1f, p.luzMax + 2f, 8f)
        col(night)
        shapes.rect(bx, by, p.luzMax, 6f)
        col(if (p.luz < Balance.LOW_LUZ) Pal.CORAL else Pal.LAMP)
        shapes.rect(bx, by, p.luz, 6f)
        col(Pal.LAMP_HI)
        shapes.rect(bx, by + 4f, p.luz, 1f)
        // Marca del coste del Haz: por debajo no se puede disparar.
        col(Pal.CREAM, 0.8f)
        shapes.rect(bx + Balance.HAZ_COST, by, 1f, 6f)
        // Recarga del Destello
        val dashMax = Balance.DASH_COOLDOWN + Balance.DASH_TIME
        val k = 1f - (p.dashCd / dashMax).coerceIn(0f, 1f)
        col(Pal.OUTLINE)
        shapes.rect(bx - 1f, by - 7f, 22f, 4f)
        col(if (k >= 1f) Pal.LAMP_HI else Pal.SEAM)
        shapes.rect(bx, by - 6f, 20f * k, 2f)
        if (w.gameOver) {
            col(Pal.OUTLINE, (w.gameOverT * 0.6f).coerceIn(0f, 0.6f))
            shapes.rect(0f, 0f, iw.toFloat(), ih.toFloat())
        }
        shapes.end()
    }

    private fun drawScreenSpace(w: World, c: Controls) {
        val bw = Gdx.graphics.backBufferWidth
        val bh = Gdx.graphics.backBufferHeight
        Gdx.gl.glViewport(0, 0, bw, bh)
        screenProj.setToOrtho2D(0f, 0f, bw.toFloat(), bh.toFloat())
        if (c.touch) drawTouch(c)

        batch.projectionMatrix = screenProj
        font.data.setScale(max(1, scale / 2).toFloat())
        batch.begin()
        val p = w.player
        text("LUZ ${p.luz.roundToInt()}/${p.luzMax.roundToInt()}", 8f + p.luzMax + 6f, ih - 7f, Pal.LAMP_HI)
        text("SALA ${w.roomIndex + 1}   PISO ${w.floor}   BAJAS ${w.kills}", iw - 8f, ih - 7f, Pal.CREAM, align = 1)
        for (f in w.fx.floaters) {
            text(f.text, f.x - camLeft, f.y - camBottom, f.color, alpha = (f.t / 0.9f).coerceIn(0f, 1f), align = 0)
        }
        if (w.bannerT > 0f) {
            text(w.banner, iw / 2f, ih * 0.80f, Pal.LAMP, alpha = w.bannerT.coerceIn(0f, 1f), align = 0)
        }
        if (w.gameOver && w.gameOverT > 1.2f) {
            text("Sala ${w.roomIndex + 1}  -  ${w.kills} bajas", iw / 2f, ih * 0.55f, Pal.CREAM, align = 0)
            val msg = if (c.touch) "Toca para volver a bajar" else "Pulsa una tecla para volver a bajar"
            text(msg, iw / 2f, ih * 0.45f, Pal.LAMP_HI, align = 0)
        }
        if (c.touch) {
            val btns = c.buttons()
            for (b in btns) {
                glyphs.setText(font, b.label)
                font.color = Pal.CREAM
                font.draw(batch, b.label, b.x - glyphs.width / 2f, b.y + glyphs.height / 2f)
            }
        }
        if (debug) {
            font.color = Color.GREEN
            font.draw(batch, "FPS ${Gdx.graphics.framesPerSecond}  x$scale  luz:${if (lighting) "on" else "off"}", 8f, 24f * font.data.scaleY)
        }
        batch.end()
        font.color = Color.WHITE
    }

    /** align: -1 izquierda, 0 centro, 1 derecha. Coordenadas internas convertidas a pantalla. */
    private fun text(s: String, ix: Float, iy: Float, color: Color, alpha: Float = 1f, align: Int = -1) {
        glyphs.setText(font, s)
        var x = ox + ix * scale
        val y = oy + iy * scale
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
            val b = btns[i]
            val base = when (i) { 0 -> Pal.LAMP; 1 -> Pal.LAMP_HI; else -> Pal.CREAM }
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
