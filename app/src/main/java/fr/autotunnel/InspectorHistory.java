package io.github.syldegil.autotunnel;

import java.util.*;

/** Bounded, monotonic, per-interface history. Missing/reset samples are gaps, never zero traffic. */
final class InspectorHistory {
    static final long WINDOW = 900000;
    static final int LIMIT = 301;
    static final class Point {
        final long elapsed, rx, tx;
        Point(long elapsed, long rx, long tx) { this.elapsed = elapsed; this.rx = rx; this.tx = tx; }
    }
    private final Map<String, List<Point>> series = new TreeMap<>();
    private Map<String, long[]> previous = new TreeMap<>();
    private Map<String, String> identities = new TreeMap<>();
    private long last = -1;
    void clear() { series.clear(); previous.clear(); identities.clear(); last = -1; }
    void add(long elapsed, Map<String, long[]> counters, Map<String, String> identity) {
        if (elapsed <= last) return;
        Set<String> names = new TreeSet<>(series.keySet()); names.addAll(counters.keySet());
        for (String name : names) {
            long[] now = counters.get(name), old = previous.get(name);
            boolean same = identity.get(name) != null && Objects.equals(identity.get(name), identities.get(name));
            long rx = now == null || old == null ? -1 : InspectorData.rate(old[0], now[0], elapsed - last, same);
            long tx = now == null || old == null ? -1 : InspectorData.rate(old[1], now[1], elapsed - last, same);
            List<Point> points = series.computeIfAbsent(name, key -> new ArrayList<>());
            points.add(new Point(elapsed, rx, tx));
            while (!points.isEmpty() && (points.size() > LIMIT || elapsed - points.get(0).elapsed > WINDOW)) points.remove(0);
        }
        // Disappeared interfaces have no indefinitely retained history or identity.
        series.entrySet().removeIf(e -> !counters.containsKey(e.getKey()) && e.getValue().stream().allMatch(p -> p.rx < 0 && p.tx < 0));
        previous = new TreeMap<>(); counters.forEach((key, value) -> previous.put(key, value.clone()));
        identities = new TreeMap<>(identity); last = elapsed;
    }
    List<Point> points(String name, long window) {
        List<Point> out = new ArrayList<>();
        for (Point point : series.getOrDefault(name, Collections.emptyList())) if (last - point.elapsed <= window) out.add(point);
        return Collections.unmodifiableList(out);
    }
    static String rate(long bytes) {
        if (bytes < 0) return "Indisponible";
        if (bytes < 1000) return bytes + " o/s";
        if (bytes < 1000000) return String.format(Locale.FRANCE, "%.1f ko/s", bytes / 1000.0);
        return String.format(Locale.FRANCE, "%.2f Mo/s", bytes / 1000000.0);
    }
}
