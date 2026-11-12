package app.nestview

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.nestview.core.data.CameraRepository
import app.nestview.core.data.MonitorRepository
import app.nestview.core.designsystem.NestViewTheme
import app.nestview.core.sdm.auth.AuthManager
import app.nestview.core.sdm.auth.AuthStatus
import app.nestview.feature.monitor.MonitorScreen
import app.nestview.feature.monitor.alerts.AlertCoordinator
import app.nestview.feature.settings.SettingsScreen
import app.nestview.feature.setup.SetupScreen
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.ui.graphics.Color as ComposeColor

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var alerts: AlertCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            NestViewTheme {
                Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    AppRoot()
                }
            }
        }
        if (savedInstanceState == null) handleAlertTap(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAlertTap(intent)
    }

    /** An alert notification was tapped: show that camera full screen. */
    private fun handleAlertTap(intent: Intent?) {
        intent?.getStringExtra(AlertCoordinator.EXTRA_FOCUS_CAMERA)?.let(alerts::requestFocus)
    }
}

enum class Screen { SETUP, MONITOR, SETTINGS }

/** Decides where the app opens: straight into the monitor whenever cameras are known. */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val repository: MonitorRepository,
    private val auth: AuthManager,
    private val cameras: CameraRepository,
) : ViewModel() {
    private val _start = MutableStateFlow<Screen?>(null)
    val start: StateFlow<Screen?> = _start.asStateFlow()

    init {
        viewModelScope.launch {
            val state = repository.current()
            val signedIn = auth.status.value == AuthStatus.SIGNED_IN
            _start.value = if (state.demoMode || (signedIn && state.cameras.isNotEmpty())) Screen.MONITOR else Screen.SETUP
            // Pick up renamed, added or removed cameras in the background.
            if (signedIn && !state.demoMode) cameras.refreshFromNest()
        }
    }
}

@Composable
private fun AppRoot(viewModel: AppViewModel = hiltViewModel()) {
    val start by viewModel.start.collectAsStateWithLifecycle()
    val initial = start
    if (initial == null) {
        Box(Modifier.fillMaxSize().background(ComposeColor.Black))
        return
    }
    var screen by rememberSaveable { mutableStateOf(initial) }
    var setupFromSettings by rememberSaveable { mutableStateOf(false) }

    when (screen) {
        Screen.SETUP -> {
            BackHandler(enabled = setupFromSettings) { screen = Screen.SETTINGS }
            SetupScreen(onFinished = {
                setupFromSettings = false
                screen = Screen.MONITOR
            })
        }
        Screen.MONITOR -> MonitorScreen(onOpenSettings = { screen = Screen.SETTINGS })
        Screen.SETTINGS -> {
            BackHandler { screen = Screen.MONITOR }
            SettingsScreen(
                onBack = { screen = Screen.MONITOR },
                onOpenSetup = {
                    setupFromSettings = true
                    screen = Screen.SETUP
                },
                versionName = BuildConfig.VERSION_NAME,
            )
        }
    }
}
