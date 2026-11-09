package app.nestview.feature.monitor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/** The cameras that aren't on this view, each with its last picture; tap one to add it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddCameraSheet(
    viewName: String,
    cameras: List<AddableCamera>,
    snapshot: suspend (String) -> Bitmap?,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("Add a camera", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp))
            Text(
                (if (viewName.isBlank()) "It joins the end of this view." else "It joins the end of “$viewName”.") +
                    " Hold and drag a camera to move it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
            )
            LazyColumn {
                items(cameras, key = { it.cameraId }) { camera ->
                    val picture by produceState<Bitmap?>(null, camera.cameraId) { value = snapshot(camera.cameraId) }
                    ListItem(
                        headlineContent = { Text(camera.name) },
                        supportingContent = camera.room?.takeIf { it.isNotBlank() && it != camera.name }?.let { room -> { Text(room) } },
                        leadingContent = { Thumbnail(picture) },
                        trailingContent = { Icon(Icons.Default.Add, contentDescription = "Add ${camera.name}") },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onAdd(camera.cameraId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(picture: Bitmap?) {
    Box(
        Modifier
            .size(width = 96.dp, height = 54.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1B1D21)),
        contentAlignment = Alignment.Center,
    ) {
        if (picture != null) {
            val image = remember(picture) { picture.asImageBitmap() }
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Default.Videocam, contentDescription = null, tint = Color.Gray)
        }
    }
}
