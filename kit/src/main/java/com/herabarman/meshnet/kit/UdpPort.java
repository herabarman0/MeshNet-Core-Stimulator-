package com.herabarman.meshnet.kit;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * One UDP socket, as the hole-punching code sees it. {@link JavaUdpPort} is the real one; the tests use a
 * simulated one that sits behind a model of a NAT, so every kind of NAT can be tried without a network.
 */
public interface UdpPort {
    interface Receiver {
        /** Called on the port's own thread for every datagram. */
        void onDatagram(InetSocketAddress from, byte[] data);
    }

    void setReceiver(Receiver r);

    void send(InetSocketAddress to, byte[] data);

    /** Addresses a peer on the same network could reach this socket at (not the public one: that comes from STUN). */
    List<InetSocketAddress> localCandidates();

    void close();
}
