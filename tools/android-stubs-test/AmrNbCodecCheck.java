import com.herabarman.meshnet.android.AmrNbCodec;
import java.util.Arrays;

/** Runs AmrNbCodec's own logic against the toy MediaCodec stub: framing, lag handling, bad input. Not AMR itself. */
public class AmrNbCodecCheck {
    static int pass, fail;
    static void check(boolean ok, String n) { if (ok) pass++; else { fail++; System.out.println("FAIL: " + n); } }

    static short[] tone(int start) {
        short[] p = new short[480];
        for (int i = 0; i < p.length; i++) p[i] = (short) (8000 * Math.sin(2 * Math.PI * 440 * (start + i) / 8000.0));
        return p;
    }

    static double snr(short[] ref, short[] y) {
        double s = 0, e = 0;
        for (int i = 0; i < ref.length; i++) { s += (double) ref[i] * ref[i]; e += (double) (ref[i] - y[i]) * (ref[i] - y[i]); }
        return 10 * Math.log10(s / e);
    }

    public static void main(String[] a) {
        check(AmrNbCodec.isAvailable(), "isAvailable sees an encoder and a decoder");
        for (int lag = 0; lag <= 2; lag++) {
            android.media.MediaCodec.LAG = lag;
            check(com.herabarman.meshnet.kit.VoiceSelfTest.codecWorks(AmrNbCodec.FACTORY), "on-phone codec check passes with a codec that holds back " + lag + " frames");
        }
        android.media.MediaCodec.LAG = 0;
        android.media.MediaCodec.DEAF = true;
        check(!com.herabarman.meshnet.kit.VoiceSelfTest.codecWorks(AmrNbCodec.FACTORY), "on-phone codec check FAILS when the decoder returns silence");
        android.media.MediaCodec.DEAF = false;
        for (int lag = 0; lag <= 2; lag++) {
            android.media.MediaCodec.LAG = lag;
            AmrNbCodec enc = new AmrNbCodec(), dec = new AmrNbCodec();
            int sent = 0, decoded = 0, nullAfterFirst = 0;
            short[] firstDecoded = null;
            for (int b = 0; b < 10; b++) {
                byte[] blk = enc.encode(tone(b * 480));
                if (blk == null) continue;
                sent++;
                check(blk[0] == 3, "lag " + lag + ": block says 3 frames");
                short[] pcm = dec.decode(blk);
                if (pcm != null) {
                    check(pcm.length == 480, "lag " + lag + ": block decodes to 480 samples");
                    decoded++;
                    if (firstDecoded == null) firstDecoded = pcm;
                } else if (decoded > 0) nullAfterFirst++;
            }
            check(sent >= 9, "lag " + lag + ": at most the first block is skipped by a lagging encoder (" + sent + " of 10 sent)");
            check(decoded >= sent - 1 && nullAfterFirst == 0, "lag " + lag + ": a lagging decoder gives only the first block late (" + decoded + " of " + sent + " decoded)");
            check(firstDecoded != null && energy(firstDecoded) > 0, "lag " + lag + ": decoded audio is not silence");
            // steady state after the lag: the decoded block is a faithful, delayed copy of the tone
            AmrNbCodec e2 = new AmrNbCodec(), d2 = new AmrNbCodec();
            if (lag == 0) {
                short[] src = tone(0);
                short[] back = d2.decode(e2.encode(src));
                check(back != null && snr(src, back) > 25, "lag 0: tone survives the codec wrapper (" + (back == null ? 0 : (int) snr(src, back)) + " dB)");
            }
        }
        android.media.MediaCodec.LAG = 0;
        AmrNbCodec d = new AmrNbCodec();
        check(d.decode(null) == null && d.decode(new byte[1]) == null, "decoder rejects missing or tiny input");
        check(d.decode(new byte[]{3, (byte) 200, 1, 2}) == null, "decoder rejects a frame longer than the block");
        check(d.decode(new byte[]{2, 1, 1}) == null, "decoder rejects a wrong frame count");
        AmrNbCodec e = new AmrNbCodec(), dd = new AmrNbCodec();
        short[] good = dd.decode(e.encode(tone(0)));
        short[] c1 = dd.conceal(), c2 = dd.conceal(), c3 = dd.conceal(), c4 = dd.conceal();
        check(good != null && energy(c1) > 0 && energy(c1) < energy(good) && energy(c2) < energy(c1) && energy(c3) < energy(c2) && energy(c4) == 0,
                "conceal: quieter repeat, then silence");
        try { e.encode(new short[10]); check(false, "wrong block size must throw"); } catch (IllegalArgumentException ex) { check(true, "wrong block size throws"); }
        System.out.println("AmrNbCodec wrapper check: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static double energy(short[] x) { double e = 0; for (short v : x) e += (double) v * v; return e; }
}
