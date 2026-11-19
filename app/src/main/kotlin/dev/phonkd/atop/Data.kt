package dev.phonkd.atop

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs

// ---------------------------------------------------------------- models

data class Core(val index: Int, val usage: Float, val freqKHz: Long, val maxKHz: Long)

data class Temp(val name: String, val celsius: Float)

data class Battery(
    val percent: Int,
    val status: String,
    val watts: Float?,
    val celsius: Float?,
    val volts: Float?,
    val cycles: Int?,
)

data class ProcInfo(
    val pid: Int,
    val ppid: Int,
    val uid: Int,
    val name: String,
    val cmdline: String,
    val pkg: String?,
    val icon: ImageBitmap?,
    val state: Char,
    val threads: Int,
    val nice: Int,
    val rss: Long,
    val cpu: Float,
    val cpuSeconds: Long,
    val startSeconds: Long,
    val kernel: Boolean,
) {
    val isApp get() = pkg != null
    val user
        get() = when {
            uid == 0 -> "root"
            uid == 1000 -> "system"
            uid == 2000 -> "shell"
            uid >= 10000 -> "u${uid / 100000}_a${uid % 100000 - 10000}"
            else -> uid.toString()
        }
}

data class Snapshot(
    val cpu: Float,
    val cores: List<Core>,
    val load: List<Float>,
    val uptime: Long,
    val cpuTemp: Float?,
    val memTotal: Long,
    val memUsed: Long,
    val memCache: Long,
    val memFree: Long,
    val swapTotal: Long,
    val swapUsed: Long,
    val rxRate: Float,
    val txRate: Float,
    val readRate: Float,
    val writeRate: Float,
    val storageTotal: Long,
    val storageUsed: Long,
    val battery: Battery?,
    val gpu: Float?,
    val gpuFreqHz: Long?,
    val temps: List<Temp>,
    val procs: List<ProcInfo>,
    val threads: Int,
    val running: Int,
)

// ---------------------------------------------------------------- root shell

/** One long-lived `su` process; commands are written to stdin and read back up to a marker. */
class RootShell {
    private val mutex = Mutex()
    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStream? = null

    /** Returns the output lines, or null if root is unavailable. */
    suspend fun exec(command: String): List<String>? = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                if (!ensureOpen()) null else run(command)
            } catch (e: Exception) {
                close()
                null
            }
        }
    }

    private fun ensureOpen(): Boolean {
        if (process?.isAlive == true) return true
        close()
        val p = ProcessBuilder("su").start()
        process = p
        reader = p.inputStream.bufferedReader()
        writer = p.outputStream
        val id = run("exec 2>/dev/null; id -u")
        if (id?.firstOrNull()?.trim() != "0") {
            close()
            return false
        }
        return true
    }

    private fun run(command: String): List<String>? {
        val w = writer ?: return null
        val r = reader ?: return null
        w.write("$command\necho $END\n".toByteArray())
        w.flush()
        val out = ArrayList<String>(2048)
        while (true) {
            val line = r.readLine() ?: run { close(); return null }
            if (line == END) return out
            out.add(line)
        }
    }

    private fun close() {
        runCatching { process?.destroy() }
        process = null; reader = null; writer = null
    }

    private companion object {
        const val END = "@@end"
    }
}

// ---------------------------------------------------------------- app labels & icons

class AppCache(context: Context) {
    private class Meta(val label: String, @Volatile var icon: ImageBitmap?)

    private val pm = context.packageManager
    private val known = ConcurrentHashMap<String, Meta>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val iconLoader = Executors.newSingleThreadExecutor()

    /** Label and (once loaded) icon for [pkg], or null if it is not an installed package. */
    fun lookup(pkg: String): Pair<String, ImageBitmap?>? {
        known[pkg]?.let { return it.label to it.icon }
        if (pkg in missing) return null
        val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
        if (info == null) {
            missing.add(pkg)
            return null
        }
        val meta = Meta(pm.getApplicationLabel(info).toString(), null)
        known[pkg] = meta
        iconLoader.execute {
            meta.icon = runCatching {
                pm.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()
        }
        return meta.label to null
    }
}

// ---------------------------------------------------------------- collector

class Collector(context: Context, private val shell: RootShell) {
    private val apps = AppCache(context)
    private val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
    private val hz = Os.sysconf(OsConstants._SC_CLK_TCK).coerceAtLeast(1)

    private var prevCpu = HashMap<String, LongArray>() // name -> [total, idle]
    private var prevProc = HashMap<Int, LongArray>()   // pid -> [ticks, starttime]
    private var prevNet = longArrayOf(-1, -1)
    private var prevDisk = longArrayOf(-1, -1)
    private var prevNanos = 0L

