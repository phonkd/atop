package dev.phonkd.atop

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun App(vm: MonitorViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val p = LocalPalette.current
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        val snap = state.snap
        when {
            snap != null -> {
                Crossfade(tab, label = "tab") {
                    if (it == 0) Overview(state, snap) else Processes(snap, vm)
                }
                if (!WindowInsets.isImeVisible) {
                    Segmented(
                        items = listOf("Overview", "Processes"),
                        selected = tab,
                        onSelect = { tab = it },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = navBottom() + 16.dp)
                            .shadow(24.dp, CircleShape, ambientColor = Color(0x33000000), spotColor = Color(0x33000000))
                            .width(248.dp),
                        height = 56.dp,
                        track = if (p.dark) Color(0xFF1F1F23) else p.card,
                        thumb = if (p.dark) Color(0xFF34343A) else p.track,
                        textSize = 15,
                    )
                }
            }

            state.root == Root.Denied -> NoRoot()
        }
    }
}

@Composable
private fun navBottom() = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

@Composable
private fun screenPadding() = PaddingValues(
    start = 16.dp,
    end = 16.dp,
    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 20.dp,
    bottom = navBottom() + 104.dp,
)

@Composable
private fun NoRoot() {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        T("Root access required", 24, weight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            "atop reads system-wide stats through su. Grant it superuser access in your root " +
                "manager (KernelSU, Magisk, APatch) and this screen will update on its own.",
            color = p.sub, fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Header(title: String, subtitle: String) {
    Column(Modifier.padding(start = 6.dp, bottom = 8.dp)) {
        T(title, 34, weight = FontWeight.Bold)
        T(subtitle, 14, color = LocalPalette.current.sub)
    }
}

// ---------------------------------------------------------------- overview

@Composable
private fun Overview(state: UiState, s: Snapshot) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            val device = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
            Header("Overview", "$device · up ${fmtDuration(s.uptime)}")
        }
        item { CpuCard(s, state.cpuHist) }
        item { MemoryCard(s) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GaugeCard(
                    label = "GPU",
                    progress = s.gpu,
                    color = LocalPalette.current.purple,
                    caption = s.gpuFreqHz?.let { "${it / 1_000_000} MHz" } ?: "No data",
                    modifier = Modifier.weight(1f),
                )
                val b = s.battery
                GaugeCard(
                    label = "Battery",
                    progress = b?.percent?.div(100f),
                    color = if ((b?.percent ?: 100) <= 20) LocalPalette.current.red else LocalPalette.current.green,
                    caption = b?.let {
                        listOfNotNull(
                            it.watts?.let { w -> String.format(Locale.US, "%.1f W", w) },
                            it.celsius?.let { c -> "${fmtTemp(c)}C" },
                            it.status.takeIf { st -> st.isNotEmpty() },
                        ).joinToString(" · ")
                    } ?: "No data",
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item { NetworkCard(s, state.rxHist, state.txHist) }
        item { StorageCard(s) }
        if (s.temps.isNotEmpty()) item { SensorsCard(s.temps) }
    }
}

@Composable
private fun tempColor(c: Float): Color {
    val p = LocalPalette.current
    return when {
        c >= 80f -> p.red
        c >= 60f -> p.orange
        else -> p.text
    }
}

@Composable
private fun Pill(text: String, color: Color = LocalPalette.current.text) {
    Box(
        Modifier.clip(CircleShape).background(LocalPalette.current.track).padding(horizontal = 12.dp, vertical = 6.dp),
    ) { T(text, 13, color = color, weight = FontWeight.Medium) }
}

@Composable
private fun CpuCard(s: Snapshot, history: List<Float>) {
    val p = LocalPalette.current
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Label("CPU")
                Row(verticalAlignment = Alignment.Bottom) {
                    T((s.cpu * 100).toInt().toString(), 56, weight = FontWeight.Bold)
                    T("%", 24, Modifier.padding(start = 2.dp, bottom = 10.dp), p.sub, FontWeight.Medium)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                s.cpuTemp?.let { Pill("${fmtTemp(it)}C", tempColor(it)) }
                s.load.firstOrNull()?.let { Pill(String.format(Locale.US, "Load %.1f", it)) }
            }
        }
        Spacer(Modifier.height(8.dp))
        AreaChart(listOf(history to p.blue), Modifier.fillMaxWidth().height(96.dp), maxValue = 1f)
        Spacer(Modifier.height(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (row in s.cores.chunked(8)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in row) CoreBar(c, Modifier.weight(1f))
                    repeat(8 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row {
            Stat("Processes", s.procs.size.toString(), Modifier.weight(1f))
            Stat("Threads", s.threads.toString(), Modifier.weight(1f))
            Stat("Running", s.running.toString(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun CoreBar(c: Core, modifier: Modifier) {
    val p = LocalPalette.current
    val h by animateFloatAsState(c.usage, spring(stiffness = 150f), label = "core")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(10.dp)).background(p.track),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                Modifier.fillMaxWidth().fillMaxHeight(h.coerceIn(0f, 1f))
                    .background(if (c.usage >= 0.85f) p.orange else p.blue),
            )
        }
        Spacer(Modifier.height(6.dp))
        T(
            if (c.freqKHz > 0) String.format(Locale.US, "%.1f", c.freqKHz / 1e6f) else "off",
            11, color = p.sub,
        )
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        T(label, 12, color = LocalPalette.current.sub)
        T(value, 17, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun Legend(color: Color, label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Dot(color)
        Column(Modifier.padding(start = 8.dp)) {
            T(label, 12, color = LocalPalette.current.sub)
            T(value, 14, weight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MemoryCard(s: Snapshot) {
    val p = LocalPalette.current
    val total = s.memTotal.coerceAtLeast(1).toFloat()
    Card(Modifier.fillMaxWidth()) {
        Label("Memory")
        Row(verticalAlignment = Alignment.Bottom) {
            T(fmtBytes(s.memUsed), 34, weight = FontWeight.Bold)
            T("of ${fmtBytes(s.memTotal)}", 14, Modifier.padding(start = 8.dp, bottom = 6.dp), p.sub)
        }
        Spacer(Modifier.height(12.dp))
        SegmentBar(listOf(s.memUsed / total to p.green, s.memCache / total to p.green.copy(alpha = 0.35f)))
        Spacer(Modifier.height(14.dp))
        Row {
            Legend(p.green, "Used", fmtBytes(s.memUsed), Modifier.weight(1f))
            Legend(p.green.copy(alpha = 0.35f), "Cached", fmtBytes(s.memCache), Modifier.weight(1f))
            Legend(p.track, "Free", fmtBytes(s.memFree), Modifier.weight(1f))
        }
        if (s.swapTotal > 0) {
            Spacer(Modifier.height(18.dp))
            Row {
                T("Swap", 13, Modifier.weight(1f), p.sub, FontWeight.Medium)
                T("${fmtBytes(s.swapUsed)} of ${fmtBytes(s.swapTotal)}", 13, color = p.sub)
            }
            Spacer(Modifier.height(8.dp))
            SegmentBar(listOf(s.swapUsed.toFloat() / s.swapTotal to p.orange), height = 6.dp)
        }
    }
}

@Composable
private fun GaugeCard(label: String, progress: Float?, color: Color, caption: String, modifier: Modifier) {
    Card(modifier) {
        Label(label)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Ring(progress ?: 0f, color, Modifier.size(104.dp))
            T(progress?.let { fmtPercent(it) } ?: "–", 24, weight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            caption, Modifier.fillMaxWidth(), color = LocalPalette.current.sub, fontSize = 12.sp,
            textAlign = TextAlign.Center, maxLines = 1,
        )
    }
}

@Composable
private fun NetworkCard(s: Snapshot, rx: List<Float>, tx: List<Float>) {
    val p = LocalPalette.current
    Card(Modifier.fillMaxWidth()) {
        Label("Network")
        Spacer(Modifier.height(8.dp))
        Row {
            Legend(p.teal, "Download", fmtRate(s.rxRate), Modifier.weight(1f))
            Legend(p.purple, "Upload", fmtRate(s.txRate), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        AreaChart(listOf(rx to p.teal, tx to p.purple), Modifier.fillMaxWidth().height(72.dp), floor = 16 * 1024f)
    }
}

@Composable
private fun StorageCard(s: Snapshot) {
    val p = LocalPalette.current
    Card(Modifier.fillMaxWidth()) {
        Label("Storage")
        Row(verticalAlignment = Alignment.Bottom) {
            T(fmtBytes(s.storageUsed), 34, weight = FontWeight.Bold)
            T("of ${fmtBytes(s.storageTotal)}", 14, Modifier.padding(start = 8.dp, bottom = 6.dp), p.sub)
        }
        Spacer(Modifier.height(12.dp))
        SegmentBar(listOf(s.storageUsed.toFloat() / s.storageTotal.coerceAtLeast(1) to p.blue))
        Spacer(Modifier.height(14.dp))
        Row {
            Legend(p.blue, "Read", fmtRate(s.readRate), Modifier.weight(1f))
            Legend(p.orange, "Write", fmtRate(s.writeRate), Modifier.weight(1f))
        }
    }
}

@Composable
private fun SensorsCard(temps: List<Temp>) {
    val p = LocalPalette.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val sorted = remember(temps) { temps.sortedByDescending { it.celsius } }
    val shown = if (expanded) sorted else sorted.take(8)
    Card(Modifier.fillMaxWidth()) {
        Row {
            Label("Sensors", Modifier.weight(1f))
            if (sorted.size > 8) {
                T(
                    if (expanded) "Show less" else "Show all ${sorted.size}", 13,
                    Modifier.plainClick { expanded = !expanded }, p.blue, FontWeight.Medium,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (pair in shown.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    for (t in pair) {
                        Row(Modifier.weight(1f)) {
                            T(t.name, 13, Modifier.weight(1f), p.sub)
                            T(fmtTemp(t.celsius), 14, Modifier.padding(start = 6.dp), tempColor(t.celsius), FontWeight.SemiBold)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- processes

private val FILTERS = listOf("All", "Apps", "System", "Kernel")

@Composable
private fun Processes(s: Snapshot, vm: MonitorViewModel) {
    val p = LocalPalette.current
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }

    val list = remember(s.procs, query, sort, filter) {
        val q = query.trim()
        s.procs.asSequence()
            .filter {
                when (filter) {
                    0 -> !it.kernel
                    1 -> it.isApp
                    2 -> !it.isApp && !it.kernel
                    else -> it.kernel
                }
            }
            .filter {
                q.isEmpty() || it.name.contains(q, true) || it.cmdline.contains(q, true) || it.pid.toString() == q
            }
            .sortedWith(
                when (sort) {
                    0 -> compareByDescending<ProcInfo> { it.cpu }.thenByDescending { it.rss }
                    1 -> compareByDescending { it.rss }
                    else -> compareBy { it.name.lowercase() }
                },
            )
            .toList()
    }

    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            Header("Processes", "${list.size} shown · ${s.threads} threads · ${s.running} running")
        }
        item(key = "search") { SearchField(query) { query = it } }
        item(key = "sort") {
            Segmented(listOf("CPU", "Memory", "Name"), sort, { sort = it }, Modifier.fillMaxWidth(), track = p.track)
        }
        item(key = "filter") {
            Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FILTERS.forEachIndexed { i, name ->
                    val on = i == filter
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(if (on) p.text else p.card)
                            .plainClick { filter = i }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) { T(name, 13, color = if (on) p.bg else p.sub, weight = FontWeight.Medium) }
                }
            }
        }
        items(list, key = { it.pid }) { proc ->
            ProcRow(proc, Modifier.animateItem()) { selected = proc.pid }
        }
    }

    selected?.let { pid ->
        val proc = s.procs.firstOrNull { it.pid == pid }
        if (proc == null) LaunchedEffect(pid) { selected = null }
        else ProcSheet(proc, s.uptime, vm) { selected = null }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(CircleShape).background(p.card).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val w = 1.8.dp.toPx()
            val r = size.width * 0.36f
            drawCircle(p.sub, r, Offset(r + w / 2, r + w / 2), style = Stroke(w))
            drawLine(p.sub, Offset(r * 1.75f + w / 2, r * 1.75f + w / 2), Offset(size.width - w / 2, size.height - w / 2), w, StrokeCap.Round)
        }
        Box(Modifier.weight(1f).padding(start = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) T("Search name, command or PID", 15, color = p.sub)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = p.text, fontSize = 15.sp),
                cursorBrush = SolidColor(p.blue),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            T("Clear", 13, Modifier.plainClick { onChange("") }, p.blue, FontWeight.Medium)
        }
    }
}

@Composable
private fun Avatar(proc: ProcInfo, size: Int) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape((size * 0.28f).dp)
    if (proc.icon != null) {
        Image(proc.icon, null, Modifier.size(size.dp).clip(shape))
    } else {
        Box(Modifier.size(size.dp).clip(shape).background(p.track), contentAlignment = Alignment.Center) {
            T(
                proc.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
                (size * 0.4f).toInt(), color = p.sub, weight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ProcRow(proc: ProcInfo, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(p.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(proc, 42)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            T(proc.name, 15, weight = FontWeight.Medium)
            T("${proc.pid} · ${proc.user}", 12, color = p.sub)
        }
        Column(horizontalAlignment = Alignment.End) {
            T(
                String.format(Locale.US, "%.1f%%", proc.cpu), 15,
                color = when {
                    proc.cpu >= 10f -> p.orange
                    proc.cpu >= 0.05f -> p.text
                    else -> p.sub
                },
                weight = FontWeight.SemiBold,
            )
            T(if (proc.rss > 0) fmtBytes(proc.rss) else "–", 12, color = p.sub)
        }
    }
}

private fun stateName(c: Char) = when (c) {
    'R' -> "Running"
    'S' -> "Sleeping"
    'I' -> "Idle"
    'D' -> "Disk wait"
    'Z' -> "Zombie"
    'T', 't' -> "Stopped"
    else -> c.toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProcSheet(proc: ProcInfo, uptime: Long, vm: MonitorViewModel, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = p.card,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
    ) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(proc, 54)
                Column(Modifier.padding(start = 14.dp)) {
                    T(proc.name, 21, weight = FontWeight.Bold)
                    T(proc.pkg ?: proc.user, 13, color = p.sub)
                }
            }
            Spacer(Modifier.height(22.dp))
            val stats = listOf(
                "PID" to proc.pid.toString(),
                "Parent" to proc.ppid.toString(),
                "User" to proc.user,
                "CPU" to String.format(Locale.US, "%.1f%%", proc.cpu),
                "Memory" to fmtBytes(proc.rss),
                "Threads" to proc.threads.toString(),
                "State" to stateName(proc.state),
                "Nice" to proc.nice.toString(),
                "CPU time" to fmtDuration(proc.cpuSeconds),
                "Running for" to fmtDuration((uptime - proc.startSeconds).coerceAtLeast(0)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                for (row in stats.chunked(3)) {
                    Row {
                        for ((k, v) in row) Stat(k, v, Modifier.weight(1f))
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.track).padding(14.dp)) {
                T(proc.cmdline, 12, maxLines = 4, mono = true)
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (proc.pkg != null) {
                    Action("Force stop", p.text, p.track, Modifier.weight(1f)) {
                        vm.forceStop(proc.pkg); onDismiss()
                    }
                }
                Action("Terminate", p.text, p.track, Modifier.weight(1f)) {
                    vm.signal(proc.pid, 15); onDismiss()
                }
                Action("Kill", p.red, p.red.copy(alpha = 0.14f), Modifier.weight(1f)) {
                    vm.signal(proc.pid, 9); onDismiss()
                }
            }
        }
    }
}

@Composable
private fun Action(label: String, color: Color, bg: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(52.dp).clip(RoundedCornerShape(18.dp)).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { T(label, 15, color = color, weight = FontWeight.SemiBold) }
}
