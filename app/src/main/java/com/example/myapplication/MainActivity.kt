package com.example.myapplication

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.example.myapplication.ui.ChatScreen
import com.example.myapplication.ui.ChatViewModel
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.example.myapplication.BluetoothMeshService

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            requestDiscoverable()
        }
    }

    private val discoverableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        showChat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestBluetoothPermissions()
    }

    private fun requestBluetoothPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (permissions.isEmpty()) {
            requestDiscoverable()
            return
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    /** Запрашивает режим «доступен для обнаружения» по Bluetooth; после ответа пользователя показывается чат. */
    private fun requestDiscoverable() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            showChat()
            return
        }
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_DURATION_SECONDS)
        }
        discoverableLauncher.launch(intent)
    }

    private fun showChat() {
        BluetoothMeshService.start(this)
        val viewModel = ViewModelProvider(this)[ChatViewModel::class.java]
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ChatScreen(viewModel = viewModel)
                }
            }
        }
    }

    companion object {
        /** Время (в секундах), в течение которого устройство остаётся обнаруживаемым. Макс. 3600. */
        private const val DISCOVERABLE_DURATION_SECONDS = 300
    }
}
