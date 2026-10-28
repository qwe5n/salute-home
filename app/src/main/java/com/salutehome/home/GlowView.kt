package com.salutehome.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.View
import android.annotation.TargetApi
import com.salutehome.core.Tokens
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The glow behind everything (glow.css "Refined"): light rising from below the dock in the focused
 * artwork's colour, a deep core and a darker mid tone in one wide ellipse centred below the screen, and a
 * faint second tone in the top-left corner.
 *
 * The glow is dark (its brightest pixels are about 45 of 255), so an 8-bit gradient steps one level every
 * 40 to 50 px and the steps read as rings. On Android 13 and up one shader ([Gpu]) draws the whole glow,
 * dithered with a level of fixed noise (a fine grain), in every frame, moving or still: the frame the motion
 * stops on is the one that stays. Older Androids draw the
 * gradients below while it moves, then work the still glow out off the main thread into one dithered
 * picture that fades in over them ([Bake]).
 *
 * Both ellipses are radial gradients stretched by a local matrix, as the CSS's are. The TV draws with
 * Android 9's older OpenGL renderer (dumpsys gfxinfo: Pipeline=FrameBuilder), which handles gradient
 * shaders but not alpha-only bitmaps inside a shader (those come out black), so no weight maps here. The
 * gradients are rebuilt only while the colours ease (700 ms); the fades (600 ms in, 300 ms out) only change
 * the paints' alpha. Mixing the core into the mid tone in sRGB instead of OKLCH is within 2 levels of 255.
 *
 * [wash] adds a third layer under the two: the whole screen tinted in the mid tone, for a shelf showing a
 * single app; the glow over it still lightens it towards the dock. It eases its colour with the glow, so the
 * two read as one. A flat fill, not a gradient: a full-screen gradient rebuilt for every frame of a colour
 * ease cost the TV's GPU 30 ms a frame.
 */
class GlowView(context: Context) : View(context) {

    private val shown get() = opacity.target > 0f
    private val opacity = Tween(0f)
    private val washing = Tween(0f)

    // The three colours as premultiplied OKLab plus alpha (L*a, A*a, B*a, a), for core, mid and far: where
    // they are now, where the ease started and where it's going. CSS transitions colours in OKLab.
    private val now = DoubleArray(12)
    private val start = DoubleArray(12)
    private val goal = DoubleArray(12)
    private var easeStart = 0L
    private var easing = false
    private var goalArt = 0
    private var hasGoal = false

    private val widePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val farPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val washPaint = Paint()

    // Where each ellipse is centred and how it's stretched (the gradients are circles of radius rx).
    private var wideCx = 0f
    private var wideCy = 0f
    private var wideRx = 1f
    private val wideMatrix = Matrix()
    private var farCx = 0f
    private var farCy = 0f
    private var farRx = 1f
    private val farMatrix = Matrix()

    // The colours the gradients were last built with, so they're only rebuilt when the colours move.
    private val wideColors = IntArray(3)
    private val farColors = IntArray(2)
    private var builtCore = 0
    private var builtMid = 0
    private var builtFar = 0
    private var built = false
    private var wideRy = 1f
    private var farRy = 1f

    // The still glow worked out into a picture: the one shown, the one being made, and a spare to reuse.
    private var baked: Bake? = null
    private var baking = false
    private var bakedAt = 0L
    private val fadePaint = Paint()

    private val gpu: Gpu? = if (Build.VERSION.SDK_INT >= 33) Gpu() else null
    private var spare: Bitmap? = null

