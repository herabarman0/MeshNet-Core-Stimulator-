package com.herabarman.meshnet.kit;

import com.herabarman.meshnet.core.image.ImageShrinker;
import com.herabarman.meshnet.core.image.Raster;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Desktop/server JPEG codec on ImageIO (progressive, optimised Huffman tables). NOT for Android (no java.desktop there): see AndroidImageCodec. */
public final class JdkJpegCodec implements ImageShrinker.Codec {
    static { System.setProperty("java.awt.headless", "true"); }

    @Override public Raster decode(byte[] data) {
        try {
            BufferedImage b = ImageIO.read(new ByteArrayInputStream(data));
            if (b == null) return null;
            int w = b.getWidth(), h = b.getHeight();
            int[] px = new int[w * h];
            b.getRGB(0, 0, w, h, px, 0, w);
            for (int i = 0; i < px.length; i++) px[i] &= 0xFFFFFF;
            return new Raster(w, h, px);
        } catch (IOException | RuntimeException e) { return null; }
    }

    @Override public byte[] encode(Raster r, int quality) {
        try {
            BufferedImage b = new BufferedImage(r.w, r.h, BufferedImage.TYPE_INT_RGB);
            b.setRGB(0, 0, r.w, r.h, r.rgb, 0, r.w);
            ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
            JPEGImageWriteParam p = (JPEGImageWriteParam) w.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(Math.max(1, Math.min(100, quality)) / 100f);
            p.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            p.setOptimizeHuffmanTables(true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out);
            w.setOutput(ios);
            w.write(null, new IIOImage(b, null, null), p);
            ios.close();
            w.dispose();
            return out.toByteArray();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
}
