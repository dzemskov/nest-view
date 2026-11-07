package app.nestview.feature.setup

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nestview.core.data.CameraRepository
import app.nestview.core.sdm.SdmConfig
import app.nestview.core.sdm.SdmConfigRepository
import app.nestview.core.sdm.auth.AuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SetupUiState(
    val config: SdmConfig,
    val busy: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    /** Manual flow: the Google page was opened and we're waiting for the pasted address. */
    val waitingForPastedAddress: Boolean = false,
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val auth: AuthManager,
    private val configRepository: SdmConfigRepository,
    private val cameras: CameraRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SetupUiState(config = configRepository.config.value))
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    private val _finished = Channel<Unit>(Channel.BUFFERED)
    val finished: Flow<Unit> = _finished.receiveAsFlow()

    /** Browser-tab sign-in for Android OAuth clients; null (with an error shown) if not possible. */
    fun authorizationIntent(): Intent? = runCatching { auth.authorizationIntent() }
        .onFailure { e -> _state.update { it.copy(error = e.message) } }
        .getOrNull()

    fun onAuthorizationResult(data: Intent?) = runBusy {
        auth.handleAuthorizationResult(data).getOrThrow()
        discoverCameras()
    }

    /** Manual sign-in for Web OAuth clients: returns the Google page to open in a browser. */
    fun startManualSignIn(): Uri? = runCatching { auth.startManualAuthorization() }
        .onSuccess { _state.update { it.copy(waitingForPastedAddress = true, error = null) } }
        .onFailure { e -> _state.update { it.copy(error = e.message) } }
        .getOrNull()

    fun completeManualSignIn(address: String) = runBusy {
        auth.completeManualAuthorization(address).getOrThrow()
        _state.update { it.copy(waitingForPastedAddress = false) }
        discoverCameras()
    }

    fun saveConfig(projectId: String, clientId: String, clientSecret: String, redirectUri: String) {
        configRepository.update(SdmConfig(projectId, clientId, clientSecret.ifBlank { null }, redirectUri))
        _state.update { it.copy(config = configRepository.config.value, info = "Saved", error = null) }
    }

    fun startDemo() = runBusy {
        cameras.enableDemo()
        _finished.send(Unit)
    }

    private suspend fun discoverCameras() {
        val discovery = cameras.refreshFromNest().getOrThrow()
        if (discovery.cameras.isEmpty()) {
            val skipped = if (discovery.unsupported > 0) {
                " ${discovery.unsupported} older camera(s) that only support RTSP were skipped."
            } else {
                ""
            }
            error("Signed in, but no cameras were shared. On the Google page, allow access to your cameras.$skipped")
        }
        _finished.send(Unit)
    }

    private fun runBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, info = null) }
            try {
                block()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
