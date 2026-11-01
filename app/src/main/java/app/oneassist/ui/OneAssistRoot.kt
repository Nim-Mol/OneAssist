package app.oneassist.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.oneassist.ui.assistant.AssistantScreen
import app.oneassist.ui.components.NoticeBanner
import app.oneassist.ui.importer.ImportScreen
import app.oneassist.ui.insights.InsightsScreen
import app.oneassist.ui.lock.AppLock
import app.oneassist.ui.lock.AppLockGate
import app.oneassist.ui.overview.OverviewScreen
import app.oneassist.ui.settings.SettingsScreen
import app.oneassist.ui.share.ShareDialogs
import app.oneassist.ui.share.ShareViewModel
import app.oneassist.ui.subscriptions.SubscriptionsScreen
import app.oneassist.ui.transactions.TransactionsScreen

object Routes {
    const val OVERVIEW = "overview"
    const val TRANSACTIONS = "transactions"
    const val SUBSCRIPTIONS = "subscriptions"
    const val INSIGHTS = "insights"
    const val ASSISTANT = "assistant"
    const val SETTINGS = "settings"
    const val IMPORT = "import"
}

private data class TopLevelDestination(val route: String, val label: String, val icon: ImageVector)

private val topLevelDestinations = listOf(
    TopLevelDestination(Routes.OVERVIEW, "Overview", Icons.Outlined.Home),
    TopLevelDestination(Routes.TRANSACTIONS, "Transactions", Icons.AutoMirrored.Outlined.ReceiptLong),
    TopLevelDestination(Routes.SUBSCRIPTIONS, "Subscriptions", Icons.Outlined.Autorenew),
    TopLevelDestination(Routes.INSIGHTS, "Insights", Icons.Outlined.Lightbulb),
    TopLevelDestination(Routes.ASSISTANT, "Assistant", Icons.AutoMirrored.Outlined.Chat),
)

/** The whole app UI: lock gate, bottom navigation, screens and share dialogs. */
@Composable
fun OneAssistRoot(shareViewModel: ShareViewModel) {
    AppLockGate {
        MainScaffold()
        ShareDialogs(shareViewModel)
    }
}

@Composable
private fun MainScaffold() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val showBottomBar = destination == null ||
        topLevelDestinations.any { top -> destination.hierarchy.any { it.route == top.route } }
    var bannerDismissed by rememberSaveable { mutableStateOf(false) }
    val showBanner = AppLock.noScreenLock && !bannerDismissed

    Scaffold(
        bottomBar = {
            Column {
                if (showBanner) {
                    NoticeBanner(
                        icon = Icons.Outlined.Warning,
                        text = "Your phone has no screen lock, so anyone holding it can open OneAssist. " +
                            "Set a PIN, pattern or fingerprint in your phone's settings.",
                        trailing = {
                            IconButton(onClick = { bannerDismissed = true }) {
                                Icon(Icons.Outlined.Close, contentDescription = "Dismiss")
                            }
                        },
                    )
                }
                if (showBottomBar) {
                    NavigationBar {
                        topLevelDestinations.forEach { top ->
                            val selected = destination?.hierarchy?.any { it.route == top.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navController.navigateTopLevel(top.route) },
                                icon = { Icon(top.icon, contentDescription = null) },
                                label = { Text(top.label, maxLines = 1) },
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        },
    ) { innerPadding ->
        // Screens draw their own top bar (and its status bar inset); we only reserve the bottom.
        val bottom = PaddingValues(bottom = innerPadding.calculateBottomPadding())
        NavHost(
            navController = navController,
            startDestination = Routes.OVERVIEW,
            modifier = Modifier
                .padding(bottom)
                .consumeWindowInsets(bottom),
        ) {
            composable(Routes.OVERVIEW) {
                OverviewScreen(
                    onOpenImport = { navController.navigate(Routes.IMPORT) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenInsights = { navController.navigateTopLevel(Routes.INSIGHTS) },
                    onOpenSubscriptions = { navController.navigateTopLevel(Routes.SUBSCRIPTIONS) },
                )
            }
            composable(Routes.TRANSACTIONS) { TransactionsScreen() }
            composable(Routes.SUBSCRIPTIONS) { SubscriptionsScreen() }
            composable(Routes.INSIGHTS) { InsightsScreen() }
            composable(Routes.ASSISTANT) {
                AssistantScreen(onOpenSettings = { navController.navigate(Routes.SETTINGS) })
            }
            composable(Routes.SETTINGS) { SettingsScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.IMPORT) { ImportScreen(onBack = { navController.popBackStack() }) }
        }
    }
}

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
