package dev.phonkd.atop

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

const val HISTORY = 60
private const val INTERVAL_MS = 1000L

enum class Root { Checking, Granted, Denied }

data class UiState(
    val root: Root = Root.Checking,
    val snap: Snapshot? = null,
    val cpuHist: List<Float> = emptyList(),
    val gpuHist: List<Float> = emptyList(),
    val rxHist: List<Float> = emptyList(),
    val txHist: List<Float> = emptyList(),
)

class MonitorViewModel(app: Application) : AndroidViewModel(app) {
    private val shell = RootShell()
    private val collector = Collector(app, shell)
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    /** Samples until cancelled; driven by the activity so nothing runs in the background. */
    suspend fun poll() {
        while (true) {
            val snap = collector.sample()
            val s = _state.value
            _state.value = if (snap == null) s.copy(root = Root.Denied) else s.copy(
                root = Root.Granted,
                snap = snap,
                cpuHist = (s.cpuHist + snap.cpu).takeLast(HISTORY),
                gpuHist = (s.gpuHist + (snap.gpu ?: 0f)).takeLast(HISTORY),
                rxHist = (s.rxHist + snap.rxRate).takeLast(HISTORY),
                txHist = (s.txHist + snap.txRate).takeLast(HISTORY),
            )
            // After a denial, back off: every attempt may pop a superuser prompt.
            delay(if (snap == null) 5000L else INTERVAL_MS)
        }
    }

    fun signal(pid: Int, signal: Int) {
        viewModelScope.launch { shell.exec("kill -$signal $pid") }
    }

    fun forceStop(pkg: String) {
        if (!PKG.matches(pkg)) return
        viewModelScope.launch { shell.exec("am force-stop $pkg") }
    }

    private companion object {
        val PKG = Regex("[A-Za-z0-9._]+")
    }
}

class MainActivity : ComponentActivity() {
    private val vm: MonitorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { vm.poll() }
        }
        setContent { AtopTheme { App(vm) } }
    }
}
