package com.moss.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The fullscreen card's backdrop: one fixed image, sharp and veiled.
 *
 * The card used to be a window onto whatever was behind it. That was the whole
 * point of [Glass.frost] — `FLAG_BLUR_BEHIND` defocuses the home screen or the
 * app underneath and the card's 60%-black fill lays the glass on top of it — and
 * it is the right answer for a modal that appears over a document. It is the
 * wrong answer for this card: it is fullscreen, it stays up for minutes at a
 * time, and what shows through is the user's home screen with their own icons
 * dissolved behind the text. The card read as *transparent* rather than as
 * *ours*.
 *
 * So the card brings its own background. Three consequences worth stating,
 * because each one is a decision rather than a side effect:
 *
 *  * **The compositor blur is off while this is showing** (`FloatingWindowUi.
 *    applyWindow`). There is no longer anything behind the card to defrost — the
 *    backdrop covers the whole window — so asking for it would be a full-screen
 *    defocus of the GPU, several times a second, for a result nobody can see.
 *    It is also what makes the card's look independent of
 *    `isCrossWindowBlurEnabled`, which is false on battery saver, on devices
 *    that cannot afford it, and whenever the developer option is off.
 *  * **There is no blur on the image itself either.** It used to be a plain
 *    box blur run three times, which read as frosted glass over a photograph.
 *    The photograph is the user's own wallpaper now, and it is shown sharp,
 *    at up to [TARGET_W] wide and never upscaled past its native size — the
 *    veil below is the only film over it, and the veil is brightness, not
 *    softness, so text stays readable without the image going murky.
 *  * **It runs off the main thread, at build time.** The window is built folded
 *    and the backdrop is `GONE` while that runs, so nothing ever blocks the
 *    main thread for it.
 *
 *    It used to also say "finished long before the first unfold", on the
 *    grounds that the service starts folded so nothing is shown until the user
 *    long-presses. The service no longer starts folded — it unfolds at birth
 *    (`BodyCoreService.onServiceConnected`) — so on a cold start the first
 *    unfold can *beat* this. The card is already built for that: until [ready]
 *    flips, `backdropUp()` is false, `paintCard` gives the card its own fill,
 *    and [warm]'s callback re-runs `applyWindow` so the real backdrop takes
 *    over the moment it exists. The cost is a frame or two of the fallback
 *    look instead of a card with a transparent hole in it — the same trade
 *    `backdropUp` was written for.
 *
 * **One photograph serves both palettes, veiled in opposite directions.** The image is
 * the landscape this app shipped with, and it is the only texture in a UI that is
 * otherwise flat vector shapes. It is filmed over:
 *
 *  * under the **night** palette it takes a black veil, 0.22 / 0.38 as it always was,
 *    which is why the page reads `#243C49` — the original `backdrop.jpg` behaviour, kept
 *    to the digit;
 *  * under the **day** palette it takes a **white** one, to 0.70, so the same photograph
 *    sits behind dark type at nearly half its own contrast: a landscape you can read as
 *    sky and meadow rather than a texture you have to be told about, and still light
 *    enough for the type. That target is the dial for how visible the image is — see
 *    [DAY_TARGET_LUMA].
 *
 * That is the whole difference. Nothing about the file changes
 * between the two — see [veilFor] for the arithmetic, and the constants under it for why
 * one direction needs a floor and the other a ceiling.
 *
 * It has been flat white in between, which lost because **a material needs something to
 * be made of**: glass over white is white, and the card stopped reading as a surface at
 * all. A veil over a photograph is what makes the pane a pane — and it is why the
 * bubbles and the input field read as frosted glass on this backdrop and only as pale
 * rectangles on a flat one.
 */
object Backdrop {

    private const val TAG = "Body"

    /**
     * The decode's width cap, in pixels. Fixed rather than screen-relative on
     * purpose: beyond this the extra pixels cost memory for detail the
     * `CENTER_CROP` stretch cannot show, and a source narrower than the cap
     * is never upscaled — upscaling invents nothing, it only smooths what
     * is there, which is the murk the blur removal was for.
     */
    private const val TARGET_W = 1080

