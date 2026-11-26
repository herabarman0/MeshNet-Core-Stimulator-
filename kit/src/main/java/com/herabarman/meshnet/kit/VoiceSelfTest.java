package com.herabarman.meshnet.kit;

import java.util.ArrayList;
import java.util.List;

/**
 * A check the app can run on the phone itself, because only the phone can answer: does the microphone open and
 * deliver sound at the right speed, does the loudspeaker take samples, and does each codec bring a tone back
 * as the same tone. Run it once (off the main thread, it takes about a second) and show {@link Report#toString()}.
 * The codec check is also what decides, on the phone, whether a codec is safe to use.
 */
public final class VoiceSelfTest {
    public static final class Item {
        public final String name;
        public final boolean ok;
        public final String detail;

        Item(String name, boolean ok, String detail) {
            this.name = name;
            this.ok = ok;
            this.detail = detail;
        }
    }

    public static final class Report {
        public final List<Item> items = new ArrayList<>();

        public boolean allOk() {
            for (Item i : items) if (!i.ok) return false;
            return !items.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder b = new StringBuilder();
            for (Item i : items) b.append(i.ok ? "OK    " : "FAIL  ").append(i.name).append(": ").append(i.detail).append('\n');
            return b.toString();
        }
    }

    private static final double TONE_HZ = 440;

    private VoiceSelfTest() { }

    public static Report run(AudioSource mic, AudioSink speaker, VoiceCodec.Factory... codecs) {
        Report r = new Report();
        VoiceCodec probe = codecs[0].create();
        int rate = probe.sampleRate(), block = probe.blockSamples();
        r.items.add(micCheck(mic, rate, block));
        r.items.add(speakerCheck(speaker, rate, block));
        for (VoiceCodec.Factory f : codecs) {
            String why = codecProblem(f);
            r.items.add(new Item("codec " + f.id(), why == null, why == null ? "a tone comes back as the same tone" : why));
        }
        return r;
    }

    private static Item micCheck(AudioSource mic, int rate, int block) {
        short[] buf = new short[block];
        double energy = 0;
        int blocks = 10;
        long t0 = System.currentTimeMillis();
        try {
            mic.start(rate);
            for (int i = 0; i < blocks; i++) {
                if (mic.read(buf) < 0) return new Item("microphone", false, "stopped while reading");
                for (short s : buf) energy += (double) s * s;
            }
        } catch (RuntimeException e) {
            return new Item("microphone", false, "cannot open: " + e.getMessage());
        } finally {
            try { mic.stop(); } catch (RuntimeException ignored) { }
        }
        long took = System.currentTimeMillis() - t0;
        long expect = (long) blocks * block * 1000 / rate;
        if (took < expect * 0.6 || took > expect * 1.6) {
            return new Item("microphone", false, "delivers samples at the wrong speed (" + took + " ms for " + expect + " ms of audio)");
        }
        double rms = Math.sqrt(energy / ((double) blocks * block));
        if (rms == 0) return new Item("microphone", false, "opens but every sample is zero (muted, or blocked by another app)");
        return new Item("microphone", true, "opens, right speed, level " + (int) rms);
    }

    private static Item speakerCheck(AudioSink speaker, int rate, int block) {
        try {
            speaker.start(rate);
            short[] beep = new short[block];
            for (int b = 0; b < 5; b++) {                              // 300 ms at 60 ms blocks
                for (int i = 0; i < block; i++) beep[i] = (short) (6000 * Math.sin(2 * Math.PI * TONE_HZ * (b * block + i) / rate));
                speaker.write(beep);
            }
            return new Item("loudspeaker", true, "accepted a 300 ms beep (you should have heard it)");
        } catch (RuntimeException e) {
            return new Item("loudspeaker", false, "cannot play: " + e.getMessage());
        } finally {
            try { speaker.stop(); } catch (RuntimeException ignored) { }
        }
    }

    /** True if this codec carries a tone through as the same tone (and at about the same loudness). */
    public static boolean codecWorks(VoiceCodec.Factory f) {
        return codecProblem(f) == null;
    }

    /** @return null if the codec is fine, else what is wrong */
    static String codecProblem(VoiceCodec.Factory f) {
        try {
            VoiceCodec enc = f.create(), dec = f.create();
            int block = enc.blockSamples(), rate = enc.sampleRate();
            short[] last = null, src = null;
            for (int b = 0; b < 8; b++) {                              // a codec may hold a frame or two back: feed several blocks
                short[] pcm = new short[block];
                for (int i = 0; i < block; i++) pcm[i] = (short) (8000 * Math.sin(2 * Math.PI * TONE_HZ * (b * block + i) / rate));
                byte[] bytes = enc.encode(pcm);
                if (bytes == null) continue;
                short[] out = dec.decode(bytes);
                if (out != null) { last = out; src = pcm; }
            }
            if (last == null) return "gave no audio back";
            if (last.length != block) return "gave " + last.length + " samples instead of " + block;
            double ratio = rms(last) / rms(src);
            if (!(ratio > 0.3 && ratio < 3.0)) return "wrong loudness (x" + String.format("%.2f", ratio) + ")";
            double frac = toneFraction(last, TONE_HZ, rate);
            if (frac < 0.5) return "the tone is not recognisable afterwards (" + (int) (frac * 100) + "% of the sound is the tone)";
            return null;
        } catch (RuntimeException e) {
            return "failed: " + e;
        }
    }

    static double rms(short[] x) {
        double e = 0;
        for (short v : x) e += (double) v * v;
        return Math.sqrt(e / x.length);
    }

    /** Share of the signal's energy that is a pure tone at freq (1.0 = a clean sine). */
    static double toneFraction(short[] x, double freq, int rate) {
        double w = 2 * Math.PI * freq / rate, coeff = 2 * Math.cos(w), s1 = 0, s2 = 0, energy = 0;
        for (short v : x) {
            double s = v + coeff * s1 - s2;
            s2 = s1;
            s1 = s;
            energy += (double) v * v;
        }
        if (energy == 0) return 0;
        double power = s1 * s1 + s2 * s2 - coeff * s1 * s2;
        return Math.min(1.0, 2 * power / ((double) x.length * energy));
    }
}
