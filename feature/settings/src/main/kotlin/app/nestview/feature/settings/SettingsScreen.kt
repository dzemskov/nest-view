package app.nestview.feature.settings

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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
