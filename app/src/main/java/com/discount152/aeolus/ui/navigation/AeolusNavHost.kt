package com.discount152.aeolus.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.discount152.aeolus.ui.forecast.ForecastScreen
import com.discount152.aeolus.ui.location.LocationPermissionEffect
import com.discount152.aeolus.ui.map.MapScreen
import com.discount152.aeolus.ui.models.ModelsScreen
import com.discount152.aeolus.ui.settings.SettingsScreen
import com.discount152.aeolus.ui.state.rememberWeatherViewModel
import com.discount152.aeolus.ui.theme.PhosphorGreen
import com.discount152.aeolus.ui.theme.TerminalBackground
import com.discount152.aeolus.ui.theme.TerminalBorder
import com.discount152.aeolus.ui.theme.TerminalMuted
import com.discount152.aeolus.ui.today.TodayScreen

object AeolusRoutes {
    const val TODAY = "today"
    const val FORECAST = "forecast"
    const val MODELS = "models"
    const val MAP = "map"
    const val SETTINGS = "settings"
}

private data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val bottomNavItems = listOf(
    BottomNavItem(AeolusRoutes.TODAY, "TODAY", Icons.Outlined.Home),
    BottomNavItem(AeolusRoutes.FORECAST, "FORECAST", Icons.Outlined.Cloud),
    BottomNavItem(AeolusRoutes.MODELS, "MODELS", Icons.Outlined.Layers),
    BottomNavItem(AeolusRoutes.MAP, "MAP", Icons.Outlined.Map),
)

@Composable
fun AeolusNavHost() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val showBottomBar = currentDestination?.route != AeolusRoutes.SETTINGS
    val weatherVm = rememberWeatherViewModel()
    LocationPermissionEffect(weatherVm)

    Scaffold(
        containerColor = TerminalBackground,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = TerminalBackground,
                    contentColor = PhosphorGreen,
                ) {
                    bottomNavItems.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.label,
                                )
                            },
                            label = {
                                Text(text = item.label)
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = PhosphorGreen,
                                selectedTextColor = PhosphorGreen,
                                unselectedIconColor = TerminalMuted,
                                unselectedTextColor = TerminalMuted,
                                indicatorColor = TerminalBorder,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AeolusRoutes.TODAY,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(AeolusRoutes.TODAY) {
                TodayScreen(
                    onOpenSettings = { navController.navigate(AeolusRoutes.SETTINGS) },
                    onOpenForecastDay = { date ->
                        weatherVm.requestForecastDayExpand(date)
                        navController.navigate(AeolusRoutes.FORECAST) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(AeolusRoutes.FORECAST) {
                ForecastScreen()
            }
            composable(AeolusRoutes.MODELS) {
                ModelsScreen()
            }
            composable(AeolusRoutes.MAP) {
                MapScreen()
            }
            composable(AeolusRoutes.SETTINGS) {
                SettingsScreen(onNavigateBack = { navController.popBackStack() })
            }
        }
    }
}
