package com.herabarman.meshnet.core.image;

/** How much two pictures differ the way an eye sees it. SSIM = 1.0 means identical; above about 0.97 people rarely see a difference. */
public final class ImageQuality {
    private ImageQuality() { }

    /** Mean SSIM over 8x8 brightness windows (stride 4). Both pictures must have the same size. */
    public static double ssim(Raster a, Raster b) {
        if (a.w != b.w || a.h != b.h) throw new IllegalArgumentException("sizes differ");
        float[] ya = a.luma(), yb = b.luma();
        final double c1 = 6.5025, c2 = 58.5225;            // (0.01*255)^2, (0.03*255)^2
        double sum = 0;
        int n = 0;
        int win = Math.min(8, Math.min(a.w, a.h)), step = Math.max(1, win / 2);
        for (int y = 0; y + win <= a.h; y += step) {
            for (int x = 0; x + win <= a.w; x += step) {
                double ma = 0, mb = 0;
                for (int j = 0; j < win; j++) for (int i = 0; i < win; i++) { int k = (y + j) * a.w + x + i; ma += ya[k]; mb += yb[k]; }
                int cnt = win * win;
                ma /= cnt; mb /= cnt;
                double va = 0, vb = 0, cov = 0;
                for (int j = 0; j < win; j++) for (int i = 0; i < win; i++) {
                    int k = (y + j) * a.w + x + i;
                    double da = ya[k] - ma, db = yb[k] - mb;
                    va += da * da; vb += db * db; cov += da * db;
                }
                va /= cnt - 1 == 0 ? 1 : cnt - 1; vb /= cnt - 1 == 0 ? 1 : cnt - 1; cov /= cnt - 1 == 0 ? 1 : cnt - 1;
                sum += ((2 * ma * mb + c1) * (2 * cov + c2)) / ((ma * ma + mb * mb + c1) * (va + vb + c2));
                n++;
            }
        }
        return n == 0 ? 1 : sum / n;
    }

    /** Peak signal-to-noise ratio in dB over all colour channels; infinity if identical. */
    public static double psnr(Raster a, Raster b) {
        if (a.w != b.w || a.h != b.h) throw new IllegalArgumentException("sizes differ");
        double se = 0;
        for (int i = 0; i < a.rgb.length; i++) {
            int p = a.rgb[i], q = b.rgb[i];
            int dr = ((p >> 16) & 255) - ((q >> 16) & 255), dg = ((p >> 8) & 255) - ((q >> 8) & 255), db = (p & 255) - (q & 255);
            se += dr * dr + dg * dg + db * db;
        }
        if (se == 0) return Double.POSITIVE_INFINITY;
        return 10 * Math.log10(255.0 * 255.0 / (se / (3.0 * a.rgb.length)));
    }
}
