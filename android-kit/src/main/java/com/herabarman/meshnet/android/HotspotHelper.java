package com.herabarman.meshnet.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * Helper for offline Local-Only Hotspot hosting (API 26+) and Wi-Fi hotspot IP discovery.
 * Enables nearby phones without mobile data or router access to join a local mesh subnet
 * for high-bandwidth photo, voice/video, and OTA swarm transfers.
 */
public final class HotspotHelper {
    public interface Listener {
        void onStarted(String ssid, String passphrase, String localIp);
        void onStopped();
        void onError(int reason, String message);
    }

    private final WifiManager wifiManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WifiManager.LocalOnlyHotspotReservation reservation;
    private volatile String ssid;
    private volatile String passphrase;
    private volatile boolean active;

    public HotspotHelper(Context context) {
        Context app = context.getApplicationContext();
        this.wifiManager = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
    }

    public boolean isActive() {
        return active;
    }

    public String currentSsid() {
        return ssid;
    }

    public String currentPassphrase() {
        return passphrase;
    }

    @SuppressWarnings("deprecation")
    @SuppressLint("MissingPermission")
    public synchronized void start(final Listener listener) {
        if (Build.VERSION.SDK_INT < 26) {
            if (listener != null) listener.onError(-1, "API 26+ required for LocalOnlyHotspot");
            return;
        }
        if (wifiManager == null) {
            if (listener != null) listener.onError(-2, "Wi-Fi service unavailable");
            return;
        }
        if (active && reservation != null) {
            if (listener != null) listener.onStarted(ssid, passphrase, bestLocalIpv4());
            return;
        }
        try {
            wifiManager.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation res) {
                    synchronized (HotspotHelper.this) {
                        reservation = res;
                        active = true;
                        if (Build.VERSION.SDK_INT >= 30) {
                            ssid = res.getSoftApConfiguration().getSsid();
                            passphrase = res.getSoftApConfiguration().getPassphrase();
                        } else if (res.getWifiConfiguration() != null) {
                            ssid = res.getWifiConfiguration().SSID;
                            passphrase = res.getWifiConfiguration().preSharedKey;
                        }
                    }
                    if (listener != null) listener.onStarted(ssid, passphrase, bestLocalIpv4());
                }

                @Override
                public void onStopped() {
                    synchronized (HotspotHelper.this) {
                        reservation = null;
                        active = false;
                        ssid = null;
                        passphrase = null;
                    }
                    if (listener != null) listener.onStopped();
                }

                @Override
                public void onFailed(int reason) {
                    synchronized (HotspotHelper.this) {
                        active = false;
                    }
                    if (listener != null) listener.onError(reason, "LocalOnlyHotspot failed (" + reason + ")");
                }
            }, main);
        } catch (SecurityException se) {
            if (listener != null) listener.onError(-3, "Missing NEARBY_WIFI_DEVICES or Location permission");
        } catch (RuntimeException re) {
            if (listener != null) listener.onError(-4, String.valueOf(re.getMessage()));
        }
    }

    public synchronized void stop() {
        if (reservation != null) {
            try {
                reservation.close();
            } catch (RuntimeException ignored) {
            }
        }
        reservation = null;
        active = false;
        ssid = null;
        passphrase = null;
    }

    /** Returns the device's non-loopback IPv4 address (useful when hosting a hotspot TCP/UDP bridge). */
    public String bestLocalIpv4() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "192.168.43.1";
    }

    @SuppressWarnings("deprecation")
    public String connectedGatewayIpv4() {
        if (wifiManager == null) return null;
        try {
            int gw = wifiManager.getDhcpInfo().gateway;
            if (gw == 0) {
                WifiInfo info = wifiManager.getConnectionInfo();
                if (info == null || info.getIpAddress() == 0) return null;
            }
            return (gw & 0xFF) + "." + ((gw >> 8) & 0xFF) + "." + ((gw >> 16) & 0xFF) + "." + ((gw >> 24) & 0xFF);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
