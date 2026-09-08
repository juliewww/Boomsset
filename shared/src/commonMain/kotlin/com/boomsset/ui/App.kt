package com.boomsset.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import com.boomsset.ui.theme.BoomssetTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.boomsset.ui.allocation.AllocationScreen
import com.boomsset.ui.allocation.AllocationViewModel
import com.boomsset.ui.assets.AssetListScreen
import com.boomsset.ui.assets.AssetListViewModel
import com.boomsset.ui.networth.NetWorthScreen
import com.boomsset.security.AppLockGate
import com.boomsset.security.AppLockViewModel
import com.boomsset.ui.networth.NetWorthViewModel
import org.koin.compose.viewmodel.koinViewModel

private const val ROUTE_NET_WORTH = "net_worth"
private const val ROUTE_ALLOCATION = "allocation"
private const val ROUTE_ASSETS = "assets"

/** 添加资产是**独立页面**而不是对话框 —— 见 AddAssetScreen 的注释。 */
private const val ROUTE_ADD_ASSET = "add_asset"

private data class Tab(val route: String, val label: String)

private val tabs = listOf(
    Tab(ROUTE_NET_WORTH, "净值"),
    Tab(ROUTE_ALLOCATION, "配置"),
    Tab(ROUTE_ASSETS, "资产"),
)

