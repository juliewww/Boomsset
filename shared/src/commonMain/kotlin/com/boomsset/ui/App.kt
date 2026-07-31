package com.boomsset.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import com.boomsset.ui.theme.BoomssetTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
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
        var currentRoute by remember { mutableStateOf(ROUTE_NET_WORTH) }
        var showAddDialog by remember { mutableStateOf(false) }

        // ViewModel 在这一层取，好让加号按钮能触达 NetWorthViewModel。
        // 注意 koinViewModel 依赖 di/Modules.kt 里的显式 factory —— Native 没有反射。
        val netWorthViewModel: NetWorthViewModel = koinViewModel()
        val netWorthState by netWorthViewModel.state.collectAsStateWithLifecycle()

        Scaffold(
            topBar = { TopAppBar(title = { Text("旺资") }) },
            bottomBar = {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                if (currentRoute != tab.route) {
                                    currentRoute = tab.route
                                    navController.navigate(tab.route) {
                                        popUpTo(ROUTE_NET_WORTH) { inclusive = false }
                                        launchSingleTop = true
                                    }
                                }
                            },
                            icon = {},
                            label = { Text(tab.label) },
                        )
                    }
                }
            },
            floatingActionButton = {
                if (currentRoute == ROUTE_NET_WORTH) {
                    FloatingActionButton(onClick = { showAddDialog = true }) { Text("＋") }
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

        if (showAddDialog) {
            AddAssetDialog(
                subtypes = netWorthState.subtypes,
                defaultCurrency = netWorthState.baseCurrency,
                onDismiss = { showAddDialog = false },
                onConfirm = { newAsset ->
                    netWorthViewModel.addAsset(newAsset)
                    showAddDialog = false
                },
            )
        }
    }
}
