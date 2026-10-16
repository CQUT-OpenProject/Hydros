package dev.uednd.hydros.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.uednd.hydros.R

/** Inline loading or empty placeholder without an outer card container. */
@Composable
fun InlineState(
    title: String,
    description: String = "",
    loading: Boolean = false,
    icon: Int? = if (loading) null else R.drawable.ic_info,
    action: String? = null,
    onAction: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(32.dp))
        } else if (icon != null) {
            IconTile(icon, tone = IconTone.NEUTRAL)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (description.isNotBlank()) {
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        if (action != null) {
            OutlinedButton(
                onClick = onAction,
                modifier = Modifier.heightIn(min = 44.dp),
                shape = CircleShape
            ) {
                Text(action)
            }
        }
    }
}

@Composable
fun StateCard(
    title: String,
    description: String,
    loading: Boolean = false,
    action: String? = null,
    onAction: () -> Unit = {}
) {
    Section {
        InlineState(
            title = title,
            description = description,
            loading = loading,
            action = action,
            onAction = onAction
        )
    }
}

