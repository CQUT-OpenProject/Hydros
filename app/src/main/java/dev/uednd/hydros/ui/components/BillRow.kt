package dev.uednd.hydros.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.uednd.hydros.ui.state.*

@Composable
fun BillRow(bill: BillUi, onClick: (() -> Unit)? = null) {
    val content: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    bill.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    bill.time.format(BillTimeFormat),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "${if (bill.recharge) "+" else "−"} ¥${money(bill.amountCents)}",
                style = MaterialTheme.typography.titleMedium,
                color = if (bill.recharge) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
    if (onClick == null) Surface(shape = MaterialTheme.shapes.small, content = content)
    else Surface(onClick = onClick, shape = MaterialTheme.shapes.small, content = content)
}

