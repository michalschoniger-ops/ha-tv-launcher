package org.hometv.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Paired household notices; a doorbell opens its validated native camera only after explicit OK. */
class TvOverlayService : Service() {
    companion object {
        private const val CHANNEL = "household_overlay"
        private const val NOTICE_ID = 4317
        private const val ACTION_STOP = "org.hometv.launcher.overlay.STOP"
        private const val ACTION_HIDE = "org.hometv.launcher.overlay.HIDE"
        private const val IMAGE_LIMIT = 2 * 1024 * 1024
        @Volatile var dashboardVisible = false
        @Volatile var foregroundNotice: ((String, String, String?) -> Unit)? = null
        @Volatile var foregroundNoticeDetails: ((String, String, String?, Int) -> Unit)? = null
        @Volatile var foregroundMedia: ((String, String, String) -> Unit)? = null
        @Volatile var foregroundSettings: ((JSONObject) -> Unit)? = null
        @Volatile var foregroundPower: ((JSONObject) -> Unit)? = null
        @Volatile var foregroundPowerStatus: ((JSONObject) -> Unit)? = null
        @Volatile private var running = false
        @Volatile private var rejectedCookie: String? = null

        fun enabled(context: Context) = context.getSharedPreferences("tv_overlay", Context.MODE_PRIVATE).getBoolean("enabled", true)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences("tv_overlay", Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
            if (enabled) startIfAuthorized(context) else context.stopService(Intent(context, TvOverlayService::class.java))
        }

        /** Called only by the visible Activity, never a boot/background-start exemption. */
        fun startIfAuthorized(context: Context) {
            if (!dashboardVisible || running || !enabled(context) || !Settings.canDrawOverlays(context)) return
            val session = OverlayProtocol.sessionCookie(CookieManager.getInstance().getCookie(BuildConfig.DASHBOARD_URL)) ?: return
            if (session == rejectedCookie) return
            runCatching {
                val intent = Intent(context, TvOverlayService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            }.onFailure { Log.w("HomeTVOverlay", "service_start_unavailable") }
        }

        fun activityVisible(context: Context) {
            dashboardVisible = true
            if (running) runCatching { context.startService(Intent(context, TvOverlayService::class.java).setAction(ACTION_HIDE)) }
        }
    }

    private data class Notice(val id: String, val title: String, val message: String, val cameraId: String?, val durationSeconds: Int, val expiresAt: Long?)
    private data class Seen(val fingerprint: String, val time: Long)
    private data class Envelope(val notice: Notice, val fingerprint: String)
    private val ui = Handler(Looper.getMainLooper())
    private val eventWorker = Executors.newSingleThreadExecutor()
    private val doorStatusRepository = NativeDashboardRepository()
    // One in-flight snapshot and at most one newest pending doorbell; event
    // bursts cannot create an unbounded executor queue or parallel camera fetches.
    private val imageWorker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(1), ThreadPoolExecutor.DiscardOldestPolicy())
    private val listening = AtomicBoolean(false)
    private val uiScheduled = AtomicBoolean(false)
    private val eventQueue = ArrayBlockingQueue<Envelope>(8)
    private val seen = LinkedHashMap<String, Seen>()
    private val pending = ArrayDeque<Notice>()
    @Volatile private var stopping = false
    @Volatile private var cookie: String? = null
    @Volatile private var eventConnection: HttpURLConnection? = null
    @Volatile private var imageConnection: HttpURLConnection? = null
    @Volatile private var generation = 0
    private lateinit var windows: WindowManager
    private var overlay: LinearLayout? = null
    private var cameraView: ImageView? = null
    private var cameraStatus: TextView? = null
    private var activeCamera: String? = null
    private var cameraSelectDown = false
    private var activeDoorAvailable = false
    private var doorSelected = false
    private var noticeVisual: NativeNoticeVisuals.Parts? = null
    private var noticeDark = true
    private var overlayUntil = 0L
    private val deliver = object : Runnable {
        override fun run() {
            // Bound work per frame so a burst does not block the remote/UI thread.
            repeat(4) { eventQueue.poll()?.let { present(it.notice, it.fingerprint) } }
            uiScheduled.set(false)
            if (!stopping && eventQueue.isNotEmpty() && uiScheduled.compareAndSet(false, true)) ui.postDelayed(this, 16)
        }
    }
    private val hide = Runnable {
        hideOverlay()
        if (!dashboardVisible && pending.isNotEmpty()) showNotice(pending.removeLast())
        pending.clear()
    }

