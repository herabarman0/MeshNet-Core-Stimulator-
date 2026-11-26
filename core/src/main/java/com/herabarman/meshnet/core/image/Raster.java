package com.herabarman.meshnet.core.image;

/** A decoded picture: width x height pixels, each 0xRRGGBB (alpha is ignored; photos are sent opaque). */
public final class Raster {
    public final int w, h;
    public final int[] rgb;

    public Raster(int w, int h, int[] rgb) {
        if (w <= 0 || h <= 0 || rgb.length != w * h) throw new IllegalArgumentException("bad raster");
        this.w = w; this.h = h; this.rgb = rgb;
    }

    /** Brightness (0..255) of every pixel, Rec.601 weights. */
    public float[] luma() {
        float[] y = new float[rgb.length];
        for (int i = 0; i < y.length; i++) {
            int p = rgb[i];
            y[i] = 0.299f * ((p >> 16) & 255) + 0.587f * ((p >> 8) & 255) + 0.114f * (p & 255);
        }
        return y;
    }

    /** Box-filter (area average) resize: good quality for shrinking, no external library. */
    public Raster scale(int nw, int nh) {
        if (nw == w && nh == h) return this;
        int[] out = new int[nw * nh];
        for (int y = 0; y < nh; y++) {
            double y0 = y * (double) h / nh, y1 = (y + 1) * (double) h / nh;
            for (int x = 0; x < nw; x++) {
                double x0 = x * (double) w / nw, x1 = (x + 1) * (double) w / nw;
                double r = 0, g = 0, b = 0, wsum = 0;
                for (int yy = (int) y0; yy < Math.min(h, (int) Math.ceil(y1)); yy++) {
                    double wy = Math.min(yy + 1, y1) - Math.max(yy, y0);
                    for (int xx = (int) x0; xx < Math.min(w, (int) Math.ceil(x1)); xx++) {
                        double wt = wy * (Math.min(xx + 1, x1) - Math.max(xx, x0));
                        int p = rgb[yy * w + xx];
                        r += wt * ((p >> 16) & 255); g += wt * ((p >> 8) & 255); b += wt * (p & 255);
                        wsum += wt;
                    }
                }
                out[y * nw + x] = ((int) Math.round(r / wsum) << 16) | ((int) Math.round(g / wsum) << 8) | (int) Math.round(b / wsum);
            }
        }
        return new Raster(nw, nh, out);
    }
}