    suspend fun sample(): Snapshot? {
        val lines = shell.exec(SCRIPT) ?: return null
        return withContext(Dispatchers.Default) { parse(lines) }
    }

    private fun parse(lines: List<String>): Snapshot {
        val sec = HashMap<String, MutableList<String>>()
        var cur: MutableList<String>? = null
        for (l in lines) {
            if (l.startsWith("@@")) cur = sec.getOrPut(l.substring(2)) { ArrayList() }
            else cur?.add(l)
        }
        fun s(name: String): List<String> = sec[name] ?: emptyList()

        val now = SystemClock.elapsedRealtimeNanos()
        val dt = if (prevNanos == 0L) 0f else (now - prevNanos) / 1e9f
        prevNanos = now

        // ---- cpu
        val cpuNow = HashMap<String, LongArray>()
        val usage = HashMap<String, Float>()
        var totalDelta = 0L
        for (l in s("stat")) {
            if (!l.startsWith("cpu")) continue
            val f = l.split(' ').filter { it.isNotEmpty() }
            val v = f.drop(1).take(8).map { it.toLongOrNull() ?: 0 }
            if (v.size < 5) continue
            val total = v.sum()
            val idle = v[3] + v[4]
            cpuNow[f[0]] = longArrayOf(total, idle)
            val p = prevCpu[f[0]]
            if (p != null && total > p[0]) {
                usage[f[0]] = (1f - (idle - p[1]).toFloat() / (total - p[0])).coerceIn(0f, 1f)
                if (f[0] == "cpu") totalDelta = total - p[0]
            }
        }
        prevCpu = cpuNow

        val curFreq = HashMap<Int, Long>()
        val maxFreq = HashMap<Int, Long>()
        for (l in s("freq")) {
            val idx = l.substringAfter("/cpu/cpu").substringBefore('/').toIntOrNull() ?: continue
            val v = l.substringAfterLast(':').trim().toLongOrNull() ?: continue
            if ("scaling_cur_freq" in l) curFreq[idx] = v else maxFreq[idx] = v
        }
        val cores = cpuNow.keys.mapNotNull { it.removePrefix("cpu").toIntOrNull() }.sorted().map {
            Core(it, usage["cpu$it"] ?: 0f, curFreq[it] ?: 0, maxFreq[it] ?: 0)
        }

        val loadLines = s("load")
        val load = loadLines.getOrNull(0)?.split(' ')?.take(3)?.mapNotNull { it.toFloatOrNull() } ?: emptyList()
        val uptime = loadLines.getOrNull(1)?.substringBefore(' ')?.toFloatOrNull()?.toLong() ?: 0

        // ---- memory
        val mem = HashMap<String, Long>()
        for (l in s("mem")) {
            val k = l.substringBefore(':')
            mem[k] = (l.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0) * 1024
        }
        val memTotal = mem["MemTotal"] ?: 0
        val memFree = mem["MemFree"] ?: 0
        val memAvail = mem["MemAvailable"] ?: memFree
        val swapTotal = mem["SwapTotal"] ?: 0

        // ---- thermals
        val types = HashMap<Int, String>()
        val values = HashMap<Int, Float>()
        for (l in s("therm")) {
            val idx = l.substringAfter("thermal_zone").substringBefore('/').toIntOrNull() ?: continue
            val v = l.substringAfterLast(':').trim()
            if (l.substringBeforeLast(':').endsWith("/type")) types[idx] = v
            else v.toFloatOrNull()?.let { values[idx] = normalizeTemp(it) }
        }
        val temps = types.keys.sorted().mapNotNull { i ->
            val c = values[i] ?: return@mapNotNull null
            if (c < 1f || c > 130f) null else Temp(types[i]!!, c)
        }
        val cpuTemp = temps.filter { it.name.contains("cpu", true) }.maxOfOrNull { it.celsius }
            ?: temps.filter { it.name.contains("soc", true) }.maxOfOrNull { it.celsius }

        // ---- network
        var rx = 0L
        var tx = 0L
        for (l in s("net")) {
            val name = l.substringBefore(':', "").trim()
            if (NET_PREFIXES.none { name.startsWith(it) }) continue
            val f = l.substringAfter(':').split(' ').filter { it.isNotEmpty() }
            rx += f.getOrNull(0)?.toLongOrNull() ?: 0
            tx += f.getOrNull(8)?.toLongOrNull() ?: 0
        }
        val rxRate = rate(rx, prevNet[0], dt)
        val txRate = rate(tx, prevNet[1], dt)
        prevNet = longArrayOf(rx, tx)

        // ---- disk
        var rd = 0L
        var wr = 0L
        for (l in s("disk")) {
            val f = l.split(' ').filter { it.isNotEmpty() }
            if (f.size < 10 || !DISK_NAME.matches(f[2])) continue
            rd += (f[5].toLongOrNull() ?: 0) * 512
            wr += (f[9].toLongOrNull() ?: 0) * 512
        }
        val readRate = rate(rd, prevDisk[0], dt)
        val writeRate = rate(wr, prevDisk[1], dt)
        prevDisk = longArrayOf(rd, wr)

        val fs = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        val storageTotal = fs?.totalBytes ?: 0
        val storageUsed = storageTotal - (fs?.availableBytes ?: 0)

        // ---- battery
        val bat = s("bat").associate {
            it.substringBefore('=').removePrefix("POWER_SUPPLY_") to it.substringAfter('=')
        }
        val battery = bat["CAPACITY"]?.toIntOrNull()?.let { pct ->
            // Units differ between vendors: normalise to amps / volts.
            val amps = bat["CURRENT_NOW"]?.toFloatOrNull()?.let { abs(it) }?.let { if (it < 20000) it / 1e3f else it / 1e6f }
            val volts = bat["VOLTAGE_NOW"]?.toFloatOrNull()?.let { if (it < 100000) it / 1e3f else it / 1e6f }
            Battery(
                percent = pct,
                status = bat["STATUS"] ?: "",
                watts = if (amps != null && volts != null) amps * volts else null,
                celsius = bat["TEMP"]?.toFloatOrNull()?.div(10f),
                volts = volts,
                cycles = bat["CYCLE_COUNT"]?.toIntOrNull(),
            )
        }

        // ---- gpu
        val gpu = s("gpu").firstOrNull()?.trim()?.substringBefore(' ')?.toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f)
        val gpuFreq = s("gpufreq").firstOrNull()?.trim()?.toLongOrNull()?.takeIf { it > 0 }

