package org.hometv.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.EditText
import android.text.InputType
import android.window.OnBackInvokedDispatcher
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Native TV home. HA is a data service; no browser or HTML renders the launcher. */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val preferences by lazy { getSharedPreferences("native_tv", MODE_PRIVATE) }
    private val data = NativeDashboardRepository()
    private val images = NativeDashboardRepository()
    private val commands = NativeDashboardRepository()
    private val cameraRequests = NativeDashboardRepository()
    private val decoder = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(8))
    private lateinit var screen: NativeTvView
    private lateinit var root: FrameLayout
    private lateinit var audio: NativeAudio
    private lateinit var input: TvAssistantInput
    private lateinit var media: NativeMediaController
    private lateinit var cameras: NativeWebRtcController
    private lateinit var dialogs: NativeDialogController
    private lateinit var googleMap: NativeGoogleMapController
    private lateinit var notices: NativeNoticeCard
    private var resumed = false
    private var endpointReady = false
    private var dashboardConnected = false
    private var mapHasCurrentData = false
    private var mapSyncPosted = false
    private var lastMapBounds: RectF? = null
    private val cameraPosters = LinkedHashMap<String, Bitmap>()
    private var vacuumMapBusy = false
    private var vacuumMapKey: String? = null
    private var vacuumMapAttempt = 0L
    private var generation = 0L
    private var loading = false
    private var imageBusy = false
    private var screenshotIndex = 0
    private var profileLoaded = false
    private var photoIndex = 0
    private var photoAt = 0L
    private var lastInteraction = SystemClock.uptimeMillis()
    private var saver = false
    private var bootstrap = JSONObject()
    private var dashboard = JSONObject()
    private var photoList = JSONArray()
    private var appIds = emptyList<String>()
    private var transientNotice: TextView? = null
    private var assistantBusy = false
    private var pendingSpeech: String? = null
    private var assistantEpoch = 0L
    private var assistantDialog: AlertDialog? = null
    private var formDialog: AlertDialog? = null
    private var pendingCamera: String? = null
    private var pendingDoor = false
    private var settingsWrite = 0L
    private var settingsPending = false
    private var menuLong = false
    private val menuHold = Runnable {
        if (resumed) { menuLong = true; dialogs.showControls(); scheduleAuxiliarySync() }
    }
    private val auxiliarySync = Runnable {
        mapSyncPosted = false
        syncGoogleMap()
        maybeLoadVacuumMap()
    }

    private val refresh = object : Runnable {
        override fun run() {
            if (!resumed) return
            refreshData()
            ui.postDelayed(this, 30000)
        }
    }
    private val snapshots = object : Runnable {
        override fun run() {
            if (!resumed) return
            if (!imageBusy && !saver && !media.isOpen() && !cameras.isOpen() && screen.tab in listOf(NativeTvView.Tab.HOME, NativeTvView.Tab.CAMERAS)) {
                val cameras = dashboard.optJSONArray("cameras") ?: JSONArray()
                val available = (0 until cameras.length()).mapNotNull { cameras.optJSONObject(it) }
                    .filter { it.optBoolean("available") }
                val camera = if (screen.tab == NativeTvView.Tab.HOME) available.firstOrNull { it.optString("id") == "entrance" } ?: available.firstOrNull()
                    else available.getOrNull(screenshotIndex++ % available.size.coerceAtLeast(1))
                val id = camera?.optString("id").orEmpty()
                if (id.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) {
                    imageBusy = true
                    val epoch = generation
                    images.snapshot(id) { result ->
                        if (!resumed || epoch != generation) return@snapshot
                        result.onSuccess { decode(it, epoch) { bitmap ->
                            if (!cameraPosters.containsKey(id) && cameraPosters.size >= 4) cameraPosters.remove(cameraPosters.keys.first())
                            cameraPosters[id] = bitmap
                            screen.setCameraBitmap(id, bitmap)
                        } }
                        imageBusy = false
                        ui.postDelayed(this, 1000) // One request at a time; never pile up stale frames.
                    }
                    return
                }
            }
            ui.postDelayed(this, 1000)
        }
    }
    private val clock = object : Runnable {
        override fun run() {
            if (!resumed) return
            // The native video has its own render thread. Do not keep repainting
            // the obscured weather dashboard beneath its SurfaceView.
            if (!media.isOpen() && !cameras.isOpen()) screen.tick()
            val saverDelay = preferences.getInt("saverMinutes", 5).coerceIn(0, 120) * 60000L
            if (saverDelay > 0 && !saver && !media.isOpen() && !cameras.isOpen() && !googleMap.isOpen() && !dialogs.isOpen() && assistantDialog == null && formDialog == null && SystemClock.uptimeMillis() - lastInteraction > saverDelay) startSaver()
            if (saver && SystemClock.uptimeMillis() - photoAt > preferences.getInt("photoIntervalSeconds", 30).coerceIn(10, 3600) * 1000L) nextPhoto()
            if (endpointReady) TvOverlayService.startIfAuthorized(this@MainActivity)
            scheduleAuxiliarySync()
            ui.postDelayed(this, 1000 - (System.currentTimeMillis() % 1000))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        root = FrameLayout(this)
        screen = NativeTvView(this)
        screen.isSoundEffectsEnabled = false
        root.addView(screen, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        audio = NativeAudio(this)
        input = TvAssistantInput(this, { resumed }, { text ->
            if (resumed) sendAssistant(text.take(500))
            else { pendingSpeech = text.take(500); true }
        }, isDark = { darkAppearance() })
        media = NativeMediaController(this, BuildConfig.DASHBOARD_URL, { id, quality, callback ->
            cameraRequests.requestJson("GET", "/api/cameras/$id/stream?quality=$quality") { result ->
                callback(result.map { it.getString("url") })
            }
        }, cancelStreamRequest = { cameraRequests.cancelPending() }, isDark = { darkAppearance() },
            onClosed = { bind(); screen.tick(); if (screen.tab == NativeTvView.Tab.HOME) screen.focusNetflix(); scheduleAuxiliarySync() },
            cameraPoster = { id -> cameraPosters[id]?.takeUnless { it.isRecycled } })
        cameras = NativeWebRtcController(this, BuildConfig.DASHBOARD_URL, isDark = { darkAppearance() },
            onClosed = { bind(); screen.tick(); if (screen.tab == NativeTvView.Tab.HOME) screen.focusNetflix(); scheduleAuxiliarySync() },
            cameraPoster = { id -> cameraPosters[id]?.takeUnless { it.isRecycled } })
        notices = NativeNoticeCard(this, isDark = { darkAppearance() }, onOpenDoor = { dialogs.showDoor() }) { id -> openCamera(id) }
        dialogs = NativeDialogController(this,
            { method, path, body, callback -> commands.requestJson(method, path, body, callback) },
            { preferences.getString("theme", "auto") ?: "auto" },
            { value -> preferences.edit().putString("theme", value).apply(); applyAppearance(); saveSettings(JSONObject().put("theme", value)) },
            { openSettings() }, { input.showChoice() },
            background = { preferences.getString("background", "weather") ?: "weather" },
            onBackground = { value -> preferences.edit().putString("background", value).apply(); applyAppearance(); if (value == "photos") nextPhoto(); saveSettings(JSONObject().put("background", value)) },
            soundEnabled = { preferences.getBoolean("sounds", true) },
            onSoundEnabled = { value -> preferences.edit().putBoolean("sounds", value).apply(); if (!value) audio.invalidate(); saveSettings(JSONObject().put("navigationSounds", value)) },
            onSaver = { startSaver() }, onPair = { showPair() }, isDark = { darkAppearance() },
            controls = { bootstrap.optJSONObject("controls") ?: JSONObject() },
            homeState = { dashboard.optJSONObject("home") ?: JSONObject() },
            domestic = { dashboard.optJSONObject("domestic") ?: JSONObject() },
            onControlRefresh = { refreshData() })
        googleMap = NativeGoogleMapController(this, { darkAppearance() }) {
            if (resumed && !isDestroyed) { screen.requestFocus(); scheduleAuxiliarySync() }
        }
        screen.onAction = { action -> handleAction(action); scheduleAuxiliarySync() }
        screen.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scheduleAuxiliarySync() }
        screen.viewTreeObserver.addOnDrawListener {
            // Draw calculates the Canvas-to-screen transform. Post only changed map geometry;
            // adding the map must not cause an invalidation/relayout feedback loop.
            if (resumed && ::googleMap.isInitialized) {
                val expected = if (auxiliaryBlocked() || googleMap.isOpen()) null else screen.googleMapBounds()
                if (expected != lastMapBounds) scheduleAuxiliarySync()
            }
        }
        applyAppearance()
        appIds = runCatching {
            val saved = JSONArray(preferences.getString("apps", "[]"))
            (0 until saved.length()).map { saved.getString(it) }.filter { NativeLaunchPolicy.isAllowedPackage(it) }
        }.getOrDefault(emptyList())
        bind()
        screen.focusNetflix()
        loadInstalledApps()
        pendingCamera = cameraIntent(intent)
        pendingDoor = intent.getStringExtra("open_door") == "entrance"
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (!notices.handleKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))) {
                    if (googleMap.isOpen()) googleMap.close() else goHome()
                }
            }
        }
        endpointReady = true

    }

    override fun onResume() {
        super.onResume()
        resumed = true; generation++; loading = false; imageBusy = false
        lastInteraction = SystemClock.uptimeMillis()
        audio.resume()
        TvOverlayService.activityVisible(this)
        TvOverlayService.foregroundNotice = { title, message, camera ->
            if (resumed) showNotice(title, message, camera)
        }
        TvOverlayService.foregroundNoticeDetails = { title, message, camera, duration -> if (resumed) showNotice(title, message, camera, duration) }
        TvOverlayService.foregroundMedia = { url, type, title -> if (resumed) { googleMap.close(); cameras.close(); media.openMedia(url, type, title); scheduleAuxiliarySync() } }
        TvOverlayService.foregroundSettings = { value -> if (resumed) applyClientSettings(value) }
        TvOverlayService.foregroundPower = { value -> if (resumed) applyPower(value) }
        TvOverlayService.foregroundPowerStatus = { value ->
            if (resumed && value.opt("connected") == false) {
                val id = dashboard.optJSONObject("domestic")?.optJSONObject("energy")?.optJSONObject("power")?.optString("entityId").orEmpty()
                if (id.isNotBlank()) applyPower(JSONObject().put("entityId", id).put("available", false))
            }
        }
        // A system speech result is delivered after onResume; it can then open its own result dialog.
        goHome()
        maybeOpenPendingCamera()
        if (endpointReady) { ui.post(refresh); ui.post(snapshots) }
        ui.post(clock)
        scheduleAuxiliarySync()
        pendingSpeech?.let { text -> pendingSpeech = null; ui.post { if (resumed) sendAssistant(text) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (intent.getStringExtra("open_door") == "entrance") { pendingDoor = true; if (resumed) { goHome(); maybeOpenPendingCamera() }; return }
        val camera = cameraIntent(intent)
        if (camera != null) { pendingCamera = camera; if (resumed) { goHome(); maybeOpenPendingCamera() } }
        else if (intent.hasCategory(Intent.CATEGORY_HOME)) goHome()
    }

    override fun onPause() {
        resumed = false; generation++
        settingsWrite++; settingsPending = false
        ui.removeCallbacksAndMessages(null)
        mapSyncPosted = false; lastMapBounds = null; vacuumMapBusy = false
        TvOverlayService.foregroundNotice = null
        TvOverlayService.foregroundNoticeDetails = null
        TvOverlayService.foregroundMedia = null
        TvOverlayService.foregroundSettings = null
        TvOverlayService.foregroundPower = null
        TvOverlayService.foregroundPowerStatus = null
        TvOverlayService.dashboardVisible = false
        media.onPause(); cameras.onPause(); dialogs.onPause(); googleMap.onPause()
        notices.close()
        assistantDialog?.dismiss(); assistantDialog = null
        formDialog?.dismiss(); formDialog = null
        transientNotice?.let { root.removeView(it) }; transientNotice = null
        audio.pause()
        data.cancelPending(); images.cancelPending(); commands.cancelPending(); cameraRequests.cancelPending()
        loading = false; imageBusy = false; assistantBusy = false
        super.onPause()
    }

    override fun onDestroy() {
        data.close(); images.close(); commands.close(); cameraRequests.close(); decoder.shutdownNow()
        media.onDestroy(); cameras.onDestroy(); dialogs.onDestroy(); googleMap.onDestroy(); notices.onDestroy(); audio.close()
        cameraPosters.clear()
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && resumed && ::screen.isInitialized && ::dialogs.isInitialized &&
            !dialogs.isOpen() && !media.isOpen() && !cameras.isOpen() && !googleMap.isOpen() && assistantDialog == null && formDialog == null && !saver && screen.tab == NativeTvView.Tab.HOME) {
            screen.focusNetflix()
        }
        scheduleAuxiliarySync()
    }

    @Deprecated("System speech result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)
        input.onActivityResult(requestCode, resultCode, intent)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        // Native dialogs and mirrored mouse input also count as activity.
        lastInteraction = SystemClock.uptimeMillis()
    }

    private var pointerTraceCount = 0
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (BuildConfig.DEBUG && pointerTraceCount++ < 24) {
            Log.d("HomeTVInput", "stage=touch action=${event.actionMasked} source=${event.source} x=${event.x.toInt()} y=${event.y.toInt()}")
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) lastInteraction = SystemClock.uptimeMillis()
        if (::notices.isInitialized && notices.handleKey(event)) return true
        if (::cameras.isInitialized && cameras.isOpen()) {
            val handled = cameras.handleKey(event)
            if (handled || event.keyCode in listOf(KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN)) return true
            return super.dispatchKeyEvent(event)
        }
        if (::googleMap.isInitialized && googleMap.isOpen()) {
            val handled = googleMap.handleKey(event)
            if (handled || event.keyCode == KeyEvent.KEYCODE_MENU) return true
            return super.dispatchKeyEvent(event)
        }
        if (::media.isInitialized && media.isOpen()) {
            val handled = media.handleKey(event)
            if (handled || event.keyCode in listOf(KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN)) return true
            return super.dispatchKeyEvent(event)
        }
        if (saver) {
            if (event.action == KeyEvent.ACTION_DOWN) goHome()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) goHome()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) { menuLong = false; ui.postDelayed(menuHold, 650) }
            if (event.action == KeyEvent.ACTION_UP) { ui.removeCallbacks(menuHold); if (!menuLong && !event.isCanceled) { dialogs.showSettings(); scheduleAuxiliarySync() } }
            return true
        }
        if (event.keyCode in listOf(KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val tabs = NativeTvView.Tab.entries
                val offset = if (event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP) 1 else -1
                screen.tab = tabs[(screen.tab.ordinal + offset + tabs.size) % tabs.size]
                if (screen.tab == NativeTvView.Tab.HOME) screen.focusNetflix()
                screen.invalidate()
                scheduleAuxiliarySync()
            }
            return true
        }
        val before = screen.selectedAction
        val handled = screen.handleKey(event)
        if (handled) scheduleAuxiliarySync()
        if (handled && event.action == KeyEvent.ACTION_DOWN) {
            val kind = if (event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) NativeAudioPolicy.Kind.SELECT else NativeAudioPolicy.Kind.MOVE
            if (kind == NativeAudioPolicy.Kind.SELECT || before != screen.selectedAction)
                audio.requestNative(kind, event.eventTime, preferences.getBoolean("sounds", true))
            if (BuildConfig.DEBUG) Log.d("HomeTVNative", "tab=${screen.tab} selected=${screen.selectedAction}")
        }
        return handled || super.dispatchKeyEvent(event)
    }

    private fun goHome() {
        if (!::screen.isInitialized) return
        media.close(); cameras.close(); dialogs.close(); if (::googleMap.isInitialized) googleMap.close(); assistantDialog?.dismiss(); assistantDialog = null
        if (::notices.isInitialized) notices.close()
        formDialog?.dismiss(); formDialog = null
        saver = false; screen.setScreenSaver(false)
        screen.tab = NativeTvView.Tab.HOME
        screen.focusNetflix(); screen.requestFocus(); lastInteraction = SystemClock.uptimeMillis()
        scheduleAuxiliarySync()
        if (BuildConfig.DEBUG) Log.d("HomeTVNative", "home selected=${screen.selectedAction}")
    }

    private fun applyAppearance() {
        preferences.edit().putBoolean("resolvedDark", darkAppearance()).apply()
        screen.setTheme(preferences.getString("theme", "auto") ?: "auto")
        screen.setBackground(preferences.getString("background", "weather") ?: "weather")
        scheduleAuxiliarySync()
    }

    private fun bind() {
        preferences.edit().putBoolean("resolvedDark", darkAppearance()).apply()
        if (!::cameras.isInitialized || !cameras.isOpen() && !media.isOpen()) screen.bind(dashboard, bootstrap, appIds)
        if (::googleMap.isInitialized) bindGoogleMap()
        scheduleAuxiliarySync()
    }

    private fun mapDataReady(): Boolean = dashboardConnected && NativeWeatherPolicy.isFresh(
        dashboard.optString("mode"), dashboard.optString("updatedAt"), System.currentTimeMillis())

    private fun bindGoogleMap() {
        if (!::googleMap.isInitialized) return
        mapHasCurrentData = mapDataReady()
        googleMap.bind(if (mapHasCurrentData) dashboard.optJSONObject("household") ?: JSONObject() else JSONObject())
    }

    private fun auxiliaryBlocked(): Boolean = !resumed || saver || media.isOpen() || cameras.isOpen() || dialogs.isOpen() ||
        assistantDialog?.isShowing == true || formDialog?.isShowing == true || !window.decorView.hasWindowFocus()

    private fun scheduleAuxiliarySync() {
        if (!resumed || isDestroyed || mapSyncPosted || !::screen.isInitialized || !::googleMap.isInitialized ||
            !::media.isInitialized || !::dialogs.isInitialized) return
        mapSyncPosted = true
        ui.post(auxiliarySync)
    }

    private fun syncGoogleMap() {
        if (!::googleMap.isInitialized) return
        if (mapHasCurrentData != mapDataReady()) bindGoogleMap()
        val blocked = auxiliaryBlocked()
        if (blocked && googleMap.isOpen()) googleMap.close()
        if (googleMap.isOpen()) { lastMapBounds = null; return }
        val bounds = if (blocked) null else screen.googleMapBounds()
        lastMapBounds = bounds?.let(::RectF)
        googleMap.showEmbedded(bounds)
    }

    private fun maybeLoadVacuumMap() {
        if (endpointReady && resumed && !auxiliaryBlocked() && screen.tab == NativeTvView.Tab.HOUSEHOLD &&
            screen.householdPage() == "cleaning") loadVacuumMap(silent = true)
    }

    private fun refreshData() {
        if (loading || !resumed || !endpointReady) return
        loading = true
        val epoch = generation
        data.refresh { result ->
            if (!resumed || epoch != generation) return@refresh
            loading = false
            result.onSuccess { snapshot ->
                bootstrap = snapshot.bootstrap
                snapshot.dashboard?.let { dashboard = it }
                bootstrap.optJSONObject("clientSettings")?.let { applyClientSettings(it) }
                photoList = snapshot.photos
                dashboardConnected = bootstrap.optBoolean("authenticated") && snapshot.dashboard != null
                bind()
                if (bootstrap.optBoolean("authenticated")) {
                    screen.setConnectionStatus(true, "HA Domov")
                    if (!profileLoaded && bootstrap.optJSONObject("profile")?.optBoolean("available") == true) {
                        profileLoaded = true
                        images.getProfileImage { response ->
                            response.onSuccess { decode(it, epoch, 512) { image -> screen.setProfileBitmap(image) } }
                            response.onFailure { profileLoaded = false }
                        }
                    }
                    TvOverlayService.startIfAuthorized(this)
                    maybeOpenPendingCamera()
                } else {
                    clearPrivateData()
                    screen.setConnectionStatus(false, "Připojení domácnosti vyžaduje spárování v Nastavení")
                }
            }.onFailure {
                dashboardConnected = false; bindGoogleMap()
                if ((it as? NativeDashboardError)?.status == 401) clearPrivateData()
                screen.setConnectionStatus(false, "HA Domov není dostupný · aplikace fungují dál")
                if (BuildConfig.DEBUG) Log.d("HomeTVNative", "data_unavailable")
            }
        }
    }

    private fun loadInstalledApps() {
        decoder.execute {
            val launcher = TvAppLauncher(packageManager)
            val installed = NativeLaunchPolicy.allowedPackages().filter { launcher.resolve(it) != null }
            val icons = installed.mapNotNull { id ->
                runCatching {
                    val drawable = packageManager.getApplicationIcon(id)
                    val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
                    drawable.setBounds(0, 0, 192, 192); drawable.draw(Canvas(bitmap))
                    id to bitmap
                }.getOrNull()
            }
            ui.post {
                if (isDestroyed) return@post
                appIds = installed
                preferences.edit().putString("apps", JSONArray(installed).toString()).apply()
                icons.forEach { (id, icon) -> screen.setAppIcon(id, icon) }
                bind()
            }
        }
    }

    private fun decode(bytes: ByteArray, epoch: Long, maximum: Int = 1280, ready: (Bitmap) -> Unit) {
        runCatching {
            decoder.execute {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth !in 1..16384 || bounds.outHeight !in 1..16384) return@execute
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maximum) sample *= 2
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return@execute
                ui.post { if (resumed && generation == epoch && !isDestroyed) ready(bitmap) else bitmap.recycle() }
            }
        }
    }

    private fun handleAction(action: String) {
        lastInteraction = SystemClock.uptimeMillis()
        if (bootstrap.optJSONObject("preview")?.optBoolean("readOnly") == true && action in listOf("night", "kino", "assistant")) {
            toast("Živý náhled je pouze pro čtení."); return
        }
        when {
            action == "saver:stop" -> goHome()
            action.startsWith("tab:") -> Unit // Arrow navigation remains in the native header.
            action.startsWith("app:") -> {
                val id = action.removePrefix("app:")
                val intent = TvAppLauncher(packageManager).resolve(id)
                if (intent == null) toast("Aplikace není dostupná.")
                else runCatching { startActivity(intent) }.onFailure { toast("Aplikaci se nepodařilo otevřít.") }
            }
            action == "appearance" -> dialogs.showSettings()
            action == "settings" -> dialogs.showSettings()
            action == "notices" -> dialogs.showNotices()
            action == "assistant" -> input.showChoice()
            action == "photos" -> startSaver()
            action == "night" -> dialogs.showNightPlan()
            action == "kino" -> dialogs.showKino()
            action == "map:google" -> googleMap.openExpanded()
            action.startsWith("camera:") -> {
                val id = action.removePrefix("camera:")
                openCamera(id)
            }
            action.startsWith("webapp:") -> openMiniApp(action.removePrefix("webapp:"))
            action == "media:play" -> showMediaInput()
            action.startsWith("room:") -> find(dashboard.optJSONObject("domestic")?.optJSONArray("rooms"), "id", action.removePrefix("room:"))?.let { dialogs.showRoom(it) } ?: toast("Údaje místnosti nejsou dostupné.")
            action.startsWith("security:") -> details("Zabezpečení", find(dashboard.optJSONObject("household")?.optJSONArray("security"), "id", action.removePrefix("security:")))
            action == "vacuum:map" -> loadVacuumMap()
        }
    }

    private fun openSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }.onFailure { toast("Nastavení Androidu se nepodařilo otevřít.") }
    }

    private fun startSaver() {
        dialogs.close(); googleMap.close(); saver = true; screen.setScreenSaver(true); nextPhoto(); scheduleAuxiliarySync()
    }
    private fun nextPhoto() {
        photoAt = SystemClock.uptimeMillis()
        if (photoList.length() == 0) return
        val id = photoList.optJSONObject(photoIndex++ % photoList.length())?.optString("id").orEmpty()
        if (!id.matches(Regex("[a-f0-9]{24}"))) return
        val epoch = generation
        images.photo(id) { result -> result.onSuccess { decode(it, epoch, 1920) { bitmap -> screen.setPhotoBitmap(bitmap) } } }
    }

    private fun sendAssistant(text: String): Boolean {
        if (!resumed || assistantBusy || bootstrap.optJSONObject("preview")?.optBoolean("readOnly") == true) return false
        assistantBusy = true
        val request = ++assistantEpoch
        showAssistantResult("Zpracovávám povel…", null)
        val installed = NativeLaunchPolicy.allowedPackages().filter { TvAppLauncher(packageManager).resolve(it) != null }
        commands.post("/api/assistant", JSONObject().put("text", text).put("installedApps", JSONArray(installed))) { result ->
            assistantBusy = false
            result.onSuccess { answer ->
                if (request != assistantEpoch) { screen.setNotice("Asistent", answer.optString("reply", "Odpověď je dostupná po novém zadání.")); return@onSuccess }
                val confirmation = answer.optString("confirmationId").takeIf { answer.optString("status") == "confirmation" && it.matches(Regex("[A-Za-z0-9_-]{16,128}")) }
                showAssistantResult(answer.optString("reply", "Výsledek povelu není potvrzený."), confirmation)
            }.onFailure {
                if (request == assistantEpoch) showAssistantResult("Výsledek povelu není potvrzený. Povel automaticky neopakuji.", null)
                else screen.setNotice("Asistent", "Výsledek povelu není potvrzený.")
            }
        }
        return true
    }
    private fun showAssistantResult(message: String, confirmation: String?) {
        if (!resumed) return
        assistantDialog?.setOnDismissListener(null); assistantDialog?.dismiss()
        val builder = AlertDialog.Builder(this).setTitle("Asistent HA TV Launcher").setMessage(message.take(2500)).setNegativeButton("Zavřít", null)
        if (confirmation != null) builder.setPositiveButton("Potvrdit povel") { dialog, _ ->
            if (!assistantBusy) {
                (dialog as? AlertDialog)?.setOnDismissListener(null)
                assistantBusy = true
                val request = ++assistantEpoch
                showAssistantResult("Provádím potvrzený povel…", null)
                commands.post("/api/assistant/confirm", JSONObject().put("confirmationId", confirmation)) { result ->
                    assistantBusy = false
                    val answer = result.getOrNull()
                    var reply = answer?.optString("reply") ?: "Výsledek povelu není potvrzený. Povel automaticky neopakuji."
                    var leave = false
                    if (answer?.optString("status") == "native_action" && answer.optJSONObject("nativeAction") != null) {
                        if (request != assistantEpoch || !resumed) { screen.setNotice("Asistent", "Potvrzený povel už není aktuální. Žádnou místní akci neprovádím."); return@post }
                        assistantDialog?.setOnDismissListener(null); assistantDialog?.dismiss(); assistantDialog = null
                        val outcome = runCatching { NativeAssistantActions.execute(this, answer.getJSONObject("nativeAction"),
                            home = { goHome() }, back = { dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)); dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)) }) }
                        reply = outcome.getOrNull()?.reply ?: "Místní povel se nepodařilo ověřit nebo provést. Povel automaticky neopakuji."
                        leave = outcome.getOrNull()?.leaveDialog == true
                    }
                    if (!leave && request == assistantEpoch) showAssistantResult(reply, null)
                    else if (!leave) screen.setNotice("Asistent", reply)
                }
            }
        }
        assistantDialog = builder.create().also { dialog ->
            dialog.setOnDismissListener { if (assistantDialog === dialog) { assistantDialog = null; assistantEpoch++ } }
            dialog.show(); NativeDialogStyle.apply(dialog, this, darkAppearance())
        }
    }

    private fun showNotice(title: String, message: String, camera: String?, duration: Int = 10) {
        val safeCamera = camera?.takeIf { find(dashboard.optJSONArray("cameras"), "id", it)?.optBoolean("available") == true }
        notices.show(title, message, safeCamera, duration)
    }
    private fun openCamera(id: String) {
        val camera = find(dashboard.optJSONArray("cameras"), "id", id)
        if (camera?.optBoolean("available") == true && resumed) {
            notices.close(); dialogs.close(); googleMap.close(); media.close()
            cameras.openCamera(id, camera.optString("name", id), "auto")
            scheduleAuxiliarySync()
        } else toast("Kamera není dostupná.")
    }
    private fun cameraIntent(value: Intent?): String? = value?.getStringExtra("open_camera")?.takeIf {
        it in setOf("entrance", "cars", "garden", "outside")
    }
    private fun maybeOpenPendingCamera() {
        if (!resumed || !dashboardConnected) return
        if (pendingDoor) { pendingDoor = false; ui.post { if (resumed) dialogs.showDoor() }; return }
        pendingCamera?.let { id -> pendingCamera = null; ui.post { if (resumed) openCamera(id) } }
    }
    private fun saveSettings(patch: JSONObject) {
        if (!bootstrap.optBoolean("authenticated")) return
        val ticket = ++settingsWrite
        settingsPending = true
        commands.requestJson("PUT", "/api/client-settings", patch) { result ->
            if (ticket != settingsWrite) return@requestJson
            settingsPending = false
            result.onSuccess { applyClientSettings(it) }.onFailure { toast("Společné nastavení se nepodařilo uložit. Místní volba zůstává aktivní.") }
        }
    }
    private fun applyClientSettings(value: JSONObject) {
        if (settingsPending || value.optInt("version") != 1 || value.optLong("revision") <= preferences.getLong("settingsRevision", 0)) return
        val settings = value.optJSONObject("settings") ?: return
        val theme = settings.optString("theme"); val background = settings.optString("background")
        val sound = settings.opt("navigationSounds") as? Boolean ?: return
        val minutes = settings.opt("saverMinutes") as? Number ?: return
        val interval = settings.opt("photoIntervalSeconds") as? Number ?: return
        if (theme !in listOf("auto", "light", "dark") || background !in listOf("weather", "plain", "photos") ||
            minutes.toDouble() != minutes.toInt().toDouble() || minutes.toInt() !in 0..120 ||
            interval.toDouble() != interval.toInt().toDouble() || interval.toInt() !in 10..3600) return
        preferences.edit().putString("theme", theme).putString("background", background).putBoolean("sounds", sound)
            .putInt("saverMinutes", minutes.toInt()).putInt("photoIntervalSeconds", interval.toInt())
            .putLong("settingsRevision", value.optLong("revision")).apply()
        if (!sound) audio.invalidate()
        applyAppearance()
    }
    private fun applyPower(value: JSONObject) {
        val energy = dashboard.optJSONObject("domestic")?.optJSONObject("energy") ?: return
        val expected = energy.optJSONObject("power")?.optString("entityId").orEmpty()
        if (expected.isBlank() || value.optString("entityId") != expected || value.opt("available") !is Boolean) return
        val old = energy.optJSONObject("power")?.optString("updatedAt").orEmpty()
        val incoming = value.optString("updatedAt")
        if (value.optBoolean("available")) {
            val watts = (value.opt("powerW") as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: return
            val raw = (value.opt("value") as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: return
            val unit = value.optString("unit").takeIf { it in listOf("W", "kW") } ?: return
            if (kotlin.math.abs(watts - raw * if (unit == "kW") 1000 else 1) > 0.000001 * maxOf(1.0, kotlin.math.abs(watts))) return
            val newTime = runCatching { java.time.Instant.parse(incoming).toEpochMilli() }.getOrNull() ?: return
            val oldTime = runCatching { java.time.Instant.parse(old).toEpochMilli() }.getOrDefault(0L)
            if (newTime < oldTime) return
            energy.put("power", JSONObject().put("entityId", expected).put("available", true).put("value", raw).put("unit", unit).put("updatedAt", incoming))
            energy.put("powerW", watts)
        } else {
            energy.put("power", JSONObject().put("entityId", expected).put("available", false).put("value", JSONObject.NULL).put("unit", JSONObject.NULL).put("updatedAt", JSONObject.NULL))
            energy.put("powerW", JSONObject.NULL)
        }
        // Keep the overall dashboard timestamp untouched: one live sensor is not proof that every entity refreshed.
        if (!cameras.isOpen() && !media.isOpen()) screen.bind(dashboard, bootstrap, appIds)
        if (BuildConfig.DEBUG) Log.d("HomeTVNative", "power_update available=${value.optBoolean("available")}")
    }
    private fun openMiniApp(id: String) {
        val app = find(bootstrap.optJSONArray("apps"), "id", id)
        val raw = app?.optString("url").orEmpty()
        val base = Uri.parse(BuildConfig.DASHBOARD_URL)
        val uri = if (raw.startsWith("/")) base.buildUpon().encodedPath(raw.substringBefore('?')).encodedQuery(raw.substringAfter('?', "").ifEmpty { null }).build() else Uri.parse(raw)
        if (uri.scheme != base.scheme || uri.host != base.host || uri.port != base.port || uri.userInfo != null || !uri.path.orEmpty().startsWith("/apps/")) { toast("Tato aplikace není dostupná."); return }
        startActivity(Intent(this, NativeWebAppActivity::class.java).putExtra("url", uri.toString()))
    }
    private fun loadVacuumMap(silent: Boolean = false) {
        val map = dashboard.optJSONObject("domestic")?.optJSONObject("vacuum")?.optJSONObject("map")
        val url = map?.optString("url").orEmpty()
        if (map?.optBoolean("available") != true || url.isBlank()) { if (!silent) toast("Mapa vysavače není dostupná."); return }
        val cacheKey = url + "|" + map.optString("lastUpdated")
        if (vacuumMapBusy || (silent && cacheKey == vacuumMapKey)) return
        val now = SystemClock.uptimeMillis()
        if (silent && vacuumMapAttempt != 0L && now - vacuumMapAttempt < 30000) return
        vacuumMapBusy = true; vacuumMapAttempt = now
        val epoch = generation
        images.requestBytes(url) { result ->
            if (!resumed || epoch != generation) return@requestBytes
            vacuumMapBusy = false
            result.onSuccess { decode(it, epoch) { bitmap -> vacuumMapKey = cacheKey; screen.setVacuumBitmap(bitmap) } }
                .onFailure { if (!silent) toast("Mapa vysavače není dostupná.") }
        }
    }
    private fun details(title: String, value: JSONObject?) {
        if (value == null) { toast("Údaje nejsou dostupné."); return }
        val labels = mapOf("armed" to "Zapnuto", "disarmed" to "Vypnuto", "arming" to "Odchodové zpoždění", "open" to "Otevřeno", "closed" to "Zavřeno", "heating" to "Topí", "cooling" to "Chladí", "idle" to "V klidu", "auto" to "Automaticky", "heat" to "Vytápění", "off" to "Vypnuto")
        fun reading(key: String): String {
            val number = (value.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: return "—"
            return java.text.NumberFormat.getNumberInstance(java.util.Locale.forLanguageTag("cs-CZ")).apply { maximumFractionDigits = 1 }.format(number) + " " + value.optString("unit", "°C")
        }
        val lines = if (title == "Místnost") listOf(
            "Aktuální teplota: ${reading("currentTemperature")}", "Nastavená teplota: ${reading("targetTemperature")}",
            "Režim: ${labels[value.optString("state")] ?: "Stav neznámý"}",
            "Topení: ${if (value.optBoolean("available")) value.optString("heatingLabel", "Neověřeno") else "Není dostupné"}")
        else buildList {
            add("Alarm: ${labels[value.optString("state")] ?: "Stav neznámý"}")
            add("Dostupné kontakty: ${value.optInt("availableContactCount")}/${value.optInt("contactCount")}")
            val contacts = value.optJSONArray("contacts") ?: JSONArray()
            for (index in 0 until minOf(contacts.length(), 10)) {
                val item = contacts.optJSONObject(index) ?: continue
                add("${item.optString("name", "Kontakt")}: ${if (item.optBoolean("available")) labels[item.optString("state")] ?: "Stav neznámý" else "Není dostupný"}")
            }
        }
        val dialog = AlertDialog.Builder(this).setTitle(value.optString("name", title)).setMessage(lines.joinToString("\n\n")).setPositiveButton("Zavřít", null).create()
        formDialog = dialog; dialog.setOnDismissListener { if (formDialog === dialog) formDialog = null }
        dialog.show(); NativeDialogStyle.apply(dialog, this, darkAppearance())
    }
    private fun find(values: JSONArray?, key: String, value: String): JSONObject? = (0 until (values?.length() ?: 0)).mapNotNull { values?.optJSONObject(it) }.firstOrNull { it.optString(key) == value }
    private fun clearPrivateData() {
        generation++; images.cancelPending(); imageBusy = false
        dashboardConnected = false; cameraPosters.clear(); vacuumMapBusy = false; vacuumMapKey = null; vacuumMapAttempt = 0L
        ui.removeCallbacks(snapshots); if (resumed) ui.postDelayed(snapshots, 1000)
        dashboard = JSONObject(); photoList = JSONArray(); profileLoaded = false
        screen.setPhotoBitmap(null); screen.setProfileBitmap(null); screen.setVacuumBitmap(null)
        listOf("entrance", "garden", "cars", "outside").forEach { screen.setCameraBitmap(it, null) }
        bind()
    }
    private fun darkAppearance(): Boolean = when (preferences.getString("theme", "auto")) {
        "light" -> false; "dark" -> true; else -> !NativeWeatherPolicy.isFresh(dashboard.optString("mode"), dashboard.optString("updatedAt"), System.currentTimeMillis()) || dashboard.optJSONObject("solar")?.optString("state") != "above_horizon"
    }
    private fun showPair() {
        val field = EditText(this).apply {
            hint = "Párovací kód domácího mostu"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(this).setTitle("Připojení k HA Domov").setView(field)
            .setPositiveButton("Připojit", null).setNegativeButton("Zrušit", null).create()
        formDialog = dialog
        dialog.setOnDismissListener { if (formDialog === dialog) formDialog = null }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = field.text.toString().trim()
                if (value.length !in 8..256) { field.error = "Zadej platný párovací kód."; return@setOnClickListener }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                commands.post("/api/pair", JSONObject().put("code", value)) { result ->
                    field.text.clear()
                    if (result.isSuccess) { dialog.dismiss(); refreshData() }
                    else if (dialog.isShowing) { field.error = "Připojení se nepodařilo. Ověř kód a server."; dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true }
                }
            }
        }
        dialog.show()
        NativeDialogStyle.apply(dialog, this, darkAppearance())
    }
    private fun showMediaInput() {
        val field = EditText(this).apply { hint = "https://…"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; setSingleLine(true) }
        val dialog = AlertDialog.Builder(this).setTitle("Otevřít médium").setView(field)
            .setPositiveButton("Video") { _, _ -> media.openMedia(field.text.toString().trim(), "video/mp4", "Přehrávání") }
            .setNeutralButton("Hudba") { _, _ -> media.openMedia(field.text.toString().trim(), "audio/mpeg", "Přehrávání") }
            .setNegativeButton("Zrušit", null).create()
        formDialog = dialog; dialog.setOnDismissListener { if (formDialog === dialog) formDialog = null }; dialog.show()
        NativeDialogStyle.apply(dialog, this, darkAppearance())
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
}
