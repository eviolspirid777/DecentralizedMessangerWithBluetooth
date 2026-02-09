package com.example.myapplication.bluetooth

import com.example.myapplication.data.ChatMessage
import org.json.JSONObject

private const val KEY_MESSAGE_ID = "id"
private const val KEY_SENDER_ADDRESS = "senderAddress"
private const val KEY_SENDER_NAME = "senderName"
private const val KEY_TEXT = "text"
private const val KEY_TIMESTAMP = "timestamp"

/**
 * Serializes [ChatMessage] to a single-line JSON string for transmission over Bluetooth.
 * Newlines in [text] are replaced to avoid breaking line-based reading.
 */
fun ChatMessage.toWireFormat(): String {
    val json = JSONObject().apply {
        put(KEY_MESSAGE_ID, messageId)
        put(KEY_SENDER_ADDRESS, senderAddress)
        put(KEY_SENDER_NAME, senderDisplayName)
        put(KEY_TEXT, text.replace("\n", " "))
        put(KEY_TIMESTAMP, timestamp)
    }
    return json.toString() + "\n"
}

/**
 * Parses a line of JSON (without trailing newline) into [ChatMessage].
 * @throws org.json.JSONException if the string is not valid JSON or required keys are missing.
 */
fun parseWireFormat(line: String): ChatMessage {
    val json = JSONObject(line.trim())
    return ChatMessage(
        messageId = json.getString(KEY_MESSAGE_ID),
        senderAddress = json.getString(KEY_SENDER_ADDRESS),
        senderDisplayName = json.optString(KEY_SENDER_NAME, "Unknown"),
        text = json.optString(KEY_TEXT, ""),
        timestamp = json.optLong(KEY_TIMESTAMP, 0L)
    )
}
