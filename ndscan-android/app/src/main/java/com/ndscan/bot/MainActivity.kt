package com.ndscan.bot

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as SysSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private val Green = Color(0xFF2ECC71)
private val Red = Color(0xFFE74C3C)
private val Amber = Color(0xFFF5B301)

class MainActivity : ComponentActivity() {

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        firstRun()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Green, background = Color(0xFF0E1116), surface = Color(0xFF161B22))) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AppScreen() }
            }
        }
    }

    /** Перший запуск: дозвіл на сповіщення і виняток з оптимізації батареї. */
    @SuppressLint("BatteryLife")
    private fun firstRun() {
        val rt = Settings.runtime(this)
        if (rt.getBoolean("first_run_done", false)) return
        rt.edit().putBoolean("first_run_done", true).apply()
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (!isIgnoringBattery(this)) {
            runCatching {
                startActivity(Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        }
    }
}

fun isIgnoringBattery(ctx: android.content.Context) =
    ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

private val TABS = listOf("Головна", "Лог", "Сигнали", "Звіт", "Налаштування", "Телефон")

@Composable
fun AppScreen() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            TABS.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen()
                1 -> LogScreen()
                2 -> SignalsScreen()
                3 -> ReportScreen()
                4 -> SettingsScreen()
                5 -> PhoneScreen()
            }
        }
    }
}

// ------------------------- Головна -------------------------

@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    val s by BotState.status.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var enabled by remember { mutableStateOf(Settings.isEnabled(ctx)) }
    var battery by remember { mutableStateOf(isIgnoringBattery(ctx)) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); enabled = Settings.isEnabled(ctx); battery = isIgnoringBattery(ctx); delay(1000) }
    }
    val active = s.state != "stopped"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = {
                if (enabled) BotService.stop(ctx) else BotService.start(ctx)
                enabled = !enabled
            },
            modifier = Modifier.fillMaxWidth().height(96.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (enabled) Red else Green),
        ) { Text(if (enabled) "СТОП" else "СТАРТ", fontSize = 32.sp, fontWeight = FontWeight.Bold) }

        val color = when (s.state) { "running" -> Green; "stopped" -> Color.Gray; else -> Amber }
        Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stateLabel(s.state).replaceFirstChar { it.uppercase() }, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Row2("Аптайм", if (active && s.started > 0) duration(now - s.started) else "—")
            Row2("Останній блок", s.block?.toString() ?: "—")
            Row2("Відставання", s.lag?.let { "$it блоків" } ?: "—")
            Row2("Останній цикл", if (s.loopTs > 0) "${(now - s.loopTs) / 1000} с тому" else "—")
            Row2("У слежці", s.watch.toString())
            Row2("Депозитів", s.deposits.toString())
            Row2("Сигналів за 6г", s.signals6h.toString())
            Row2("BingX", Settings.okxMode(ctx) + if (s.trades > 0) " · угод ${s.trades}" else "")
        } }

        Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row2("Виняток з оптимізації батареї", if (battery) "✅ надано" else "❌ ні")
            if (!battery) Text("Без винятку Android може приспати бота. Вкладка «Телефон» → «Батарея».",
                color = Amber, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        } }
    }
}

@Composable
private fun Row2(k: String, v: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, Modifier.weight(1f), color = Color(0xFF9AA4B2))
        Text(v, fontWeight = FontWeight.Medium)
    }
}

private fun duration(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60 * 24) "${m / 1440} д ${m % 1440 / 60} г" else "${m / 60} г ${m % 60} хв"
}

// ------------------------- Лог -------------------------

@Composable
fun LogScreen() {
    val ctx = LocalContext.current
    val lines by BotState.log.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.size - 1) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Останні ${lines.size} рядків", color = Color.Gray, fontSize = 13.sp)
            Button(onClick = { shareLog(ctx) }) { Text("Поділитися логом") }
        }
        if (lines.isEmpty()) Text("Лог з’явиться після старту бота. Повний лог — кнопка «Поділитися».",
            Modifier.padding(16.dp), color = Color.Gray)
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp) }
        }
    }
}

