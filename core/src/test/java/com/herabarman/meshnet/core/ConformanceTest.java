package com.herabarman.meshnet.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Keeps conformance/vectors.txt honest: the committed file must be exactly what the Java engine produces today. */
class ConformanceTest {
    private static File find() {
        String prop = System.getProperty("mesh.conformance");
        List<String> candidates = new ArrayList<>();
        if (prop != null) candidates.add(prop);
        candidates.add("../conformance/vectors.txt");
        candidates.add("conformance/vectors.txt");
        for (String c : candidates) {
            File f = new File(c);
            if (f.isFile()) return f;
        }
        return null;
    }

    @Test
    void committedVectorsMatchTheJavaEngine() throws IOException {
        File f = find();
        if (f == null) return; // not running from a checkout
        List<String> expected = ConformanceVectors.generate();
        List<String> actual = new ArrayList<>();
        BufferedReader r = new BufferedReader(new FileReader(f));
        try {
            String line;
            while ((line = r.readLine()) != null) if (!line.startsWith("#")) actual.add(line);
        } finally {
            r.close();
        }
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) assertEquals(expected.get(i), actual.get(i));
    }

    @Test
    void everyPacketVectorRoundTripsInJava() {
        int n = 0;
        for (String line : ConformanceVectors.generate()) {
            String[] f = line.split(" ");
            if (!f[0].equals("packet")) continue;
            byte[] bytes = new byte[f[1].length() / 2];
            for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(f[1].substring(2 * i, 2 * i + 2), 16);
            Packet p = Packet.decode(bytes);
            assertArrayEquals(bytes, p.encode());
            n++;
        }
        assertTrue(n > 400);
    }

    @Test
    void badVectorsAreRejected() {
        int n = 0;
        for (String line : ConformanceVectors.generate()) {
            if (!line.startsWith("bad")) continue;
            String h = line.length() > 4 ? line.substring(4) : "";
            byte[] bytes = new byte[h.length() / 2];
            for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
            assertThrows(IllegalArgumentException.class, () -> Packet.decode(bytes));
            n++;
        }
        assertEquals(6, n);
    }
}
