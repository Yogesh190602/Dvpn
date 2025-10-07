package com.example.yankdvpn.networking

import android.net.VpnService
import android.util.Log
import com.celzero.firestack.intra.Bridge
import com.celzero.firestack.intra.Mark
import com.celzero.firestack.intra.PreMark
import com.celzero.firestack.intra.SocketSummary
import com.celzero.firestack.backend.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * GvisorTrafficBridge - Handles actual packet forwarding using gVisor netstack
 * This bridges network traffic between the TUN interface and real network
 */
class GvisorTrafficBridge(
    private val vpnService: VpnService,
    private val isExitNode: Boolean,
    private val onStatsUpdate: (bytesForwarded: Long, packetsProcessed: Long, activeConnections: Int) -> Unit = { _, _, _ -> }
) : Bridge {

    companion object {
        private const val TAG = "GvisorTrafficBridge"
    }

    private val bytesForwarded = AtomicLong(0)
    private val packetsProcessed = AtomicLong(0)
    private val activeConnections = AtomicLong(0)
    private val scope = CoroutineScope(Dispatchers.IO)

    // ------------------------- Socket Protection -------------------------
    override fun bind4(who: String?, addrport: String?, fd: Long) {
        Log.v(TAG, "bind4: $who -> $addrport")
        vpnService.protect(fd.toInt())
    }

    override fun bind6(who: String?, addrport: String?, fd: Long) {
        Log.v(TAG, "bind6: $who -> $addrport") 
        vpnService.protect(fd.toInt())
    }

    override fun protect(who: String?, fd: Long) {
        Log.v(TAG, "protect: $who (fd: $fd)")
        vpnService.protect(fd.toInt())
    }

    // ------------------------- Traffic Flow Management -------------------------
    override fun flow(
        uid: Int,
        proto: Int, 
        src: Gostr?,
        dst: Gostr?,
        realIp: Gostr?,
        proxyAddr: Gostr?,
        proxyDomain: Gostr?,
        procInfo: Gostr?
    ): Mark {
        val srcAddr = src?.string() ?: "unknown"
        val dstAddr = dst?.string() ?: "unknown"
        
        Log.v(TAG, "flow: $srcAddr -> $dstAddr (proto: $proto, uid: $uid)")
        
        // Update packet count
        packetsProcessed.incrementAndGet()
        
        if (isExitNode) {
            return handleExitNodeFlow(uid, proto, srcAddr, dstAddr)
        } else {
            return handleClientFlow(uid, proto, srcAddr, dstAddr)
        }
    }

    override fun inflow(pid: Int, proto: Int, src: Gostr?, dst: Gostr?): Mark {
        val srcAddr = src?.string() ?: "unknown"
        val dstAddr = dst?.string() ?: "unknown"
        
        Log.v(TAG, "inflow: $srcAddr -> $dstAddr (proto: $proto)")
        
        packetsProcessed.incrementAndGet()
        return Mark()
    }

    override fun preflow(pid: Int, proto: Int, src: Gostr?, dst: Gostr?): PreMark {
        Log.v(TAG, "preflow: ${src?.string()} -> ${dst?.string()}")
        return PreMark()
    }

    override fun postFlow(mark: Mark?) {
        // Update stats after packet processing
        scope.launch {
            updateStats()
        }
    }

    // ------------------------- Exit Node Flow Handling -------------------------
    private fun handleExitNodeFlow(uid: Int, proto: Int, srcAddr: String, dstAddr: String): Mark {
        // For exit node: forward client traffic to internet
        Log.d(TAG, "Exit node forwarding: $srcAddr -> $dstAddr")
        
        return when {
            isWireGuardTraffic(srcAddr, dstAddr) -> {
                // Let WireGuard traffic pass through normally
                Log.v(TAG, "WireGuard traffic detected, allowing")
                Mark()
            }
            isLocalTraffic(dstAddr) -> {
                // Local traffic, don't forward
                Log.v(TAG, "Local traffic, not forwarding")
                Mark()
            }
            else -> {
                // Forward to internet
                Log.v(TAG, "Forwarding to internet: $dstAddr")
                bytesForwarded.addAndGet(estimatePacketSize(proto))
                Mark()
            }
        }
    }

    // ------------------------- Client Flow Handling -------------------------
    private fun handleClientFlow(uid: Int, proto: Int, srcAddr: String, dstAddr: String): Mark {
        // For client: route traffic through exit node
        Log.d(TAG, "Client routing: $srcAddr -> $dstAddr")
        
        return when {
            isWireGuardTraffic(srcAddr, dstAddr) -> {
                // Let WireGuard traffic pass through normally
                Log.v(TAG, "WireGuard traffic detected, allowing")
                Mark()
            }
            isLocalTraffic(dstAddr) -> {
                // Local traffic, don't route
                Log.v(TAG, "Local traffic, not routing")
                Mark()
            }
            else -> {
                // Route through VPN tunnel
                Log.v(TAG, "Routing through VPN: $dstAddr")
                bytesForwarded.addAndGet(estimatePacketSize(proto))
                Mark()
            }
        }
    }

    // ------------------------- Connection Management -------------------------
    override fun onSocketClosed(summary: SocketSummary?) {
        summary?.let {
            Log.v(TAG, "Socket closed: ${it.id}, rx=${it.rx}, tx=${it.tx}")
            bytesForwarded.addAndGet(it.rx + it.tx)
            activeConnections.decrementAndGet()
            
            scope.launch {
                updateStats()
            }
        }
    }

    // ------------------------- DNS Handling -------------------------
    override fun onQuery(domain: Gostr?, type: Gostr?, fd: Long): DNSOpts {
        val domainStr = domain?.string() ?: "unknown"
        Log.v(TAG, "DNS query: $domainStr")
        return DNSOpts()
    }

    override fun onResponse(summary: DNSSummary?) {
        summary?.let {
            Log.v(TAG, "DNS response processed")
        }
    }

    override fun onUpstreamAnswer(summary: DNSSummary?, blocklist: Gostr?): DNSOpts {
        return DNSOpts()
    }

    // ------------------------- Logging -------------------------
    override fun log(level: Int, msg: Gostr?) {
        val message = msg?.string() ?: ""
        when (level) {
            0 -> Log.v(TAG, message)
            1 -> Log.d(TAG, message)  
            2 -> Log.i(TAG, message)
            3 -> Log.w(TAG, message)
            4 -> Log.e(TAG, message)
            else -> Log.v(TAG, message)
        }
    }

    // ------------------------- Proxy/Service Handlers (Stubs) -------------------------
    override fun onDNSAdded(dns: Gostr?) {
        Log.v(TAG, "DNS added: ${dns?.string()}")
    }

    override fun onDNSRemoved(dns: Gostr?) {
        Log.v(TAG, "DNS removed: ${dns?.string()}")
    }

    override fun onDNSStopped() {
        Log.v(TAG, "DNS stopped")
    }

    override fun onProxiesStopped() {
        Log.v(TAG, "Proxies stopped")
    }

    override fun onProxyAdded(proxy: Gostr?) {
        Log.v(TAG, "Proxy added: ${proxy?.string()}")
    }

    override fun onProxyRemoved(proxy: Gostr?) {
        Log.v(TAG, "Proxy removed: ${proxy?.string()}")
    }

    override fun onProxyStopped(proxy: Gostr?) {
        Log.v(TAG, "Proxy stopped: ${proxy?.string()}")
    }

    override fun onSvcComplete(summary: ServerSummary?) {
        Log.v(TAG, "Service complete")
    }

    override fun svcRoute(s1: String?, s2: String?, s3: String?, s4: String?, s5: String?): Tab {
        return Tab()
    }

    // ------------------------- Helper Methods -------------------------
    private fun isWireGuardTraffic(srcAddr: String, dstAddr: String): Boolean {
        // Check if this is WireGuard protocol traffic (UDP port 51820)
        return dstAddr.contains(":51820") || srcAddr.contains(":51820")
    }

    private fun isLocalTraffic(addr: String): Boolean {
        // Check for local/private addresses including WireGuard tunnel IPs
        return addr.startsWith("127.") || 
               addr.startsWith("192.168.") || 
               addr.startsWith("10.") ||
               addr.startsWith("172.16.") ||
               addr.startsWith("::1") ||
               addr.startsWith("fd86:ea04:1111:")
    }

    private fun estimatePacketSize(proto: Int): Long {
        // Rough estimation: TCP ~1500, UDP ~1200
        return if (proto == 6) 1500L else 1200L
    }

    private fun updateStats() {
        onStatsUpdate(
            bytesForwarded.get(),
            packetsProcessed.get(), 
            activeConnections.get().toInt()
        )
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        Log.i(TAG, "Cleaning up gVisor traffic bridge")
        // Reset counters
        bytesForwarded.set(0)
        packetsProcessed.set(0)
        activeConnections.set(0)
    }
}