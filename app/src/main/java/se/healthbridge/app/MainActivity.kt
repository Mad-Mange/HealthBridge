package se.healthbridge.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.healthbridge.app.healthconnect.HealthConnectRepository
import se.healthbridge.app.ui.DataScreen
import se.healthbridge.app.ui.HomeScreen
import se.healthbridge.app.ui.LogScreen
import se.healthbridge.app.ui.MainViewModel
import se.healthbridge.app.ui.SettingsScreen
import se.healthbridge.app.ui.WithingsLoginScreen
import se.healthbridge.app.ui.theme.HealthBridgeTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HealthBridgeTheme {
                HealthBridgeRoot(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions may have changed in Health Connect or system settings while we were away.
        viewModel.refreshHealthConnect()
        viewModel.refreshBatteryStatus()
    }
}

private enum class Tab(@StringRes val label: Int, val icon: ImageVector) {
    HOME(R.string.tab_home, Icons.Filled.Home),
    DATA(R.string.tab_data, Icons.Filled.Favorite),
    LOG(R.string.tab_log, Icons.AutoMirrored.Filled.List),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings),
}

@Composable
private fun HealthBridgeRoot(vm: MainViewModel) {
    val login by vm.login.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val snackbar = remember { SnackbarHostState() }
    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted -> vm.onPermissionsResult(granted) }
    val requestPermissions: () -> Unit = {
        try {
            permissionLauncher.launch(HealthConnectRepository.PERMISSIONS)
        } catch (_: ActivityNotFoundException) {
            vm.onPermissionsResult(emptySet()) // Health Connect missing on this device
        }
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    // Once Withings is connected, ask (once per launch) to notify about a broken background sync.
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val connected = settings?.withingsConnected == true
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(connected) {
        if (connected && !askedNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotifications = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val currentLogin = login
    if (currentLogin != null) {
        WithingsLoginScreen(currentLogin, onRedirect = vm::handleRedirect, onCancel = vm::cancelLogin)
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(stringResource(item.label)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.HOME -> HomeScreen(vm, onRequestPermissions = requestPermissions, onOpenSettings = { tab = Tab.SETTINGS })
                Tab.DATA -> DataScreen(vm)
                Tab.LOG -> LogScreen(vm)
                Tab.SETTINGS -> SettingsScreen(vm, onRequestPermissions = requestPermissions)
            }
        }
    }
}
