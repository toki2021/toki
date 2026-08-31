package com.zhuanz.autoleger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.outlined.Email as EmailOutline
import androidx.compose.material.icons.outlined.Home as HomeOutline
import androidx.compose.material.icons.automirrored.outlined.List as ListOutline
import androidx.compose.material.icons.outlined.Settings as SettingsOutline
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhuanz.autoleger.LedgerAppProvider
import com.zhuanz.autoleger.data.AppContainer
import kotlinx.coroutines.flow.Flow

object Routes {
    const val HOME = "home"
    const val PENDING = "pending"
    const val RULES = "rules"
    const val CATEGORIES = "categories"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val EDIT = "edit/{txId}/{pendingId}"
    fun edit(txId: Long, pendingId: Long) = "edit/$txId/$pendingId"
}

private data class NavItem(
    val label: String,
    val icon: ImageVector,
    val outlined: ImageVector,
    val route: String,
)

private val navItems = listOf(
    NavItem("首页", Icons.Filled.Home, Icons.Outlined.HomeOutline, Routes.HOME),
    NavItem("待处理", Icons.Filled.Email, Icons.Outlined.EmailOutline, Routes.PENDING),
    NavItem("规则", Icons.AutoMirrored.Filled.List, Icons.AutoMirrored.Outlined.ListOutline, Routes.RULES),
    NavItem("设置", Icons.Filled.Settings, Icons.Outlined.SettingsOutline, Routes.SETTINGS),
)

@Composable
fun rememberContainer(): AppContainer =
    (LocalContext.current.applicationContext as LedgerAppProvider).container

@Composable
fun AutoLedgerApp(deepLinkFlow: Flow<Long>) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val vm: UiVariantViewModel = viewModel()
    val uiState by vm.uiState.collectAsState()
    val variant = uiState.effective
    val context = LocalContext.current
    val container = rememberContainer()
    val pendingCount by container.pendingEntryDao.observeCount().collectAsState(initial = 0)

    // 深链：onNewIntent/onCreate 转发的待确认 id → 跳转编辑页
    LaunchedEffect(Unit) {
        deepLinkFlow.collect { pendingId ->
            navController.navigate(Routes.edit(-1, pendingId))
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (currentRoute in navItems.map { it.route }) {
                    when (variant) {
                        UiVariant.A -> FloatingDockNav(currentRoute) { route -> navSelect(navController, route) }
                        UiVariant.B -> PillNav(currentRoute) { route -> navSelect(navController, route) }
                        UiVariant.C -> TintedNav(currentRoute) { route -> navSelect(navController, route) }
                        UiVariant.D -> FloatingDockNav(currentRoute) { route -> navSelect(navController, route) }
                        UiVariant.E -> TintedNav(currentRoute) { route -> navSelect(navController, route) }
                        UiVariant.F -> MonoNav(currentRoute, pendingCount) { route -> navSelect(navController, route) }
                    }
                }
            },
        ) { padding ->
            // 方案 A 的 Dock 是悬浮岛，内容直接延伸到底部（列表末尾自带留白）
            val padModifier = if (variant == UiVariant.A) Modifier else Modifier.padding(padding)
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = padModifier,
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        onEdit = { txId -> navController.navigate(Routes.edit(txId, -1)) },
                        onStats = { navController.navigate(Routes.STATS) },
                    )
                }
                composable(Routes.STATS) { StatsScreen() }
                composable(Routes.PENDING) {
                    PendingScreen(
                        onConfirm = { pendingId -> navController.navigate(Routes.edit(-1, pendingId)) },
                    )
                }
                composable(Routes.RULES) {
                    RulesScreen(onManageCategories = { navController.navigate(Routes.CATEGORIES) })
                }
                composable(Routes.CATEGORIES) {
                    CategoriesScreen(onBack = { navController.popBackStack() })
                }
                composable(
                    route = Routes.EDIT,
                    arguments = listOf(
                        navArgument("txId") { type = NavType.LongType; defaultValue = -1L },
                        navArgument("pendingId") { type = NavType.LongType; defaultValue = -1L },
                    ),
                ) { entry ->
                    val txId = entry.arguments?.getLong("txId") ?: -1L
                    val pendingId = entry.arguments?.getLong("pendingId") ?: -1L
                    EditEntryScreen(
                        txId = txId,
                        pendingId = pendingId,
                        onDone = { navController.popBackStack() },
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen()
                }
            }

            // —— 预览模式悬浮控制条 ——
            if (uiState.previewing) {
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 44.dp)
                        .shadow(10.dp, RoundedCornerShape(24.dp))
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.inverseSurface)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "◀",
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        fontSize = 18.sp,
                        modifier = Modifier.clickable {
                            val entries = UiVariant.entries
                            val i = entries.indexOf(uiState.previewVariant)
                            vm.setPreviewVariant(entries[(i - 1 + entries.size) % entries.size])
                        },
                    )
                    Text(
                        "预览 · " + uiState.previewVariant.label,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                    Text(
                        "▶",
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        fontSize = 18.sp,
                        modifier = Modifier.clickable {
                            val entries = UiVariant.entries
                            val i = entries.indexOf(uiState.previewVariant)
                            vm.setPreviewVariant(entries[(i + 1) % entries.size])
                        },
                    )
                    Text(
                        "✓ 用这套",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.clickable { vm.confirmPreview(context) },
                    )
                    Text(
                        "✕",
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.clickable { vm.cancelPreview() },
                    )
                }
            }
        }
    }
}