private fun shareLog(ctx: android.content.Context) {
    val dir = ctx.filesDir
    val parts = listOf("log.txt.2", "log.txt.1", "log.txt").map { File(dir, it) }.filter { it.exists() }
    if (parts.isEmpty()) return
    val out = File(ctx.cacheDir, "ndscan-log.txt").also { f -> f.writeText(""); parts.forEach { f.appendBytes(it.readBytes()) } }
    val shareDir = File(dir, "share").apply { mkdirs() }
    val f = File(shareDir, "ndscan-log.txt"); out.copyTo(f, overwrite = true)
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, "Поділитися логом"))
}

// ------------------------- Сигнали -------------------------

data class SignalRow(val id: String, val time: String, val type: String, val symbol: String, val dir: String,
                     val usd: String, val outcome: String, val result: String)

fun parseCsvLine(line: String): List<String> {
    val out = ArrayList<String>(); val sb = StringBuilder(); var q = false; var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            q && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
            c == '"' -> q = !q
            c == ',' && !q -> { out.add(sb.toString()); sb.clear() }
            else -> sb.append(c)
        }
        i++
    }
    out.add(sb.toString()); return out
}

fun readSignals(file: File): List<SignalRow> {
    if (!file.exists()) return emptyList()
    val lines = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
    if (lines.isEmpty()) return emptyList()
    val h = parseCsvLine(lines[0].removePrefix("﻿")).withIndex().associate { it.value to it.index }
    val byId = LinkedHashMap<String, SignalRow>()
    for (l in lines.drop(1)) {
        val c = parseCsvLine(l)
        fun g(k: String) = h[k]?.let { c.getOrNull(it) } ?: ""
        val id = g("id")
        val r = SignalRow(id, g("time_utc"), g("type"), g("symbol"), g("dir"), g("usd"), g("outcome"), g("result_pct"))
        val prev = byId[id]
        // Рядок результату дописується пізніше — він важливіший за початковий.
        if (prev == null || r.outcome.isNotEmpty()) byId[id] = r
    }
    return byId.values.sortedByDescending { it.time }
}

@Composable
fun SignalsScreen() {
    val ctx = LocalContext.current
    var rows by remember { mutableStateOf<List<SignalRow>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) { rows = withContext(Dispatchers.IO) { readSignals(File(ctx.filesDir, "signals.csv")) }; delay(30_000) }
    }
    val hs = rememberScrollState()
    Column(Modifier.fillMaxSize().horizontalScroll(hs)) {
        SignalLine("Час (UTC)", "Тип", "Монета", "Напрям", "$", "Результат", header = true)
        LazyColumn(Modifier.fillMaxHeight()) {
            items(rows, key = { it.id }) { r ->
                val res = if (r.outcome.isEmpty()) "відкрито" else "${r.outcome} ${r.result}%"
                SignalLine(r.time.drop(5).dropLast(3), r.type, r.symbol, r.dir, r.usd, res,
                    resColor = when (r.outcome) { "TARGET" -> Green; "STOP" -> Red; "" -> Color.Gray; else -> Amber })
            }
        }
    }
}

@Composable
private fun SignalLine(t: String, type: String, sym: String, dir: String, usd: String, res: String,
                       header: Boolean = false, resColor: Color = Color.Unspecified) {
    val w = if (header) FontWeight.Bold else FontWeight.Normal
    val dirColor = when (dir) { "LONG" -> Green; "SHORT" -> Red; else -> Color.Unspecified }
    Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
        Text(t, Modifier.width(96.dp), fontSize = 12.sp, fontWeight = w)
        Text(type, Modifier.width(92.dp), fontSize = 12.sp, fontWeight = w)
        Text(sym, Modifier.width(84.dp), fontSize = 12.sp, fontWeight = w)
        Text(dir, Modifier.width(56.dp), fontSize = 12.sp, fontWeight = w, color = if (header) Color.Unspecified else dirColor)
        Text(usd, Modifier.width(72.dp), fontSize = 12.sp, fontWeight = w)
        Text(res, Modifier.width(110.dp), fontSize = 12.sp, fontWeight = w, color = if (header) Color.Unspecified else resColor)
    }
}

// ------------------------- Звіт -------------------------

