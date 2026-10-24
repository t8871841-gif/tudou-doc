package com.jarvis.app.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager

/**
 * 批十四移植自 Andee 的 screen/ScreenCapture（实现原样，只改包名、虚拟屏名与
 * 模型可见文案的身份）：不用无障碍的屏幕像素通道（MediaProjection）。
 *
 * **为什么存在。** 读屏的其余一切都走无障碍服务：元素树靠 rootInActiveWindow，
 * 截图靠 takeScreenshot。一旦 ROM 把无障碍收走，助手还能说话、还能记事，却彻底
 * 看不见。MediaProjection 是平台的另一条路：用户授权一次，之后无需无障碍即可
 * 抓屏。
 *
 * **它不是替代品，不许冒充。** 无障碍截图是「富」的：带瞄准网格、附带元素表、
 * 有 shot_id 把图和下一次点击绑在一起——三者都服务于点击，而没有无障碍就没有
 * 点击（dispatchGesture 没有非 root 替代）。所以录屏图的诚实形态只有「这是像
 * 素，尺寸如此」：能回答屏幕上有什么，不能拿来瞄准。调用方必须标明来源
 * （[SOURCE]），不许让模型以为手里这张图可以按图操作。
 *
 * **授权是调用方的事。** createScreenCaptureIntent() 必须从 Activity 发起，
 * 结果再交回 [start]。没有办法从服务里要，也没有办法静默要：Android 14 起授权
 * 按会话计，重启后要重新找用户。这正是它只能当兜底的原因。
 *
 * 接线现状（批十四）：本类以 [shared] 单例持有；TaskLoop 在无障碍截图拿不到图
 * 时用它兜底（隐私黑名单包两条通道都不拍）。授权的发起端（Activity 拉起系统
 * 授权框、把结果喂给 [start]）尚未接进 MainActivity——见 V3_CHANGES 批十四。
 */
class ScreenCapture(private val context: Context) {

    private val lock = Any()
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var width = 0
    private var height = 0

    /** Whether a capture will work right now. Free to call. */
    fun isAvailable(): Boolean = synchronized(lock) { reader != null && projection != null }

