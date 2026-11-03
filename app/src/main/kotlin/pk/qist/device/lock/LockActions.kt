package pk.qist.device.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pk.qist.device.R

private val TouchTarget = 56.dp

/** "Call branch" (only when the branch has a phone number) and "Emergency call", both large and full width. */
@Composable
fun LockActions(
    branchName: String?,
    branchPhone: String?,
    onCallBranch: (phone: String) -> Unit,
    onEmergencyCall: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (branchPhone != null) {
            val description = stringResource(R.string.lock_call_branch_description, branchName.orEmpty(), branchPhone)
            Button(
                onClick = { onCallBranch(branchPhone) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = TouchTarget)
                    .semantics { contentDescription = description }
            ) {
                Text(stringResource(R.string.lock_call_branch), style = MaterialTheme.typography.titleMedium)
            }
        }
        OutlinedButton(
            onClick = onEmergencyCall,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TouchTarget)
        ) {
            Text(stringResource(R.string.lock_emergency_call), style = MaterialTheme.typography.titleMedium)
        }
    }
}
