package com.scalpbot.bingx.data.local.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

enum class TradingMode { DEMO, LIVE }

enum class RiskPreset(val marginPercent: Float, val leverage: Int) {
    CONSERVATIVE(5f, 5),
    AGGRESSIVE(50f, 20),
    CUSTOM(5f, 5),
}

/**
 * Усі секрети (ключі BingX/DeepSeek) і торгові налаштування — в одному
 * EncryptedSharedPreferences-файлі (AES256-GCM через Jetpack Security).
 * Ключі НІКОЛИ не хардкодяться і вводяться лише в екрані Налаштувань.
 */
class SecureConfigStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "scalpbot_secure_config",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private object Keys {
        const val BINGX_DEMO_API_KEY = "bingx_demo_api_key"
        const val BINGX_DEMO_API_SECRET = "bingx_demo_api_secret"
        const val BINGX_LIVE_API_KEY = "bingx_live_api_key"
        const val BINGX_LIVE_API_SECRET = "bingx_live_api_secret"
        const val DEEPSEEK_API_KEY = "deepseek_api_key"
        const val TRADING_MODE = "trading_mode"
        const val RISK_PRESET = "risk_preset"
        const val MARGIN_PERCENT = "margin_percent"
        const val LEVERAGE = "leverage"
        const val MAX_HOLD_MINUTES = "max_hold_minutes"
        const val DAILY_LOSS_LIMIT_PERCENT = "daily_loss_limit_percent"
        const val MAX_TRADES_PER_DAY = "max_trades_per_day"
        const val SESSION_START_EQUITY_USD = "session_start_equity_usd"
        const val DAY_START_EQUITY_USD = "day_start_equity_usd"
        const val DAY_START_EPOCH_DAY = "day_start_epoch_day"
        const val BOT_ACTIVE = "bot_active"
        const val KILL_SWITCH_TRIGGERED = "kill_switch_triggered"
        const val DAILY_LIMIT_PAUSED_UNTIL_EPOCH_DAY = "daily_limit_paused_until_epoch_day"
        const val COOLDOWN_UNTIL_EPOCH_MS = "cooldown_until_epoch_ms"
    }

    var bingxDemoApiKey: String
        get() = prefs.getString(Keys.BINGX_DEMO_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(Keys.BINGX_DEMO_API_KEY, value).apply()

    var bingxDemoApiSecret: String
        get() = prefs.getString(Keys.BINGX_DEMO_API_SECRET, "") ?: ""
        set(value) = prefs.edit().putString(Keys.BINGX_DEMO_API_SECRET, value).apply()

    var bingxLiveApiKey: String
        get() = prefs.getString(Keys.BINGX_LIVE_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(Keys.BINGX_LIVE_API_KEY, value).apply()

    var bingxLiveApiSecret: String
        get() = prefs.getString(Keys.BINGX_LIVE_API_SECRET, "") ?: ""
        set(value) = prefs.edit().putString(Keys.BINGX_LIVE_API_SECRET, value).apply()

    var deepseekApiKey: String
        get() = prefs.getString(Keys.DEEPSEEK_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(Keys.DEEPSEEK_API_KEY, value).apply()

    var tradingMode: TradingMode
        get() = prefs.getString(Keys.TRADING_MODE, null)?.let {
            runCatching { TradingMode.valueOf(it) }.getOrNull()
        } ?: TradingMode.DEMO
        set(value) = prefs.edit().putString(Keys.TRADING_MODE, value.name).apply()

    var riskPreset: RiskPreset
        get() = prefs.getString(Keys.RISK_PRESET, null)?.let {
            runCatching { RiskPreset.valueOf(it) }.getOrNull()
        } ?: RiskPreset.CONSERVATIVE
        set(value) {
            prefs.edit().putString(Keys.RISK_PRESET, value.name).apply()
            if (value != RiskPreset.CUSTOM) {
                marginPercent = value.marginPercent
                leverage = value.leverage
            }
        }

    var marginPercent: Float
        get() = prefs.getFloat(Keys.MARGIN_PERCENT, RiskPreset.CONSERVATIVE.marginPercent)
        set(value) = prefs.edit().putFloat(Keys.MARGIN_PERCENT, value).apply()

    var leverage: Int
        get() = prefs.getInt(Keys.LEVERAGE, RiskPreset.CONSERVATIVE.leverage)
        set(value) = prefs.edit().putInt(Keys.LEVERAGE, value).apply()

    var maxHoldMinutes: Int
        get() = prefs.getInt(Keys.MAX_HOLD_MINUTES, 90)
        set(value) = prefs.edit().putInt(Keys.MAX_HOLD_MINUTES, value).apply()

    /** Додатне число, напр. 10f означає ліміт -10% на добу. */
    var dailyLossLimitPercent: Float
        get() = prefs.getFloat(Keys.DAILY_LOSS_LIMIT_PERCENT, 10f)
        set(value) = prefs.edit().putFloat(Keys.DAILY_LOSS_LIMIT_PERCENT, value).apply()

    var maxTradesPerDay: Int
        get() = prefs.getInt(Keys.MAX_TRADES_PER_DAY, 15)
        set(value) = prefs.edit().putInt(Keys.MAX_TRADES_PER_DAY, value).apply()

    /** Baseline для kill-switch (-30% від старту). null, поки бот жодного разу не запускався. */
    var sessionStartEquityUsd: Double?
        get() = prefs.getFloat(Keys.SESSION_START_EQUITY_USD, -1f).let { if (it < 0) null else it.toDouble() }
        set(value) = prefs.edit().putFloat(Keys.SESSION_START_EQUITY_USD, value?.toFloat() ?: -1f).apply()

    var dayStartEquityUsd: Double?
        get() = prefs.getFloat(Keys.DAY_START_EQUITY_USD, -1f).let { if (it < 0) null else it.toDouble() }
        set(value) = prefs.edit().putFloat(Keys.DAY_START_EQUITY_USD, value?.toFloat() ?: -1f).apply()

    /** Епоха-день (days since epoch, UTC) для якого зафіксовано dayStartEquityUsd. */
    var dayStartEpochDay: Long
        get() = prefs.getLong(Keys.DAY_START_EPOCH_DAY, -1L)
        set(value) = prefs.edit().putLong(Keys.DAY_START_EPOCH_DAY, value).apply()

    var botActive: Boolean
        get() = prefs.getBoolean(Keys.BOT_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(Keys.BOT_ACTIVE, value).apply()

    /** Хардкод-стан: якщо true, торгівля заблокована назавжди до ручного скидання в Налаштуваннях. */
    var killSwitchTriggered: Boolean
        get() = prefs.getBoolean(Keys.KILL_SWITCH_TRIGGERED, false)
        set(value) = prefs.edit().putBoolean(Keys.KILL_SWITCH_TRIGGERED, value).apply()

    var dailyLimitPausedUntilEpochDay: Long
        get() = prefs.getLong(Keys.DAILY_LIMIT_PAUSED_UNTIL_EPOCH_DAY, -1L)
        set(value) = prefs.edit().putLong(Keys.DAILY_LIMIT_PAUSED_UNTIL_EPOCH_DAY, value).apply()

    var cooldownUntilEpochMs: Long
        get() = prefs.getLong(Keys.COOLDOWN_UNTIL_EPOCH_MS, -1L)
        set(value) = prefs.edit().putLong(Keys.COOLDOWN_UNTIL_EPOCH_MS, value).apply()

    fun apiKeyFor(mode: TradingMode): Pair<String, String> = when (mode) {
        TradingMode.DEMO -> bingxDemoApiKey to bingxDemoApiSecret
        TradingMode.LIVE -> bingxLiveApiKey to bingxLiveApiSecret
    }

    fun hasBingxKeysFor(mode: TradingMode): Boolean {
        val (key, secret) = apiKeyFor(mode)
        return key.isNotBlank() && secret.isNotBlank()
    }

    fun hasDeepSeekKey(): Boolean = deepseekApiKey.isNotBlank()

    /** Емітить назву зміненого ключа — для реактивних екранів/двигуна. */
    fun observeChanges(): Flow<String> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null) trySend(key)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
