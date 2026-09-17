package com.boomsset.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

/** Add asset is a **standalone screen**, not a dialog — see the comments in AddAssetScreen. */
private const val ROUTE_ADD_ASSET = "add_asset"

private data class Tab(val route: String, val label: String, val icon: @Composable () -> Unit)

private val tabs = listOf(
    Tab(ROUTE_NET_WORTH, "净值") { NetWorthTabIcon() },
    Tab(ROUTE_ALLOCATION, "配置") { AllocationTabIcon() },
    Tab(ROUTE_ASSETS, "资产") { AssetListTabIcon() },
)

/**
 * Shared UI entry point, called from both platforms.
 *
 * Navigation uses **string routes** rather than type-safe routes: the latter relies on
 * `@Serializable`'s reflection-based resolution, which requires hand-writing a
 * `SerializersModule` on Kotlin/Native (see AGENTS.md constraint 2). String routes sidestep
 * this entirely. Revisit type-safe routing once route parameters get more complex.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    BoomssetTheme {
        // App lock is the outermost gate — only compose any business content inside it
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

        // The current route is **derived from navigation state**, not a manually maintained
        // var. Manual maintenance was passable when there were only tabs, but once a
        // non-tab page like "add asset" exists, the system back button changes the actual
        // page without updating that var — the bottom bar and the FAB would then drift out
        // of sync with the real page.
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route ?: ROUTE_NET_WORTH
        val onAddRoute = currentRoute == ROUTE_ADD_ASSET

        // The ViewModel is obtained at this level so the FAB can reach NetWorthViewModel.
        // Note that koinViewModel relies on the explicit factory in di/Modules.kt —
        // Native has no reflection.
        val netWorthViewModel: NetWorthViewModel = koinViewModel()
        val netWorthState by netWorthViewModel.state.collectAsStateWithLifecycle()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        if (onAddRoute) {
                            Text("添加资产")
                        } else {
                            // The pig only appears next to the title, not in sub-page titles
                            // like "add asset" — it's a brand mark, while a sub-page title
                            // states "what's currently being done"; the two are different things.
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PigGlyph()
                                Text("猪满仓")
                            }
                        }
                    },
                    navigationIcon = {
                        // A standalone page needs a way out. It's placed in the TopAppBar
                        // rather than the page content, so it stays unaffected by the
                        // in-page steps (pick subtype / fill details)
                        if (onAddRoute) {
                            TextButton(onClick = { navController.popBackStack() }) { Text("取消") }
                        }
                    },
                )
            },
            bottomBar = {
                // Hide the bottom bar while adding an asset: accidentally tapping a tab
                // partway through would lose what's already been filled in
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
                            icon = tab.icon,
                            // The selected state relies on **three channels**: icon+text color
                            // change, a solid indicator pill, and bold text. Color alone
                            // doesn't work for color-blind users, which is why the text is
                            // also bolded.
                            label = {
                                Text(
                                    tab.label,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            // No need to manually read `selected` and set the Text color inside
                            // `label` — NavigationBarItem already passes these `colors` down to
                            // the label/icon content via LocalContentColor.
                            colors = NavigationBarItemDefaults.colors(
                                // The selected state uses `primary` directly — mid-rose against
                                // the nav bar background (`surfaceContainer`) is **4.07:1**,
                                // clearly distinguishable, and this row uses the genuine brand
                                // color, so across pages it reads as the same color as the FAB
                                // and the net-worth-chart bars, with no sense of disconnect.
                                // ⚠️ **This is tightly coupled to primary's lightness — changing
                                // the brand color requires re-measuring:** the bright-rose version
                                // was only 2.82:1 against the nav bar background (even fainter
                                // than the unselected state's 5.28:1), which forced a separate,
                                // brighter constant to be solved for at the time; the even
                                // earlier cream-yellow was only 1.67:1.
                                // General rule: **wherever color alone signals state, compute and
                                // compare the contrast ratios of both the selected and unselected
                                // states** — don't just check "does the selected state carry the
                                // brand color."
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                // The indicator pill (M3 draws it at the **icon slot**, i.e. the
                                // area above the text) must also carry the selected state.
                                // Its default color is `secondaryContainer`, which is **only
                                // 1.05:1** against the nav bar background — effectively invisible
                                // (real-device feedback "the selected effect is too faint" was
                                // half due to this).
                                indicatorColor = MaterialTheme.colorScheme.primary,
                                // The icon is now drawn **on top of** this solid indicator pill,
                                // so the selected state needs `onPrimary` (light) rather than
                                // `primary` — a same-color icon would blend into the pill. This
                                // was a TODO left here before icons were added; now that icons
                                // have actually arrived, it's applied.
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            },
            floatingActionButton = {
                // The FAB is on the "assets" page rather than "net worth" — the net worth
                // page is a read-only overview (trend, growth rate), while adding an asset
                // is something the assets page does; putting it on the net worth page would
                // make users look for the action in the wrong place (real-device feedback).
                if (currentRoute == ROUTE_ASSETS) {
                    // Explicitly given `primary`, **not M3's default** (the default is
                    // `primaryContainer`, which is lighter and would make the "+" blend in more).
                    // primary is mid-rose, 4.34:1 against the page background; the white "+"
                    // is 4.54:1 against primary.
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
                        onToggleAmountsHidden = netWorthViewModel::setAmountsHidden,
                        onRetryPricing = netWorthViewModel::retryPricing,
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