@Composable
fun ReportScreen() {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        busy = true
        text = withContext(Dispatchers.IO) {
            runCatching {
                Settings.seed(ctx)
                Python.getInstance().getModule("runner")
                    .callAttr("report", ctx.filesDir.absolutePath, Settings.envJson(ctx)).toString()
            }.getOrElse { "Помилка: $it" }
        }
        busy = false
    }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Button(onClick = { refresh++ }, enabled = !busy) { Text(if (busy) "Рахую…" else "Оновити звіт") }
        Text(text, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(top = 8.dp),
            fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

// ------------------------- Налаштування -------------------------

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val p = remember { Settings.secure(ctx) }
    var saved by remember { mutableStateOf(false) }
    val fields = remember { mutableStateMapOf<String, String>() }
    val flags = remember { mutableStateMapOf<String, Boolean>() }
    fun str(k: String, def: String = "") = fields.getOrPut(k) { p.getString(k, def) ?: def }
    fun flag(k: String, def: Boolean) = flags.getOrPut(k) { p.getBoolean(k, def) }
    var confirmReal by remember { mutableStateOf(false) }

    @Composable
    fun Field(k: String, label: String, def: String = "", secret: Boolean = false, number: Boolean = false) {
        OutlinedTextField(
            value = str(k, def), onValueChange = { fields[k] = it; saved = false }, label = { Text(label) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text),
        )
    }

    @Composable
    fun ToggleRow(k: String, label: String, def: Boolean, onChange: (Boolean) -> Unit = { flags[k] = it; saved = false }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f)); Switch(checked = flag(k, def), onCheckedChange = onChange)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("Telegram")
        Field("tg_token", "Токен бота", secret = true)
        Field("tg_chat", "Chat ID")

        Section("BingX")
        Field("bx_key", "API key", secret = true)
        Field("bx_secret", "Secret", secret = true)
        ToggleRow("bx_enabled", "Автоторгівля увімкнена", false)
        ToggleRow("bx_demo", "Демо-рахунок (VST)", true) { demo ->
            if (demo) { flags["bx_demo"] = true; saved = false } else confirmReal = true
        }
        if (!flag("bx_demo", true)) Text("⚠️ РЕАЛ: угоди відкриватимуться на справжні гроші", color = Red, fontWeight = FontWeight.Bold)
        ToggleRow("bx_auto_ladder", "Розгін «драбинкою» (маржа від балансу)", true)
        if (flag("bx_auto_ladder", true)) {
            Field("bx_margin_pct", "Частка бази на угоду (0.45 = 45%)", "0.45", number = true)
            Field("bx_ladder_steps", "Сходинки балансу, $", DEFAULT_LADDER)
            Field("bx_step_down_dd", "Відкат при просадці, %", "20", number = true)
            Text("Маржа = сходинка × частка, обсяг = маржа × плече. Якщо баланс недоступний — розмір угоди нижче.",
                color = Color.Gray, fontSize = 12.sp)
        }
        Field("bx_trade_usd", if (flag("bx_auto_ladder", true)) "Розмір угоди, $ (запасний)" else "Розмір угоди, $", "50", number = true)
        Field("bx_lever", "Плече", "3", number = true)
        Field("bx_max_pos", "Макс. одночасних позицій", "3", number = true)
        Field("bx_max_spread", "Макс. спред, %", "1.0", number = true)
        Text("Які сигнали торгувати:")
        SIGNAL_TYPES.forEach { ToggleRow("bx_type_$it", it, true) }

        Section("Сканер (змінні оточення ndscan)")
        ENV_FIELDS.forEach { (k, def) -> Field("env_$k", k, def, number = true) }

        Button(onClick = {
            p.edit().apply {
                fields.forEach { (k, v) -> putString(k, v.trim()) }
                flags.forEach { (k, v) -> putBoolean(k, v) }
            }.apply()
            saved = true
        }, modifier = Modifier.fillMaxWidth()) { Text("Зберегти") }
        if (saved) Text("Збережено. Діє після перезапуску бота (Стоп → Старт).", color = Green)
        Text("Ключі зберігаються зашифровано на телефоні; у файли бота записуються лише при старті.",
            color = Color.Gray, fontSize = 12.sp)
        Spacer(Modifier.height(24.dp))
    }

    if (confirmReal) AlertDialog(
        onDismissRequest = { confirmReal = false },
        title = { Text("Перейти на РЕАЛ-рахунок?") },
        text = { Text("Бот відкриватиме угоди на справжні гроші на BingX. Ви впевнені?") },
        confirmButton = { TextButton(onClick = { flags["bx_demo"] = false; saved = false; confirmReal = false }) { Text("Так, РЕАЛ", color = Red) } },
        dismissButton = { TextButton(onClick = { confirmReal = false }) { Text("Скасувати") } },
    )
}

