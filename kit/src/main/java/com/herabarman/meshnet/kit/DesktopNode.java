package com.herabarman.meshnet.kit;

import com.herabarman.meshnet.core.FileStorage;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.file.Files;

/**
 * A mesh node for a desktop / server / Raspberry Pi, talking to other nodes over UDP.
 *
 *   java -cp ... com.herabarman.meshnet.kit.DesktopNode <userId> <udpPort> [host:port ...] [--server URL] [--data DIR] [--lan]
 *
 * Commands on stdin:  send <user> <text...>   blob <user> <file>   peers   id   quit
 * Received blobs are written to ./received-<blobId>.bin. State (keys, queue) is kept in DIR (default ./meshdata-<userId>).
 */
public final class DesktopNode {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: DesktopNode <userId> <udpPort> [host:port ...] [--server URL] [--data DIR] [--lan]");
            return;
        }
        final String user = args[0];
        int port = Integer.parseInt(args[1]);
        String server = null, data = "meshdata-" + user;
        boolean lan = false;
        java.util.List<InetSocketAddress> seeds = new java.util.ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--server")) server = args[++i];
            else if (args[i].equals("--data")) data = args[++i];
            else if (args[i].equals("--lan")) lan = true;
            else { String[] hp = args[i].split(":"); seeds.add(new InetSocketAddress(hp[0], Integer.parseInt(hp[1]))); }
        }
        File dir = new File(data);
        dir.mkdirs();
        FileStorage storage = new FileStorage(dir);
        int node = new IdMap(storage).nodeOf(user);
        UdpRadio radio = new UdpRadio(node, port, lan);
        for (InetSocketAddress a : seeds) radio.addPeer(a);
        MeshClient.Builder b = MeshClient.builder(user).radio(radio).storage(storage).listener(new MeshClient.Listener() {
            @Override public void onText(String from, long id, long ts, String text) { System.out.println("[" + from + "] " + text); }
            @Override public void onBytes(String from, long id, byte[] d) { System.out.println("[" + from + "] " + d.length + " bytes"); }
            @Override public void onBlob(String from, long id, byte[] d) {
                try { File f = new File("received-" + id + ".bin"); Files.write(f.toPath(), d); System.out.println("[" + from + "] file " + d.length + " bytes -> " + f); }
                catch (java.io.IOException e) { System.out.println("cannot save file: " + e); }
            }
            @Override public void onDelivered(long id) { System.out.println("(delivered " + id + ")"); }
            @Override public void onFailed(long id, String r) { System.out.println("(failed " + id + ": " + r + ")"); }
            @Override public void onSeen(long id) { System.out.println("(seen " + id + ")"); }
            @Override public void onPeers(int n) { System.out.println("(neighbours: " + n + ")"); }
        }).dtn(true);
        if (server != null) b.server(new HttpServerLink(server, null));
        final MeshClient mesh = b.build();
        mesh.start();
        System.out.println("node " + user + " (id " + node + ") on udp/" + port + ", key " + mesh.myFingerprint());
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.equals("quit")) break;
            else if (line.equals("peers")) System.out.println(mesh.linkQuality());
            else if (line.equals("id")) System.out.println(mesh.myFingerprint());
            else if (line.startsWith("send ")) { String[] p = line.split(" ", 3); if (p.length == 3) mesh.sendText(p[1], p[2]); }
            else if (line.startsWith("blob ")) {
                String[] p = line.split(" ", 3);
                if (p.length == 3) { try { mesh.sendBlob(p[1], Files.readAllBytes(new File(p[2]).toPath())); } catch (Exception e) { System.out.println("error: " + e.getMessage()); } }
            }
        }
        mesh.stop();
    }
}