private fun navSelect(navController: androidx.navigation.NavHostController, route: String) {
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** 方案 A：悬浮胶囊 Dock，活动项圆形主色高亮 */
@Composable
private fun FloatingDockNav(currentRoute: String?, onSelect: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(68.dp)
                .shadow(14.dp, RoundedCornerShape(34.dp))
                .clip(RoundedCornerShape(34.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.98f))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(34.dp)),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navItems.forEach { item ->
                val selected = currentRoute == item.route
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { onSelect(item.route) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (selected) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(item.icon, item.label, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                        }
                        Text(
                            item.label,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        Icon(
                            item.icon, item.label,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 方案 B：经典底栏，活动项胶囊指示器 + 标签 */
@Composable
private fun PillNav(currentRoute: String?, onSelect: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().height(70.dp), verticalAlignment = Alignment.CenterVertically) {
            navItems.forEach { item ->
                val selected = currentRoute == item.route
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { onSelect(item.route) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else Color.Transparent
                            )
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            item.icon, item.label,
                            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                        if (selected) {
                            Text(
                                item.label,
                                Modifier.padding(start = 6.dp),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 方案 C：顶部圆角上翘的彩色分区导航，活动项彩色底衬胶囊 */
@Composable
private fun TintedNav(currentRoute: String?, onSelect: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navItems.forEach { item ->
                val selected = currentRoute == item.route
                Row(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent
                        )
                        .clickable { onSelect(item.route) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        item.icon, item.label,
                        tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                    if (selected) {
                        Text(
                            item.label,
                            Modifier.padding(start = 6.dp),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }
    }
}

/** 方案 F：极简底栏（细线分隔、描边/实心图标、待处理徽标） */
@Composable
private fun MonoNav(currentRoute: String?, badge: Int, onSelect: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().height(62.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navItems.forEach { item ->
                val selected = currentRoute == item.route
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { onSelect(item.route) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box {
                        Icon(
                            if (selected) item.icon else item.outlined,
                            contentDescription = item.label,
                            tint = if (selected) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                        if (item.route == Routes.PENDING && badge > 0) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 8.dp, y = (-3).dp)
                                    .size(15.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    badge.toString(),
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        item.label,
                        fontSize = 10.sp,
                        color = if (selected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}