/**
 * 共享 UI 入口，两端都调这个。
 *
 * 导航用**字符串路由**而不是类型安全路由：后者依赖 `@Serializable` 的反射式解析，
 * 在 Kotlin/Native 上要手写 `SerializersModule`（见 AGENTS.md 约束 2）。
 * 字符串路由完全绕开这个问题。等路由参数变复杂时再考虑上类型安全那套。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    BoomssetTheme {
        // 应用锁是最外层的门 —— 在它里面才组合任何业务内容
        val lockViewModel: AppLockViewModel = koinViewModel()
        val lockState by lockViewModel.state.collectAsStateWithLifecycle()

        AppLockGate(
            state = lockState,
            onAuthenticate = lockViewModel::authenticate,
        ) {
            AppContent(
                lockState = lockState,
                onToggleLock = lockViewModel::setLockEnabled,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppContent(
    lockState: com.boomsset.security.AppLockUiState,
    onToggleLock: (Boolean) -> Unit,
) {
    run {
        val navController = rememberNavController()

        // 当前路由**从导航状态派生**，不再用一个手工维护的 var。
        // 手工维护在只有 tab 的时候还能凑合，但一旦有了"添加资产"这种非 tab 页面，
        // 系统返回键会改变实际页面而不更新那个 var —— 底部栏和加号就会跟真实页面脱节。
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route ?: ROUTE_NET_WORTH
        val onAddRoute = currentRoute == ROUTE_ADD_ASSET

        // ViewModel 在这一层取，好让加号按钮能触达 NetWorthViewModel。
        // 注意 koinViewModel 依赖 di/Modules.kt 里的显式 factory —— Native 没有反射。
        val netWorthViewModel: NetWorthViewModel = koinViewModel()
        val netWorthState by netWorthViewModel.state.collectAsStateWithLifecycle()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (onAddRoute) "添加资产" else "旺资") },
                    navigationIcon = {
                        // 独立页面要有退路。放在 TopAppBar 而不是页面内容里，
                        // 这样它不受页面内分步（选品种 / 填详情）的影响
                        if (onAddRoute) {
                            TextButton(onClick = { navController.popBackStack() }) { Text("取消") }
                        }
                    },
                )
            },
            bottomBar = {
                // 添加资产时藏起底部栏：录一半时误点 tab 会丢掉已填的内容
                if (onAddRoute) return@Scaffold
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (currentRoute != tab.route) {
                                    navController.navigate(tab.route) {
                                        popUpTo(ROUTE_NET_WORTH) { inclusive = false }
                                        launchSingleTop = true
                                    }
                                }
                            },
                            icon = {},
                            // 选中态靠**两个通道**：更深 + 更粗。只靠颜色对色弱用户不成立，
                            // 而且这一栏只有文字（`icon = {}`），没有图标可以承载状态。
                            label = {
                                Text(
                                    tab.label,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            // label 里不用手动读 selected 再设 Text 颜色 —— NavigationBarItem
                            // 已经把这里的 colors 通过 LocalContentColor 传给 label 内容了。
                            colors = NavigationBarItemDefaults.colors(
                                // ⚠️ **不要用 `primary`。** 莫兰迪配色下它是 L 0.61 的橄榄金，
                                // 压在导航栏底上只有 3.41:1，而未选中的 `onSurfaceVariant`
                                // 有 5.26:1 —— 选中态反而比未选中更淡（实机反馈"选中效果太浅"）。
                                // 旧的高彩度橙 `#BD4D03` 靠彩度撑住"有颜色=选中"，
                                // 换成低彩度品牌色之后这个读法就塌了。
                                // `onPrimaryContainer` 是同一色系的深棕，12.72:1。
                                selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                // 指示条（M3 画在**图标位**，即文字上方那一块）也要承载选中态。
                                // 默认色是 `secondaryContainer`，压在导航栏底上**只有 1.05:1**，
                                // 等于不存在（实机反馈"选中效果太浅"，一半原因在这）。
                                // 换成 `primary`：3.41:1，过了 UI 元件"看得见"的 3:1 门槛。
                                // ⚠️ 这一栏 `icon = {}` 没有图标，所以这块是**实心色块**，
                                // 它的"有/无"本身就是选中态 —— 加图标的话这里要改成
                                // `selectedIconColor = onPrimary`，否则图标会糊在色块上。
                                indicatorColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
            },
            floatingActionButton = {
                // 加号放在"资产"页而不是"净值"页 —— 净值页是只读的概览（趋势、增长率），
                // 添加资产是资产页在做的事，放在净值页会让用户在错的地方找操作入口（实机反馈）。
                if (currentRoute == ROUTE_ASSETS) {
                    // 显式给 primary，**不用 M3 的默认值**。默认是 `primaryContainer`，
                    // 而莫兰迪配色下它是淡沙色 —— 压在同样是暖米白的页面底上只有 1.3:1，
                    // 加号几乎看不见（模拟器实测）。`primary` 是 3.70:1，
                    // 过了"UI 元件要看得见"的 3:1 门槛。
                    FloatingActionButton(
                        onClick = { navController.navigate(ROUTE_ADD_ASSET) },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) { Text("＋") }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = ROUTE_NET_WORTH,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(ROUTE_NET_WORTH) {
                    NetWorthScreen(
                        state = netWorthState,
                        onSelectPeriod = netWorthViewModel::selectPeriod,
                        onSelectBaseCurrency = netWorthViewModel::selectBaseCurrency,
                        onSelectChartMode = netWorthViewModel::selectChartMode,
                        onSelectChartStyle = netWorthViewModel::selectChartStyle,
                        onToggleClass = netWorthViewModel::toggleClassVisible,
                        lockState = lockState,
                        onToggleLock = onToggleLock,
                    )
                }
                composable(ROUTE_ALLOCATION) {
                    val allocationViewModel: AllocationViewModel = koinViewModel()
                    val allocationState by allocationViewModel.state.collectAsStateWithLifecycle()
                    AllocationScreen(
                        state = allocationState,
                        onSelectAllocation = allocationViewModel::selectAllocation,
                        onSaveTargets = allocationViewModel::saveTargets,
                        onCreateAllocation = allocationViewModel::createAllocation,
                        onRestoreBuiltIn = allocationViewModel::restoreBuiltIn,
                        onDeleteAllocation = allocationViewModel::deleteAllocation,
                    )
                }
                composable(ROUTE_ADD_ASSET) {
                    AddAssetScreen(
                        subtypes = netWorthState.subtypes,
                        defaultCurrency = netWorthState.baseCurrency,
                        onCancel = { navController.popBackStack() },
                        onConfirm = { newAsset ->
                            netWorthViewModel.addAsset(newAsset)
                            navController.popBackStack()
                        },
                    )
                }
                composable(ROUTE_ASSETS) {
                    val assetsViewModel: AssetListViewModel = koinViewModel()
                    val assetsState by assetsViewModel.state.collectAsStateWithLifecycle()
                    AssetListScreen(
                        state = assetsState,
                        onUpdateManual = assetsViewModel::updateManualValue,
                        onUpdateQuoted = assetsViewModel::updateQuotedHolding,
                        onArchive = assetsViewModel::archive,
                        onUnarchive = assetsViewModel::unarchive,
                        onEditMeta = assetsViewModel::editMeta,
                        onAddSubtype = assetsViewModel::addSubtype,
                        onSetManualPrice = assetsViewModel::setManualPrice,
                    )
                }
            }
        }
    }
}
