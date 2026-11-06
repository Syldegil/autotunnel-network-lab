package io.github.syldegil.autotunnel;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local metadata only. A successful read is never a capability or data-path qualification. */
public final class RootInspection {
    static final int INPUT_LIMIT = 32768, SOCKET_LIMIT = 64, INTERFACE_LIMIT = 128, PAYLOAD_LIMIT = 24576;
    public static final String SOURCE = "procfs root · lecture ponctuelle";
    private static volatile Snapshot latest;
    public static Snapshot latest() { return latest; }
    static void publish(Snapshot value) { latest = value; }
    private RootInspection() { }

    static String path(NetworkDiscovery.Probe probe) {
        switch (probe) {
            case LINK_COUNTERS: return "/proc/net/dev";
            case SOCKETS4: return "/proc/net/tcp";
            case SOCKETS6: return "/proc/net/tcp6";
            case UDP4: return "/proc/net/udp";
            case UDP6: return "/proc/net/udp6";
            case IP_STATS4: return "/proc/net/netstat";
            case IP_STATS6: return "/proc/net/snmp6";
            default: throw new IllegalArgumentException("Unsupported inspector probe");
        }
    }
    static boolean supports(NetworkDiscovery.Probe probe) {
        try { path(probe); return true; } catch (IllegalArgumentException e) { return false; }
    }
    public static final class Snapshot {
        public final NetworkDiscovery.Probe probe;
        public final long at;
        public final String status, error;
        public final boolean truncated;
        public final int rejectedRows;
        public final Map<String, long[]> counters;
        public final Map<String, Long> families;
        public final List<InspectorData.Connection> connections;
        Snapshot(NetworkDiscovery.Probe probe, long at, String status, String error, boolean truncated, int rejectedRows,
                 Map<String, long[]> counters, Map<String, Long> families, List<InspectorData.Connection> connections) {
            this.probe = probe; this.at = at; this.status = status; this.error = error;
            this.truncated = truncated; this.rejectedRows = rejectedRows;
            this.counters = Collections.unmodifiableMap(new TreeMap<>(counters));
            this.families = Collections.unmodifiableMap(new TreeMap<>(families));
            this.connections = Collections.unmodifiableList(new ArrayList<>(connections));
        }
        public String describe() {
            StringBuilder out = new StringBuilder(SOURCE).append(" · ").append(new Date(at)).append('\n')
                .append(probe).append(' ').append(path(probe)).append(" : ").append(status)
                .append("\nErreur : ").append(error.isEmpty() ? "aucune" : error)
                .append("\nTronqué : ").append(truncated).append(" ; lignes rejetées : ").append(rejectedRows);
            for (Map.Entry<String, long[]> e : counters.entrySet()) out.append('\n').append(e.getKey()).append(" RX/TX octets=")
                .append(e.getValue()[0]).append('/').append(e.getValue()[1]).append(" ; débit inconnu (ponctuel)");
            if (!families.isEmpty()) out.append("\nOctets IP globaux : ").append(families);
            for (InspectorData.Connection c : connections) out.append('\n').append(c.label());
            return out.append("\nLecture locale partielle, aucun trajet attribué, aucune capacité qualifiée. Autres sondes NON OBSERVÉES.").toString();
        }
    }
    static Snapshot read(NetworkDiscovery.Probe probe) {
        String file = path(probe); long at = System.currentTimeMillis();
        try (InputStream in = new FileInputStream(file)) { return parse(probe, at, readBounded(in)); }
        catch (Exception e) { return new Snapshot(probe, at, "ERROR", error(e), e instanceof LimitException, 0,
            Collections.emptyMap(), Collections.emptyMap(), Collections.emptyList()); }
    }
    private static final class LimitException extends IOException {
        LimitException() { super("Procfs input exceeds 32768 bytes; no partial data accepted"); }
    }
    static String readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
        while ((n = in.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Root observation cancelled");
            if (out.size() + n > INPUT_LIMIT) throw new LimitException();
            out.write(buffer, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
    static String error(Throwable e) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; e != null && i < 4; i++, e = e.getCause()) {
            if (i > 0) out.append("; caused by ");
            out.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
        }
        return out.length() <= 2048 ? out.toString() : out.substring(0, 2000) + " [error text truncated]";
    }
    static Snapshot parse(NetworkDiscovery.Probe probe, long at, String text) {
        path(probe);
        if (text.getBytes(StandardCharsets.UTF_8).length > INPUT_LIMIT) throw new IllegalArgumentException("Input limit");
        Map<String, long[]> counters = new TreeMap<>(); Map<String, Long> families = new TreeMap<>();
        List<InspectorData.Connection> connections = new ArrayList<>(); int rejected = 0; boolean truncated = false;
        if (probe == NetworkDiscovery.Probe.LINK_COUNTERS) {
            for (String line : text.split("\n")) {
                if (line.trim().isEmpty() || line.contains("|") && !line.contains(":")) continue;
                Map<String, long[]> row = InspectorData.interfaces(line);
                if (row.size() != 1 || !line.substring(line.indexOf(':') + 1).trim().matches("[0-9]+(?:\\s+[0-9]+){15}")) { rejected++; continue; }
                if (counters.size() >= INTERFACE_LIMIT) { truncated = true; continue; }
                for (String name : row.keySet()) if (counters.containsKey(name)) rejected++;
                counters.putAll(row);
            }
        } else if (probe == NetworkDiscovery.Probe.IP_STATS4 || probe == NetworkDiscovery.Probe.IP_STATS6) {
            families.putAll(InspectorData.familyOctets(probe == NetworkDiscovery.Probe.IP_STATS4 ? text : "", probe == NetworkDiscovery.Probe.IP_STATS6 ? text : ""));
            if (families.size() != 2) rejected++;
            if (probe == NetworkDiscovery.Probe.IP_STATS4) {
                String[] lines = text.split("\n"); int sections = 0;
                for (int i = 0; i < lines.length; i++) if (lines[i].startsWith("IpExt:")) {
                    sections++;
                    if (i + 1 >= lines.length || !lines[i + 1].startsWith("IpExt:")) { rejected++; continue; }
                    String[] names = lines[i].trim().split("\\s+"), values = lines[++i].trim().split("\\s+");
                    if (names.length != values.length || Collections.frequency(Arrays.asList(names), "InOctets") != 1
                        || Collections.frequency(Arrays.asList(names), "OutOctets") != 1) rejected++;
                }
                if (sections != 1) rejected++;
            } else {
                for (String name : Arrays.asList("Ip6InOctets", "Ip6OutOctets")) {
                    int count = 0;
                    for (String line : text.split("\n")) if (line.trim().split("\\s+")[0].equals(name)) count++;
                    if (count != 1) rejected++;
                }
            }
        } else {
            boolean header = false;
            for (String line : text.split("\n")) {
                if (line.trim().isEmpty()) continue;
                if (line.trim().matches("sl\\s+local_address\\s+(?:rem|remote)_address\\s+st\\s+.*")) { header = true; continue; }
                List<InspectorData.Connection> row = InspectorData.connections(line, probe.name().endsWith("6"), probe.name().startsWith("UDP"));
                if (row.size() != 1) { rejected++; continue; }
                if (connections.size() >= SOCKET_LIMIT) { truncated = true; continue; }
                connections.add(row.get(0));
            }
            if (!header) rejected++;
        }
        if (text.trim().isEmpty()) rejected++;
        String error = rejected == 0 ? "" : "Unparsed/missing procfs rows or required counters: " + rejected;
        return new Snapshot(probe, at, rejected > 0 || truncated ? "PARTIAL" : "READ_OK", error, truncated, rejected, counters, families, connections);
    }
    static JSONObject encode(Snapshot s) throws Exception {
        JSONObject counters = new JSONObject(), families = new JSONObject(); JSONArray sockets = new JSONArray();
        for (Map.Entry<String, long[]> e : s.counters.entrySet()) counters.put(e.getKey(), new JSONArray().put(e.getValue()[0]).put(e.getValue()[1]));
        for (Map.Entry<String, Long> e : s.families.entrySet()) families.put(e.getKey(), e.getValue());
        for (InspectorData.Connection c : s.connections) sockets.put(new JSONObject().put("protocol", c.protocol).put("source", c.source)
            .put("sourcePort", c.sourcePort).put("destination", c.destination).put("destinationPort", c.destinationPort).put("state", c.state).put("uid", c.uid));
        JSONObject json = new JSONObject().put("version", 1).put("probe", s.probe.name()).put("at", s.at).put("status", s.status)
            .put("error", s.error).put("truncated", s.truncated).put("rejectedRows", s.rejectedRows)
            .put("counters", counters).put("families", families).put("sockets", sockets);
        if (json.toString().getBytes(StandardCharsets.UTF_8).length > PAYLOAD_LIMIT) throw new IOException("Inspection payload limit");
        return json;
    }
    static Snapshot decode(JSONObject json, NetworkDiscovery.Probe expected) throws Exception {
        if (json.toString().getBytes(StandardCharsets.UTF_8).length > PAYLOAD_LIMIT) throw new IOException("Inspection payload limit");
        requireKeys(json, "version", "probe", "at", "status", "error", "truncated", "rejectedRows", "counters", "families", "sockets");
        if (json.getInt("version") != 1 || !expected.name().equals(json.getString("probe"))) throw new IOException("Inspection identity mismatch");
        path(expected);
        String status = json.getString("status"), error = json.getString("error");
        if (!Arrays.asList("READ_OK", "PARTIAL", "ERROR").contains(status) || error.length() > 2048) throw new IOException("Inspection status");
        long at = nonnegative(json.getLong("at")); int rejected = json.getInt("rejectedRows");
        if (rejected < 0 || rejected > INPUT_LIMIT) throw new IOException("Rejected row count");
        Map<String, long[]> counters = new TreeMap<>(); Map<String, Long> families = new TreeMap<>(); List<InspectorData.Connection> sockets = new ArrayList<>();
        JSONObject c = json.getJSONObject("counters"), f = json.getJSONObject("families"); JSONArray a = json.getJSONArray("sockets");
        if (c.length() > INTERFACE_LIMIT || f.length() > 2 || a.length() > SOCKET_LIMIT) throw new IOException("Inspection row limit");
        for (Iterator<String> i = c.keys(); i.hasNext();) {
            String name = i.next(); RoutingProfile.interfaceName(name); JSONArray pair = c.getJSONArray(name);
            if (expected != NetworkDiscovery.Probe.LINK_COUNTERS || pair.length() != 2) throw new IOException("Counter shape");
            counters.put(name, new long[]{nonnegative(pair.getLong(0)), nonnegative(pair.getLong(1))});
        }
        for (Iterator<String> i = f.keys(); i.hasNext();) {
            String name = i.next();
            if (!(expected == NetworkDiscovery.Probe.IP_STATS4 && Arrays.asList("IPv4-InOctets", "IPv4-OutOctets").contains(name))
                && !(expected == NetworkDiscovery.Probe.IP_STATS6 && Arrays.asList("Ip6InOctets", "Ip6OutOctets").contains(name))) throw new IOException("Family mismatch");
            families.put(name, nonnegative(f.getLong(name)));
        }
        for (int i = 0; i < a.length(); i++) {
            JSONObject row = a.getJSONObject(i); requireKeys(row, "protocol", "source", "sourcePort", "destination", "destinationPort", "state", "uid");
            String protocol = row.getString("protocol"), from = row.getString("source"), to = row.getString("destination"), state = row.getString("state");
            String wanted = (expected.name().startsWith("UDP") ? "UDP" : "TCP") + (expected.name().endsWith("6") ? "6" : "4");
            if (!Arrays.asList(NetworkDiscovery.Probe.SOCKETS4, NetworkDiscovery.Probe.SOCKETS6, NetworkDiscovery.Probe.UDP4, NetworkDiscovery.Probe.UDP6).contains(expected)
                || !protocol.equals(wanted) || !from.matches("[0-9a-fA-F:.]{1,45}") || !to.matches("[0-9a-fA-F:.]{1,45}") || !state.matches("[0-9a-fA-F]{2}")) throw new IOException("Socket metadata");
            int sp = row.getInt("sourcePort"), dp = row.getInt("destinationPort"), uid = row.getInt("uid");
            if (sp < 0 || sp > 65535 || dp < 0 || dp > 65535 || uid < 0) throw new IOException("Socket range");
            sockets.add(new InspectorData.Connection(protocol, from, sp, to, dp, state, uid));
        }
        boolean truncated = json.getBoolean("truncated");
        if (status.equals("READ_OK") && (truncated || rejected != 0 || !error.isEmpty())
            || status.equals("ERROR") && (!counters.isEmpty() || !families.isEmpty() || !sockets.isEmpty() || error.isEmpty())) throw new IOException("Inconsistent inspection status");
        return new Snapshot(expected, at, status, error, truncated, rejected, counters, families, sockets);
    }
    private static long nonnegative(long n) throws IOException { if (n < 0) throw new IOException("Negative counter/time"); return n; }
    private static void requireKeys(JSONObject json, String... names) throws IOException {
        Set<String> actual = new HashSet<>(); for (Iterator<String> i = json.keys(); i.hasNext();) actual.add(i.next());
        if (!actual.equals(new HashSet<>(Arrays.asList(names)))) throw new IOException("Unexpected inspection fields");
    }
}
