package org.hometv.launcher

import android.animation.ValueAnimator
import android.annotation.TargetApi
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.animation.DecelerateInterpolator
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** Native TV surface. All coordinates are in a 1440x810 design space. No WebView or network I/O. */
class NativeTvView(context: Context) : View(context) {
    enum class Tab(val key: String, val title: String) {
        HOME("home", "Přehled"), MEDIA("media", "Média"), CAMERAS("cameras", "Kamery"),
        CALENDAR("calendar", "Kalendář"), HOUSEHOLD("household", "Domov"), APPS("apps", "Aplikace")
    }
    data class Target(val action: String, val label: String, val bounds: RectF, val row: String)
    private data class App(val id: String, val name: String, val mark: String, val color: Int)
    private data class RoomDisplay(val id: String, val name: String, val heating: Boolean?, val activity: String, val temperature: String, val temperatureAvailable: Boolean, val target: String, val co2: String, val humidity: String, val pm25: String)
    private data class GradientKey(val left: Float, val top: Float, val right: Float, val bottom: Float, val dark: Boolean)
    private data class WeatherSource(val condition: String, val rain: Double?, val rainUnit: String, val solarState: String, val elevation: Double?, val azimuth: Double?)
    private data class BackgroundKey(val mode: String, val photo: Boolean, val photoRevision: Long, val weather: WeatherSource, val live: Boolean, val dark: Boolean)
    private data class ContentKey(val tab: Tab, val section: String, val calendarPage: Int, val calendarDay: String?, val roomPage: Int, val selection: String, val revision: Long, val localDay: String, val clock: Long, val bitmapRevision: Long, val live: Boolean, val dark: Boolean)