    /**
     * Take the consent result, and build a capture from it.
     *
     * Returns false rather than throwing: the two ways this fails — the user
     * refused, or the platform refused to hand over a projection for a result
     * code we liked — are both "no capture today", and neither is a fault the
     * caller can act on differently.
     */
    fun start(resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.i(TAG, "screen capture not granted (resultCode=$resultCode)")
            return false
        }
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as? MediaProjectionManager ?: return false
        val fresh = runCatching { mgr.getMediaProjection(resultCode, data) }
            .onFailure { Log.w(TAG, "getMediaProjection refused", it) }
            .getOrNull() ?: return false
        // Registered before anything is created from it. Android 14 requires a
        // callback (an app that stops the projection without one is killed),
        // and without a callback here a projection the *user* stops — from the
        // privacy chip — would leave us holding a dead one and reporting
        // "available" forever.
        fresh.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "projection stopped by the system or the user")
                release()
            }
        }, Handler(Looper.getMainLooper()))

        val bounds = context.getSystemService(WindowManager::class.java)
            .currentWindowMetrics.bounds
        val w = bounds.width()
        val h = bounds.height()
        val dpi = context.resources.displayMetrics.densityDpi
        synchronized(lock) {
            releaseLocked()
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, IMAGE_BUFFER)
            val d = runCatching {
                fresh.createVirtualDisplay(
                    "jarvis-capture", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    r.surface, null, Handler(Looper.getMainLooper()),
                )
            }.onFailure { Log.w(TAG, "createVirtualDisplay failed", it) }.getOrNull()
            if (d == null) {
                runCatching { r.close() }
                runCatching { fresh.stop() }
                return false
            }
            projection = fresh
            display = d
            reader = r
            width = w
            height = h
        }
        return true
    }

    /**
     * The display as it is now, or null if it could not be read.
     *
     * **Blocking — call it from a worker, never the main thread.** The first
     * frame after a projection starts is not there yet (the display needs a
     * beat to produce one), so this waits up to [timeoutMs] for an image rather
     * than returning an empty screen that the brain would describe as blank.
     */
    fun grab(timeoutMs: Long = GRAB_TIMEOUT_MS): Bitmap? {
        val r: ImageReader
        val w: Int
        val h: Int
        synchronized(lock) {
            r = reader ?: return null
            w = width
            h = height
        }
        val image = awaitImage(r, timeoutMs) ?: run {
            Log.w(TAG, "no frame within ${timeoutMs}ms")
            return null
        }
        return try {
            image.use { toBitmap(it, w, h) }
        } catch (t: Throwable) {
            Log.w(TAG, "frame could not be read", t)
            null
        }
    }

    private fun awaitImage(r: ImageReader, timeoutMs: Long): Image? {
        r.acquireLatestImage()?.let { return it }
        // Poll rather than listen: ImageReader.OnImageAvailableListener fires on
        // a handler we do not own, and a latch here would have to be armed
        // before the frame arrived to not lose it. Polling at this interval
        // costs nothing next to the frame the display is producing.
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
            r.acquireLatestImage()?.let { return it }
        }
        return null
    }

    /**
     * One `Image` as an ARGB bitmap.
     *
     * The row stride is not `width * 4`: the producer pads rows, so the buffer
     * is copied into a bitmap that is *at least* as wide as the padding
     * demands and then cropped. Copying straight into `w x h` would shear the
     * image diagonally, which is the kind of wrong that still looks like a
     * screenshot until someone reads text off it.
     */
    private fun toBitmap(image: Image, w: Int, h: Int): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * w
        val padded = Bitmap.createBitmap(
            w + rowPadding / pixelStride,
            h,
            Bitmap.Config.ARGB_8888,
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        if (padded.width == w) return padded
        val exact = Bitmap.createBitmap(padded, 0, 0, w, h)
        padded.recycle()
        return exact
    }

    fun release() {
        synchronized(lock) { releaseLocked() }
    }

    private fun releaseLocked() {
        runCatching { reader?.close() }
        runCatching { display?.release() }
        runCatching { projection?.stop() }
        reader = null
        display = null
        projection = null
    }

    companion object {
        private const val TAG = "ScreenCapture"

        /**
         * 全工程共用一个实例（Andee 里由 BodyCoreService 持有；本工程没有那层
         * 服务，挂在类上）。用 applicationContext 构造，不持有 Activity。
         */
        @Volatile
        private var sharedInstance: ScreenCapture? = null

        fun shared(context: Context): ScreenCapture =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: ScreenCapture(context.applicationContext)
                    .also { sharedInstance = it }
            }

        /**
         * Longest side of the PNG the brain is handed, whatever the source.
         *
         * Shared with `ScreenController`, which had the number to itself until
         * the projection fallback arrived — two capture paths with two different
         * picture sizes for the same tool is a difference the brain would have
         * to learn for no reason.
         */
        const val SHOT_MAX_SIDE = 1280

        /** How many frames the reader may hold before the producer stalls. */
        private const val IMAGE_BUFFER = 2

        /** A frame is not instant after `start`; this is how long we give it. */
        private const val GRAB_TIMEOUT_MS = 2_000L
        private const val POLL_MS = 25L

        /**
         * Which source a capture came from, in the tool result.
         *
         * Spelled out rather than implied, because the two are not
         * interchangeable: an `accessibility` shot is one the brain may aim
         * with, a `media_projection` one is only to look at.
         */
        const val SOURCE = "source"
        const val SOURCE_ACCESSIBILITY = "accessibility"
        const val SOURCE_MEDIA_PROJECTION = "media_projection"

        /** What the brain is told when a projection shot is all it gets. */
        const val PROJECTION_NOTE =
            "Captured with MediaProjection: this is the raw screen, with no aim " +
                "grid and no element list — both of those need Jarvis's accessibility " +
                "service, and so does tapping. You can describe what you see; you " +
                "cannot act on it until accessibility is back on."
    }
}
