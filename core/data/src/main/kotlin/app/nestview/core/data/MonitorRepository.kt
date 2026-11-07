package app.nestview.core.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import app.nestview.core.model.CameraInfo
import app.nestview.core.model.CameraPrefs
import app.nestview.core.model.DemoCameras
import app.nestview.core.model.MonitorView
import app.nestview.core.sdm.CameraDiscovery
import app.nestview.core.sdm.DeviceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

internal object AppStateSerializer : Serializer<AppState> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    override val defaultValue: AppState = AppState()

    override suspend fun readFrom(input: InputStream): AppState = try {
        json.decodeFromString(AppState.serializer(), input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("Unreadable app state", e)
    }

    override suspend fun writeTo(t: AppState, output: OutputStream) {
        output.write(json.encodeToString(AppState.serializer(), t).encodeToByteArray())
    }
}

/** Views, camera preferences and the cached camera list. */
@Singleton
class MonitorRepository @Inject constructor(private val store: DataStore<AppState>) {

    val state: Flow<AppState> = store.data

    suspend fun current(): AppState = store.data.first()

    suspend fun update(transform: (AppState) -> AppState) {
        store.updateData(transform)
    }

    suspend fun updateView(viewId: String, transform: (MonitorView) -> MonitorView) = update { s ->
        s.copy(views = s.views.map { if (it.id == viewId) transform(it) else it })
    }

    suspend fun updateCameraPrefs(cameraId: String, transform: (CameraPrefs) -> CameraPrefs) = update { s ->
        s.copy(cameraPrefs = s.cameraPrefs + (cameraId to transform(s.prefsFor(cameraId))))
    }

    suspend fun setCameraShown(cameraId: String, shown: Boolean) = update { AppStateOps.setCameraShown(it, cameraId, shown) }
}

/** Where cameras come from: Google (real cameras) or the built-in demo. */
@Singleton
class CameraRepository @Inject constructor(
    private val monitor: MonitorRepository,
    private val devices: DeviceRepository,
) {
    suspend fun refreshFromNest(): Result<CameraDiscovery> = runCatching { devices.discoverCameras() }
        .onSuccess { discovery ->
            monitor.update { s -> AppStateOps.withCameras(s.copy(demoMode = false), discovery.cameras) }
        }

    suspend fun enableDemo() = monitor.update { s ->
        AppStateOps.withCameras(AppState(demoMode = true), DemoCameras.all).copy(
            cameraPrefs = s.cameraPrefs.filterKeys { it.startsWith(DemoCameras.ID_PREFIX) },
        )
    }

    /** Leaves demo mode; real cameras appear after the next refresh from Google. */
    suspend fun disableDemo() = monitor.update { AppState(demoMode = false) }

    fun demoCameras(): List<CameraInfo> = DemoCameras.all
}
