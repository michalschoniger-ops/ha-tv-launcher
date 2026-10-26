package org.hometv.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A short native glass notice; its camera is a bounded snapshot preview, never another RTC peer. */
class NativeNoticeCard(
    private val activity: Activity,
    private val isDark: () -> Boolean = { activity.getSharedPreferences("native_tv", Context.MODE_PRIVATE).getBoolean("resolvedDark", true) },
    private val onOpenDoor: (() -> Unit)? = null,
    private val openCamera: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val images = NativeDashboardRepository()
    private val decoder = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1), ThreadPoolExecutor.DiscardOldestPolicy())
    private var visual: NativeNoticeVisuals.Parts? = null
    private var camera: String? = null
    private var epoch = 0L
    private var until = 0L
    private var destroyed = false
    private var acceptedDown = false
    private var doorAvailable = false
    private var doorSelected = false
    private var dark = true

    fun show(title: String, message: String, cameraId: String?, durationSeconds: Int? = null) {
        close()
        if (destroyed) return
        val id = cameraId?.takeIf(NativeDoorbellPolicy::cameraId)
        camera = id; dark = isDark()
        val ticket = epoch
        until = SystemClock.uptimeMillis() + NativeNoticeVisuals.durationSeconds(id, durationSeconds) * 1000L
        val host = activity.findViewById<FrameLayout>(android.R.id.content)
        val parts = NativeNoticeVisuals.create(activity, title, message, id, dark)
        visual = parts
        parts.cameraAction?.setOnClickListener { if (ticket == epoch) acceptCamera() }
        parts.doorAction?.setOnClickListener { if (ticket == epoch) acceptDoor() }
        host.addView(parts.body, FrameLayout.LayoutParams(NativeNoticeVisuals.px(activity, 390f), -2, Gravity.TOP or Gravity.END).apply {
            topMargin = NativeNoticeVisuals.px(activity, 90f); rightMargin = NativeNoticeVisuals.px(activity, 28f)
        })
        NativeNoticeVisuals.enter(parts.body)
        handler.postDelayed({ if (ticket == epoch) close() }, (until - SystemClock.uptimeMillis()).coerceAtLeast(1))
        if (id == "entrance") images.requestJson("GET", "/api/door/status") { result ->
            if (ticket != epoch || destroyed || SystemClock.uptimeMillis() >= until) return@requestJson
            val state = result.getOrNull()
            doorAvailable = onOpenDoor != null && state?.opt("available") == true && state.optString("cameraId") == "entrance"
            parts.doorReason?.text = if (doorAvailable) "Otevření ještě potvrdíš." else state?.optString("reason")?.takeIf { it.isNotBlank() }?.take(180) ?: "Stav dveří není ověřený."
            updateActions()
        }
        if (id != null && parts.picture != null) refreshImage(id, parts.picture, ticket)
    }

    private fun updateActions() {
        visual?.let { parts ->
            parts.cameraAction?.let { NativeNoticeVisuals.actionState(it, !doorSelected, true, dark) }
            parts.doorAction?.let { NativeNoticeVisuals.actionState(it, doorSelected, doorAvailable, dark) }
        }
    }
    private fun acceptCamera() { val id = camera ?: return; if (SystemClock.uptimeMillis() >= until) return; close(); openCamera(id) }
    private fun acceptDoor() { if (camera != "entrance" || !doorAvailable || SystemClock.uptimeMillis() >= until) return; close(); onOpenDoor?.invoke() }

    private fun refreshImage(id: String, picture: ImageView, ticket: Long) {
        if (ticket != epoch || destroyed || SystemClock.uptimeMillis() >= until) return
        images.snapshot(id) { result ->
            if (ticket != epoch || destroyed) return@snapshot
            val bytes = result.getOrNull()
            if (bytes == null || bytes.size > 2 * 1024 * 1024) { scheduleImage(id, picture, ticket); return@snapshot }
            runCatching { decoder.execute {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                val valid = bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384 && bounds.outWidth.toLong() * bounds.outHeight <= 64000000L
                if (valid) while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 720) sample *= 2
                val bitmap = if (valid) BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) else null
                handler.post {
                    if (ticket != epoch || destroyed || SystemClock.uptimeMillis() >= until) bitmap?.recycle() else {
                        if (bitmap != null) { picture.setImageBitmap(bitmap); visual?.pictureStatus?.text = "Náhled · obnovuje se po 1 s" }
                        scheduleImage(id, picture, ticket)
                    }
                }
            } }.onFailure { scheduleImage(id, picture, ticket) }
        }
    }
    private fun scheduleImage(id: String, picture: ImageView, ticket: Long) { if (ticket == epoch && !destroyed) handler.postDelayed({ refreshImage(id, picture, ticket) }, 1000) }

    fun handleKey(event: KeyEvent): Boolean {
        if (camera == null || visual == null) return false
        if (event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
            if (event.action == KeyEvent.ACTION_DOWN) { doorSelected = doorAvailable && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN); updateActions() }
            return true
        }
        if (event.keyCode !in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !event.isCanceled && event.keyCode != KeyEvent.KEYCODE_BACK) acceptedDown = true
        if (event.action == KeyEvent.ACTION_UP) {
            val accept = acceptedDown && !event.isCanceled
            acceptedDown = false
            if (!event.isCanceled && event.keyCode == KeyEvent.KEYCODE_BACK) close()
            else if (accept) { if (doorSelected) acceptDoor() else acceptCamera() }
        }
        return true
    }
    fun close() {
        epoch++; acceptedDown = false; doorAvailable = false; doorSelected = false
        handler.removeCallbacksAndMessages(null); images.cancelPending()
        visual?.body?.let { it.animate().cancel(); (it.parent as? ViewGroup)?.removeView(it) }
        visual = null; camera = null
    }
    fun onDestroy() { destroyed = true; close(); images.close(); decoder.shutdownNow() }
}

