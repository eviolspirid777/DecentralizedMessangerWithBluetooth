package com.example.myapplication.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import com.example.myapplication.data.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

/** Status of a discovered device for UI. */
enum class DeviceStatus { FOUND, CONNECTING, CONNECTED, CONNECTION_FAILED }

data class FoundDeviceInfo(
    val name: String,
    val address: String,
    val status: DeviceStatus
)

/**
 * Manages Bluetooth Classic RFCOMM server, discovery, client connections,
 * and send/receive with flooding (forward received messages to all other peers).
 */
class BluetoothMeshManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    /** Fixed UUID for the chat service; must be the same on all devices. */
    private val serviceUuid: UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")

    private val sockets = CopyOnWriteArrayList<PeerSocket>()
    private val seenMessageIds = CopyOnWriteArraySet<String>()
    private var serverJob: Job? = null
    private var discoveryReceiver: android.content.BroadcastReceiver? = null
    private var bondStateReceiver: android.content.BroadcastReceiver? = null
    private val foundDevices = CopyOnWriteArrayList<FoundDeviceInfo>()
    private var discoveryInProgress = false
    /** Адреса устройств, с которыми инициировано сопряжение; после BOND_BONDED подключаемся. */
    private val pendingBondAddresses = CopyOnWriteArraySet<String>()

    var onMessageReceived: ((ChatMessage) -> Unit)? = null
    var onPeersCountChanged: ((Int) -> Unit)? = null
    var onDiscoveryInProgress: ((Boolean) -> Unit)? = null
    var onFoundDevicesChanged: ((List<FoundDeviceInfo>) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun getLocalAddress(): String = adapter?.address ?: ""

    @SuppressLint("MissingPermission")
    fun getLocalName(): String = adapter?.name?.takeIf { it.isNotBlank() } ?: "Device"

    /**
     * Запускает RFCOMM‑сервер и принимает все входящие подключения без запроса подтверждения.
     * Каждое новое соединение автоматически добавляется в список пиров.
     * Примечание: диалог «Сопрячь с устройством?» показывает система при первой попытке
     * подключения незнакомого устройства — отключить его из приложения нельзя.
     */
    @SuppressLint("MissingPermission")
    fun startServer() {
        if (adapter == null) return
        serverJob?.cancel()
        serverJob = scope.launch {
            var serverSocket: BluetoothServerSocket? = null
            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, serviceUuid)
                while (scope.isActive) {
                    val socket = try {
                        serverSocket?.accept()
                    } catch (e: IOException) {
                        break
                    }
                    socket?.let { addPeerSocket(it, null) }
                }
            } catch (e: IOException) {
                // Server failed to start (e.g. already in use)
            } finally {
                try {
                    serverSocket?.close()
                } catch (_: IOException) { }
            }
        }
    }

    /** Starts device discovery and connects to found devices (same app / same UUID). */
    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        if (adapter == null) return
        discoveryReceiver?.let { try { context.unregisterReceiver(it) } catch (_: Exception) { } }
        discoveryReceiver = null
        foundDevices.clear()
        notifyFoundDevices()
        discoveryReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: android.content.Intent?) {
                when (intent?.action) {
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                        discoveryInProgress = true
                        notifyDiscoveryInProgress()
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        discoveryInProgress = false
                        notifyDiscoveryInProgress()
                    }
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        device?.let { onDeviceFound(it) }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(discoveryReceiver, filter)
        }
        registerBondStateReceiverIfNeeded()
        adapter.cancelDiscovery()
        discoveryInProgress = true
        notifyDiscoveryInProgress()
        adapter.startDiscovery()
    }

    @SuppressLint("MissingPermission")
    private fun registerBondStateReceiverIfNeeded() {
        if (bondStateReceiver != null) return
        bondStateReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: android.content.Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                device?.let { onBondStateChanged(it, bondState) }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(bondStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(bondStateReceiver, filter)
        }
    }

    @SuppressLint("MissingPermission")
    private fun onDeviceFound(device: BluetoothDevice) {
        val address = device.address
        if (address == getLocalAddress()) return
        val name = device.name?.takeIf { it.isNotBlank() } ?: address
        val existing = foundDevices.indexOfFirst { it.address == address }
        if (existing >= 0) return
        foundDevices.add(FoundDeviceInfo(name = name, address = address, status = DeviceStatus.FOUND))
        notifyFoundDevices()
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> tryConnectAsClient(device)
            BluetoothDevice.BOND_NONE, BluetoothDevice.BOND_BONDING -> {
                pendingBondAddresses.add(address)
                if (device.bondState == BluetoothDevice.BOND_NONE) {
                    device.createBond()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun onBondStateChanged(device: BluetoothDevice, bondState: Int) {
        val address = device.address
        when (bondState) {
            BluetoothDevice.BOND_BONDED -> {
                if (pendingBondAddresses.remove(address)) {
                    tryConnectAsClient(device)
                }
            }
            BluetoothDevice.BOND_NONE -> {
                if (pendingBondAddresses.remove(address)) {
                    updateDeviceStatus(address, DeviceStatus.CONNECTION_FAILED)
                }
            }
        }
    }

    private fun notifyDiscoveryInProgress() {
        onDiscoveryInProgress?.let { callback ->
            scope.launch { withContext(Dispatchers.Main) { callback(discoveryInProgress) } }
        }
    }

    private fun notifyFoundDevices() {
        val list = foundDevices.toList()
        onFoundDevicesChanged?.let { callback ->
            scope.launch { withContext(Dispatchers.Main) { callback(list) } }
        }
    }

    private fun updateDeviceStatus(address: String, status: DeviceStatus, nameForNew: String? = null) {
        val i = foundDevices.indexOfFirst { it.address == address }
        if (i >= 0) {
            val d = foundDevices[i]
            foundDevices[i] = d.copy(status = status)
        } else if (status == DeviceStatus.CONNECTED) {
            foundDevices.add(FoundDeviceInfo(name = nameForNew ?: address, address = address, status = status))
        }
        notifyFoundDevices()
    }

    /** Stops discovery and unregisters the discovery receiver. */
    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        adapter?.cancelDiscovery()
        discoveryReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) { }
            discoveryReceiver = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryConnectAsClient(device: BluetoothDevice) {
        val address = device.address
        if (address == getLocalAddress()) return
        if (sockets.any { it.address == address }) return
        updateDeviceStatus(address, DeviceStatus.CONNECTING)
        scope.launch {
            var socket: BluetoothSocket? = null
            try {
                socket = device.createRfcommSocketToServiceRecord(serviceUuid)
                socket?.connect()
                socket?.let { addPeerSocket(it, device) }
            } catch (e: IOException) {
                try { socket?.close() } catch (_: IOException) { }
                updateDeviceStatus(address, DeviceStatus.CONNECTION_FAILED)
            }
        }
    }

    private data class PeerSocket(
        val socket: BluetoothSocket,
        val address: String,
        val outputStream: OutputStream
    )

    @SuppressLint("MissingPermission")
    private fun addPeerSocket(bluetoothSocket: BluetoothSocket, device: BluetoothDevice?) {
        val remote = bluetoothSocket.remoteDevice
        val address = device?.address ?: remote?.address ?: "unknown"
        val name = device?.name?.takeIf { it.isNotBlank() } ?: remote?.name?.takeIf { it.isNotBlank() } ?: address
        val outputStream = try {
            bluetoothSocket.outputStream
        } catch (e: IOException) {
            try { bluetoothSocket.close() } catch (_: IOException) { }
            return
        }
        val peer = PeerSocket(bluetoothSocket, address, outputStream)
        sockets.add(peer)
        updateDeviceStatus(address, DeviceStatus.CONNECTED, nameForNew = name)
        notifyPeersCount()
        scope.launch { readLoop(peer) }
    }

    private fun removePeer(peer: PeerSocket) {
        try {
            peer.socket.close()
        } catch (_: IOException) { }
        sockets.remove(peer)
        updateDeviceStatus(peer.address, DeviceStatus.FOUND)
        notifyPeersCount()
    }

    private fun notifyPeersCount() {
        val count = sockets.size
        onPeersCountChanged?.let { callback ->
            scope.launch { withContext(Dispatchers.Main) { callback(count) } }
        }
    }

    /** Sends the message to all connected peers (flooding starts from here). */
    fun sendMessage(message: ChatMessage) {
        seenMessageIds.add(message.messageId)
        onMessageReceived?.invoke(message)
        broadcastRaw(message.toWireFormat(), fromAddress = null)
    }

    /** Broadcasts a raw JSON line to all peers except [fromAddress]. Used for flooding. */
    private fun broadcastRaw(rawLine: String, fromAddress: String?) {
        val payload = rawLine.toByteArray(Charsets.UTF_8)
        sockets.forEach { peer ->
            if (peer.address == fromAddress) return@forEach
            scope.launch {
                try {
                    peer.outputStream.write(payload)
                    peer.outputStream.flush()
                } catch (e: IOException) {
                    removePeer(peer)
                }
            }
        }
    }

    private fun readLoop(peer: PeerSocket) {
        val reader = try {
            BufferedReader(InputStreamReader(peer.socket.inputStream, Charsets.UTF_8))
        } catch (e: IOException) {
            removePeer(peer)
            return
        }
        try {
            while (scope.isActive) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                try {
                    val message = parseWireFormat(line)
                    if (seenMessageIds.add(message.messageId)) {
                        onMessageReceived?.let { callback ->
                            scope.launch { withContext(Dispatchers.Main) { callback(message) } }
                        }
                        broadcastRaw(line + "\n", fromAddress = peer.address)
                    }
                } catch (_: Exception) {
                    // Ignore malformed lines
                }
            }
        } catch (_: IOException) {
            // Socket closed or error
        } finally {
            removePeer(peer)
        }
    }

    fun stop() {
        serverJob?.cancel()
        stopDiscovery()
        sockets.toList().forEach { removePeer(it) }
    }

    fun release() {
        stop()
        bondStateReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) { }
            bondStateReceiver = null
        }
        scope.cancel()
    }

    companion object {
        private const val SERVICE_NAME = "BluetoothMeshChat"
    }
}
