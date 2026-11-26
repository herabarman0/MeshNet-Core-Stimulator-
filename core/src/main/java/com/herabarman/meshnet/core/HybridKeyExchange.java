package com.herabarman.meshnet.core;

/**
 * Post-Quantum Hybrid Key Exchange: combines RFC 7748 X25519 ECDH with FIPS 203 ML-KEM-768 (Kyber-768).
 * Even if either X25519 is broken by Shor's algorithm on a future quantum computer OR ML-KEM encounters a
 * classical flaw, the derived 32-byte AES-256-GCM session key remains secure.
 */
public final class HybridKeyExchange {
    private static final byte[] DOMAIN = "meshnet-pq-hybrid-x25519-mlkem768-v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private HybridKeyExchange() {}

    public static final class HybridIdentity {
        public final byte[] x25519Priv; // 32B
        public final byte[] x25519Pub;  // 32B
        public final byte[] mlKemEk;    // 1184B
        public final byte[] mlKemDk;    // 2400B

        HybridIdentity(byte[] x25519Priv, byte[] x25519Pub, byte[] mlKemEk, byte[] mlKemDk) {
            this.x25519Priv = x25519Priv;
            this.x25519Pub = x25519Pub;
            this.mlKemEk = mlKemEk;
            this.mlKemDk = mlKemDk;
        }
    }

    public static final class InitiatorOffer {
        public final byte[] ciphertext;   // 1088B ML-KEM ciphertext
        public final byte[] sessionKey;   // 32B hybrid AES-256 key

        InitiatorOffer(byte[] ciphertext, byte[] sessionKey) {
            this.ciphertext = ciphertext;
            this.sessionKey = sessionKey;
        }
    }

    public static HybridIdentity generateIdentity() {
        byte[][] x = KeyExchange.generateRawKeyPair();
        MlKem768.KeyPair pq = MlKem768.keygen();
        return new HybridIdentity(x[0], x[1], pq.ek, pq.dk);
    }

    /** Initiator side: derives X25519 shared secret + encapsulates against responder's ML-KEM-768 ek. */
    public static InitiatorOffer initiate(HybridIdentity initiator, byte[] responderX25519Pub, byte[] responderMlKemEk) {
        byte[] ssClassical = X25519.scalarMult(initiator.x25519Priv, responderX25519Pub);
        if (X25519.isAllZero(ssClassical)) throw new IllegalArgumentException("invalid peer X25519 key");
        MlKem768.Encapsulation enc = MlKem768.encapsulate(responderMlKemEk);
        byte[] key = combineSecrets(ssClassical, enc.sharedSecret, initiator.x25519Pub, responderX25519Pub, responderMlKemEk);
        return new InitiatorOffer(enc.ciphertext, key);
    }

    /** Responder side: derives X25519 shared secret + decapsulates ML-KEM-768 ciphertext. */
    public static byte[] respond(HybridIdentity responder, byte[] initiatorX25519Pub, byte[] mlKemCiphertext) {
        byte[] ssClassical = X25519.scalarMult(responder.x25519Priv, initiatorX25519Pub);
        if (X25519.isAllZero(ssClassical)) throw new IllegalArgumentException("invalid peer X25519 key");
        byte[] ssPq = MlKem768.decapsulate(responder.mlKemDk, mlKemCiphertext);
        return combineSecrets(ssClassical, ssPq, initiatorX25519Pub, responder.x25519Pub, responder.mlKemEk);
    }

    private static byte[] combineSecrets(byte[] ssX, byte[] ssPq, byte[] pubInit, byte[] pubResp, byte[] ekResp) {
        byte[] ekHash = Keccak.sha3_256(ekResp);
        byte[] buf = new byte[DOMAIN.length + 32 + 32 + 32 + 32 + 32];
        int pos = 0;
        System.arraycopy(DOMAIN, 0, buf, pos, DOMAIN.length); pos += DOMAIN.length;
        System.arraycopy(ssX, 0, buf, pos, 32); pos += 32;
        System.arraycopy(ssPq, 0, buf, pos, 32); pos += 32;
        System.arraycopy(pubInit, 0, buf, pos, 32); pos += 32;
        System.arraycopy(pubResp, 0, buf, pos, 32); pos += 32;
        System.arraycopy(ekHash, 0, buf, pos, 32);
        return Keccak.sha3_256(buf);
    }
}
