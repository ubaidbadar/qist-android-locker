package pk.qist.device.auth

import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pk.qist.device.container
import pk.qist.device.ui.theme.QistTheme

/** No intent extra can approve a request. Approval always requires visible UI and deliberate number entry. */
class SigninActivity : ComponentActivity() {
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            QistTheme {
                val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                val requests by container.customerSignin.requests.collectAsStateWithLifecycle()
                val unavailable by container.customerSignin.unavailable.collectAsStateWithLifecycle()
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(
                            rememberScrollState()
                        ).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("Qist sign-in", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "Only approve a sign-in that you started. Qist staff will never ask you to approve someone else's request."
                        )
                        if (Build.VERSION.SDK_INT >= 33 &&
                            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            OutlinedButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                                Text("Allow sign-in notifications")
                            }
                        }
                        unavailable?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        OutlinedButton(onClick = { refresh() }, enabled = !busy) { Text("Refresh requests") }
                        if (requests.isEmpty() && unavailable == null) Text("No pending sign-in requests.")
                        requests.forEach { request ->
                            key(request.requestId) {
                                var number by remember(request.requestId) { mutableStateOf("") }
                                var code by remember(request.requestId) { mutableStateOf<String?>(null) }
                                LaunchedEffect(request.requestId) {
                                    delay(
                                        (
                                            signinExpiry(
                                                request.expiresAt
                                            ) - System.currentTimeMillis()
                                            ).coerceIn(0, 180_000)
                                    )
                                    code = null
                                    refresh()
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Sign-in on another phone", style = MaterialTheme.typography.titleLarge)
                                    if (request.kind == "otp") {
                                        Text(
                                            "Only show a code for a sign-in you started. " +
                                                "Enter it in Qist Customer on the other device. " +
                                                "Never share it with staff or callers."
                                        )
                                        code?.let { LockerCodeCard(it, request.expiresAt) { code = null } }
                                        Button(
                                            onClick = { showCode(request) { code = it } },
                                            enabled =
                                                !busy && unavailable == null
                                        ) {
                                            Text(if (code == null) "Show sign-in code" else "Show code again")
                                        }
                                    } else {
                                        Text(
                                            "Enter the three-digit number shown on the phone where you started signing in. Requests expire after three minutes."
                                        )
                                        OutlinedTextField(
                                            value = number,
                                            onValueChange = { number = it.filter(Char::isDigit).take(3) },
                                            label = { Text("Three-digit number") },
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.NumberPassword
                                            ),
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            enabled = !busy
                                        )
                                        Button(
                                            onClick = { decide(request, true, number) },
                                            enabled =
                                                !busy && number.matches(Regex("[0-9]{3}")) && unavailable == null
                                        ) {
                                            Text("Approve sign-in")
                                        }
                                        OutlinedButton(
                                            onClick = { decide(request, false, "") },
                                            enabled =
                                                !busy && unavailable == null
                                        ) { Text("Deny request") }
                                    }
                                }
                            }
                        }
                        OutlinedButton(onClick = { finish() }, enabled = !busy) { Text("Back") }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        refresh()
    }

    private fun refresh() {
        if (busy) return
        lifecycleScope.launch { container.customerSignin.refresh() }
    }

    private fun showCode(request: SigninRequest, onCode: (String) -> Unit) {
        if (busy) return
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isDeviceLocked) {
            message = "Unlock this phone to view your sign-in code."
            if (Build.VERSION.SDK_INT >= 26) keyguard.requestDismissKeyguard(this, null)
            return
        }
        busy = true
        message = null
        lifecycleScope.launch {
            try {
                val code = container.customerSignin.showCode(request)
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) onCode(code)
            } catch (
                error: CancellationException
            ) {
                throw error
            } catch (
                _: Exception
            ) {
                message = "This code is unavailable or expired. Refresh the request and try again."
            } finally {
                busy = false
            }
        }
    }

    private fun decide(request: SigninRequest, approve: Boolean, number: String) {
        if (busy) return
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isDeviceLocked) {
            message = "Unlock this phone, then review the request again."
            if (Build.VERSION.SDK_INT >= 26) keyguard.requestDismissKeyguard(this, null)
            return
        }
        busy = true
        message = null
        lifecycleScope.launch {
            try {
                container.customerSignin.decide(request, approve, number)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message = "Could not verify the request. Check the number, refresh, or use account recovery."
            } finally {
                busy = false
            }
        }
    }
}
