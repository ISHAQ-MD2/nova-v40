package com.nova.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import java.io.File
import java.util.*

class WallSnap(
    val batPct: Int, val charging: Boolean, val batTemp: Float, val batMv: Int,
    val storTotal: Long, val storFree: Long, val ramTotal: Long, val ramFree: Long,
    val rx: Long, val tx: Long, val net: String, val ip: String, val uptimeMs: Long,
    val cores: Int, val freq: IntArray, val reminders: List<Pair<Long, String>>,
    val clipAbout: String, val clipText: String, val weather: String, val prayer: String,
    val wifiRssi: Int, val wifiMbps: Int, val rxTotal: Long, val txTotal: Long, val nextEvent: String
)

/** Wallpaper ka asli data: battery, storage, RAM, net speed, IP, CPU, sensors, reminders, clipboard, namaz, mausam, wifi, calendar. Sab phone se padha hua. */
object WallData {
    @Volatile var snap: WallSnap? = null
    val rxHist = FloatArray(60); val txHist = FloatArray(60); val ramHist = FloatArray(60)
    @Volatile var histPos = 0
    @Volatile var ax = 0f; @Volatile var ay = 0f; @Volatile var az = 0f
    @Volatile var lux = -1f

    @Volatile private var running = false
    @Volatile private var gen = 0
    private var lastRx = -1L; private var lastTx = -1L; private var lastT = 0L
    private var sm: SensorManager? = null
    private var sl: SensorEventListener? = null
    private var tick = 0
    private var evText = ""; private var evAt = 0L

    private var users = 0

    @Synchronized fun start(ctx: Context) {
        users++
        if (running) return
        running = true
        val my = ++gen
        val app = ctx.applicationContext
        Thread {
            while (running && my == gen) {
                try { sample(app) } catch (_: Throwable) {}
                try { if (tick % 5 == 0) FileWatch.scan(app) } catch (_: Throwable) {}
                tick++
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }.also { it.isDaemon = true; it.start() }
        try {
            val m = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val l = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) { ax = e.values[0]; ay = e.values[1]; az = e.values[2] }
                    else if (e.sensor.type == Sensor.TYPE_LIGHT) lux = e.values[0]
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { m.registerListener(l, it, SensorManager.SENSOR_DELAY_UI) }
            m.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { m.registerListener(l, it, SensorManager.SENSOR_DELAY_UI) }
            sm = m; sl = l
        } catch (_: Throwable) {}
    }

    @Synchronized fun stop() {
        if (users > 0) users--
        if (users > 0) return
        running = false; gen++
        try { sl?.let { sm?.unregisterListener(it) } } catch (_: Throwable) {}
        sl = null; sm = null
    }

