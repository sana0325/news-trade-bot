package com.scalpbot.bingx.domain.engine

import com.scalpbot.bingx.domain.model.TradeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionValidatorTest {

    // ATR=0.6%, спред=0.02% (типова ліквідна пара, вузький спред) — valid SL діапазон
    // [max(0.6, minStopLoss)..1.5], valid TP діапазон [max(1.2, minTakeProfit)..2.4].
    private val atrPercent = 0.6
    private val tightSpread = 0.02

    @Test
    fun `valid long decision within ATR-relative ranges is accepted`() {
        val raw = """
            {"action":"long","symbol":"BTC-USDT","sl_pct":0.9,"tp_pct":1.8,"confidence":0.8,"reason":"пробій опору"}
        """.trimIndent()
        val result = DecisionValidator.validate(raw, atrPercent, tightSpread) as DecisionValidationResult.Valid
        assertEquals(TradeAction.LONG, result.decision.action)
        assertEquals("BTC-USDT", result.decision.symbol)
        assertEquals(0.9, result.decision.slPct)
        assertEquals(1.8, result.decision.tpPct)
    }

    @Test
    fun `skip decision does not require symbol or sl-tp`() {
        val raw = """{"action":"skip","symbol":null,"sl_pct":null,"tp_pct":null,"confidence":0.2,"reason":"немає сигналу"}"""
        val result = DecisionValidator.validate(raw, atrPercent, tightSpread) as DecisionValidationResult.Valid
        assertEquals(TradeAction.SKIP, result.decision.action)
    }

    @Test
    fun `sl_pct outside ATR-relative range is invalid`() {
        // 1.0-2.5x ATR = 0.6-1.5; 0.2 is far below.
        val raw = """{"action":"long","symbol":"BTC-USDT","sl_pct":0.2,"tp_pct":1.8,"confidence":0.8,"reason":"x"}"""
        val result = DecisionValidator.validate(raw, atrPercent, tightSpread)
        assertTrue(result is DecisionValidationResult.Invalid)
    }

    @Test
    fun `tp_pct outside ATR-relative range is invalid`() {
        // 2-4x ATR = 1.2-2.4; 5.0 is far above.
        val raw = """{"action":"short","symbol":"ETH-USDT","sl_pct":0.9,"tp_pct":5.0,"confidence":0.8,"reason":"x"}"""
        val result = DecisionValidator.validate(raw, atrPercent, tightSpread)
        assertTrue(result is DecisionValidationResult.Invalid)
    }

    @Test
    fun `sl below hard floor is invalid even if within ATR range on a wide-spread pair`() {
        // Широкий спред 0.3% → minStopLossPercent = 4*(0.3+2*0.05) = 1.6, вище за
        // ATR-верхню межу (1.5) — валідного sl_pct не існує, будь-який sl_pct невалідний.
        val wideSpread = 0.3
        val raw = """{"action":"long","symbol":"BTC-USDT","sl_pct":0.9,"tp_pct":1.8,"confidence":0.8,"reason":"x"}"""
        val result = DecisionValidator.validate(raw, atrPercent, wideSpread)
        assertTrue(result is DecisionValidationResult.Invalid)
    }

    @Test
    fun `risk reward below minimum 1point5 is invalid even inside ATR ranges`() {
        // sl=1.4 (в межах 0.6-1.5), tp=1.3x sl=1.82 < 2x sl(1.2 lower bound) — щоб
        // лишитись у ATR-діапазоні TP, беремо tp трохи вище нижньої межі (1.3), тоді
        // RR=1.3/1.4 <1.5 і саме RR-перевірка мала б відхилити рішення.
        val raw = """{"action":"long","symbol":"BTC-USDT","sl_pct":1.0,"tp_pct":1.3,"confidence":0.8,"reason":"x"}"""
        val result = DecisionValidator.validate(raw, atrPercent, tightSpread)
        assertTrue(result is DecisionValidationResult.Invalid)
    }

    @Test
    fun `unknown action is invalid`() {
        val raw = """{"action":"buy","symbol":"BTC-USDT","sl_pct":0.9,"tp_pct":1.8,"confidence":0.8,"reason":"x"}"""
        val result = DecisionValidator.validate(raw, atrPercent, tightSpread)
        assertTrue(result is DecisionValidationResult.Invalid)
    }

    @Test
    fun `malformed json is invalid, not a crash`() {
        val result = DecisionValidator.validate("не json взагалі", atrPercent, tightSpread)
        assertTrue(result is DecisionValidationResult.Invalid)
    }

    @Test
    fun `missing symbol for long action is invalid`() {
        val raw = """{"action":"long","sl_pct":0.9,"tp_pct":1.8,"confidence":0.8,"reason":"x"}"""
        val result = DecisionValidator.validate(raw, atrPercent, tightSpread)
        assertTrue(result is DecisionValidationResult.Invalid)
    }

    @Test
    fun `confidence outside 0-1 is invalid`() {
        val raw = """{"action":"long","symbol":"BTC-USDT","sl_pct":0.9,"tp_pct":1.8,"confidence":1.5,"reason":"x"}"""
        val result = DecisionValidator.validate(raw, atrPercent, tightSpread)
        assertTrue(result is DecisionValidationResult.Invalid)
    }

    @Test
    fun `json wrapped in markdown code fence is still parsed`() {
        val raw = "```json\n{\"action\":\"skip\",\"confidence\":0.1,\"reason\":\"x\"}\n```"
        val result = DecisionValidator.validate(raw, atrPercent, tightSpread)
        assertTrue(result is DecisionValidationResult.Valid)
    }
}
