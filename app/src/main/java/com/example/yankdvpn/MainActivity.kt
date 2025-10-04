package com.example.yankdvpn

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.yankdvpn.ui.theme.YankDVPNTheme
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.d(TAG, "VPN permission granted")
            Toast.makeText(this, "VPN Permission Granted", Toast.LENGTH_SHORT).show()
        } else {
            Log.e(TAG, "VPN permission denied")
            Toast.makeText(this, "VPN Permission Required", Toast.LENGTH_LONG).show()
        }
    }

    // State variables
    private var isConnected by mutableStateOf(false)
    private var connectionStatus by mutableStateOf("Disconnected")
    private var connectionError by mutableStateOf("")
    private var lastErrorCode by mutableStateOf(0)
    private var debugLogs by mutableStateOf(listOf<String>())
    private var handshakeStatus by mutableStateOf("N/A")
    private var currentPublicIP by mutableStateOf("Not connected")
    private var deviceIPv4 by mutableStateOf("Fetching...")
    private var deviceIPv6 by mutableStateOf("Fetching...")
    private var exitNodeIPv4 by mutableStateOf("")
    private var exitNodeIPv6 by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestVpnPermission()

        // Fetch device IPs on startup
        fetchDevicePublicIPs()

        setContent {
            YankDVPNTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isConnected) {
            stopVpnService()
        }
    }

    @Composable
    fun MainScreen() {
        var selectedMode by remember { mutableStateOf("Exit Node") }
        var exitNodePrivateKey by remember { mutableStateOf("") }
        var exitNodePublicKey by remember { mutableStateOf("") }
        var clientPrivateKey by remember { mutableStateOf("") }
        var clientPublicKey by remember { mutableStateOf("") }
        var peerPublicKey by remember { mutableStateOf("") }
        var peerEndpoint by remember { mutableStateOf("") }
        var showDebugLogs by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Yank DVPN",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Device Public IPs Card (Always visible on homepage)
            DeviceIPCard(
                ipv4 = deviceIPv4,
                ipv6 = deviceIPv6,
                onRefresh = { fetchDevicePublicIPs() }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Status Card with Enhanced Error Display
            EnhancedStatusCard(
                connectionStatus = connectionStatus,
                connectionError = connectionError,
                lastErrorCode = lastErrorCode,
                handshakeStatus = handshakeStatus,
                publicIP = currentPublicIP,
                onShowDebugLogs = { showDebugLogs = !showDebugLogs }
            )

            // Debug Logs Card (toggleable)
            if (showDebugLogs) {
                Spacer(modifier = Modifier.height(8.dp))
                DebugLogsCard(
                    logs = debugLogs,
                    onClearLogs = { debugLogs = emptyList() }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Select Mode",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { if (!isConnected) selectedMode = "Exit Node" },
                    enabled = !isConnected,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedMode == "Exit Node")
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("Exit Node")
                }

                Button(
                    onClick = { if (!isConnected) selectedMode = "Client" },
                    enabled = !isConnected,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedMode == "Client")
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("Client")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (selectedMode) {
                "Exit Node" -> {
                    ExitNodeConfiguration(
                        publicKey = exitNodePublicKey,
                        exitNodeIPv4 = exitNodeIPv4,
                        exitNodeIPv6 = exitNodeIPv6,
                        onGenerateKeys = {
                            try {
                                addDebugLog("🔧 Generating WireGuard cryptographic keys...")
                                val keyPair = KeyPair()
                                exitNodePrivateKey = keyPair.privateKey.toBase64()
                                exitNodePublicKey = keyPair.publicKey.toBase64()

                                addDebugLog("✅ Generated real WireGuard keys")
                                addDebugLog("Private key: ${exitNodePrivateKey.length} chars - ${exitNodePrivateKey.take(10)}...")
                                addDebugLog("Public key: ${exitNodePublicKey.length} chars - ${exitNodePublicKey.take(10)}...")

                                // Verify keys are different and valid
                                if (exitNodePrivateKey == exitNodePublicKey) {
                                    addDebugLog("❌ ERROR: Private and public keys are identical!")
                                }
                                if (exitNodePrivateKey.isBlank() || exitNodePublicKey.isBlank()) {
                                    addDebugLog("❌ ERROR: Generated empty keys!")
                                }

                                addDebugLog("✅ Key generation completed successfully")
                                Toast.makeText(this@MainActivity, "Generated WireGuard keys successfully", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                addDebugLog("❌ Key generation failed: ${e.message}")
                                addDebugLog("❌ Exception: ${e.javaClass.simpleName}")
                                Toast.makeText(this@MainActivity, "Key generation failed: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        },
                        onStartTunnel = {
                            startExitNodeTunnel(
                                exitNodePrivateKey, exitNodePublicKey, peerPublicKey
                            )
                        },
                        onStopTunnel = { stopTunnel() },
                        isConnected = isConnected,
                        peerPublicKey = peerPublicKey,
                        onPeerPublicKeyChange = { peerPublicKey = it }
                    )
                }
                "Client" -> {
                    ClientConfiguration(
                        publicKey = clientPublicKey,
                        onGenerateKeys = {
                            try {
                                val keyPair = KeyPair()
                                clientPrivateKey = keyPair.privateKey.toBase64()
                                clientPublicKey = keyPair.publicKey.toBase64()
                                addDebugLog("✅ Generated real WireGuard client keys")
                                addDebugLog("Client private key: ${clientPrivateKey.length} chars")
                                addDebugLog("Client public key: ${clientPublicKey.length} chars")
                            } catch (e: Exception) {
                                addDebugLog("❌ Client key generation failed: ${e.message}")
                                Toast.makeText(this@MainActivity, "Client key generation failed: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        },
                        onStartTunnel = {
                            startClientTunnel(
                                clientPrivateKey, clientPublicKey, peerPublicKey, peerEndpoint
                            )
                        },
                        onStopTunnel = { stopTunnel() },
                        isConnected = isConnected,
                        peerPublicKey = peerPublicKey,
                        onPeerPublicKeyChange = { peerPublicKey = it },
                        peerEndpoint = peerEndpoint,
                        onPeerEndpointChange = { peerEndpoint = it }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { checkPublicIP() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !currentPublicIP.startsWith("Checking")
            ) {
                Text(if (currentPublicIP.startsWith("Checking")) "Checking..." else "Check My Public IP")
            }
        }
    }

    @Composable
    fun DeviceIPCard(
        ipv4: String,
        ipv6: String,
        onRefresh: () -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "This Device Public IPs",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onRefresh) {
                        Text("🔄", fontSize = 16.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (ipv4.isNotEmpty() && ipv4 != "Fetching..." && ipv4 != "N/A") {
                    KeyDisplay(
                        label = "IPv4 Address",
                        keyValue = ipv4,
                        onCopy = { copyToClipboard("IPv4", ipv4) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (ipv6.isNotEmpty() && ipv6 != "Fetching..." && ipv6 != "N/A") {
                    KeyDisplay(
                        label = "IPv6 Address",
                        keyValue = ipv6,
                        onCopy = { copyToClipboard("IPv6", ipv6) }
                    )
                }

                if ((ipv4 == "Fetching..." || ipv4 == "N/A") && (ipv6 == "Fetching..." || ipv6 == "N/A")) {
                    Text(
                        text = "Loading device IPs...",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }

    @Composable
    fun EnhancedStatusCard(
        connectionStatus: String,
        connectionError: String,
        lastErrorCode: Int,
        handshakeStatus: String,
        publicIP: String,
        onShowDebugLogs: () -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(4.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    connectionStatus.contains("Active") || connectionStatus.contains("Connected") ->
                        MaterialTheme.colorScheme.primaryContainer
                    connectionStatus.contains("Failed") || connectionError.isNotEmpty() ->
                        MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Status", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onShowDebugLogs) {
                        Text("Debug", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("Connection: $connectionStatus", fontSize = 14.sp)

                if (connectionError.isNotEmpty()) {
                    Text(
                        "Error: $connectionError",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (lastErrorCode != 0) {
                    Text(
                        "Error Code: $lastErrorCode ${getErrorCodeDescription(lastErrorCode)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Text("Handshake: $handshakeStatus", fontSize = 14.sp)
                Text("Public IP: $publicIP", fontSize = 14.sp)
            }
        }
    }

    @Composable
    fun DebugLogsCard(
        logs: List<String>,
        onClearLogs: () -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(2.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Debug Logs", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    TextButton(onClick = onClearLogs) {
                        Text("Clear", fontSize = 11.sp)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                if (logs.isEmpty()) {
                    Text("No logs yet...", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    logs.takeLast(10).forEach { log ->
                        Text(
                            text = log,
                            fontSize = 10.sp,
                            color = when {
                                log.contains("❌") || log.contains("ERROR") -> MaterialTheme.colorScheme.error
                                log.contains("✅") || log.contains("SUCCESS") -> Color(0xFF4CAF50)
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }

    private fun getErrorCodeDescription(errorCode: Int): String {
        return when (errorCode) {
            -1 -> "(VPN Permission / TUN Device)"
            -2 -> "(Invalid Configuration)"
            -3 -> "(Network Interface / Port)"
            else -> "(Unknown)"
        }
    }

    private fun addDebugLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        debugLogs = debugLogs + "[$timestamp] $message"
        if (debugLogs.size > 50) {
            debugLogs = debugLogs.takeLast(30)
        }
    }

    private fun validateWireGuardKey(key: String, keyType: String): String? {
        return when {
            key.isBlank() -> "$keyType is empty"
            key.length != 44 -> "$keyType wrong length: ${key.length} (should be 44)"
            !key.endsWith("=") -> "$keyType should end with '='"
            !isValidBase64(key) -> "$keyType contains invalid Base64 characters"
            else -> null // Valid key
        }
    }

    private fun isValidBase64(str: String): Boolean {
        return try {
            // Check if string contains only valid Base64 characters
            val base64Pattern = Regex("^[A-Za-z0-9+/]*={0,2}$")
            base64Pattern.matches(str)
        } catch (e: Exception) {
            false
        }
    }

    private fun generateValidTestKey(): String {
        // Generate a valid test key for debugging
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val randomKey = (1..43).map { chars.random() }.joinToString("") + "="
        return randomKey
    }

    private fun getValidTestKey(): String {
        // Return a known valid WireGuard public key for testing
        // Generated with: wg genkey | wg pubkey
        return "M/xWxGjQgZBmcfrgTOU0zD/zns8ZoVLStH/djO2N+T4="
    }

    private fun getValidTestPrivateKey(): String {
        // Return a known valid WireGuard private key for testing
        // Generated with: wg genkey
        return "oMIHwo/5C2wGSa+gKQm3IvrADEezLbzrJaQzhZrXxX0="
    }

    private fun validateWireGuardConfig(config: String): String? {
        return try {
            when {
                config.isBlank() -> "Config is empty"
                !config.contains("[Interface]") -> "Missing [Interface] section"
                !config.contains("[Peer]") -> "Missing [Peer] section"
                !config.contains("PrivateKey") -> "Missing PrivateKey in [Interface]"
                !config.contains("Address") -> "Missing Address in [Interface]"
                !config.contains("PublicKey") -> "Missing PublicKey in [Peer]"
                !config.contains("AllowedIPs") -> "Missing AllowedIPs in [Peer]"
                else -> {
                    // Additional validation for specific values
                    validateConfigContent(config)
                }
            }
        } catch (e: Exception) {
            "Config validation exception: ${e.message}"
        }
    }


    private fun validateConfigContent(config: String): String? {
        val lines = config.lines().map { it.trim() }.filter { it.isNotEmpty() }

        for (line in lines) {
            when {
                line.startsWith("PrivateKey") -> {
                    val key = line.substringAfter("=").trim()
                    validateWireGuardKey(key, "PrivateKey")?.let { return it }
                }
                line.startsWith("PublicKey") -> {
                    val key = line.substringAfter("=").trim()
                    validateWireGuardKey(key, "PublicKey")?.let { return it }
                }
                line.startsWith("Address") -> {
                    val addresses = line.substringAfter("=").trim()
                    if (!addresses.contains("/")) {
                        return "Address must include CIDR notation (e.g., 10.8.0.1/24)"
                    }
                    // Validate IPv6 format if present
                    if (addresses.contains("::") && !isValidIPv6Format(addresses)) {
                        return "Invalid IPv6 address format in Address"
                    }
                }
                line.startsWith("AllowedIPs") -> {
                    val allowedIPs = line.substringAfter("=").trim()
                    // Validate IPv6 format in AllowedIPs
                    if (allowedIPs.contains("::") && !isValidIPv6Format(allowedIPs)) {
                        return "Invalid IPv6 format in AllowedIPs"
                    }
                }
                line.startsWith("DNS") -> {
                    val dnsServers = line.substringAfter("=").trim()
                    // Validate IPv6 DNS format
                    if (dnsServers.contains("::") && !isValidIPv6Format(dnsServers)) {
                        return "Invalid IPv6 DNS server format"
                    }
                }
                line.startsWith("ListenPort") -> {
                    val port = line.substringAfter("=").trim().toIntOrNull()
                    if (port == null || port <= 0 || port > 65535) {
                        return "Invalid ListenPort: must be 1-65535"
                    }
                }
                line.startsWith("MTU") -> {
                    val mtu = line.substringAfter("=").trim().toIntOrNull()
                    if (mtu == null || mtu < 576 || mtu > 1500) {
                        return "Invalid MTU: must be 576-1500"
                    }
                }
            }
        }
        return null
    }

    private fun isValidIPv6Format(text: String): Boolean {
        return try {
            // Basic IPv6 format validation
            val ipv6Pattern = Regex("""([a-fA-F0-9:]+::?[a-fA-F0-9:]*(/[0-9]+)?)|(::/[0-9]+)""")
            text.split(",").map { it.trim() }.all { part ->
                when {
                    part.contains("::") -> ipv6Pattern.matches(part)
                    else -> true // Not IPv6, skip validation
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    @Composable
    fun ExitNodeConfiguration(
        publicKey: String,
        exitNodeIPv4: String,
        exitNodeIPv6: String,
        onGenerateKeys: () -> Unit,
        onStartTunnel: () -> Unit,
        onStopTunnel: () -> Unit,
        isConnected: Boolean,
        peerPublicKey: String,
        onPeerPublicKeyChange: (String) -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Exit Node Configuration", fontSize = 18.sp, fontWeight = FontWeight.Bold)

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onGenerateKeys,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isConnected
                ) {
                    Text("Generate Keys")
                }

                Spacer(modifier = Modifier.height(8.dp))

                KeyDisplay(
                    label = "Public Key (Share with Client)",
                    keyValue = publicKey,
                    onCopy = { copyToClipboard("Public Key", publicKey) }
                )

                // Add key validation display
                if (publicKey.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    val validation = validateWireGuardKey(publicKey, "Public Key")
                    Text(
                        text = if (validation == null) "✅ Valid key format" else "❌ $validation",
                        fontSize = 10.sp,
                        color = if (validation == null) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                    )
                    // Show actual key for verification
                    if (validation == null) {
                        Text(
                            text = "Key: ${publicKey.take(20)}...${publicKey.takeLast(4)}",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (isConnected) {
                    if (exitNodeIPv4.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        KeyDisplay(
                            label = "Exit Node IPv4",
                            keyValue = exitNodeIPv4,
                            onCopy = { copyToClipboard("Exit IPv4", exitNodeIPv4) }
                        )
                    }

                    if (exitNodeIPv6.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        KeyDisplay(
                            label = "Exit Node IPv6",
                            keyValue = exitNodeIPv6,
                            onCopy = { copyToClipboard("Exit IPv6", exitNodeIPv6) }
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Clients will appear with these IPs",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = peerPublicKey,
                    onValueChange = onPeerPublicKeyChange,
                    label = { Text("Client's Public Key") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isConnected,
                    isError = peerPublicKey.isNotEmpty() && validateWireGuardKey(peerPublicKey, "Client Key") != null
                )

                // Add client key validation display
                if (peerPublicKey.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    val validation = validateWireGuardKey(peerPublicKey, "Client Key")
                    Text(
                        text = if (validation == null) "✅ Valid client key" else "❌ $validation",
                        fontSize = 10.sp,
                        color = if (validation == null) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                    )

                    if (validation != null) {
                        Text(
                            text = "Example valid key: ${getValidTestKey()}",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = { onPeerPublicKeyChange(getValidTestKey()) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text("Use Valid Test Key", fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (!isConnected) {
                    Button(
                        onClick = onStartTunnel,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = publicKey.isNotEmpty() && peerPublicKey.isNotEmpty()
                    ) {
                        Text("Start Exit Node (NAT Enabled)")
                    }
                } else {
                    Button(
                        onClick = onStopTunnel,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Stop Exit Node")
                    }
                }
            }
        }
    }

    @Composable
    fun ClientConfiguration(
        publicKey: String,
        onGenerateKeys: () -> Unit,
        onStartTunnel: () -> Unit,
        onStopTunnel: () -> Unit,
        isConnected: Boolean,
        peerPublicKey: String,
        onPeerPublicKeyChange: (String) -> Unit,
        peerEndpoint: String,
        onPeerEndpointChange: (String) -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Client Configuration", fontSize = 18.sp, fontWeight = FontWeight.Bold)

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onGenerateKeys,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isConnected
                ) {
                    Text("Generate Keys")
                }

                Spacer(modifier = Modifier.height(8.dp))

                KeyDisplay(
                    label = "Public Key (Share with Exit Node)",
                    keyValue = publicKey,
                    onCopy = { copyToClipboard("Public Key", publicKey) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = peerPublicKey,
                    onValueChange = onPeerPublicKeyChange,
                    label = { Text("Exit Node's Public Key") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isConnected
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = peerEndpoint,
                    onValueChange = onPeerEndpointChange,
                    label = { Text("Exit Node Endpoint") },
                    placeholder = { Text("203.0.113.1:51820 or [2001:db8::1]:51820") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isConnected
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (!isConnected) {
                    Button(
                        onClick = onStartTunnel,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = publicKey.isNotEmpty() && peerPublicKey.isNotEmpty() && peerEndpoint.isNotEmpty()
                    ) {
                        Text("Connect to Exit Node")
                    }
                } else {
                    Button(
                        onClick = onStopTunnel,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Disconnect")
                    }
                }
            }
        }
    }

    @Composable
    fun KeyDisplay(label: String, keyValue: String, onCopy: () -> Unit) {
        Column {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (keyValue.isNotEmpty() && keyValue != "Fetching...") {
                        if (keyValue.length > 32) keyValue.take(32) + "..." else keyValue
                    } else "Not generated",
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f)
                )
                if (keyValue.isNotEmpty() && keyValue != "Fetching...") {
                    TextButton(onClick = onCopy) {
                        Text("Copy", fontSize = 12.sp)
                    }
                }
            }
        }
    }

    // ==================== Functions ====================

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        }
    }

    private fun startVpnService() {
        val serviceIntent = Intent(this, YankVpnService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopVpnService() {
        stopService(Intent(this, YankVpnService::class.java))
    }

    private fun fetchDevicePublicIPs() {
        lifecycleScope.launch(Dispatchers.IO) {
            // IPv4
            try {
                val url4 = URL("https://api.ipify.org?format=text")
                val ipv4 = url4.openConnection().apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                }.getInputStream().bufferedReader().use { it.readText() }

                withContext(Dispatchers.Main) {
                    deviceIPv4 = ipv4.trim()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { deviceIPv4 = "N/A" }
            }

            // IPv6
            try {
                val url6 = URL("https://api64.ipify.org?format=text")
                val ipv6 = url6.openConnection().apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                }.getInputStream().bufferedReader().use { it.readText() }

                withContext(Dispatchers.Main) {
                    deviceIPv6 = ipv6.trim()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { deviceIPv6 = "N/A" }
            }
        }
    }

    private fun fetchExitNodePublicIPs() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url4 = URL("https://api.ipify.org?format=text")
                val ipv4 = url4.openConnection().apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                }.getInputStream().bufferedReader().use { it.readText() }
                withContext(Dispatchers.Main) { exitNodeIPv4 = ipv4.trim() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { exitNodeIPv4 = "" }
            }

            try {
                val url6 = URL("https://api64.ipify.org?format=text")
                val ipv6 = url6.openConnection().apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                }.getInputStream().bufferedReader().use { it.readText() }
                withContext(Dispatchers.Main) { exitNodeIPv6 = ipv6.trim() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { exitNodeIPv6 = "" }
            }
        }
    }

    private fun startExitNodeTunnel(exitNodePrivateKey: String, exitNodePublicKey: String, clientPublicKey: String) {
        Log.d(TAG, "=== Exit Node Startup Debug ===")

        // Clear previous error state
        connectionError = ""
        lastErrorCode = 0
        addDebugLog("🚀 Starting exit node...")

        Log.d(TAG, "Private key length: ${exitNodePrivateKey.length}")
        Log.d(TAG, "Public key length: ${exitNodePublicKey.length}")
        Log.d(TAG, "Client key length: ${clientPublicKey.length}")

        if (exitNodePrivateKey.isEmpty() || clientPublicKey.isEmpty()) {
            Log.e(TAG, "❌ Keys validation failed!")
            Log.e(TAG, "Private key empty: ${exitNodePrivateKey.isEmpty()}")
            Log.e(TAG, "Client key empty: ${clientPublicKey.isEmpty()}")
            connectionError = "Keys missing - generate keys and enter client public key"
            addDebugLog("❌ Keys validation failed")
            Toast.makeText(this, "Please generate keys and enter client's public key", Toast.LENGTH_SHORT).show()
            return
        }

        // Validate key formats with detailed checking
        val privateKeyValidation = validateWireGuardKey(exitNodePrivateKey, "Private Key")
        if (privateKeyValidation != null) {
            Log.e(TAG, "❌ Private key validation failed: $privateKeyValidation")
            connectionError = "Private key invalid: $privateKeyValidation"
            lastErrorCode = -2
            addDebugLog("❌ Private key validation: $privateKeyValidation")
            Toast.makeText(this, "Private key invalid: $privateKeyValidation", Toast.LENGTH_LONG).show()
            return
        }

        val clientKeyValidation = validateWireGuardKey(clientPublicKey, "Client Public Key")
        if (clientKeyValidation != null) {
            Log.e(TAG, "❌ Client public key validation failed: $clientKeyValidation")
            connectionError = "Client key invalid: $clientKeyValidation"
            lastErrorCode = -2
            addDebugLog("❌ Client key validation: $clientKeyValidation")
            Toast.makeText(this, "Client key invalid: $clientKeyValidation", Toast.LENGTH_LONG).show()
            return
        }

        addDebugLog("✅ Key validation passed")

        // Set up WireGuard callback
        WireGuardNetstack.onStatusUpdate = { status ->
            runOnUiThread {
                connectionStatus = status
                addDebugLog("📡 $status")
            }
        }

        WireGuardNetstack.onErrorUpdate = { error, code ->
            runOnUiThread {
                connectionError = error
                lastErrorCode = code
                connectionStatus = "Failed to start"
                addDebugLog("❌ Error $code: $error")
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting VPN service...")
                withContext(Dispatchers.Main) {
                    connectionStatus = "Starting Exit Node..."
                    addDebugLog("🔧 Starting VPN service...")
                }

                // Check VPN permission first
                val vpnIntent = VpnService.prepare(this@MainActivity)
                if (vpnIntent != null) {
                    Log.e(TAG, "❌ VPN permission not granted!")
                    withContext(Dispatchers.Main) {
                        connectionStatus = "VPN permission required"
                        connectionError = "VPN permission not granted - please enable in settings"
                        lastErrorCode = -1
                        addDebugLog("❌ VPN permission not granted")
                        Toast.makeText(this@MainActivity, "VPN permission required. Please grant it.", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) { addDebugLog("✅ VPN permission granted") }

                startVpnService()
                delay(1000) // Give more time for service to start

                Log.d(TAG, "Generating configuration...")
                withContext(Dispatchers.Main) { addDebugLog("📝 Generating configuration...") }

                val config = generateExitNodeConfig(exitNodePrivateKey, exitNodePublicKey, clientPublicKey)
                Log.d(TAG, "Generated configuration:\n$config")
                withContext(Dispatchers.Main) { addDebugLog("📋 Config generated (${config.length} chars)") }

                // Validate the generated configuration
                val configValidation = validateWireGuardConfig(config)
                if (configValidation != null) {
                    Log.e(TAG, "❌ Generated config validation failed: $configValidation")
                    withContext(Dispatchers.Main) {
                        connectionError = "Config generation error: $configValidation"
                        lastErrorCode = -2
                        addDebugLog("❌ Config validation failed: $configValidation")
                        stopVpnService()
                        Toast.makeText(this@MainActivity, "Config error: $configValidation", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) { addDebugLog("✅ Config validation passed") }

                Log.d(TAG, "Configuration validated, starting WireGuard...")

                withContext(Dispatchers.Main) { addDebugLog("🔗 Starting WireGuard netstack...") }

                // Add detailed logging before calling Go
                Log.d(TAG, "About to call WireGuard Go function with:")
                Log.d(TAG, "  - Config length: ${config.length}")
                Log.d(TAG, "  - Is exit node: true")
                Log.d(TAG, "  - Private key starts: ${exitNodePrivateKey.take(10)}")
                Log.d(TAG, "  - Client key starts: ${clientPublicKey.take(10)}")

                withContext(Dispatchers.Main) {
                    addDebugLog("📡 Calling Go WireGuard function...")
                    addDebugLog("🔍 Config: ${config.lines().size} lines, ${config.length} chars")
                }

                val success = WireGuardNetstack.start(config, isExitNode = true)

                withContext(Dispatchers.Main) {
                    if (success) {
                        Log.d(TAG, "✅ Exit node started successfully!")
                        isConnected = true
                        connectionStatus = "Exit Node Active (NAT Forwarding Enabled)"
                        connectionError = ""
                        lastErrorCode = 0
                        addDebugLog("✅ Exit node started successfully!")
                        startHandshakeMonitoring()
                        fetchExitNodePublicIPs()
                        Toast.makeText(this@MainActivity, "Exit Node started!", Toast.LENGTH_SHORT).show()
                    } else {
                        Log.e(TAG, "❌ Exit node failed to start")
                        if (connectionError.isEmpty()) {
                            connectionError = "Unknown startup failure - check debug logs"
                        }
                        connectionStatus = "Failed to start"
                        addDebugLog("❌ Exit node startup failed")
                        stopVpnService()
                        Toast.makeText(this@MainActivity, "Failed to start exit node. Check error details above.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "💥 Exception in startExitNodeTunnel: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    connectionStatus = "Error occurred"
                    connectionError = "Exception: ${e.message}"
                    lastErrorCode = -999
                    addDebugLog("💥 Exception: ${e.message}")
                    stopVpnService()
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startClientTunnel(clientPrivateKey: String, clientPublicKey: String, exitNodePublicKey: String, exitNodeEndpoint: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { connectionStatus = "Connecting..." }
                startVpnService()
                delay(500)

                val config = generateClientConfig(clientPrivateKey, clientPublicKey, exitNodePublicKey, exitNodeEndpoint)
                val success = WireGuardNetstack.start(config, isExitNode = false)

                withContext(Dispatchers.Main) {
                    if (success) {
                        isConnected = true
                        connectionStatus = "Connected to Exit Node"
                        startHandshakeMonitoring()
                        delay(2000)
                        checkPublicIP()
                    } else {
                        connectionStatus = "Failed to connect"
                        stopVpnService()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    connectionStatus = "Error: ${e.message}"
                    stopVpnService()
                }
            }
        }
    }

    private fun stopTunnel() {
        lifecycleScope.launch(Dispatchers.IO) {
            WireGuardNetstack.stop()
            stopVpnService()
            delay(500)
            withContext(Dispatchers.Main) {
                isConnected = false
                connectionStatus = "Disconnected"
                handshakeStatus = "N/A"
                currentPublicIP = "Not connected"
                exitNodeIPv4 = ""
                exitNodeIPv6 = ""
            }
        }
    }

    private fun startHandshakeMonitoring() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isConnected) {
                try {
                    val status = WireGuardNetstack.getStatus()
                    val lastHandshake = parseHandshakeTime(status)
                    withContext(Dispatchers.Main) { handshakeStatus = "Last: $lastHandshake" }
                    delay(5000)
                } catch (e: Exception) {
                    delay(5000)
                }
            }
        }
    }

    private fun parseHandshakeTime(status: String): String {
        return try {
            val match = Regex("latest_handshake=(\\d+)").find(status)
            if (match != null) {
                val timestamp = match.groupValues[1].toLongOrNull() ?: 0
                if (timestamp == 0L) "Never" else {
                    val secondsAgo = (System.currentTimeMillis() / 1000) - timestamp
                    when {
                        secondsAgo < 60 -> "${secondsAgo}s ago"
                        secondsAgo < 3600 -> "${secondsAgo / 60}m ago"
                        else -> "${secondsAgo / 3600}h ago"
                    }
                }
            } else "Unknown"
        } catch (e: Exception) {
            "Error"
        }
    }

    private fun checkPublicIP() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { currentPublicIP = "Checking..." }
                val url = URL("https://api64.ipify.org?format=text")
                val publicIP = url.openConnection().apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                }.getInputStream().bufferedReader().use { it.readText() }

                withContext(Dispatchers.Main) {
                    currentPublicIP = publicIP.trim()
                    Toast.makeText(this@MainActivity, "Your IP: ${publicIP.trim()}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    currentPublicIP = if (isConnected) "Check failed" else "Not connected"
                }
            }
        }
    }

    private fun generateExitNodeConfig(privateKey: String, publicKey: String, clientPubKey: String): String {
        // Create properly formatted WireGuard configuration
        val config = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = $privateKey")
            appendLine("Address = fd15:53b6:dead::1/64, 10.8.0.1/24")
            appendLine("ListenPort = 51820")
            appendLine("MTU = 1420")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = $clientPubKey")
            appendLine("AllowedIPs = fd15:53b6:dead::2/128, 10.8.0.2/32, 0.0.0.0/0, ::/0")  // ✅ Full routing
            appendLine("PersistentKeepalive = 25")
        }.trim()

        Log.d(TAG, "Generated properly formatted WireGuard config:\n$config")
        return config
    }

    private fun generateClientConfig(privateKey: String, publicKey: String, exitPubKey: String, endpoint: String): String {
        val config = """[Interface]
PrivateKey = $privateKey
Address = fd15:53b6:dead::2/64, 10.8.0.2/24
DNS = 8.8.8.8, 2001:4860:4860::8888
MTU = 1420

[Peer]
PublicKey = $exitPubKey
Endpoint = $endpoint
AllowedIPs = 0.0.0.0/0, ::/0
PersistentKeepalive = 25"""

        Log.d(TAG, "Generated client config:\n$config")
        return config
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
    }
}
