package com.herabarman.meshnet.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline Mesh Payment & Smart Contract Ledger.
 * Enables two or more phones without internet to exchange cryptographically signed token transfers,
 * generate tamper-evident digital receipts, and execute HTLC (Hash-Time-Locked) Escrow Smart Contracts.
 * Pending receipts are queued locally with monotonic anti-double-spend nonces and Merkle roots,
 * ready for deterministic reconciliation once internet returns.
 */
public final class MeshLedger {
    public enum ContractState { LOCKED, RELEASED, REFUNDED }

    public static final class Tx {
        public final String txId;
        public final int from;
        public final int to;
        public final long amount;
        public final long nonce;
        public final long timestampMs;
        public final String memo;
        public final byte[] senderPubKey;
        public final byte[] signature;
        public final byte[] receiptHash;
        public boolean syncedToServer;

        public Tx(String txId, int from, int to, long amount, long nonce, long timestampMs, String memo,
                  byte[] senderPubKey, byte[] signature, byte[] receiptHash) {
            this.txId = txId;
            this.from = from;
            this.to = to;
            this.amount = amount;
            this.nonce = nonce;
            this.timestampMs = timestampMs;
            this.memo = memo == null ? "" : memo;
            this.senderPubKey = senderPubKey.clone();
            this.signature = signature.clone();
            this.receiptHash = receiptHash.clone();
        }

        public byte[] canonicalBytes() {
            byte[] m = memo.getBytes(StandardCharsets.UTF_8);
            ByteBuffer b = ByteBuffer.allocate(4 + 4 + 8 + 8 + 8 + 2 + m.length);
            b.putInt(from).putInt(to).putLong(amount).putLong(nonce).putLong(timestampMs).putShort((short) m.length).put(m);
            return b.array();
        }
    }

    public static final class SmartContract {
        public final String contractId;
        public final int sender;
        public final int recipient;
        public final long amount;
        public final byte[] hashLockSha3;   // 32B SHA3-256(preimage)
        public final long expiresAtMs;
        public final long createdAtMs;
        public ContractState state = ContractState.LOCKED;
        public boolean syncedToServer;

        SmartContract(String contractId, int sender, int recipient, long amount, byte[] hashLockSha3, long expiresAtMs, long createdAtMs) {
            this.contractId = contractId;
            this.sender = sender;
            this.recipient = recipient;
            this.amount = amount;
            this.hashLockSha3 = hashLockSha3.clone();
            this.expiresAtMs = expiresAtMs;
            this.createdAtMs = createdAtMs;
        }
    }

    private final Clock clock;
    private final RecordAuth auth;
    private final Map<Integer, Long> balances = new HashMap<>();
    private final Map<Integer, Long> lastNonce = new HashMap<>();
    private final Map<Integer, byte[]> publicKeys = new HashMap<>();
    private final Map<String, Tx> transactions = new LinkedHashMap<>();
    private final Map<String, SmartContract> contracts = new LinkedHashMap<>();
    public long rejectedDoubleSpends;
    public long rejectedBadSignatures;

    public MeshLedger(Clock clock, RecordAuth auth) {
        this.clock = clock;
        this.auth = auth;
    }

    public synchronized void registerAccount(int nodeId, byte[] publicKey, long initialBalance) {
        if (initialBalance < 0) throw new IllegalArgumentException("negative balance");
        publicKeys.put(nodeId, publicKey.clone());
        if (!balances.containsKey(nodeId)) balances.put(nodeId, initialBalance);
        if (!lastNonce.containsKey(nodeId)) lastNonce.put(nodeId, 0L);
    }

    public synchronized long balanceOf(int nodeId) {
        Long b = balances.get(nodeId);
        return b == null ? 0L : b;
    }

    public synchronized long nonceOf(int nodeId) {
        Long n = lastNonce.get(nodeId);
        return n == null ? 0L : n;
    }

    /** Create and locally sign a peer-to-peer offline payment using this node's RecordAuth signer. */
    public synchronized Tx createPayment(int from, int to, long amount, String memo, byte[] senderPubKey) {
        if (from == to || amount <= 0) throw new IllegalArgumentException("invalid transfer");
        long nextNonce = nonceOf(from) + 1;
        long now = clock.nowMs();
        byte[] body = canonical(from, to, amount, nextNonce, now, memo);
        byte[] sig = auth.sign(body);
        byte[] receipt = Keccak.sha3_256(concat(body, sig));
        String txId = "TX-" + hexPrefix(receipt, 8);
        Tx tx = new Tx(txId, from, to, amount, nextNonce, now, memo, senderPubKey, sig, receipt);
        if (!applyVerifiedTx(tx)) throw new IllegalStateException("insufficient offline balance");
        return tx;
    }

