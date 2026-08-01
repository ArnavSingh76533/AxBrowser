package com.akay.feature.browser.ui

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.*
import androidx.navigation.compose.*
import com.akay.core.ui.theme.Primary
import com.akay.feature.bookmarks.ui.BookmarkScreen
import com.akay.feature.downloads.ui.DownloadManagerScreen
import com.akay.feature.downloads.viewmodel.DownloadViewModel
import com.akay.feature.filemanager.ui.FileManagerScreen
import com.akay.feature.history.ui.HistoryScreen
import com.akay.feature.settings.ui.FilterListsScreen
import com.akay.feature.settings.ui.PasswordManagerScreen
import com.akay.feature.settings.ui.ProxySettingsScreen
import com.akay.feature.settings.ui.SettingsScreen
import com.akay.feature.settings.ui.UserScriptsScreen
import com.akay.feature.videoplayer.PendingMediaPlay
import com.akay.feature.videoplayer.ui.VideoPlayerScreen

private sealed class NavRoute(val route: String, val label: String, val icon: ImageVector) {
    object Browser   : NavRoute("browser",   "Browser",   Icons.Default.Language)
    object Downloads : NavRoute("downloads", "Downloads", Icons.Default.Download)
    object Bookmarks : NavRoute("bookmarks", "Bookmarks", Icons.Default.Bookmark)
    object History   : NavRoute("history",   "History",   Icons.Default.History)
    object Settings  : NavRoute("settings",  "Settings",  Icons.Default.Settings)
}

private val bottomNavItems = listOf(NavRoute.Browser, NavRoute.Downloads, NavRoute.Bookmarks, NavRoute.History, NavRoute.Settings)

@Composable
fun BrowserNavHost() {
    val navController = rememberNavController()
    val currentBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStack?.destination?.route
    val showBottomBar = bottomNavItems.any { it.route == currentRoute }
    val downloadViewModel: DownloadViewModel = hiltViewModel()

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    bottomNavItems.forEach { item ->
                        val selected = currentRoute == item.route
                        val iconScale by animateFloatAsState(
                            targetValue   = if (selected) 1.15f else 1f,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                            label         = "iconScale_${item.route}"
                        )
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState    = true
                                }
                            },
                            icon = {
                                Icon(item.icon, contentDescription = item.label,
                                    modifier = Modifier.scale(iconScale))
                            },
                            label = {
                                AnimatedVisibility(
                                    visible = selected,
                                    enter = fadeIn(tween(200)) + expandHorizontally(),
                                    exit  = fadeOut(tween(100)) + shrinkHorizontally()
                                ) {
                                    Text(item.label,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1)
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor   = Primary,
                                selectedTextColor   = Primary,
                                indicatorColor      = Primary.copy(alpha = 0.12f),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(0.5f)
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController    = navController,
            startDestination = NavRoute.Browser.route,
            modifier         = Modifier.padding(innerPadding),
            enterTransition  = {
                slideInHorizontally(tween(280)) { it / 5 } + fadeIn(tween(280))
            },
            exitTransition   = {
                slideOutHorizontally(tween(280)) { -it / 5 } + fadeOut(tween(280))
            },
            popEnterTransition  = {
                slideInHorizontally(tween(280)) { -it / 5 } + fadeIn(tween(280))
            },
            popExitTransition   = {
                slideOutHorizontally(tween(280)) { it / 5 } + fadeOut(tween(280))
            }
        ) {
            composable(NavRoute.Browser.route) {
                BrowserScreen(
                    downloadViewModel = downloadViewModel,
                    onOpenSettings = { navController.navigate(NavRoute.Settings.route) }
                )
            }
            composable(NavRoute.Downloads.route) {
                DownloadManagerScreen(
                    onBack = { navController.popBackStack() },
                    viewModel = downloadViewModel,
                    onPlayInApp = { filePath, title ->
                        PendingMediaPlay.filePath = filePath
                        PendingMediaPlay.title = title
                        navController.navigate("videoplayer")
                    }
                )
            }
            composable(NavRoute.Bookmarks.route) {
                BookmarkScreen(onBookmarkClick = { navController.navigate(NavRoute.Browser.route) }, onBack = { navController.popBackStack() })
            }
            composable(NavRoute.History.route) {
                HistoryScreen(onHistoryClick = { navController.navigate(NavRoute.Browser.route) }, onBack = { navController.popBackStack() })
            }
            composable(NavRoute.Settings.route) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenExtensions = { navController.navigate("userscripts") },
                    onOpenFilterLists = { navController.navigate("filterlists") },
                    onOpenPasswords = { navController.navigate("passwords") },
                    onOpenProxySettings = { navController.navigate("proxy") }
                )
            }
            composable("userscripts") {
                UserScriptsScreen(onBack = { navController.popBackStack() })
            }
            composable("filterlists") {
                FilterListsScreen(onBack = { navController.popBackStack() })
            }
            composable("passwords") {
                PasswordManagerScreen(onBack = { navController.popBackStack() })
            }
            composable("proxy") {
                ProxySettingsScreen(onBack = { navController.popBackStack() })
            }
            composable("videoplayer") {
                VideoPlayerScreen(onBack = { navController.popBackStack() })
            }
            composable("filemanager") {
                FileManagerScreen(onFileClick = { path ->
                    PendingMediaPlay.filePath = path
                    PendingMediaPlay.title = path.substringAfterLast("/")
                    navController.navigate("videoplayer")
                })
            }
        }
    }
}
