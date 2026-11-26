package com.herabarman.meshnet.kit;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/** A UdpPort on a real DatagramSocket (works on Android 5+). One receive thread. */
public final class JavaUdpPort implements UdpPort {
    private final DatagramSocket socket;
    private volatile Receiver receiver;
    private volatile boolean open = true;
    private final boolean loopback;

    /** @param port 0 = any free port */
    public JavaUdpPort(int port) throws SocketException {
        this(port, false);
    }

    /** @param loopback also offer 127.0.0.1 as a candidate (for two nodes on one machine: tests, desktop tools) */
    public JavaUdpPort(int port, boolean loopback) throws SocketException {
        this.loopback = loopback;
        this.socket = new DatagramSocket(port);
        Thread t = new Thread(new Runnable() { @Override public void run() { loop(); } }, "mesh-udp");
        t.setDaemon(true);
        t.start();
    }

    @Override public void setReceiver(Receiver r) { this.receiver = r; }

    private void loop() {
        byte[] buf = new byte[2048];
        while (open) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                Receiver r = receiver;
                if (r == null) continue;
                byte[] data = new byte[p.getLength()];
                System.arraycopy(p.getData(), p.getOffset(), data, 0, data.length);
                r.onDatagram(new InetSocketAddress(p.getAddress(), p.getPort()), data);
            } catch (IOException e) {
                if (!open) return;
            } catch (RuntimeException e) {
                // one bad datagram must not stop the port
            }
        }
    }

    @Override
    public void send(InetSocketAddress to, byte[] data) {
        try {
            socket.send(new DatagramPacket(data, data.length, to));
        } catch (IOException | RuntimeException ignored) {
            // UDP: a failed send is a lost packet
        }
    }

    @Override
    public List<InetSocketAddress> localCandidates() {
        List<InetSocketAddress> out = new ArrayList<>();
        int port = socket.getLocalPort();
        if (loopback) out.add(new InetSocketAddress("127.0.0.1", port));
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            if (nis != null) {
                for (NetworkInterface ni : Collections.list(nis)) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                        if (a instanceof Inet4Address && !a.isLinkLocalAddress()) out.add(new InetSocketAddress(a, port));
                    }
                }
            }
        } catch (SocketException ignored) {
        }
        return out;
    }

    public int localPort() { return socket.getLocalPort(); }

    @Override
    public void close() {
        open = false;
        socket.close();
    }
}
