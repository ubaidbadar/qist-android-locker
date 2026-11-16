package pk.qist.device.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** A separate non-exportable signing key. Hardware backing depends on the phone. */
internal class SigninKey {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    private fun ensureKey() {
        if (keyStore.containsAlias(ALIAS)) return
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    fun publicKey(): String {
        ensureKey()
        return Base64.encodeToString(keyStore.getCertificate(ALIAS).publicKey.encoded, Base64.NO_WRAP)
    }

    fun keyId(): String = MessageDigest.getInstance("SHA-256").digest(publicKey().toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun sign(payload: String): String {
        ensureKey()
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(keyStore.getKey(ALIAS, null) as PrivateKey)
        signature.update(payload.toByteArray())
        return Base64.encodeToString(signature.sign(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private companion object {
        const val ALIAS = "qist_customer_signin_v1"
    }
}
