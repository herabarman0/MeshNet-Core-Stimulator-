package com.herabarman.meshnet.android;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Build;
import com.herabarman.meshnet.core.image.ImageShrinker;
import com.herabarman.meshnet.core.image.Raster;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Photo codec for {@link ImageShrinker} on Android: BitmapFactory to decode (with the camera's EXIF rotation applied, because
 * BitmapFactory ignores it), Bitmap.compress to encode. WebP lossy is used (about 25-35% smaller than JPEG at the same look);
 * both ends of a mesh chat are this app, and BitmapFactory.decodeByteArray reads WebP and JPEG alike. Set useJpeg for other receivers.
 *
 * NOT COMPILED OR TRIED ON A DEVICE (no Android SDK in the build here). The API calls used are standard, but check on a real phone:
 * rotation on several camera apps, memory with a 50 MP photo (the decode is sub-sampled to maxDecodeSide), and encode time.
 */
public final class AndroidImageCodec implements ImageShrinker.Codec {
    private final boolean useJpeg;
    private final int maxDecodeSide;

    public AndroidImageCodec(boolean useJpeg, int maxDecodeSide) { this.useJpeg = useJpeg; this.maxDecodeSide = Math.max(512, maxDecodeSide); }

    public AndroidImageCodec() { this(false, 3200); }

    @Override public Raster decode(byte[] data) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxDecodeSide) sample *= 2;    // keeps at least maxDecodeSide
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            d.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, d);
            if (b == null) return null;
            int deg = rotation(data);
            if (deg != 0) {
                Matrix m = new Matrix();
                m.postRotate(deg);
                Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                if (r != b) b.recycle();
                b = r;
            }
            int w = b.getWidth(), h = b.getHeight();
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            b.recycle();
            for (int i = 0; i < px.length; i++) px[i] &= 0xFFFFFF;
            return new Raster(w, h, px);
        } catch (RuntimeException | OutOfMemoryError e) { return null; }
    }

    @SuppressWarnings("deprecation")
    @Override public byte[] encode(Raster r, int quality) {
        Bitmap b = Bitmap.createBitmap(r.rgb, r.w, r.h, Bitmap.Config.ARGB_8888);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Bitmap.CompressFormat f = useJpeg ? Bitmap.CompressFormat.JPEG
                    : Build.VERSION.SDK_INT >= 30 ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
            b.compress(f, Math.max(1, Math.min(100, quality)), out);
            return out.toByteArray();
        } finally { b.recycle(); }
    }

    private static int rotation(byte[] data) {
        try {
            if (Build.VERSION.SDK_INT < 24) return 0;
            ExifInterface e = new ExifInterface(new ByteArrayInputStream(data));
            switch (e.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (java.io.IOException | RuntimeException e) { return 0; }
    }
}
