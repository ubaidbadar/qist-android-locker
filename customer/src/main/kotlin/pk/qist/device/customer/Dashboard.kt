package pk.qist.device.customer

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay

@Composable
internal fun Dashboard(
    data: DashboardSample,
    tab: CustomerTab,
    onTab: (CustomerTab) -> Unit,
    onHelp: () -> Unit,
    phone: String,
    onLanguage: () -> Unit,
    onLogout: () -> Unit,
) {
    val tabs = listOf(copy("Home", "گھر"), copy("Payments", "ادائیگیاں"), copy("Plan", "منصوبہ"), copy("Profile", "پروفائل"))
    val icons = listOf(R.drawable.tab_home, R.drawable.tab_payments, R.drawable.tab_plan, R.drawable.tab_profile)
    Scaffold(
        containerColor = Canvas,
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                CustomerTab.entries.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { onTab(item) },
                        icon = { Icon(painterResource(icons[index]), contentDescription = null) },
                        label = { Text(tabs[index], maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            if (tab == CustomerTab.Home) {
                Box {
                    Box(Modifier.fillMaxWidth().height(160.dp).background(Navy))
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(copy("Assalam o Alaikum", "السلام علیکم"), color = Muted)
                                Text(data.name, color = Color.White, style = MaterialTheme.typography.headlineMedium)
                            }
                            HomeNotificationsButton()
                        }
                        InstallmentCard(data, onHelp)
                        Panel {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.size(44.dp).background(Blue.copy(alpha = 0.08f), CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(painterResource(R.drawable.phone_outline), contentDescription = null, tint = Blue)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(data.model, style = MaterialTheme.typography.titleMedium)
                                    Text(copy("Your phone", "آپ کا فون"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(
                                    copy("✓ Active", "✓ فعال"),
                                    Modifier.background(Color(0xFFD9F4EB), CircleShape).padding(horizontal = 10.dp, vertical = 6.dp),
                                    color = Color(0xFF08674D),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                        PaymentsCard(data.payments) { onTab(CustomerTab.Payments) }
                    }
                }
            } else {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(if (tab == CustomerTab.Plan) copy("My plan", "میرا منصوبہ") else tabs[tab.ordinal], style = MaterialTheme.typography.headlineMedium)
                    when (tab) {
                        CustomerTab.Payments -> PaymentHistory(data)
                        CustomerTab.Plan -> PlanDetails(data)
                        CustomerTab.Profile -> CustomerProfile(data, phone, onLanguage, onLogout)
                        CustomerTab.Home -> Unit
                    }
                }
            }
        }
    }
}

@Composable
internal fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(20.dp), content = content)
    }
}

@Composable
private fun InstallmentCard(data: DashboardSample, onHelp: () -> Unit) {
    Panel {
        Text(copy("Next installment", "اگلی قسط"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(copy("Rs. ${data.amount}", "روپے ${data.amount}"), style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(copy("Due ${data.dueDate} · in ${data.days} days", "${data.dueDate} تک · ${data.days} دن باقی"))
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { (data.paid.toFloat() / data.total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = Blue,
            trackColor = Color(0xFFE4E9F4),
        )
        Spacer(Modifier.height(6.dp))
        Text(copy("${data.paid} of ${data.total} installments paid", "${data.total} میں سے ${data.paid} اقساط ادا ہوئیں"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Pill(copy("How to pay", "ادائیگی کیسے کریں"), onHelp)
    }
}

@Composable
private fun PaymentsCard(payments: List<PaymentSample>, onViewAll: (() -> Unit)? = null) {
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(copy("Recent payments", "حالیہ ادائیگیاں"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            if (onViewAll != null) TextButton(onClick = onViewAll) { Text(copy("View all", "تمام دیکھیں")) }
        }
        payments.forEach { payment ->
            HorizontalDivider(color = Color(0xFFE4E9F4))
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(copy("Installment ${payment.number}", "قسط ${payment.number}"), style = MaterialTheme.typography.titleMedium)
                    Text(copy("Paid ${payment.date}", "ادا شدہ ${payment.date}"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(copy("Rs. ${payment.amount}", "روپے ${payment.amount}"), color = Color(0xFF08674D), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun HomeNotificationsButton() {
    var open by rememberSaveable { mutableStateOf(false) }
    var unread by rememberSaveable { mutableStateOf(true) }
    val label = copy("Notifications", "اطلاعات")

    FilledIconButton(
        onClick = {
            open = true
            unread = false
        },
        modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = DeepSurface,
            contentColor = Color.White,
        ),
    ) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.detail_bell), contentDescription = label)
            if (unread) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 1.dp, y = (-1).dp)
                        .size(11.dp).background(DeepSurface, RoundedCornerShape(4.dp))
                        .padding(1.dp).background(Cyan, RoundedCornerShape(3.dp)),
                )
            }
        }
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = { Text(copy("Check your plan for upcoming installments.", "آنے والی اقساط کے لیے اپنا منصوبہ دیکھیں۔")) },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(copy("Close", "بند کریں")) }
            },
        )
    }
}
