package pk.qist.device.auth

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SigninProtocolTest {
    @Test
    fun `Java verifies the backend canonical signature and rejects altered fields`() {
        val json = requireNotNull(javaClass.getResource("/trusted-signin.json")).readText()
        val fixture = Json.parseToJsonElement(json).jsonObject
        fun field(name: String) = fixture.getValue(name).jsonPrimitive.content
        val request =
            SigninRequest(field("requestId"), field("kind"), field("challenge"), field("nonceHash"), field("expiresAt"))
        val payload = request.payload(field("deviceId"), field("decision"), field("number"))
        assertEquals(field("payload"), payload)
        val publicKey = KeyFactory.getInstance(
            "EC"
        ).generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(field("publicKey"))))
        fun valid(body: String) = Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(body.toByteArray(Charsets.UTF_8))
            verify(Base64.getUrlDecoder().decode(field("signature")))
        }
        assertTrue(valid(payload))
        assertFalse(valid(request.copy(kind = "local").payload(field("deviceId"), "approve", "074")))
        assertFalse(valid(request.payload(field("deviceId"), "deny", "074")))
        assertFalse(valid(request.payload(field("deviceId"), "approve", "075")))
    }
}
