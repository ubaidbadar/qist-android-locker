package pk.qist.device.customer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal enum class CustomerRoute { Welcome, Phone, Verify, Home }
internal enum class CustomerTab { Home, Payments, Plan, Profile }

@Composable
internal fun CustomerShell() {
    var route by rememberSaveable { mutableStateOf(CustomerRoute.Welcome) }
    var tab by rememberSaveable { mutableStateOf(CustomerTab.Home) }

    BackHandler(route == CustomerRoute.Phone || route == CustomerRoute.Verify) {
        route = if (route == CustomerRoute.Verify) CustomerRoute.Phone else CustomerRoute.Welcome
    }
    Scaffold(bottomBar = {
        if (route == CustomerRoute.Home) {
            NavigationBar {
                CustomerTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = item == tab,
                        onClick = { tab = item },
                        icon = { Text(item.name.take(1)) },
                        label = { Text(item.name) },
                    )
                }
            }
        }
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val title = if (route == CustomerRoute.Home) tab.name else route.name
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (route != CustomerRoute.Home) {
                Button(
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = { route = CustomerRoute.entries[route.ordinal + 1] },
                ) { Text("Continue") }
            }
        }
    }
}
