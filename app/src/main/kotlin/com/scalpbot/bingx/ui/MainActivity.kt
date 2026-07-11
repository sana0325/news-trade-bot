package com.scalpbot.bingx.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.scalpbot.bingx.service.BatteryOptimizationHelper
import com.scalpbot.bingx.ui.navigation.ScalpNavHost
import com.scalpbot.bingx.ui.theme.ScalpBotTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScalpBotTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ScalpNavHost()
                }
                FirstRunPrompts()
            }
        }
    }
}

/**
 * Запит дозволу на сповіщення (Android 13+) і винятку з оптимізації батареї —
 * без останнього Doze рано чи пізно приспить фоновий процес попри Foreground Service.
 */
@Composable
private fun FirstRunPrompts() {
    val context = LocalContext.current
    var showBatteryDialog by remember {
        mutableStateOf(!BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context))
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {},
    )

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val batteryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { showBatteryDialog = !BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context) },
    )

    if (showBatteryDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryDialog = false },
            title = { Text("Дозволити роботу у фоні") },
            text = {
                Text(
                    "ScalpBot торгує безперервно, навіть коли екран заблокований або додаток " +
                        "закритий. Щоб Android не приспав торговий процес, потрібен виняток з " +
                        "оптимізації батареї — інакше бот може пропускати сигнали.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBatteryDialog = false
                    batteryLauncher.launch(BatteryOptimizationHelper.buildRequestIntent(context))
                }) { Text("Дозволити") }
            },
            dismissButton = {
                TextButton(onClick = { showBatteryDialog = false }) { Text("Пізніше") }
            },
        )
    }
}
