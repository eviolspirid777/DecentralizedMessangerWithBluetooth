package com.example.myapplication.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.myapplication.R
import com.example.myapplication.bluetooth.DeviceStatus
import com.example.myapplication.bluetooth.FoundDeviceInfo
import com.example.myapplication.data.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val messages by viewModel.messages.collectAsState()
    val peersCount by viewModel.peersCount.collectAsState()
    val discoveryInProgress by viewModel.discoveryInProgress.collectAsState()
    val foundDevices by viewModel.foundDevices.collectAsState()
    val localAddress = viewModel.localAddress

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when {
                    discoveryInProgress -> stringResource(R.string.status_discovery_running)
                    peersCount > 0 -> LocalContext.current.getString(R.string.status_peers, peersCount)
                    else -> stringResource(R.string.status_searching)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { viewModel.startDiscovery() },
                enabled = !discoveryInProgress,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text(stringResource(R.string.find_devices))
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.35f)
                .heightIn(min = 72.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.found_devices_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
            if (foundDevices.isEmpty()) {
                Text(
                    text = stringResource(R.string.found_devices_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            } else {
                foundDevices.forEach { device ->
                    val statusText = when (device.status) {
                        DeviceStatus.CONNECTED -> stringResource(R.string.device_connected)
                        DeviceStatus.CONNECTING -> stringResource(R.string.device_connecting)
                        DeviceStatus.CONNECTION_FAILED -> stringResource(R.string.device_connection_failed)
                        DeviceStatus.FOUND -> ""
                    }
                    Text(
                        text = "${device.name} (${device.address})${if (statusText.isNotEmpty()) " — $statusText" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.messageId }) { message ->
                val isOwn = message.senderAddress == localAddress
                MessageBubble(
                    message = message,
                    isOwn = isOwn
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.message_hint)) },
                singleLine = false,
                maxLines = 4
            )
            Button(
                onClick = {
                    viewModel.sendMessage(inputText)
                    inputText = ""
                }
            ) {
                Text(stringResource(R.string.send))
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    isOwn: Boolean
) {
    val backgroundColor = if (isOwn) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isOwn) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .background(
                    color = backgroundColor,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(12.dp)
        ) {
            Text(
                text = message.senderDisplayName,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor
            )
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor
            )
            Text(
                text = formatTimestamp(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.7f)
            )
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}
