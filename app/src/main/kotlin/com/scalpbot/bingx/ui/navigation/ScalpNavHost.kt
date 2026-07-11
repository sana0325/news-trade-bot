package com.scalpbot.bingx.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.scalpbot.bingx.ui.home.HomeScreen
import com.scalpbot.bingx.ui.journal.JournalScreen
import com.scalpbot.bingx.ui.market.MarketDetailScreen
import com.scalpbot.bingx.ui.market.MarketScreen
import com.scalpbot.bingx.ui.settings.LogViewerScreen
import com.scalpbot.bingx.ui.settings.SettingsScreen
import com.scalpbot.bingx.ui.stats.StatisticsScreen

@Composable
fun ScalpNavHost(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                ScalpDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = ScalpDestination.Home.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(ScalpDestination.Home.route) { HomeScreen() }
            composable(ScalpDestination.Market.route) {
                MarketScreen(onPairClick = { symbol -> navController.navigate(ScalpRoutes.marketDetail(symbol)) })
            }
            composable(
                route = ScalpRoutes.MARKET_DETAIL,
                arguments = listOf(navArgument("symbol") { type = NavType.StringType }),
            ) { entry ->
                val symbol = entry.arguments?.getString("symbol").orEmpty()
                MarketDetailScreen(symbol = symbol, onBack = { navController.popBackStack() })
            }
            composable(ScalpDestination.Journal.route) { JournalScreen() }
            composable(ScalpDestination.Statistics.route) { StatisticsScreen() }
            composable(ScalpDestination.Settings.route) {
                SettingsScreen(onOpenLogs = { navController.navigate(ScalpRoutes.LOG_VIEWER) })
            }
            composable(ScalpRoutes.LOG_VIEWER) { LogViewerScreen(onBack = { navController.popBackStack() }) }
        }
    }
}
