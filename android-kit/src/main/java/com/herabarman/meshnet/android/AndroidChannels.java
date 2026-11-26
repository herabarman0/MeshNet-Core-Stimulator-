package com.herabarman.meshnet.android;

import android.content.Context;
import com.herabarman.meshnet.kit.MultiChannelBuilder;
import com.herabarman.meshnet.kit.RawRadio;
import com.herabarman.meshnet.kit.TcpBulk;
import com.herabarman.meshnet.kit.TcpRadio;
import com.herabarman.meshnet.kit.UdpRadio;

/**
 * Builds the radio set for anti-jamming on Android.
 *
 * Channel 0 = Nearby Connections (Bluetooth / Wi-Fi, chosen by Google's library, no medium control),
 * channel 1 = {@link UdpRadio} over the Wi-Fi network or hotspot the phone is on (LAN broadcast finds neighbours),
 * channel 2 = {@link TcpRadio} over a Wi-Fi Direct group ({@link WifiDirectConnector}).
 * These are separate radios or links, so a jammer on one leaves the others.
 *
 * NOT COMPILED OR TRIED ON A DEVICE (the build here has no Android SDK). Things to verify on real phones:
 *  - broadcast reception while the screen is off (Wi-Fi power save may drop it; a WifiManager multicast/wifi lock may be needed),
 *  - Nearby itself may use the same Wi-Fi band for its high-speed medium, so channels are not always independent,
 *  - some Wi-Fi networks block client-to-client traffic (AP isolation): then channel 1 never comes up and the link stays on Nearby (harmless),
 *  - Wi-Fi Direct usually takes the phone off its normal Wi-Fi network while a group is active.
 * Not implemented: Wi-Fi Aware and BLE-GATT as raw radios (each would be one more RawRadio given to MultiChannelBuilder).
 */
public final class AndroidChannels {
    private AndroidChannels() { }

    /** The radio to give MeshClient, plus the Wi-Fi Direct helper to start/stop (null if not requested). */
    public static final class Set {
        public final RawRadio radio;
        public final WifiDirectConnector wifiDirect;
        /** TCP bulk link that follows the Wi-Fi Direct TcpRadio (null unless bulk was asked for and Wi-Fi Direct is on). */
        public final TcpBulk tcpBulk;
        Set(RawRadio r, WifiDirectConnector w, TcpBulk b) { radio = r; wifiDirect = w; tcpBulk = b; }
    }

    public static RawRadio nearbyPlusWifi(int selfNode, NearbyRadio nearby, int udpPort) {
        UdpRadio wifi = new UdpRadio(selfNode, udpPort, true);
        return new MultiChannelBuilder(selfNode)
                .add(0, nearby, 0.35)
                .add(1, wifi, 1.0)
                .build();
    }

    public static Set build(Context ctx, int selfNode, NearbyRadio nearby, boolean wifiLan, int udpPort, boolean wifiDirect, int tcpPort) {
        return build(ctx, selfNode, nearby, wifiLan, udpPort, wifiDirect, tcpPort, false);
    }

    public static Set build(Context ctx, int selfNode, NearbyRadio nearby, boolean wifiLan, int udpPort, boolean wifiDirect, int tcpPort, boolean bulk) {
        MultiChannelBuilder b = new MultiChannelBuilder(selfNode).add(0, nearby, 0.35);
        if (wifiLan) b.add(1, new UdpRadio(selfNode, udpPort, true), 1.0);
        WifiDirectConnector wd = null;
        TcpBulk tb = null;
        if (wifiDirect) {
            TcpRadio tcp = new TcpRadio(selfNode, tcpPort);
            wd = new WifiDirectConnector(ctx, tcp, tcpPort);
            b.add(2, tcp, 0.9);
            if (bulk) tb = new TcpBulk(selfNode, tcpPort + 1).follow(tcp, tcpPort + 1);
        }
        return new Set(b.build(), wd, tb);
    }
}