    override fun onCreate() {
        super.onCreate()
        windows = getSystemService(WindowManager::class.java)
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Domácí oznámení", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Spojení s HA TV Launcherem pro oznámení a zvonek přes ostatní aplikace."
                setSound(null, null); enableVibration(false); setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, TvOverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        @Suppress("DEPRECATION")
        val notification = builder.setSmallIcon(R.drawable.overlay_status_icon).setContentTitle("HA TV Launcher")
            .setContentText("Zobrazení oznámení a zvonku přes TV aplikace.").setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE).setPriority(Notification.PRIORITY_MIN).setContentIntent(open)
            .addAction(R.drawable.overlay_status_icon, "Zastavit", stop).build()
        try {
            when {
                Build.VERSION.SDK_INT >= 34 -> startForeground(NOTICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                Build.VERSION.SDK_INT >= 29 -> startForeground(NOTICE_ID, notification, 0)
                else -> startForeground(NOTICE_ID, notification)
            }
            running = true
        } catch (_: RuntimeException) {
            Log.w("HomeTVOverlay", "foreground_service_unavailable")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { setEnabled(this, false); stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_HIDE || dashboardVisible) { pending.clear(); hideOverlay() }
        cookie = OverlayProtocol.sessionCookie(CookieManager.getInstance().getCookie(BuildConfig.DASHBOARD_URL))
        if (!running || cookie == null || !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        if (listening.compareAndSet(false, true)) eventWorker.execute { listen() }
        // A force-stop/process kill is a backstop. Opening the launcher starts it again.
        return START_NOT_STICKY
    }

    private fun connection(uri: URI, timeout: Int): HttpURLConnection = (uri.toURL().openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 5000; readTimeout = timeout
        requestMethod = "GET"
        setRequestProperty("Cookie", cookie ?: throw IOException("No paired session"))
        setRequestProperty("User-Agent", "HomeTVOverlay/0.4.0")
    }

    private fun listen() {
        var retry = 2000L
        while (!stopping && !Thread.currentThread().isInterrupted) {
            var current: HttpURLConnection? = null
            try {
                current = connection(OverlayProtocol.eventsUrl(BuildConfig.DASHBOARD_URL), 30000)
                eventConnection = current
                current.setRequestProperty("Accept", "text/event-stream")
                val status = current.responseCode
                if (status == 401 || status == 403) { rejectedCookie = cookie; ui.post { stopSelf() }; return }
                if (status != 200 || !current.contentType.orEmpty().startsWith("text/event-stream", true)) throw IOException("Event connection unavailable")
                retry = 2000L
                current.inputStream.use { input -> OverlayProtocol.readEvents(input) { event -> receive(event) } }
            } catch (_: Exception) {
                // Never log response bodies, private notices, host addresses or cookies.
            } finally {
                current?.disconnect(); eventConnection = null
                ui.post {
                    if (!stopping && dashboardVisible) foregroundPowerStatus?.invoke(JSONObject()
                        .put("configured", true).put("connected", false).put("state", "disconnected"))
                }
            }
            if (stopping) return
            try { Thread.sleep(retry) } catch (_: InterruptedException) { return }
            retry = minOf(30000L, retry * 2)
        }
    }

    private fun text(value: String, max: Int) = value.replace(Regex("[\\u0000-\\u001f\\u007f]"), " ").take(max).trim()

    private fun receive(event: OverlayProtocol.Event) {
        if (event.type in setOf("client_settings", "power", "power_status")) {
            val payload = runCatching { JSONObject(event.data) }.getOrNull() ?: return
            ui.post {
                if (!stopping && dashboardVisible) when (event.type) {
                    "client_settings" -> foregroundSettings?.invoke(payload)
                    "power" -> foregroundPower?.invoke(payload)
                    "power_status" -> foregroundPowerStatus?.invoke(payload)
                }
            }
            return // State/control envelopes must never become blank household notices.
        }
        if (event.type == "play") {
            val payload = runCatching { JSONObject(event.data) }.getOrNull() ?: return
            val url = payload.optString("url").take(2048)
            val type = payload.optString("type").take(80)
            val title = text(payload.optString("title", "Média"), 160)
            // Only the visible home may start playback. Never take over another TV app.
            ui.post { if (!stopping && dashboardVisible) foregroundMedia?.invoke(url, type, title) }
            return
        }
        val notice = runCatching {
            val data = JSONObject(event.data)
            val cameraId = if (event.type == "doorbell") data.optString("cameraId").also {
                if (!NativeDoorbellPolicy.cameraId(it)) return
                OverlayProtocol.snapshotUrl(BuildConfig.DASHBOARD_URL, it)
            } else null
            val title = text(data.optString("title", if (cameraId == null) "HA TV Launcher" else "Někdo zvoní"), 120)
            val message = text(data.optString("message"), 1000)
            if (cameraId == null && message.isBlank()) return
            val id = text(data.optString("id", "${event.type}-$cameraId-$title-$message"), 160)
            val expiresAt = data.optString("expiresAt").takeIf { it.isNotBlank() }?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
            if (expiresAt != null && expiresAt <= System.currentTimeMillis()) return
            val requestedSeconds = if (expiresAt != null) kotlin.math.ceil((expiresAt - System.currentTimeMillis()) / 1000.0).toInt()
                else (data.opt("durationSeconds") as? Number)?.toDouble()?.takeIf { it.isFinite() }?.toInt()
            Notice(id, title, message, cameraId, NativeNoticeVisuals.durationSeconds(cameraId, requestedSeconds), expiresAt)
        }.getOrNull() ?: return
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(event.data.toByteArray()).joinToString("") { "%02x".format(it) }
        if (stopping) return
        val envelope = Envelope(notice, fingerprint)
        if (!eventQueue.offer(envelope)) { eventQueue.poll(); eventQueue.offer(envelope) }
        if (uiScheduled.compareAndSet(false, true)) ui.post(deliver)
    }

    private fun present(notice: Notice, fingerprint: String) {
        if (stopping || notice.expiresAt?.let { it <= System.currentTimeMillis() } == true) return
        val now = SystemClock.elapsedRealtime()
        val previous = seen[notice.id]
        if (previous?.fingerprint == fingerprint && now - previous.time < 30000) return
        seen[notice.id] = Seen(fingerprint, now)
        while (seen.size > 128) seen.remove(seen.keys.first())
        if (dashboardVisible) {
            pending.clear(); hideOverlay()
            val details = foregroundNoticeDetails
            if (details != null) details.invoke(notice.title, notice.message, notice.cameraId, notice.durationSeconds)
            else foregroundNotice?.invoke(notice.title, notice.message, notice.cameraId)
            return
        }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        if (notice.cameraId == null && activeCamera != null) {
            if (pending.size == 3) pending.removeFirst()
            pending.addLast(notice)
        } else showNotice(notice)
    }

    @Suppress("DEPRECATION")
    private fun showNotice(notice: Notice) {
        if (notice.expiresAt?.let { it <= System.currentTimeMillis() } == true) return
        hideOverlay()
        if (stopping || dashboardVisible) return
        noticeDark = getSharedPreferences("native_tv", Context.MODE_PRIVATE).getBoolean("resolvedDark", true)
        val parts = NativeNoticeVisuals.create(this, notice.title, notice.message, notice.cameraId, noticeDark)
        val card = parts.body
        noticeVisual = parts; cameraView = parts.picture; cameraStatus = parts.pictureStatus
        if (notice.cameraId != null) {
            card.isFocusable = true; card.isFocusableInTouchMode = true
            parts.cameraAction?.setOnClickListener { openDoorbellCamera(notice.cameraId) }
            parts.doorAction?.setOnClickListener { openDoorConfirmation() }
            card.setOnKeyListener { _, key, event ->
                when (key) {
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                        if (event.action == KeyEvent.ACTION_UP) { pending.clear(); hideOverlay() }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (event.action == KeyEvent.ACTION_DOWN) { doorSelected = activeDoorAvailable && key in listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN); updateNoticeActions() }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !event.isCanceled) cameraSelectDown = true
                        if (event.action == KeyEvent.ACTION_UP) {
                            val accept = cameraSelectDown && !event.isCanceled
                            cameraSelectDown = false
                            if (accept) { if (doorSelected) openDoorConfirmation() else openDoorbellCamera(notice.cameraId) }
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or (if (notice.cameraId == null) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)
        val params = WindowManager.LayoutParams(NativeNoticeVisuals.px(this, 390f), WindowManager.LayoutParams.WRAP_CONTENT, type, flags,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; x = NativeNoticeVisuals.px(this@TvOverlayService, 28f); y = NativeNoticeVisuals.px(this@TvOverlayService, 90f); title = "HA TV Launcher oznámení" }
        try { windows.addView(card, params); overlay = card; if (notice.cameraId != null) card.requestFocus(); NativeNoticeVisuals.enter(card) }
        catch (_: RuntimeException) { cameraView = null; cameraStatus = null; noticeVisual = null; Log.w("HomeTVOverlay", "overlay_window_unavailable"); return }
        activeCamera = notice.cameraId
        val token = generation
        val remainingSeconds = notice.expiresAt?.let { kotlin.math.ceil((it - System.currentTimeMillis()) / 1000.0).toInt() }
        val duration = NativeNoticeVisuals.durationSeconds(notice.cameraId, remainingSeconds ?: notice.durationSeconds) * 1000L
        overlayUntil = SystemClock.elapsedRealtime() + duration
        ui.postDelayed(hide, duration)
        if (notice.cameraId == "entrance") doorStatusRepository.requestJson("GET", "/api/door/status") { result ->
            if (stopping || dashboardVisible || generation != token || overlay == null || SystemClock.elapsedRealtime() >= overlayUntil) return@requestJson
            val state = result.getOrNull()
            activeDoorAvailable = state?.opt("available") == true && state.optString("cameraId") == "entrance"
            parts.doorReason?.text = if (activeDoorAvailable) "Otevření ještě potvrdíš." else state?.optString("reason")?.takeIf { it.isNotBlank() }?.take(180) ?: "Stav dveří není ověřený."
            updateNoticeActions()
        }
        if (notice.cameraId != null) {
            val until = overlayUntil
            imageWorker.execute { snapshots(notice.cameraId, token, until) }
        }
    }

    private fun updateNoticeActions() {
        noticeVisual?.let { parts ->
            parts.cameraAction?.let { NativeNoticeVisuals.actionState(it, !doorSelected, true, noticeDark) }
            parts.doorAction?.let { NativeNoticeVisuals.actionState(it, doorSelected, activeDoorAvailable, noticeDark) }
        }
    }

    private fun snapshots(cameraId: String, token: Int, until: Long) {
        while (!stopping && !dashboardVisible && generation == token && SystemClock.elapsedRealtime() < until) {
            var current: HttpURLConnection? = null
            var bitmap: Bitmap? = null
            try {
                current = connection(OverlayProtocol.snapshotUrl(BuildConfig.DASHBOARD_URL, cameraId), 7000)
                imageConnection = current
                if (current.responseCode != 200 || current.contentLengthLong > IMAGE_LIMIT ||
                    current.contentType.orEmpty().substringBefore(';').lowercase() !in setOf("image/jpeg", "image/png", "image/webp")) throw IOException("Camera unavailable")
                val output = ByteArrayOutputStream()
                current.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer); if (read == -1) break
                        if (output.size() + read > IMAGE_LIMIT) throw IOException("Camera image exceeds limit")
                        output.write(buffer, 0, read)
                    }
                }
                val bytes = output.toByteArray()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 64000000L) throw IOException("Invalid image dimensions")
                var sample = 1
                while (bounds.outWidth / sample > 960 || bounds.outHeight / sample > 720) sample *= 2
                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw IOException("Invalid camera image")
            } catch (_: Exception) { /* The existing picture stays visible during a temporary camera failure. */ }
            finally { current?.disconnect(); imageConnection = null }
            val image = bitmap
            ui.post {
                if (!stopping && !dashboardVisible && generation == token && overlay != null && SystemClock.elapsedRealtime() < until) {
                    if (image != null) { cameraView?.setImageBitmap(image); cameraStatus?.text = "Náhled · obnovuje se po 1 s" }
                    else cameraStatus?.text = "Obraz kamery čeká na připojení…"
                } else image?.recycle()
            }
            try { Thread.sleep(NativeDoorbellPolicy.SNAPSHOT_INTERVAL_MS) } catch (_: InterruptedException) { return }
        }
    }

