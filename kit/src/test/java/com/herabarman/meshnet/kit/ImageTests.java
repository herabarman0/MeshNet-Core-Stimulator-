package com.herabarman.meshnet.kit;

import com.herabarman.meshnet.core.MemoryStorage;
import com.herabarman.meshnet.core.image.ImageQuality;
import com.herabarman.meshnet.core.image.ImageShrinker;
import com.herabarman.meshnet.core.image.Raster;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Photo compression: size, how close the result looks, the byte budget, privacy (EXIF), and a real transfer over UDP. */
public final class ImageTests {
    static int passed, failed;

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("PASS  " + what); } else { failed++; System.out.println("FAIL  " + what); }
    }

    static boolean waitFor(BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { if (c.getAsBoolean()) return true; Thread.sleep(50); }
        return c.getAsBoolean();
    }

    /** A photo-like picture: soft light, large shapes, fine texture and sensor noise. */
    static Raster photo(int w, int h, long seed) {
        Random rnd = new Random(seed);
        double[][] grid = new double[3][];
        int[] px = new int[w * h];
        int gs = 64;
        double[] g1 = new double[(w / gs + 4) * (h / gs + 4) * 3], g2 = new double[g1.length];
        for (int i = 0; i < g1.length; i++) { g1[i] = rnd.nextDouble(); g2[i] = rnd.nextDouble(); }
        int gw = w / gs + 4;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double[] c = new double[3];
                for (int ch = 0; ch < 3; ch++) {
                    double v = 0.25 + 0.35 * x / w + 0.25 * y / h * (ch == 2 ? 1 : 0.4);
                    v += 0.25 * noise(g1, gw, x / (double) gs / 4, y / (double) gs / 4, ch);
                    v += 0.12 * noise(g2, gw, x / (double) gs, y / (double) gs, ch);
                    v += 0.10 * (noise(g2, gw, x / (double) gs * 6.1 % 3.0, y / (double) gs * 6.1 % 3.0, ch) - 0.5);
                    v += 0.07 * (rnd.nextDouble() - 0.5);
                    c[ch] = v;
                }
                double dx = x - w * 0.62, dy = y - h * 0.45;
                if (dx * dx + dy * dy < Math.pow(Math.min(w, h) * 0.22, 2)) { c[0] = c[0] * 0.5 + 0.45; c[1] *= 0.6; c[2] *= 0.5; }
                int r = clamp(c[0]), gr = clamp(c[1]), b = clamp(c[2]);
                px[y * w + x] = (r << 16) | (gr << 8) | b;
            }
        }
        return new Raster(w, h, px);
    }

    static int clamp(double v) { return Math.max(0, Math.min(255, (int) Math.round(v * 255))); }

    static double noise(double[] g, int gw, double x, double y, int ch) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
        double fx = x - xi, fy = y - yi;
        fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy);
        double a = g[(yi * gw + xi) * 3 + ch], b = g[(yi * gw + xi + 1) * 3 + ch], c = g[((yi + 1) * gw + xi) * 3 + ch], d = g[((yi + 1) * gw + xi + 1) * 3 + ch];
        return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy;
    }

    /** Insert a fake EXIF segment (with a GPS marker) right after the JPEG start. */
    static byte[] withExif(byte[] jpeg, String marker) {
        byte[] payload = ("Exif\0\0" + marker).getBytes();
        byte[] out = new byte[jpeg.length + 4 + payload.length];
        out[0] = jpeg[0]; out[1] = jpeg[1];
        out[2] = (byte) 0xFF; out[3] = (byte) 0xE1;
        int len = payload.length + 2;
        out[4] = (byte) (len >> 8); out[5] = (byte) len;
        System.arraycopy(payload, 0, out, 6, payload.length);
        System.arraycopy(jpeg, 2, out, 6 + payload.length, jpeg.length - 2);
        return out;
    }

    static boolean contains(byte[] hay, String needle) {
        byte[] n = needle.getBytes();
        outer:
        for (int i = 0; i + n.length <= hay.length; i++) { for (int j = 0; j < n.length; j++) if (hay[i + j] != n[j]) continue outer; return true; }
        return false;
    }

    public static void main(String[] args) throws Exception {
        JdkJpegCodec codec = new JdkJpegCodec();
        ImageShrinker sh = new ImageShrinker(codec);

        // metric sanity
        Raster a = photo(400, 300, 1), b = photo(400, 300, 1);
        check("SSIM of identical pictures is 1 and PSNR is infinite", ImageQuality.ssim(a, b) > 0.9999 && Double.isInfinite(ImageQuality.psnr(a, b)));
        double s90 = ImageQuality.ssim(a, codec.decode(codec.encode(a, 90))), s30 = ImageQuality.ssim(a, codec.decode(codec.encode(a, 30))), s5 = ImageQuality.ssim(a, codec.decode(codec.encode(a, 5)));
        check("SSIM falls as JPEG quality falls (q90 " + String.format("%.3f", s90) + " > q30 " + String.format("%.3f", s30) + " > q5 " + String.format("%.3f", s5) + ")", s90 > s30 && s30 > s5);
        int[] chk = new int[16 * 16];
        for (int i = 0; i < chk.length; i++) chk[i] = ((i % 16 + i / 16) & 1) == 0 ? 0xFFFFFF : 0;
        Raster half = new Raster(16, 16, chk).scale(8, 8);
        check("resizing a black/white checkerboard by two gives mid-grey (area average)", (half.rgb[0] & 255) >= 126 && (half.rgb[0] & 255) <= 129 && half.w == 8);

        // a big "camera" photo
        Raster big = photo(3000, 2000, 7);
        byte[] original = codec.encode(big, 97);            // what a camera would hand over: high quality, large
        check("test photo is camera-sized (" + original.length / 1024 + " KB, 3000x2000)", original.length > 1_000_000);
        long t0 = System.currentTimeMillis();
        ImageShrinker.Result r = sh.shrink(withExif(original, "GPS 23.81N 90.41E Pixel7"), null);
        long ms = System.currentTimeMillis() - t0;
        System.out.println("INFO  " + r + " in " + ms + " ms, " + r.encodes + " encodes");
        check("the photo shrinks to under 15% of its size", r.ratio() < 0.15);
        check("the long side is limited to 1600 px", Math.max(r.width, r.height) == 1600);
        Raster out = codec.decode(r.bytes);
        Raster ref = big.scale(out.w, out.h);
        double real = ImageQuality.ssim(ref, out);
        check("measured separately, it still looks like the original (SSIM " + String.format("%.4f", real) + " >= 0.97, PSNR " + String.format("%.1f", ImageQuality.psnr(ref, out)) + " dB)", real >= 0.97 && Math.abs(real - r.ssim) < 0.002);
        check("camera metadata (GPS, device) is gone from the result", !contains(r.bytes, "GPS 23.81N") && !contains(r.bytes, "Pixel7") && contains(withExif(original, "GPS 23.81N"), "GPS 23.81N"));
        check("the search is cheap: a few encodes only (" + r.encodes + ")", r.encodes <= 10);

        // quality target moves the size
        ImageShrinker.Config strict = new ImageShrinker.Config(); strict.targetSsim = 0.98; strict.maxQuality = 100;
        ImageShrinker.Config loose = new ImageShrinker.Config(); loose.targetSsim = 0.93; loose.floorSsim = 0.9;
        ImageShrinker.Result rs = sh.shrink(original, strict), rl = sh.shrink(original, loose);
        check("a stricter target gives a bigger file, a looser one a smaller file (" + rs.bytes.length / 1024 + " KB > " + r.bytes.length / 1024 + " KB > " + rl.bytes.length / 1024 + " KB)", rs.bytes.length > r.bytes.length && r.bytes.length > rl.bytes.length && rs.ssim >= 0.98);

        // byte budget
        ImageShrinker.Config budget = new ImageShrinker.Config(); budget.maxBytes = 40 * 1024;
        ImageShrinker.Result rb = sh.shrink(original, budget);
        System.out.println("INFO  budget 40 KB: " + rb);
        check("a 40 KB budget is met, with the cost stated (SSIM " + String.format("%.3f", rb.ssim) + ")", rb.metBudget && rb.bytes.length <= 40 * 1024 && rb.ssim >= 0.85);

        // a small picture that cannot get smaller stays as it is
        byte[] tiny = codec.encode(photo(160, 120, 3), 40);
        ImageShrinker.Config hard = new ImageShrinker.Config(); hard.targetSsim = 0.999;
        ImageShrinker.Result rt = sh.shrink(tiny, hard);
        check("when re-encoding does not help, the original bytes are returned untouched", rt.bytes.length <= tiny.length && (rt.usedOriginal ? Arrays.equals(rt.bytes, tiny) : rt.ssim >= 0.9));
        check("bytes that are not a picture are refused", sh.shrink("hello".getBytes(), null) == null);

        // real transfer
        int base = 47000 + (int) (System.nanoTime() % 500);
        UdpRadio r1 = new UdpRadio(401, base, false, 300, 2500), r2 = new UdpRadio(402, base + 1, false, 300, 2500);
        r1.addPeer(new java.net.InetSocketAddress("127.0.0.1", base + 1));
        r2.addPeer(new java.net.InetSocketAddress("127.0.0.1", base));
        KitTests.Probe p1 = new KitTests.Probe(), p2 = new KitTests.Probe();
        MeshClient c1 = MeshClient.builder("401").radio(r1).storage(new MemoryStorage()).listener(p1).build();
        MeshClient c2 = MeshClient.builder("402").radio(r2).storage(new MemoryStorage()).listener(p2).build();
        c1.start(); c2.start();
        check("two phones find each other", waitFor(() -> c1.peerCount() == 1 && c2.peerCount() == 1, 6000));
        final AtomicReference<ImageShrinker.Result> res = new AtomicReference<>();
        final AtomicReference<Long> blob = new AtomicReference<>();
        PhotoSender.send(c1, "402", original, codec, null, new PhotoSender.Callback() {
            @Override public void onQueued(long id, ImageShrinker.Result x) { res.set(x); blob.set(id); }
            @Override public void onFailed(String why) { System.out.println("INFO  failed: " + why); }
        });
        check("PhotoSender compresses in the background and queues the blob", waitFor(() -> blob.get() != null, 30000));
        check("the receiver gets exactly the compressed bytes", waitFor(() -> p2.blobs.containsKey(blob.get()) && Arrays.equals(p2.blobs.get(blob.get()), res.get().bytes), 40000));
        Raster seen = codec.decode(p2.blobs.get(blob.get()));
        check("and can decode it as a picture (" + seen.w + "x" + seen.h + ", " + res.get().bytes.length / 1024 + " KB instead of " + original.length / 1024 + " KB over the air)", seen != null && seen.w == res.get().width);
        c1.stop(); c2.stop(); r1.stop(); r2.stop();
        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
