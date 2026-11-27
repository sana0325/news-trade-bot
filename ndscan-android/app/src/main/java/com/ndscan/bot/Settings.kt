package com.ndscan.bot

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Змінні оточення ndscan, які можна міняти в застосунку (дефолти — як у скрипті). */
val ENV_FIELDS = listOf(
    "MIN_USD" to "30000",
    "MIN_MCAP" to "15000000",
    "TARGET_PCT" to "5",
    "STOP_PCT" to "3",
    "EVAL_H" to "24",
    "POLL_SEC" to "4",
)

const val DEFAULT_LADDER = "100, 140, 200, 280, 400, 560, 800, 1100"

val SIGNAL_TYPES = listOf("CEX_IN", "CEX_OUT", "FRESH_BUY", "WATCH_OUT")

/** Файли з assets/seed, які копіюються у filesDir при першому запуску (стан з ПК). */
private val SEED_IF_ABSENT = listOf("universe.json", "open_signals.json", "signals.csv")

object Settings {
    @Volatile private var secure: SharedPreferences? = null

    /** Ключі й налаштування — зашифровано; у filesDir пишуться лише перед стартом бота. */
    fun secure(ctx: Context): SharedPreferences = secure ?: synchronized(this) {
        secure ?: run {
            val key = MasterKey.Builder(ctx.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                ctx.applicationContext, "secure_settings", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ).also { secure = it }
        }
    }

    /** Робочий стан сервісу (не секретний): увімкнено, heartbeat. */
    fun runtime(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("runtime", Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context) = runtime(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, v: Boolean) = runtime(ctx).edit().putBoolean("enabled", v).apply()

    fun okxMode(ctx: Context): String {
        val p = secure(ctx)
        if (p.getString("bx_key", "").isNullOrBlank() || !p.getBoolean("bx_enabled", false)) return "вимкнено"
        return if (p.getBoolean("bx_demo", true)) "ДЕМО" else "РЕАЛ"
    }

    fun envJson(ctx: Context): String {
        val p = secure(ctx)
        val o = JSONObject()
        for ((k, def) in ENV_FIELDS) o.put(k, p.getString("env_$k", def)?.trim().takeUnless { it.isNullOrEmpty() } ?: def)
        return o.toString()
    }

    fun seed(ctx: Context) {
        val dir = ctx.filesDir
        for (name in SEED_IF_ABSENT) {
            val f = File(dir, name)
            if (!f.exists()) ctx.assets.open("seed/$name").use { i -> f.outputStream().use { i.copyTo(it) } }
        }
        // Список гаманців бірж — конфіг, а не вивчений стан: оновлюється разом із застосунком.
        ctx.assets.open("seed/cex_wallets.json").use { i -> File(dir, "cex_wallets.json").outputStream().use { i.copyTo(it) } }
    }

    /** tg.json і bingx_keys.json у filesDir — безпосередньо перед стартом Python. */
    fun writeBotFiles(ctx: Context) {
        val p = secure(ctx)
        val dir = ctx.filesDir
        val token = p.getString("tg_token", "")!!.trim()
        val chat = p.getString("tg_chat", "")!!.trim()
        val tg = File(dir, "tg.json")
        if (token.isNotEmpty() && chat.isNotEmpty()) tg.writeText(JSONObject().put("token", token).put("chat", chat).toString())
        else tg.delete()

        File(dir, "okx_keys.json").delete()   // від старої версії на OKX
        val okx = File(dir, "bingx_keys.json")
        val key = p.getString("bx_key", "")!!.trim()
        if (key.isEmpty()) { okx.delete(); return }
        val types = JSONArray()
        SIGNAL_TYPES.filter { p.getBoolean("bx_type_$it", true) }.forEach { types.put(it) }
        okx.writeText(JSONObject()
            .put("key", key)
            .put("secret", p.getString("bx_secret", "")!!.trim())
            .put("demo", p.getBoolean("bx_demo", true))
            .put("enabled", p.getBoolean("bx_enabled", false))
            .put("trade_usd", p.getString("bx_trade_usd", "50")!!.toDoubleOrNull() ?: 50.0)
            .put("lever", p.getString("bx_lever", "3")!!.toIntOrNull() ?: 3)
            .put("max_pos", p.getString("bx_max_pos", "3")!!.toIntOrNull() ?: 3)
            .put("max_spread_pct", p.getString("bx_max_spread", "1.0")!!.toDoubleOrNull() ?: 1.0)
            .put("types", types)
            .put("auto_ladder", p.getBoolean("bx_auto_ladder", true))
            .put("margin_pct", p.getString("bx_margin_pct", "0.45")!!.toDoubleOrNull() ?: 0.45)
            .put("ladder_steps", JSONArray().apply {
                val steps = p.getString("bx_ladder_steps", DEFAULT_LADDER)!!.split(',', ' ', ';').mapNotNull { it.trim().toDoubleOrNull() }
                (steps.ifEmpty { DEFAULT_LADDER.split(',').map { it.trim().toDouble() } }).forEach { put(it) }
            })
            .put("step_down_dd_pct", p.getString("bx_step_down_dd", "20")!!.toDoubleOrNull() ?: 20.0)
            .toString(1))
    }
}