    private fun nextCalendar(ctx: Context): String {
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) return ""
        val from = System.currentTimeMillis(); val to = from + 3L * 24 * 3600 * 1000
        val bu = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon()
        android.content.ContentUris.appendId(bu, from); android.content.ContentUris.appendId(bu, to)
        var out = ""
        ctx.contentResolver.query(bu.build(), arrayOf("title", "begin"), null, null, "begin ASC")?.use { c ->
            if (c.moveToFirst()) {
                val t = c.getString(0) ?: "event"; val b = c.getLong(1)
                out = java.text.SimpleDateFormat("EEE HH:mm", Locale.US).format(Date(b)) + " " + t
            }
        }
        return out
    }

    private fun sample(ctx: Context) {
        val now = System.currentTimeMillis()
        // battery
        val bi = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = bi?.getIntExtra("level", -1) ?: -1; val sc = bi?.getIntExtra("scale", 100) ?: 100
        val st = bi?.getIntExtra("status", -1) ?: -1
        val pct = if (lvl < 0) 0 else lvl * 100 / sc.coerceAtLeast(1)
        val temp = (bi?.getIntExtra("temperature", 0) ?: 0) / 10f
        val mv = bi?.getIntExtra("voltage", 0) ?: 0
        // storage
        var sTot = 0L; var sFree = 0L
        try { val s = StatFs(Environment.getExternalStorageDirectory().path); sTot = s.blockCountLong * s.blockSizeLong; sFree = s.availableBlocksLong * s.blockSizeLong } catch (_: Throwable) {}
        // ram
        var rTot = 0L; var rFree = 0L
        try { val mi = ActivityManager.MemoryInfo(); ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi); rTot = mi.totalMem; rFree = mi.availMem } catch (_: Throwable) {}
        // net speed
        val rxN = TrafficStats.getTotalRxBytes(); val txN = TrafficStats.getTotalTxBytes()
        var rxS = 0L; var txS = 0L
        if (lastRx >= 0 && rxN >= 0 && now > lastT) { val dt = (now - lastT) / 1000.0; rxS = ((rxN - lastRx) / dt).toLong().coerceAtLeast(0); txS = ((txN - lastTx) / dt).toLong().coerceAtLeast(0) }
        lastRx = rxN; lastTx = txN; lastT = now
        var net = "NO NET"
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val c = cm.getNetworkCapabilities(cm.activeNetwork)
            if (c != null) net = when {
                c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
                c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETH"
                c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "ONLINE"
            }
        } catch (_: Throwable) {}
        val ip = MiniServer.lanIp() ?: "-"
        // wifi signal (location permission ki zaroorat nahi)
        var rssi = -127; var mbps = 0
        try {
            @Suppress("DEPRECATION")
            val wi = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
            if (wi != null && net == "WIFI") { rssi = wi.rssi; mbps = wi.linkSpeed }
        } catch (_: Throwable) {}
        // cpu freq (kuch phones par band hota hai - tab khali)
        val cores = Runtime.getRuntime().availableProcessors()
        val fr = ArrayList<Int>()
        for (i in 0 until cores) {
            try { val t = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq").readText().trim().toLong(); fr.add((t / 1000).toInt()) } catch (_: Throwable) { break }
        }
        // reminders
        val rem = try {
            TaskStore.all(ctx).filter { it.optLong("at") > now - 60_000L }.sortedBy { it.optLong("at") }.take(4)
                .map { it.optLong("at") to it.optString("text").removePrefix("notify_user:").removePrefix("NOTIFY_USER:").trim() }
        } catch (_: Throwable) { emptyList() }
        var cAbout = ""; var cText = ""
        if (tick % 3 == 0 || snap == null) try { ClipStore.all(ctx).lastOrNull()?.let { cAbout = it.optString("about"); cText = it.optString("text") } } catch (_: Throwable) {}
        else { cAbout = snap?.clipAbout ?: ""; cText = snap?.clipText ?: "" }
        val wx = try { Weather.cached(ctx)?.let { it.first + " " + it.second } ?: "" } catch (_: Throwable) { "" }
        val pr = try { Prayer.nextPrayer(now)?.let { it.first + "|" + it.second } ?: "" } catch (_: Throwable) { "" }
        if (now - evAt > 60_000L) { evAt = now; evText = try { nextCalendar(ctx) } catch (_: Throwable) { "" } }

        val p = histPos
        rxHist[p] = rxS / 1024f; txHist[p] = txS / 1024f; ramHist[p] = if (rTot > 0) 1f - rFree.toFloat() / rTot else 0f
        histPos = (p + 1) % rxHist.size
        snap = WallSnap(pct, st == 2 || st == 5, temp, mv, sTot, sFree, rTot, rFree, rxS, txS, net, ip, SystemClock.elapsedRealtime(), cores, fr.toIntArray(), rem, cAbout, cText, wx, pr,
            rssi, mbps, if (rxN > 0) rxN else 0L, if (txN > 0) txN else 0L, evText)
    }

    fun fmtBytes(b: Long): String {
        if (b < 1024) return "$b B"
        val k = b / 1024.0; if (k < 1024) return String.format(Locale.US, "%.0f KB", k)
        val m = k / 1024.0; if (m < 1024) return String.format(Locale.US, "%.1f MB", m)
        return String.format(Locale.US, "%.2f GB", m / 1024.0)
    }
}

/** Nayi file aate hi pakadta hai (Download, Camera, Movies, Music, WhatsApp, NOVA-FILES...) - naam + size wallpaper par dikhta hai. */
object FileWatch {
    private val known = HashMap<String, Long>()
    private val pending = HashMap<String, Long>()
    private var first = true
    private val SKIP = listOf(".tmp", ".part", ".crdownload", ".pending", ".trashed", ".download", ".ytdl", ".temp", ".nomedia")

    private fun roots(): List<File> {
        val r = Environment.getExternalStorageDirectory()
        return listOf("Download", "DCIM/Camera", "DCIM/Screenshots", "Movies", "Pictures", "Music", "Documents", "NOVA-FILES", "Download/NOVA", "Android/media/com.whatsapp/WhatsApp/Media",
            "WhatsApp/Media", "Telegram").map { File(r, it) }.filter { it.isDirectory }
    }

    private fun walk(d: File, depth: Int, out: MutableList<File>) {
        val l = try { d.listFiles() } catch (_: Throwable) { null } ?: return
        for (f in l) {
            if (out.size > 6000) return
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) { if (depth < 2) walk(f, depth + 1, out) }
            else if (SKIP.none { f.name.endsWith(it, true) }) out.add(f)
        }
    }

    @Synchronized fun scan(ctx: Context) {
        val all = ArrayList<File>()
        for (r in roots()) walk(r, 0, all)
        val seen = HashSet<String>()
        if (first) {
            first = false
            for (f in all) { known[f.path] = f.length(); seen.add(f.path) }
            val cutoff = System.currentTimeMillis() - 3L * 24 * 3600 * 1000
            all.filter { it.lastModified() > cutoff }.sortedByDescending { it.lastModified() }.take(3).reversed()
                .forEach { WallBus.fileEvent(it.name, it.length(), false) }
            return
        }
        for (f in all) {
            val p = f.path; seen.add(p)
            if (known.containsKey(p)) continue
            val sz = f.length()
            val prev = pending[p]
            if (prev != null && prev == sz && sz > 0) { known[p] = sz; pending.remove(p); WallBus.fileEvent(f.name, sz, true) }
            else pending[p] = sz
        }
        pending.keys.retainAll(seen)
        if (known.size > 20000) known.clear()
    }
}
