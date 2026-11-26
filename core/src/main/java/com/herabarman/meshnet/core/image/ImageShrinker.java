package com.herabarman.meshnet.core.image;

/**
 * Makes a photo as small as possible while it still looks like the original. It does not guess: for every candidate it encodes,
 * decodes again and measures the difference to the (resized) original with SSIM, then keeps the smallest result that stays above
 * the quality target.
 *
 * Steps: decode, shrink very large pictures to maxLongSide (a phone camera's 4000 px is far more than a chat screen shows; this
 * is where most of the saving comes from), then binary-search the lowest encoder quality whose SSIM is still >= targetSsim.
 * If the picture must fit maxBytes (a mesh link is slow), the target is relaxed in small steps and the picture shrunk a little more,
 * and the result says exactly what was achieved. Re-encoding also removes camera metadata (EXIF: GPS position, device).
 * If nothing is smaller than the original, the original bytes are returned untouched.
 * The platform supplies a {@link Codec} (Android: Bitmap/WebP; desktop: ImageIO/JPEG).
 */
public final class ImageShrinker {
    public interface Codec {
        /** @return null if the bytes are not a picture this codec understands. Must honour camera rotation. */
        Raster decode(byte[] data);

        /** quality 1..100. */
        byte[] encode(Raster r, int quality);
    }

    public static final class Config {
        public int maxLongSide = 1600;
        public double targetSsim = 0.97;
        public double floorSsim = 0.92;          // only reached when maxBytes cannot be met otherwise
        public int minQuality = 30, maxQuality = 95;
        public int maxBytes = 0;                 // 0 = no byte limit
        public int minLongSide = 480;
    }

    public static final class Result {
        public final byte[] bytes;
        public final int width, height, quality, originalBytes;
        public final double ssim;
        public final boolean usedOriginal, metBudget;
        public final int encodes;
        Result(byte[] b, int w, int h, int q, int ob, double s, boolean orig, boolean met, int enc) {
            bytes = b; width = w; height = h; quality = q; originalBytes = ob; ssim = s; usedOriginal = orig; metBudget = met; encodes = enc;
        }
        public double ratio() { return originalBytes == 0 ? 1 : bytes.length / (double) originalBytes; }
        @Override public String toString() {
            return originalBytes + " -> " + bytes.length + " bytes (" + Math.round(100 * (1 - ratio())) + "% smaller), " + width + "x" + height + ", q" + quality + ", ssim " + String.format("%.4f", ssim) + (usedOriginal ? ", original kept" : "");
        }
    }

    private final Codec codec;

    public ImageShrinker(Codec codec) { this.codec = codec; }

    /** @return null if the input is not a picture. */
    public Result shrink(byte[] original, Config cfg) {
        if (cfg == null) cfg = new Config();
        Raster src = codec.decode(original);
        if (src == null) return null;
        int encodes = 0;
        double target = cfg.targetSsim;
        int longSide = Math.min(Math.max(src.w, src.h), cfg.maxLongSide);
        Result best = null;
        while (true) {
            Raster ref = scaleTo(src, longSide);
            int lo = cfg.minQuality, hi = cfg.maxQuality;
            byte[] okBytes = null;
            int okQ = -1;
            double okS = 0;
            while (lo <= hi) {                                    // lowest quality that is still good enough
                int q = (lo + hi) >>> 1;
                byte[] enc = codec.encode(ref, q);
                encodes++;
                Raster back = codec.decode(enc);
                double s = back == null || back.w != ref.w || back.h != ref.h ? 0 : ImageQuality.ssim(ref, back);
                if (s >= target) { okBytes = enc; okQ = q; okS = s; hi = q - 1; } else lo = q + 1;
            }
            if (okBytes == null) {                                // even the best quality misses the target: take the best one anyway
                okBytes = codec.encode(ref, cfg.maxQuality);
                encodes++;
                Raster back = codec.decode(okBytes);
                okQ = cfg.maxQuality;
                okS = back == null ? 0 : ImageQuality.ssim(ref, back);
            }
            best = new Result(okBytes, ref.w, ref.h, okQ, original.length, okS, false, cfg.maxBytes == 0 || okBytes.length <= cfg.maxBytes, encodes);
            if (cfg.maxBytes == 0 || okBytes.length <= cfg.maxBytes) break;
            if (target > cfg.floorSsim + 1e-9) { target = Math.max(cfg.floorSsim, target - 0.01); continue; }   // first give up a little quality
            if (longSide <= cfg.minLongSide) break;                                                              // then a little size
            longSide = Math.max(cfg.minLongSide, (int) (longSide * 0.85));
            target = cfg.targetSsim - 0.02;
        }
        if (best.bytes.length >= original.length && src.w <= cfg.maxLongSide && src.h <= cfg.maxLongSide)
            return new Result(original, src.w, src.h, 100, original.length, 1.0, true, cfg.maxBytes == 0 || original.length <= cfg.maxBytes, encodes);
        return best;
    }

    private static Raster scaleTo(Raster r, int longSide) {
        int big = Math.max(r.w, r.h);
        if (big <= longSide) return r;
        double f = longSide / (double) big;
        return r.scale(Math.max(1, (int) Math.round(r.w * f)), Math.max(1, (int) Math.round(r.h * f)));
    }
}
