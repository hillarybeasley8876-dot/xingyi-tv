package xyz.erapi0neer.xingyi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/** Only digests and a random nonce survive the bootstrap. Cleartext arrives via tools/call. */
final class HandoffBinding {
    private static final SecureRandom RANDOM = new SecureRandom();
    final String requestId, nonce;
    private final String textDigest;
    private boolean claimed;
    HandoffBinding(String requestId,String textDigest) {
        if (!hex64(requestId) || !hex64(textDigest)) throw new IllegalArgumentException("无效的接力凭证");
        this.requestId = requestId;
        this.textDigest = textDigest;
        this.nonce = random();
    }
    synchronized boolean claim(String id,String text,String n) {
        if (claimed || !equal(requestId,id) || !equal(nonce,n) || SharedTextDraft.text(text) == null || !equal(textDigest,digest(text))) return false;
        claimed = true;
        return true;
    }
    static String random() { byte[] b = new byte[32]; RANDOM.nextBytes(b); return hex(b); }
    static boolean hex64(String s) { return s != null && s.matches("[a-f0-9]{64}"); }
    static boolean equal(String a,String b) {
        return a != null && hex64(b) && MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII),b.getBytes(StandardCharsets.US_ASCII));
    }
    static String digest(String s) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder(bytes.length*2);
        for (byte value:bytes) b.append(Character.forDigit((value >>> 4)&15,16)).append(Character.forDigit(value&15,16));
        return b.toString();
    }
}
