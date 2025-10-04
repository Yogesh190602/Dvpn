package com.example.yankdvpn

import android.util.Log
import netstack.Netstack

object WireGuardNetstack {

    private const val TAG = "WireGuardNetstack"
    private var isRunning = false

    // Callback for UI updates
    var onStatusUpdate: ((String) -> Unit)? = null
    var onErrorUpdate: ((String, Int) -> Unit)? = null

    /**
     * Start WireGuard with netstack backend
     * @param config WireGuard configuration string
     * @param isExitNode true if this device should act as exit node with NAT
     * @return true if started successfully
     */
    fun start(config: String, isExitNode: Boolean): Boolean {
        return try {
            onStatusUpdate?.invoke("Initializing WireGuard...")
            Log.d(TAG, "=== Starting WireGuard ===")
            Log.d(TAG, "Config length: ${config.length}")
            Log.d(TAG, "Is exit node: $isExitNode")
            Log.d(TAG, "Full config:\n$config")

            // Validate config before sending to Go
            if (config.isBlank()) {
                Log.e(TAG, "❌ Config is empty!")
                onErrorUpdate?.invoke("Configuration is empty", -2)
                return false
            }

            if (!config.contains("[Interface]")) {
                Log.e(TAG, "❌ Config missing [Interface] section!")
                onErrorUpdate?.invoke("Invalid config: missing [Interface]", -2)
                return false
            }

            if (!config.contains("PrivateKey")) {
                Log.e(TAG, "❌ Config missing PrivateKey!")
                onErrorUpdate?.invoke("Invalid config: missing PrivateKey", -2)
                return false
            }

            onStatusUpdate?.invoke("Creating network interface...")

            // Call Go function - convert boolean to int (1 = true, 0 = false)
            Log.d(TAG, "Calling Netstack.startWireGuardWithNetstack()...")
            val result = Netstack.StartWireGuardWithNetstack(config, if (isExitNode) 1 else 0)
            Log.d(TAG, "Go function returned: $result")

            isRunning = result == 0L

            when (result.toInt()) {
                0 -> {
                    Log.d(TAG, "✅ WireGuard started successfully")
                    onStatusUpdate?.invoke("WireGuard started successfully!")
                }

                -1 -> {
                    val errorMsg = "Failed to create network interface"
                    Log.e(TAG, "❌ ERROR CODE -1: $errorMsg")
                    onErrorUpdate?.invoke("$errorMsg - Check VPN permission", -1)
                }

                -2 -> {
                    val errorMsg = "Invalid WireGuard configuration"
                    Log.e(TAG, "❌ ERROR CODE -2: $errorMsg")
                    Log.e(TAG, "Failed config:\n$config")
                    onErrorUpdate?.invoke("$errorMsg - Check key format", -2)
                }

                -3 -> {
                    val errorMsg = "Failed to start network interface"
                    Log.e(TAG, "❌ ERROR CODE -3: $errorMsg")
                    onErrorUpdate?.invoke("$errorMsg - Port/network conflict", -3)
                }

                else -> {
                    val errorMsg = "Unknown error occurred"
                    Log.e(TAG, "❌ UNKNOWN ERROR CODE: $result")
                    onErrorUpdate?.invoke("$errorMsg (code: $result)", result.toInt())
                }
            }

            isRunning

        } catch (e: Exception) {
            val errorMsg = "Exception: ${e.message}"
            Log.e(TAG, "💥 Exception starting WireGuard: ${e.message}", e)
            onErrorUpdate?.invoke(errorMsg, -999)
            false
        }
    }

    /**
     * Stop WireGuard netstack
     */
    fun stop() {
        try {
            Log.d(TAG, "Stopping WireGuard")
            Netstack.stopWireGuard()
            isRunning = false
        } catch (e: Exception) {
            Log.e(TAG, "Exception stopping WireGuard: ${e.message}", e)
        }
    }

    /**
     * Get current WireGuard status
     * @return status string with peer info and handshakes
     */
    fun getStatus(): String {
        return try {
            if (!isRunning) {
                "Stopped"
            } else {
                val status = Netstack.getWireGuardStatus()
                status ?: "No status available"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception getting status: ${e.message}", e)
            "Error"
        }
    }

    /**
     * Check if WireGuard is currently running
     */
    fun isRunning(): Boolean = isRunning
}
