package io.github.syldegil.autotunnel;

import java.util.*;
import static io.github.syldegil.autotunnel.NetworkSnapshot.*;

/** Symbolic kernel for UI/tests. Never executes a process; no evidence escapes into real qualification. */
public final class SimulatedNetworkBackend implements NetworkBackend {
    public NetworkSnapshot snapshot;
    public boolean proof = true;
    public int failAtMutation = -1, mutationCount;
    public boolean refuseReadback, failUndo, failClose;
    public final List<String> trace = new ArrayList<>();
    private final Set<NetworkPlan.Operation> objects = new LinkedHashSet<>();
    private final Map<String, List<String>> gates = new HashMap<>();
    public final Set<String> foreignObjects = new HashSet<>(Arrays.asList("netd:rule12000", "third-party:example", "vendor:tc"));
    public SimulatedNetworkBackend(long now) { snapshot = fixture(now, true); }
    public static NetworkSnapshot fixture(long now, boolean ipv6) {
        return new NetworkSnapshot("sim-boot", "sim-generation1", now, true, true, Arrays.asList(
            new Link("simup0", 2, Role.MOBILE_UPSTREAM, "192.0.2.1/24", "2001:db8:1::1/64"),
            new Link("simvpn0", 7, Role.VPN, "192.0.2.2/32", ipv6 ? "2001:db8:2::2/128" : ""),
            new Link("simwifi0", 10, Role.WIFI_CLIENTS, "198.18.1.1/24", ipv6 ? "2001:db8:3::1/64" : ""),
            new Link("simusb0", 11, Role.USB_CLIENTS, "198.18.2.1/24", ipv6 ? "2001:db8:4::1/64" : "")),
            EnumSet.allOf(Capability.class), "203.0.113.7", 51820, 10123,
            Collections.singletonList("0.0.0.0/0"), ipv6 ? Collections.singletonList("::/0") : Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList(), Collections.singletonList("192.0.2.53"));
    }
    public NetworkSnapshot observe() { return snapshot; }
    public boolean mutationsEnabled() { return true; }
    public boolean simulated() { return true; }
    public void preflight(NetworkPlan plan) {
        if (!objects.isEmpty()) throw new IllegalStateException("Existing owned state");
        trace.add("PREFLIGHT");
    }
    public void execute(NetworkPlan.Operation op) {
        trace.add(op.id); mutationCount++;
        if (mutationCount == failAtMutation) throw new IllegalStateException("Injected failure");
        if (op.apply.contains("-R")) {
            String chain = op.apply.get(op.apply.indexOf("-R") + 1);
            boolean exists = objects.stream().anyMatch(o -> o.tool == op.tool && o.apply.contains("-N") && o.apply.contains(chain));
            if (!exists || failClose && op.apply.contains("DROP")) throw new IllegalStateException("Guard missing");
            gates.put(slot(op), op.expected);
        } else objects.add(op);
    }
    public void undo(NetworkPlan.Operation op) {
        if (failUndo) throw new IllegalStateException("Injected undo failure");
        trace.add("UNDO:" + op.id); objects.remove(op);
        if (op.apply.contains("-N")) {
            String prefix = op.tool + ":" + op.apply.get(op.apply.indexOf("-N") + 1) + ":";
            gates.keySet().removeIf(key -> key.startsWith(prefix));
        } else if (op.check == NetworkPlan.Check.RULE_AT_INDEX) gates.remove(slot(op));
    }
    public boolean present(NetworkPlan.Operation op) {
        if (refuseReadback) throw new IllegalStateException("Read denied");
        if (op.apply.contains("-R")) return op.expected.equals(gates.get(slot(op)));
        if (!objects.contains(op)) return false;
        if (op.check == NetworkPlan.Check.RULE_AT_INDEX && gates.containsKey(slot(op)))
            return op.expected.equals(gates.get(slot(op)));
        return true;
    }
    public boolean pathVerified(NetworkPlan plan, long now) {
        return proof && snapshot.tunnelUp && snapshot.fresh(now) && snapshot.identity().equals(plan.snapshot.identity());
    }
    public void drainOwnedFlows(NetworkPlan plan) { trace.add("DRAIN_SELECTED_ONLY"); }
    private String slot(NetworkPlan.Operation op) {
        return op.tool + ":" + op.expected.get(1) + ":" + op.position;
    }
    public boolean closed() { return !gates.isEmpty() && gates.values().stream().allMatch(rule -> rule.get(rule.size() - 1).equals("DROP")); }
    public int ownedCount() { return objects.size(); }
    public void removeForTest(NetworkPlan.Operation op) { objects.remove(op); }
    public void adoptOwned(NetworkPlan plan, Set<String> journalIds) { trace.add("ADOPT_ROOT_JOURNAL"); }
}
