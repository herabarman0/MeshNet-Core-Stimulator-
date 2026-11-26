package com.herabarman.meshnet.kit;

/**
 * Turns one block of microphone samples into bytes for the mesh and back. One block = one mesh voice packet
 * (for example 60 ms), and every block must decode on its own, so a lost packet never damages the next one.
 * An instance keeps state for concealment, so use one instance per sender when decoding (see {@link Factory}).
 */
public interface VoiceCodec {
    /** Sends a small id in every packet, so the receiver picks the right decoder. Unique per codec. */
    int id();

    /** Samples per second of the PCM this codec takes and gives (mono, 16 bit). */
    int sampleRate();

    /** PCM samples in one block (sampleRate * block length). */
    int blockSamples();

    /** @param pcm exactly blockSamples() samples */
    byte[] encode(short[] pcm);

    /** @return blockSamples() samples, or null if the bytes are not a valid block */
    short[] decode(byte[] block);

    /** The block to play when a packet was lost: a faded repeat of the last good one, then silence. */
    short[] conceal();

    /** Makes encoder / decoder instances. */
    interface Factory {
        int id();

        VoiceCodec create();
    }
}
