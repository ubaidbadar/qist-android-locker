package pk.qist.device.auth

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.datastore.core.DataStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import pk.qist.device.R
import pk.qist.device.data.DeviceState
import pk.qist.device.data.Enrolment

internal data class SigninRequest(
    val requestId: String,
    val kind: String,
    val challenge: String,
    val nonceHash: String,
    val expiresAt: String
) {
    fun payload(deviceId: String, decision: String, number: String) = listOf(
        "qist-customer-signin-v1", kind, requestId, deviceId, challenge, nonceHash, expiresAt, decision, number
    ).joinToString("\n")
}

/** Authenticated API calls and signing are owned here, never by the UI or the caller app. */
internal class CustomerSignin(
    private val context: Context,
    private val store: DataStore<DeviceState>,
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val json: Json
) {
    private val key by lazy { SigninKey() }
    private val mutex = Mutex()
    private val registrationMutex = Mutex()
    private var registeredEnrollment: Enrolment.Enrolled? = null
    private val mutable = MutableStateFlow<List<SigninRequest>>(emptyList())
    val requests = mutable.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val unavailable = mutableError.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val enrolled = store.data.first().enrolment as? Enrolment.Enrolled
                if (enrolled == null) {
                    mutable.value = emptyList()
                    mutableError.value = "This phone is no longer enrolled for sign-in."
                    notifications().cancel(NOTIFICATION_ID)
                    return@withLock
                }
                register(enrolled)
                val body = api(enrolled, "pending")
                val pending = body.getValue("requests").jsonArray.map { parse(it.jsonObject) }
                val changed = pending.map { it.requestId } != mutable.value.map { it.requestId }
                mutable.value = pending
                mutableError.value = null
                if (pending.isEmpty()) {
                    notifications().cancel(NOTIFICATION_ID)
                } else if (changed) {
                    notifyPending()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // This optional capability must never fail or retry a completed finance check-in.
                mutableError.value = "Could not refresh sign-in requests. Check connectivity and try again."
            }
        }
    }

    suspend fun local(requestId: String, nonce: String) = withContext(Dispatchers.IO) {
        require(Regex("[a-f0-9]{24}").matches(requestId) && Regex("[A-Za-z0-9_-]{43}").matches(nonce))
        requireUnlocked()
        val enrolled = enrolled()
        register(enrolled)
        val request =
            parse(
                api(
                    enrolled,
                    "local",
                    buildJsonObject {
                        put("requestId", requestId)
                        put("nonce", nonce)
                    }
                )
            )
        require(request.kind == "local" && request.requestId == requestId)
        requireUnlocked()
        approve(enrolled, request, "approve", "")
    }

    suspend fun decide(request: SigninRequest, approve: Boolean, number: String) = withContext(Dispatchers.IO) {
        require(request.kind == "remote")
        requireUnlocked()
        val enrolled = enrolled()
        approve(enrolled, request, if (approve) "approve" else "deny", if (approve) number else "")
        mutable.value = mutable.value.filterNot { it.requestId == request.requestId }
        if (mutable.value.isEmpty()) notifications().cancel(NOTIFICATION_ID)
    }

    private fun requireUnlocked() {
        check(!context.getSystemService(KeyguardManager::class.java).isDeviceLocked) {
            "Unlock this phone to continue."
        }
    }

    private suspend fun enrolled(): Enrolment.Enrolled = store.data.first().enrolment as? Enrolment.Enrolled
        ?: throw IOException("This phone is not enrolled for sign-in.")

    private suspend fun register(enrolled: Enrolment.Enrolled) = registrationMutex.withLock {
        if (registeredEnrollment == enrolled) return@withLock
        val keyId = key.keyId()
        api(
            enrolled,
            "key",
            buildJsonObject {
                put("publicKey", key.publicKey())
                put("signature", key.sign("qist-customer-key-v1\n${enrolled.deviceId}\n$keyId"))
            }
        )
        registeredEnrollment = enrolled
    }

    private suspend fun approve(
        enrolled: Enrolment.Enrolled,
        request: SigninRequest,
        decision: String,
        number: String
    ) {
        // Enrollment must still be the one that initiated this operation after network/IPC suspension.
        check(store.data.first().enrolment == enrolled)
        api(
            enrolled,
            "decide",
            buildJsonObject {
                put("requestId", request.requestId)
                put("decision", decision)
                if (number.isNotEmpty()) put("number", number)
                put("signature", key.sign(request.payload(enrolled.deviceId, decision, number)))
            }
        )
    }

    private fun api(enrolled: Enrolment.Enrolled, path: String, body: JsonObject? = null): JsonObject {
        val request = Request.Builder().url(baseUrl.newBuilder().addPathSegments("device/customer-auth/$path").build())
            .header("Authorization", "Bearer ${enrolled.credential}")
        if (body != null) request.post(body.toString().toRequestBody("application/json".toMediaType()))
        client.newCall(request.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    if (response.code ==
                        409
                    ) {
                        "This phone's sign-in key needs account recovery."
                    } else {
                        "Could not verify sign-in. Check the number or try recovery."
                    }
                )
            }
            return json.parseToJsonElement(response.body.string()).jsonObject.getValue("data").jsonObject
        }
    }

    private fun parse(body: JsonObject) = SigninRequest(
        text(body, "requestId"),
        text(body, "kind"),
        text(body, "challenge"),
        text(body, "nonceHash"),
        text(body, "expiresAt")
    )

    private fun text(body: JsonObject, name: String) = (body[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf { it.isNotBlank() } ?: throw IOException("Invalid sign-in response.")

    private fun notifications() = context.getSystemService(NotificationManager::class.java)

    private fun notifyPending() {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = notifications()
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >=
            26
        ) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Qist sign-in requests", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val intent = Intent(context, SigninActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = androidx.core.app.NotificationCompat.Builder(context, CHANNEL)
        manager.notify(
            NOTIFICATION_ID,
            builder.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(
                    "Qist sign-in request"
                ).setContentText("Open Qist sign-in to review. Approve only requests you started.")
                .setVisibility(
                    androidx.core.app.NotificationCompat.VISIBILITY_PRIVATE
                ).setContentIntent(pending).setAutoCancel(true).build()
        )
    }

    private companion object {
        const val CHANNEL = "qist-customer-signin"
        const val NOTIFICATION_ID = 725
    }
}