    private fun hideOverlay() {
        generation++
        ui.removeCallbacks(hide)
        imageConnection?.disconnect(); imageConnection = null
        doorStatusRepository.cancelPending()
        cameraView?.setImageDrawable(null)
        overlay?.let { it.animate().cancel(); runCatching { windows.removeView(it) } }
        overlay = null; cameraView = null; cameraStatus = null; activeCamera = null; cameraSelectDown = false
        activeDoorAvailable = false; doorSelected = false; noticeVisual = null; overlayUntil = 0L
    }

    private fun openDoorbellCamera(id: String) {
        if (!NativeDoorbellPolicy.cameraId(id) || activeCamera != id || dashboardVisible || stopping || SystemClock.elapsedRealtime() >= overlayUntil) return
        pending.clear(); hideOverlay()
        // No arbitrary URL/package/action is accepted. Closing the preview alone
        // never starts an Activity or changes the application being watched.
        runCatching { startActivity(Intent(this, MainActivity::class.java)
            .putExtra("open_camera", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            .onFailure { Log.w("HomeTVOverlay", "camera_open_unavailable") }
    }

    private fun openDoorConfirmation() {
        if (activeCamera != "entrance" || !activeDoorAvailable || dashboardVisible || stopping || SystemClock.elapsedRealtime() >= overlayUntil) return
        pending.clear(); hideOverlay()
        // User-selected preparation/confirmation only. The service cannot actuate a lock.
        runCatching { startActivity(Intent(this, MainActivity::class.java)
            .putExtra("open_door", "entrance").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            .onFailure { Log.w("HomeTVOverlay", "door_confirmation_unavailable") }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopping = true; running = false
        eventConnection?.disconnect(); imageConnection?.disconnect()
        eventWorker.shutdownNow(); imageWorker.shutdownNow(); doorStatusRepository.close()
        ui.removeCallbacksAndMessages(null); eventQueue.clear(); pending.clear(); seen.clear(); hideOverlay()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
