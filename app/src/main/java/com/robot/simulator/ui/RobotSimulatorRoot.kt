package com.robot.simulator.ui

import android.app.Activity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.robot.simulator.ui.navigation.Destination
import com.robot.simulator.ui.screens.RouteEditorScreen
import com.robot.simulator.ui.screens.RoutesScreen
import com.robot.simulator.ui.screens.SimulationHistoryScreen
import com.robot.simulator.ui.screens.SimulationRunningScreen
import com.robot.simulator.ui.screens.SimulationSetupScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RobotSimulatorRoot() {
    val context = LocalContext.current
    var showMapSettings by remember { mutableStateOf(false) }
    if (showMapSettings) {
        MapKeyDialog(
            onDismiss = { showMapSettings = false },
            onSaved = {
                showMapSettings = false
                (context as? Activity)?.recreate()
            },
        )
    }
    val navController = rememberNavController()
    val routesViewModel: RoutesViewModel = viewModel()
    val simulationViewModel: SimulationViewModel = viewModel()
    val routes by routesViewModel.routes.collectAsState()
    val snapshot by simulationViewModel.snapshot.collectAsState()
    val sensorHistory by simulationViewModel.sensorHistory.collectAsState()
    val activeRoute by simulationViewModel.activeRoute.collectAsState()
    val sessions by simulationViewModel.sessions.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Destination.Routes.route
    val currentDestination = Destination.entries.firstOrNull { it.route == currentRoute }
        ?: Destination.Routes
    val primaryDestinations = listOf(
        Destination.Routes,
        Destination.Setup,
        Destination.History,
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(currentDestination.title) },
                actions = {
                    TextButton(onClick = { showMapSettings = true }) { Text("地图设置") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                primaryDestinations.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(Destination.Routes.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Text(destination.shortLabel.take(1)) },
                        label = { Text(destination.shortLabel) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Routes.route,
        ) {
            composable(Destination.Routes.route) {
                RoutesScreen(
                    routes = routes,
                    contentPadding = padding,
                    onCreateRoute = { navController.navigate(Destination.Editor.route) },
                    onDeleteRoute = routesViewModel::deleteRoute,
                )
            }
            composable(Destination.Editor.route) {
                RouteEditorScreen(
                    contentPadding = padding,
                    onCancel = { navController.popBackStack() },
                    onSave = { name, points ->
                        routesViewModel.saveRoute(name, points) {
                            navController.popBackStack()
                        }
                    },
                )
            }
            composable(Destination.Setup.route) {
                SimulationSetupScreen(
                    routes = routes,
                    contentPadding = padding,
                    onStart = { route, steps, cadence, variation, speedVariation, seed ->
                        simulationViewModel.start(route, steps, cadence, variation, speedVariation, seed)
                        navController.navigate(Destination.Running.route)
                    },
                )
            }
            composable(Destination.Running.route) {
                SimulationRunningScreen(
                    snapshot = snapshot,
                    sensorHistory = sensorHistory,
                    route = activeRoute,
                    contentPadding = padding,
                    onPause = simulationViewModel::pause,
                    onResume = simulationViewModel::resume,
                    onStop = simulationViewModel::stop,
                )
            }
            composable(Destination.History.route) {
                SimulationHistoryScreen(
                    sessions = sessions,
                    contentPadding = padding,
                )
            }
        }
    }
}
