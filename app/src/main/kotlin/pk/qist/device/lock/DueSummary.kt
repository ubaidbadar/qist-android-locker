package pk.qist.device.lock

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pk.qist.device.R

/** The amount owed, large, on an amber card, with the next due date under it. Shows nothing when neither is known. */
@Composable
fun DueSummary(amountDue: String?, nextDueDate: String?, modifier: Modifier = Modifier) {
    if (amountDue == null && nextDueDate == null) return
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
    ) {
        Column(Modifier.padding(20.dp)) {
            if (amountDue != null) {
                Text(stringResource(R.string.lock_amount_due), style = MaterialTheme.typography.titleMedium)
                Text(amountDue, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            }
            if (nextDueDate != null) {
                Text(
                    stringResource(R.string.lock_next_due, nextDueDate),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
