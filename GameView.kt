package com.spacedodger.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private val CYAN = 0xFF00E5FF.toInt()
private val GOLD = 0xFFFFD54F.toInt()
private val ORANGE = 0xFFFFB300.toInt()
private val RED = 0xFFFF5252.toInt()
private val GREEN = 0xFF69F0AE.toInt()
private val WHITE = 0xFFFFFFFF.toInt()
private val GREY = 0xFFB0BEC5.toInt()

private const val SHIP_R = 13f
private const val MAX_LIVES = 5
private const val MAX_PARTICLES = 500
private const val KEY_BEST = "best"
private const val CHAPTER_SECONDS = 30f

private val STORY_TITLES = arrayOf(
    "CHAPTER 1: ASTEROID BELT",
    "CHAPTER 2: SITAARON KA TOOFAN",
    "CHAPTER 3: KHAMOSH ANDHERA",
    "CHAPTER 4: ZAMEEN KA DARWAZA"
)

private val STORY_LINES = arrayOf(
    arrayOf(
        "Saal 2150. Zameen ki roshni",
        "bujhne wali hai.",
        "Pilot Zara ke paas aakhri umeed:",
        "Noor gems Zameen tak pahunchana.",
        "Pehle asteroid belt paar karo!"
    ),
    arrayOf(
        "Zara belt se nikal gayi, magar",
        "aage sitaron ka toofan hai.",
        "Radio se awaaz aati hai:",
        "'Zara, himmat mat haarna!'",
        "Asteroids ab zyada tez hain."
    ),
    arrayOf(
        "Toofan peeche reh gaya.",
        "Ab sirf khamosh andhera hai.",
        "Ek ajnabi jahaz Zara ko dekhta hai",
        "magar woh dushman nahi.",
        "Gems jama karo, jaan bachao!"
    ),
    arrayOf(
        "Zameen nazar aane lagi hai!",
        "Bas aakhri raasta baaki hai.",
        "Asteroids ki baarish hai, magar",
        "Zara ruk nahi sakti.",
        "Zameen ko roshni do!"
    )
)

