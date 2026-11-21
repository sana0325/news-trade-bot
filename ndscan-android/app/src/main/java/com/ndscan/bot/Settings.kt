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
        if (p.getString("okx_key", "").isNullOrBlank() || !p.getBoolean("okx_enabled", false)) return "вимкнено"
        return if (p.getBoolean("okx_demo", true)) "ДЕМО" else "РЕАЛ"
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

    /** tg.json і okx_keys.json у filesDir — безпосередньо перед стартом Python. */
    fun writeBotFiles(ctx: Context) {
        val p = secure(ctx)
        val dir = ctx.filesDir
        val token = p.getString("tg_token", "")!!.trim()
        val chat = p.getString("tg_chat", "")!!.trim()
        val tg = File(dir, "tg.json")
        if (token.isNotEmpty() && chat.isNotEmpty()) tg.writeText(JSONObject().put("token", token).put("chat", chat).toString())
        else tg.delete()

        val okx = File(dir, "okx_keys.json")
        val key = p.getString("okx_key", "")!!.trim()
        if (key.isEmpty()) { okx.delete(); return }
        val types = JSONArray()
        SIGNAL_TYPES.filter { p.getBoolean("okx_type_$it", true) }.forEach { types.put(it) }
        okx.writeText(JSONObject()
            .put("key", key)
            .put("secret", p.getString("okx_secret", "")!!.trim())
            .put("passphrase", p.getString("okx_pass", "")!!.trim())
            .put("demo", p.getBoolean("okx_demo", true))
            .put("enabled", p.getBoolean("okx_enabled", false))
            .put("trade_usd", p.getString("okx_trade_usd", "50")!!.toDoubleOrNull() ?: 50.0)
            .put("lever", p.getString("okx_lever", "3")!!.toIntOrNull() ?: 3)
            .put("max_pos", p.getString("okx_max_pos", "3")!!.toIntOrNull() ?: 3)
            .put("max_spread_pct", p.getString("okx_max_spread", "1.0")!!.toDoubleOrNull() ?: 1.0)
            .put("types", types)
            .toString(1))
    }
}
