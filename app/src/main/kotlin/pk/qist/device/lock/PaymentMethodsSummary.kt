package pk.qist.device.lock

import android.content.ClipData
import android.content.ClipboardManager
import android.view.Gravity
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import pk.qist.device.R
import pk.qist.device.data.RetailerPaymentMethod
import pk.qist.device.ui.theme.QistLightColors
import pk.qist.device.ui.theme.QistLockLayout

@Composable
internal fun PaymentMethodsSummary(screen: LockScreenUi) {
    var details by remember { mutableStateOf(false) }
    val retailer = screen.retailerName ?: screen.branchName ?: stringResource(R.string.lock_your_retailer)
    Surface(
        shape = RoundedCornerShape(QistLockLayout.CardRadius),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.fillMaxWidth().padding(QistLockLayout.Padding)) {
            Row(horizontalArrangement = Arrangement.spacedBy(QistLockLayout.Gap)) {
                Text(
                    text = stringResource(R.string.lock_pay_retailer, retailer),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(onClick = { details = true }) {
                    Text(stringResource(R.string.lock_view_payment_details))
                }
            }
            if (screen.paymentMethods.isEmpty()) {
                Text(
                    stringResource(R.string.lock_no_payment_methods),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(QistLockLayout.Gap)) {
                    screen.paymentMethods.distinctBy { it.kind }.forEach { method ->
                        AssistChip(onClick = { details = true }, label = { Text(paymentMethodLabel(method.kind)) })
                    }
                }
            }
        }
    }
    if (details) PaymentMethodsSheet(screen, retailer) { details = false }
}

/** A bottom-aligned platform dialog, using stable Compose APIs and the existing light palette. */
@Composable
private fun PaymentMethodsSheet(screen: LockScreenUi, retailer: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        LaunchedEffect(view) {
            (view.parent as? DialogWindowProvider)?.window?.setGravity(Gravity.BOTTOM)
        }
        MaterialTheme(colorScheme = QistLightColors) {
            Surface(
                shape = RoundedCornerShape(topStart = QistLockLayout.CardRadius, topEnd = QistLockLayout.CardRadius),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = QistLockLayout.SheetMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(QistLockLayout.Padding),
                    verticalArrangement = Arrangement.spacedBy(QistLockLayout.SectionGap)
                ) {
                    Text(stringResource(R.string.lock_payment_methods), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.lock_pay_retailer, retailer),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (screen.paymentMethods.isEmpty()) Text(stringResource(R.string.lock_no_payment_methods))
                    screen.paymentMethods.forEach { method -> PaymentMethodCard(method, screen.branchAddress) }
                    Text(
                        stringResource(R.string.lock_direct_payment_notice),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth().heightIn(min = QistLockLayout.TouchTarget)
                    ) {
                        Text(stringResource(R.string.lock_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun PaymentMethodCard(method: RetailerPaymentMethod, address: String) {
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(QistLockLayout.CardRadius), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxWidth().padding(QistLockLayout.Padding),
            verticalArrangement = Arrangement.spacedBy(QistLockLayout.Gap)
        ) {
            Text(paymentMethodLabel(method.kind), style = MaterialTheme.typography.titleMedium)
            if (method.kind == "cash") {
                Text(address.ifBlank { stringResource(R.string.lock_no_shop_address) })
            } else {
                if (method.bankName.isNotBlank()) Text(method.bankName)
                Text(method.accountTitle)
                Text(method.accountNumber, style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Account number", method.accountNumber))
                        Toast.makeText(context, R.string.lock_copied, Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text(stringResource(R.string.lock_copy))
                }
            }
        }
    }
}

@Composable
private fun paymentMethodLabel(kind: String): String = stringResource(
    when (kind) {
        "cash" -> R.string.lock_cash
        "easypaisa" -> R.string.lock_easypaisa
        "jazzcash" -> R.string.lock_jazzcash
        "bank" -> R.string.lock_bank
        else -> R.string.lock_payment_methods
    }
)
