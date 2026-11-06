package io.github.syldegil.autotunnel;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/** Framed one-request sessions, random server challenge, OS-authenticated peer UID. */
public final class PrivilegedProtocol {
    public static final int VERSION = 1, LIMIT = 65536;
    public enum Operation { QUALIFY, PREVIEW, APPLY, CLOSE, RESTORE }
    private PrivilegedProtocol() { }
    public static boolean observationAllows(Operation operation) { return operation == Operation.QUALIFY; }
    public static void requireObservationFields(java.util.Set<String> fields) {
        if (!fields.equals(new java.util.HashSet<>(java.util.Arrays.asList("version", "sequence", "challenge", "operation", "probe"))))
            throw new SecurityException("Unexpected observation fields");
    }
    public static boolean previewAllows(Operation operation) {
        return observationAllows(operation) || operation == Operation.PREVIEW;
    }
    public static void requirePreviewFields(java.util.Set<String> fields) {
        if (!fields.equals(new java.util.HashSet<>(java.util.Arrays.asList("version", "sequence", "challenge", "operation", "profile"))))
            throw new SecurityException("Unexpected preview fields");
    }
    public static boolean authorized(int peerUid, int provisionedUid) {
        return peerUid == 0 || provisionedUid >= 10000 && peerUid == provisionedUid;
    }
    public static String challenge() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    public static void authenticate(String supplied, String challenge, long sequence, int version) {
        if (version != VERSION || sequence != 1 || !challenge.equals(supplied)) throw new SecurityException("Invalid session");
    }
    public static String read(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream); int length = in.readInt();
        if (length < 1 || length > LIMIT) throw new IOException("Frame size");
        byte[] bytes = new byte[length]; in.readFully(bytes); return new String(bytes, StandardCharsets.UTF_8);
    }
    public static void write(OutputStream stream, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 1 || bytes.length > LIMIT) throw new IOException("Frame size");
        DataOutputStream out = new DataOutputStream(stream); out.writeInt(bytes.length); out.write(bytes); out.flush();
    }
}
