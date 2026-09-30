package com.zhuanz.autoleger.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.zhuanz.autoleger.LedgerAppProvider
import com.zhuanz.autoleger.data.AppContainer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

object Routes {
    const val HOME = "home"
    const val STATS = "stats"
    const val PENDING = "pending"
    const val SETTINGS = "settings"
    const val RULES = "rules"
    const val CATEGORIES = "categories"
    const val EDIT = "edit/{txId}/{pendingId}"
    fun edit(txId: Long, pendingId: Long) = "edit/$txId/$pendingId"
}
@Composable
fun rememberContainer(): AppContainer = (LocalContext.current.applicationContext as LedgerAppProvider).container

private data class NavItem(val title: String, val route: String, val icon: ImageVector)
private val navItems = listOf(
    NavItem("账单", Routes.HOME, Icons.AutoMirrored.Rounded.ReceiptLong),
    NavItem("统计", Routes.STATS, Icons.Rounded.BarChart),
    NavItem("待确认", Routes.PENDING, Icons.Rounded.Inbox),
    NavItem("设置", Routes.SETTINGS, Icons.Rounded.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoLedgerApp(deepLinkFlow: Flow<Long>) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val container = rememberContainer()
    val factory = remember(container) { viewModelFactory { initializer { HomeViewModel(container, createSavedStateHandle()) } } }
    // Activity scope: deletion feedback survives closing the editor.
    val home: HomeViewModel = viewModel(factory = factory)
    val pendingCount by container.pendingEntryDao.observeCount().collectAsStateWithLifecycle(initialValue = 0)
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(home) {
        home.undoEvents.collect { tx ->
            if (snackbar.showSnackbar("已删除 ${tx.merchant}", "撤销", withDismissAction = true,
                    duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) home.undo(tx)
        }
    }
    LaunchedEffect(home) { home.errors.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(deepLinkFlow) {
        // Wait until NavHost has installed its graph before consuming a cold-start intent.
        nav.currentBackStackEntryFlow.first()
        deepLinkFlow.collect { id -> nav.navigate(Routes.edit(-1, id)) { launchSingleTop = true } }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (route in navItems.map { it.route }) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                navItems.forEach { item ->
                    NavigationBarItem(
                        selected = route == item.route,
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary),
                        onClick = {
                            nav.navigate(item.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            BadgedBox(badge = {
                                if (item.route == Routes.PENDING && pendingCount > 0)
                                    Badge { Text(if (pendingCount > 99) "99+" else pendingCount.toString()) }
                            }) { Icon(item.icon, null) }
                        },
                        label = { Text(item.title) }, alwaysShowLabel = true,
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.HOME,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
            composable(Routes.HOME) { HomeScreen(home, { nav.navigate(Routes.edit(it, -1)) }, { nav.navigate(Routes.STATS) }) }
            composable(Routes.STATS) { StatsScreen(home) }
            composable(Routes.PENDING) { PendingScreen { nav.navigate(Routes.edit(-1, it)) } }
            composable(Routes.SETTINGS) { SettingsScreen(onRules = { nav.navigate(Routes.RULES) }, onCategories = { nav.navigate(Routes.CATEGORIES) }) }
            composable(Routes.RULES) { RulesScreen(onManageCategories = { nav.navigate(Routes.CATEGORIES) }, onBack = { nav.popBackStack() }) }
            composable(Routes.CATEGORIES) { CategoriesScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.EDIT, arguments = listOf(
                navArgument("txId") { type = NavType.LongType; defaultValue = -1L },
                navArgument("pendingId") { type = NavType.LongType; defaultValue = -1L },
            )) { entry ->
                EditEntryScreen(entry.arguments?.getLong("txId") ?: -1, entry.arguments?.getLong("pendingId") ?: -1,
                    onDone = { nav.popBackStack() }, onDelete = { tx -> home.delete(tx) { nav.popBackStack() } })
            }
        }
    }
}
