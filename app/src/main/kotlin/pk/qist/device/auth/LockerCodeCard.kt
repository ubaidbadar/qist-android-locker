package pk.qist.device.auth

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay

internal fun signinExpiry(value: String): Long = try {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        isLenient = false
        timeZone = TimeZone.getTimeZone("UTC")
    }.parse(value)?.time ?: 0
} catch (_: java.text.ParseException) {
    0
}

/** The OTP is visible only inside Locker; no notification, intent extra or saved-instance state contains it. */
@Composable
internal fun LockerCodeCard(code: String, expiresAt: String, onHide: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val hide by rememberUpdatedState(onHide)
    DisposableEffect(lifecycle, code) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) hide() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var feedback by remember(code) { mutableStateOf<String?>(null) }
    val cyan = Color(0xFF20C7DF)
    Surface(color = Color(0xFF0C1C41), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Your sign-in code", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Text("Type it into Qist Customer to sign in.", color = Color(0xFFAABBE0))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = if (maxWidth < 260.dp || LocalDensity.current.fontScale > 1.6f) 3 else 6
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    code.toList().chunked(columns).forEach { digits ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            digits.forEach { digit ->
                                Box(
                                    Modifier.weight(1f).heightIn(min = 62.dp)
                                        .border(1.dp, Color(0xFF3A5EBB), RoundedCornerShape(14.dp))
                                        .background(
                                            Brush.verticalGradient(listOf(Color(0xFF173877), Color(0xFF102454))),
                                            RoundedCornerShape(14.dp)
                                        )
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(digit.toString(), color = cyan, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
            CodeExpiry(expiresAt, onHide)
            run {
                val actions: @Composable ColumnScope.() -> Unit = {
                    Button(
                        onClick = {
                            try {
                                val clip = ClipData.newPlainText("Qist sign-in code", code)
                                clip.description.extras =
                                    PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                                feedback = "Code copied"
                            } catch (_: SecurityException) {
                                feedback = "Copy is unavailable. Type the code manually."
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(
                            min = 48.dp
                        ),
                        colors = ButtonDefaults.buttonColors(containerColor = cyan, contentColor = Color(0xFF0C1C41))
                    ) {
                        Text("Copy code")
                    }
                    OutlinedButton(onClick = {
                        val launch = context.packageManager.getLaunchIntentForPackage("pk.qist.customer")
                        try {
                            if (launch == null) {
                                feedback = "Qist Customer is not installed on this phone."
                            } else {
                                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        } catch (
                            _: SecurityException
                        ) {
                            feedback = "Open Qist Customer from your home screen."
                        } catch (
                            _: android.content.ActivityNotFoundException
                        ) {
                            feedback =
                                "Open Qist Customer from your home screen."
                        }
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Open Qist", color = Color.White)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = actions)
            }
            feedback?.let { Text(it, color = cyan) }
            Text(
                "Qist will never ask for this code by call, chat or through your retailer.",
                color = Color(0xFFAABBE0),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun CodeExpiry(expiresAt: String, onExpire: () -> Unit) {
    val expire by rememberUpdatedState(onExpire)
    // Translate server expiry once; changing the wall clock must never extend a visible code indefinitely.
    val until = remember(expiresAt) {
        SystemClock.elapsedRealtime() + (signinExpiry(expiresAt) - System.currentTimeMillis()).coerceIn(0, 180_000)
    }
    var remaining by remember(until) {
        mutableLongStateOf(((until - SystemClock.elapsedRealtime()) / 1000).coerceIn(0, 180))
    }
    LaunchedEffect(until) {
        while (remaining > 0) {
            delay(1000)
            remaining = ((until - SystemClock.elapsedRealtime()) / 1000).coerceIn(0, 180)
        }
        expire()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Expires in ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}", color = Color(0xFFAABBE0))
        LinearProgressIndicator(progress = {
            remaining / 180f
        }, modifier = Modifier.fillMaxWidth(), color = Color(0xFF20C7DF))
    }
}