    /** Verify and apply an incoming payment received over BLE / Wi-Fi / Nearby mesh. */
    public synchronized boolean applyVerifiedTx(Tx tx) {
        if (tx == null || tx.amount <= 0 || tx.from == tx.to) return false;
        if (transactions.containsKey(tx.txId)) return true; // idempotent replay
        long expectedNonce = nonceOf(tx.from) + 1;
        if (tx.nonce != expectedNonce) {
            rejectedDoubleSpends++;
            return false;
        }
        byte[] key = publicKeys.get(tx.from);
        if (key == null) key = tx.senderPubKey;
        if (!auth.verify(key, tx.canonicalBytes(), tx.signature)) {
            rejectedBadSignatures++;
            return false;
        }
        long bal = balanceOf(tx.from);
        if (bal < tx.amount) {
            rejectedDoubleSpends++;
            return false;
        }
        balances.put(tx.from, bal - tx.amount);
        balances.put(tx.to, balanceOf(tx.to) + tx.amount);
        lastNonce.put(tx.from, tx.nonce);
        publicKeys.put(tx.from, key.clone());
        transactions.put(tx.txId, tx);
        return true;
    }

    /** Lock tokens in an offline HTLC Smart Contract released only by a SHA3-256 preimage or refunded after expiry. */
    public synchronized SmartContract createHtlcContract(int sender, int recipient, long amount, byte[] secretPreimage, long ttlMs) {
        if (sender == recipient || amount <= 0 || ttlMs <= 0 || secretPreimage == null) {
            throw new IllegalArgumentException("invalid contract");
        }
        long bal = balanceOf(sender);
        if (bal < amount) throw new IllegalStateException("insufficient balance for escrow lock");
        balances.put(sender, bal - amount);
        long now = clock.nowMs();
        byte[] hashLock = Keccak.sha3_256(secretPreimage);
        byte[] idSeed = Keccak.sha3_256(ByteBuffer.allocate(48).putInt(sender).putInt(recipient).putLong(amount).putLong(now).put(hashLock, 0, 16).array());
        String id = "SC-" + hexPrefix(idSeed, 8);
        SmartContract sc = new SmartContract(id, sender, recipient, amount, hashLock, now + ttlMs, now);
        contracts.put(id, sc);
        return sc;
    }

    /** Claim locked contract funds by presenting the secret preimage (e.g. delivery confirmation code). */
    public synchronized boolean settleHtlc(String contractId, byte[] preimage) {
        SmartContract sc = contracts.get(contractId);
        if (sc == null || sc.state != ContractState.LOCKED || preimage == null) return false;
        if (clock.nowMs() > sc.expiresAtMs) return false;
        byte[] digest = Keccak.sha3_256(preimage);
        if (!Arrays.equals(digest, sc.hashLockSha3)) return false;
        sc.state = ContractState.RELEASED;
        sc.syncedToServer = false;
        balances.put(sc.recipient, balanceOf(sc.recipient) + sc.amount);
        return true;
    }

    /** Refund locked contract funds back to sender once ttl expires. */
    public synchronized boolean refundExpiredHtlc(String contractId) {
        SmartContract sc = contracts.get(contractId);
        if (sc == null || sc.state != ContractState.LOCKED) return false;
        if (clock.nowMs() <= sc.expiresAtMs) return false;
        sc.state = ContractState.REFUNDED;
        sc.syncedToServer = false;
        balances.put(sc.sender, balanceOf(sc.sender) + sc.amount);
        return true;
    }

    public synchronized List<Tx> pendingServerSync() {
        List<Tx> out = new ArrayList<>();
        for (Tx t : transactions.values()) if (!t.syncedToServer) out.add(t);
        return out;
    }

    public synchronized List<SmartContract> allContracts() {
        return new ArrayList<>(contracts.values());
    }

    public synchronized List<Tx> allTransactions() {
        return new ArrayList<>(transactions.values());
    }

    /** Marks offline transactions + settled contracts as reconciled with the online server. */
    public synchronized int syncToServerBatch() {
        int count = 0;
        for (Tx t : transactions.values()) {
            if (!t.syncedToServer) {
                t.syncedToServer = true;
                count++;
            }
        }
        for (SmartContract sc : contracts.values()) {
            if (!sc.syncedToServer && sc.state != ContractState.LOCKED) {
                sc.syncedToServer = true;
                count++;
            }
        }
        return count;
    }

    /** Merkle root over all local digital receipts for instant server audit. */
    public synchronized byte[] ledgerRootHash() {
        if (transactions.isEmpty()) return new byte[32];
        byte[] acc = new byte[32];
        for (Tx t : transactions.values()) {
            acc = Keccak.sha3_256(concat(acc, t.receiptHash));
        }
        return acc;
    }

    private static byte[] canonical(int from, int to, long amount, long nonce, long ts, String memo) {
        byte[] m = (memo == null ? "" : memo).getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(4 + 4 + 8 + 8 + 8 + 2 + m.length)
                .putInt(from).putInt(to).putLong(amount).putLong(nonce).putLong(ts).putShort((short) m.length).put(m).array();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    public static String hexPrefix(byte[] bytes, int count) {
        StringBuilder sb = new StringBuilder(count * 2);
        int lim = Math.min(bytes.length, count);
        for (int i = 0; i < lim; i++) sb.append(String.format(java.util.Locale.ROOT, "%02x", bytes[i] & 0xff));
        return sb.toString();
    }
}