    var onAction: (String) -> Unit = {}
    private var selection = "app:com.netflix.ninja"
    var selectedAction: String
        get() = selection
        set(value) { selection = value.take(180); pendingHomeFocus = false; updateDescription(); invalidate() }
    var tab: Tab = Tab.HOME
        set(value) {
            val previous = field
            field = value
            if (previous != value) { tabPillFrom = previous.ordinal.toFloat(); animatePage(movePill = true) }
            if (value == Tab.HOME) { focusNetflix() }
            else if (value == Tab.APPS) { selectFirstApp() }
            else selection = "tab:${value.key}"
            updateDescription(); invalidate()
        }
    private var dashboard = JSONObject()
    private var bootstrap = JSONObject()
    private var installed = emptyList<App>()
    private var pendingHomeFocus = true
    private var connected = false
    private var connectionMessage = "Připojuji domácí most…"
    private var freshAt = Long.MIN_VALUE
    private var freshValue = false
    private var theme = "auto"
    private var backgroundMode = "weather"
    private var saver = false
    private var now = System.currentTimeMillis()
    private var calendarPage = 0
    private var calendarDay: String? = null
    private var householdSection = "overview"
    private var roomPage = 0
    private var profile: Bitmap? = null
    private var photo: Bitmap? = null
    private var vacuumMap: Bitmap? = null
    private var noticeTitle = ""
    private var noticeMessage = ""
    private var noticeUntil = 0L
    private val cameraImages = LinkedHashMap<String, Bitmap>()
    private val appIcons = LinkedHashMap<String, Bitmap>()
    private val appIconBackgrounds = HashMap<String, Int>()
    private val targets = ArrayList<Target>(48)
    private val cameraAreas = LinkedHashMap<String, RectF>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val regularFace = Typeface.create("sans-serif", Typeface.NORMAL)
    private val mediumFace = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val lightFace = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val brandFace = Typeface.create("sans-serif", Typeface.BOLD)
    private val netflixFace = Typeface.create("sans-serif-black", Typeface.NORMAL)
    private val path = Path()
    private val weatherPainter = NativeWeatherPainter()
    private val locale = Locale("cs", "CZ")
    private val zone = TimeZone.getTimeZone("Europe/Prague")
    private val formatters = HashMap<String, SimpleDateFormat>()
    private val numberFormat = NumberFormat.getNumberInstance(locale)
    private val calendarProjection = NativeCalendarProjection()
    private var roomProjection = emptyList<RoomDisplay>()
    private var calendarRevision = 0L
    private var roomRevision = 0L
    private var bindRevision = 0L
    private var photoRevision = 0L
    private var vacuumRevision = 0L
    private var weatherSource = WeatherSource("", null, "", "", null, null)
    private val displayLists = if (Build.VERSION.SDK_INT >= 29) DisplayLists() else null
    private val cardGradients = object : LinkedHashMap<GradientKey, LinearGradient>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GradientKey, LinearGradient>?): Boolean = size > 96
    }
    private var drawScale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var focusProgress = 1f
    private var pageProgress = 1f
    private var tabPillFrom = 0f
    private var focusAnimator: ValueAnimator? = null
    private var pageAnimator: ValueAnimator? = null

    init { isFocusable = true; isFocusableInTouchMode = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    fun bind(dashboard: JSONObject, bootstrap: JSONObject, apps: List<String>) {
        this.dashboard = dashboard; this.bootstrap = bootstrap; bindRevision++
        val weather = dashboard.obj("weather"); val solar = dashboard.obj("solar")
        weatherSource = WeatherSource(weather.string("condition"), weather.number("rainRate"), weather.obj("units").string("rainRate"), solar.string("state"), solar.number("elevation"), solar.number("azimuth"))
        freshAt = Long.MIN_VALUE
        bindCalendarProjection(dashboard)
        bindRoomProjection(dashboard)
        val nextInstalled = APPS.filter { apps.contains(it.id) }
        if (installed != nextInstalled) installed = nextInstalled
        if (pendingHomeFocus && tab == Tab.HOME) focusNetflix()
        else if (selection.startsWith("app:") && installed.none { "app:${it.id}" == selection }) selectFirstApp()
        updateDescription(); invalidate()
    }
    fun setTheme(mode: String) { theme = if (mode in listOf("light", "dark", "auto")) mode else "auto"; invalidate() }
    fun setBackground(mode: String) { backgroundMode = if (mode in listOf("weather", "plain", "photos")) mode else "weather"; invalidate() }
    fun setConnectionStatus(connected: Boolean, message: String) { this.connected = connected; connectionMessage = message.take(180); updateDescription(); invalidate() }
    fun setCameraBitmap(id: String, bitmap: Bitmap?) {
        if (!Regex("[a-z0-9_-]{1,80}").matches(id)) return
        if (bitmap == null) cameraImages.remove(id) else { if (!cameraImages.containsKey(id) && cameraImages.size >= 4) cameraImages.remove(cameraImages.keys.first()); cameraImages[id] = bitmap }
        invalidate()
    }
    fun setAppIcon(id: String, bitmap: Bitmap?) {
        if (APPS.none { it.id == id }) return
        if (bitmap == null || bitmap.isRecycled) { appIcons.remove(id); appIconBackgrounds.remove(id) }
        else { appIcons[id] = bitmap; appIconBackgrounds[id] = iconEdgeColor(bitmap) }
        invalidate()
    }
    fun setProfileBitmap(bitmap: Bitmap?) { profile = bitmap; invalidate() }
    fun setPhotoBitmap(bitmap: Bitmap?) { photo = bitmap; photoRevision++; invalidate() }
    fun setVacuumBitmap(bitmap: Bitmap?) { vacuumMap = bitmap; vacuumRevision++; invalidate() }
    fun setScreenSaver(enabled: Boolean) { saver = enabled; if (!enabled && tab == Tab.HOME) focusNetflix(); updateDescription(); invalidate() }
    fun setNotice(title: String, message: String) { noticeTitle = title.take(180); noticeMessage = message.take(500); noticeUntil = now + 8000; invalidate() }
    fun tick(nowMillis: Long = System.currentTimeMillis()) { if (nowMillis / 1000 != now / 1000) { now = nowMillis; invalidate() } }
    fun focusNetflix() { pendingHomeFocus = true; val app = installed.find { it.id == NETFLIX } ?: installed.firstOrNull(); selection = app?.let { "app:${it.id}" } ?: "tab:home"; if (app != null) pendingHomeFocus = false; updateDescription(); invalidate() }
    fun actionRects(): Map<String, RectF> = targets.associate { it.action to RectF(it.bounds) }
    fun actions(): List<Target> = targets.map { it.copy(bounds = RectF(it.bounds)) }
    fun cameraRects(): Map<String, RectF> = cameraAreas.mapValues { RectF(it.value) }
    fun householdPage(): String = householdSection
    // Chromium initialization must not compete with a page's transition frames.
    fun googleMapBounds(): RectF? = if (!saver && pageProgress >= 1f && tab == Tab.HOUSEHOLD && householdSection == "security") virtualToScreen(RectF(59f, 430f, 911f, 688f)) else null
    fun virtualToScreen(rect: RectF): RectF = RectF(rect.left * drawScale + offsetX, rect.top * drawScale + offsetY, rect.right * drawScale + offsetX, rect.bottom * drawScale + offsetY)

    private fun live(): Boolean {
        if (!connected) return false
        if (freshAt != now) { freshValue = NativeWeatherPolicy.isFresh(dashboard.optString("mode"), dashboard.optString("updatedAt"), now); freshAt = now }
        return freshValue
    }
    private fun data() = if (live()) dashboard else EMPTY_OBJECT
    private fun dark(): Boolean = when (theme) { "light" -> false; "dark" -> true; else -> data().obj("solar").optString("state") != "above_horizon" }
    private fun ink() = if (dark()) Color.rgb(246, 247, 250) else Color.rgb(36, 40, 47)
    private fun muted() = if (dark()) Color.rgb(173, 180, 194) else Color.rgb(102, 110, 121)
    private fun glass() = if (dark()) Color.argb(202, 48, 51, 60) else Color.argb(126, 255, 255, 255)
    private fun selectFirstApp() { selection = installed.firstOrNull()?.let { "app:${it.id}" } ?: "tab:${tab.key}"; invalidate() }
    private fun updateDescription() { contentDescription = "HA TV Launcher; tab=${tab.key}; action=$selection; connected=$connected; saver=$saver" }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val geometry = NativeRenderPolicy.geometry(width, height)
        drawScale = geometry.scale
        offsetX = (width - WIDTH * drawScale) / 2f; offsetY = (height - HEIGHT * drawScale) / 2f
        canvas.drawColor(if (dark()) Color.rgb(24, 27, 34) else Color.rgb(221, 227, 232))
        val saved = canvas.save(); canvas.translate(offsetX, offsetY); canvas.scale(drawScale, drawScale)
        targets.clear(); cameraAreas.clear()
        if (Build.VERSION.SDK_INT >= 29 && canvas.isHardwareAccelerated && displayLists != null) {
            val photoActive = (backgroundMode == "photos" || saver) && photo?.isRecycled == false
            val key = BackgroundKey(backgroundMode, photoActive, if (photoActive) photoRevision else 0L, weatherSource, live(), dark())
            displayLists.background(canvas, key, geometry) { drawBackground(it) }
        } else drawBackground(canvas)
        if (saver) drawSaver(canvas) else {
            drawHeader(canvas); drawGreeting(canvas)
            // Cached display lists keep the original alpha/slide animation without
            // rebuilding every card, text measurement and glyph on every frame.
            val headerFocused = targets.any { it.row == "header" && it.action == selection }
            if (Build.VERSION.SDK_INT >= 29 && canvas.isHardwareAccelerated && displayLists != null && tab in listOf(Tab.CALENDAR, Tab.HOUSEHOLD) && (headerFocused || focusProgress >= 1f)) {
                val localDay = if (tab == Tab.CALENDAR) calendarProjection.days(now).first().key else ""
                val revision = when { tab == Tab.CALENDAR -> calendarRevision; householdSection == "rooms" -> roomRevision; else -> bindRevision }
                val key = ContentKey(tab, householdSection, calendarPage, calendarDay, roomPage, selection, revision, localDay,
                    if (tab == Tab.HOUSEHOLD && householdSection == "cleaning") now / 1000 else 0L,
                    if (tab == Tab.HOUSEHOLD && householdSection == "cleaning") vacuumRevision else 0L, live(), dark())
                val headerCount = targets.size
                val recordedTargets = displayLists.content(canvas, key, pageProgress, geometry) { recorded ->
                    drawPage(recorded)
                    ArrayList(targets.subList(headerCount, targets.size))
                }
                // Recording calls target() once; a cache hit restores the same
                // virtual geometry for remote navigation and Android map placement.
                if (targets.size == headerCount) targets.addAll(recordedTargets)
            } else {
                val content = if (pageProgress < 1f) canvas.saveLayerAlpha(24f, 150f, 1416f, 796f, (175 + 80 * pageProgress).toInt()) else canvas.save()
                canvas.translate(0f, 10f * (1f - pageProgress)); drawPage(canvas)
                canvas.restoreToCount(content)
            }
        }
        if (noticeUntil > now && noticeTitle.isNotBlank()) {
            card(canvas, RectF(470f, 15f, 970f, 100f), 25f)
            icon(canvas, "bell", 493f, 42f, 24f, ink())
            text(canvas, noticeTitle, 535f, 45f, 16f, ink(), 410f, true)
            text(canvas, noticeMessage.replace('\n', ' '), 535f, 73f, 13f, muted(), 410f)
        }
        canvas.restoreToCount(saved)
    }

    private fun drawBackground(canvas: Canvas) {
        if ((backgroundMode == "photos" || saver) && photo?.isRecycled == false) {
            image(canvas, photo, RectF(0f, 0f, WIDTH, HEIGHT), 0f)
            fill(canvas, RectF(0f, 0f, WIDTH, HEIGHT), Color.argb(if (dark()) 105 else 48, 15, 22, 31), 0f)
        } else if (backgroundMode == "weather") weatherPainter.draw(canvas, WIDTH, HEIGHT, data(), dark(), now)
    }
    private fun drawPage(canvas: Canvas) {
        when (tab) {
            Tab.HOME -> drawHome(canvas)
            Tab.MEDIA -> drawMedia(canvas)
            Tab.CAMERAS -> drawCameras(canvas)
            Tab.CALENDAR -> drawCalendar(canvas)
            Tab.HOUSEHOLD -> drawHousehold(canvas)
            Tab.APPS -> drawApps(canvas)
        }
    }

    /** Native-pixel display lists: at most two pages + backdrop, <=24MiB retained RGBA layers. */
    @TargetApi(29)
    private class DisplayLists {
        private data class Page(val node: RenderNode, val targets: List<Target>)
        private data class GeometryKey(val width: Int, val height: Int, val scaleBits: Int)
        private var geometryKey: GeometryKey? = null
        private var backgroundKey: BackgroundKey? = null
        private var backgroundNode: RenderNode? = null
        private val pages = object : LinkedHashMap<ContentKey, Page>(2, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ContentKey, Page>?): Boolean {
                if (size <= 2) return false
                eldest?.value?.node?.let(::release); return true
            }
        }
        private fun release(node: RenderNode) {
            node.setUseCompositingLayer(false, null)
            node.discardDisplayList()
        }
        private fun useGeometry(geometry: NativeRenderPolicy.Geometry) {
            val next = GeometryKey(geometry.viewWidth, geometry.viewHeight, geometry.scale.toRawBits())
            if (geometryKey != next) { clear(); geometryKey = next }
        }
        // The parent retains design coordinates for hit targets and live content.
        // Cancel only its scale when drawing a node already recorded in native pixels.
        private fun drawNative(canvas: Canvas, node: RenderNode, scale: Float) {
            val saved = canvas.save()
            canvas.scale(1f / scale, 1f / scale)
            canvas.drawRenderNode(node)
            canvas.restoreToCount(saved)
        }
        fun background(canvas: Canvas, key: BackgroundKey, geometry: NativeRenderPolicy.Geometry, record: (Canvas) -> Unit) {
            useGeometry(geometry)
            val bounds = geometry.background
            var node = backgroundNode
            if (backgroundKey != key || node?.hasDisplayList() != true) {
                node?.let(::release)
                node = RenderNode("HomeTV-background").apply { setPosition(bounds.left, bounds.top, bounds.right, bounds.bottom); setClipToBounds(true); setUseCompositingLayer(geometry.compositingAllowed, null) }
                val drawing = node.beginRecording(bounds.width, bounds.height)
                drawing.scale(geometry.scale, geometry.scale)
                try { record(drawing) } finally { node.endRecording() }
                backgroundNode = node; backgroundKey = key
            }
            drawNative(canvas, node, geometry.scale)
        }
        fun content(canvas: Canvas, key: ContentKey, progress: Float, geometry: NativeRenderPolicy.Geometry, record: (Canvas) -> List<Target>): List<Target> {
            useGeometry(geometry)
            val bounds = geometry.content
            var page = pages[key]
            if (page == null || !page.node.hasDisplayList()) {
                // A new power value, selected row or page replaces this tab's old
                // layer; it must not evict the other tab by filling the LRU with
                // obsolete revisions of the same page.
                val obsolete = pages.entries.iterator()
                while (obsolete.hasNext()) {
                    val entry = obsolete.next()
                    if (entry.key.tab == key.tab) { release(entry.value.node); obsolete.remove() }
                }
                val node = RenderNode("HomeTV-content").apply {
                    setPosition(bounds.left, bounds.top, bounds.right, bounds.bottom); setClipToBounds(true)
                    setHasOverlappingRendering(true); setUseCompositingLayer(geometry.compositingAllowed, null)
                }
                val drawing = node.beginRecording(bounds.width, bounds.height)
                drawing.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
                drawing.scale(geometry.scale, geometry.scale)
                val targets: List<Target>
                try { targets = record(drawing) } finally { node.endRecording() }
                page = Page(node, targets); pages[key] = page
            }
            page.node.setAlpha(if (geometry.compositingAllowed && progress < 1f) (175 + 80 * progress).toInt() / 255f else 1f)
            page.node.setTranslationY(10f * (1f - progress) * geometry.scale)
            drawNative(canvas, page.node, geometry.scale)
            return page.targets
        }
        fun clear() {
            backgroundNode?.let(::release); backgroundNode = null; backgroundKey = null; geometryKey = null
            pages.values.forEach { release(it.node) }; pages.clear()
        }
    }

    private fun drawHeader(c: Canvas) {
        icon(c, "home", 34f, 33f, 23f, ink()); text(c, "HA TV Launcher", 68f, 54f, 27f, ink(), 260f, true)
        card(c, RectF(345f, 23f, 996f, 70f), 26f)
        val pillIndex = tabPillFrom + (tab.ordinal - tabPillFrom) * pageProgress
        fill(c, RectF(351f + pillIndex * 106f, 27f, 453f + pillIndex * 106f, 66f), if (dark()) Color.rgb(227, 231, 239) else Color.WHITE, 22f)
        Tab.values().forEachIndexed { index, item ->
            val r = RectF(351f + index * 106f, 27f, 453f + index * 106f, 66f)
            target("tab:${item.key}", item.title, r, "header")
            if (selection == "tab:${item.key}") fill(c, r, if (item == tab) Color.argb(38, 100, 126, 157) else Color.argb(48, 163, 185, 214), 22f)
            centered(c, item.title, r.centerX(), 52f, 15f, if (item == tab) Color.rgb(35, 42, 55) else ink())
        }
        listOf("appearance" to "sun", "notices" to "bell", "assistant" to "spark", "photos" to "photo", "settings" to "profile").forEachIndexed { index, item ->
            val x = 1113f + index * 57f; val r = RectF(x, 25f, x + 42f, 67f)
            target(item.first, mapOf("appearance" to "Vzhled a pozadí", "notices" to "Oznámení", "assistant" to "Asistent", "photos" to "Fotografie a spořič", "settings" to "Nastavení")[item.first] ?: item.first, r, "header")
            focus(c, item.first, r, 22f)
            if (item.first == "settings" && profile?.isRecycled == false) image(c, profile, r, 22f)
            else icon(c, item.second, x + 9f, 35f, 24f, ink())
        }
    }
    private fun drawGreeting(c: Canvas) {
        val hour = calendar(now).get(Calendar.HOUR_OF_DAY)
        val greeting = when { hour < 5 -> "Dobrou noc"; hour < 11 -> "Dobré ráno"; hour < 18 -> "Dobrý den"; else -> "Dobrý večer" }
        text(c, "$greeting.", 34f, 106f, 31f, ink(), 930f)
        val household = data().obj("household"); val nameday = household.obj("nameday")
        val names = nameday.array("names").strings().joinToString(", ")
        val birthdays = household.array("birthdaysToday").objects().map { it.string("summary") }.filter { it.isNotBlank() }.joinToString(", ")
        val detail = format(now, "EEEE d. MMMM") + (if (nameday.optBoolean("available") && names.isNotBlank()) "   ·   Svátek má $names" else "") + (if (birthdays.isNotBlank()) "   ·   $birthdays" else "")
        text(c, detail, 34f, 137f, 13f, muted(), 1030f)
        text(c, format(now, "HH:mm:ss"), 1120f, 128f, 26f, ink(), 145f)
        fill(c, RectF(1269f, 107f, 1406f, 134f), if (dark()) Color.argb(40, 220, 230, 248) else Color.argb(120, 255, 255, 255), 16f)
        centered(c, if (live()) "Připojeno · HA" else "Stav neověřený", 1337f, 125f, 10f, muted())
    }

    private fun drawHome(c: Canvas) {
        weatherCard(c, RectF(34f, 162f, 498f, 386f))
        calendarCard(c, RectF(514f, 162f, 956f, 386f))
        val first = data().array("cameras").objects(4).firstOrNull()
        cameraCard(c, first, RectF(972f, 162f, 1406f, 386f), "overview")
        val d = data().obj("domestic"); val bedroom = (if (d.optInt("roomsContractVersion") >= 2) d.array("rooms") else d.obj("air").array("rooms")).objects().find { it.string("id") == "bedroom" }
        val entries = listOf(
            arrayOf("overview", "energy", "Dům právě teď", power(d.obj("energy").number("powerW"))),
            arrayOf("rooms", "wind", "Vzduch v ložnici", metric(bedroom?.obj("co2"), 0)),
            arrayOf("overview", "drop", "Hladina studny a nádrže", metric(d.obj("water").obj("wellDepth"), 2) + "   ·   " + metric(d.obj("water").obj("tankDepth"), 2)),
            arrayOf("cleaning", "spark", "Pračka", status(d.obj("washer").obj("machine").string("state")))
        )
        entries.forEachIndexed { index, item ->
            val r = RectF(34f + index * 347f, 404f, 365f + index * 347f, 474f)
            val action = "quick:$index"; target(action, item[2], r, "quick")
            val saved = c.save()
            if (selection == action) { val scale = 1f + .025f * focusProgress; c.scale(scale, scale, r.centerX(), r.centerY()) }
            card(c, r, 20f); focus(c, action, r, 20f)
            icon(c, item[1], r.left + 17f, r.top + 24f, 22f, muted())
            text(c, item[2], r.left + 53f, r.top + 25f, 12f, muted(), 248f)
            text(c, item[3], r.left + 53f, r.top + 51f, 18f, ink(), 248f, true)
            c.restoreToCount(saved)
        }
        text(c, "Tvoje aplikace", 34f, 512f, 20f, ink(), 600f, true)
        drawAppRow(c, installed.take(6), 534f)
        homeStrip(c)
    }
    private fun weatherCard(c: Canvas, r: RectF) {
        card(c, r); icon(c, "sun", r.left + 20f, r.top + 20f, 18f, muted()); text(c, "Venku právě teď", r.left + 48f, r.top + 35f, 14f, ink(), 260f, true)
        val weather = data().obj("weather")
        weather.number("feelsLike")?.let { text(c, "Pocitově " + reading(it, weather.obj("units").string("feelsLike").let { unit -> if (unit.isBlank()) "" else " $unit" }, 1), r.right - 150f, r.top + 34f, 10f, muted(), 130f) }
        paint.shader = null; paint.color = ink(); paint.textSize = 62f; paint.typeface = lightFace
        c.drawText(reading(weather.number("temperature"), "°", 1), r.left + 22f, r.top + 104f, paint)
        val condition = weather.string("condition")
        val rainUnit = weather.obj("units").string("rainRate").lowercase(locale)
        val description = mapOf("sunny" to "Jasno", "partlycloudy" to "Polojasno", "cloudy" to "Zataženo", "rainy" to "Déšť", "pouring" to "Silný déšť", "snowy" to "Sněžení", "snowy-rainy" to "Déšť se sněhem", "clear-night" to "Jasná noc", "fog" to "Mlha", "lightning" to "Bouřka", "lightning-rainy" to "Bouřka s deštěm")[condition] ?: if ((weather.number("rainRate") ?: 0.0) > 0 && rainUnit in listOf("mm/h", "mm/hr", "in/h", "in/hr")) "Prší u domu" else "Vlastní měření u domu"
        text(c, if (live()) description else "Údaje nejsou dostupné", r.left + 24f, r.top + 126f, 14f, muted(), 345f)
        val solar = data().obj("solar"); val elevation = solar.number("elevation"); val azimuth = solar.number("azimuth")
        if (solar.string("state") == "above_horizon" && elevation != null && elevation in 0.0..90.0 && azimuth != null && azimuth in 0.0..360.0) {
            val direction = (solar.opt("rising") as? Boolean)?.let { if (it) "Slunce stoupá" else "Slunce klesá" } ?: "Poloha slunce"
            text(c, direction + " · " + reading(elevation, "°", 1) + " · " + reading(azimuth, "°", 0), r.left + 24f, r.top + 141f, 10f, muted(), 410f)
        }
        weatherArtwork(c, condition, solar.string("state") == "above_horizon", RectF(r.right - 119f, r.top + 50f, r.right - 25f, r.top + 124f))
        line(c, r.left + 22f, r.top + 149f, r.right - 22f, r.top + 149f)
        val units = weather.obj("units")
        listOf(Triple("wind", "windSpeed", "Vítr"), Triple("wind", "windGust", "Nárazy"), Triple("compass", "windDirection", "Směr"), Triple("drop", "humidity", "Vlhkost"), Triple("rain", "rainRate", "Déšť"), Triple("gauge", "pressure", "Tlak")).forEachIndexed { index, item ->
            val x = r.left + 24f + index % 3 * 145f; val y = r.top + 163f + index / 3 * 30f
            icon(c, item.first, x, y + 2f, 16f, muted()); text(c, item.third, x + 23f, y, 9f, muted(), 110f)
            val unit = units.string(item.second).let { if (it.isBlank()) "" else " $it" }
            text(c, reading(weather.number(item.second), unit, if (item.second == "rainRate") 1 else 0), x + 23f, y + 16f, 12f, ink(), 110f)
        }
    }
    private fun calendarCard(c: Canvas, r: RectF) {
        card(c, r); icon(c, "calendar", r.left + 20f, r.top + 20f, 18f, muted()); text(c, "Rodinný kalendář", r.left + 48f, r.top + 35f, 14f, ink(), 280f, true)
        target("open:calendar", "Rodinný kalendář", r, "cards"); focus(c, "open:calendar", r)
        val square = RectF(r.left + 22f, r.top + 59f, r.left + 75f, r.top + 116f); fill(c, square, if (dark()) Color.argb(28, 255, 255, 255) else Color.argb(150, 255, 255, 255), 12f)
        centered(c, format(now, "MMM").uppercase(locale).replace(".", ""), square.centerX(), square.top + 17f, 9f, muted()); centered(c, format(now, "d"), square.centerX(), square.bottom - 11f, 26f, ink())
        text(c, format(now, "EEEE"), r.left + 91f, r.top + 80f, 15f, ink(), 300f, true)
        val events = events(); text(c, if (calendarAvailable()) "${events.size} událostí v přehledu" else "Kalendář není dostupný", r.left + 91f, r.top + 105f, 11f, muted(), 300f)
        events.take(2).forEachIndexed { index, event -> eventLine(c, event, r.left + 24f, r.top + 147f + index * 43f, r.width() - 48f, false) }
        if (events.isEmpty()) text(c, if (calendarAvailable()) "Žádné nadcházející události." else "Čekám na rodinný kalendář.", r.left + 24f, r.top + 160f, 12f, muted(), r.width() - 48f)
    }
    private fun cameraCard(c: Canvas, camera: JSONObject?, r: RectF, row: String) {
        card(c, r); val id = camera?.string("id") ?: ""; val available = live() && camera?.optBoolean("available") == true
        val area = RectF(r.left, r.top, r.right, r.bottom - 47f)
        if (id.isNotEmpty()) { target("camera:$id", camera?.string("name", "Kamera") ?: "Kamera", r, row); focus(c, "camera:$id", r); cameraAreas[id] = RectF(area) }
        val bitmap = if (available) cameraImages[id] else null
        val clipped = c.save(); path.reset(); path.addRoundRect(r, 24f, 24f, Path.Direction.CW); c.clipPath(path)
        fill(c, area, Color.rgb(12, 16, 23))
        if (bitmap?.isRecycled == false) image(c, bitmap, area, 0f, false) else { icon(c, "camera", area.centerX() - 22f, area.centerY() - 20f, 44f, muted()); centered(c, if (available) "Načítám obraz…" else "Kamera není dostupná", area.centerX(), area.centerY() + 44f, 12f, muted()) }
        c.restoreToCount(clipped)
        text(c, camera?.string("name", "Kamera") ?: "Kamera", r.left + 18f, r.bottom - 19f, 13f, ink(), r.width() - 65f); icon(c, "chevron", r.right - 35f, r.bottom - 31f, 18f, muted())
    }
    private fun drawAppRow(c: Canvas, apps: List<App>, top: Float, row: String = "apps") {
        if (apps.isEmpty()) { text(c, "Žádné vybrané TV aplikace nejsou dostupné.", 34f, top + 62f, 16f, muted(), 1000f); return }
        apps.forEachIndexed { index, app ->
            val x = 34f + index * 232f; val outer = RectF(x, top, x + 212f, top + 137f); val action = "app:${app.id}"; target(action, app.name, outer, row)
            val selected = selection == action; val art = RectF(x, top, x + 212f, top + 99f)
            if (selected) { art.inset(-(4f + 4f * focusProgress), -(4f + 4f * focusProgress)); val halo = RectF(art); halo.inset(-7f, -7f); fill(c, halo, if (dark()) Color.argb(64, 220, 231, 248) else Color.argb(155, 255, 255, 255), 27f) }
            drawBrandTile(c, app, art)
            if (selected) fill(c, art, Color.argb(16, 255, 255, 255), 20f)
        }
    }
    private fun iconEdgeColor(bitmap: Bitmap): Int {
        if (bitmap.width < 1 || bitmap.height < 1) return Color.TRANSPARENT
        val right = bitmap.width - 1; val bottom = bitmap.height - 1
        val samples = listOf(0 to 0, right to 0, 0 to bottom, right to bottom, right / 2 to 0, right / 2 to bottom, 0 to bottom / 2, right to bottom / 2)
        var red = 0L; var green = 0L; var blue = 0L; var weight = 0L
        samples.forEach { (x, y) -> val color = bitmap.getPixel(x, y); val alpha = Color.alpha(color); if (alpha > 32) { red += Color.red(color) * alpha; green += Color.green(color) * alpha; blue += Color.blue(color) * alpha; weight += alpha } }
        return if (weight == 0L) if (dark()) Color.rgb(28, 31, 38) else Color.WHITE else Color.rgb((red / weight).toInt(), (green / weight).toInt(), (blue / weight).toInt())
    }
    private fun drawBrandTile(c: Canvas, app: App, r: RectF): Boolean {
        val colors = when (app.id) {
            NETFLIX -> intArrayOf(Color.rgb(35, 38, 44), Color.rgb(8, 10, 15))
            "net.cme.voyo.cz.tvapp" -> intArrayOf(Color.rgb(67, 37, 192), Color.rgb(103, 56, 237))
            "cz.prima.tvapp" -> intArrayOf(Color.rgb(243, 45, 114), Color.rgb(236, 34, 102))
            "com.google.android.youtube.tv" -> intArrayOf(Color.rgb(246, 247, 250), Color.rgb(240, 241, 246))
            "com.spotify.tv.android" -> intArrayOf(Color.rgb(32, 52, 43), Color.rgb(16, 35, 27))
            "org.videolan.vlc" -> intArrayOf(Color.rgb(255, 195, 122), Color.rgb(228, 122, 34))
            else -> intArrayOf(app.color, darken(app.color))
        }
        val saved = c.save(); path.reset(); path.addRoundRect(r, 20f, 20f, Path.Direction.CW); c.clipPath(path)
        paint.color = Color.WHITE; paint.shader = LinearGradient(r.left, r.top, r.right, r.bottom, colors[0], colors[1], Shader.TileMode.CLAMP); c.drawRect(r, paint); paint.shader = null
        // A static diagonal glass reflection, without blur, shadow layers or animation.
        path.reset(); path.moveTo(r.left, r.top); path.lineTo(r.right, r.top); path.lineTo(r.left + r.width() * .1f, r.bottom); path.lineTo(r.left, r.bottom); path.close()
        paint.color = Color.argb(9, 255, 255, 255); c.drawPath(path, paint)
        if (app.id == "com.google.android.youtube.tv") {
            paint.textSize = 26f; paint.typeface = brandFace; val width = 44f + paint.measureText("YouTube"); val left = r.centerX() - width / 2f
            val logo = RectF(left, r.centerY() - 12f, left + 36f, r.centerY() + 13f); fill(c, logo, Color.rgb(244, 37, 44), 8f)
            paint.color = Color.WHITE; path.reset(); path.moveTo(logo.left + 14f, logo.top + 6f); path.lineTo(logo.left + 25f, logo.centerY()); path.lineTo(logo.left + 14f, logo.bottom - 6f); path.close(); c.drawPath(path, paint)
            paint.color = Color.rgb(23, 25, 29); paint.textSize = 26f; paint.typeface = brandFace; c.drawText("YouTube", left + 44f, r.centerY() + 9f, paint)
        } else if (app.id !in listOf(NETFLIX, "net.cme.voyo.cz.tvapp", "cz.prima.tvapp", "com.spotify.tv.android", "org.videolan.vlc")) {
            val glyph = when (app.id) { "com.tcl.gallery" -> "photo"; "com.tcl.browser" -> "compass"; "com.tcl.ui_mediaCenter" -> "play"; "flar2.homebutton" -> "remote"; else -> "play" }
            icon(c, glyph, r.centerX() - 14f, r.centerY() - 33f, 28f, Color.WHITE)
            centered(c, app.mark, r.centerX(), r.centerY() + 28f, 23f, Color.WHITE, true)
        } else {
            val title = when (app.id) { NETFLIX -> "NETFLIX"; "net.cme.voyo.cz.tvapp" -> "oneplay"; "cz.prima.tvapp" -> "prima+"; "com.spotify.tv.android" -> "Spotify"; else -> "VLC" }
            paint.color = when (app.id) { NETFLIX -> Color.rgb(237, 24, 40); "com.spotify.tv.android" -> Color.rgb(68, 220, 130); else -> Color.WHITE }
            paint.typeface = if (app.id == NETFLIX) netflixFace else brandFace; paint.textSize = if (app.id == NETFLIX) 28f else 29f
            if (app.id == NETFLIX) {
                val gap = 1.1f; val total = paint.measureText(title) + gap * (title.length - 1); var x = r.centerX() - total / 2f
                title.forEach { letter -> val glyph = letter.toString(); c.drawText(glyph, x, r.centerY() + 10f, paint); x += paint.measureText(glyph) + gap }
            } else c.drawText(title, r.centerX() - paint.measureText(title) / 2f, r.centerY() + 10f, paint)
        }
        c.restoreToCount(saved)
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f; paint.color = Color.argb(42, 255, 255, 255); c.drawRoundRect(r, 20f, 20f, paint); paint.style = Paint.Style.FILL
        return true
    }
    private fun weatherArtwork(c: Canvas, condition: String, aboveHorizon: Boolean, r: RectF) {
        if (condition.isBlank()) return
        val saved = c.save(); c.translate(r.left, r.top); c.scale(r.width() / 95f, r.height() / 83f)
        val cloudy = condition !in listOf("sunny", "clear-night")
        if (aboveHorizon && condition in listOf("sunny", "partlycloudy")) {
            paint.color = Color.WHITE; paint.shader = LinearGradient(43f, 1f, 88f, 49f, Color.rgb(255, 232, 166), Color.rgb(239, 182, 104), Shader.TileMode.CLAMP)
            c.drawCircle(66f, 26f, 25.5f, paint); paint.shader = null
        } else if (condition == "clear-night") {
            paint.color = Color.WHITE; paint.shader = LinearGradient(46f, 0f, 87f, 49f, Color.rgb(239, 242, 252), Color.rgb(174, 192, 221), Shader.TileMode.CLAMP); c.drawCircle(66f, 26f, 23f, paint); paint.shader = null
        }
        if (cloudy) {
            NativeCloudSilhouette.build(path, 1f, 14f, 83f, 59f)
            paint.color = Color.WHITE; paint.shader = LinearGradient(8f, 20f, 78f, 69f, Color.rgb(243, 244, 253), Color.rgb(183, 199, 221), Shader.TileMode.CLAMP)
            c.drawPath(path, paint); paint.shader = null
            if (condition in listOf("rainy", "pouring", "lightning-rainy", "snowy-rainy")) { paint.color = Color.rgb(120, 153, 193); paint.strokeWidth = 2.4f; paint.strokeCap = Paint.Cap.ROUND; repeat(3) { val x = 19f + it * 22f; c.drawLine(x, 76f, x - 3f, 82f, paint) } }
            if (condition in listOf("snowy", "snowy-rainy", "hail")) { paint.color = Color.rgb(229, 239, 252); repeat(3) { c.drawCircle(17f + it * 24f, 78f, 2.8f, paint) } }
        }
        c.restoreToCount(saved)
    }
    private fun homeStrip(c: Canvas) {
        val r = RectF(34f, 735f, 1406f, 781f); card(c, r, 18f)
        val home = data().obj("home"); val locks = home.array("locks").objects(); val lights = home.array("lights").objects().filter { it.string("role") != "controller-status" }
        val unlocked = locks.any { it.string("state") == "unlocked" }
        val lockText = when { locks.isEmpty() || locks.any { it.string("state") !in listOf("locked", "unlocked") } -> "Stav neznámý"; unlocked -> "Odemčeno"; else -> "Zamčeno" }
        val on = lights.filter { it.string("state") == "on" }; val lightText = if (on.isNotEmpty()) "Svítí · " + on.take(2).joinToString(", ") { it.string("area", it.string("name")) } else if (lights.isEmpty() || lights.any { it.string("state") !in listOf("on", "off") }) "Světla · neúplný stav" else "Vybraná světla zhasnutá"
        icon(c, "home", 52f, 749f, 18f, muted()); text(c, "Stav domu", 79f, 766f, 12f, muted(), 115f)
        icon(c, if (unlocked) "unlock" else "lock", 207f, 749f, 18f, muted()); text(c, lockText, 235f, 765f, 12f, ink(), 155f)
        icon(c, "bulb", 412f, 749f, 18f, if (on.isNotEmpty()) Color.rgb(189, 153, 86) else muted()); text(c, lightText, 440f, 765f, 12f, ink(), 455f)
        line(c, 913f, 748f, 913f, 768f)
        listOf(Triple("household:security", "Zabezpečení", RectF(924f, 740f, 1099f, 776f)), Triple("kino", "Kino", RectF(1110f, 740f, 1210f, 776f)), Triple("night", "Dobrou noc", RectF(1221f, 740f, 1386f, 776f))).forEach { (id, label, b) ->
            target(id, label, b, "status"); focus(c, id, b, 16f)
            paint.textSize = 12f; paint.typeface = regularFace; val left = b.centerX() - (paint.measureText(label) + 26f) / 2f
            icon(c, when (id) { "kino" -> "play"; "night" -> "moon"; else -> "shield" }, left, 749f, 18f, ink())
            text(c, label, left + 26f, 765f, 12f, ink(), b.width() - 35f)
        }
    }

    private fun drawApps(c: Canvas) {
        text(c, "Tvoje aplikace", 34f, 196f, 27f, ink(), 600f, true)
        text(c, "Nainstalované aplikace v televizi", 34f, 229f, 14f, muted(), 900f)
        drawAppRow(c, installed.take(6), 270f, "apps-0")
        if (installed.size > 6) drawAppRow(c, installed.drop(6), 435f, "apps-1")
        val webs = bootstrap.array("apps").objects(4)
        if (webs.isNotEmpty()) {
            text(c, "Rodinné weby", 34f, 636f, 20f, ink(), 600f, true)
            webs.forEachIndexed { index, app -> val id = app.string("id"); if (id.isNotBlank()) smallCard(c, "webapp:$id", app.string("name", "Vlastní aplikace"), app.string("description", "Vlastní rodinný web"), RectF(34f + index * 347f, 654f, 365f + index * 347f, 751f), "webapps") }
        }
    }
    private fun drawMedia(c: Canvas) {
        card(c, RectF(34f, 172f, 845f, 700f)); icon(c, "play", 365f, 276f, 100f, muted()); centered(c, "Tvůj večer, tvoje média.", 438f, 454f, 30f, ink()); centered(c, "Video, hudba nebo fotografie přímo na velké obrazovce.", 438f, 495f, 15f, muted())
        button(c, "media:play", "Otevřít odkaz na médium", RectF(250f, 550f, 628f, 604f), "media")
        val r = RectF(866f, 172f, 1406f, 700f); card(c, r); text(c, "Televize a přehrávače", 891f, 211f, 20f, ink(), 480f, true)
        val players = data().array("mediaPlayers").objects(5)
        players.forEachIndexed { index, item -> text(c, item.string("name", "Přehrávač"), 891f, 267f + index * 77f, 16f, ink(), 480f); text(c, status(item.string("state")) + item.string("source").let { if (it.isBlank()) "" else " · $it" }, 891f, 292f + index * 77f, 13f, muted(), 480f) }
        if (players.isEmpty()) text(c, "Žádný přehrávač není připojený.", 891f, 267f, 15f, muted(), 480f)
    }
    private fun drawCameras(c: Canvas) {
        text(c, "Tvůj domov pod dohledem", 34f, 191f, 24f, ink(), 1000f, true)
        val cameras = data().array("cameras").objects(4)
        cameras.forEachIndexed { index, camera -> val x = 34f + (index % 2) * 694f; val y = 214f + (index / 2) * 266f; cameraCard(c, camera, RectF(x, y, x + 678f, y + 248f), "cameras-${index / 2}") }
        if (cameras.isEmpty()) text(c, "Kamery nejsou dostupné. Čekám na ověřená data HA.", 34f, 282f, 18f, muted(), 1200f)
    }
    private fun drawCalendar(c: Canvas) {
        val days = calendarProjection.days(now)
        if (calendarDay != null && days.none { it.key == calendarDay }) {
            calendarDay = null; calendarPage = 0
            if (selection.startsWith("calendar:day:")) selection = "calendar:day:all"
        }
        button(c, "calendar:day:all", "Celý týden", RectF(34f, 173f, 193f, 232f), "calendar-days", calendarDay == null)
        days.forEachIndexed { index, day ->
            val x = 34f + (index + 1) * 173.5f
            button(c, "calendar:day:${day.key}", day.shortLabel, RectF(x, 173f, x + 159f, 232f), "calendar-days", calendarDay == day.key)
        }
        val list = calendarProjection.occurrences(now, calendarDay, live()); val pageCount = max(1, (list.size + 3) / 4); calendarPage = calendarPage.coerceIn(0, pageCount - 1)
        card(c, RectF(34f, 254f, 1406f, 663f))
        val first = calendarPage * 4
        for (index in 0 until minOf(4, list.size - first)) {
            val row = list[first + index]; val y = 291f + index * 91f
            text(c, row.day.longLabel, 59f, y, 12f, muted(), 1200f); eventLine(c, row.event, 60f, y + 32f, 1314f, true)
        }
        if (list.isEmpty()) text(c, if (calendarAvailable()) "Na vybrané dny nemáte žádné události." else "Rodinný kalendář není dostupný.", 59f, 322f, 20f, muted(), 1250f)
        pager(c, "calendar", calendarPage, pageCount, 691f)
    }

    private fun drawHousehold(c: Canvas) {
        val sections = listOf("overview" to "Dům a energie", "rooms" to "Místnosti", "cleaning" to "Úklid", "security" to "Zabezpečení a rodina")
        sections.forEachIndexed { index, item -> button(c, "household:${item.first}", item.second, RectF(34f + index * 347f, 172f, 365f + index * 347f, 220f), "household-tabs", householdSection == item.first) }
        when (householdSection) { "rooms" -> drawRooms(c); "cleaning" -> drawCleaning(c); "security" -> drawSecurity(c); else -> drawEnergy(c) }
    }
    private fun drawEnergy(c: Canvas) {
        val d = data().obj("domestic"); val energy = d.obj("energy"); val water = d.obj("water"); val hot = d.obj("hotWater")
        card(c, RectF(34f, 244f, 640f, 508f)); badge(c, "energy", 59f, 257f, Color.rgb(171, 136, 73)); text(c, "Energie domu", 109f, 283f, 21f, ink(), 330f, true)
        chip(c, if (energy.number("powerW") != null) "Živé měření" else "Údaj chybí", 474f, 260f, 140f, Color.rgb(144, 157, 137))
        text(c, "Celkový příkon", 59f, 331f, 14f, muted()); text(c, power(energy.number("powerW")), 59f, 390f, 48f, ink(), 530f)
        text(c, "Dnešní spotřeba", 59f, 445f, 14f, muted()); text(c, energy.number("todayKWh")?.let { reading(it, " kWh", 2) } ?: metric(energy.obj("today"), 2), 330f, 445f, 25f, ink(), 270f)
        line(c, 59f, 466f, 615f, 466f); text(c, "Aktuální příkon a dnešní součet z Home Assistantu", 59f, 490f, 11f, muted(), 550f)
        card(c, RectF(660f, 244f, 1406f, 508f)); badge(c, "drop", 685f, 257f, Color.rgb(95, 143, 175)); text(c, "Studna a zahrada", 735f, 283f, 21f, ink(), 600f, true)
        val conflict = water.obj("wellVolume").number("value") == 0.0 && (water.obj("wellLevel").number("value") ?: 0.0) > 0
        readingBlock(c, "Hladina studny", metric(water.obj("wellDepth"), 2), 685f, 329f, 220f)
        readingBlock(c, "Hladina nádrže", metric(water.obj("tankDepth"), 2), 925f, 329f, 220f)
        readingBlock(c, "Objem studny", if (conflict) "Neověřeno" else metric(water.obj("wellVolume"), 0), 1165f, 329f, 210f)
        if (conflict) text(c, "Odvozený objem a naměřená hladina se rozcházejí.", 685f, 452f, 12f, muted(), 680f)
        card(c, RectF(34f, 528f, 640f, 735f)); badge(c, "thermometer", 59f, 540f, Color.rgb(183, 133, 109)); text(c, "Teplá voda", 109f, 566f, 21f, ink(), 500f, true)
        readingBlock(c, "Horní čidlo", metric(hot.obj("top")), 59f, 608f, 170f); readingBlock(c, "Druhé čidlo", metric(hot.obj("bottom")), 250f, 608f, 170f); readingBlock(c, "Příkon bojleru", metric(hot.obj("power"), 0), 441f, 608f, 170f)
        card(c, RectF(660f, 528f, 1406f, 735f)); badge(c, "drop", 685f, 540f, Color.rgb(111, 152, 124)); text(c, "Voda v zahradě", 735f, 566f, 21f, ink(), 600f, true)
        water.array("valves").objects(3).forEachIndexed { index, valve -> text(c, valve.string("name", "Ventil"), 685f, 611f + index * 36f, 14f, ink(), 480f); text(c, if (valve.optBoolean("available")) status(valve.string("state")) else "Stav neznámý", 1200f, 611f + index * 36f, 13f, muted(), 175f) }
    }
    private fun drawRooms(c: Canvas) {
        val rooms = if (live()) roomProjection else emptyList()
        val pages = max(1, (rooms.size + 8) / 9); roomPage = roomPage.coerceIn(0, pages - 1)
        val first = roomPage * 9
        for (index in 0 until minOf(9, rooms.size - first)) {
            val room = rooms[first + index]
            val x = 34f + index % 3 * 463f; val y = 245f + index / 3 * 162f
            val r = RectF(x, y, x + 446f, y + 151f); card(c, r)
            val action = "room:${room.id}"; target(action, room.name, r, "rooms-${index / 3}"); focus(c, action, r)
            val accent = if (room.heating == true) Color.rgb(188, 126, 96) else Color.rgb(119, 151, 166)
            text(c, room.name, x + 20f, y + 30f, 17f, ink(), 245f, true)
            chip(c, room.activity, x + 276f, y + 10f, 150f, accent)
            text(c, room.temperature, x + 20f, y + 72f, 28f, if (room.temperatureAvailable) accent else muted(), 215f)
            text(c, "Vlhkost  ${room.humidity}", x + 250f, y + 69f, 15f, ink(), 177f)
            text(c, "CO₂  ${room.co2}", x + 20f, y + 104f, 15f, ink(), 215f)
            text(c, "PM2,5  ${room.pm25}", x + 250f, y + 104f, 14f, ink(), 177f)
            line(c, x + 20f, y + 116f, x + 426f, y + 116f)
            text(c, room.target, x + 20f, y + 138f, 11.5f, muted(), 406f)
        }
        if (rooms.isEmpty()) text(c, "Pokojová měření nejsou dostupná.", 34f, 300f, 20f, muted(), 1300f)
        if (pages > 1) pager(c, "rooms", roomPage, pages, 740f)
    }
    private fun drawCleaning(c: Canvas) {
        val d = data().obj("domestic"); val washer = d.obj("washer"); val vacuum = d.obj("vacuum"); val map = vacuum.obj("map")
        card(c, RectF(34f, 245f, 698f, 733f)); badge(c, "washer", 59f, 258f, Color.rgb(115, 149, 184)); text(c, "Pračka", 109f, 285f, 23f, ink(), 350f, true)
        chip(c, if (washer.optBoolean("available")) "Připojená" else "Stav neznámý", 497f, 263f, 175f, Color.rgb(139, 155, 145))
        icon(c, "washer", 71f, 335f, 105f, muted()); text(c, if (washer.obj("job").optBoolean("available")) status(washer.obj("job").string("state")) else "Fáze není dostupná", 220f, 370f, 28f, ink(), 448f)
        text(c, washerCountdown(washer), 220f, 411f, 16f, muted(), 448f)
        val percent = washer.number("progressPercent"); if (percent != null) { fill(c, RectF(59f, 472f, 672f, 482f), if (dark()) Color.argb(40, 255, 255, 255) else Color.argb(30, 25, 45, 65), 6f); fill(c, RectF(59f, 472f, 59f + 613f * (percent / 100).coerceIn(0.0, 1.0).toFloat(), 482f), Color.rgb(139, 170, 202), 6f) }
        readingBlock(c, "Celková energie", metric(washer.obj("energy"), 2), 59f, 530f, 185f); readingBlock(c, "Celková voda", metric(washer.obj("water"), 1), 268f, 530f, 185f); readingBlock(c, "Příkon", metric(washer.obj("power"), 0), 477f, 530f, 185f)
        text(c, "Konec praní potvrzuje skutečný stav programu. Čas je pouze odhad.", 59f, 698f, 12f, muted(), 610f)
        card(c, RectF(720f, 245f, 1406f, 733f)); badge(c, "robot", 745f, 258f, Color.rgb(130, 154, 149)); text(c, vacuum.string("name", "Robotický vysavač"), 795f, 285f, 22f, ink(), 410f, true)
        chip(c, reading(vacuum.number("batteryPercent"), " %", 0), 1267f, 262f, 110f, Color.rgb(130, 154, 149))
        text(c, (if (vacuum.optBoolean("available")) status(vacuum.string("state")) else "Robot není dostupný") + "   ·   Baterie " + reading(vacuum.number("batteryPercent"), " %", 0), 745f, 319f, 14f, muted(), 630f)
        val area = RectF(745f, 343f, 1381f, 613f); target("vacuum:map", "Poslední mapa vysavače", area, "cleaning")
        if (map.optBoolean("available") && vacuumMap?.isRecycled == false) image(c, vacuumMap, area, 15f, false) else { fill(c, area, if (dark()) Color.argb(35, 0, 0, 0) else Color.argb(70, 225, 231, 239), 15f); centered(c, if (map.optBoolean("available")) "Načítám poslední mapu…" else "Mapa není dostupná", area.centerX(), area.centerY(), 17f, muted()) }
        focus(c, "vacuum:map", area, 15f); text(c, "Poslední aktualizace " + timeLabel(map.string("lastUpdated")), 745f, 647f, 12f, muted(), 630f)
        text(c, "Plocha " + metric(vacuum.obj("cleanedArea")) + "    Čas " + metric(vacuum.obj("cleaningTime"), 0), 745f, 680f, 15f, ink(), 630f)
        text(c, "Poslední mapa nepotvrzuje aktuální spojení s robotem.", 745f, 710f, 12f, muted(), 630f)
    }
    private fun drawSecurity(c: Canvas) {
        val h = data().obj("household"); val security = h.array("security").objects(3)
        security.forEachIndexed { index, item ->
            val x = 34f + index * 463f; val r = RectF(x, 244f, x + 446f, 359f); val action = "security:${item.string("id")}"
            val accent = when (item.string("state")) { "armed" -> Color.rgb(111, 156, 133); "arming" -> Color.rgb(180, 144, 93); "disarmed" -> Color.rgb(117, 147, 175); else -> Color.rgb(146, 149, 158) }
            card(c, r); target(action, item.string("name", "Zabezpečení"), r, "security"); focus(c, action, r)
            badge(c, "lock", x + 20f, 263f, accent)
            text(c, item.string("name", "Zabezpečení"), x + 69f, 279f, 18f, ink(), 350f, true)
            chip(c, securityLabel(item.string("state")), x + 69f, 292f, 217f, accent)
            text(c, if (item.optBoolean("coverageComplete")) "Všechny kontakty dostupné" else "${item.optInt("availableContactCount")}/${item.optInt("contactCount")} kontaktů dostupných", x + 69f, 341f, 11f, muted(), 350f)
        }
        card(c, RectF(34f, 379f, 936f, 734f)); text(c, "Kde jsme", 59f, 414f, 20f, ink(), 820f, true); drawMap(c, h, RectF(59f, 430f, 911f, 688f))
        val people = h.array("people").objects(6); text(c, people.joinToString("   ·   ") { it.string("name") + ": " + if (!it.optBoolean("available")) "Poloha neznámá" else if (it.string("state") == "home") "Doma" else it.string("zoneName", "Mimo domov") }, 59f, 716f, 12f, muted(), 820f)
        smallCard(c, "open:notices", "Co potřebuje pozornost", "Oznámení a připomínky domácnosti", RectF(958f, 379f, 1406f, 480f), "family")
        smallCard(c, "night", "Dobrou noc", "Zobrazit plán domácího trojkliku", RectF(958f, 500f, 1406f, 601f), "family")
        smallCard(c, "open:calendar", "Dnes doma", if (!calendarAvailable()) "Rodinný kalendář není dostupný." else if (events().isEmpty()) "Žádné nadcházející události." else events().take(2).joinToString(" · ") { it.title }, RectF(958f, 621f, 1406f, 734f), "family")
    }

    private fun drawSaver(c: Canvas) {
        text(c, "HA TV Launcher", 60f, 65f, 27f, ink(), 600f, true)
        if (profile?.isRecycled == false) image(c, profile, RectF(60f, 90f, 125f, 155f), 33f)
        centered(c, format(now, "HH:mm"), 720f, 405f, 136f, ink()); centered(c, format(now, "EEEE d. MMMM yyyy"), 720f, 463f, 26f, ink())
        val title = if (live()) "HA · aktualizováno " + timeLabel(dashboard.string("updatedAt")) else "Aktuální stav domu není ověřený"
        centered(c, title, 720f, 742f, 13f, muted()); button(c, "saver:stop", "Zpět na přehled", RectF(1175f, 34f, 1385f, 80f), "saver")
    }
    private fun pager(c: Canvas, kind: String, page: Int, pages: Int, y: Float) {
        if (pages <= 1) return
        if (page > 0) button(c, "page:$kind:prev", "‹ Předchozí", RectF(34f, y, 216f, y + 43f), "pager")
        centered(c, "${page + 1} / $pages", 306f, y + 28f, 14f, muted())
        if (page + 1 < pages) button(c, "page:$kind:next", "Další ›", RectF(396f, y, 558f, y + 43f), "pager")
    }
    private fun eventLine(c: Canvas, event: NativeCalendarProjection.Event, x: Float, y: Float, width: Float, large: Boolean) {
        fill(c, RectF(x, y - 16f, x + 3f, y + 14f), if (dark()) Color.rgb(180, 193, 222) else Color.rgb(140, 164, 199), 2f)
        if (large) text(c, event.timeLabel, x + 13f, y, 14f, muted(), 75f)
        else {
            text(c, event.dateLabel, x + 13f, y - 2f, 10.5f, ink(), 75f, true)
            text(c, event.timeLabel, x + 13f, y + 14f, 10f, muted(), 75f)
        }
        text(c, event.title, x + 94f, y, if (large) 19f else 13f, ink(), width - 100f, true)
        text(c, event.calendar, x + 94f, y + if (large) 24f else 18f, if (large) 12f else 10f, muted(), width - 100f)
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (saver) { if (event.repeatCount == 0) { setScreenSaver(false); tab = Tab.HOME; onAction("saver:stop") }; return true }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { if (event.repeatCount == 0) activate(selection); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { move(event.keyCode); return true }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> { val values = Tab.values(); val delta = if (event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP) 1 else -1; selectHeaderTab(values[(tab.ordinal + delta + values.size) % values.size]); return true }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { if (tab != Tab.HOME) { tab = Tab.HOME; onAction("tab:home"); return true }; return false }
        }
        return false
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = handleKey(event) || super.onKeyDown(keyCode, event)
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (saver) {
            if (event.action == MotionEvent.ACTION_UP) { setScreenSaver(false); tab = Tab.HOME; onAction("saver:stop") }
            return true
        }
        if (event.action == MotionEvent.ACTION_UP) { val x = (event.x - offsetX) / drawScale; val y = (event.y - offsetY) / drawScale; targets.lastOrNull { it.bounds.contains(x, y) }?.let { selection = it.action; pendingHomeFocus = false; activate(it.action) }; return true }
        return event.action == MotionEvent.ACTION_DOWN
    }
    private fun move(key: Int) {
        pendingHomeFocus = false
        val current = targets.find { it.action == selection }
        if (current == null) {
            if (tab == Tab.HOME) focusNetflix()
            else (targets.find { it.action == "tab:${tab.key}" } ?: targets.firstOrNull())?.let(::select)
            return
        }
        val horizontal = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
        if (horizontal) {
            val peers = targets.filter { it.row == current.row }.sortedBy { it.bounds.centerX() }
            val index = peers.indexOf(current); val next = peers[(index + if (key == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1).coerceIn(0, peers.size - 1)]
            if (current.row == "header" && next.action.startsWith("tab:") && next.action != current.action) {
                Tab.values().find { it.key == next.action.substringAfter(':') }?.let { selectHeaderTab(it) }; return
            }
            select(next); return
        }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN && current.row == "header") { val target = targets.find { it.action == "app:$NETFLIX" } ?: targets.firstOrNull { it.row.startsWith("apps") }; if (target != null) { select(target); return } }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN && current.row == "header" && tab == Tab.HOUSEHOLD) {
            targets.find { it.action == "household:$householdSection" }?.let { select(it); return }
        }
        val positive = key == KeyEvent.KEYCODE_DPAD_DOWN
        val candidates = targets.filter { it != current && (if (positive) it.bounds.centerY() - current.bounds.centerY() else current.bounds.centerY() - it.bounds.centerY()) > 8f }
        val next = candidates.minByOrNull { abs(it.bounds.centerY() - current.bounds.centerY()) + abs(it.bounds.centerX() - current.bounds.centerX()) * 2.3f }
        if (next != null) {
            val destination = if (key == KeyEvent.KEYCODE_DPAD_UP && current.row != "header" && next.row == "header") targets.find { it.action == "tab:${tab.key}" } ?: next else next
            select(destination)
        }
    }
    private fun selectHeaderTab(next: Tab) {
        val action = "tab:${next.key}"
        if (selection == action && tab == next) return
        tab = next
        // The HOME/APPS setters select content for true app returns. Arrow tab
        // switching is a different user action and must stay in the header.
        pendingHomeFocus = false
        selection = action
        animateFocus(); updateDescription(); sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED); invalidate()
        onAction("focus:$action")
        onAction(action)
    }
    private fun select(target: Target) { if (selection == target.action) return; selection = target.action; animateFocus(); updateDescription(); sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED); invalidate(); onAction("focus:${target.action}") }
    private fun activate(action: String) {
        when {
            action == "open:calendar" -> { tab = Tab.CALENDAR; onAction("tab:calendar") }
            action == "open:notices" -> onAction("notices")
            action.startsWith("quick:") -> { householdSection = when (action.substringAfter(':')) { "1" -> "rooms"; "3" -> "cleaning"; else -> "overview" }; tab = Tab.HOUSEHOLD; selection = "household:$householdSection"; onAction("tab:household") }
            action.startsWith("tab:") -> { Tab.values().find { it.key == action.substringAfter(':') }?.let { selectHeaderTab(it) } }
            action.startsWith("household:") -> { householdSection = action.substringAfter(':'); tab = Tab.HOUSEHOLD; selection = action; animatePage(); invalidate(); onAction("tab:household") }
            action.startsWith("page:") -> { val bits = action.split(':'); val delta = if (bits.last() == "next") 1 else -1; when (bits.getOrNull(1)) { "calendar" -> { calendarPage = max(0, calendarPage + delta); selection = "calendar:day:${calendarDay ?: "all"}" }; "rooms" -> { roomPage = max(0, roomPage + delta); selection = "household:rooms" } }; invalidate(); onAction("view:changed") }
            action.startsWith("calendar:day:") -> { calendarDay = action.substringAfterLast(':').takeUnless { it == "all" }; calendarPage = 0; selection = action; invalidate() }
            action == "saver:stop" -> { setScreenSaver(false); tab = Tab.HOME; onAction(action) }
            else -> onAction(action)
        }
    }

    private fun target(action: String, label: String, r: RectF, row: String) { if (targets.none { it.action == action }) targets.add(Target(action, label.take(180), RectF(r), row)) }
    private fun card(c: Canvas, r: RectF, radius: Float = 24f) { fill(c, r, glass(), radius); paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.35f; paint.color = Color.WHITE; val dark = dark(); val key = GradientKey(r.left, r.top, r.right, r.bottom, dark); paint.shader = cardGradients.getOrPut(key) { LinearGradient(r.left, r.top, r.right, r.bottom, Color.argb(if (dark) 52 else 218, 255, 255, 255), Color.argb(if (dark) 17 else 98, 255, 255, 255), Shader.TileMode.CLAMP) }; c.drawRoundRect(r, radius, radius, paint); paint.shader = null; paint.style = Paint.Style.FILL }
    private fun focus(c: Canvas, action: String, r: RectF, radius: Float = 24f) { if (selection == action) fill(c, r, if (dark()) Color.argb((32 + 22 * focusProgress).toInt(), 213, 226, 249) else Color.argb((95 + 55 * focusProgress).toInt(), 255, 255, 255), radius) }
    private fun button(c: Canvas, action: String, label: String, r: RectF, row: String, active: Boolean = false) { target(action, label, r, row); fill(c, r, if (active) if (dark()) Color.argb(95, 220, 230, 244) else Color.argb(205, 255, 255, 255) else glass(), 24f); focus(c, action, r, 24f); centered(c, label, r.centerX(), r.centerY() + 5f, 14f, ink()) }
    private fun smallCard(c: Canvas, action: String, title: String, subtitle: String, r: RectF, row: String) { card(c, r); target(action, title, r, row); focus(c, action, r); badge(c, when (action) { "open:notices" -> "bell"; "night" -> "moon"; "open:calendar" -> "calendar"; else -> "play" }, r.right - 55f, r.top + 16f, Color.rgb(129, 150, 178)); text(c, title, r.left + 22f, r.top + 35f, 19f, ink(), r.width() - 90f, true); text(c, subtitle, r.left + 22f, r.top + 69f, 13f, muted(), r.width() - 44f) }
    private fun readingBlock(c: Canvas, label: String, value: String, x: Float, y: Float, width: Float) { text(c, label, x, y, 13f, muted(), width); text(c, value, x, y + 45f, 28f, ink(), width) }
    private fun badge(c: Canvas, name: String, x: Float, y: Float, accent: Int) { fill(c, RectF(x, y, x + 36f, y + 36f), Color.argb(if (dark()) 49 else 36, Color.red(accent), Color.green(accent), Color.blue(accent)), 13f); icon(c, name, x + 8f, y + 8f, 20f, accent) }
    private fun chip(c: Canvas, label: String, x: Float, y: Float, width: Float, accent: Int) { fill(c, RectF(x, y, x + width, y + 26f), Color.argb(if (dark()) 43 else 32, Color.red(accent), Color.green(accent), Color.blue(accent)), 14f); text(c, label, x + 12f, y + 18f, 10.5f, if (dark()) ink() else Color.rgb((Color.red(accent) * .65).toInt(), (Color.green(accent) * .65).toInt(), (Color.blue(accent) * .65).toInt()), width - 24f) }
    private fun metricGauge(c: Canvas, metric: JSONObject, r: RectF, accent: Int) { if (!metric.optBoolean("available") || metric.string("unit") != "%") return; val value = metric.number("value")?.takeIf { it in 0.0..100.0 } ?: return; fill(c, r, Color.argb(30, Color.red(accent), Color.green(accent), Color.blue(accent)), 4f); if (value > 0) fill(c, RectF(r.left, r.top, r.left + r.width() * value.toFloat() / 100f, r.bottom), accent, 4f) }
    private fun fill(c: Canvas, r: RectF, color: Int, radius: Float = 0f) { paint.shader = null; paint.style = Paint.Style.FILL; paint.color = color; c.drawRoundRect(r, radius, radius, paint) }
    private fun line(c: Canvas, x: Float, y: Float, right: Float, bottom: Float) { paint.color = if (dark()) Color.argb(30, 255, 255, 255) else Color.argb(25, 35, 50, 65); paint.strokeWidth = 1f; c.drawLine(x, y, right, bottom, paint) }
    private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, maxWidth: Float = Float.POSITIVE_INFINITY, bold: Boolean = false) { paint.shader = null; paint.style = Paint.Style.FILL; paint.textSize = size; paint.color = color; paint.typeface = if (bold) mediumFace else regularFace; val clean = value.replace('\n', ' ').take(1000); val shown = if (maxWidth.isFinite() && paint.measureText(clean) > maxWidth) { val count = paint.breakText(clean, true, max(0f, maxWidth - paint.measureText("…")), null); clean.take(count) + "…" } else clean; c.drawText(shown, x, y, paint) }
    private fun centered(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) { paint.textSize = size; paint.typeface = if (bold) mediumFace else regularFace; val left = x - paint.measureText(value) / 2f; text(c, value, left, y, size, color, Float.POSITIVE_INFINITY, bold) }
    private fun image(c: Canvas, bitmap: Bitmap?, r: RectF, radius: Float, cover: Boolean = true) {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return
        val saved = c.save(); path.reset(); path.addRoundRect(r, radius, radius, Path.Direction.CW); c.clipPath(path)
        val scale = if (cover) max(r.width() / bitmap.width, r.height() / bitmap.height) else min(r.width() / bitmap.width, r.height() / bitmap.height)
        val w = bitmap.width * scale; val h = bitmap.height * scale; paint.color = Color.WHITE; paint.shader = null; c.drawBitmap(bitmap, null, RectF(r.centerX() - w / 2f, r.centerY() - h / 2f, r.centerX() + w / 2f, r.centerY() + h / 2f), paint); c.restoreToCount(saved)
    }
    private fun icon(c: Canvas, name: String, x: Float, y: Float, size: Float, color: Int) {
        val saved = c.save(); c.translate(x, y); c.scale(size / 24f, size / 24f); paint.shader = null; paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.6f; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND; path.reset()
        when (name) {
            "sun" -> { c.drawCircle(12f, 12f, 4f, paint); repeat(8) { val save = c.save(); c.rotate(it * 45f, 12f, 12f); c.drawLine(12f, 2f, 12f, 4f, paint); c.restoreToCount(save) } }
            "moon" -> { path.moveTo(17f, 3f); path.cubicTo(6f, 1f, 1f, 14f, 9f, 20f); path.cubicTo(16f, 25f, 23f, 20f, 22f, 15f); path.cubicTo(14f, 18f, 10f, 9f, 17f, 3f); c.drawPath(path, paint) }
            "home" -> { path.moveTo(3f, 10f); path.lineTo(12f, 3f); path.lineTo(21f, 10f); path.lineTo(21f, 21f); path.lineTo(15f, 21f); path.lineTo(15f, 14f); path.lineTo(9f, 14f); path.lineTo(9f, 21f); path.lineTo(3f, 21f); path.close(); c.drawPath(path, paint) }
            "bell" -> { path.moveTo(5f, 17f); path.lineTo(7f, 14f); path.lineTo(7f, 9f); path.cubicTo(7f, 2f, 17f, 2f, 17f, 9f); path.lineTo(17f, 14f); path.lineTo(19f, 17f); path.close(); c.drawPath(path, paint); c.drawLine(10f, 21f, 14f, 21f, paint) }
            "spark" -> { path.moveTo(12f, 2f); path.lineTo(15f, 9f); path.lineTo(22f, 12f); path.lineTo(15f, 15f); path.lineTo(12f, 22f); path.lineTo(9f, 15f); path.lineTo(2f, 12f); path.lineTo(9f, 9f); path.close(); c.drawPath(path, paint) }
            "photo" -> { c.drawRoundRect(3f, 3f, 21f, 21f, 2f, 2f, paint); c.drawCircle(16f, 8f, 1.5f, paint); path.moveTo(3f, 17f); path.lineTo(9f, 11f); path.lineTo(14f, 17f); path.lineTo(17f, 14f); path.lineTo(21f, 18f); c.drawPath(path, paint) }
            "profile" -> { c.drawCircle(12f, 12f, 10f, paint); c.drawCircle(12f, 9f, 3f, paint); c.drawArc(5f, 13f, 19f, 25f, 200f, 140f, false, paint) }
            "camera" -> { c.drawRoundRect(3f, 6f, 16f, 20f, 2f, 2f, paint); path.moveTo(16f, 11f); path.lineTo(22f, 7f); path.lineTo(22f, 19f); path.lineTo(16f, 15f); c.drawPath(path, paint) }
            "calendar" -> { c.drawRoundRect(3f, 5f, 21f, 22f, 2f, 2f, paint); c.drawLine(3f, 10f, 21f, 10f, paint); c.drawLine(8f, 2f, 8f, 7f, paint); c.drawLine(16f, 2f, 16f, 7f, paint) }
            "cloud", "rain" -> { path.moveTo(5f, 17f); path.cubicTo(-1f, 17f, -1f, 7f, 6f, 8f); path.cubicTo(6f, -1f, 19f, 1f, 18f, 9f); path.cubicTo(25f, 7f, 26f, 17f, 19f, 17f); path.close(); c.drawPath(path, paint); if (name == "rain") { c.drawLine(6f, 20f, 5f, 23f, paint); c.drawLine(13f, 20f, 12f, 23f, paint); c.drawLine(20f, 20f, 19f, 23f, paint) } }
            "drop" -> { path.moveTo(12f, 2f); path.cubicTo(9f, 8f, 4f, 11f, 4f, 16f); path.cubicTo(4f, 25f, 20f, 25f, 20f, 16f); path.cubicTo(20f, 11f, 15f, 8f, 12f, 2f); c.drawPath(path, paint) }
            "wind" -> { path.moveTo(2f, 8f); path.lineTo(16f, 8f); path.cubicTo(24f, 8f, 20f, -2f, 15f, 3f); path.moveTo(2f, 13f); path.lineTo(19f, 13f); path.cubicTo(25f, 13f, 23f, 21f, 18f, 18f); path.moveTo(2f, 18f); path.lineTo(11f, 18f); c.drawPath(path, paint) }
            "compass" -> { c.drawCircle(12f, 12f, 9f, paint); path.moveTo(15f, 6f); path.lineTo(13f, 13f); path.lineTo(6f, 16f); path.lineTo(9f, 9f); path.close(); c.drawPath(path, paint) }
            "gauge" -> { c.drawArc(3f, 4f, 21f, 22f, 150f, 240f, false, paint); c.drawLine(12f, 13f, 17f, 8f, paint); c.drawCircle(12f, 13f, 1.5f, paint) }
            "energy" -> { path.moveTo(14f, 2f); path.lineTo(4f, 14f); path.lineTo(11f, 14f); path.lineTo(10f, 23f); path.lineTo(21f, 10f); path.lineTo(14f, 10f); path.close(); c.drawPath(path, paint) }
            "shield" -> { path.moveTo(12f, 2f); path.lineTo(21f, 6f); path.lineTo(20f, 14f); path.cubicTo(19f, 19f, 15f, 22f, 12f, 23f); path.cubicTo(9f, 22f, 5f, 19f, 4f, 14f); path.lineTo(3f, 6f); path.close(); c.drawPath(path, paint) }
            "bulb" -> { path.moveTo(8f, 16f); path.cubicTo(-1f, 7f, 7f, -2f, 14f, 3f); path.cubicTo(22f, 6f, 21f, 12f, 16f, 16f); path.lineTo(16f, 18f); path.lineTo(8f, 18f); path.close(); c.drawPath(path, paint); c.drawLine(9f, 22f, 15f, 22f, paint) }
            "unlock" -> { c.drawRoundRect(5f, 10f, 19f, 22f, 2f, 2f, paint); c.drawArc(8f, 1f, 16f, 15f, 180f, 145f, false, paint) }
            "remote" -> { c.drawRoundRect(7f, 1f, 17f, 23f, 4f, 4f, paint); c.drawCircle(12f, 9f, 3f, paint); c.drawLine(10f, 17f, 14f, 17f, paint) }
            "lock" -> { c.drawRoundRect(5f, 10f, 19f, 22f, 2f, 2f, paint); c.drawArc(8f, 1f, 16f, 15f, 180f, 180f, false, paint) }
            "chevron" -> { path.moveTo(9f, 5f); path.lineTo(16f, 12f); path.lineTo(9f, 19f); c.drawPath(path, paint) }
            "play" -> { path.moveTo(7f, 3f); path.lineTo(21f, 12f); path.lineTo(7f, 21f); path.close(); c.drawPath(path, paint) }
            "washer" -> { c.drawRoundRect(2f, 2f, 22f, 23f, 3f, 3f, paint); c.drawLine(2f, 7f, 22f, 7f, paint); c.drawCircle(12f, 15f, 6f, paint) }
            "thermometer" -> { path.moveTo(9f, 14f); path.lineTo(9f, 5f); path.cubicTo(9f, 1f, 15f, 1f, 15f, 5f); path.lineTo(15f, 14f); path.cubicTo(22f, 20f, 13f, 26f, 8f, 21f); path.cubicTo(5f, 19f, 6f, 16f, 9f, 14f); c.drawPath(path, paint); c.drawLine(12f, 8f, 12f, 18f, paint); c.drawLine(18f, 5f, 21f, 5f, paint); c.drawLine(18f, 9f, 20f, 9f, paint) }
            "robot" -> { c.drawCircle(12f, 13f, 9f, paint); c.drawRoundRect(7f, 6f, 17f, 11f, 2f, 2f, paint); c.drawLine(8f, 18f, 16f, 18f, paint); c.drawCircle(16f, 14f, .8f, paint) }
            "map" -> { path.moveTo(2f, 5f); path.lineTo(8f, 2f); path.lineTo(16f, 5f); path.lineTo(22f, 2f); path.lineTo(22f, 19f); path.lineTo(16f, 22f); path.lineTo(8f, 19f); path.lineTo(2f, 22f); path.close(); c.drawPath(path, paint); c.drawLine(8f, 2f, 8f, 19f, paint); c.drawLine(16f, 5f, 16f, 22f, paint) }
            else -> c.drawCircle(12f, 12f, 8f, paint)
        }
        paint.style = Paint.Style.FILL; c.restoreToCount(saved)
    }

    private fun bindCalendarProjection(value: JSONObject) {
        val input = ArrayList<NativeCalendarProjection.Source>()
        val calendars = value.optJSONArray("calendars"); var available = false
        for (index in 0 until minOf(calendars?.length() ?: 0, 16)) {
            val calendar = calendars?.optJSONObject(index) ?: continue
            if (!calendar.optBoolean("available", true)) continue
            available = true
            val events = calendar.optJSONArray("events"); val name = calendar.string("name", "Rodina")
            for (eventIndex in 0 until minOf(events?.length() ?: 0, 256)) {
                val event = events?.optJSONObject(eventIndex) ?: continue
                input.add(NativeCalendarProjection.Source(event.string("summary", "Událost"), name, eventDate(event.opt("start")), eventDate(event.opt("end"))))
            }
        }
        if (calendarProjection.bind(input, available)) calendarRevision++
    }
    private fun bindRoomProjection(value: JSONObject) {
        val domestic = value.obj("domestic")
        // v2 is the explicit dashboard mapping. Never merge climate/air lists by name,
        // or substitute a climate temperature when the mapped room sensor is missing.
        val rawRooms = if (domestic.optInt("roomsContractVersion") == 2) domestic.array("rooms") else JSONArray()
        val next = ArrayList<RoomDisplay>()
        val seen = HashSet<String>()
        for (index in 0 until minOf(rawRooms.length(), 16)) {
            val room = rawRooms.optJSONObject(index) ?: continue
            val id = room.string("id")
            if (id.isBlank() || !seen.add(id)) continue
            val climate = room.obj("climate")
            val temperature = room.obj("temperature")
            val measured = temperature.number("value").takeIf { temperature.optBoolean("available") }
            val unit = climate.string("unit")
            val target = if (!climate.optBoolean("available")) null else climate.number("targetTemperature")?.let { reading(it, " $unit", 1) }
                ?: if (climate.number("targetLow") != null && climate.number("targetHigh") != null) reading(climate.number("targetLow"), "", 1) + "–" + reading(climate.number("targetHigh"), " $unit", 1) else null
            val presence = room.array("presence").objects(16)
            val active = presence.any { it.optBoolean("available") && it.opt("active") == true }
            val allClear = presence.isNotEmpty() && presence.all { it.optBoolean("available") && it.opt("active") == false }
            val summary = listOfNotNull(target?.let { "Cíl $it" }, when { active -> "Přítomnost hlášena"; allClear -> "Bez přítomnosti"; presence.isNotEmpty() -> "Přítomnost neznámá"; else -> null }).joinToString(" · ")
            val heating = room.opt("heatingActive") as? Boolean
            val hasClimate = climate.string("entityId").isNotBlank()
            val activity = when (heating) { true -> "Topí"; false -> "Netopí"; null -> if (hasClimate) "Topení neověřeno" else if (room.optBoolean("available")) "Živá data" else "Nedostupné" }
            next.add(RoomDisplay(id, room.string("name", "Místnost"), heating, activity,
                if (measured != null) reading(measured, temperature.string("unit").let { if (it.isEmpty()) "" else " $it" }, 1) else "—",
                measured != null, summary.ifBlank { "Podrobnosti místnosti" }, metric(room.obj("co2"), 0), metric(room.obj("humidity"), 0), metric(room.obj("pm25"), 1)))
        }
        if (next != roomProjection) { roomProjection = next; roomRevision++ }
    }
    private fun events(): List<NativeCalendarProjection.Event> = calendarProjection.events(live())
    private fun calendarAvailable() = calendarProjection.available(live())
    private fun eventDate(value: Any?): String = when (value) { is String -> value; is JSONObject -> value.string("dateTime", value.string("date")); else -> "" }
    private fun calendar(time: Long): Calendar = Calendar.getInstance(zone, locale).apply { timeInMillis = time }
    private fun format(time: Long, pattern: String): String = formatters.getOrPut(pattern) { SimpleDateFormat(pattern, locale).apply { timeZone = zone } }.format(Date(time))
    private fun parse(raw: String): Long? = calendarProjection.parse(raw)
    private fun timeLabel(raw: String): String = parse(raw)?.let { format(it, "HH:mm") } ?: "čas neznámý"
    private fun reading(value: Double?, unit: String = "", digits: Int = 1): String { if (value == null || !value.isFinite()) return "—"; numberFormat.maximumFractionDigits = digits; numberFormat.minimumFractionDigits = 0; return numberFormat.format(value) + unit }
    private fun metric(value: JSONObject?, digits: Int = 1): String = if (value?.optBoolean("available") == true) reading(value.number("value"), value.string("unit").let { if (it.isEmpty()) "" else " $it" }, digits) else "—"
    private fun power(value: Double?): String = if (value == null) "—" else if (abs(value) >= 1000) reading(value / 1000, " kW", 2) else reading(value, " W", 0)
    private fun securityLabel(value: String): String = mapOf("armed" to "Zapnuto", "disarmed" to "Vypnuto", "arming" to "Odchodové zpoždění")[value] ?: "Stav neznámý"
    private fun status(value: String): String = mapOf("on" to "Zapnuto", "off" to "Vypnuto", "open" to "Otevřeno", "closed" to "Zavřeno", "opening" to "Otevírá se", "closing" to "Zavírá se", "heating" to "Topí", "cooling" to "Chladí", "idle" to "V klidu", "auto" to "Automaticky", "heat" to "Vytápění", "cool" to "Chlazení", "docked" to "V doku", "cleaning" to "Uklízí", "returning" to "Vrací se do doku", "paused" to "Pozastaveno", "pause" to "Pozastaveno", "error" to "Chyba", "charging" to "Nabíjí se", "run" to "Běží", "active" to "Běží", "stop" to "Zastaveno", "wash" to "Praní", "washing" to "Praní", "rinse" to "Máchání", "spin" to "Odstřeďování", "drying" to "Sušení", "finish" to "Dokončeno", "finished" to "Dokončeno", "none" to "Bez programu")[value] ?: "Stav neznámý"
    private fun washerCountdown(washer: JSONObject): String { val machine = washer.obj("machine"); val job = washer.obj("job").string("state"); if (machine.optBoolean("available") && machine.string("state") in listOf("stop", "off", "idle")) return if (job == "finish") "Program hlásí dokončení" else "Pračka právě neběží"; if (!washer.optBoolean("completionAvailable")) return "Čas dokončení není dostupný"; val end = parse(washer.string("estimatedCompletion")) ?: return "Čas dokončení není dostupný"; if (end <= now) return if (job == "finish") "Program hlásí dokončení" else "Poslední odhad již uplynul"; val minutes = ((end - now + 59999) / 60000).toInt(); return "Odhadem zbývá " + if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min" }

    private fun drawMap(c: Canvas, household: JSONObject, r: RectF) {
        target("map:google", "Otevřít Google Mapy", r, "map")
        fill(c, r, if (dark()) Color.argb(36, 130, 157, 182) else Color.argb(36, 255, 255, 255), 16f)
        icon(c, "map", r.centerX() - 17f, r.centerY() - 43f, 34f, muted())
        centered(c, "Google Mapy se načítají…", r.centerX(), r.centerY() + 14f, 16f, muted())
        focus(c, "map:google", r, 16f)
    }
    private fun animateFocus() {
        focusAnimator?.cancel(); focusProgress = 0f
        focusAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 190; interpolator = DecelerateInterpolator()
            addUpdateListener { focusProgress = it.animatedValue as Float; invalidate() }; start()
        }
    }
    private fun animatePage(movePill: Boolean = false) {
        if (!movePill) tabPillFrom = tab.ordinal.toFloat()
        pageAnimator?.cancel(); pageProgress = 0f
        pageAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 210; interpolator = DecelerateInterpolator()
            addUpdateListener { pageProgress = it.animatedValue as Float; invalidate() }; start()
        }
    }
    private fun cancelAnimations() { focusAnimator?.cancel(); pageAnimator?.cancel(); focusAnimator = null; pageAnimator = null; focusProgress = 1f; pageProgress = 1f }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); if (Build.VERSION.SDK_INT >= 29) displayLists?.clear() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); if (visibility != VISIBLE) { cancelAnimations(); if (Build.VERSION.SDK_INT >= 29) displayLists?.clear() } }
    override fun onDetachedFromWindow() { cancelAnimations(); if (Build.VERSION.SDK_INT >= 29) displayLists?.clear(); super.onDetachedFromWindow() }

    companion object {
        const val WIDTH = 1440f; const val HEIGHT = 810f; const val NETFLIX = "com.netflix.ninja"
        private fun darken(color: Int): Int = Color.rgb((Color.red(color) * .65).toInt(), (Color.green(color) * .65).toInt(), (Color.blue(color) * .65).toInt())
        private val APPS = listOf(
            App(NETFLIX, "Netflix", "NETFLIX", Color.rgb(79, 14, 27)), App("net.cme.voyo.cz.tvapp", "Oneplay", "oneplay", Color.rgb(88, 42, 115)), App("cz.prima.tvapp", "prima+", "prima+", Color.rgb(42, 94, 85)),
            App("com.google.android.youtube.tv", "YouTube", "YouTube", Color.rgb(153, 44, 48)), App("com.spotify.tv.android", "Spotify", "Spotify", Color.rgb(47, 99, 75)), App("org.videolan.vlc", "VLC", "VLC", Color.rgb(152, 100, 42)),
            App("com.google.android.youtube.tvmusic", "YouTube Music", "Music", Color.rgb(141, 50, 61)), App("com.google.android.youtube.tvkids", "YouTube Kids", "Kids", Color.rgb(150, 58, 69)), App("com.tcl.ui_mediaCenter", "Centrum médií", "Média", Color.rgb(83, 80, 112)),
            App("com.tcl.browser", "Prohlížeč", "Web", Color.rgb(53, 99, 121)), App("com.tcl.gallery", "Galerie", "Fotky", Color.rgb(78, 109, 88)), App("flar2.homebutton", "Button Mapper", "Ovladač", Color.rgb(92, 102, 121))
        )
    }
}

private val EMPTY_OBJECT = JSONObject()
private val EMPTY_ARRAY = JSONArray()
private fun JSONObject.obj(key: String): JSONObject = optJSONObject(key) ?: EMPTY_OBJECT
private fun JSONObject.array(key: String): JSONArray = optJSONArray(key) ?: EMPTY_ARRAY
private fun JSONObject.string(key: String, fallback: String = ""): String = (opt(key) as? String)?.takeIf { it.isNotBlank() }?.take(1000) ?: fallback
private fun JSONObject.number(key: String): Double? = (opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }
private fun JSONArray.objects(limit: Int = 64): List<JSONObject> = (0 until min(length(), limit)).mapNotNull { optJSONObject(it) }
private fun JSONArray.strings(limit: Int = 64): List<String> = (0 until min(length(), limit)).mapNotNull { opt(it) as? String }.map { it.take(1000) }
