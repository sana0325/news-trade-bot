package com.scalpbot.bingx.data.remote.bingx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BingXSignerTest {

    @Test
    fun `sign matches known HMAC-SHA256 vector`() {
        val signature = BingXSigner.sign("key", "The quick brown fox jumps over the lazy dog")
        assertEquals(
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            signature,
        )
    }

    @Test
    fun `buildSignedQuery sorts params by ASCII key and encodes the transmitted value`() {
        val query = BingXSigner.buildSignedQuery(
            mapOf("timestamp" to "123", "symbol" to "BTC-USDT"),
            "secret",
        ) { it.replace(" ", "%20") }
        val beforeSignature = query.substringBefore("&signature=")
        assertEquals("symbol=BTC-USDT&timestamp=123", beforeSignature)
        assertTrue(query.contains("&signature="))
    }

    @Test
    fun `buildSignedQuery signature is deterministic for same input`() {
        val params = mapOf("a" to "1", "b" to "2")
        val q1 = BingXSigner.buildSignedQuery(params, "secret") { it }
        val q2 = BingXSigner.buildSignedQuery(params, "secret") { it }
        assertEquals(q1, q2)
    }

    @Test
    fun `signature is computed over raw values, not the encoded ones sent on the wire`() {
        // Regression test for a real bug: signing the already-encoded JSON body of
        // stopLoss/takeProfit params made BingX reject every order with "Signature
        // verification failed" - the server recomputes the signature over the raw,
        // decoded values, so that's what we must sign too.
        val params = mapOf("symbol" to "BTC-USDT", "stopLoss" to """{"type":"STOP_MARKET"}""")
        val encode = { value: String -> value.replace("{", "%7B").replace("}", "%7D").replace("\"", "%22") }

        val query = BingXSigner.buildSignedQuery(params, "secret", encode)
        val signature = query.substringAfter("&signature=")

        val rawCanonicalString = "stopLoss={\"type\":\"STOP_MARKET\"}&symbol=BTC-USDT"
        val expectedSignature = BingXSigner.sign("secret", rawCanonicalString)

        assertEquals(expectedSignature, signature)
        // і водночас у фактичний запит значення йде вже закодованим
        assertTrue(query.contains("stopLoss=%7B%22type%22:%22STOP_MARKET%22%7D"))
    }
}
