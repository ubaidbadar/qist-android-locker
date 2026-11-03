package pk.qist.device.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import pk.qist.device.R
import pk.qist.device.ui.theme.QistTheme

/** The full-screen lock: why, how much, by when, where to pay, and the two calls that always work. */
@Composable
fun LockScreenContent(
    screen: LockScreenUi,
    onCallBranch: (phone: String) -> Unit,
    onEmergencyCall: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                stringResource(R.string.lock_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() }
            )
            Text(screen.message, style = MaterialTheme.typography.bodyLarge)
            DueSummary(amountDue = screen.amountDue, nextDueDate = screen.nextDueDate)
            if (screen.branchName != null) {
                Text(
                    stringResource(R.string.lock_pay_at, screen.branchName),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            LockActions(
                branchName = screen.branchName,
                branchPhone = screen.branchPhone,
                onCallBranch = onCallBranch,
                onEmergencyCall = onEmergencyCall
            )
        }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LockScreenContentPreview() {
    QistTheme {
        LockScreenContent(
            screen = LockScreenUi(
                message = "This phone is locked for an overdue installment of Rs 15,000. Please pay at Saddar.",
                amountDue = "Rs 15,000",
                nextDueDate = "12 Oct 2026",
                branchName = "Saddar",
                branchPhone = "0300 1234567"
            ),
            onCallBranch = {},
            onEmergencyCall = {}
        )
    }
}
