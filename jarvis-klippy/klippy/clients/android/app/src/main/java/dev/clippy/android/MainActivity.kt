package dev.clippy.android

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val sharedText = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedText.value = intent.sharedText()

        setContent {
            ClippyTheme {
                ClippyApp(sharedText = sharedText.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedText.value = intent.sharedText()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClippyApp(sharedText: String?) {
    val context = LocalContext.current
    val settings = remember { ClippySettings(context) }
    val scope = rememberCoroutineScope()

    var serverUrl by rememberSaveable { mutableStateOf(settings.serverUrl) }
    var clientId by rememberSaveable { mutableStateOf(settings.clientId) }
    var clientToken by rememberSaveable { mutableStateOf(settings.clientToken) }
    var clipboardText by rememberSaveable { mutableStateOf(sharedText.orEmpty()) }
    var autoSync by rememberSaveable { mutableStateOf(settings.autoSync) }
    var status by rememberSaveable {
        mutableStateOf(
            if (sharedText.isNullOrBlank()) {
                "Ready"
            } else {
                "Shared text loaded"
            }
        )
    }
    var sending by rememberSaveable { mutableStateOf(false) }
    var lastSent by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(sharedText) {
        if (!sharedText.isNullOrBlank()) {
            clipboardText = sharedText
            status = "Shared text loaded"
        }
    }

    fun saveSettings() {
        settings.serverUrl = serverUrl.trim()
        settings.clientId = clientId.trim()
        settings.clientToken = clientToken.trim()
        settings.autoSync = autoSync
        status = "Settings saved"
    }

    fun loadClipboard() {
        val text = context.readClipboardText()
        if (text.isNullOrBlank()) {
            status = "Clipboard has no text"
        } else {
            clipboardText = text
            status = "Clipboard loaded"
        }
    }

    fun sendCurrent(content: String = clipboardText) {
        val normalizedServerUrl = serverUrl.trim()
        val normalizedClientId = clientId.trim()
        val normalizedClientToken = clientToken.trim()

        if (normalizedServerUrl.isBlank()) {
            status = "Enter a server URL"
            return
        }
        if (normalizedClientId.isBlank()) {
            status = "Enter a client ID"
            return
        }
        if (normalizedClientToken.isBlank()) {
            status = "Enter a client token"
            return
        }
        if (content.isBlank()) {
            status = "Nothing to send"
            return
        }

        sending = true
        status = "Sending..."
        scope.launch {
            val result = ClippyApi.send(normalizedServerUrl, normalizedClientId, normalizedClientToken, content)
            sending = false
            status = result.message
            if (result.success) {
                lastSent = content
                settings.serverUrl = normalizedServerUrl
                settings.clientId = normalizedClientId
                settings.clientToken = normalizedClientToken
            }
        }
    }

    LaunchedEffect(autoSync, serverUrl, clientId, clientToken) {
        settings.autoSync = autoSync
        while (autoSync) {
            val text = context.readClipboardText()
            if (!sending && !text.isNullOrBlank() && text != lastSent) {
                clipboardText = text
                sendCurrent(text)
            }
            delay(2_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Clippy",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF8FAFC))
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Connection", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Server URL") },
                        placeholder = { Text("http://192.168.1.10:8080") },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            keyboardType = KeyboardType.Uri
                        )
                    )
                    OutlinedTextField(
                        value = clientId,
                        onValueChange = { clientId = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Client ID") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
                    )
                    OutlinedTextField(
                        value = clientToken,
                        onValueChange = { clientToken = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Client Token") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
                    )
                    Button(
                        onClick = ::saveSettings,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Save")
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Clipboard", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Auto sync checks while this screen is open.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF64748B)
                            )
                        }
                        Switch(checked = autoSync, onCheckedChange = { autoSync = it })
                    }

                    OutlinedTextField(
                        value = clipboardText,
                        onValueChange = { clipboardText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        label = { Text("Text") },
                        minLines = 6
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = ::loadClipboard,
                            modifier = Modifier.weight(1f),
                            enabled = !sending
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("Paste")
                        }
                        Button(
                            onClick = { sendCurrent() },
                            modifier = Modifier.weight(1f),
                            enabled = !sending
                        ) {
                            Icon(
                                imageVector = if (sending) Icons.Default.Sync else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(if (sending) "Sending" else "Send")
                        }
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = Color(0xFF2563EB))
                    Column {
                        Text("Status", style = MaterialTheme.typography.titleSmall)
                        Text(status, color = Color(0xFF334155))
                    }
                }
            }
        }
    }
}

@Composable
private fun ClippyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF2563EB),
            secondary = Color(0xFF0F766E),
            tertiary = Color(0xFFB45309),
            background = Color(0xFFF8FAFC),
            surface = Color.White
        ),
        content = content
    )
}

private class ClippySettings(context: Context) {
    private val preferences = context.getSharedPreferences("clippy", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = preferences.getString("serverUrl", "") ?: ""
        set(value) = preferences.edit().putString("serverUrl", value).apply()

    var clientId: String
        get() = preferences.getString("clientId", defaultClientId()) ?: defaultClientId()
        set(value) = preferences.edit().putString("clientId", value).apply()

    var clientToken: String
        get() = preferences.getString("clientToken", "") ?: ""
        set(value) = preferences.edit().putString("clientToken", value).apply()

    var autoSync: Boolean
        get() = preferences.getBoolean("autoSync", false)
        set(value) = preferences.edit().putBoolean("autoSync", value).apply()
}

private object ClippyApi {
    suspend fun send(serverUrl: String, clientId: String, clientToken: String, content: String): SendResult =
        withContext(Dispatchers.IO) {
            try {
                val endpoint = clipboardEndpoint(serverUrl)
                val payload = JSONObject()
                    .put("clientId", clientId)
                    .put("content", content)
                    .put("timestamp", Instant.now().toString())
                    .toString()

                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 5_000
                    readTimeout = 10_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer $clientToken")
                }

                connection.outputStream.use { output ->
                    output.write(payload.toByteArray(Charsets.UTF_8))
                }

                val statusCode = connection.responseCode
                connection.disconnect()

                if (statusCode in 200..299) {
                    SendResult(success = true, message = "Sent ${content.length} chars")
                } else {
                    SendResult(success = false, message = "Server responded HTTP $statusCode")
                }
            } catch (exception: Exception) {
                SendResult(success = false, message = exception.message ?: "Send failed")
            }
        }

    private fun clipboardEndpoint(serverUrl: String): String {
        val trimmed = serverUrl.trim()
        return if (trimmed.endsWith("/clipboard")) {
            trimmed
        } else {
            trimmed.trimEnd('/') + "/clipboard"
        }
    }
}

private data class SendResult(
    val success: Boolean,
    val message: String
)

private fun Context.readClipboardText(): String? {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
    return item.coerceToText(this)?.toString()
}

private fun Intent.sharedText(): String? {
    if (action != Intent.ACTION_SEND || type != "text/plain") {
        return null
    }
    return getStringExtra(Intent.EXTRA_TEXT)
}

private fun defaultClientId(): String {
    return "android-" + android.os.Build.MODEL
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-')
        .ifBlank { "device" }
}