@Composable
private fun Section(t: String) = Text(t, Modifier.padding(top = 12.dp), color = Green, fontWeight = FontWeight.Bold, fontSize = 18.sp)

// ------------------------- Налаштування телефону -------------------------

@Composable
fun PhoneScreen() {
    val ctx = LocalContext.current
    var battery by remember { mutableStateOf(isIgnoringBattery(ctx)) }
    LaunchedEffect(Unit) { while (true) { battery = isIgnoringBattery(ctx); delay(1000) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Щоб бот працював 24/7, телефон не повинен його присипляти.", fontWeight = FontWeight.Bold)

        Section("1. Батарея")
        Text(if (battery) "✅ Виняток з оптимізації батареї надано" else "❌ Виняток не надано", color = if (battery) Green else Red)
        Text("Дозвольте застосунку працювати у фоні без обмежень («Не оптимізувати» / «Без обмежень»).", fontSize = 13.sp)
        Button(onClick = { PhoneSettings.battery(ctx) }, Modifier.fillMaxWidth()) { Text("Відкрити налаштування батареї") }

        Section("2. Автозапуск")
        Text("На Xiaomi, Huawei, Oppo, Vivo, Samsung є власні «вбивці» фонових застосунків. " +
            "Увімкніть для ndscan «Автозапуск» і вимкніть «Економію енергії» / «Сон для невикористовуваних застосунків».", fontSize = 13.sp)
        Button(onClick = { PhoneSettings.autostart(ctx) }, Modifier.fillMaxWidth()) { Text("Відкрити автозапуск (${Build.MANUFACTURER})") }
        Button(onClick = { PhoneSettings.appDetails(ctx) }, Modifier.fillMaxWidth()) { Text("Сторінка застосунку в налаштуваннях") }

        Section("3. Закріпити в недавніх")
        Text("Відкрийте список недавніх застосунків, натисніть і утримуйте картку ndscan (або значок над нею) " +
            "і виберіть «Закріпити» / «Заблокувати» (значок замка). На Xiaomi — потягніть картку вниз.", fontSize = 13.sp)

        Section("Що ще")
        Text("• Xiaomi/MIUI: Безпека → Батарея → ndscan → «Без обмежень»; Дозволи → Автозапуск → увімкнути.\n" +
            "• Samsung: Налаштування → Акумулятор → Обмеження фонового використання → прибрати ndscan зі «Сплячих»; " +
            "додати до «Ніколи не присипляти».\n" +
            "• Huawei: Акумулятор → Запуск застосунків → ndscan → керувати вручну, увімкнути всі три перемикачі.\n" +
            "• Oppo/Realme/OnePlus: Акумулятор → ndscan → «Дозволити фонову активність», «Автозапуск».\n" +
            "• Не вимикайте сповіщення ndscan — без нього Android зупинить сервіс.", fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))
    }
}

object PhoneSettings {
    @SuppressLint("BatteryLife")
    fun battery(ctx: android.content.Context) {
        val tries = listOf(
            Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
            Intent(SysSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )
        open(ctx, tries)
    }

    fun appDetails(ctx: android.content.Context) =
        open(ctx, listOf(Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))))

    fun autostart(ctx: android.content.Context) {
        fun c(pkg: String, cls: String) = Intent().setClassName(pkg, cls)
        val tries = listOf(
            c("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            c("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            c("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            c("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            c("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            c("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            c("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            c("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            c("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
        )
        open(ctx, tries)
    }

    private fun open(ctx: android.content.Context, tries: List<Intent>) {
        for (i in tries) {
            if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }
}
