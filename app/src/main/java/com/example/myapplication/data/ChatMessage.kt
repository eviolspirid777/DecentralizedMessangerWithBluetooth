package com.example.myapplication.data

/**
 * Model for a chat message in the mesh network.
 * Used for display and for serialization over Bluetooth.
 */
data class ChatMessage(
    val messageId: String,
    val senderAddress: String,
    val senderDisplayName: String,
    val text: String,
    val timestamp: Long
)
