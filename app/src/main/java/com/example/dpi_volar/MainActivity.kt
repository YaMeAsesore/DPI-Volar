package com.example.dpi_volar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.dpi_volar.ui.theme.DPIVolarTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var isProtectionActive = mutableStateOf(false)

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: si la niega, la notificación simplemente no se muestra */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            DPIVolarTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(
                        isActive = isProtectionActive.value,
                        onToggle = { toggleProtection() }
                    )
                }
            }
        }
    }

    private fun toggleProtection() {
        if (isProtectionActive.value) {
            stopVpnService()
            isProtectionActive.value = false
        } else {
            requestVpnPermission()
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_START
        }
        startService(intent)
        isProtectionActive.value = true
    }

    private fun stopVpnService() {
        val intent = Intent(this, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_STOP
        }
        startService(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(isActive: Boolean, onToggle: () -> Unit) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawerContent(onCloseDrawer = { scope.launch { drawerState.close() } })
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("DPI-Volar") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menú")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        ) { padding ->
            ProtectionScreen(
                modifier = Modifier.padding(padding),
                isActive = isActive,
                onToggle = onToggle
            )
        }
    }
}

@Composable
fun AppDrawerContent(onCloseDrawer: () -> Unit) {
    ModalDrawerSheet {
        Spacer(Modifier.height(24.dp))
        Text(
            "DPI-Volar",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()

        NavigationDrawerItem(
            label = { Text("Protección DPI") },
            selected = true,
            icon = { Icon(Icons.Default.Shield, contentDescription = null) },
            onClick = onCloseDrawer,
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        Spacer(Modifier.height(4.dp))

        NavigationDrawerItem(
            label = { Text("Más servicios (próximamente)") },
            selected = false,
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            onClick = onCloseDrawer,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
}

@Composable
fun ProtectionScreen(modifier: Modifier = Modifier, isActive: Boolean, onToggle: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "DPI",
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 2.sp
        )
        Text(
            text = "Volar",
            fontSize = 36.sp,
            fontWeight = FontWeight.Light,
            color = MaterialTheme.colorScheme.secondary,
            letterSpacing = 4.sp
        )

        Spacer(modifier = Modifier.height(40.dp))

        Text(
            text = if (isActive) "Protección DPI activa" else "Protección DPI inactiva",
            style = MaterialTheme.typography.bodyLarge,
            color = if (isActive) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onToggle,
            modifier = Modifier
                .height(56.dp)
                .fillMaxWidth(0.7f)
        ) {
            Text(if (isActive) "Detener protección" else "Activar protección")
        }
    }

}