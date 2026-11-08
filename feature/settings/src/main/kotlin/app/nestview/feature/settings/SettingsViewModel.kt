package app.nestview.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nestview.core.data.CameraRepository
import app.nestview.core.data.MonitorRepository
import app.nestview.core.sdm.SdmConfigRepository
import app.nestview.core.sdm.auth.AuthManager
import app.nestview.core.sdm.auth.AuthStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CameraRow(
    val id: String,
    val name: String,
    val originalName: String,
    val room: String?,
    val shown: Boolean,
)

data class SettingsUiState(
    val cameras: List<CameraRow> = emptyList(),
    val demoMode: Boolean = false,
    val backgroundListening: Boolean = true,
    val authStatus: AuthStatus = AuthStatus.SIGNED_OUT,
    val projectId: String = "",
    val busy: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: MonitorRepository,
    private val cameras: CameraRepository,
    private val auth: AuthManager,
    configRepository: SdmConfigRepository,
) : ViewModel() {

    private val transient = MutableStateFlow(SettingsUiState())

    val state: StateFlow<SettingsUiState> = combine(
        repository.state,
        auth.status,
        configRepository.config,
        transient,
    ) { app, authStatus, config, t ->
        SettingsUiState(
            cameras = app.cameras.map { c ->
                CameraRow(
                    id = c.id,
                    name = app.displayName(c.id),
                    originalName = c.name,
                    room = c.room,
                    shown = c.id !in app.hiddenCameraIds,
                )
            },
            demoMode = app.demoMode,
            backgroundListening = app.settings.backgroundListening,
            authStatus = authStatus,
            projectId = config.projectId,
            busy = t.busy,
            message = t.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setShown(cameraId: String, shown: Boolean) {
        viewModelScope.launch { repository.setCameraShown(cameraId, shown) }
    }

    fun rename(cameraId: String, nickname: String) {
        viewModelScope.launch {
            repository.updateCameraPrefs(cameraId) { it.copy(nickname = nickname.trim().ifBlank { null }) }
        }
    }

    fun setBackgroundListening(enabled: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(backgroundListening = enabled) } }
    }

    fun refreshCameras() = runBusy {
        val discovery = cameras.refreshFromNest().getOrThrow()
        "Found ${discovery.cameras.size} camera(s)" +
            if (discovery.unsupported > 0) " · ${discovery.unsupported} older RTSP-only camera(s) skipped" else ""
    }

    fun signOut() {
        auth.signOut()
        transient.update { it.copy(message = "Signed out") }
    }

    /** Returns true when leaving demo mode needs a Google sign-in first. */
    fun setDemoMode(enabled: Boolean, onNeedsSignIn: () -> Unit) = runBusy {
        if (enabled) {
            cameras.enableDemo()
            "Demo cameras on"
        } else {
            cameras.disableDemo()
            if (auth.status.value == AuthStatus.SIGNED_IN) {
                val discovery = cameras.refreshFromNest().getOrThrow()
                "Showing your ${discovery.cameras.size} camera(s)"
            } else {
                onNeedsSignIn()
                null
            }
        }
    }

    private fun runBusy(block: suspend () -> String?) {
        viewModelScope.launch {
            transient.update { it.copy(busy = true, message = null) }
            val message = try {
                block()
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            transient.update { it.copy(busy = false, message = message) }
        }
    }
}
