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
    fun `buildSignedQuery sorts params by ASCII key before signing`() {
        val query = BingXSigner.buildSignedQuery(
            mapOf("timestamp" to "123", "symbol" to "BTC-USDT"),
            "secret",
        )
        val beforeSignature = query.substringBefore("&signature=")
        assertEquals("symbol=BTC-USDT&timestamp=123", beforeSignature)
        assertTrue(query.contains("&signature="))
    }

    @Test
    fun `buildSignedQuery signature is deterministic for same input`() {
        val params = mapOf("a" to "1", "b" to "2")
        val q1 = BingXSigner.buildSignedQuery(params, "secret")
        val q2 = BingXSigner.buildSignedQuery(params, "secret")
        assertEquals(q1, q2)
    }
}