    /**
     * How far the photograph is pushed, and which way.
     *
     * **One image, two palettes, two directions** — and the direction is the whole of
     * it. Night puts light type on the page, so the photo is pushed *down* under a black
     * veil until its mean luminance is somewhere dark: 0.22 was picked on a device
     * because 0.13 — what a 60%-black card composites to over a blurred desktop — read
     * as a black wall with a photo buried in it. The 0.38 floor is the other half of
     * that, a promise of *some* darkening so a bright image can never come back bright
     * enough to swallow the pale ball. Those two numbers are why the backdrop lands on
     * `#243C49`, which is what this app shipped with.
     *
     * Day puts dark type on the page, so the same photograph is pushed *up* under a
     * **white** veil instead. Nothing about the image differs between the two; only the
     * film over it does. See [veilFor] for the arithmetic.
     *
     * **[DAY_TARGET_LUMA] is the "how much do you want to see the photograph" dial** —
     * turn it down to let more of the image through, up to hide it. Walked down three
     * times on request: 0.88 → 0.82 → 0.70 → 0.62.
     *
     * The relation that makes this number tractable is
     *
     *     surviving contrast of the photograph = (1 − target) / (1 − luma)
     *
     * which at this image's luma of 0.364 runs 0.28, 0.44, 0.50, **0.60** across those
     * four values — so roughly two points of image per hundredth of target, and each step
     * is a large visible one.
     *
     * **How far down it can go is set by type contrast, not taste.** The page's mean
     * luminance *is* this number, and the fullscreen card has no fill of its own — the
     * photograph *is* the conversation's background, so its text sits directly on the
     * image. Meanwhile the bubbles are translucent films of 8–16% black, which is nothing
     * much to sit on when what is underneath varies. The numbers to keep in mind while
     * turning it: [SECONDARY] is a 0.36 grey, so it needs the ground under it to stay
     * above about 0.55, and the photograph's darker half lands near 0.45 at this target.
     * See [veilFor].
     */
    private const val NIGHT_TARGET_LUMA = 0.22f
    private const val NIGHT_VEIL_MIN = 0.38f
    private const val DAY_TARGET_LUMA = 0.62f

    /**
     * Night's other clamp: a near-black image must not be darkened into nothing.
     */
    private const val NIGHT_VEIL_MAX = 0.75f

