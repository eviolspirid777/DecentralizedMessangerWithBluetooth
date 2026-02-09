package com.example.myapplication.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.bluetooth.BluetoothMeshManager
import com.example.myapplication.bluetooth.FoundDeviceInfo
import com.example.myapplication.data.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val manager = (application as com.example.myapplication.MyApplication).bluetoothMeshManager

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _peersCount = MutableStateFlow(0)
    val peersCount: StateFlow<Int> = _peersCount.asStateFlow()

    private val _discoveryInProgress = MutableStateFlow(false)
    val discoveryInProgress: StateFlow<Boolean> = _discoveryInProgress.asStateFlow()

    private val _foundDevices = MutableStateFlow<List<FoundDeviceInfo>>(emptyList())
    val foundDevices: StateFlow<List<FoundDeviceInfo>> = _foundDevices.asStateFlow()

    val localAddress: String get() = manager.getLocalAddress()
    val localName: String get() = manager.getLocalName()

    init {
        manager.onMessageReceived = { message ->
            viewModelScope.launch {
                _messages.update { it + message }
            }
        }
        manager.onPeersCountChanged = { count ->
            viewModelScope.launch {
                _peersCount.value = count
            }
        }
        manager.onDiscoveryInProgress = { inProgress ->
            viewModelScope.launch {
                _discoveryInProgress.value = inProgress
            }
        }
        manager.onFoundDevicesChanged = { list ->
            viewModelScope.launch {
                _foundDevices.value = list
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        val message = ChatMessage(
            messageId = UUID.randomUUID().toString(),
            senderAddress = manager.getLocalAddress(),
            senderDisplayName = manager.getLocalName(),
            text = text.trim(),
            timestamp = System.currentTimeMillis()
        )
        manager.sendMessage(message)
    }

    fun startDiscovery() {
        manager.startDiscovery()
    }

    fun stopDiscovery() {
        manager.stopDiscovery()
    }

    override fun onCleared() {
        super.onCleared()
        // Менеджер — синглтон в Application; не освобождаем, чтобы сервис продолжал работать в фоне.
    }
}
