package io.github.alexeygrigorev.openscan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.alexeygrigorev.openscan.update.UpdateCheckResult
import io.github.alexeygrigorev.openscan.update.releaseVersionLabel

/**
 * The home-screen strip for a newer release. Shown only when a check
 * SUCCEEDED and found something; the download is a browser tab, not an
 * in-app install — sideloading the APK is the user's act, exactly like the
 * first time they put the app on the phone.
 */
@Composable
fun UpdateBanner(
    available: UpdateCheckResult.Available,
    onDownload: () -> Unit,
    onNotes: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${available.tagName} is available — you are on " +
                        releaseVersionLabel(available.currentVersion),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onDownload) {
                        Icon(Icons.Filled.Download, contentDescription = null)
                        Text("Download", modifier = Modifier.padding(start = 4.dp))
                    }
                    TextButton(onClick = onNotes) { Text("Notes") }
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss")
            }
        }
    }
}
