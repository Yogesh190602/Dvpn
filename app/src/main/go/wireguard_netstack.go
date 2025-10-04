package netstack

import (
    "fmt"
    "log"
    "net/netip"
    "strings"

    "golang.zx2c4.com/wireguard/conn"
    "golang.zx2c4.com/wireguard/device"
    "golang.zx2c4.com/wireguard/tun/netstack"
)

// Global variables
var (
    wgDevice  *device.Device
    tunDevice *netstack.Net
)

// StartWireGuardWithNetstack starts WireGuard with netstack
func StartWireGuardWithNetstack(configStr string, isExitNode int) int64 {
    isExit := isExitNode == 1

    log.Printf("=== Go WireGuard Starting ===")
    log.Printf("Config: %d chars, Exit node: %v", len(configStr), isExit)

    if configStr == "" {
        log.Printf("❌ Empty config")
        return -2
    }

    // Create netstack TUN
    tunDev, tun, err := netstack.CreateNetTUN(
        []netip.Addr{
            netip.MustParseAddr("10.8.0.1"),
            netip.MustParseAddr("fd15:53b6:dead::1"),
        },
        []netip.Addr{
            netip.MustParseAddr("8.8.8.8"),
            netip.MustParseAddr("2001:4860:4860::8888"),
        },
        1420,
    )
    if err != nil {
        log.Printf("❌ TUN creation failed: %v", err)
        return -1
    }

    tunDevice = tun

    // Create WireGuard device
    logger := device.NewLogger(device.LogLevelVerbose, "WG: ")
    wgDevice = device.NewDevice(tunDev, conn.NewDefaultBind(), logger)

    // Convert and apply config
    ipcConfig := convertToIpcFormat(configStr)
    log.Printf("IPC config:\n%s", ipcConfig)

    if err := wgDevice.IpcSet(ipcConfig); err != nil {
        log.Printf("❌ Config failed: %v", err)
        wgDevice.Close()
        wgDevice = nil
        return -2
    }

    // Bring up
    if err := wgDevice.Up(); err != nil {
        log.Printf("❌ Device up failed: %v", err)
        wgDevice.Close()
        wgDevice = nil
        return -3
    }

    log.Printf("✅ WireGuard started (exit: %v)", isExit)
    return 0
}

func convertToIpcFormat(config string) string {
    var ipcLines []string
    var currentSection string
    var peerStarted bool

    for _, line := range strings.Split(config, "\n") {
        line = strings.TrimSpace(line)
        if line == "" {
            continue
        }

        if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
            currentSection = strings.ToLower(strings.Trim(line, "[]"))
            continue
        }

        if strings.Contains(line, "=") {
            parts := strings.SplitN(line, "=", 2)
            if len(parts) != 2 {
                continue
            }

            key := strings.TrimSpace(parts[0])
            value := strings.TrimSpace(parts[1])

            switch currentSection {
            case "interface":
                switch strings.ToLower(key) {
                case "privatekey":
                    ipcLines = append(ipcLines, "private_key="+value)
                case "listenport":
                    ipcLines = append(ipcLines, "listen_port="+value)
                }

            case "peer":
                switch strings.ToLower(key) {
                case "publickey":
                    if !peerStarted {
                        ipcLines = append(ipcLines, "replace_peers=true")
                        peerStarted = true
                    }
                    ipcLines = append(ipcLines, "public_key="+value)

                case "allowedips":
                    for _, ip := range strings.Split(value, ",") {
                        ip = strings.TrimSpace(ip)
                        if ip != "" {
                            ipcLines = append(ipcLines, "allowed_ip="+ip)
                        }
                    }

                case "endpoint":
                    ipcLines = append(ipcLines, "endpoint="+value)

                case "persistentkeepalive":
                    ipcLines = append(ipcLines, "persistent_keepalive_interval="+value)
                }
            }
        }
    }

    return strings.Join(ipcLines, "\n")
}

// StopWireGuard stops WireGuard
func StopWireGuard() {
    log.Printf("Stopping WireGuard")
    if wgDevice != nil {
        wgDevice.Close()
        wgDevice = nil
    }
    tunDevice = nil
}

// GetWireGuardStatus returns status
func GetWireGuardStatus() string {
    if wgDevice == nil {
        return "stopped"
    }
    status, err := wgDevice.IpcGet()
    if err != nil {
        return fmt.Sprintf("error: %v", err)
    }
    return status
}