    /**
     * The ceiling on day's lift, and it is not the mirror of night's floor.
     *
     * A floor is needed in one direction and a ceiling in the other, because the two
     * have opposite failure modes. Under-darkening a bright image leaves dark type
     * fighting a photograph; under-*lightening* a dark one does exactly the same, so day
     * needs a guaranteed minimum lift — which the `(target − luma) / (1 − luma)` form
     * gives for free, since it climbs to 1 as the image darkens. What is left to guard
     * is the far end: without a cap, a near-black photo would be washed to a blank white
     * page, and "use the photograph in light mode too" would quietly have become "use a
     * white page". 0.90 keeps a tenth of the image's own contrast alive.
     */
    private const val DAY_VEIL_MAX = 0.90f

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "backdrop").apply { isDaemon = true }
    }

    @Volatile private var ready = false

    /** The bitmap, whole. `CENTER_CROP` does the cropping, not this. */
    @Volatile private var bitmap: Bitmap? = null

    /** ARGB fill that goes over [bitmap] and under everything else. */
    @Volatile private var scrim = 0

    /** Whether [bitmap] and [scrim] are worth reading yet. */
    fun ready(): Boolean = ready

    /**
     * Forget the decode, so the next [warm] redoes it against the current theme.
     *
     * Called on a theme change. [warm] is one-shot by design — "safe to call more than
     * once; only the first does work" — and the two palettes have two different files,
     * so without this the card would keep the day wash under the night palette. That is
     * the most visible way to get a theme switch wrong, and it is silent: the palette
     * around it would all be correct.
     */
    fun reset() {
        ready = false
        bitmap = null
        scrim = 0
    }

    fun bitmap(): Bitmap? = bitmap

    /**
     * The fill the card's own background should be while the backdrop is up.
     * `0` before [warm] finishes — callers should read [ready] first.
     */
    fun scrim(): Int = scrim

    /**
     * Decode and measure the backdrop, then hand the result to [onReady] on
     * the main thread. Safe to call more than once; only the first does work.
     */
    fun warm(context: Context, onReady: (Bitmap) -> Unit) {
        if (ready) {
            ui.post { onReady(bitmap!!) }
            return
        }
        val app = context.applicationContext
        worker.execute {
            val out = runCatching { build(app) }
                .onFailure { Log.e(TAG, "backdrop failed; card keeps its own fill", it) }
                .getOrNull() ?: return@execute
            bitmap = out.first
            scrim = out.second
            ready = true
            ui.post { onReady(out.first) }
        }
    }

    /** Decode → cap → measure. Worker thread only. */
    private fun build(context: Context): Pair<Bitmap, Int> {
        val src = decode(context)
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        src.recycle()

        val luma = meanLuma(px)
        val veil = veilFor(luma)
        Log.i(
            TAG,
            "backdrop %dx%d  mean luma %.3f → veil %.0f%% %s".format(
                w, h, luma, Color.alpha(veil) * 100f / 255f, if (Theme.night) "black" else "white",
            ),
        )
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888) to veil
    }

    /**
     * The film that goes over the photograph, as ARGB, for the palette in force.
     *
     * Two formulas, because the two palettes fail in opposite directions. A black veil
     * leaves `luma × (1 − a)`, so the alpha falls straight out of the target. A white one
     * leaves `luma × (1 − a) + a`, which inverts to `(target − luma) / (1 − luma)` — and
     * that form is self-limiting in the useful direction: an image already brighter than
     * the target yields a negative alpha, i.e. no veil at all, which is the right answer
     * for a photograph pale enough for dark type to sit on unaided.
     *
     * Worker thread only.
     */
    private fun veilFor(luma: Float): Int {
        val l = max(luma, 0.02f)
        return if (Theme.night) {
            val a = (1f - NIGHT_TARGET_LUMA / l).coerceIn(NIGHT_VEIL_MIN, NIGHT_VEIL_MAX)
            Color.argb((a * 255).roundToInt(), 0, 0, 0)
        } else {
            val a = ((DAY_TARGET_LUMA - l) / (1f - l)).coerceIn(0f, DAY_VEIL_MAX)
            Color.argb((a * 255).roundToInt(), 255, 255, 255)
        }
    }

    /**
     * The image, at up to [TARGET_W] wide, never upscaled past native.
     *
     * Two steps rather than one: `inSampleSize` is only cheap *and* predictable
     * in powers of two, so it takes the width down to somewhere at or above the
     * cap and a scaled copy finishes the job. A single non-power-of-two
     * `inSampleSize` is honoured on API 26+ but not exact, and the arithmetic
     * that decides it is more code than the scaled copy it saves. A source
     * already narrower than the cap is returned as decoded — stretching it
     * here would only smooth it.
     */
    private fun decode(context: Context): Bitmap {
        // One file, both palettes. What differs between day and night is the *veil*
        // over this image, not the image — see [veilFor] — so a second file would have
        // nothing of its own to say. This is the photograph the app shipped with, and
        // it is the only texture in a UI that is otherwise flat vector shapes.
        val id = com.moss.app.R.drawable.backdrop
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, id, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_W) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = requireNotNull(BitmapFactory.decodeResource(context.resources, id, opts)) {
            "backdrop.jpg did not decode"
        }
        if (raw.width <= TARGET_W) return raw
        val h = max(1, (raw.height * TARGET_W.toFloat() / raw.width).roundToInt())
        val scaled = Bitmap.createScaledBitmap(raw, TARGET_W, h, true)
        if (scaled != raw) raw.recycle()
        return scaled
    }

    /** Rec. 709 relative luminance, averaged. Worker thread only. */
    private fun meanLuma(px: IntArray): Float {
        var sum = 0.0
        for (c in px) {
            sum += (0.2126 * ((c shr 16) and 0xFF) +
                0.7152 * ((c shr 8) and 0xFF) +
                0.0722 * (c and 0xFF)) / 255.0
        }
        return (sum / px.size).toFloat()
    }
}
