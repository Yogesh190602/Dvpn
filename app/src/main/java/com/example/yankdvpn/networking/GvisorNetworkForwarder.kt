package com.example.yankdvpn.networking

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.celzero.firestack.intra.Bridge
import com.celzero.firestack.intra.DefaultDNS
import com.celzero.firestack.intra.Intra
import com.celzero.firestack.intra.Tunnel
import com.celzero.firestack.settings.Settings
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket

/**
 * GvisorNetworkForwarder - Handles network packet forwarding using gVisor netstack
 * This runs alongside the existing WireGuard tunnel without interfering with it
 */
class GvisorNetworkForwarder(
    private val vpnService: VpnService,
    private val isExitNode: Boolean = false
) {
    
    companion object {
        private const val TAG = "GvisorForwarder"
        private const val TUN_MTU = 1500L
    }
    
    private var tunInterface: ParcelFileDescriptor? = null
    private var tunnel: Tunnel? = null
    private var bridge: GvisorTrafficBridge? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var bytesForwarded = 0L
    private var packetsProcessed = 0L
    private var activeConnections = 0
    
    /**
     * Start gVisor netstack for traffic forwarding
     * This complements the WireGuard tunnel by handling packet routing
     */
    fun startNetworkForwarding(
        tunFd: ParcelFileDescriptor,
        onStatsUpdate: (bytesForwarded: Long, packets: Long, connections: Int) -> Unit = { _, _, _ -> }
    ) {
        scope.launch {
            try {
                Log.i(TAG, "Starting gVisor netstack for ${if (isExitNode) "exit node" else "client"} traffic forwarding")
                
                // Get file descriptor
                val fd = getTunFd(tunFd)
                
                // Create gVisor bridge for packet handling
                bridge = GvisorTrafficBridge(vpnService, isExitNode) { bytes, packets, connections ->
                    bytesForwarded = bytes
                    packetsProcessed = packets
                    activeConnections = connections
                    onStatsUpdate(bytes, packets, connections)
                }
                
                // Initialize gVisor netstack
                val defaultDNS = Intra.newBuiltinDefaultDNS()
                val session = "working-dvpn-netstack"
                val resolver = if (isExitNode) "8.8.8.8,1.1.1.1" else "2001:4860:4860::8888,2001:4860:4860::8844"
                val engine = Settings.Ns46
                
                // Create tunnel with gVisor
                tunnel = Intra.newTunnel(fd, TUN_MTU, session, resolver, defaultDNS, bridge)
                tunnel?.restart(fd, TUN_MTU, engine)
                
                Log.i(TAG, "gVisor netstack started successfully")
                
                if (isExitNode) {
                    startExitNodeForwarding()
                } else {
                    startClientForwarding()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start gVisor netstack: ${e.message}", e)
            }
        }
    }
    
    /**
     * Stop network forwarding
     */
    fun stopNetworkForwarding() {
        scope.launch {
            try {
                Log.i(TAG, "Stopping gVisor netstack")
                tunnel?.disconnect()
                bridge?.cleanup()
                tunnel = null
                bridge = null
                bytesForwarded = 0L
                packetsProcessed = 0L
                activeConnections = 0
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping netstack: ${e.message}")
            }
        }
    }
    
    /**
     * Get statistics
     */
    fun getStats(): NetworkStats {
        return NetworkStats(
            bytesForwarded = bytesForwarded,
            packetsProcessed = packetsProcessed,
            activeConnections = activeConnections,
            isActive = tunnel != null
        )
    }
    
    private fun getTunFd(tunInterface: ParcelFileDescriptor): Long {
        return try {
            val fileDescriptor = tunInterface.fileDescriptor
            val fdField = fileDescriptor.javaClass.getDeclaredField("fd")
            fdField.isAccessible = true
            fdField.getInt(fileDescriptor).toLong()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get TUN fd: ${e.message}")
            -1L
        }
    }
    
    /**
     * Exit node forwarding: Route client traffic to internet
     */
    private suspend fun startExitNodeForwarding() {
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Starting exit node traffic forwarding")
            // gVisor handles the actual packet routing through the Bridge interface
            // This is where we could add additional exit node specific logic
        }
    }
    
    /**
     * Client forwarding: Route device traffic through exit node
     */
    private suspend fun startClientForwarding() {
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Starting client traffic forwarding")
            // gVisor handles the actual packet routing through the Bridge interface
            // This is where we could add additional client specific logic
        }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        scope.cancel()
        tunnel?.disconnect()
        bridge?.cleanup()
    }
}

/**
 * Network statistics data class
 */
data class NetworkStats(
    val bytesForwarded: Long,
    val packetsProcessed: Long,
    val activeConnections: Int,
    val isActive: Boolean
)