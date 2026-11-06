package io.github.syldegil.autotunnel;

import java.net.InetAddress;
import java.nio.ByteOrder;
import java.util.*;

/** Metadata only. No payload, key, host name lookup or inference of end-to-end security. */
public final class InspectorData {
    public enum Confidence { CONFIGURED, CALCULATED, PARTIALLY_OBSERVED, VERIFIED, BLOCKED }
    public static final class Connection {
        public final String protocol, source, destination, state;
        public final int sourcePort, destinationPort, uid;
        public Connection(String protocol, String source, int sourcePort, String destination, int destinationPort, String state, int uid) {
            this.protocol = protocol; this.source = source; this.sourcePort = sourcePort; this.destination = destination;
            this.destinationPort = destinationPort; this.state = state; this.uid = uid;
        }
        public String label() { return protocol + " UID=" + uid + " " + endpoint(source, sourcePort) + " ↔ " + endpoint(destination, destinationPort) + " état=" + state; }
        private static String endpoint(String ip, int port) { return (ip.contains(":") ? "[" + ip + "]" : ip) + ":" + port; }
    }
    public static List<Connection> connections(String text, boolean ipv6, boolean udp) {
        List<Connection> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (out.size() >= 128) break;
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 10 || !fields[0].matches("[0-9]+:")) continue;
            try {
                String[] from = fields[1].split(":"), to = fields[2].split(":");
                int size = ipv6 ? 32 : 8;
                if (from.length != 2 || to.length != 2 || from[0].length() != size || to[0].length() != size
                    || !from[1].matches("[0-9A-Fa-f]{4}") || !to[1].matches("[0-9A-Fa-f]{4}")
                    || !fields[3].matches("[0-9A-Fa-f]{2}") || !fields[7].matches("[0-9]{1,9}")) continue;
                out.add(new Connection((udp ? "UDP" : "TCP") + (ipv6 ? "6" : "4"), decode(from[0]), Integer.parseInt(from[1], 16),
                    decode(to[0]), Integer.parseInt(to[1], 16), fields[3], Integer.parseInt(fields[7])));
            } catch (Exception ignored) { /* unknown row is not a fabricated connection */ }
        }
        return out;
    }
    private static String decode(String hex) throws Exception {
        if (!hex.matches("[0-9A-Fa-f]{8}|[0-9A-Fa-f]{32}")) throw new IllegalArgumentException();
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int index = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? i / 4 * 4 + 3 - i % 4 : i;
            bytes[index] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return InetAddress.getByAddress(bytes).getHostAddress();
    }
    public static Map<String, long[]> interfaces(String text) {
        Map<String, long[]> result = new TreeMap<>();
        for (String line : text.split("\n")) {
            String[] parts = line.trim().split(":", 2); if (parts.length != 2) continue;
            try {
                RoutingProfile.interfaceName(parts[0]); String[] fields = parts[1].trim().split("\\s+");
                long rx = Long.parseLong(fields[0]), tx = Long.parseLong(fields[8]); if (rx < 0 || tx < 0) continue;
                result.put(parts[0], new long[]{rx, tx});
            } catch (Exception ignored) { }
        }
        return result;
    }
    public static long rate(long previous, long current, long elapsedMillis, boolean sameIdentity) {
        if (!sameIdentity || previous < 0 || current < previous || elapsedMillis <= 0 || elapsedMillis > 15000) return -1;
        double rate = (current - previous) * 1000.0 / elapsedMillis;
        return rate > Long.MAX_VALUE ? -1 : (long) rate;
    }
    public static boolean validCounters(long rx, long tx) { return rx >= 0 && tx >= 0; }
    public static Map<String, Long> familyOctets(String ipv4, String ipv6) {
        Map<String, Long> result = new TreeMap<>();
        String[] lines = ipv4.split("\n");
        for (int i = 0; i + 1 < lines.length; i++) if (lines[i].startsWith("IpExt:") && lines[i + 1].startsWith("IpExt:")) {
            String[] names = lines[i].trim().split("\\s+"), values = lines[++i].trim().split("\\s+");
            for (int j = 1; j < Math.min(names.length, values.length); j++)
                if (names[j].equals("InOctets") || names[j].equals("OutOctets")) putCounter(result, "IPv4-" + names[j], values[j]);
        }
        for (String line : ipv6.split("\n")) {
            String[] f = line.trim().split("\\s+"); if (f.length == 2 && (f[0].equals("Ip6InOctets") || f[0].equals("Ip6OutOctets"))) putCounter(result, f[0], f[1]);
        }
        return result;
    }
    private static void putCounter(Map<String, Long> map, String key, String value) {
        try { long n = Long.parseLong(value); if (n >= 0) map.put(key, n); } catch (NumberFormatException ignored) { }
    }
}
