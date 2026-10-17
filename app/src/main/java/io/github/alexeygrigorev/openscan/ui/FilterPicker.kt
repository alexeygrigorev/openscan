package io.github.alexeygrigorev.openscan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.alexeygrigorev.openscan.scan.ScanFilter

/**
 * Shared enhancement picker (items 6+7): the four filters, optionally with a
 * leading "Use default (…)" row that clears the override (null).
 */
@Composable
fun FilterPickerDialog(
    title: String,
    selectedId: String?,
    defaultLabel: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (defaultLabel != null) {
                    FilterRow(
                        title = "Use default",
                        subtitle = defaultLabel,
                        selected = selectedId == null,
                        onClick = { onPick(null) },
                    )
                }
                for (filter in ScanFilter.entries) {
                    FilterRow(
                        title = filter.title,
                        subtitle = if (filter == ScanFilter.DEFAULT) "Recommended" else null,
                        selected = selectedId == filter.id,
                        onClick = { onPick(filter.id) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun FilterRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