        // ---- processes
        val uids = HashMap<Int, Int>()
        for (l in s("uid")) {
            val pid = l.substring(6).substringBefore(' ').toIntOrNull() ?: continue
            uids[pid] = l.substringAfterLast(' ').toIntOrNull() ?: continue
        }
        val cmds = HashMap<Int, String>()
        for (l in s("cmd")) {
            if (!l.startsWith("/proc/")) continue
            val pid = l.substring(6).substringBefore('/').toIntOrNull() ?: continue
            cmds[pid] = l.substringAfter(':')
        }

        val procNow = HashMap<Int, LongArray>()
        val procs = ArrayList<ProcInfo>(1024)
        var threads = 0
        var running = 0
        for (l in s("proc")) {
            val open = l.indexOf('(')
            val close = l.lastIndexOf(')')
            if (open < 0 || close < 0 || close + 2 >= l.length) continue
            val pid = l.substring(0, open).trim().toIntOrNull() ?: continue
            val comm = l.substring(open + 1, close)
            val f = l.substring(close + 2).split(' ')
            if (f.size < 22) continue
            val ticks = (f[11].toLongOrNull() ?: 0) + (f[12].toLongOrNull() ?: 0)
            val start = f[19].toLongOrNull() ?: 0
            procNow[pid] = longArrayOf(ticks, start)
            val prev = prevProc[pid]
            val cpu = if (prev != null && prev[1] == start && totalDelta > 0)
                ((ticks - prev[0]).toFloat() / totalDelta * 100f).coerceIn(0f, 100f) else 0f

            val raw = cmds[pid].orEmpty()
            val argv0 = raw.substringBefore('\u0000').trim()
            val ppid = f[1].toIntOrNull() ?: 0
            val kernel = argv0.isEmpty() && (ppid == 2 || pid == 2)
            val pkg = argv0.substringBefore(':').takeIf { '.' in it && '/' !in it }
            val app = pkg?.let { apps.lookup(it) }
            val name = when {
                app != null -> app.first
                argv0.isEmpty() -> comm
                else -> argv0.substringAfterLast('/')
            }
            val state = f[0].firstOrNull() ?: '?'
            val nThreads = f[17].toIntOrNull() ?: 1
            threads += nThreads
            if (state == 'R') running++
            procs += ProcInfo(
                pid = pid,
                ppid = ppid,
                uid = uids[pid] ?: 0,
                name = name,
                cmdline = raw.replace('\u0000', ' ').trim().ifEmpty { "[$comm]" },
                pkg = if (app != null) pkg else null,
                icon = app?.second,
                state = state,
                threads = nThreads,
                nice = f[16].toIntOrNull() ?: 0,
                rss = (f[21].toLongOrNull() ?: 0) * pageSize,
                cpu = cpu,
                cpuSeconds = ticks / hz,
                startSeconds = start / hz,
                kernel = kernel,
            )
        }
        prevProc = procNow

