package com.example.myapplication.data

/**
 * Model for a chat message in the mesh network.
 * [recipientAddress]: "*" — всем; иначе адрес устройства-получателя (только он видит сообщение в чате).
 */
data class ChatMessage(
    val messageId: String,
    val senderAddress: String,
    val senderDisplayName: String,
    val recipientAddress: String,
    val text: String,
    val timestamp: Long
)