    // Where each ellipse can be non-black, so the fill stays off the rest of the screen.
    private val wideRect = RectF()
    private val farRect = RectF()
    private val fullRect = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        setWillNotDraw(false)
        // No hardware layer: on the TV one cost more frame time than the shader it would save repainting.
    }

    /** Glow in the colour of [art] (a vivid colour from [VividColor]); fades in if it was hidden. */
    fun show(art: Int) {
        if (!hasGoal || art != goalArt) {
            hasGoal = true
            goalArt = art
            setGoal(GlowColors.forArt(art))
        }
        if (!shown) opacity.to(1f, FADE_IN_MS)
        invalidate()
    }

    /** Fades out (focus went to the side dock). The colours stay for the next time it shows. */
    fun hide() {
        if (!shown) return
        opacity.to(0f, FADE_OUT_MS)
        invalidate()
    }

    val isShowing: Boolean get() = shown

    /** Tints the whole screen in the glow's colour, or takes the tint away; both ease. */
    fun wash(on: Boolean) {
        washing.to(if (on) 1f else 0f, if (on) WASH_IN_MS else WASH_OUT_MS)
        invalidate()
    }

    /**
     * Night's dimming (HomeScreen): worked into the still picture before it's dithered. A view alpha laid
     * over it would round the one-level noise away again.
     */
    var dim = 1f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** The art colour it's glowing in (or last glowed in, while hidden). */
    val artColor: Int get() = goalArt

    private fun setGoal(colors: GlowColors) {
        val t = HomeMotion.now()
        stepColors(t)
        now.copyInto(start)
        put(colors.core, 0)
        put(colors.mid, 4)
        put(colors.far, 8)
        if (HomeMotion.enabled) {
            easeStart = t
            easing = true
        } else {
            goal.copyInto(now)
            easing = false
        }
    }

    private fun put(lch: DoubleArray, at: Int) {
        val alpha = lch[3]
        val h = Math.toRadians(lch[2])
        goal[at] = lch[0] * alpha
        goal[at + 1] = lch[1] * cos(h) * alpha
        goal[at + 2] = lch[1] * sin(h) * alpha
        goal[at + 3] = alpha
    }

    private fun stepColors(t: Long): Boolean {
        if (!easing) return false
        val p = ((t - easeStart).toFloat() / COLOR_MS).coerceIn(0f, 1f)
        val e = Tokens.EASE_OUT.getInterpolation(p).toDouble()
        for (i in 0 until 12) now[i] = start[i] + (goal[i] - start[i]) * e
        if (p >= 1f) easing = false
        return easing
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // radial-gradient(115% 74% at 50% 112%, core, mid 44%, transparent 80%).
        wideCx = w * 0.5f
        wideCy = h * 1.12f
        wideRx = w * 1.15f
        wideRy = h * 0.74f
        place(wideMatrix, wideCx, wideCy, wideRx, wideRy, 0.80f, wideRect, w, h)
        // radial-gradient(60% 62% at 6% -10%, far, transparent 72%).
        farCx = w * 0.06f
        farCy = h * -0.10f
        farRx = w * 0.60f
        farRy = h * 0.62f
        place(farMatrix, farCx, farCy, farRx, farRy, 0.72f, farRect, w, h)
        fullRect.set(0f, 0f, w.toFloat(), h.toFloat())
        built = false
    }

    /** Squashes a circle of radius [rx] into the CSS ellipse, and bounds what it lights out to [extent]. */
    private fun place(m: Matrix, cx: Float, cy: Float, rx: Float, ry: Float, extent: Float, bounds: RectF, w: Int, h: Int) {
        m.setScale(1f, ry / rx, cx, cy)
        bounds.set(cx - rx * extent, cy - ry * extent, cx + rx * extent, cy + ry * extent)
        if (!bounds.intersect(0f, 0f, w.toFloat(), h.toFloat())) bounds.setEmpty()
    }

    override fun onDraw(canvas: Canvas) {
        val t = HomeMotion.now()
        val moving = opacity.step(t) or washing.step(t) or stepColors(t)
        val o = opacity.value
        if (o > 0f && hasGoal) {
            rebuildIfMoved()
            val w = washing.value
            val washAlpha = (o * w * WASH_ALPHA).roundToInt().coerceIn(0, 255)
            // Under a full wash the faint top-left tone doesn't show; skipping it saves a large pass a frame
            // while the shelf slides.
            val far = w < 1f && !farRect.isEmpty
            val still = baked
            // Software canvases (a screenshot test) can't run the shader; they take the older way.
            if (gpu != null && canvas.isHardwareAccelerated) {
                gpu.draw(canvas, o, washAlpha, far)
            } else if (!moving && still != null && still.shows(builtCore, builtMid, builtFar, washAlpha, far, dim, width, height)) {
                // The picture fades in over the live glow, so the step from one to the other never shows.
                val f = if (HomeMotion.enabled) ((t - bakedAt).toFloat() / SETTLE_MS).coerceIn(0f, 1f) else 1f
                if (f < 1f) {
                    drawLive(canvas, o, washAlpha, far)
                    fadePaint.alpha = (Tokens.EASE_OUT.getInterpolation(f) * 255).roundToInt()
                    canvas.drawBitmap(still.bitmap, 0f, 0f, fadePaint)
                    postInvalidateOnAnimation()
                } else {
                    canvas.drawBitmap(still.bitmap, 0f, 0f, null)
                }
            } else {
                drawLive(canvas, o, washAlpha, far)
                if (!moving) bake(washAlpha, far)
            }
        }
        if (moving) postInvalidateOnAnimation()
    }

    /** The glow from its gradients, as it's drawn while it moves. */
    private fun drawLive(canvas: Canvas, o: Float, washAlpha: Int, far: Boolean) {
        // The dimming goes on each layer's alpha: near enough while it moves.
        val alpha = (o * dim * 255).roundToInt().coerceIn(0, 255)
        widePaint.alpha = alpha
        farPaint.alpha = alpha
        if (washAlpha > 0) {
            washPaint.alpha = (washAlpha * dim).roundToInt()
            canvas.drawRect(fullRect, washPaint)
        }
        if (far) canvas.drawRect(farRect, farPaint)
        if (!wideRect.isEmpty) canvas.drawRect(wideRect, widePaint)
    }

    /** Works the still glow out into a picture off the main thread; one at a time, the newest wins. */
    private fun bake(washAlpha: Int, far: Boolean) {
        if (baking || width == 0 || height == 0) return
        baking = true
        val job = Bake(
            builtCore, builtMid, builtFar, washAlpha, far, dim, width, height,
            wideCx, wideCy, wideRx, wideRy, farCx, farCy, farRx, farRy,
            spare?.takeIf { it.width == width && it.height == height }
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888),
        )
        spare = null
        baker.post {
            job.render()
            // Sent to the GPU now, on the render thread, not in the frame that first draws it (8 MB held
            // that frame up).
            job.bitmap.prepareToDraw()
            post {
                baking = false
                // The picture shown before goes spare: nothing draws it from the next frame on.
                val old = baked
                baked = job
                bakedAt = HomeMotion.now()
                spare = old?.bitmap
                invalidate()
            }
        }
    }

    /** New gradients only when a colour has moved since the last ones (while easing, or the first time). */
    private fun rebuildIfMoved() {
        val core = colorAt(0)
        val mid = colorAt(4)
        val far = colorAt(8)
        if (built && core == builtCore && mid == builtMid && far == builtFar) return
        built = true
        builtCore = core
        builtMid = mid
        builtFar = far
        // Each ends in its own colour at no alpha, so the fade out doesn't darken on the way, as CSS's
        // premultiplied fades don't.
        wideColors[0] = core
        wideColors[1] = mid
        wideColors[2] = mid and 0xFFFFFF
        widePaint.shader = RadialGradient(wideCx, wideCy, wideRx, wideColors, WIDE_STOPS, Shader.TileMode.CLAMP)
            .apply { setLocalMatrix(wideMatrix) }
        farColors[0] = far
        farColors[1] = far and 0xFFFFFF
        farPaint.shader = RadialGradient(farCx, farCy, farRx, farColors, FAR_STOPS, Shader.TileMode.CLAMP)
            .apply { setLocalMatrix(farMatrix) }
        washPaint.color = mid or (0xFF shl 24)
    }

    /** Colour [at] in sRGB with its own alpha. */
    private fun colorAt(at: Int): Int {
        val a = now[at + 3]
        if (a <= 0.0) return 0
        val alpha = (a * 255).roundToInt().coerceIn(0, 255)
        val rgb = Oklab.toArgb(now[at] / a, now[at + 1] / a, now[at + 2] / a)
        return (rgb and 0xFFFFFF) or (alpha shl 24)
    }

    override fun hasOverlappingRendering() = false

    /**
     * The whole glow in one shader, as [Bake] works it out: the wash, the far tone and the wide ellipse over
     * black (colours mixed unpremultiplied along each ellipse, then source-over), dimmed, then one level of
     * noise from the pixel's position (interleaved gradient noise, half a level either way), the same in every frame, so
     * the 8-bit result dissolves the steps into a fine grain and stays put once the glow stops.
     */
    @TargetApi(33)
    private inner class Gpu {
        private val shader = RuntimeShader(SKSL)
        private val paint = Paint()
        private val bounds = RectF()

        fun draw(canvas: Canvas, fade: Float, washAlpha: Int, withFar: Boolean) {
            shader.setFloatUniform("wideC", wideCx, wideCy)
            shader.setFloatUniform("wideInv", 1f / wideRx, 1f / wideRy)
            shader.setFloatUniform("farC", farCx, farCy)
            shader.setFloatUniform("farInv", 1f / farRx, 1f / farRy)
            color("core", builtCore)
            color("mid", builtMid)
            color("far", builtFar)
            shader.setFloatUniform("wash", washAlpha / 255f)
            shader.setFloatUniform("useFar", if (withFar) 1f else 0f)
            shader.setFloatUniform("fade", fade)
            shader.setFloatUniform("dim", dim)
            // Set again after the uniforms change, so the paint takes them.
            paint.shader = shader
            // Only where the glow isn't black: the window behind is black already.
            if (washAlpha > 0) {
                canvas.drawRect(fullRect, paint)
            } else {
                bounds.set(wideRect)
                if (withFar) bounds.union(farRect)
                if (!bounds.isEmpty) canvas.drawRect(bounds, paint)
            }
        }

        private fun color(name: String, c: Int) {
            shader.setFloatUniform(
                name,
                ((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, (c ushr 24) / 255f,
            )
        }
    }

    /**
     * One still glow worked out pixel by pixel: the wash, the far tone and the wide ellipse laid over black
     * as the live gradients are (colours mixed unpremultiplied along each ellipse, then source-over), then
     * rounded to 8 bits with triangular noise of one level, the same for all three channels, so the steps
     * between levels dissolve into a fine grey grain instead of rings.
     */
    private class Bake(
        val core: Int, val mid: Int, val far: Int, val washAlpha: Int, val withFar: Boolean, val dim: Float,
        val w: Int, val h: Int,
        val wideCx: Float, val wideCy: Float, val wideRx: Float, val wideRy: Float,
        val farCx: Float, val farCy: Float, val farRx: Float, val farRy: Float,
        val bitmap: Bitmap,
    ) {
        fun shows(core: Int, mid: Int, far: Int, washAlpha: Int, withFar: Boolean, dim: Float, w: Int, h: Int): Boolean =
            core == this.core && mid == this.mid && far == this.far && washAlpha == this.washAlpha &&
                withFar == this.withFar && dim == this.dim && w == this.w && h == this.h

        fun render() {
            val coreA = (core ushr 24) / 255f
            val midA = (mid ushr 24) / 255f
            val farA = if (withFar) (far ushr 24) / 255f else 0f
            val washA = washAlpha / 255f
            val row = IntArray(w)
            var seed = 0x2545F491
            for (y in 0 until h) {
                val wy = (y + 0.5f - wideCy) / wideRy
                val fy = (y + 0.5f - farCy) / farRy
                for (x in 0 until w) {
                    var r = red(mid) * washA
                    var g = green(mid) * washA
                    var b = blue(mid) * washA
                    if (farA > 0f) {
                        val dx = (x + 0.5f - farCx) / farRx
                        val t = sqrt(dx * dx + fy * fy)
                        if (t < FAR_END) {
                            val a = farA * (1f - t / FAR_END)
                            r += (red(far) - r) * a
                            g += (green(far) - g) * a
                            b += (blue(far) - b) * a
                        }
                    }
                    val dx = (x + 0.5f - wideCx) / wideRx
                    val t = sqrt(dx * dx + wy * wy)
                    if (t < WIDE_END) {
                        val sr: Float
                        val sg: Float
                        val sb: Float
                        val a: Float
                        if (t < WIDE_MID) {
                            val f = t / WIDE_MID
                            sr = red(core) + (red(mid) - red(core)) * f
                            sg = green(core) + (green(mid) - green(core)) * f
                            sb = blue(core) + (blue(mid) - blue(core)) * f
                            a = coreA + (midA - coreA) * f
                        } else {
                            sr = red(mid)
                            sg = green(mid)
                            sb = blue(mid)
                            a = midA * (1f - (t - WIDE_MID) / (WIDE_END - WIDE_MID))
                        }
                        r += (sr - r) * a
                        g += (sg - g) * a
                        b += (sb - b) * a
                    }
                    // Two uniform numbers from one xorshift step: their sum less one is triangular, -1 to 1.
                    seed = seed xor (seed shl 13)
                    seed = seed xor (seed ushr 17)
                    seed = seed xor (seed shl 5)
                    val n = ((seed and 0xFFFF) + (seed ushr 16 and 0xFFFF)) / 65536f - 1f
                    row[x] = (0xFF shl 24) or (level(r * dim + n) shl 16) or (level(g * dim + n) shl 8) or level(b * dim + n)
                }
                bitmap.setPixels(row, 0, w, 0, y, w, 1)
            }
        }

        private fun red(c: Int) = ((c shr 16) and 0xFF).toFloat()
        private fun green(c: Int) = ((c shr 8) and 0xFF).toFloat()
        private fun blue(c: Int) = (c and 0xFF).toFloat()
        private fun level(v: Float) = (v + 0.5f).toInt().coerceIn(0, 255)
    }

    companion object {
        const val COLOR_MS = 700L
        const val FADE_IN_MS = 600L
        const val FADE_OUT_MS = 300L

        /** The still picture's fade in over the live glow. */
        private const val SETTLE_MS = 250f

        private const val WIDE_MID = 0.44f
        private const val WIDE_END = 0.80f
        private const val FAR_END = 0.72f

        private const val SKSL = """
            uniform float2 wideC;
            uniform float2 wideInv;
            uniform float2 farC;
            uniform float2 farInv;
            uniform half4 core;
            uniform half4 mid;
            uniform half4 far;
            uniform half wash;
            uniform half useFar;
            uniform half fade;
            uniform half dim;

            half4 main(float2 p) {
                half3 c = mid.rgb * wash;
                half tf = half(length((p - farC) * farInv));
                c = mix(c, far.rgb, far.a * fade * useFar * saturate(1.0 - tf / 0.72));
                half t = half(length((p - wideC) * wideInv));
                half f = saturate(t / 0.44);
                half a = mix(core.a, mid.a, f) * saturate((0.80 - t) / 0.36);
                c = mix(c, mix(core.rgb, mid.rgb, f), a * fade);
                half n = half(fract(52.9829189 * fract(dot(p, float2(0.06711056, 0.00583715))))) - 0.5;
                return half4(c * dim + n / 255.0, 1.0);
            }
        """

        /** One thread for the still glow's pictures, for the life of the process. */
        private val baker: Handler by lazy { Handler(HandlerThread("glow").apply { start() }.looper) }

        private val WIDE_STOPS = floatArrayOf(0f, WIDE_MID, WIDE_END)
        private val FAR_STOPS = floatArrayOf(0f, FAR_END)

        const val WASH_IN_MS = 350L
        const val WASH_OUT_MS = 250L

        /** The wash's strength, of 255. */
        private const val WASH_ALPHA = 0x80
    }
}
