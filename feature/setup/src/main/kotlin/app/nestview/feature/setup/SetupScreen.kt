package app.nestview.feature.setup

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** One-time setup: connect Google Home (or try the demo). */
@Composable
fun SetupScreen(onFinished: () -> Unit, viewModel: SetupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.finished.collect { onFinished() } }
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onAuthorizationResult(result.data)
    }

    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Nest View", style = MaterialTheme.typography.displaySmall)
            Text(
                "All your Nest cameras on one screen, made for keeping an eye on the kids.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Connect your cameras", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Sign in with the Google account that has your Nest cameras, then allow access to the cameras you want to watch.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!state.config.isComplete) {
                        Text(
                            "First enter your Google Device Access details under \"Device Access settings\" below (see docs/SETUP.md).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(
                        onClick = {
                            if (state.config.usesManualRedirect) {
                                viewModel.startManualSignIn()?.let { uri ->
                                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                }
                            } else {
                                viewModel.authorizationIntent()?.let(signIn::launch)
                            }
                        },
                        enabled = state.config.isComplete && !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Connect Google Home") }

                    if (state.waitingForPastedAddress) {
                        var address by rememberSaveable { mutableStateOf("") }
                        Text(
                            "After allowing access, Google opens a page that may look empty. Copy that page's full address from the browser and paste it here.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            label = { Text("Address of the page you ended on") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { viewModel.completeManualSignIn(address) },
                            enabled = address.isNotBlank() && !state.busy,
                        ) { Text("Finish") }
                    }

                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                }
            }

            OutlinedButton(onClick = viewModel::startDemo, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Try the demo (no cameras needed)")
            }

            DeviceAccessSettings(state = state, onSave = viewModel::saveConfig)

            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Text(
                    "Nest View is not a medical device. It doesn't monitor breathing and doesn't replace checking on your child. " +
                        "Google's API doesn't allow talking through the camera.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun DeviceAccessSettings(state: SetupUiState, onSave: (String, String, String, String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(!state.config.isComplete) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "Hide Device Access settings" else "Device Access settings")
    }
    if (!expanded) return
    var projectId by rememberSaveable { mutableStateOf(state.config.projectId) }
    var clientId by rememberSaveable { mutableStateOf(state.config.clientId) }
    var clientSecret by rememberSaveable { mutableStateOf(state.config.clientSecret.orEmpty()) }
    var redirectUri by rememberSaveable { mutableStateOf(state.config.redirectUri) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(projectId, { projectId = it }, label = { Text("Device Access project ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(clientId, { clientId = it }, label = { Text("OAuth client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            clientSecret,
            { clientSecret = it },
            label = { Text("OAuth client secret (Web clients only)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(redirectUri, { redirectUri = it }, label = { Text("Redirect URI") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(
            "Android clients use com.googleusercontent.apps.<id>:/oauth2redirect and must match the build. " +
                "Web clients use https://www.google.com and the paste-the-address step.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { onSave(projectId, clientId, clientSecret, redirectUri) }) { Text("Save") }
        state.info?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
