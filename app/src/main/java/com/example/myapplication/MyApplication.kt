package com.example.myapplication

import android.app.Application
import com.example.myapplication.bluetooth.BluetoothMeshManager

class MyApplication : Application() {

    val bluetoothMeshManager: BluetoothMeshManager by lazy {
        BluetoothMeshManager(applicationContext).also { it.startServer() }
    }
}
