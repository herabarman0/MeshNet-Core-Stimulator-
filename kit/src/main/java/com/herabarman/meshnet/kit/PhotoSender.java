package com.herabarman.meshnet.kit;

import com.herabarman.meshnet.core.image.ImageShrinker;

/**
 * Compresses a photo (off the caller's thread) and sends it with MeshClient.sendBlob. Small pictures are kept as they are when
 * re-encoding would not make them smaller. The byte budget defaults to what the mesh handles comfortably (about 300 KB), never above
 * the blob limit.
 */
public final class PhotoSender {
    public interface Callback {
        /** Compression finished and the blob was queued; result tells what was achieved. blobId is for Listener.onBlobProgress. */
        void onQueued(long blobId, ImageShrinker.Result result);
        void onFailed(String reason);
    }

    private PhotoSender() { }

    public static void sendUltraCompact(final MeshClient client, final String toUser, final byte[] photo, final ImageShrinker.Codec codec, final Callback cb) {
        send(client, toUser, photo, codec, ImageShrinker.Config.ultraCompactVisuallyLossless(), cb);
    }

    /** Synchronous helper for UI previews: returns shrunk bytes + SSIM without altering original unless smaller. */
    public static ImageShrinker.Result compressVisuallyLossless(final byte[] photo, final ImageShrinker.Codec codec, final int maxBytes) {
        ImageShrinker.Config cfg = ImageShrinker.Config.ultraCompactVisuallyLossless();
        if (maxBytes > 0) cfg.maxBytes = maxBytes;
        return new ImageShrinker(codec).shrink(photo, cfg);
    }

    public static void send(final MeshClient client, final String toUser, final byte[] photo, final ImageShrinker.Codec codec, ImageShrinker.Config cfgIn, final Callback cb) {
        final ImageShrinker.Config cfg = cfgIn != null ? cfgIn : new ImageShrinker.Config();
        if (cfg.maxBytes <= 0 || cfg.maxBytes > MeshClient.MAX_BLOB_BYTES) cfg.maxBytes = Math.min(300 * 1024, MeshClient.MAX_BLOB_BYTES);
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ImageShrinker.Result r = new ImageShrinker(codec).shrink(photo, cfg);
                    if (r == null) { if (cb != null) cb.onFailed("not a picture"); return; }
                    if (r.bytes.length > MeshClient.MAX_BLOB_BYTES) { if (cb != null) cb.onFailed("still too large after compression: " + r.bytes.length); return; }
                    long id = client.sendBlob(toUser, r.bytes);
                    if (cb != null) cb.onQueued(id, r);
                } catch (RuntimeException e) {
                    if (cb != null) cb.onFailed(String.valueOf(e.getMessage()));
                }
            }
        }, "mesh-photo-compress");
        t.setDaemon(true);
        t.start();
    }
}