private val WIN_LINES = arrayOf(
    "Zara ne Noor gems Zameen tak",
    "pahuncha diye!",
    "Zameen phir se roshan ho gayi.",
    "Shukriya, hero!"
)

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    private enum class State { MENU, STORY, PLAYING, GAME_OVER, WIN }

    private class Asteroid(
        var x: Float, var y: Float, val r: Float, val speed: Float,
        var rot: Float, val rotSpeed: Float, val shape: FloatArray
    )

    private class Gem(var x: Float, var y: Float, val speed: Float, var phase: Float)

    private class Particle(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var life: Float, val maxLife: Float, val color: Int, val size: Float
    )

    private class BgStar(var x: Float, var y: Float, val speed: Float, val size: Float, val alpha: Int)

    // ---- threading / lifecycle ----
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var surfaceReady = false
    @Volatile private var needInit = false
    @Volatile private var pendingStart = false
    @Volatile private var targetX = 0f
    @Volatile private var safeTop = 0f
    @Volatile private var state = State.MENU
    @Volatile private var gameOverTime = 0L
    @Volatile private var storyTime = 0L
    @Volatile private var pendingContinue = false
    @Volatile private var pendingMenu = false
    private var chapter = 0
    private var chapterTime = 0f

    // ---- world ----
    private var w = 0f
    private var h = 0f
    private var u = 1f // pixels per "unit" (screen width / 360)
    private var shipX = 0f
    private var shipY = 0f
    private var lives = 3
    private var score = 0f
    private var finalScore = 0
    private var newBest = false
    private var gems = 0
    private var elapsed = 0f
    private var spawnTimer = 0f
    private var gemTimer = 0f
    private var invuln = 0f
    private var shake = 0f
    private var animTime = 0f
    private var best = 0

    private val asteroids = ArrayList<Asteroid>()
    private val gemList = ArrayList<Gem>()
    private val particles = ArrayList<Particle>()
    private val bgStars = ArrayList<BgStar>()

    // ---- paints ----
    private val prefs = context.getSharedPreferences("space_dodger", Context.MODE_PRIVATE)
    private val bgPaint = Paint()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()

    init {
        holder.addCallback(this)
        isFocusable = true
        best = prefs.getInt(KEY_BEST, 0)
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val i = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            safeTop = i.top.toFloat()
            insets
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    fun resume() {
        if (running) return
        running = true
        thread = Thread(this, "GameThread").also { it.start() }
    }

    fun pause() {
        running = false
        thread?.let {
            try {
                it.join(500)
            } catch (_: InterruptedException) {
            }
        }
        thread = null
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        needInit = true
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                targetX = e.x
                when (state) {
                    State.MENU -> pendingStart = true
                    State.GAME_OVER ->
                        if (System.nanoTime() - gameOverTime > 700_000_000L) pendingStart = true
                    State.STORY ->
                        if (System.nanoTime() - storyTime > 500_000_000L) pendingContinue = true
                    State.WIN ->
                        if (System.nanoTime() - storyTime > 1_000_000_000L) pendingMenu = true
                    State.PLAYING -> {}
                }
            }
            MotionEvent.ACTION_MOVE -> targetX = e.x
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // ------------------------------------------------------------------
    // Game loop
    // ------------------------------------------------------------------

    override fun run() {
        var last = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            val dt = min((now - last) / 1_000_000_000f, 0.05f)
            last = now

            if (!surfaceReady || width == 0 || height == 0) {
                sleepMs(16)
                continue
            }

            update(dt)

            val canvas: Canvas? = try {
                holder.lockCanvas()
            } catch (_: Exception) {
                null
            }
            if (canvas != null) {
                try {
                    drawFrame(canvas)
                } finally {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (_: Exception) {
                    }
                }
            }

            val frameMs = (System.nanoTime() - now) / 1_000_000
            if (frameMs < 16) sleepMs(16 - frameMs)
        }
    }

    private fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    // ------------------------------------------------------------------
    // Update
    // ------------------------------------------------------------------

    private fun initWorld() {
        needInit = false
        w = width.toFloat()
        h = height.toFloat()
        u = w / 360f
        shipX = w / 2f
        shipY = h * 0.82f
        targetX = shipX
        bgStars.clear()
        repeat(70) {
            bgStars.add(
                BgStar(
                    Random.nextFloat() * w, Random.nextFloat() * h,
                    rnd(15f, 85f), rnd(0.6f, 2.2f), 80 + Random.nextInt(176)
                )
            )
        }
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h, 0xFF03040F.toInt(), 0xFF1B0B3D.toInt(), Shader.TileMode.CLAMP
        )
    }

    private fun startGame() {
        asteroids.clear()
        gemList.clear()
        particles.clear()
        lives = 3
        score = 0f
        gems = 0
        elapsed = 0f
        spawnTimer = 0.6f
        gemTimer = 3f
        invuln = 0f
        shake = 0f
        newBest = false
        shipY = h * 0.82f
        chapter = 0
        chapterTime = 0f
        enterStory()
    }

    private fun enterStory() {
        storyTime = System.nanoTime()
        state = State.STORY
    }

    private fun winGame() {
        score += 500f
        finalScore = score.toInt()
        newBest = finalScore > best
        if (newBest) {
            best = finalScore
            prefs.edit().putInt(KEY_BEST, best).apply()
        }
        burst(w / 2f, h * 0.5f, 60, GOLD, 300f, 3.5f)
        burst(w / 2f, h * 0.5f, 40, CYAN, 260f, 3f)
        storyTime = System.nanoTime()
        state = State.WIN
    }

    private fun difficulty(): Float = min(elapsed / 120f, 1f)

    private fun update(dt: Float) {
        if (needInit) initWorld()
        if (pendingStart) {
            pendingStart = false
            startGame()
        }
        if (pendingContinue) {
            pendingContinue = false
            if (state == State.STORY) {
                chapterTime = 0f
                spawnTimer = 0.6f
                gemTimer = 3f
                invuln = 1f
                state = State.PLAYING
            }
        }
        if (pendingMenu) {
            pendingMenu = false
            if (state == State.WIN) state = State.MENU
        }
        animTime += dt

        val boost = if (state == State.PLAYING) 1f + difficulty() else 1f
        for (s in bgStars) {
            s.y += s.speed * u * boost * dt
            if (s.y > h) {
                s.y = -2f
                s.x = Random.nextFloat() * w
            }
        }
        updateParticles(dt)
        shake = max(0f, shake - dt)

        if (state != State.PLAYING) return

        elapsed += dt
        chapterTime += dt
        if (chapterTime >= CHAPTER_SECONDS) {
            chapterTime = 0f
            chapter++
            score += 200f
            asteroids.clear()
            gemList.clear()
            if (chapter >= STORY_TITLES.size) winGame() else enterStory()
            return
        }
        val diff = difficulty()
        score += dt * 10f

        // ship movement (smoothly follows the finger)
        shipX += (targetX - shipX) * min(1f, 14f * dt)
        shipX = shipX.coerceIn(16f * u, w - 16f * u)
        if (invuln > 0f) invuln -= dt

        // engine trail
        if (Random.nextFloat() < 0.6f && particles.size < MAX_PARTICLES) {
            particles.add(
                Particle(
                    shipX + rnd(-3f, 3f) * u, shipY + 14f * u,
                    rnd(-10f, 10f) * u, rnd(60f, 120f) * u,
                    0.35f, 0.35f, ORANGE, 2.2f * u
                )
            )
        }

        // spawning
        spawnTimer -= dt
        if (spawnTimer <= 0f) {
            spawnAsteroid(diff)
            spawnTimer = (0.85f - 0.57f * diff) * rnd(0.8f, 1.2f)
        }
        gemTimer -= dt
        if (gemTimer <= 0f) {
            gemList.add(Gem(rnd(24f * u, w - 24f * u), -20f * u, rnd(90f, 130f), 0f))
            gemTimer = rnd(2.5f, 5f)
        }

        // asteroids
        val ai = asteroids.iterator()
        while (ai.hasNext()) {
            val a = ai.next()
            a.y += a.speed * u * dt
            a.rot += a.rotSpeed * dt
            if (a.y - a.r > h + 10f) {
                ai.remove()
                score += 2f
                continue
            }
            if (invuln <= 0f && hypot(a.x - shipX, a.y - shipY) < a.r * 0.8f + SHIP_R * u) {
                ai.remove()
                onHit(a.x, a.y)
                if (state != State.PLAYING) return
            }
        }

        // gems
        val gi = gemList.iterator()
        while (gi.hasNext()) {
            val g = gi.next()
            g.y += g.speed * u * dt
            g.phase += dt * 6f
            if (g.y > h + 30f * u) {
                gi.remove()
                continue
            }
            if (hypot(g.x - shipX, g.y - shipY) < 22f * u) {
                gi.remove()
                gems++
                score += 50f
                burst(g.x, g.y, 14, GOLD, 160f, 2.5f)
                if (gems % 5 == 0 && lives < MAX_LIVES) {
                    lives++
                    burst(shipX, shipY, 24, GREEN, 200f, 3f)
                }
            }
        }
    }

    private fun spawnAsteroid(diff: Float) {
        val r = rnd(14f, 34f) * u
        val n = 9
        val shape = FloatArray(n) { rnd(0.75f, 1f) }
        asteroids.add(
            Asteroid(
                rnd(r, w - r), -r, r,
                rnd(110f, 200f) + diff * 180f,
                rnd(0f, 360f), rnd(-120f, 120f), shape
            )
        )
    }

    private fun onHit(x: Float, y: Float) {
        lives--
        burst(x, y, 24, 0xFFFF7043.toInt(), 220f, 3f)
        shake = 0.35f
        post { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
        if (lives <= 0) {
            gameOver()
        } else {
            invuln = 1.6f
        }
    }

    private fun gameOver() {
        state = State.GAME_OVER
        burst(shipX, shipY, 60, CYAN, 300f, 3.5f)
        burst(shipX, shipY, 40, ORANGE, 240f, 3f)
        finalScore = score.toInt()
        newBest = finalScore > best
        if (newBest) {
            best = finalScore
            prefs.edit().putInt(KEY_BEST, best).apply()
        }
        shake = 0.6f
        gameOverTime = System.nanoTime()
    }

    private fun updateParticles(dt: Float) {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            if (p.life <= 0f) {
                it.remove()
                continue
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
        }
    }

    private fun burst(x: Float, y: Float, n: Int, color: Int, speed: Float, size: Float) {
        repeat(n) {
            if (particles.size >= MAX_PARTICLES) return
            val a = Random.nextFloat() * 2f * PI.toFloat()
            val s = speed * u * rnd(0.3f, 1f)
            val life = rnd(0.4f, 1f)
            particles.add(
                Particle(x, y, cos(a) * s, sin(a) * s, life, life, color, size * u * rnd(0.5f, 1.5f))
            )
        }
    }

    private fun rnd(a: Float, b: Float): Float = a + Random.nextFloat() * (b - a)

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    private fun drawFrame(c: Canvas) {
        c.drawRect(0f, 0f, w, h, bgPaint)

        for (s in bgStars) {
            fill.color = WHITE
            fill.alpha = s.alpha
            c.drawCircle(s.x, s.y, s.size * u * 0.6f, fill)
        }
        fill.alpha = 255

        c.save()
        if (shake > 0f) {
            c.translate(rnd(-1f, 1f) * shake * 20f * u, rnd(-1f, 1f) * shake * 20f * u)
        }

        when (state) {
            State.MENU -> {
                drawShip(c, w / 2f, h * 0.70f + sin(animTime * 2f) * 6f * u, 1.3f, true)
            }
            State.STORY, State.WIN -> {
                drawShip(c, w / 2f, h * 0.80f + sin(animTime * 2f) * 6f * u, 1.2f, true)
            }
            State.PLAYING, State.GAME_OVER -> {
                for (a in asteroids) drawAsteroid(c, a)
                for (g in gemList) drawGem(c, g)
                if (state == State.PLAYING) {
                    val visible = invuln <= 0f || ((invuln * 10f).toInt() % 2 == 0)
                    if (visible) drawShip(c, shipX, shipY, 1f, true)
                }
            }
        }

        for (p in particles) {
            fill.color = p.color
            fill.alpha = (255 * (p.life / p.maxLife)).toInt().coerceIn(0, 255)
            c.drawCircle(p.x, p.y, p.size, fill)
        }
        fill.alpha = 255
        c.restore()

        when (state) {
            State.MENU -> drawMenu(c)
            State.STORY -> drawStory(c)
            State.WIN -> drawWin(c)
            State.PLAYING -> drawHud(c)
            State.GAME_OVER -> {
                drawHud(c)
                drawGameOver(c)
            }
        }
    }

    private fun drawShip(c: Canvas, x: Float, y: Float, scale: Float, flame: Boolean) {
        c.save()
        c.translate(x, y)
        c.scale(scale * u, scale * u)
        if (flame) {
            val len = 10f + Random.nextFloat() * 10f
            path.reset()
            path.moveTo(-6f, 10f)
            path.lineTo(0f, 10f + len)
            path.lineTo(6f, 10f)
            path.close()
            fill.color = ORANGE
            c.drawPath(path, fill)
            path.reset()
            path.moveTo(-3f, 10f)
            path.lineTo(0f, 10f + len * 0.55f)
            path.lineTo(3f, 10f)
            path.close()
            fill.color = 0xFFFFF59D.toInt()
            c.drawPath(path, fill)
        }
        path.reset()
        path.moveTo(0f, -20f)
        path.lineTo(14f, 14f)
        path.lineTo(0f, 7f)
        path.lineTo(-14f, 14f)
        path.close()
        fill.color = CYAN
        c.drawPath(path, fill)
        fill.color = WHITE
        c.drawCircle(0f, -2f, 3.5f, fill)
        c.restore()
    }

    private fun drawAsteroid(c: Canvas, a: Asteroid) {
        c.save()
        c.translate(a.x, a.y)
        c.rotate(a.rot)
        path.reset()
        val n = a.shape.size
        for (i in 0 until n) {
            val ang = i * 2f * PI.toFloat() / n
            val rr = a.r * a.shape[i]
            val px = cos(ang) * rr
            val py = sin(ang) * rr
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        fill.color = 0xFF6D5D52.toInt()
        c.drawPath(path, fill)
        stroke.color = 0xFF3B312B.toInt()
        stroke.strokeWidth = 2f * u
        c.drawPath(path, stroke)
        fill.color = 0xFF4E4239.toInt()
        c.drawCircle(-a.r * 0.25f, -a.r * 0.2f, a.r * 0.18f, fill)
        c.drawCircle(a.r * 0.3f, a.r * 0.25f, a.r * 0.12f, fill)
        c.restore()
    }

    private fun drawGem(c: Canvas, g: Gem) {
        val r = 11f * u * (1f + 0.12f * sin(g.phase))
        fill.color = GOLD
        fill.alpha = 60
        c.drawCircle(g.x, g.y, r * 1.8f, fill)
        fill.alpha = 255
        path.reset()
        path.moveTo(g.x, g.y - r)
        path.lineTo(g.x + r * 0.7f, g.y)
        path.lineTo(g.x, g.y + r)
        path.lineTo(g.x - r * 0.7f, g.y)
        path.close()
        fill.color = GOLD
        c.drawPath(path, fill)
    }

    private fun label(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        align: Paint.Align = Paint.Align.CENTER, glow: Boolean = false, alpha: Int = 255
    ) {
        text.textSize = size
        text.color = color
        text.alpha = alpha
        text.textAlign = align
        if (glow) text.setShadowLayer(size * 0.5f, 0f, 0f, color) else text.clearShadowLayer()
        c.drawText(s, x, y, text)
        text.clearShadowLayer()
    }

    private fun pulseAlpha(): Int = (150 + 105 * sin(animTime * 4f)).toInt().coerceIn(0, 255)

    private fun drawHud(c: Canvas) {
        val top = safeTop + 36f * u
        label(c, "${score.toInt()}", 16f * u, top, 26f * u, WHITE, Paint.Align.LEFT)
        label(c, "BEST ${max(best, if (state == State.PLAYING) 0 else finalScore)}",
            16f * u, top + 20f * u, 13f * u, GREY, Paint.Align.LEFT)
        label(c, "GEMS $gems", 16f * u, top + 38f * u, 13f * u, GOLD, Paint.Align.LEFT)
        for (i in 0 until lives) {
            drawShip(c, w - 24f * u - i * 26f * u, top - 8f * u, 0.55f, false)
        }
        label(c, "CHAPTER ${chapter + 1}/${STORY_TITLES.size}", w - 16f * u, top + 22f * u,
            13f * u, CYAN, Paint.Align.RIGHT)
    }

    private fun drawStory(c: Canvas) {
        c.drawColor(0xAA000000.toInt())
        val idx = chapter.coerceIn(0, STORY_TITLES.size - 1)
        label(c, STORY_TITLES[idx], w / 2f, h * 0.20f, 20f * u, CYAN, glow = true)
        val lines = STORY_LINES[idx]
        for (i in lines.indices) {
            label(c, lines[i], w / 2f, h * 0.30f + i * 30f * u, 17f * u, WHITE)
        }
        if (System.nanoTime() - storyTime > 500_000_000L) {
            label(c, "TAP TO CONTINUE", w / 2f, h * 0.92f, 20f * u, GOLD, alpha = pulseAlpha())
        }
    }

    private fun drawWin(c: Canvas) {
        c.drawColor(0x88000000.toInt())
        label(c, "MISSION COMPLETE!", w / 2f, h * 0.18f, 32f * u, GOLD, glow = true)
        for (i in WIN_LINES.indices) {
            label(c, WIN_LINES[i], w / 2f, h * 0.28f + i * 30f * u, 17f * u, WHITE)
        }
        label(c, "Score: $finalScore", w / 2f, h * 0.28f + 150f * u, 26f * u, WHITE)
        if (newBest) {
            label(c, "NEW BEST!", w / 2f, h * 0.28f + 186f * u, 22f * u, GOLD, glow = true)
        }
        if (System.nanoTime() - storyTime > 1_000_000_000L) {
            label(c, "TAP FOR MENU", w / 2f, h * 0.92f, 20f * u, CYAN, alpha = pulseAlpha())
        }
    }

    private fun drawMenu(c: Canvas) {
        label(c, "SPACE", w / 2f, h * 0.22f, 60f * u, WHITE, glow = true)
        label(c, "DODGER", w / 2f, h * 0.22f + 66f * u, 60f * u, CYAN, glow = true)
        val y0 = h * 0.42f
        label(c, "Pilot Zara ki kahani: 4 chapters", w / 2f, y0, 16f * u, GREY)
        label(c, "Ungli drag karo, asteroids se bacho", w / 2f, y0 + 26f * u, 16f * u, GREY)
        label(c, "5 gems = 1 extra jaan", w / 2f, y0 + 52f * u, 16f * u, GREY)
        label(c, "BEST: $best", w / 2f, h * 0.56f, 20f * u, GOLD)
        label(c, "TAP TO START", w / 2f, h * 0.90f, 24f * u, WHITE, alpha = pulseAlpha())
    }

    private fun drawGameOver(c: Canvas) {
        c.drawColor(0xAA000000.toInt())
        label(c, "GAME OVER", w / 2f, h * 0.33f, 44f * u, RED, glow = true)
        label(c, "Score: $finalScore", w / 2f, h * 0.33f + 56f * u, 28f * u, WHITE)
        if (newBest) {
            label(c, "NEW BEST!", w / 2f, h * 0.33f + 96f * u, 24f * u, GOLD, glow = true)
        } else {
            label(c, "Best: $best", w / 2f, h * 0.33f + 96f * u, 22f * u, GOLD)
        }
        label(c, "Gems: $gems", w / 2f, h * 0.33f + 130f * u, 18f * u, GREY)
        if (System.nanoTime() - gameOverTime > 700_000_000L) {
            label(c, "TAP TO PLAY AGAIN", w / 2f, h * 0.78f, 22f * u, WHITE, alpha = pulseAlpha())
        }
    }
}