        return Snapshot(
            cpu = usage["cpu"] ?: 0f,
            cores = cores,
            load = load,
            uptime = uptime,
            cpuTemp = cpuTemp,
            memTotal = memTotal,
            memUsed = memTotal - memAvail,
            memCache = (memAvail - memFree).coerceAtLeast(0),
            memFree = memFree,
            swapTotal = swapTotal,
            swapUsed = swapTotal - (mem["SwapFree"] ?: 0),
            rxRate = rxRate,
            txRate = txRate,
            readRate = readRate,
            writeRate = writeRate,
            storageTotal = storageTotal,
            storageUsed = storageUsed,
            battery = battery,
            gpu = gpu,
            gpuFreqHz = gpuFreq,
            temps = temps,
            procs = procs,
            threads = threads,
            running = running,
        )
    }

    private fun rate(now: Long, prev: Long, dt: Float) =
        if (prev < 0 || dt <= 0f || now < prev) 0f else (now - prev) / dt

    /** Zones report millidegrees, tenths or plain degrees depending on the driver. */
    private fun normalizeTemp(v: Float) = when {
        abs(v) > 1000 -> v / 1000f
        abs(v) > 200 -> v / 10f
        else -> v
    }

    private companion object {
        val NET_PREFIXES = listOf("wlan", "rmnet", "ccmni", "eth")
        val DISK_NAME = Regex("sd[a-z]+|mmcblk\\d+|nvme\\d+n\\d+")

        // `grep -H .` prints "path:value" for every file in a single process, which is
        // far cheaper than looping in the shell.
        val SCRIPT = """
            echo @@stat; cat /proc/stat
            echo @@mem; cat /proc/meminfo
            echo @@load; cat /proc/loadavg /proc/uptime
            echo @@freq; grep -H . /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_cur_freq /sys/devices/system/cpu/cpu[0-9]*/cpufreq/cpuinfo_max_freq
            echo @@therm; grep -H . /sys/class/thermal/thermal_zone*/type /sys/class/thermal/thermal_zone*/temp
            echo @@net; cat /proc/net/dev
            echo @@disk; cat /proc/diskstats
            echo @@bat; cat /sys/class/power_supply/battery/uevent
            echo @@gpu; cat /sys/kernel/ged/hal/gpu_utilization /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage
            echo @@gpufreq; cat /sys/class/devfreq/*mali*/cur_freq /sys/class/kgsl/kgsl-3d0/gpuclk
            echo @@uid; stat -c '%n %u' /proc/[0-9]*
            echo @@cmd; grep -a -H -m1 '' /proc/[0-9]*/cmdline
            echo @@proc; cat /proc/[0-9]*/stat
        """.trimIndent()
    }
}

// ---------------------------------------------------------------- formatting

fun fmtBytes(b: Long): String = fmtBytes(b.toFloat())

fun fmtBytes(b: Float): String = when {
    b >= 1e9f -> String.format(Locale.US, if (b >= 1e11f) "%.0f GB" else "%.1f GB", b / 1073741824f)
    b >= 1e6f -> String.format(Locale.US, "%.0f MB", b / 1048576f)
    b >= 1e3f -> String.format(Locale.US, "%.0f KB", b / 1024f)
    else -> String.format(Locale.US, "%.0f B", b)
}

fun fmtRate(b: Float): String = when {
    b >= 1e6f -> String.format(Locale.US, "%.1f MB/s", b / 1048576f)
    b >= 1e3f -> String.format(Locale.US, "%.0f KB/s", b / 1024f)
    else -> String.format(Locale.US, "%.0f B/s", b)
}

fun fmtPercent(f: Float) = "${(f * 100).toInt()}%"

fun fmtTemp(c: Float) = "${c.toInt()}°"

fun fmtDuration(s: Long): String = when {
    s >= 86400 -> "${s / 86400}d ${s % 86400 / 3600}h"
    s >= 3600 -> "${s / 3600}h ${s % 3600 / 60}m"
    s >= 60 -> "${s / 60}m ${s % 60}s"
    else -> "${s}s"
}
