package com.herabarman.meshnet.kit;

import com.herabarman.meshnet.core.RecordAuth;

/** Signs gossip / DHT location records with the node's ECDSA identity key. */
public final class IdentityRecordAuth implements RecordAuth {
    private final IdentityKeys keys;

    public IdentityRecordAuth(IdentityKeys keys) { this.keys = keys; }

    @Override public byte[] publicKey() { return keys.publicRaw(); }

    @Override public byte[] sign(byte[] data) { return keys.sign(data); }

    @Override
    public boolean verify(byte[] publicKey, byte[] data, byte[] signature) {
        try {
            return IdentityKeys.verify(publicKey, data, signature);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
