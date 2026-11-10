package app.nestview.feature.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nestview.core.sdm.auth.AuthStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSetup: () -> Unit,
    versionName: String,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<CameraRow?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.message?.let { msg ->
                item { Text(msg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp)) }
            }

            item { SectionHeader("Cameras on the monitor") }
            items(state.cameras, key = { it.id }) { row ->
                ListItem(
                    headlineContent = { Text(row.name) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    supportingContent = {
                        val details = listOfNotNull(row.room?.takeIf { it != row.name }, row.originalName.takeIf { it != row.name })
                        Text(if (details.isEmpty()) "Tap to rename" else details.joinToString(" · ") + " · tap to rename")
                    },
                    trailingContent = { Switch(checked = row.shown, onCheckedChange = { viewModel.setShown(row.id, it) }) },
                    modifier = Modifier
                        .widthIn(max = 720.dp)
                        .clickable { renaming = row },
                )
            }
            if (!state.demoMode) {
                item {
                    OutlinedButton(onClick = viewModel::refreshCameras, enabled = !state.busy, modifier = Modifier.padding(16.dp)) {
                        Text("Refresh camera list from Google")
                    }
                }
            }

            item { HorizontalDivider(Modifier.widthIn(max = 720.dp)) }
            item { SectionHeader("Listening with the screen off") }
            item {
                BackgroundSection(
                    onAtStart = state.keepListeningAtStart,
                    onAtStartChange = viewModel::setKeepListeningAtStart,
                )
            }

            item { HorizontalDivider(Modifier.widthIn(max = 720.dp)) }
            item { SectionHeader("Google account") }
            item {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when (state.authStatus) {
                            AuthStatus.SIGNED_IN -> "Connected to Google Home" + if (state.projectId.isNotBlank()) " (project ${state.projectId})" else ""
                            AuthStatus.NEEDS_SIGN_IN -> "Google sign-in expired. Connect again to keep watching."
                            AuthStatus.SIGNED_OUT -> "Not connected"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onOpenSetup) {
                            Text(if (state.authStatus == AuthStatus.SIGNED_OUT) "Connect Google Home" else "Connect again")
                        }
                        if (state.authStatus != AuthStatus.SIGNED_OUT) {
                            TextButton(onClick = viewModel::signOut) { Text("Sign out") }
                        }
                    }
                }
            }

            item { HorizontalDivider(Modifier.widthIn(max = 720.dp).padding(top = 16.dp)) }
            item {
                ListItem(
                    headlineContent = { Text("Demo cameras") },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    supportingContent = { Text("Four simulated cameras with sound, to try the app without Nest cameras.") },
                    trailingContent = {
                        Switch(
                            checked = state.demoMode,
                            onCheckedChange = { viewModel.setDemoMode(it, onNeedsSignIn = onOpenSetup) },
                            enabled = !state.busy,
                        )
                    },
                    modifier = Modifier.widthIn(max = 720.dp),
                )
            }

            item { HorizontalDivider(Modifier.widthIn(max = 720.dp)) }
            item { SectionHeader("About") }
            item {
                Text(
                    "Nest View $versionName\n\n" +
                        "Not a medical device: it doesn't monitor breathing and doesn't replace checking on your child.\n\n" +
                        "Google's API for third-party apps doesn't allow talking through the camera, playing sounds on it, " +
                        "changing camera settings or viewing recorded history. The 2025 3rd-generation Nest cameras aren't available to it yet. " +
                        "Battery cameras need a fresh stream every 5 minutes; Nest View switches over in the background.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 720.dp).padding(16.dp),
                )
            }
        }
    }

    renaming?.let { row ->
        var name by remember(row.id) { mutableStateOf(row.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename camera") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name on the monitor") })
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(row.id, if (name == row.originalName) "" else name)
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}

/**
 * "Keep listening" is switched on the monitor (the ear button). Here: whether it starts on, plus
 * the two Android settings that keep it working overnight.
 */
@Composable
private fun BackgroundSection(onAtStart: Boolean, onAtStartChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    var unrestricted by remember { mutableStateOf(true) }
    var notificationsOn by remember { mutableStateOf(true) }
    // Re-checked on return from Android's settings screens.
    LifecycleResumeEffect(Unit) {
        unrestricted = context.isIgnoringBatteryOptimizations()
        notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { }
    }
    Column(Modifier.widthIn(max = 720.dp)) {
        Text(
            "Sound stops when you turn off the screen or leave the app. To keep hearing the cameras, turn on " +
                "Keep listening with the ear button on the monitor; a notification with Mute and Stop shows while it's on.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        ListItem(
            headlineContent = { Text("Start with Keep listening on") },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            supportingContent = { Text("Turns it on every time the monitor opens.") },
            trailingContent = { Switch(checked = onAtStart, onCheckedChange = onAtStartChange) },
            modifier = Modifier.clickable { onAtStartChange(!onAtStart) },
        )
        if (!unrestricted) {
            ListItem(
                headlineContent = { Text("Let it run all night") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                supportingContent = { Text("Android may stop Nest View to save battery. Tap to let it keep running.") },
                modifier = Modifier.clickable { context.requestUnrestrictedBattery() },
            )
        }
        if (!notificationsOn) {
            ListItem(
                headlineContent = { Text("Show the listening notification") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                supportingContent = { Text("Notifications are off, so Mute and Stop don't show when the app is in the background.") },
                modifier = Modifier.clickable { context.openNotificationSettings() },
            )
        }
    }
}

private fun Context.isIgnoringBatteryOptimizations(): Boolean =
    getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

@SuppressLint("BatteryLife") // a personal baby monitor has to run all night
private fun Context.requestUnrestrictedBattery() {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
    runCatching { startActivity(direct) }.onFailure {
        runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

private fun Context.openNotificationSettings() {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    runCatching { startActivity(intent) }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}
