package com.scalpbot.bingx.domain.engine

import com.scalpbot.bingx.domain.model.TradeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionValidatorTest {

    @Test
    fun `valid long decision within ranges is accepted`() {
        val raw = """
            {"action":"long","symbol":"BTC-USDT","sl_pct":0.5,"tp_pct":1.0,"confidence":0.8,"reason":"пробій опору"}
        """.trimIndent()
        val result = DecisionValidator.validate(raw) as DecisionValidationResult.Valid
        assertEquals(TradeAction.LONG, result.decision.action)
        assertEquals("BTC-USDT", result.decision.symbol)
        assertEquals(0.5, result.decision.slPct)
        assertEquals(1.0, result.decision.tpPct)
    }

    @Test
    fun `skip decision does not require symbol or sl-tp`() {
        val raw = """{"action":"skip","symbol":null,"sl_pct":null,"tp_pct":null,"confidence":0.2,"reason":"немає сигналу"}"""
        val result = DecisionValidator.validate(raw) as DecisionValidationResult.Valid
        assertEquals(TradeAction.SKIP, result.decision.action)
    }

    @Test
    fun `sl_pct outside allowed range is invalid`() {
        val raw = """{"action":"long","symbol":"BTC-USDT","sl_pct":2.0,"tp_pct":1.0,"confidence":0.8,"reason":"x"}"""
        val result = DecisionValidator.validate(raw)
        assertTrue(result is DecisionValidationResult.Invalid)
    }

    @Test
    fun `tp_pct outside allowed range is invalid`() {
        val raw = """{"action":"short","symbol":"ETH-USDT","sl_pct":0.5,"tp_pct":5.0,"confidence":0.8,"reason":"x"}"""
        val result = DecisionValidator.validate(raw)
        assertTrue(result is DecisionValidationResult.Invalid)
    }

    @Test
    fun `unknown action is invalid`() {
        val raw = """{"action":"buy","symbol":"BTC-USDT","sl_pct":0.5,"tp_pct":1.0,"confidence":0.8,"reason":"x"}"""
        val result = DecisionValidator.validate(raw)
        assertTrue(result is DecisionValidationResult.Invalid)
    }

    @Test
    fun `malformed json is invalid, not a crash`() {
        val result = DecisionValidator.validate("не json взагалі")
        assertTrue(result is DecisionValidationResult.Invalid)
    }

    @Test
    fun `missing symbol for long action is invalid`() {
        val raw = """{"action":"long","sl_pct":0.5,"tp_pct":1.0,"confidence":0.8,"reason":"x"}"""
        val result = DecisionValidator.validate(raw)
        assertTrue(result is DecisionValidationResult.Invalid)
    }

    @Test
    fun `confidence outside 0-1 is invalid`() {
        val raw = """{"action":"long","symbol":"BTC-USDT","sl_pct":0.5,"tp_pct":1.0,"confidence":1.5,"reason":"x"}"""
        val result = DecisionValidator.validate(raw)
        assertTrue(result is DecisionValidationResult.Invalid)
    }

    @Test
    fun `json wrapped in markdown code fence is still parsed`() {
        val raw = "```json\n{\"action\":\"skip\",\"confidence\":0.1,\"reason\":\"x\"}\n```"
        val result = DecisionValidator.validate(raw)
        assertTrue(result is DecisionValidationResult.Valid)
    }
}