/** Shared day/night Apple-style native notice surface, used inside and above other TV apps. */
internal object NativeNoticeVisuals {
    data class Parts(val body: LinearLayout, val picture: ImageView?, val pictureStatus: TextView?, val cameraAction: TextView?, val doorAction: TextView?, val doorReason: TextView?)
    fun px(context: Context, value: Float) = (value * context.resources.displayMetrics.widthPixels / 1440f).roundToInt()
    fun durationSeconds(cameraId: String?, requested: Int?) = (requested ?: if (cameraId == null) 8 else 12).coerceIn(3, if (cameraId == null) 120 else 12)
    private fun ink(dark: Boolean) = if (dark) Color.rgb(246, 247, 250) else Color.rgb(36, 40, 47)
    private fun muted(dark: Boolean) = if (dark) Color.rgb(183, 191, 204) else Color.rgb(102, 110, 121)
    private fun shape(context: Context, colors: IntArray, radius: Float, border: Int = Color.TRANSPARENT) = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
        cornerRadius = px(context, radius).toFloat(); if (border != Color.TRANSPARENT) setStroke(px(context, 1f).coerceAtLeast(1), border)
    }
    private fun label(context: Context, value: String, size: Float, color: Int, lines: Int, medium: Boolean = false) = TextView(context).apply {
        text = value; setTextSize(TypedValue.COMPLEX_UNIT_PX, px(context, size).toFloat()); setTextColor(color)
        maxLines = lines; includeFontPadding = false; ellipsize = TextUtils.TruncateAt.END
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL); isFocusable = false
    }
    fun create(context: Context, title: String, message: String, cameraId: String?, dark: Boolean): Parts {
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(context, 18f), px(context, 18f), px(context, 18f), px(context, 16f))
            background = shape(context, if (dark) intArrayOf(Color.argb(244, 56, 60, 69), Color.argb(248, 30, 34, 41)) else intArrayOf(Color.argb(246, 252, 253, 255), Color.argb(239, 233, 238, 245)), 24f, if (dark) Color.argb(65, 237, 243, 255) else Color.argb(230, 255, 255, 255))
            elevation = px(context, 12f).toFloat(); isFocusable = false
            contentDescription = if (cameraId == null) "Oznámení domácnosti" else "Zvonek; OK otevře kameru; Zpět zavře náhled"
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val brand = ImageView(context).apply {
            background = shape(context, intArrayOf(Color.rgb(105, 160, 244), Color.rgb(52, 102, 210)), 13f)
            setImageDrawable(Glyph(cameraId != null)); setPadding(px(context, 9f), px(context, 9f), px(context, 9f), px(context, 9f)); isFocusable = false
        }
        header.addView(brand, LinearLayout.LayoutParams(px(context, 40f), px(context, 40f)))
        val headings = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        headings.addView(label(context, if (cameraId == null) "HA TV LAUNCHER" else "HA TV LAUNCHER · ZVONEK", 10f, muted(dark), 1, true))
        headings.addView(label(context, title.take(120), 19f, ink(dark), 2, true).apply { setPadding(0, px(context, 5f), 0, 0) })
        header.addView(headings, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(context, 12f) }); body.addView(header)
        if (message.isNotBlank()) body.addView(label(context, message.take(1000), 14f, muted(dark), 3).apply { setPadding(0, px(context, 12f), 0, 0) })
        var picture: ImageView? = null; var pictureStatus: TextView? = null; var cameraAction: TextView? = null; var doorAction: TextView? = null; var doorReason: TextView? = null
        if (cameraId != null) {
            picture = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; background = shape(context, intArrayOf(Color.rgb(12, 16, 23), Color.rgb(12, 16, 23)), 16f); clipToOutline = true; isFocusable = false }
            body.addView(picture, LinearLayout.LayoutParams(-1, px(context, 354f * 9f / 16f)).apply { topMargin = px(context, 14f) })
            pictureStatus = label(context, "Načítám pohled z kamery…", 11f, muted(dark), 1).apply { setPadding(0, px(context, 9f), 0, 0) }; body.addView(pictureStatus)
            val actions = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            cameraAction = label(context, "Otevřít kameru", 12f, ink(dark), 1, true).apply { gravity = Gravity.CENTER; setPadding(px(context, 12f), px(context, 11f), px(context, 12f), px(context, 11f)) }
            actionState(cameraAction, true, true, dark); actions.addView(cameraAction, LinearLayout.LayoutParams(0, -2, 1f))
            if (cameraId == "entrance") {
                doorAction = label(context, "Otevřít dveře", 12f, muted(dark), 1, true).apply { gravity = Gravity.CENTER; setPadding(px(context, 12f), px(context, 11f), px(context, 12f), px(context, 11f)) }
                actionState(doorAction, false, false, dark); actions.addView(doorAction, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(context, 8f) })
            }
            body.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(context, 12f) })
            if (cameraId == "entrance") { doorReason = label(context, "Ověřuji stav dveří…", 11f, muted(dark), 2).apply { setPadding(0, px(context, 8f), 0, 0) }; body.addView(doorReason) }
            body.addView(label(context, "OK vybrat · Zpět zavřít", 10f, muted(dark), 1).apply { setPadding(0, px(context, 10f), 0, 0) })
        }
        return Parts(body, picture, pictureStatus, cameraAction, doorAction, doorReason)
    }
    fun actionState(view: TextView, selected: Boolean, enabled: Boolean, dark: Boolean) {
        view.isEnabled = enabled; view.alpha = if (enabled) 1f else .48f
        view.setTextColor(if (enabled) ink(dark) else muted(dark))
        val color = if (selected && enabled) if (dark) Color.argb(90, 200, 219, 248) else Color.argb(242, 255, 255, 255) else if (dark) Color.argb(34, 210, 224, 247) else Color.argb(95, 255, 255, 255)
        view.background = shape(view.context, intArrayOf(color, color), 14f)
    }
    fun enter(view: View) {
        view.animate().cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) { view.alpha = 1f; view.translationY = 0f; return }
        view.alpha = 0f; view.translationY = -px(view.context, 10f).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
    }
    private class Glyph(private val camera: Boolean) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.65f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        override fun draw(canvas: Canvas) {
            val save = canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
            val path = Path()
            if (camera) { canvas.drawRoundRect(2f, 5f, 16f, 20f, 3f, 3f, paint); path.moveTo(16f, 10f); path.lineTo(22f, 6f); path.lineTo(22f, 19f); path.lineTo(16f, 15f); canvas.drawPath(path, paint) }
            else { path.moveTo(5f, 17f); path.lineTo(7f, 14f); path.lineTo(7f, 9f); path.cubicTo(7f, 2f, 17f, 2f, 17f, 9f); path.lineTo(17f, 14f); path.lineTo(19f, 17f); path.close(); canvas.drawPath(path, paint); canvas.drawLine(10f, 21f, 14f, 21f, paint) }
            canvas.restoreToCount(save)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
